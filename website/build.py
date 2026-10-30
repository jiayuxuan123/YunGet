#!/usr/bin/env python3
"""YunGet 官网的静态站点生成器（纯标准库，YunGet 专用）。

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
    asset,
    asset_fingerprint,
    breadcrumb,
    buttons,
    cards,
    code_block,
    esc,
    fmt_bytes,
    note,
    rel,
    section,
    set_asset_fingerprints,
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
        # 图片来源署名：由 assets/credits.json（下载图片时自动记录）生成，
        # **不手写** —— 手写的署名迟早与真实来源脱节，而技能要求每张外来的
        # 真实图片都在页面上署名。没有这个文件（还没下载过图片）时为空串。
        self.foot_credits: str = self._build_credits()
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

    def _build_credits(self) -> str:
        """把 assets/credits.json 里的图片来源拼成一行页脚署名。

        格式：`标题（作者，许可，via 来源）` —— 技能要求署名可核查：
        标题、作者、许可、来源四样齐全，点标题回到原始页面。
        """
        path = self.assets_dir / "credits.json"
        if not path.exists():
            return ""
        try:
            items = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return ""
        parts = []
        for it in items if isinstance(items, list) else []:
            title = esc(it.get("title") or it.get("file") or "图片")
            page = it.get("page") or it.get("image") or ""
            creator = esc(it.get("creator") or "")
            lic = esc(it.get("license") or "")
            via = esc(it.get("via") or "")
            link = f'<a href="{esc(page)}">{title}</a>' if page else title
            meta = "，".join(x for x in (creator, lic, f"via {via}" if via else "") if x)
            parts.append(f"{link}（{meta}）" if meta else link)
        return "；".join(parts)

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
<meta name="color-scheme" content="light dark">
<meta name="theme-color" content="#f1eadb" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#171812" media="(prefers-color-scheme: dark)">
<link rel="icon" href="{rel(page["dir"], "assets/favicon.svg")}" type="image/svg+xml">
<link rel="apple-touch-icon" href="{rel(page["dir"], "assets/apple-touch-icon.png")}">
<link rel="preload" href="{rel(page["dir"], "assets/fonts/schibsted-grotesk-latin.woff2")}" as="font" type="font/woff2" crossorigin>
<!-- 主题在样式表之前落地，避免深色偏好下闪白。 -->
<script>document.documentElement.classList.add("js");try{{var t=localStorage.getItem("theme");if(t==="light"||t==="dark")document.documentElement.setAttribute("data-theme",t)}}catch(e){{}}setTimeout(function(){{document.documentElement.classList.add("motion-fallback")}},1800);</script>
<link rel="stylesheet" href="{asset(page["dir"], "site.css")}">
</head>
<body class="{esc(cfg.get('body_class', ''))}" data-page="{esc(page["key"])}" data-kind="{esc(self.page_kind(page))}">
<a class="skip" href="#main">{esc(self.ui(lang, "skip"))}</a>
"""

    def page_kind(self, page: dict) -> str:
        """页面类别：只影响视觉编排，不改变任何内容或路由。"""
        key = page.get("key", "")
        if key == "home":
            return "home"
        if key.startswith(("doc-", "legal-")):
            return "document"
        return "inner"

    def header(self, page: dict) -> str:
        """顶栏：运单抬头、路线导航、主题开关与快速取件入口。"""
        cfg = self.cfg
        lang = page["lang"]
        brand_href = rel(page["dir"], self.index["home"].get(lang) or self.index["home"][cfg["default_lang"]])
        nav_items = []
        for i, (key, _labels) in enumerate(cfg["nav"], 1):
            href = self.href(page, key)
            active = ' aria-current="page"' if page["key"] == key else ""
            nav_items.append(
                f'<a href="{href}"{active}><span class="nav-index" aria-hidden="true">{i:02d}</span>'
                f'<span>{esc(self.label((key, _labels), lang))}</span></a>'
            )
        nav = "".join(nav_items)

        # 语言切换：同一 page key 在另一个语言下的地址；目标语言没有该页时退回其首页（不做死链）
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
                        target = self.index["home"].get(code)
                    links.append(f'<a href="{rel(page["dir"], target)}">{esc(self.label((code, _lab), lang))}</a>')
            switcher = '<div class="langs" aria-label="语言">' + "".join(links) + "</div>"

        dl_href = self.href(page, "download")
        nav_titles = {key: labels for key, labels in cfg["nav"]}
        if page.get("key") in nav_titles:
            route_title = esc(self.label((page["key"], nav_titles[page["key"]]), lang))
        else:
            route_title = esc(page.get("title", cfg["name"]))
        theme_label = esc(self.ui(lang, "theme"))
        menu_label = esc(self.ui(lang, "menu"))

        return f"""<header class="top">
<div class="top-rule">
<div class="wrap top-rule-in">
<span>YUNGET / CLOUD COURIER</span>
<span class="route-title">{route_title}</span>
<span>ANDROID · OPEN SOURCE</span>
</div>
</div>
<div class="wrap top-in">
<a class="brand" href="{brand_href}" aria-label="{esc(cfg['name'])} 首页">
<span class="brand-mark"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="24" height="24" aria-hidden="true"><rect width="32" height="32" fill="#c83f2e"/><rect x="3" y="3" width="26" height="26" fill="#f1eadb"/><path d="M8 8h16v7H8z" fill="none" stroke="#181914" stroke-width="1.8"/><path d="M8 13h16M12 8v5M20 8v5" fill="none" stroke="#181914" stroke-width="1.5"/><path d="M10 18h5l1.5 2 1.5-2h4" fill="none" stroke="#c83f2e" stroke-width="1.8" stroke-linecap="square"/><path d="M16 20v5M12.5 22.5 16 26l3.5-3.5" fill="none" stroke="#181914" stroke-width="1.8" stroke-linecap="square" stroke-linejoin="miter"/></svg></span>
<span class="brand-copy"><strong>{esc(cfg['name'])}</strong><small>云端包裹 / CLOUD COURIER</small></span>
</a>
<nav class="top-nav" id="top-nav" aria-label="主导航">{nav}</nav>
<div class="top-actions">
{switcher}
<button class="theme-toggle" type="button" data-theme-toggle aria-label="{theme_label}" aria-pressed="false">
<svg class="theme-sun" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.42-1.42M17.66 6.34l1.41-1.41"/></svg>
<svg class="theme-moon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M20.5 14.2A8.5 8.5 0 0 1 9.8 3.5 8.5 8.5 0 1 0 20.5 14.2Z"/></svg>
<span class="theme-label">夜间</span>
</button>
<a class="icon-link" href="https://github.com/jiayuxuan123/YunGet" aria-label="在 GitHub 查看 YunGet">
<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 .7a11.5 11.5 0 0 0-3.64 22.41c.58.1.79-.25.79-.56v-2.17c-3.22.7-3.9-1.37-3.9-1.37-.53-1.34-1.29-1.7-1.29-1.7-1.05-.72.08-.7.08-.7 1.16.08 1.78 1.2 1.78 1.2 1.03 1.77 2.71 1.26 3.37.96.1-.75.4-1.26.73-1.55-2.57-.29-5.27-1.28-5.27-5.69 0-1.26.45-2.29 1.19-3.1-.12-.29-.52-1.46.11-3.05 0 0 .97-.31 3.16 1.18a10.9 10.9 0 0 1 5.76 0c2.2-1.49 3.16-1.18 3.16-1.18.63 1.59.23 2.76.11 3.05.74.81 1.19 1.84 1.19 3.1 0 4.42-2.71 5.39-5.29 5.68.42.36.78 1.06.78 2.14v3.17c0 .31.21.67.8.56A11.5 11.5 0 0 0 12 .7Z"/></svg>
</a>
<a class="top-download" href="{dl_href}">取件<span aria-hidden="true">↘</span></a>
<button class="nav-toggle" type="button" aria-expanded="false" aria-controls="top-nav" aria-label="{menu_label}">
<span></span><span></span><span></span>
</button>
</div>
</div>
<div class="route-progress" aria-hidden="true"><i></i></div>
</header>
<main id="main">
"""

    def footer(self, page: dict) -> str:
        """页脚：一张完整的出库运单，而不是普通站点地图。"""
        cfg = self.cfg
        lang = page["lang"]
        cols_cfg = cfg["footer"][lang] if lang in cfg["footer"] else cfg["footer"][cfg["default_lang"]]
        cols = []
        for title, links in cols_cfg["columns"]:
            items = "".join(
                f'<li><a href="{self.href(page, h[1:]) if h.startswith("@") else h}">{esc(t)}</a></li>'
                for t, h in links
            )
            cols.append(f'<section class="foot-col"><h2>{esc(title)}</h2><ul>{items}</ul></section>')

        paras = "".join(f"<p>{p}</p>" for p in cols_cfg["paragraphs"])
        credits = getattr(self, "foot_credits", "")
        credits_block = (
            f'<section class="foot-credits"><h2>{esc(self.ui(lang, "credits"))}</h2><p>{credits}</p></section>'
            if credits else ""
        )

        dl_href = self.href(page, "download")
        return f"""</main>
<footer class="site-foot">
<div class="foot-tear" aria-hidden="true"></div>
<div class="wrap foot-mast">
<div class="foot-stamp" aria-hidden="true"><span>云取</span><strong>已封装</strong><small>SEALED</small></div>
<div class="foot-mark"><span>YUNGET</span><small>把下一条链接，变成文件。</small></div>
<a class="foot-cta" href="{dl_href}">
<span><small>OUTBOUND PARCEL</small><strong>{esc(self.ui(lang, "foot_cta"))}</strong></span>
<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg>
</a>
</div>
<div class="wrap foot-ledger">
<nav class="foot-nav-grid" aria-label="页脚导航">{"".join(cols)}</nav>
<div class="foot-fine">
<section class="foot-meta"><h2>项目身份 / MANIFEST</h2>{paras}</section>
{credits_block}
</div>
</div>
<div class="wrap foot-bottom">
<span>{esc(cfg['name'])} · CLOUD COURIER</span>
<span>OPEN SOURCE / AGPL-3.0</span>
<a href="https://github.com/jiayuxuan123/YunGet">SOURCE ↗</a>
</div>
</div>
</footer>
<script src="{asset(page["dir"], "site.js")}" defer></script>
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
        if page.get("key") != "home" and body.startswith('<section class="hero">'):
            body = body.replace('<section class="hero">', '<section class="hero page-intro">', 1)
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
        # 页面画布统一包裹正文：外壳、内页与文档共享路线标记，但不共享内容结构。
        canvas = (
            f'<div class="page-canvas" data-page="{esc(page["key"])}">'
            + crumb_html
            + body
            + "</div>"
        )
        return head + self.header(page) + canvas + self.footer(page)

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
            '<section class="notfound">'
            '<div class="notfound-code" aria-hidden="true"><span>4</span><i></i><span>4</span></div>'
            '<p class="route-kicker">ROUTE NOT FOUND / 运单退回</p>'
            f'<h1>{esc(self.ui(lang, "notfound_title"))}</h1>'
            f'<p class="lead">{esc(self.ui(lang, "notfound_body"))}</p>'
            + buttons(links)
            + '</section>'
        )
        fake = dict(page)
        fake["body"] = body
        fake["desc"] = self.ui(lang, "notfound_body")
        canvas = f'<div class="page-canvas" data-page="404">{body}</div>'
        return self.head(fake) + self.header(fake) + canvas + self.footer(fake)

    # ---- 顶层 ----

    def build(self) -> int:
        """渲染并落盘。

        【不会清空输出目录】输出目录里可能还有人工维护的内容；整目录删除会一并抹掉。
        所以这里读上次的
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

        # 资源指纹要在渲染之前算好：页面里的 <link>/<script> 要引用带指纹的文件名，
        # 而指纹文件名下的文件也要被复制到输出目录。
        set_asset_fingerprints(self.assets_dir)

        self.out.mkdir(parents=True, exist_ok=True)
        emit(".nojekyll", "")
        for page in self.pages:
            emit(page["path"], self.render(page))
        emit("sitemap.xml", self.sitemap())
        emit("robots.txt", self.robots())
        emit("404.html", self.not_found())

        # 静态资源原样拷贝（图片压缩由 make_assets.py 负责，这里不做二次处理）。
        # .css/.js 额外写一份**带内容指纹**的文件名：页面引用的是那个名字，
        # 浏览器 URL 变了缓存自然失效（见 sitekit.asset 的说明）。
        if self.assets_dir.exists():
            for src in sorted(self.assets_dir.rglob("*")):
                if src.is_file() and not src.name.startswith("."):
                    rel_path = "assets/" + str(src.relative_to(self.assets_dir)).replace("\\", "/")
                    dst = self.out / rel_path
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(src, dst)
                    written.add(rel_path)

                    fingerprinted = asset_fingerprint(src.name)
                    if fingerprinted and fingerprinted != src.name:
                        f_dst = self.out / ("assets/" + fingerprinted)
                        shutil.copy2(src, f_dst)
                        written.add("assets/" + fingerprinted)

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
