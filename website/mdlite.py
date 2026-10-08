#!/usr/bin/env python3
"""把仓库里的 Markdown 文档渲染成官网页面（纯标准库）。

## 为什么要有它

官网上的文档有两份去处：仓库里一份（给改代码的人），官网上再放一份（给看文档的人）。
两份最容易的坏法是**各写一遍** —— 于是迟早对不上。这个模块让官网直接读仓库里的
`.md` 文件来渲染，正文只有一份，改一处两处都对。

## 支持的 Markdown 子集

**支持**：ATX 标题、段落、无序/有序列表（含一层嵌套）、GFM 管道表格、围栏代码块
（``` 与 ~~~）、引用块、分隔线、行内 `代码`、**粗体**、*斜体*、~~删除线~~、
`[文字](链接)`、`![图片](地址)`、`<https://自动链接>`，以及**原样透传的 HTML 块**
（仓库 README 里的 `<div align="center">`、`<img>` 这类）。

**不支持**：脚注、定义列表、参考式链接定义、任务列表复选框、数学公式、内嵌 HTML 的
Markdown（`<div markdown="1">`）。文档里出现这些会按普通文字显示 —— 渲染器不会崩，
但也不会当作结构。**文档写作时就避开这些语法**，这是本模块的契约。

## 两个关键接口

- `render(text, link=...)`：`link` 是链接改写回调，把文档里的相对链接
  （`../README.md`、`CONVENTION.md`）换成站点地址或 GitHub 地址 —— 否则官网上会出现
  指向不存在的 `.md` 的链接。
- 返回值是 `(html, toc)`：`toc` 是由标题生成的多级目录，供文档页做侧栏。
"""

from __future__ import annotations

import html
import re

from sitekit import code_block, esc, note, table

#: 代码块围栏：``` 或 ~~~，允许缩进，允许尾部有语言名。
_FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})\s*([\w+#.-]*)\s*$")
_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
_HR = re.compile(r"^\s{0,3}([-*_])(?:\s*\1){2,}\s*$")
_QUOTE = re.compile(r"^\s{0,3}>\s?(.*)$")
_BULLET = re.compile(r"^(\s*)([-*+])\s+(.*)$")
_NUMBER = re.compile(r"^(\s*)(\d{1,9})[.)]\s+(.*)$")
_TABLE_SEP = re.compile(r"^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$")
_LIST_ITEM = re.compile(r"^\s*(?:[-*+]|\d{1,9}[.)])\s+\S")
#: HTML 块起始：块级标签或注释。这类内容原样透传（仓库文档自己写的，可信）。
_HTML_BLOCK = re.compile(r"^\s{0,3}(?:<!--|</?(?:div|p|table|thead|tbody|tr|td|th|ul|ol|li|details|summary|"
                         r"picture|figure|figcaption|h[1-6]|img|br|hr|sub|sup|kbd|center|span|a|b|i|code|pre|blockquote)\b)")


# --------------------------------------------------------------------------- #
# 行内
# --------------------------------------------------------------------------- #

def _slug(text: str, used: set[str]) -> str:
    """给标题生成稳定、可读的锚点 id。

    汉字保留（浏览器会把它们百分号编码，链接照样能用）—— 中文文档的标题如果全被丢掉，
    每节就会退化成 `sec`、`sec-2`、`sec-3`，在前面插一节就会让所有已分享的锚点集体错位。
    标点与空白折成连字符；万一是纯符号标题，用 `sec-N` 兜底。同名标题加 `-2`、`-3`，
    保证 id 唯一 —— 否则目录里的链接会全部跳到第一个同名标题。
    """
    base = re.sub(r"[^0-9a-z\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]+", "-", text.lower())
    base = re.sub(r"-{2,}", "-", base).strip("-")[:80] or "sec"
    slug = base
    n = 1
    while slug in used:
        n += 1
        slug = f"{base}-{n}"
    used.add(slug)
    return slug


def _inline(text: str, link=None, *, image_prefix: str = "") -> str:
    """渲染行内标记。

    先摘出 `代码` 片段（里面的 `*`、`_` 是字面量，不能再被当成强调语法），
    处理完其余标记再放回去。
    """
    spans: list[str] = []

    def stash(m: re.Match) -> str:
        spans.append(f"<code>{esc(m.group(2))}</code>")
        return f"\x00{len(spans) - 1}\x00"

    # 反引号可以有多个（``a `b` c``），所以用回溯匹配等长的围栏
    text = re.sub(r"(`+)(.+?)\1", stash, text, flags=re.S)

    text = html.escape(text, quote=False)

    def _url(raw: str) -> str:
        url = raw.strip()
        if url.startswith("<") and url.endswith(">"):
            url = url[1:-1]
        # 标题（`"..."`）在 URL 之后，转义后引号会变成 &quot;
        url = re.split(r"\s+&quot;|\s+'", url)[0]
        return html.unescape(url).strip()

    # 图片先于链接：![...](...) 的尾巴也是 [...](...)，顺序反了会漏掉感叹号
    def img(m: re.Match) -> str:
        alt, url = m.group(1), _url(m.group(2))
        if link:
            url = link(url, "image")
        return f'<img src="{esc(image_prefix + url)}" alt="{esc(html.unescape(re.sub(r"[*_`]", "", alt)))}" loading="lazy">'

    text = re.sub(r"!\[([^\]]*)\]\(([^)]*)\)", img, text)

    def a(m: re.Match) -> str:
        label, url = m.group(1), _url(m.group(2))
        if link:
            url = link(url, "link")
        ext = ' target="_blank" rel="noopener"' if url.startswith(("http://", "https://")) else ""
        return f'<a href="{esc(url)}"{ext}>{label}</a>'

    text = re.sub(r"\[([^\]]+)\]\(([^)]*)\)", a, text)
    text = re.sub(r"&lt;(https?://[^\s&]+)&gt;", lambda m: a(m) if False else
                  f'<a href="{esc(m.group(1))}" target="_blank" rel="noopener">{esc(m.group(1))}</a>', text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"<em>\1</em>", text)
    text = re.sub(r"~~([^~]+)~~", r"<del>\1</del>", text)
    text = re.sub(r"\x00(\d+)\x00", lambda m: spans[int(m.group(1))], text)
    return text


# --------------------------------------------------------------------------- #
# 块级
# --------------------------------------------------------------------------- #

def _split_row(line: str) -> list[str]:
    row = line.strip()
    if row.startswith("|"):
        row = row[1:]
    if row.endswith("|"):
        row = row[:-1]
    # 管道前有反斜杠表示字面量竖线
    cells = re.split(r"(?<!\\)\|", row)
    return [c.replace("\\|", "|").strip() for c in cells]


def render(text: str, link=None, *, h_offset: int = 1, toc_max: int = 3,
           image_prefix: str = "", toc: bool = True) -> tuple[str, str]:
    """渲染 Markdown，返回 `(正文 HTML, 目录 HTML)`。

    `h_offset`：文档的 `#` 是页面里的一级标题，而页面模板已经有一个 `<h1>` 了，
    所以默认把标题整体下沉一级（`#` → `<h2>`），保证每页只有一个 `<h1>`。
    """
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out: list[str] = []
    heads: list[tuple[int, str, str]] = []
    used: set[str] = set()
    i = 0
    n = len(lines)

    def blank(k: int) -> bool:
        return k >= n or not lines[k].strip()

    while i < n:
        line = lines[i]

        if not line.strip():
            i += 1
            continue

        # --- 围栏代码块 ---
        m = _FENCE.match(line)
        if m:
            fence, lang = m.group(1), m.group(2)
            body: list[str] = []
            i += 1
            while i < n and not re.match(rf"^\s{{0,3}}{re.escape(fence[0])}{{{len(fence)},}}\s*$", lines[i]):
                body.append(lines[i])
                i += 1
            i += 1
            out.append(code_block("\n".join(body), lang))
            continue

        # --- HTML 块（原样透传） ---
        if _HTML_BLOCK.match(line):
            block = []
            while i < n and lines[i].strip():
                block.append(lines[i])
                i += 1
            out.append("\n".join(block))
            continue

        # --- 标题 ---
        m = _HEADING.match(line)
        if m:
            level = min(len(m.group(1)) + h_offset, 6)
            raw = m.group(2)
            plain = html.unescape(re.sub(r"[*_`]", "", raw))
            slug = _slug(plain, used)
            heads.append((level, plain, slug))
            out.append(
                f'<h{level} id="{slug}">{_inline(raw, link, image_prefix=image_prefix)}'
                f'<a class="anchor" href="#{slug}" aria-label="链接到此节">#</a></h{level}>'
            )
            i += 1
            continue

        # --- 分隔线 ---
        if _HR.match(line):
            out.append("<hr>")
            i += 1
            continue

        # --- 表格（表头 + 分隔行） ---
        if "|" in line and i + 1 < n and _TABLE_SEP.match(lines[i + 1]) and "|" in lines[i + 1]:
            heads_row = [_inline(c, link, image_prefix=image_prefix) for c in _split_row(line)]
            i += 2
            rows = []
            while i < n and lines[i].strip() and "|" in lines[i]:
                cells = [_inline(c, link, image_prefix=image_prefix) for c in _split_row(lines[i])]
                cells += [""] * (len(heads_row) - len(cells))
                rows.append(cells[: len(heads_row)])
                i += 1
            out.append(table(heads_row, rows))
            continue

        # --- 引用块 ---
        if _QUOTE.match(line):
            buf = []
            while i < n and (_QUOTE.match(lines[i]) or (buf and lines[i].strip())):
                mq = _QUOTE.match(lines[i])
                buf.append(mq.group(1) if mq else lines[i].strip())
                i += 1
            inner, _ = render("\n".join(buf), link, h_offset=h_offset, toc_max=toc_max,
                              image_prefix=image_prefix, toc=False)
            out.append(note(inner))
            continue

        # --- 列表 ---
        if _LIST_ITEM.match(line):
            ordered = bool(_NUMBER.match(line))
            items: list[tuple[str, int]] = []
            while i < n:
                mo, mb = _NUMBER.match(lines[i]), _BULLET.match(lines[i])
                if mo:
                    items.append((mo.group(3), len(mo.group(1)) // 2))
                    i += 1
                elif mb:
                    items.append((mb.group(3), len(mb.group(1)) // 2))
                    i += 1
                elif items and lines[i].strip() and lines[i].startswith(("  ", "\t")) and not _LIST_ITEM.match(lines[i]):
                    # 列表项的续行
                    text_, depth = items[-1]
                    items[-1] = (text_ + " " + lines[i].strip(), depth)
                    i += 1
                elif items and not lines[i].strip() and i + 1 < n and _LIST_ITEM.match(lines[i + 1]):
                    break
                else:
                    break
            tag = "ol" if ordered else "ul"
            parts = []
            depth = 0
            for body, d in items:
                while d > depth:
                    parts.append(f"<{tag}>")
                    depth += 1
                while d < depth:
                    parts.append(f"</{tag}>")
                    depth -= 1
                parts.append(f"<li>{_inline(body, link, image_prefix=image_prefix)}</li>")
            while depth > 0:
                parts.append(f"</{tag}>")
                depth -= 1
            out.append(f"<{tag}>" + "".join(parts).replace(f"<{tag}></{tag}>", "") + f"</{tag}>")
            continue

        # --- 段落 ---
        buf = []
        while i < n and lines[i].strip() and not any((
            _FENCE.match(lines[i]), _HEADING.match(lines[i]), _HR.match(lines[i]),
            _QUOTE.match(lines[i]), _LIST_ITEM.match(lines[i]), _HTML_BLOCK.match(lines[i]),
        )) and not (i + 1 < n and _TABLE_SEP.match(lines[i + 1]) and "|" in lines[i]):
            buf.append(lines[i].strip())
            i += 1
        if buf:
            out.append(f"<p>{_inline(' '.join(buf), link, image_prefix=image_prefix)}</p>")
            continue
        i += 1

    return "".join(out), _toc(heads, toc_max) if toc else ""


def _toc(heads: list[tuple[int, str, str]], toc_max: int) -> str:
    """由标题生成目录：`<ul>` 按层级缩进。"""
    items = [(lv, t, s) for lv, t, s in heads if lv <= toc_max]
    if len(items) < 2:
        return ""
    parts = ['<nav class="toc" aria-label="目录"><div class="toc-title">本页目录</div><ul>']
    depth = items[0][0]
    first = True
    for lv, title, slug in items:
        if first:
            depth = lv
            first = False
        while lv > depth:
            parts.append("<ul>")
            depth += 1
        while lv < depth:
            parts.append("</li></ul>")
            depth -= 1
        if not parts[-1].startswith("<ul>") and not parts[-1].startswith('<nav'):
            parts.append("</li>")
        parts.append(f'<li><a href="#{slug}">{esc(title)}</a>')
    while depth > items[0][0]:
        parts.append("</li></ul>")
        depth -= 1
    parts.append("</li></ul></nav>")
    return "".join(parts)


# --------------------------------------------------------------------------- #
# 自检（`python website/mdlite.py` 直接跑，不需要测试框架）
# --------------------------------------------------------------------------- #

def _selftest() -> int:
    """`(用例名, 源, 必须出现, 必须不出现)`。"""
    cases: list[tuple[str, list[str], list[str], list[str]]] = [
        ("标题下沉一级", ["# 一", "## 二"], ['<h2 id="一">', '<h3 id="二">'], ["<h1"]),
        ("中文标题保留在锚点里", ["# 为什么不止一个引擎"], ['id="为什么不止一个引擎"'], ['id="sec"']),
        ("纯符号标题有兜底", ["# ---", "# ..."], ['id="sec"', 'id="sec-2"'], []),
        ("代码围栏里的标记保持字面量", ["```js", "a * b", "```"],
         ["<code>a * b</code>"], ["<strong>", "<em>"]),
        ("表格", ["| a | b |", "|---|---|", "| 1 | 2 |"], ["<table>", "<th>a</th>"], []),
        ("列表", ["- 甲", "- 乙"], ["<ul>", "<li>甲</li>", "<li>乙</li>"], []),
        ("有序列表", ["1. 甲", "2. 乙"], ["<ol>", "<li>甲</li>"], ["<ul>"]),
        ("行内标记", ["**加粗** 与 `代码` 与 [链接](https://e.com) 与 ~~删~~"],
         ["<strong>加粗</strong>", "<code>代码</code>", '<a href="https://e.com"', "<del>删</del>"], []),
        ("HTML 转义", ["a < b & c"], ["a &lt; b &amp; c"], ["a < b"]),
        ("同名标题 id 唯一", ["# A B", "# A B"], ['id="a-b"', 'id="a-b-2"'], []),
        ("引用块进提示条", ["> 注意这一点"], ['<div class="note">', "注意这一点"], []),
        ("链接改写回调生效", ["[x](other.md)"], ['href="/mapped"'], ["other.md"]),
        ("目录至少两节才生成", ["# A", "x", "## B", "y"], ['class="toc"', 'href="#b"'], []),
    ]
    bad = 0
    for name, src, wants, nots in cases:
        text = "\n".join(src)
        cb = (lambda url, kind: "/mapped") if "改写回调" in name else None
        got, toc = render(text, cb)
        both = got + toc
        for want in wants:
            if want not in both:
                print(f"[FAIL] {name}: 期望出现 {want!r}\n      实得：{both[:220]}")
                bad += 1
        for notw in nots:
            if notw in both:
                print(f"[FAIL] {name}: 不应出现 {notw!r}\n      实得：{both[:220]}")
                bad += 1
    print(f"[mdlite] {len(cases)} 组自检，" + ("全部通过" if not bad else f"{bad} 处不符"))
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(_selftest())
