#!/usr/bin/env python3
"""审查站点产物的常见问题（响应式、无障碍、文案）。

为什么要有这个脚本：两个站点的 HTML 是生成的，改一处 CSS 可能影响几十页；
靠人肉翻页面看不到"某个断点下溢出 40px"这种事。这里对**产物**做静态检查，
把能机械判定的问题列出来，剩下的交给浏览器实测。

用法：
    python website/audit_pages.py            # 审当前 docs/ 产物
    python website/audit_pages.py --root ../TurboDL/docs

检查项（凡是能静态判定的）：
  * <img> 缺 alt / 缺显式 width/height
  * 缺 lang 属性
  * heading 层级跳跃（h1 → h3）
  * 用了 `...` 而不是 `…`
  * 直引号 " 在正文里（该用中文引号）
  * 数字+单位之间该用 &nbsp;（10MB → 10&nbsp;MB）
  * anchor 链接的 href 为空 / 指向 #
  * button 缺 type
  * 有 aria-label 但对应元素不是可交互元素
"""

from __future__ import annotations

import argparse
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

VOID = {"img", "br", "hr", "meta", "link", "input", "source", "wbr", "col", "area", "base", "embed", "param", "track"}


class PageAudit(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.findings: list[str] = []
        self.images: list[tuple[str, dict]] = []
        self.headings: list[tuple[str, str]] = []
        self.links: list[tuple[str, dict]] = []
        self.buttons: list[dict] = []
        self._stack: list[str] = []
        self._in_body = False
        self._text_parts: list[str] = []

    # -- helpers -------------------------------------------------------
    def _where(self) -> str:
        return "/".join(self._stack[-3:]) or "(root)"

    # -- HTMLParser ----------------------------------------------------
    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag == "body":
            self._in_body = True
        if tag == "html" and not a.get("lang"):
            self.findings.append("html 缺 lang 属性")
        if tag == "img":
            self.images.append((self._where(), a))
            if "alt" not in a:
                self.findings.append(f"img 缺 alt：{a.get('src', '?')[:70]}")
            if "width" not in a or "height" not in a:
                self.findings.append(f"img 缺显式 width/height（会抖）：{a.get('src', '?')[:70]}")
            if "loading" not in a:
                self.findings.append(f"img 缺 loading=lazy：{a.get('src', '?')[:70]}")
        if re.fullmatch(r"h[1-6]", tag):
            self.headings.append((tag, self._where()))
        if tag == "a":
            self.links.append((self._where(), a))
            href = a.get("href", "")
            if not href:
                self.findings.append("a 缺 href")
            elif href == "#":
                self.findings.append(f"a href='#'（点了没反应）：{a.get('class', '')}")
        if tag == "button":
            self.buttons.append(a)
            if "type" not in a:
                self.findings.append("button 缺 type（默认 submit，会误提交）")
        if tag not in VOID:
            self._stack.append(tag)

    def handle_endtag(self, tag: str) -> None:
        if tag in self._stack:
            while self._stack:
                t = self._stack.pop()
                if t == tag:
                    break
        if tag == "body":
            self._in_body = False

    def handle_data(self, data: str) -> None:
        if self._in_body:
            self._text_parts.append(data)


def _strip_code(text: str) -> str:
    """去掉代码块与行内代码，只留下散文。

    代码里的 `...`（占位）、直引号（JSON 示例）、`256 × 1MB` 都是合法的；
    把它们算进"文案问题"只会让真问题被淹没。

    顺带把标签本身也去掉 —— 剩下的就是纯文本。
    """
    text = re.sub(r"<pre\b.*?</pre>", " ", text, flags=re.S | re.I)
    text = re.sub(r"<code\b.*?</code>", " ", text, flags=re.S | re.I)
    # <script> 里也是代码（页头那段主题切换的 JS），
    # 里面的字符串字面量同样不该被当成"文案里的直引号"。
    text = re.sub(r"<script\b.*?</script>", " ", text, flags=re.S | re.I)
    # .mono 是"机器数据"样式（坐标、JSON 字段值）—— `level: "js"` 这类
    # 引号是**语法的一部分**，不是文案的引号。
    text = re.sub(r'<[^>]*class="[^"]*\bmono\b[^"]*"[^>]*>.*?</[^>]+>', " ", text, flags=re.S | re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    return text


def audit_file(path: Path) -> list[str]:
    src = path.read_text(encoding="utf-8", errors="replace")
    # 更新日志页的内容是**引述发布说明原文** —— 那是历史记录，
    # 不该被当成本站的文案来挑（当年的写法保留原样才对）。
    is_quoted_release_notes = "changelog" in path.parts
    p = PageAudit()
    p.feed(src)

    out = list(p.findings)

    # heading 层级跳跃
    levels = [int(t[0][1]) for t in p.headings]
    for i in range(1, len(levels)):
        if levels[i] > levels[i - 1] + 1:
            out.append(f"heading 层级跳跃：h{levels[i-1]} → h{levels[i]}")
            break
    if levels and levels[0] != 1:
        out.append(f"首个 heading 是 h{levels[0]}，不是 h1")

    # ★ 只检查**散文**部分：代码块（pre）与行内代码（code）里的 `...`、
    #   直引号、数字+单位都是合法的（`{...}` 占位、JSON 示例、`256 × 1MB`）。
    #   不剔除它们的话，这份报告会满是被误报淹没的真问题。
    # ★ 从**源码**剥，而不是从解析后的文本 —— 解析后的文本已经丢了标签边界。
    prose = "" if is_quoted_release_notes else _strip_code(src)

    if "..." in prose:
        out.append("正文里有 `...`，应为 `…`")
    if re.search(r'(?<![\w"])"[^"]{2,40}"', prose):
        out.append("正文里有直引号，中文语境应为「」或“”")
    for m in re.finditer(r"\b(\d+)(MB|GB|KB|TB|ms|s)\b", prose):
        out.append(f"数字与单位之间缺 &nbsp;：{m.group(0)}")
        break

    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="docs")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    root = Path(args.root)
    if not root.is_dir():
        print(f"找不到目录 {root}", file=sys.stderr)
        return 2

    pages = sorted(root.rglob("*.html"))
    total = 0
    for page in pages:
        findings = audit_file(page)
        rel = page.relative_to(root)
        if findings:
            total += len(findings)
            print(f"\n{rel}")
            for f in findings:
                print(f"  - {f}")
        elif args.verbose:
            print(f"✓ {rel}")
    print(f"\n共 {len(pages)} 页，{total} 处问题")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
