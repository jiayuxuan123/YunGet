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
from pathlib import Path


def esc(text: object) -> str:
    """转义成安全的 HTML 文本（属性与正文通用）。"""
    return html.escape(str(text), quote=True)


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
    """数据表。单元格按 HTML 片段处理（调用方自行 esc 或给链接）。"""
    head = "".join(f"<th>{h}</th>" for h in headers)
    body = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return f'<div class="table-wrap"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


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
    return f"<section{aid}><h2>{title}</h2>{body}</section>"


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
