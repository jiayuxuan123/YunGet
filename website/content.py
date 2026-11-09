#!/usr/bin/env python3
"""YunGet 官网的内容定义（中文）。

## 这份文件里只有"事实"

页面正文全部来自仓库里**可核对**的东西：README 的功能清单与支持平台、
`build.gradle.kts` 的版本与 ABI、`NOTICE` 的署名义务、`SupportScreen.kt` 的赞赏文案、
`website/data/releases.json`（由 `refresh_releases.py` 从 GitHub API 抓取），
以及 `YunGet-Plugins/plugins.json`（官方插件源的真实索引）。

## 六条纪律（对应《YunGet 插件化架构设计文档》第 19 / 21 / 22 / 23 / 26 章）

1. **首屏三问答**：YunGet 是什么 / 现在能做什么 / 去哪里下载。
2. **只用真实素材**：应用界面使用实际 Compose 组件生成的脱敏预览，由 `data/screenshots.json` 记录版本。旧版照片中另外三张
   分别含真实账号昵称与用量（Login.jpg）、明文签名直链（Link.jpg）、上游旧版界面（about.jpg），
   **不上站**。
3. **平台区不造假按钮**：Android 有真实构建就做成可下载；Windows/macOS/Linux 没有构建，
   就如实标注"规划中"且**不可点**（设计文档第 23 章：只有真正提供可用构建时才显示为可下载）。
4. **下载信息可核对**：版本、字节数、SHA-256 全部取自 GitHub API 的附件元数据。
5. **不写"绝对安全"**：不声称不存在的验证流程。
6. **插件页照实写**：插件系统已经发布（JS 插件 + 插件市场），所以导航里有 `/plugins` 与
   `/developers` 两个入口；插件列表读的是官方源仓库的 `plugins.json`（真实条目，读不到
   就如实说明，不摆占位卡片）。信任等级按设计文档第 26 章写，**不写"绝对安全"**。
7. **文档只写一遍**：官网上的每一篇文档都是仓库里的一个 `.md` 文件，由 `mdlite.py`
   现场渲染 —— 官网一份、GitHub 一份，但源头只有一个。想改文案，改仓库里的文件即可。
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import mdlite
import transit
from sitekit import esc

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DATA = json.loads((HERE / "data" / "releases.json").read_text(encoding="utf-8"))
LATEST = DATA["latest"]
RELEASES = DATA["releases"]
VERSION = LATEST["tag"].lstrip("v")

#: 与 app/build.gradle.kts 保持一致的构建口径。
#:
#: ★ 这些值**从 app/build.gradle.kts 里读**，不再手写。
#:   手写的代价是实打实的：0.2.1.0 那次发版漏改了这里，站上一直显示 0.2.0.8，
#:   直到手机端验收时才被看见；这一次同样的坑又让站上停在 0.2.1.0，
#:   而实际已经是 0.2.4.0。**同一个错犯了两次**，所以根子上换掉做法：
#:   数字只有一个来源，改代码时自然跟着变。
def _read_gradle_fact(pattern: str, default: str = "") -> str:
    """从 app/build.gradle.kts 里取一个值（正则第一组捕获）。

    读不到就返回默认值 —— 站点构建不该因为读不到一个数字而整个挂掉。
    """
    import re as _re
    gradle = Path(__file__).resolve().parent.parent / "app" / "build.gradle.kts"
    try:
        text = gradle.read_text(encoding="utf-8")
    except OSError:
        return default
    m = _re.search(pattern, text)
    return m.group(1) if m else default


#: minSdk / targetSdk：与 app/build.gradle.kts 同源。
_MIN_SDK_NUM = _read_gradle_fact(r"minSdk\s*=\s*(\d+)", "24")
#: Android 版本名与 API 等级对照（只列这两个 App 实际会用到的）。
_ANDROID_BY_API = {"23": "6.0", "24": "7.0", "25": "7.1", "26": "8.0",
                  "27": "8.1", "28": "9", "29": "10", "30": "11", "31": "12",
                  "32": "12L", "33": "13", "34": "14", "35": "15", "36": "16"}
MIN_SDK = f"Android {_ANDROID_BY_API.get(_MIN_SDK_NUM, _MIN_SDK_NUM)}（API {_MIN_SDK_NUM}）"
MIN_SDK_SHORT = f"{_ANDROID_BY_API.get(_MIN_SDK_NUM, _MIN_SDK_NUM)}+"
TARGET_SDK = "API " + _read_gradle_fact(r"targetSdk\s*=\s*(\d+)", "34")

#: YunGet 内置的下载引擎版本：**从 app/build.gradle.kts 的 dev.turbodl 依赖读**。
#: 那五个 implementation("dev.turbodl:*:X") 是唯一来源，站上不再另抄一份。
TURBODL_VERSION = _read_gradle_fact(r'dev\.turbodl:turbodl-core:([0-9.]+)', "0.0.0")

REPO = "https://github.com/jiayuxuan123/YunGet"
BLOB = f"{REPO}/blob/main"
UPSTREAM = "https://github.com/CYQawa/YunX"
TURBODL_REPO = "https://github.com/jiayuxuan123/TurboDL"
DOWNLOAD_EDGE = "https://dl.yunl.us.ci"
COMMUNITY_URL = "https://community.yunget.yunl.us.ci"


def _download_edge_url(url: str) -> str:
    """Map only this project's immutable Release assets to the Cloudflare edge."""
    mappings = (
        ("https://github.com/jiayuxuan123/YunGet/releases/download/", "yunget"),
        ("https://github.com/jiayuxuan123/TurboDL/releases/download/", "turbodl"),
    )
    for prefix, project in mappings:
        if url.startswith(prefix):
            return f"{DOWNLOAD_EDGE}/{project}/releases/download/{url[len(prefix):]}"
    return url

APKS = [a for a in LATEST["assets"] if a["name"].lower().endswith(".apk") and "kernel" not in a["name"].lower()]
APK = next((a for a in APKS if "universal" in a["name"].lower()), None) or next(iter(APKS), None)

#: 文档里的占位符 → 实际值。文档里写 `{{引擎版本}}` 而不是手写版本号：
#: 手写的必然滞后（0.2.0.6 那版在站上挂了很久没人发现），改用占位符后
#: 版本号的唯一来源是 app/build.gradle.kts，改代码时文档跟着变。
DOC_VARIABLES = {
    "引擎版本": TURBODL_VERSION,
    "应用版本": VERSION,
    "最低安卓": MIN_SDK_SHORT,
}

#: 官方插件源仓库。索引、脚本、公钥都在这里，App 拉的是它根目录下的 `plugins.json`。
PLUGINS_REPO = "https://github.com/jiayuxuan123/YunGet-Plugins"
PLUGIN_INDEX_URL = f"{PLUGINS_REPO}/blob/main/plugins.json"
PLUGIN_INDEX_RAW = f"https://raw.githubusercontent.com/jiayuxuan123/YunGet-Plugins/main/plugins.json"
#: 引擎（TurboDL）官网 —— 插件 ABI 与 schema 的出处。
TURBODL_SITE = "https://jiayuxuan123.github.io/TurboDL/"

#: 官方源索引在本机的路径（`YunGet/` 与 `YunGet-Plugins/` 是同级目录）。
PLUGIN_INDEX_PATH = Path(__file__).resolve().parent.parent.parent / "YunGet-Plugins" / "plugins.json"


def _load_plugin_index() -> dict | None:
    """读官方源的 `plugins.json`（真实数据）。读不到返回 `None`，由调用方降级。

    官网构建**不能**依赖同级目录里恰好有一份插件源仓库：CI 上只 clone 了本站仓库、
    或者索引正被重新生成时，这个文件都可能不在。所以这里吞掉所有读取/解析异常，
    让页面退回一句"暂时读不到插件源数据"，而不是让整站构建失败。
    """
    try:
        return json.loads(PLUGIN_INDEX_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


PLUGIN_INDEX = _load_plugin_index()

# --------------------------------------------------------------------------- #
# 事实表
# --------------------------------------------------------------------------- #

PLATFORMS = [
    ("夸克网盘", "网页登录", "解析、浏览目录、取直链、下载"),
    ("UC 网盘", "网页登录", "解析、浏览目录、取直链、下载"),
    ("迅雷网盘", "账号密码 / 短信", "解析、取直链；取链后清理临时转存"),
    ("百度网盘", "网页登录", "解析、取直链；**不推荐**，可能触发风控"),
    ("123 云盘", "网页登录", "解析、下载"),
    ("139 网盘（和彩云）", "网页登录", "解析、下载"),
    ("115 网盘", "应用内登录页", "解析、下载"),
    ("光鸭云盘", "应用内登录页", "解析、下载"),
    ("蓝奏云", "网页登录", "解析、下载"),
    ("蓝奏云优享版", "账号密码", "解析、下载"),
]

# --------------------------------------------------------------------------- #
# 文档页：官网上的每一篇文档，源头都是仓库里的那个文件
# --------------------------------------------------------------------------- #

#: `src` 是仓库内相对路径（`None` 表示这一篇由代码现场生成）；`raw` 为真时按纯文本排版，
#: 不做 Markdown 重排（LICENSE、NOTICE 这类文件必须保持原样，一字不改）。
DOC_PAGES = [
    {
        "key": "doc-readme",
        "slug": "readme",
        "title": "项目说明",
        "blurb": "它是什么、支持哪些网盘、能做什么、怎么从源码构建 —— 就是仓库里的那篇 README。",
        "src": "README.md",
    },
    {
        "key": "doc-guide",
        "slug": "guide",
        "title": "使用说明",
        "blurb": "从粘贴一条分享链接开始：解析、浏览、取直链、下载，以及登录与凭证怎么处理。",
        "src": "docs/GUIDE.md",
    },
    {
        "key": "doc-faq",
        "slug": "faq",
        "title": "常见问题",
        "blurb": "解析失败、速度慢、换机要重登、更新检不出来 —— 这些问题的成因与处理办法。",
        "src": "docs/FAQ.md",
    },
    {
        "key": "doc-build",
        "slug": "build",
        "title": "从源码构建",
        "blurb": "需要的 JDK 与 SDK、签名怎么配、测试怎么跑，以及国内网络环境下的仓库镜像。",
        "src": "docs/BUILD.md",
    },
    {
        "key": "doc-engines",
        "slug": "engines",
        "title": "下载引擎",
        "blurb": "TurboDL 负责什么、应用里为什么不止一个引擎、什么时候会用到哪一个。",
        "src": "docs/ENGINES.md",
    },
    # ---- 插件四篇（`/developers` 上那张卡片墙就是这四个入口）----
    {
        "key": "doc-plugin-dev",
        "slug": "plugin-dev",
        "title": "写一个插件",
        "blurb": "插件是一段 JavaScript：能做什么、不能做什么，以及从零写一个能跑的解析器。",
        "src": "docs/PLUGIN-DEV.md",
    },
    {
        "key": "doc-plugin-api",
        "slug": "plugin-api",
        "title": "插件 ABI 参考",
        "blurb": "脚本能用的每一样东西：plugin / host 两个对象、能力、错误码与所有上限。",
        "src": "docs/PLUGIN-API.md",
    },
    {
        "key": "doc-plugin-publish",
        "slug": "plugin-publish",
        "title": "打包与发布",
        "blurb": "清单怎么写、签名怎么算、怎么提 PR 进官方源、怎么自建一个源、怎么发新版本。",
        "src": "docs/PLUGIN-PUBLISH.md",
    },
    {
        "key": "doc-plugin-security",
        "slug": "plugin-security",
        "title": "插件安全模型",
        "blurb": "信任四级、校验链，以及这份机制不做（也不打算假装做了）的那些承诺。",
        "src": "docs/PLUGIN-SECURITY.md",
    },
    {
        "key": "legal-license",
        "slug": "license",
        "title": "开源协议（AGPL-3.0）",
        "blurb": "本项目基于 GNU AGPL-3.0 开源，这里是协议原文，一字未改。",
        "src": "LICENSE",
        "raw": True,
    },
    {
        "key": "legal-notice",
        "slug": "notice",
        "title": "署名与致谢",
        "blurb": "上游 CYQawa/YunX 的版权与协议声明，以及本项目与上游的关系说明。",
        "src": "NOTICE",
        "raw": True,
    },
    {
        "key": "legal-privacy",
        "slug": "privacy",
        "title": "隐私说明",
        "blurb": "网盘凭证存在哪、会发给谁，以及哪些数据从来不离开这台设备。",
        "src": "docs/PRIVACY.md",
    },
    {
        "key": "legal-terms",
        "slug": "terms",
        "title": "使用条款与免责声明",
        "blurb": "使用者的责任、软件提供到什么程度、下载内容的版权归属。",
        "src": "docs/TERMS.md",
    },
]

#: 仓库路径 → 文档页。文档正文里的相对链接靠它落到站内，落不到就指回 GitHub。
DOC_BY_SRC = {d["src"]: d for d in DOC_PAGES if d["src"]}

DOC_GROUPS = {
    "docs": "文档",
    "legal": "协议与说明",
}


def _resolver(src_path: str, page_href):
    """把文档里的链接改写到站内（或 GitHub）。

    文档里写的是 `FAQ.md`、`../README.md` 这类仓库内相对路径 —— 原样搬到官网上就是死链。
    这里按「相对当前文件解析 → 查站点文档表 → 查不到就指到 GitHub」处理，
    所以写文档的人不必为了网站改写法。
    """
    base_dir = str(Path(src_path).parent).replace("\\", "/")
    if base_dir == ".":
        base_dir = ""

    def link(url: str, kind: str = "link") -> str:
        if url.startswith(("http://", "https://", "mailto:", "#", "data:")):
            return url
        head, _, tail = url.partition("#")
        parts: list[str] = []
        for seg in (base_dir + "/" + head).split("/"):
            if seg in ("", "."):
                continue
            if seg == "..":
                if parts:
                    parts.pop()
                continue
            parts.append(seg)
        target = "/".join(parts)
        doc = DOC_BY_SRC.get(target)
        if doc:
            return page_href(doc["key"]) + (f"#{tail}" if tail else "")
        return f"{BLOB}/{target}" + (f"#{tail}" if tail else "")

    return link


def _page_doc(doc: dict, lang: str, href) -> str:
    """渲染一篇镜像文档：标题区 + 目录侧栏 + 正文。"""
    src = doc["src"]
    if src is None:
        body, toc = "", ""
        src_label = "（由代码生成）"
    else:
        text = (ROOT / src).read_text(encoding="utf-8")
        src_label = src
        if doc.get("raw"):
            body = f'<pre class="doc-raw">{esc(text.strip())}</pre>'
            toc = ""
        else:
            # 文档的首行标题就是页面顶部的 <h1>，去掉，免得同一句话出现两次。
            # 去掉之后文档里的 `##` 落到页面的 <h2>，标题层级不断档（所以 offset=0）；
            # 万一某篇没有首行标题，就退回 offset=1，保证页面里只有一个 <h1>。
            text, dropped = re.subn(r"\A\s*#[^\n]*\n", "", text, count=1)
            body, toc = mdlite.render(
                text, _resolver(src, href), h_offset=0 if dropped else 1, toc_max=3,
                variables=DOC_VARIABLES,
            )

    src_url = f"{BLOB}/{src}" if src else REPO
    meta = (
        '<div class="doc-meta">'
        f'<span class="mono small">{esc(src_label)}</span>'
        f'<a class="mono small" href="{esc(src_url)}">在 GitHub 上查看</a>'
        f'<a class="mono small" href="{esc(src_url.replace("/blob/", "/raw/"))}">原文下载</a>'
        "</div>"
    )
    aside = f'<aside class="doc-aside">{toc}{_doc_nav(doc, href)}</aside>'
    return (
        f'<section class="doc-head"><h1>{esc(doc["title"])}</h1>'
        f'<p class="lead">{esc(doc["blurb"])}</p></section>'
        + meta
        + f'<div class="doc-grid">{aside}<article class="doc-body">{body}</article></div>'
        + f'<p class="small"><a href="{href("docs")}">← 全部文档</a></p>'
    )


def _doc_nav(current: dict, href) -> str:
    """侧栏里的其它文档。"""
    items = []
    for d in DOC_PAGES:
        if d["key"] == current["key"]:
            items.append(f'<li class="cur"><span>{esc(d["title"])}</span></li>')
        else:
            items.append(f'<li><a href="{href(d["key"])}">{esc(d["title"])}</a></li>')
    # 与"本页目录"同理：手机上 12 条页面链接会把正文推出屏幕，折叠成一行标题。
    # 桌面端仍是常驻侧栏（summary 在宽屏被 site.css 隐藏）。
    return (
        '<details class="toc doc-nav" open><summary>全部页面</summary><ul>'
        + "".join(items)
        + "</ul></details>"
    )


# --------------------------------------------------------------------------- #
# 页面正文
# --------------------------------------------------------------------------- #

FACTS = {
    "screenshots": json.loads((HERE / "data" / "screenshots.json").read_text(encoding="utf-8")),
    "name": "云取 YunGet",
    "version": VERSION,
    "latest": LATEST,
    "releases": RELEASES,
    "apks": APKS,
    "apk": APK,
    "min_sdk": MIN_SDK,
    "min_sdk_short": MIN_SDK_SHORT,
    "target_sdk": TARGET_SDK,
    "turbodl_version": TURBODL_VERSION,
    "repo": REPO,
    "blob": BLOB,
    "upstream": UPSTREAM,
    "turbodl_repo": TURBODL_REPO,
    "turbodl_site": TURBODL_SITE,
    "download_edge": DOWNLOAD_EDGE,
    "community": COMMUNITY_URL,
    "plugins_repo": PLUGINS_REPO,
    "plugin_index_url": PLUGIN_INDEX_URL,
    "plugin_index_raw": PLUGIN_INDEX_RAW,
    "plugin_index": PLUGIN_INDEX,
    "platforms": PLATFORMS,
    "doc_pages": DOC_PAGES,
}


def _page_home(lang: str, href) -> str:
    return transit.home(FACTS, href)


def _page_download(lang: str, href) -> str:
    return transit.download(FACTS, href)


def _page_docs(lang: str, href) -> str:
    return transit.docs(FACTS, href)


def _page_plugins(lang: str, href) -> str:
    return transit.plugins(FACTS, href)


def _page_developers(lang: str, href) -> str:
    return transit.developers(FACTS, href)


def _page_changelog(lang: str, href) -> str:
    return transit.changelog(FACTS, href)


def _page_community(lang: str, href) -> str:
    return transit.community(FACTS, href)


def _page_support(lang: str, href) -> str:
    return transit.support(FACTS, href)


def _page_legal(lang: str, href) -> str:
    return transit.legal(FACTS, href)


# --------------------------------------------------------------------------- #
# 站点定义
# --------------------------------------------------------------------------- #

def _pages() -> list:
    group = [
        ("home", "index.html", "云取 YunGet —— 网盘分享链接解析与本地下载", "把支持的网盘分享链接解析为可下载文件，并交给设备上的 TurboDL 下载。", _page_home),
        ("download", "download/index.html", "下载", f"下载云取 YunGet {VERSION} 的 Android APK，含校验值与安装说明。", _page_download),
        ("plugins", "plugins/index.html", "插件", "云取的 JS 插件：官方插件源、安装方式、信任四级，以及插件能做什么、不经手什么。", _page_plugins),
        ("docs", "docs/index.html", "文档", "使用说明、常见问题、下载引擎、从源码构建、项目说明，以及四篇插件文档。", _page_docs),
        ("developers", "developers/index.html", "开发者", "写一个云取插件：能力边界、ABI 参考、最小示例，以及签名与发布的最短路径。", _page_developers),
        ("changelog", "changelog/index.html", "更新日志", "云取 YunGet 的版本历史，由发布说明生成。", _page_changelog),
        ("community", "community/index.html", "社区", "问题反馈、与上游的关系、开源协议与免责声明。", _page_community),
        ("support", "support/index.html", "支持开发", "赞赏方式，以及除了赞赏还能怎么帮上忙。", _page_support),
        ("legal", "legal/index.html", "协议与说明", "AGPL-3.0 原文、上游署名、隐私说明与使用条款。", _page_legal),
    ]
    out = [
        {
            "key": key,
            "lang": "zh",
            "path": path,
            "title": title,
            "desc": desc,
            "body": builder,
            # 首页标题里已经带了站名，模板不要再拼一次（否则是「…… · 云取 YunGet」）
            "title_is_full": key == "home",
            "priority": "1.0" if key == "home" else "0.7",
        }
        for key, path, title, desc, builder in group
    ]
    # 文档镜像页：每一篇都是仓库里的一个文件（协议原文照录，不做 Markdown 重排）
    for d in DOC_PAGES:
        sub = "docs" if d["key"].startswith("doc-") else "legal"
        out.append(
            {
                "key": d["key"],
                "lang": "zh",
                "path": f"{sub}/{d['slug']}/index.html",
                "title": d["title"],
                "desc": d["blurb"],
                "body": (lambda dd: (lambda l, h: _page_doc(dd, l, h)))(d),
                "breadcrumb": [
                    (DOC_GROUPS[sub], sub),
                    (d["title"], None),
                ],
                "priority": "0.6",
            }
        )
    return out


SITE = {
    "name": "云取 YunGet",
    "tagline": "网盘分享链接解析与本地下载",
    "base_url": "https://jiayuxuan123.github.io/YunGet",
    "out_dir": "docs",
    "assets_dir": "website/assets",
    "default_lang": "zh",
    "langs": [("zh", {"zh": "中文"})],
    "body_class": "brand-yunget",
    "mark_svg": (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="24" height="24" aria-hidden="true">'
        '<g fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round">'
        '<path d="M6 5v9.5M16 3.2v11.3M26 5v9.5"/></g>'
        '<path d="M16 15.6l6.2 6.2H9.8z" fill="currentColor"/>'
        '<path d="M16 21v7" stroke="currentColor" stroke-width="2.3" stroke-linecap="round"/></svg>'
    ),
    # 顶栏只放**最常走的五条路**。上一版列了九项（概览/下载/插件/文档/开发者/
    # 更新日志/社区/支持开发/协议），一眼看过去是一排按钮墙，而且把"下载"这个
    # 主行动淹掉了。其余入口在页脚按栏目分组列全（见下面 footer），一条也没少。
    "nav": [
        ("home", {"zh": "概览"}),
        ("download", {"zh": "下载"}),
        ("plugins", {"zh": "插件"}),
        ("docs", {"zh": "文档"}),
        ("developers", {"zh": "开发者"}),
    ],
    "ui": {
        "zh": {
            "skip": "跳到正文",
            "menu": "菜单",
            "theme": "切换深色 / 浅色主题",
            "notfound_title": "页面不存在",
            "notfound_body": "这个地址在本站不存在。全部内容就在下面这几处：",
            # 重构新增：页脚图片来源的署名标题与收尾瓦片的召唤语。
            # 署名必须上页面（技能要求每张外来的真实图片都在页面上署名）。
            "credits": "图片来源",
            "foot_cta": "下载通用 APK",
        }
    },
    "footer": {
        "zh": {
            "columns": [
                (
                    "项目",
                    [
                        ("GitHub", REPO),
                        ("下载", "@download"),
                        ("更新日志", "@changelog"),
                        ("问题反馈", f"{REPO}/issues"),
                    ],
                ),
                (
                    "文档",
                    [
                        ("项目说明", "@doc-readme"),
                        ("使用说明", "@doc-guide"),
                        ("常见问题", "@doc-faq"),
                        ("下载引擎", "@doc-engines"),
                        ("从源码构建", "@doc-build"),
                    ],
                ),
                (
                    "插件",
                    [
                        ("插件（市场与安装）", "@plugins"),
                        ("开发者（怎么写）", "@developers"),
                        ("写一个插件", "@doc-plugin-dev"),
                        ("插件 ABI 参考", "@doc-plugin-api"),
                        ("打包与发布", "@doc-plugin-publish"),
                        ("插件安全模型", "@doc-plugin-security"),
                    ],
                ),
                (
                    "协议与说明",
                    [
                        ("AGPL-3.0 原文", "@legal-license"),
                        ("署名与致谢", "@legal-notice"),
                        ("隐私说明", "@legal-privacy"),
                        ("使用条款", "@legal-terms"),
                        ("支持开发", "@support"),
                    ],
                ),
                (
                    "相关",
                    [
                        ("上游 CYQawa/YunX", UPSTREAM),
                        ("下载引擎 TurboDL", TURBODL_REPO),
                        ("插件源 YunGet-Plugins", PLUGINS_REPO),
                        ("社区", "@community"),
                    ],
                ),
            ],
            "paragraphs": [
                f"云取 YunGet {LATEST['tag']} · AGPL-3.0 · 内置 TurboDL {TURBODL_VERSION} · {MIN_SDK}",
                "本项目是 CYQawa/YunX（云析）的二次开发版本，与上游作者无隐性关联。"
                "仅供个人学习与技术交流，请勿用于商业用途；下载内容的版权归原作者及相关权利人所有。",
            ],
        }
    },
    "pages": _pages(),
}
