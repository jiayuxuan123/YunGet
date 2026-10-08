#!/usr/bin/env python3
"""站点链接检查器。

用户对官网的要求是「所有按键都要正常」—— 那就把它变成一条**可执行的门禁**，
而不是靠肉眼抽查。

## 检查什么

1. **内部链接（硬失败）**：`href` / `src` 解析成文件后必须**真实存在**。
   支持目录式链接（`/download` → `download/index.html`）与锚点（`#section`）。
2. **外部链接（探测，可选）**：**连不上**和**404** 是两件事：
   - 4xx/5xx = 链接坏了 → 计入失败；
   - 网络不通（超时/连接被拒）= "未验证" —— **不**算通过也不算失败。
     这台机器的 `github.com` 就时通时不通，混为一谈会让这条门禁变得不可信。
3. **重复 id**：同一页面里两个 `id="x"` 会让锚点指向不确定的位置。

## 出站边界（本工具自己也是一段"会发请求的代码"，同样要守规矩）

被检查的 URL 来自站点文件，属于**内容可控**而不是绝对可信的输入。因此这里不裸发请求：

- 只允许 `http` / `https`，其余协议直接拒绝；
- **先解析主机名，再校验解析出的每一个 IP** 都是公网地址（拒绝环回 / 私有 /
  链路本地 / 组播 / 保留段 / 未指定地址）—— 这样即使链接里写的是
  `http://127.0.0.1:8787/` 或 `http://169.254.169.254/`（云元数据），也会被拦下，
  而不会变成"用别人的站点当跳板去打本机/内网服务"的工具；
- 重定向**逐跳**重复同样的校验，并限制跳数 —— 否则一次 302 就能绕开上面的检查。

用法：
    python website/check_links.py                 # 只查内部链接（离线可用）
    python website/check_links.py --external      # 再探测外部链接
"""

from __future__ import annotations

import argparse
import ipaddress
import re
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent

ATTR_RE = re.compile(r'(?:href|src)\s*=\s*"([^"]*)"')
ID_RE = re.compile(r'\bid\s*=\s*"([^"]+)"')
SKIP_SCHEMES = ("mailto:", "tel:", "javascript:", "data:")

#: 允许探测的协议。其他（file: / ftp: / gopher: …）一律不碰。
ALLOWED_SCHEMES = ("http", "https")
#: 最多跟随几跳重定向。站点链接不需要更多；跳数越少，越没有绕开校验的空间。
MAX_REDIRECTS = 3


class LinkNotProbeable(Exception):
    """该 URL 不允许被探测（协议不支持，或解析到非公网地址）。"""


def is_public_ip(ip: str) -> bool:
    """这个 IP 是否属于可以访问的公网地址。

    逐类显式排除，而不是只判断 `is_private`：`is_private` 对 100.64.0.0/10（运营商级 NAT）、
    192.0.0.0/24 等保留段并不都返回 true，只靠它会有漏网。
    """
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return not (
        addr.is_private
        or addr.is_loopback
        or addr.is_link_local
        or addr.is_multicast
        or addr.is_reserved
        or addr.is_unspecified
    )


def assert_probeable(url: str) -> None:
    """校验 URL 可以安全探测；不通过就抛 [LinkNotProbeable]。

    **先解析、再校验 IP**，而不是只看主机名字符串：`localhost` 能解析到环回，
    而一个公网域名也可能（被恶意配置或被 DNS rebinding）解析到内网地址。
    """
    parts = urllib.parse.urlsplit(url)
    if parts.scheme.lower() not in ALLOWED_SCHEMES:
        raise LinkNotProbeable(f"协议不被允许：{parts.scheme or '(空)'}")
    host = parts.hostname
    if not host:
        raise LinkNotProbeable("没有主机名")
    try:
        infos = socket.getaddrinfo(host, parts.port or (443 if parts.scheme == "https" else 80),
                                   proto=socket.IPPROTO_TCP)
    except socket.gaierror as e:
        raise LinkNotProbeable(f"域名解析失败：{e}") from e
    ips = {info[4][0] for info in infos}
    if not ips:
        raise LinkNotProbeable("域名没有解析出任何地址")
    bad = sorted(ip for ip in ips if not is_public_ip(ip))
    if bad:
        raise LinkNotProbeable(f"解析到非公网地址，拒绝访问：{', '.join(bad)}")


class LimitedRedirects(urllib.request.HTTPRedirectHandler):
    """限制重定向跳数，并**对每一跳重新做同样的边界校验**。

    默认的 `HTTPRedirectHandler` 会一直跟下去：一个被检查的链接只要 302 到
    `http://127.0.0.1:8787/`，请求就打到本机服务上了 —— 上面那次校验形同虚设。
    """

    def __init__(self) -> None:
        self.hops = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        self.hops += 1
        if self.hops > MAX_REDIRECTS:
            raise urllib.error.HTTPError(req.full_url, code, "重定向次数过多", headers, fp)
        assert_probeable(newurl)  # 每一跳都校验
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(LimitedRedirects())


_USER_AGENT = "Mozilla/5.0 (compatible; TurboDL-site-link-check)"


def probe(url: str, timeout: float) -> tuple[str, str]:
    """返回 `(状态, 说明)`，状态取 `ok` / `broken` / `unverified`。"""
    try:
        assert_probeable(url)
    except LinkNotProbeable as e:
        # 不能探测 ≠ 链接坏了：如实报告，交由人判断（例如链接写的就是内网地址，那本身就是问题）
        return ("unverified", f"跳过探测（{e}）")

    op = opener()
    head = urllib.request.Request(url, method="HEAD", headers={"User-Agent": _USER_AGENT})
    get = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT})
    for attempt in (head, get):
        try:
            with op.open(attempt, timeout=timeout) as resp:
                return ("ok", str(resp.status))
        except urllib.error.HTTPError as e:
            # 不少站点不支持 HEAD（403/405/501），换 GET 再试；GET 仍失败才算坏
            if e.code in (403, 405, 501) and attempt.method == "HEAD":
                continue
            return ("broken", f"HTTP {e.code}")
        except urllib.error.URLError as e:
            return ("unverified", f"{e.reason}")
        except (TimeoutError, OSError) as e:
            return ("unverified", f"{e}")
    return ("broken", "HEAD 与 GET 均被拒")


def target_to_file(site_root: Path, page: Path, target: str) -> Path | None:
    """把站内链接映射到磁盘文件；判断不了（外链/纯锚点）返回 None。"""
    t = target.split("#", 1)[0].split("?", 1)[0]
    if not t:
        return None  # 纯锚点：同页
    if t.startswith(SKIP_SCHEMES) or "://" in t or t.startswith("//"):
        return None
    if t.startswith("/"):
        # 站内绝对路径。本站一律产出相对路径，但生成器将来可能改，一并支持。
        rel = t.lstrip("/")
        if rel.startswith(site_root.name + "/"):
            rel = rel[len(site_root.name) + 1 :]
        return site_root / rel
    return (page.parent / t).resolve()


def check_internal(site_root: Path) -> tuple[list[str], list[str]]:
    problems: list[str] = []
    checked = 0
    for page in sorted(site_root.rglob("*.html")):
        html = page.read_text(encoding="utf-8", errors="replace")
        rel_page = page.relative_to(site_root)
        for target in ATTR_RE.findall(html):
            f = target_to_file(site_root, page, target)
            if f is None:
                continue
            checked += 1
            candidates = [f] if f.suffix else [f / "index.html", f.with_suffix(".html")]
            if not any(c.exists() for c in candidates):
                problems.append(f"{rel_page}: 链接指向不存在的文件 → {target}")
        dupes = [i for i, n in Counter(ID_RE.findall(html)).items() if n > 1]
        if dupes:
            problems.append(f"{rel_page}: 重复 id {dupes}")
    return problems, [f"内部链接 {checked} 条"]


def check_external(site_root: Path, timeout: float) -> tuple[list[str], list[str], list[str]]:
    urls: dict[str, set[str]] = {}
    for page in sorted(site_root.rglob("*.html")):
        rel_page = str(page.relative_to(site_root))
        for target in ATTR_RE.findall(page.read_text(encoding="utf-8", errors="replace")):
            if target.startswith("http://") or target.startswith("https://"):
                urls.setdefault(target.split("#", 1)[0], set()).add(rel_page)

    broken: list[str] = []
    unverified: list[str] = []
    for url in sorted(urls):
        state, detail = probe(url, timeout)
        where = ", ".join(sorted(urls[url])[:2])
        if state == "broken":
            broken.append(f"{url}  [{detail}]  出现于: {where}")
        elif state == "unverified":
            unverified.append(f"{url}  [{detail}]")
    notes = [
        f"外部链接 {len(urls)} 条：ok={len(urls) - len(broken) - len(unverified)} "
        f"broken={len(broken)} unverified={len(unverified)}"
    ]
    return broken, unverified, notes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--site-root", default=str(ROOT / "docs"))
    ap.add_argument("--external", action="store_true", help="同时探测外部链接（需要网络）")
    ap.add_argument("--timeout", type=float, default=8.0)
    args = ap.parse_args()

    site_root = Path(args.site_root)
    if not site_root.exists():
        print(f"站点目录不存在：{site_root}（先跑 build.py）")
        return 2

    problems, notes = check_internal(site_root)
    unverified: list[str] = []
    if args.external:
        ext_broken, unverified, ext_notes = check_external(site_root, args.timeout)
        problems += ext_broken
        notes += ext_notes

    for n in notes:
        print(f"[check] {n}")
    if unverified:
        print(f"\n[check] 未能验证（网络不可达或不允许探测，不计入失败）—— {len(unverified)} 条：")
        for u in unverified:
            print(f"  ? {u}")
    if problems:
        print(f"\n[check] {len(problems)} 个问题：")
        for p in problems:
            print(f"  x {p}")
        return 1
    print("\n[check] 所有已检查的链接都有效")
    return 0


if __name__ == "__main__":
    sys.exit(main())
