#!/usr/bin/env python3
"""TurboDL / YunGet 官网的静态站点生成器（纯标准库）。

## 为什么用生成器而不是手写 HTML

站点有 20+ 个页面（可能是单语言，也可能是中英双语），且每次发版都要改版本号、下载直链、
更新日志。手写意味着同一处信息抄 20 遍、改 20 遍 —— 迟早自相矛盾。
这里把「内容」放进 `content.py`，把「渲染」写在本文件里，输出到仓库的 `docs/`。

## 本生成器遵循的硬规则（来自《YunGet 插件化架构设计文档》第 18–26 章）

1. **只用真实信息**：真实截图、真实下载入口、真实版本号、真实文档入口。
   没有「假数据大屏」、没有占位卡片、没有点了没反应的按钮。
2. **深链接必须能直接打开**：`/download`、`/plugins/<id>`、`/docs`、`/changelog`
   都是**真实目录**（`download/index.html`），不依赖前端路由。
3. **站内链接一律相对路径**：这样 `user.github.io/repo/`（project pages）
   与将来自有域名根路径都能用，不需要改一处 base 路径。
4. **移动端不是缩小版**：三档布局（单列 / 两列 / 居中+侧栏），导航在窄屏折叠。

## 产物

- `docs/**/*.html`、`docs/assets/*`、`docs/sitemap.xml`、`docs/robots.txt`、
  `docs/404.html`、`docs/.nojekyll`
- `.nojekyll` 是必须的：GitHub Pages 默认跑 Jekyll，而更新日志里会出现
  `{{ }}` / `{%` 这类字面量（发布说明里的代码示例），Jekyll 会把它们当 Liquid 解析而报错。
"""

from __future__ import annotations

import json
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

from sitekit import (
    breadcrumb,
    buttons,
    cards,
    code_block,
    esc,
    fmt_bytes,
    note,
    rel,
    section,
    table,
    write,
)

# --------------------------------------------------------------------------- #
# 基础设施
# --------------------------------------------------------------------------- #

#: 产物清单文件名（存在输出目录里）：记录上次由本站点生成了哪些文件。
#: 有了它才能"只删自己生成的"，而不是清空整个 `docs/`（那里还有人工维护的文档）。
MANIFEST = ".site-manifest.json"


def load_content(repo_root: Path) -> dict:
    """载入 `website/content.py`。

    把 `website/` 放进 `sys.path`，这样 `content.py` 与 `build.py`、`sitekit.py` 之间
    就是同目录的普通 import（不依赖启动目录，也不要求包结构）。
    """
    web = str(repo_root / "website")
    if web not in sys.path:
        sys.path.insert(0, web)
    import content  # type: ignore

    return content.SITE


# --------------------------------------------------------------------------- #
# 页面渲染
# --------------------------------------------------------------------------- #


class Site:
    """一次站点构建。`content.py` 提供配置与页面列表，本类负责渲染与落盘。"""

    def __init__(self, cfg: dict, repo_root: Path):
        self.cfg = cfg
        self.root = repo_root
        self.out = repo_root / cfg.get("out_dir", "docs")
        self.pages: list[dict] = cfg["pages"]
        self.assets_dir: Path = repo_root / cfg["assets_dir"]
        # `dir` 由 `path` 推导，避免手写两处导致相对链接算错
        for p in self.pages:
            p.setdefault("lang", cfg["default_lang"])
            p["dir"] = str(Path(p["path"]).parent).replace("\\", "/")
            if p["dir"] == ".":
                p["dir"] = ""
        # page_key -> {lang: 输出路径}，用于在语言之间互链
        self.index: dict[str, dict[str, str]] = {}
        for p in self.pages:
            self.index.setdefault(p["key"], {})[p["lang"]] = p["path"]

    # ---- 文案 ----

    def ui(self, lang: str, key: str) -> str:
        """取该语言的界面文案（缺该语言时回退默认语言）。"""
        table = self.cfg.get("ui", {})
        return table.get(lang, {}).get(key) or table.get(self.cfg["default_lang"], {}).get(key, key)

    def label(self, entry: tuple, lang: str) -> str:
        """`(key, {lang: label})` 形式的条目标题。"""
        _key, labels = entry
        return labels.get(lang) or labels.get(self.cfg["default_lang"], entry[0])

    # ---- 链接 ----

    def href(self, from_page: dict, key: str) -> str:
        """把 page key 解析成相对当前页面的链接（同语言优先）。"""
        lang = from_page.get("lang", self.cfg["default_lang"])
        targets = self.index.get(key)
        if not targets:
            raise KeyError(f"content.py 里没有 page key={key!r}（页面之间互相引用时必须先定义）")
        target = targets.get(lang) or next(iter(targets.values()))
        return rel(from_page["dir"], target)

    def asset(self, page: dict, name: str) -> str:
        """把正文里的 `{IMG:name}` 解析成 `assets/` 下**真实存在**的那个文件。

        找不到就报错，而不是渲染出一个坏掉的图片地址 —— 官网上的图裂了没人会来告诉你。
        """
        for src in sorted(self.assets_dir.glob(f"{name}.*")):
            return rel(page["dir"], "assets/" + src.name)
        raise FileNotFoundError(f"assets/ 里没有 {name}.*，但内容里引用了 {{IMG:{name}}}")

    # ---- 头部 / 导航 / 页脚 ----

    def head(self, page: dict) -> str:
        cfg = self.cfg
        lang = page["lang"]
        base = cfg["base_url"].rstrip("/")
        title = page["title"]
        full_title = title if page.get("title_is_full") else f"{title} · {cfg['name']}"
        desc = page.get("desc", cfg["tagline"])
        og_image = f"{base}/{cfg.get('og_image', 'assets/og.png')}"
        canon = f"{base}/{page['path']}"
        return f"""<!DOCTYPE html>
<html lang="{esc(lang)}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(full_title)}</title>
<meta name="description" content="{esc(desc)}">
<link rel="canonical" href="{esc(canon)}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="{esc(cfg['name'])}">
<meta property="og:title" content="{esc(full_title)}">
<meta property="og:description" content="{esc(desc)}">
<meta property="og:url" content="{esc(canon)}">
<meta property="og:image" content="{esc(og_image)}">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="{rel(page["dir"], "assets/favicon.svg")}" type="image/svg+xml">
<link rel="apple-touch-icon" href="{rel(page["dir"], "assets/apple-touch-icon.png")}">
<link rel="stylesheet" href="{rel(page["dir"], "assets/site.css")}">
</head>
<body class="{esc(cfg.get('body_class', ''))}">
<a class="skip" href="#main">{esc(self.ui(lang, "skip"))}</a>
"""

    def header(self, page: dict) -> str:
        cfg = self.cfg
        lang = page["lang"]
        brand_href = rel(page["dir"], self.index["home"].get(lang) or self.index["home"][cfg["default_lang"]])
        nav_items = []
        for key, _labels in cfg["nav"]:
            href = self.href(page, key)
            active = ' class="active" aria-current="page"' if page["key"] == key else ""
            nav_items.append(f'<a href="{href}"{active}>{esc(self.label((key, _labels), lang))}</a>')
        nav = "".join(nav_items)

        # 语言切换：同一 page key 在另一个语言下的地址
        switcher = ""
        langs = cfg.get("langs") or []
        if len(langs) > 1:
            links = []
            for code, _lab in langs:
                if code == lang:
                    links.append(f'<span class="lang-cur">{esc(self.label((code, _lab), lang))}</span>')
                else:
                    target = self.index.get(page["key"], {}).get(code)
                    if target is None:
                        # 该页在目标语言不存在：退回目标语言的首页，不做死链
                        target = self.index["home"].get(code)
                    links.append(f'<a href="{rel(page["dir"], target)}">{esc(self.label((code, _lab), lang))}</a>')
            switcher = '<div class="langs">' + "".join(links) + "</div>"

        return f"""<header class="site-head">
<div class="wrap">
<a class="brand" href="{brand_href}"><span class="brand-mark">{cfg['mark_svg']}</span><span class="brand-name">{esc(cfg['name'])}</span></a>
<button class="nav-toggle" aria-expanded="false" aria-controls="site-nav">{esc(self.ui(lang, "menu"))}</button>
<nav id="site-nav" class="site-nav">{nav}</nav>
{switcher}
</div>
</header>
<main id="main">
"""

    def footer(self, page: dict) -> str:
        """页脚。链接以 `@` 开头表示"站内页面 key"，渲染时才解析成相对路径。

        页脚出现在每一种深度的页面上（`/`、`/legal/privacy/`……），写死的相对路径
        只会在其中一层对；所以站内链接走 key 解析，站外链接（GitHub 等）照原样输出。
        """
        cfg = self.cfg
        lang = page["lang"]
        cols_cfg = cfg["footer"][lang] if lang in cfg["footer"] else cfg["footer"][cfg["default_lang"]]
        cols = []
        for title, links in cols_cfg["columns"]:
            items = "".join(
                f'<li><a href="{self.href(page, h[1:]) if h.startswith("@") else h}">{esc(t)}</a></li>'
                for t, h in links
            )
            cols.append(f'<div class="foot-col"><h4>{esc(title)}</h4><ul>{items}</ul></div>')
        cols_html = "".join(cols)
        paras = "".join(f"<p>{p}</p>" for p in cols_cfg["paragraphs"])
        return f"""</main>
<footer class="site-foot">
<div class="wrap">
<div class="foot-cols">{cols_html}</div>
<div class="foot-note">{paras}</div>
</div>
</footer>
<script src="{rel(page["dir"], "assets/site.js")}" defer></script>
</body>
</html>
"""

    def render(self, page: dict) -> str:
        """渲染一页。

        正文可以是字符串，也可以是 `(lang, href) -> html` 的函数 —— 后者用于需要在**渲染期**
        才知道相对链接的页面（同一份内容要在 `/` 与 `/zh/download/` 两种深度下都算对路径）。
        """
        body = page["body"]
        if callable(body):
            body = body(page["lang"], lambda key: self.href(page, key))
        # 正文里用 `{LINK:page-key}` 标记"站内跳转"、`{IMG:name}` 标记"站点图片"，
        # 在这里统一换成相对路径：这样内容文件不必知道自己在哪一层目录下。
        body = re.sub(
            r"\{LINK:([a-zA-Z0-9_-]+)\}",
            lambda m: self.href(page, m.group(1)),
            body,
        )
        body = re.sub(
            r"\{IMG:([a-zA-Z0-9_.-]+)\}",
            lambda m: self.asset(page, m.group(1)),
            body,
        )
        crumbs = page.get("breadcrumb")
        crumb_html = breadcrumb([(t, self.href(page, k) if k else "") for t, k in crumbs]) if crumbs else ""
        head = self.head(page)
        # 面包屑插在 <main> 之后、正文之前
        return head + self.header(page) + crumb_html + body + self.footer(page)

    # ---- 其它文件 ----

    def sitemap(self) -> str:
        base = self.cfg["base_url"].rstrip("/")
        urls = []
        for p in self.pages:
            if p.get("noindex"):
                continue
            urls.append(
                f"  <url><loc>{esc(base + '/' + p['path'])}</loc>"
                f"<priority>{p.get('priority', '0.6')}</priority></url>"
            )
        return (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
            + "\n".join(urls)
            + "\n</urlset>\n"
        )

    def robots(self) -> str:
        base = self.cfg["base_url"].rstrip("/")
        return f"User-agent: *\nAllow: /\n\nSitemap: {base}/sitemap.xml\n"

    def not_found(self) -> str:
        """404 页：给出**真实可用**的去处，而不是一句空话。

        用默认语言渲染（GitHub Pages 的 404 无法按请求语言分流），导航与页脚照常，
        这样迷路的访客一键就能回到正轨。
        """
        cfg = self.cfg
        lang = cfg["default_lang"]
        page = {
            "key": "404",
            "lang": lang,
            "dir": "",
            "path": "404.html",
            "title": self.ui(lang, "notfound_title"),
            "noindex": True,
        }
        links = [
            (self.label((k, labels), lang), rel("", self.index[k][lang]))
            for k, labels in cfg["nav"]
        ]
        body = (
            f'<section><h1>{esc(self.ui(lang, "notfound_title"))}</h1>'
            f'<p class="lead">{esc(self.ui(lang, "notfound_body"))}</p>'
            + buttons(links)
            + "</section>"
        )
        fake = dict(page)
        fake["body"] = body
        fake["desc"] = self.ui(lang, "notfound_body")
        return self.head(fake) + self.header(fake) + body + self.footer(fake)

    # ---- 顶层 ----

    def build(self) -> int:
        """渲染并落盘。

        【不会清空输出目录】输出目录里可能本来就有**人工维护**的文档（例如 TurboDL 的
        `docs/plugins/*.md`、`docs/i18n/*`）；整目录删除会把它们一起抹掉。所以这里读上次的
        产物清单，只删「上次由本站点生成、这次不再生成」的文件。
        """
        manifest_path = self.out / MANIFEST
        previous: set[str] = set()
        if manifest_path.exists():
            try:
                previous = set(json.loads(manifest_path.read_text(encoding="utf-8")))
            except (ValueError, OSError):
                previous = set()

        written: set[str] = set()

        def emit(rel_path: str, text: str) -> None:
            write(self.out / rel_path, text)
            written.add(rel_path)

        self.out.mkdir(parents=True, exist_ok=True)
        emit(".nojekyll", "")
        for page in self.pages:
            emit(page["path"], self.render(page))
        emit("sitemap.xml", self.sitemap())
        emit("robots.txt", self.robots())
        emit("404.html", self.not_found())

        # 静态资源原样拷贝（图片压缩由 make_assets.py 负责，这里不做二次处理）
        if self.assets_dir.exists():
            for src in sorted(self.assets_dir.rglob("*")):
                if src.is_file() and not src.name.startswith("."):
                    rel_path = "assets/" + str(src.relative_to(self.assets_dir)).replace("\\", "/")
                    dst = self.out / rel_path
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(src, dst)
                    written.add(rel_path)

        # 清理上次生成、这次不再有的文件；空目录一并收掉
        for stale in sorted(previous - written):
            target = self.out / stale
            if target.is_file():
                target.unlink()
        for d in sorted((p for p in self.out.rglob("*") if p.is_dir()), reverse=True):
            if d != self.out and not any(d.iterdir()):
                d.rmdir()

        write(manifest_path, json.dumps(sorted(written), ensure_ascii=False, indent=1) + "\n")
        return len(self.pages)


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    cfg = load_content(repo_root)
    site = Site(cfg, repo_root)
    n = site.build()
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")
    print(f"[build] {cfg['name']}: {n} 页 → {site.out}  ({stamp})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
