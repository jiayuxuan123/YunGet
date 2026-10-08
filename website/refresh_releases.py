#!/usr/bin/env python3
"""抓取 GitHub Releases，生成站点用的更新日志/下载数据。

产物：`website/data/releases.json`（**提交进仓库**，因此构建站点的过程不需要联网，
       也不会因为 GitHub API 限流而构建失败）。

用法：
    python website/refresh_releases.py --repo jiayuxuan123/TurboDL
    python website/refresh_releases.py --repo jiayuxuan123/YunGet

## 为什么不用 /releases/latest

GitHub 的 `/releases/latest` **按设计跳过 prerelease**。TurboDL 发过一批 rc，将来也可能再
发；用 latest 会让站点显示一个比实际更旧的版本。这里取列表后自己挑「最大的版本号」，
与 YunGet 应用内 UpdateChecker 的算法保持同一个口径（它也是这么做的，并且注释了原因）。

## 数据里保留什么

- 版本号、发布时间、是否 prerelease、Release 页面地址；
- 每个附件的**文件名、字节数、直链**（下载页直接渲染它，不手写任何 URL）；
- 说明正文的前若干字符（更新日志用；过长的截断并标注）。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
GH = r"C:\Program Files\GitHub CLI\gh.exe"


def gh_json(endpoint: str):
    """调用 gh api 拿 JSON。失败时抛异常并带上 gh 的原始输出（便于判断是限流还是权限）。"""
    exe = GH if Path(GH).exists() else "gh"
    p = subprocess.run(
        [exe, "api", endpoint],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if p.returncode != 0:
        raise RuntimeError(f"gh api {endpoint} 失败（exit {p.returncode}）：{(p.stderr or p.stdout).strip()[:400]}")
    return json.loads(p.stdout)


def version_key(tag: str):
    """把 tag 变成可比较的键。

    与 YunGet UpdateChecker 同一套规则：去掉 `v` 前缀；数字段按数值比（避免 `dev9 > dev12`
    这类字典序错误）；**带后缀的排在无后缀之前**（`2.6.9-rc1 < 2.6.9`）。
    """
    raw = tag.lstrip("vV")
    core, _, suffix = raw.partition("-")
    nums = []
    for part in core.split("."):
        m = re.match(r"^(\d+)", part)
        nums.append(int(m.group(1)) if m else 0)
    while len(nums) < 4:
        nums.append(0)
    return (tuple(nums), 0 if suffix else 1, suffix)


def summarize(body: str, limit: int = 400) -> str:
    """取说明正文的可读开头：去掉 markdown 标题符号与代码块，压成一段。"""
    text = re.sub(r"```.*?```", "", body or "", flags=re.S)
    text = re.sub(r"^\s*#+\s*", "", text, flags=re.M)
    text = re.sub(r"^\s*[-*]\s+", "· ", text, flags=re.M)
    text = re.sub(r"\n{2,}", "\n", text).strip()
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "…"


def collect(repo: str, max_releases: int) -> dict:
    releases = gh_json(f"repos/{repo}/releases?per_page={max_releases}")
    out = []
    for rel in releases:
        if rel.get("draft"):
            continue
        assets = [
            {
                "name": a["name"],
                "size": a["size"],
                "url": a["browser_download_url"],
                "downloads": a.get("download_count", 0),
                "sha256": (a.get("digest") or "").replace("sha256:", "") or None,
            }
            for a in rel.get("assets", [])
        ]
        out.append(
            {
                "tag": rel["tag_name"],
                "name": rel.get("name") or rel["tag_name"],
                "published": rel.get("published_at") or rel.get("created_at"),
                "prerelease": bool(rel.get("prerelease")),
                "url": rel["html_url"],
                "summary": summarize(rel.get("body") or ""),
                "assets": assets,
            }
        )
    if not out:
        raise SystemExit(f"{repo}: 没有取到任何已发布的 Release（草稿不算）")
    out.sort(key=lambda r: version_key(r["tag"]), reverse=True)
    stable = [r for r in out if not r["prerelease"]]
    return {
        "repo": repo,
        "latest": (stable or out)[0],
        "releases": out,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--max", type=int, default=30)
    ap.add_argument("--out", default=str(HERE / "data" / "releases.json"))
    args = ap.parse_args()

    data = collect(args.repo, args.max)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    latest = data["latest"]
    print(f"[releases] {args.repo}: {len(data['releases'])} 个发布 → {out}")
    print(f"  最新稳定版: {latest['tag']}  ({latest['published']})  附件 {len(latest['assets'])} 个")
    for a in latest["assets"][:5]:
        print(f"    {a['name']}  {a['size']} B")
    if len(latest["assets"]) > 5:
        print(f"    … 其余 {len(latest['assets']) - 5} 个")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
