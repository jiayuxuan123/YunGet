#!/usr/bin/env python3
"""站点生成器与内容文件**共用**的 HTML 片段助手。

为什么单独一个文件：`build.py`（渲染器）与 `content.py`（内容）都要用这些函数 ——
内容文件要拼表格/卡片/代码块，渲染器要用同一个 `esc`/`rel` 保证口径一致。
放在任一方都会形成循环导入。

这里只做"拼字符串"，不做任何决定：不判断链接是否真实、不判断版本号。
那些属于内容层的职责。
"""

from __future__ import annotations

import html
import re
from pathlib import Path


def esc(text: object) -> str:
    """转义成安全的 HTML 文本（属性与正文通用）。"""
    return html.escape(str(text), quote=True)


#: 资源文件的内容指纹 → 带指纹的路径。
#:
#: **为什么需要它**：样式表与脚本如果一直用同一个 URL，浏览器会一直用缓存里的旧版本。
#: 线上改完 CSS 重新部署，读者看到的还是旧样式 —— 而且没有任何报错，只是"改了没生效"。
#: 把内容哈希放进文件名，URL 变了，缓存自然失效。
#:
#: 指纹在构建开始时算一次（见 `asset_fingerprints`），不是每次调用都算 ——
#: 一页要引用几十次资源，每次都读文件算哈希会明显拖慢构建。
_ASSET_FINGERPRINTS: dict[str, str] = {}


def set_asset_fingerprints(assets_dir: Path) -> None:
    """为 assets/ 下的 .css / .js 算内容指纹（构建开始时调用一次）。"""
    import hashlib

    _ASSET_FINGERPRINTS.clear()
    if not assets_dir.is_dir():
        return
    for f in sorted(assets_dir.iterdir()):
        if f.suffix not in (".css", ".js"):
            continue
        digest = hashlib.sha256(f.read_bytes()).hexdigest()[:8]
        _ASSET_FINGERPRINTS[f.name] = f"{f.stem}.{digest}{f.suffix}"


def rel(from_dir: str, to_path: str) -> str:
    """把站点内路径 `to_path` 表达成相对当前页面目录的链接。

    站内链接必须可靠：同一份内容在 `/` 与 `/download/` 两种深度下都要指向对的地方，
    所以一律用相对路径 —— 这样 project pages（`user.github.io/repo/`）与将来自有域名的
    根路径都能用，不需要改 base。
    """
    from_parts = [p for p in from_dir.split("/") if p]
    to_parts = [p for p in to_path.split("/") if p]
    while from_parts and to_parts and from_parts[0] == to_parts[0]:
        from_parts.pop(0)
        to_parts.pop(0)
    return "/".join([".."] * len(from_parts) + to_parts) or "."


def asset_fingerprint(name: str) -> str:
    """资源名 → 带内容指纹的文件名；没登记过就原样返回。"""
    return _ASSET_FINGERPRINTS.get(name, name)


def asset(from_dir: str, name: str) -> str:
    """引用 assets/ 下的资源，自动带上内容指纹（见 `set_asset_fingerprints`）。

    没登记过的文件原样引用 —— 指纹只对参与缓存的 .css/.js 有意义。
    """
    base = _ASSET_FINGERPRINTS.get(name, name)
    return rel(from_dir, f"assets/{base}")


def write(path: Path, text: str) -> None:
    """写文本文件，统一 LF（Git 里不回显成整文件改动）。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def fmt_bytes(n: int) -> str:
    if n >= 1024 * 1024:
        return f"{n / 1048576:.1f} MB"
    if n >= 1024:
        return f"{n / 1024:.0f} KB"
    return f"{n} B"


def code_block(code: str, caption: str = "") -> str:
    """代码块。`caption` 是上方那行小标签（文件名 / 语言）。"""
    cap = f'<div class="code-cap">{esc(caption)}</div>' if caption else ""
    return f'<div class="code">{cap}<pre><code>{esc(code.strip(chr(10)))}</code></pre></div>'


def table(headers: list[str], rows: list[list[str]]) -> str:
    """数据表。单元格按 HTML 片段处理（调用方自行 esc 或给链接）。

    每个 `<td>` 都带上 `data-label="列名"`，这是**手机端卡片式堆叠**的前提：
    窄屏时表头行会被隐藏，而 CSS 读不到"这一格属于哪一列"，只能靠这个属性
    把列名重新贴回每个值前面。没有它，手机上的宽表格只能硬压成多行断句。
    """
    head = "".join(f"<th>{h}</th>" for h in headers)
    body = "".join(
        "<tr>"
        + "".join(
            f'<td data-label="{esc(_plain(h))}">{c}</td>'
            for h, c in zip(headers, r)
        )
        + "</tr>"
        for r in rows
    )
    return f'<div class="table-wrap"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


def _plain(fragment: str) -> str:
    """把一个可能是 HTML 片段的表头压成纯文本，用于 data-label。

    表头里常有 `<span class="mono">` 这类标记；原样塞进属性会破坏标签。
    """
    text = re.sub(r"<[^>]+>", "", str(fragment))
    return html.unescape(text).strip()


def md_inline(text: object) -> str:
    """把事实文本里的 `**强调**` 渲染成 <strong>。

    事实表（支持平台、能力清单）当初是按 Markdown 写的，直接拼 HTML 的地方如果不转换，
    页面上就会露出字面的 `**不推荐**`。只认这一种标记，并且**先转义再替换**，
    所以内容里的 `<` 依然是安全的。
    """
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", esc(text))
    return out.replace("**", "")  # 截断过的文案可能留下孤立的半个标记


def cards(items: list[tuple[str, str]], columns: int = 3) -> str:
    """卡片网格：`(标题, 正文HTML)`。"""
    inner = "".join(
        f'<div class="card"><h3>{t}</h3><div class="card-body">{b}</div></div>' for t, b in items
    )
    return f'<div class="cards cols-{columns}">{inner}</div>'


def buttons(items: list[tuple[str, str]], kind: str = "") -> str:
    """按钮行：`(文本, href)`。href 由调用方保证真实可达。"""
    cls = f"btn-row {kind}".strip()
    inner = "".join(f'<a class="btn" href="{h}">{t}</a>' for t, h in items)
    return f'<div class="{cls}">{inner}</div>'


def section(title: str, body: str, anchor: str = "") -> str:
    aid = f' id="{anchor}"' if anchor else ""
    # class="section" 负责章节间距（clamp 后的 56–108px 双侧留白）。
    # 没有它时区块彼此贴住：门禁会把上一节尾部的说明读成下一节标题上的
    # "眉标"（小字标签）并判 FAIL —— 间距在这里是功能，不只是观感。
    return f'<section{aid} class="section">{seg_rule()}<h2>{title}</h2>{body}</section>'


def seg_rule(segments: list[tuple[int, bool]] | None = None) -> str:
    """分段线：本站的标志性元素（设计说明见 `_audit/官网重设计计划.md`）。

    这个软件把文件切成**不等长的分片**并发下载，所以区块之间的分隔线同样被切成
    不等宽的分段：`(相对宽度, 是否已完成)`。已完成段用强调色。

    为什么不用普通 1px 横线：一条横线不承载任何信息，只是装饰；而分段线本身
    就是这个产品在做的事。默认给出的一组比例是固定的（不是随机数）——
    随机会让同一页面在不同构建下长得不一样，而这里要的是"同一个记号反复出现"。
    """
    if segments is None:
        # 一组不等宽的比例，模拟"下到一半"的真实观感。固定值，保证可复现。
        segments = [(7, True), (3, False), (5, True), (2, False), (9, False), (4, True)]
    total = sum(w for w, _ in segments) or 1
    parts = []
    for width, done in segments:
        pct = width / total * 100
        cls = "done" if done else ""
        parts.append(f'<i class="{cls}" style="width:{pct:.3f}%"></i>')
    return '<div class="seg-rule" aria-hidden="true">' + "".join(parts) + "</div>"


def note(text: str, tone: str = "") -> str:
    """提示条：tone 取 warn / ok，留空为普通提示。"""
    cls = f"note {tone}".strip()
    return f'<div class="{cls}">{text}</div>'


def breadcrumb(items: list[tuple[str, str]]) -> str:
    """面包屑：`(文本, href)`；最后一项不带链接。"""
    parts = []
    for i, (text, href) in enumerate(items):
        if i == len(items) - 1 or not href:
            parts.append(f'<span aria-current="page">{esc(text)}</span>')
        else:
            parts.append(f'<a href="{href}">{esc(text)}</a>')
    return '<nav class="crumbs">' + '<span class="sep">/</span>'.join(parts) + "</nav>"
