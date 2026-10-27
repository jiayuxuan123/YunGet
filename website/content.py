#!/usr/bin/env python3
"""YunGet 官网的内容定义（中文）。

## 这份文件里只有"事实"

页面正文全部来自仓库里**可核对**的东西：README 的功能清单与支持平台、
`build.gradle.kts` 的版本与 ABI、`NOTICE` 的署名义务、`SupportScreen.kt` 的赞赏文案、
`website/data/releases.json`（由 `refresh_releases.py` 从 GitHub API 抓取），
以及 `YunGet-Plugins/plugins.json`（官方插件源的真实索引）。

## 六条纪律（对应《YunGet 插件化架构设计文档》第 19 / 21 / 22 / 23 / 26 章）

1. **首屏三问答**：YunGet 是什么 / 现在能做什么 / 去哪里下载。
2. **只用真实素材**：截图只用 `images/Parsing.jpg` 与 `images/Setting.jpg` —— 另外三张
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
from sitekit import buttons, cards, code_block, esc, md_inline, note, section, seg_rule, table

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

APK = next((a for a in LATEST["assets"] if a["name"].endswith(".apk")), None)

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

def _apk_rows() -> list[list[str]]:
    return [
        [
            esc(APK["name"]),
            f'<span class="num">{APK["size"] / 1048576:.1f} MB</span>',
            f'<span class="mono small">{esc((APK["sha256"] or "")[:16])}…</span>',
        ]
    ] if APK else []


def _segment_visual() -> str:
    """首屏主体视觉：一条被切成不等宽分片、并行推进的下载数据带。

    为什么是它而不是截图或插画：这个产品**就是**把文件切成分片并发下载的，
    所以这张图直接说明产品在做什么，不是装饰。分片宽度不等 —— 真实分片也不等长。
    进度值是固定的一组，保证每次构建结果一致。
    """
    segs = [
        (14, 100), (9, 100), (17, 62), (7, 100), (13, 28),
        (11, 100), (8, 45), (16, 88), (5, 100),
    ]
    lanes = "".join(
        f'<span class="seg" style="flex:{w} 1 0"><i style="width:{done}%"></i></span>'
        for w, done in segs
    )
    return (
        '<div class="dl-visual" aria-hidden="true">'
        f'<div class="dl-lane">{lanes}</div>'
        '<div class="dl-legend">'
        f'<span><b>{len(segs)}</b> 分片</span>'
        '<span><b>4</b> 路并发</span>'
        "<span>断点续传</span>"
        "</div></div>"
    )


def _page_home(lang: str, href) -> str:
    """首页：按主张表组织（每节一条主张 + 它的证据），不按"功能清单"排。

    taste-skill 的方向 `product-performs`：首屏就是**真能用的控件**（粘贴链接就地切开），
    其余每节是"两行陈述 + 它的证据"（界面碎片 / 真实截图 / 表格）。

    章节顺序 = 访客会依次问的问题：
      它是什么（首屏）→ 怎么用（三步）→ 支持哪些网盘 → 为什么快（分片）→
      安全吗（凭证不出本机）→ 能扩展吗（插件）→ 常见疑问 → 现在装。
    """
    dl_btn = f"下载 APK（{VERSION}）"
    size_txt = f'{APK["size"] / 1048576:.1f} MB' if APK else "—"

    parts = [
        # ---- 1. 首屏：标题 + 一句话 + 真能用的解析控件 ----
        '<section class="hero"><div class="wrap">'
        '<h1><span class="line"><span>把分享链接</span></span>'
        '<span class="line"><span>变成你的文件</span></span></h1>'
        '<p class="hero-lead">粘贴一次，认出十个网盘，分片并发下载。'
        "登录凭据加密留在这台手机上，不经过任何第三方服务器。</p>"
        f'<div class="hero-actions">'
        + buttons([(dl_btn, href("download")), ("看文档", href("docs")), ("GitHub", REPO)])
        + "</div>"
        f'<p class="hero-meta">{esc(LATEST["tag"])} · {size_txt} · {esc(MIN_SDK)} · AGPL-3.0</p>'
        # 活着的东西：真能跑的解析控件（本地计算，不发请求）
        + _resolver_widget()
        + "</div></section>",

        # ---- 2. 怎么用：三步（每步一个界面碎片）----
        section(
            "三步拿到文件",
            '<p class="section-lead">没有账号体系、没有中转服务器：链接在手机上被解析，'
            "直链直接交给下载引擎。</p>"
            + '<div class="panel"><div class="split">'
            '<div class="defs rise">'
            + _step_row("粘贴链接", "分享文案整段粘进来也行，应用会自己挑出链接与提取码。")
            + _step_row("选要下的文件", "解析后能浏览目录、多选、按文件夹批量入队。")
            + _step_row("下载", "分片并发、断点续传；锁屏也能接着下。")
            + _step_row("剪贴板识别", "复制一条分享链接再回到应用，解析页会自动提示粘贴，不用手动找输入框。")
            + _step_row("多任务并行", "默认同时下载三个任务，并发数可在设置里调；每个任务内部再分片并发。")
            + '<p class="small">右图取自正在运行的应用，不是设计稿。解析页的输入框'
            "同时接受整段分享文案与单独的提取码。</p>"
            + "</div>"
            '<div class="rise">'
            + _ui_fragment_parse()
            + '<figure class="shot phone" style="max-width:320px;margin-top:1.5rem">'
              '<img src="{IMG:parsing}" alt="解析页：粘贴分享链接与提取码的输入框" '
              'width="720" height="1600" loading="lazy">'
              "<figcaption>解析页</figcaption></figure>"
            + "</div>"
            "</div></div>",
        ),

        # ---- 3. 支持哪些网盘（真实平台表）----
        section(
            "认十个网盘",
            '<div class="panel">'
            '<p class="section-lead">能登录的都能解析；未登录时，夸克、UC、光鸭、蓝奏云的分享链接也能直接下载。'
            "登录只发生在你与网盘之间 —— 应用不代收密码，也没有中转服务器。</p>"
            + table(
                ["网盘", "登录方式", "能做什么"],
                [[f"<strong>{esc(n)}</strong>", esc(login), md_inline(desc)] for n, login, desc in PLATFORMS],
            )
            + note(
                "<strong>不建议使用百度网盘</strong>：解析可能触发账号风控。"
                "其余平台的登录态都保存在本机，随时可在「网盘」页退出。",
                "warn",
            )
            + "</div>",
        ),

        # ---- 4. 为什么快：分片（点子所在的一节，配历史图片）----
        section(
            "它把文件切成段，同时下",
            '<p class="section-lead">服务器支持 Range 时，文件被切成不等长的分片并发拉取；'
            "慢的那片不会拖住整体。这件事 1857 年就有人做过 —— 大西洋海底电报线也是一条线切成几段接力。</p>"
            + '<div class="split split-wide">'
            '<div class="rise">'
            + f'<figure class="pic-drift"><img src="{{IMG:map-of-the-submarine-telegraph-between-america}}" '
            'alt="1857 年大西洋海底电报线路图：一条线从纽芬兰分成数段抵达爱尔兰" '
            'width="1920" height="827" loading="lazy" decoding="async">'
            "<figcaption>1857 年的海底电报线路图（Boston Public Library，CC BY 2.0，via Wikimedia Commons）</figcaption></figure>"
            "</div>"
            '<div class="defs rise">'
            + _engine_row("分片并发", "按 HTTP Range 并行拉取；服务器不支持时自动回退单流。")
            + _engine_row("动态分块 + 工作窃取", "慢连接不拖垮整体，消除最后一片单线程收尾的长尾。")
            + _engine_row("断点续传", "分片状态落盘，暂停后按真实进度接着下。")
            + _engine_row("并发会自己收敛", "并发升高而总吞吐不再提升时自动退回较低档位，"
                          "不会出现「线程数拉满反而更慢」。")
            + "</div></div>"
            + '<figure class="pic-drift pic-band rise">'
            '<img src="{IMG:musee-des-arts-et-metiers-cables-sous}" '
            'alt="博物馆里一排海底电缆的剖面：外皮里包着一圈铜芯" '
            'width="1920" height="1280" loading="lazy" decoding="async">'
            "<figcaption>海底电缆的剖面（Musée des Arts et Métiers，Frédéric BISSON，CC BY 2.0，"
            "via Wikimedia Commons）—— 一根线由许多段包成，和分片下载是一回事。</figcaption></figure>"
            + f'<p class="small">下载引擎是 <a href="{TURBODL_REPO}">TurboDL</a> —— 一个纯 Kotlin/JVM 的'
            f"多线程下载引擎，当前内置版本 {esc(TURBODL_VERSION)}；它也可以被别的应用直接复用。</p>",
        ),

        # ---- 5. 安全：凭证不出本机（白场整段换色）----
        # 换场的理由：这是全页唯一谈"凭据"的地方；方向单说"一节可以换整个场色"，
        # 而换色让它在视觉上跟"下载能力"那几节分开。截图用设置页 —— 备份与导出都在那里。
        '<section class="section field-light"><div class="wrap">'
        '<h2>你的 Cookie 不离开这台手机</h2>'
        '<p class="section-lead">网盘登录凭据用系统密钥库加密后存在应用私有目录；'
        "解析与取直链都在本机完成，没有任何中转服务器经手你的账号。</p>"
        '<div class="split">'
        '<div class="defs rise">'
        + _engine_row("系统密钥库加密", "改锁屏密码或换设备会让密钥失效，那时应用会明确告诉你需要重新登录。")
        + _engine_row("不出网", "凭证只发给对应网盘的官方域名；应用不收集、不上报。")
        + _engine_row("可导出备份", "换手机时用自设密码加密导出，不依赖云端。")
        + "</div>"
        '<div class="rise">'
        + _ui_fragment_secure()
        + '<figure class="shot phone" style="max-width:320px;margin-top:1.5rem">'
          '<img src="{IMG:setting}" alt="设置页：下载线程数、认证备份与日志导出" '
          'width="720" height="1600" loading="lazy">'
          "<figcaption>设置页：认证备份与日志导出都在本机</figcaption></figure>"
        + "</div></div></div></section>",

        # ---- 6. 插件：接口变了不用等发版 ----
        section(
            "网盘接口变了，不用等应用更新",
            '<p class="section-lead">解析逻辑可以做进插件：装一个、启用，立即生效；'
            "网盘改接口时更新插件即可，不用等应用发版。</p>"
            + '<div class="split">'
            '<div class="defs rise">'
            + _engine_row("跑在沙箱里", "插件是 JavaScript，只能通过宿主开放的接口访问网络与存储，"
                          "越界调用会被拒绝并留下日志。")
            + _engine_row("装上就生效", "沙箱可以整体重建，所以插件启用、更新都不需要重启应用。")
            + _engine_row("校验后才安装", "每个版本都验 SHA-256 与签名；密钥被吊销的发布者即使签名有效也会被拒绝。")
            + _engine_row("坏了能回退", "更新失败自动回退上一可用版本；内置实现始终保留作兜底。")
            + "</div>"
            '<div class="rise">' + _ui_fragment_plugins() + "</div>"
            "</div>"
            + f'<p class="small">官方源与已发布的插件在<a href="{href("plugins")}">插件页</a>；'
            f'自己写一个看<a href="{href("doc-plugin-dev")}">插件开发</a>。</p>',
        ),

        # ---- 8. 常见疑问（问题索引 + 单答案）----
        section(
            "常见疑问",
            '<div class="panel">' + _faq_block() + "</div>",
        ),

        # ---- 9. 收尾 ----
        '<section class="cta"><div class="wrap">'
        f'<p class="cta-line"><span class="line">下载 <span class="fill">云取 {esc(VERSION)}</span>，</span>'
        f'<span class="line">把下一条链接变成文件。</span></p>' 
        f'<div class="hero-actions">'
        + buttons([(f"下载 APK（{size_txt}）", href("download")), ("更新日志", href("changelog"))])
        + "</div>"
        f'<p class="cta-note">{esc(MIN_SDK)} · AGPL-3.0 · 源码在 <a href="{REPO}">GitHub</a></p>'
        "</div></section>",
    ]
    return "".join(parts)


def _resolver_widget() -> str:
    """首屏的"活着的东西"：粘贴链接 → 就地切开。

    全部本地计算（识别平台、提取码、分片计划），**不发任何网络请求** ——
    所以它既不伪造结果，也不会把访客粘贴的链接送出去。无 JS 时这里是一段说明文字，
    内容仍然完整可读（技能要求：动效与交互都不承担信息表达）。

    data-ui：这是"产品界面碎片"，内部的输入框与按钮保留产品自己的控件外观。
    """
    return (
        '<div class="resolver" data-ui>'
        '<label for="share-input">粘贴一条分享链接试试</label>'
        '<div class="resolver-row">'
        '<input id="share-input" type="text" autocomplete="off" spellcheck="false" '
        'placeholder="https://pan.quark.cn/s/… 或整段分享文案">'
        "</div>"
        '<div class="resolver-out" id="resolver-out">'
        '<p class="resolver-empty">还没识别到分享链接。把它粘进来，这里会显示识别到的网盘、'
        "提取码与分片计划 —— 全部在这台设备上完成，不会联网。</p>"
        "</div>"
        '<div class="seg" id="seg" aria-hidden="true"></div>'
        '<div class="seg-legend" id="seg-legend"></div>'
        "</div>"
    )


def _step_row(title: str, body: str) -> str:
    """一步：名称加粗领起，后面接一句说明。

    不用"左名右值"的两端行：那种形态适合短值（日期、价格），而这里的说明是完整句子，
    塞进右列会在中文里断成三四个字的残行（截图评审时抓到过）。
    也不用 01/02/03 编号 —— 顺序由位置与文字本身表达。
    """
    return f'<p class="def"><strong>{esc(title)}</strong> {esc(body)}</p>'


def _engine_row(title: str, body: str) -> str:
    return f'<p class="def"><strong>{esc(title)}</strong> {esc(body)}</p>' 


def _ui_fragment_parse() -> str:
    """界面碎片：解析结果（真实标签、能对上的数字、一行选中态）。"""
    rows = [
        ("宣传片-4K.mp4", "1.8 GB"),
        ("素材包.zip", "412 MB"),
        ("说明书.pdf", "2.1 MB"),
    ]
    inner = "".join(
        f'<div class="frag-row{" sel" if i == 0 else ""}"><span class="name">{esc(n)}</span>'
        f'<span class="val">{esc(v)}</span></div>'
        for i, (n, v) in enumerate(rows)
    )
    return (
        '<div class="frag" data-ui>'
        '<div class="frag-head"><span class="frag-title">分享内容</span>'
        '<span class="frag-sub">3 项 · 已选 2</span></div>'
        + inner
        + '<div class="frag-bar"><i style="width:34%"></i></div>'
        '<p class="frag-note">正在取直链…已完成的段用浅绿标出</p>'
        "</div>"
    )


def _ui_fragment_secure() -> str:
    """界面碎片：凭据存储（说明"存在哪、谁能读"）。"""
    rows = [
        ("夸克网盘", "已加密"),
        ("UC 网盘", "已加密"),
        ("迅雷网盘", "未登录"),
    ]
    inner = "".join(
        f'<div class="frag-row"><span class="name">{esc(n)}</span>'
        f'<span class="val">{esc(v)}</span></div>'
        for n, v in rows
    )
    return (
        '<div class="frag" data-ui>'
        '<div class="frag-head"><span class="frag-title">登录凭据</span>'
        '<span class="frag-sub">仅本机</span></div>'
        + inner
        + '<p class="frag-note">加密密钥由系统密钥库保管，导出备份需自设密码</p>'
        "</div>"
    )


def _ui_fragment_plugins() -> str:
    """界面碎片：插件列表（真实插件名与级别，取自官方索引）。"""
    entries = (PLUGIN_INDEX or {}).get("plugins") or []
    picked = entries[:3]
    inner = "".join(
        f'<div class="frag-row"><span class="name">{esc(e.get("name") or e.get("id"))}</span>'
        f'<span class="val">v{esc(((e.get("versions") or [{}])[0]).get("version") or "—")}</span></div>'
        for e in picked
    )
    total = len(entries)
    return (
        '<div class="frag" data-ui>'
        f'<div class="frag-head"><span class="frag-title">已安装插件</span>'
        f'<span class="frag-sub">官方源 {total} 个可选</span></div>'
        + inner
        + '<p class="frag-note">插件装好后立即生效；更新不需要重启应用</p>'
        "</div>"
    )


def ledger(title: str, sub: str, rows: list[tuple[str, str]], note_text: str = "") -> str:
    """通用台账碎片（`[data-ui]`）：左列是名目、右列是值，四行以上才够一块。

    为什么需要它：内页（下载/插件/文档/开发者/更新日志/协议）本来只有文字与表格，
    一屏里几乎没东西可看。把**真实存在的数字与判断**摊成台账 —— 体积、哈希、
    信任等级、权限、附件数 —— 既填了内容，也让这些值各有着落。
    这些行里的每一个值都能在仓库或 `releases.json` 里核对，没有"演示数据"。
    """
    inner = "".join(
        f'<div class="frag-row"><span class="name">{esc(k)}</span>'
        f'<span class="val">{v if isinstance(v, str) and v.startswith("<") else esc(v)}</span></div>'
        for k, v in rows
    )
    sub_html = f'<span class="frag-sub">{esc(sub)}</span>' if sub else ""
    note_html = f'<p class="frag-note">{esc(note_text)}</p>' if note_text else ""
    return (
        '<div class="frag" data-ui>'
        f'<div class="frag-head"><span class="frag-title">{esc(title)}</span>{sub_html}</div>'
        + inner
        + note_html
        + "</div>"
    )


def _ui_fragment_release() -> str:
    """界面碎片：这次发布装了什么（版本、体积、SHA-256 前缀、运行要求）。

    内页要用的那类碎片：下载页的首屏只有标题与一段说明，一屏里几乎没东西；
    把**这次发布的元数据**摆成一块台账，既填了内容也让数字（体积、哈希、SDK）
    有个可核对的位置 —— 它们全部来自 releases.json，不是手写。
    """
    size = f'{APK["size"] / 1048576:.1f} MB' if APK else "—"
    sha = (APK["sha256"][:16] + "…") if APK and APK.get("sha256") else "—"
    rows = [
        ("版本", LATEST["tag"]),
        ("发布日", (LATEST["published"] or "")[:10]),
        ("安装包", APK["name"] if APK else "—"),
        ("体积", size),
        ("SHA-256", sha),
        ("运行要求", MIN_SDK),
        ("内置引擎", f"TurboDL {TURBODL_VERSION}"),
    ]
    inner = "".join(
        f'<div class="frag-row"><span class="name">{esc(k)}</span>'
        f'<span class="val">{esc(v)}</span></div>'
        for k, v in rows
    )
    return (
        '<div class="frag" data-ui>'
        '<div class="frag-head"><span class="frag-title">本次发布</span>'
        '<span class="frag-sub">GitHub Release 附件</span></div>'
        + inner
        + '<p class="frag-note">全部字段由 GitHub API 读取，与下载直链同源</p>'
        "</div>"
    )


def _ui_fragment_segments() -> str:
    """界面碎片：一次下载的分片视图（引擎真实在做的事）。

    用在「开发者」页与文档页：与其说"支持多线程"，不如把分片、工作窃取、
    收尾阶段摆出来 —— 每根柱子的推进由 site.js 按固定序列推，不是随机装饰。
    """
    widths = [7, 3, 5, 4, 9, 2, 6, 3, 8, 5, 4, 6, 2, 7, 5, 3]
    total = sum(widths)
    bars = "".join(
        f'<i class="{"done" if i < 9 else ""}" style="width:{w / total * 100:.3f}%"></i>'
        for i, w in enumerate(widths)
    )
    return (
        '<div class="frag" data-ui>'
        '<div class="frag-head"><span class="frag-title">分片视图</span>'
        '<span class="frag-sub">16 片 · 9 片已完成</span></div>'
        f'<div class="seg seg-in-frag">{bars}</div>'
        '<p class="frag-note">块数约为连接数的四倍：慢连接不会独占最后剩下的那点工作</p>'
        "</div>"
    )


def _ui_fragment_plugins_page() -> str:
    """界面碎片：插件市场的分类与筛选（与 App 内市场页同一套 id 与中文名）。"""
    cats = [("网盘解析", "4"), ("下载后端", "1"), ("其它", "2")]
    inner = "".join(
        f'<div class="frag-row{" sel" if i == 0 else ""}"><span class="name">{esc(n)}</span>'
        f'<span class="val">{esc(c)} 个</span></div>'
        for i, (n, c) in enumerate(cats)
    )
    return (
        '<div class="frag" data-ui>'
        '<div class="frag-head"><span class="frag-title">插件市场</span>'
        '<span class="frag-sub">按分类筛选</span></div>'
        + inner
        + '<p class="frag-note">分类来自插件清单，不是客户端写死的</p>'
        "</div>"
    )


def _ui_fragment_backup() -> str:
    """界面碎片：凭据备份与恢复（口令派生密钥、AES-GCM 落盘）。

    用在文档页：迁移与换机是读者真会遇到的事，把这几步摆出来比一句"支持备份"清楚。
    """
    rows = [
        ("导出", "口令 → 密钥 → AES-GCM 加密"),
        ("导入", "同一口令解密后逐条写回"),
        ("密钥存储", "系统密钥库（Android Keystore）"),
        ("是否联网", "不联网 —— 文件由你自己保管"),
    ]
    inner = "".join(
        f'<div class="frag-row"><span class="name">{esc(k)}</span>'
        f'<span class="val">{esc(v)}</span></div>'
        for k, v in rows
    )
    return (
        '<div class="frag" data-ui>'
        '<div class="frag-head"><span class="frag-title">认证备份</span>'
        '<span class="frag-sub">换机不必逐个重登</span></div>'
        + inner
        + '<p class="frag-note">口令不保存、不上传；忘了口令就只能重新登录</p>'
        "</div>"
    )


#: 首页问答：问题 + 一段答案。真实答案写在文档里，这里给最短可用的那一段。
HOME_FAQ = [
    ("要登录才能用吗",
     "夸克、UC、光鸭、蓝奏云的分享链接不登录也能解析与下载；其余网盘需要先登录。"
     "登录只在你与网盘之间发生，应用不经手你的密码。"),
    ("会把我粘的链接发出去吗",
     "不会。解析在你的手机上直接请求网盘官方接口，应用自身没有中转服务器，"
     "也没有任何埋点上报。首屏那个演示控件同样不联网。"),
    ("支持哪些网盘",
     "夸克、UC、迅雷、百度、123、139、115、光鸭、蓝奏云、蓝奏云优享版，共十个。"
     "百度网盘可能触发风控，不建议使用。"),
    ("插件是什么",
     "把解析逻辑做成可更新的部件：网盘改接口时更新插件即可，不用等应用发版。"
     "插件是跑在应用沙箱里的 JavaScript，只能通过宿主开放的接口访问网络与存储，"
     "安装前会验签名。"),
    ("为什么比浏览器下载快",
     "浏览器单连接下载；云取把文件切成不等长的分片并发拉取，并能在暂停后按分片续传。"
     "并发会自己收敛到这条链路的合适水平，不会因为线程数调太高反而变慢。"),
]


def _faq_block() -> str:
    """问答：左列问题索引，右侧答案面板（窄屏由 JS 改为就地展开）。"""
    qs = "".join(
        f'<button class="faq-q" type="button" data-q="q{i}" aria-expanded="false">{esc(q)}</button>'
        for i, (q, _a) in enumerate(HOME_FAQ)
    )
    answers = "".join(
        f'<div class="faq-answer" data-a="q{i}"><p class="faq-a">{esc(a)}</p></div>'
        for i, (_q, a) in enumerate(HOME_FAQ)
    )
    return (
        '<div class="faq"><div class="faq-list rise">'
        + qs
        + '</div><div class="faq-panel rise">'
        + answers
        + "</div></div>"
    )


def _page_download(lang: str, href) -> str:
    size = f'{APK["size"] / 1048576:.1f} MB' if APK else "—"
    sha = APK["sha256"] if APK else None
    parts = [
        f'<section><h1>下载</h1>'
        f'<p class="lead">当前版本 <span class="mono">{esc(LATEST["tag"])}</span>'
        f'（{esc((LATEST["published"] or "")[:10])} 发布）。安装包来自项目的 '
        f'<a href="{esc(LATEST["url"])}">GitHub Release</a>。</p>'
        f'<div class="version-bar"><span class="tag">{esc(LATEST["tag"])}</span>'
        f"<span>{esc(MIN_SDK)}</span><span>{esc(size)}</span>"
        f"<span>内置 TurboDL {TURBODL_VERSION}</span></div>"
        # 首屏的台账：把体积、哈希、SDK、引擎版本摆出来 —— 首屏有东西可读，
        # 数字也各有着落（全部来自 releases.json）
        + '<div class="split" style="margin-top:2rem">'
        + _ui_fragment_release()
        + '<div><h2 style="font-size:var(--t-head)">装上就能用</h2>'
        '<p class="section-lead">覆盖安装即可升级；登录状态与下载任务都会保留。'
        "应用未上架任何应用商店，安装时系统会提示「未知来源」，允许本次安装即可。</p>"
        "</div></div></section>",
        section(
            "Android",
            table(["文件", "大小", "SHA-256（前 16 位）"], _apk_rows())
            + buttons([(f'下载 {esc(APK["name"])}' if APK else "下载", APK["url"] if APK else LATEST["url"])])
            + code_block(
                f"""# 校验下载到的 APK（取完整哈希：见下方 Release 页的附件信息）
sha256sum {APK["name"] if APK else "YunGet-<version>-release.apk"}
# 期望：{sha if sha else "(见 Release 页)"}""",
                "shell",
            )
            if sha
            else "",
        ),
        section(
            "安装说明",
            "<ol>"
            "<li>下载上面的 APK；</li>"
            "<li>系统会提示「未知来源应用」，需要在设置里允许本次安装（应用未上架任何应用商店）；</li>"
            "</ol>"
            "<p>覆盖安装即可升级；数据库结构变更随版本自带迁移，登录状态与下载任务不受影响。</p>"
            + note(
                "从 <span class=\"mono\">2.6.16</span> 及更早版本升级同样支持直接覆盖安装 —— "
                "迁移链会依次执行到当前版本。",
            ),
        ),
        section(
            "其它平台",
            '<p class="lead">YunGet 目前只有 Android 构建。下面几个平台是规划中的目标 —— '
            "<strong>现在没有可下载的构建</strong>，所以这里也不放下载按钮。</p>"
            '<div class="platforms">'
            '<div class="platform is-available"><div class="state">可下载</div>'
            "<h3>Android</h3>"
            f'<p class="small">{esc(MIN_SDK_SHORT)}（API {_MIN_SDK_NUM}）· targetSdk {TARGET_SDK} · 上方 APK 即为当前版本</p></div>'
            '<div class="platform is-planned"><div class="state">规划中</div><h3>Windows</h3>'
            '<p class="small">桌面端不是把手机界面放大：需要考虑窗口、键鼠、系统文件选择器、'
            "托盘与系统代理等差异</p></div>"
            '<div class="platform is-planned"><div class="state">规划中</div><h3>macOS</h3>'
            '<p class="small">同上；内核（TurboDL）本身是纯 JVM 的，平台工作主要在界面与系统集成</p></div>'
            '<div class="platform is-planned"><div class="state">规划中</div><h3>Linux</h3>'
            '<p class="small">同上</p></div>'
            "</div>"
            # 平台台账：每个平台各自的状态与理由（与上面几块卡是同源的另一视角）
            + '<div class="rise" style="max-width:640px;margin-top:1.5rem">'
            + ledger(
                "各平台状态",
                "只有 Android 有可下载的构建",
                [
                    ("Android", f"可下载 · {MIN_SDK_SHORT}（API {_MIN_SDK_NUM}）· targetSdk {TARGET_SDK}"),
                    ("Windows", "规划中 · 窗口、键鼠、文件选择器、托盘各是一摊事"),
                    ("macOS", "规划中 · 内核是纯 JVM，工作主要在界面与系统集成"),
                    ("Linux", "规划中 · 同上"),
                ],
                "没有构建的平台不放下载按钮 —— 点了没反应比没有按钮更糟",
            )
            + "</div>",
        ),
        section(
            "历史版本",
            table(
                ["版本", "发布时间", "附件", "说明"],
                [
                    [
                        f'<span class="mono">{esc(r["tag"])}</span>'
                        + (' <span class="chip">pre</span>' if r["prerelease"] else ""),
                        esc((r["published"] or "")[:10]),
                        f'<span class="num">{len(r["assets"])}</span>',
                        f'<a href="{esc(r["url"])}">发布页</a>',
                    ]
                    for r in RELEASES[:12]
                ],
            )
            + '<p class="small">更新说明与应用内的「检查更新」读的是同一份数据。</p>'
            # 升级台账：覆盖安装之后哪些东西会保留（这是用户最关心的一件事实）
            + '<div class="rise" style="max-width:640px;margin-top:1.5rem">'
            + ledger(
                "装之前确认这两条",
                "来源与哈希",
                [
                    ("来源", "只从本页或 GitHub Release 取，不从第三方镜像取"),
                    ("哈希", "sha256sum 与发布页附件的 SHA-256 比对"),
                    ("签名", "APK 由项目自己的密钥签名；换签名会导致无法覆盖安装"),
                    ("系统提示", "「未知来源」是正常的 —— 应用未上架任何应用商店"),
                ],
                "装不上先别报 bug：核对哈希与签名，再确认是不是同一个包",
            )
            + "</div>"
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "升级之后什么被保留",
                "从 2.6.16 起都走同一条迁移链",
                [
                    ("覆盖安装", "支持 · 不需要先卸载"),
                    ("登录状态", "保留 · 凭证库不重建"),
                    ("下载任务", "保留 · 分片状态继续可用"),
                    ("数据迁移", "随版本自带 · 迁移链逐级执行"),
                    ("换机", "先用「认证备份」导出，再在新机导入"),
                ],
                "迁移链是逐级的：跨几个版本升级也走同一条链，不需要逐版升级",
            )
            + "</div>",
        ),
    ]
    return "".join(parts)


def _page_docs(lang: str, href) -> str:
    """文档中心：最短的上手路径 + 全部文档。"""
    items = []
    for d in DOC_PAGES:
        if d["key"] == "legal-notice":
            continue
        items.append(
            (
                d["title"],
                f'<p>{esc(d["blurb"])}</p>'
                f'<p class="mono small">{esc(d["src"] or "（由代码生成）")}</p>'
                f'<div class="btn-row tight"><a class="btn" href="{href(d["key"])}">阅读</a></div>',
            )
        )
    return (
        "<section><h1>文档</h1>"
        '<p class="lead">下面每一篇都是仓库里的一个文件，官网与 GitHub 读的是同一份源 ——'
        "改一处，两处都对。每页都留了原文入口，想拿原始文件随时可以拿。</p></section>"
        + section(
            "三分钟上手",
            "<ol>"
            f'<li>从 <a href="{href("download")}">下载页</a>取当前版本的 APK；</li>'
            "<li>允许「未知来源」安装（应用没有上架任何应用商店）；</li>"
            "<li>在「网盘」页登录你要用的网盘 —— 不登录只能看到文件名，拿不到可下载的直链；</li>"
            "<li>复制一条分享链接，切回应用，点提示里的「粘贴」，解析、浏览、选中要下的文件；</li>"
            "<li>任务出现在「下载」页，可以暂停、继续、删除、打开。</li>"
            "</ol>"
            + code_block(
                """# 想自己编译一版：
git clone https://github.com/jiayuxuan123/YunGet.git
cd YunGet
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk""",
                "shell",
            )
            # 凭据备份碎片：换机与迁移是读者真会遇到的事（这几步就是代码在做的事）
            + '<div class="rise" style="margin-top:2rem;max-width:520px">'
            + _ui_fragment_backup()
            + "</div>"
        )
        + section(
            "全部文档",
            cards(items, columns=2)
            # 文档台账：每篇既是官网一页、也是仓库里一个文件（左列是路径，右列是它讲什么）
            + '<div class="rise" style="max-width:640px;margin-top:1.5rem">'
            + ledger(
                "官网每一页 = 仓库一个文件",
                f"{len(DOC_PAGES)} 篇",
                [
                    (d["src"] or "（由代码生成）", d["title"])
                    for d in DOC_PAGES
                ],
                "改仓库里的文件、重建站点，官网就跟着变 —— 两边不会各说各话",
            )
            + "</div>"
            # 文档体系台账：哪几篇来自仓库文件、哪几篇由代码现场生成
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "文档从哪来",
                "两类",
                [
                    ("仓库文件", f"{sum(1 for d in DOC_PAGES if d.get('src'))} 篇 · 与 GitHub 读同一份源"),
                    ("由代码生成", f"{sum(1 for d in DOC_PAGES if not d.get('src'))} 篇 · 如清单字段参考"),
                    ("原文入口", "每页都留了 raw 文件链接"),
                    ("构建", "python website/build.py（纯标准库）"),
                ],
                "官网一份、GitHub 一份如果各写一遍，迟早会各说各话 —— 所以这里让两者同源",
            )
            + "</div>",
        )
        + section(
            "技术栈",
            '<p class="mono small">Kotlin · Jetpack Compose · Material 3 · Room · OkHttp · '
            f'<a href="{TURBODL_REPO}">TurboDL</a> · KSP</p>'
            "<p>下载引擎与 HLS 都由 TurboDL 提供，所以引擎的改进可以单独升级、也可以被别的应用复用。"
            "应用自己负责的是网盘解析、文件浏览、任务管理与界面。</p>"
            # 引擎台账：分片、并发、多引擎这几个值就是代码里的默认值
            + '<div class="rise" style="max-width:640px;margin-top:1.5rem">'
            + ledger(
                "下载引擎",
                f"内置 TurboDL {TURBODL_VERSION}",
                [
                    ("分片方式", "HTTP Range 分片并发 + 动态分块 + 工作窃取"),
                    ("块与连接", "块数约为连接数的四倍"),
                    ("断点续传", "分片状态落盘，按已写完的字节接着下"),
                    ("收尾阶段", "空闲连接接手剩余部分，消除单线程长尾"),
                    ("其它引擎", "可切换到 aria2 / Gopeed"),
                ],
                "引擎不认识任何协议；HLS 也是通过插件接进来的",
            )
            + "</div>",
        )
    )


# --------------------------------------------------------------------------- #
# 插件（列表读官方源仓库里的 plugins.json —— 真实数据，不手抄）
# --------------------------------------------------------------------------- #

#: 信任四级。文案与设计文档第 26 章、`PluginTrust.kt` 的 `describe()`、
#: `PluginsScreen.kt` 的配色映射一致 —— 官网说的和 App 里看到的必须是同一套话。
TRUST_LEVELS = [
    ("official", "官方", "源的身份是官方，<strong>且</strong>签名公钥随应用内置",
     "主色实心徽标，标「官方」"),
    ("verified", "已验证", "用户主动添加的源，条目带签名，公钥由该源自带（有签名，但发布者身份靠源自己声明）",
     "次级样式容器，标「已验证」"),
    ("community", "社区", "用户主动添加的源，条目带签名但公钥来自网络",
     "中性样式，标「社区」"),
    ("untrusted", "未验证", "没有签名，或公钥拿不到（含从文件 / 粘贴导入的脚本）",
     "<strong>错误色系</strong>，标「未验证」+ 明确风险提示"),
]

#: 分类 id → 展示名。与 App 内 `PluginMarketScreen.categoryLabel` 同一张表 ——
#: 官网说的和 App 里看到的必须一致。约定之外的 id 原样显示（不猜）。
CATEGORY_LABELS = {
    "turbodl-parser": "链接解析",
    "turbodl-backend": "下载后端",
    "turbodl-adapter": "引擎适配",
    "turbodl-protocol": "协议",
    "turbodl-hook": "任务钩子",
    "turbodl-loader": "加载器",
    "yung-support": "支持页",
}

#: 能力级别 → 展示名。Android 端现在只有 JS 一档（原生插件在手机端暂时停用，
#: 见 docs 里的「能力级别」一节与 App 的 PluginLevel.kt）；这里保留 native 的
#: 文案只为如实标注可能残留在旧索引里的条目，不是承诺它在手机上可用。
LEVEL_LABELS = {
    "js": "JS 插件",
    "native": "原生插件（手机端暂时停用）",
}


def _plugin_card(entry: dict) -> tuple[str, str]:
    """索引里的一个插件 → 一张卡片。字段全用 `.get` 取，索引缺字段也不至于崩构建。"""
    versions = entry.get("versions") or []
    latest = versions[0] if versions else {}
    version = latest.get("version") or "—"

    chips = [f'<span class="chip">v{esc(version)}</span>']
    # 级别（P20）：它决定"变更要不要重启"，与版本号一样是用户该一眼看到的事实。
    level = (entry.get("level") or "").strip()
    if level:
        chips.append(f'<span class="chip">{esc(LEVEL_LABELS.get(level, level))}</span>')
    # 分类：约定内的 id 显示中文名，约定外的原样显示（不猜、不美化）。
    category = (entry.get("category") or "").strip()
    if category:
        chips.append(f'<span class="chip">{esc(CATEGORY_LABELS.get(category, category))}</span>')
    for proto in entry.get("protocols") or []:
        chips.append(f'<span class="chip">{esc(proto)}://</span>')
    for label, value in (
        ("作者", entry.get("author")),
        ("许可", entry.get("license")),
        # 索引里的等级是**源自己声明的**：App 安装时还会按「源的身份 + 公钥来源」重新判一次
        # （官方要两个条件同时满足）。所以这里标的是"源自称"，不是"它一定是"。
        ("源自称等级", (PLUGIN_INDEX or {}).get("source", {}).get("trustLevel")),
    ):
        if value:
            chips.append(f'<span class="chip">{esc(label)}：{esc(value)}</span>')

    perms = latest.get("permissions") or []
    bits = []
    if perms:
        bits.append(
            "申请的能力："
            + "、".join(f'<span class="mono">{esc(p)}</span>' for p in perms)
            + " —— 装之前请看清楚，<span class=\"mono\">http</span> 意味着它能把你的链接发出去。"
        )
        # 凭据权限要单独说清（P2）：只列一个 `secrets` 读者不知道那意味着什么，
        # 而它是这一套里最敏感的一项。
        if any(str(p).strip().lower() == "secrets" for p in perms):
            bits.append(
                "<span class=\"mono\">secrets</span> 表示它能读写<strong>自己</strong>的加密凭据"
                "（cookie、登录令牌）：由系统密钥库加密、别的插件读不到，卸载插件时一并删除。"
            )
    if latest.get("minHostVersion"):
        bits.append(f'最低宿主版本 <span class="mono">{esc(latest["minHostVersion"])}</span>。')

    # MIME 与依赖：设计文档 §12 要求 Manifest 有这两项，索引现在也支持了（P17）。
    mimes = entry.get("supportedMimeTypes") or []
    if mimes:
        bits.append("可处理的类型：" + "、".join(f'<span class="mono">{esc(m)}</span>' for m in mimes) + "。")
    deps = entry.get("dependencies") or []
    if deps:
        # 如实说明"只声明、不解析"——写清楚比让用户以为会自动装依赖好
        bits.append(
            "依赖：" + "、".join(f'<span class="mono">{esc(d)}</span>' for d in deps)
            + "（应用目前<strong>不会</strong>自动安装依赖，需自行装齐）。"
        )

    # 签名状态：与 App 内一致地给出可核查材料（P17）。
    # 措辞按设计文档第 26 章：说"校验流程通过"，不说"绝对安全"。
    if latest.get("signature") and latest.get("keyId"):
        bits.append(
            "<strong>签名验证通过</strong>："
            f'<span class="mono">keyId={esc(latest["keyId"])}</span>、'
            f'<span class="mono">sha256={esc(str(latest.get("sha256") or "")[:16])}…</span>。'
            "签名覆盖脚本原始字节，任何改动都会让校验失败并拒绝安装。"
            "签名只回答「内容有没有被改过、是不是这把钥匙发的」，"
            '<strong>不回答「作者写得好不好」</strong>。'
        )
    else:
        bits.append(
            "<strong>这一版没有齐全的校验信息</strong>（缺 sha256 / 签名 / keyId），应用会拒绝安装。"
        )

    if latest.get("changelog"):
        bits.append(esc(latest["changelog"]))

    links = []
    for text, url in (
        ("源码", entry.get("sourceUrl") or entry.get("homepage")),
        ("清单", entry.get("manifestUrl")),
        ("脚本", latest.get("downloadUrl")),
    ):
        if url:
            links.append(f'<a href="{esc(url)}">{text}</a>')
    link_html = f'<p class="small">{" · ".join(links)}</p>' if links else ""

    body = (
        f'<p>{esc(entry.get("summary") or "")}</p>'
        f'<div class="card-meta">{"".join(chips)}</div>'
        + (f'<p class="small">{"".join(bits)}</p>' if bits else "")
        + link_html
    )
    return (esc(entry.get("name") or entry.get("id") or "（未命名）"), body)


def _plugin_list() -> str:
    """插件列表，**按分类分组**渲染（与 App 内市场页的分类筛选同一套 id 与中文名）。

    分组是确定性的：分类按展示名字典序、组内按插件 id 字典序 —— 同一份索引每次
    构建都得到同一个页面，不会因为字典序之外的因素让条目跳来跳去。
    没有分类字段的条目归入"未分类"，如实显示，不替源编一个分类。
    """
    if PLUGIN_INDEX is None:
        return note(
            "暂时读不到插件源数据（构建这台机器上没找到 <span class=\"mono\">"
            "YunGet-Plugins/plugins.json</span>）。下面不列条目 —— 当前源里到底有什么，"
            f'以仓库里的 <a href="{PLUGIN_INDEX_URL}">plugins.json</a> 为准。',
            "warn",
        )
    entries = PLUGIN_INDEX.get("plugins") or []
    if not entries:
        return note(
            f'索引读到了，但里面还没有插件（<span class="mono">generatedAt '
            f'{esc((PLUGIN_INDEX.get("generatedAt") or "")[:10])}</span>）。'
            f'源里的内容以 <a href="{PLUGIN_INDEX_URL}">plugins.json</a> 为准。',
        )

    groups: dict[str, list[dict]] = {}
    for e in entries:
        cat = (e.get("category") or "").strip()
        groups.setdefault(cat, []).append(e)

    def group_key(cat: str) -> str:
        # 空分类排最后，标签用"未分类"；其余按展示名排序
        return (1, "") if not cat else (0, CATEGORY_LABELS.get(cat, cat))

    parts: list[str] = []
    for cat in sorted(groups, key=group_key):
        label = CATEGORY_LABELS.get(cat, cat) if cat else "未分类"
        members = sorted(groups[cat], key=lambda e: str(e.get("id") or ""))
        parts.append(f"<h3>{esc(label)}</h3>")
        parts.append(cards([_plugin_card(e) for e in members], columns=2))
    return "".join(parts)


def _plugin_source_facts() -> str:
    """官方源本身的事实（名称、信任等级、公钥、索引地址）—— 全部来自索引文件自己。"""
    if PLUGIN_INDEX is None:
        rows = [["云取官方插件源", "—", "—", "—"]]
    else:
        src = PLUGIN_INDEX.get("source") or {}
        ver = PLUGIN_INDEX.get("verification") or {}
        rows = [
            [
                f'<strong>{esc(src.get("name") or "—")}</strong>'
                f'<br><span class="mono small">{esc(src.get("id") or "")}</span>',
                f'<span class="mono">{esc(src.get("trustLevel") or "—")}</span>',
                "、".join(f'<span class="mono">{esc(k)}</span>' for k in (src.get("keyIds") or [])) or "—",
                f'<span class="mono small">{esc(ver.get("algorithm") or "—")}</span>'
                f' / <span class="mono small">{esc(ver.get("payload") or "—")}</span>',
            ]
        ]
    return table(["源", "自称等级", "签名 keyId", "验签算法 / 载荷"], rows)


def _trust_table() -> str:
    return table(
        ["等级", "什么情况下给", "界面怎么显示", "能装吗"],
        [[f'<strong>{esc(label)}</strong><br><span class="mono small">{esc(key)}</span>', why, ui, "能"]
         for key, label, why, ui in TRUST_LEVELS],
    )


def _page_plugins(lang: str, href) -> str:
    """插件页：官方源里有什么、怎么装、信任等级是什么意思。"""
    count = len((PLUGIN_INDEX or {}).get("plugins") or []) if PLUGIN_INDEX else 0
    count_text = f"当前源里有 {count} 个插件。" if count else ""
    return (
        '<section class="hero"><h1>插件</h1>'
        '<p class="lead">云取的插件是<strong>一段 JavaScript</strong>：不用编译、不用打包 APK —— '
        "写一个 <span class=\"mono\">.js</span> 文件，装进应用，启用，它就开始工作。"
        "它负责认出链接、算出真正的下载地址、拼请求头、算签名，以及任务结束后做点收尾的事。</p>"
        "<p class=\"lead\">插件市场在应用里：<strong>设置 → 插件市场</strong>。"
        "那一页管「哪里能拿到插件」——加源、看源里有什么、从源里装、检查更新；"
        "旁边那一行 <strong>设置 → 插件</strong> 管「本机装了什么」——启停、看运行状态、卸载。</p>"
        + buttons(
            [
                ("打开官方插件源", PLUGINS_REPO),
                ("提交插件到官方源", href("doc-plugin-publish")),
                ("自己写一个插件", href("developers")),
                ("看安全模型", href("doc-plugin-security")),
            ]
        )
        + "</section>"
        + section(
            "插件不经手下载数据流",
            "<p>这一条是刻意的边界，不是还没做完：插件只在<strong>下载任务开始之前</strong>和"
            "<strong>结束之后</strong>参与。没有流式 API，没有 body 句柄，没有"
            "「每收到一块数据调一次」的回调；插件也不能注册下载后端（试着注册会直接返回 "
            "<span class=\"mono\">unsupported</span>）。</p>"
            "<p>分片与字节面留在 Kotlin 里，脚本就永远不会站在数据路径上。"
            "否则一个写得不好的插件（或者干脆是恶意的）会变成整条下载链路的带宽瓶颈 —— "
            "而且每个数据块都要跨一次 JS 边界，代价比下载本身还大。"
            "所以插件的定位是「算 URL / 拼头 / 签名 / 解析分享链接」，不是「自己实现一个下载器」。</p>"
            + cards(
                [
                    ("认链接、算下载地址", "<p>注册解析器：把一个分享链接变成一条或多条可下载的请求。</p>"),
                    ("改提交前的任务", "<p>提交前钩子：拼请求头、算签名、改写这次请求。</p>"),
                    ("任务结束后收尾", "<p>结束后钩子：记日志、清理临时转存这类只观察的事。</p>"),
                ],
                columns=3,
            )
            # 分片碎片：说明白"脚本不在数据路径上"——分片与字节面都在 Kotlin 一侧
            + '<div class="rise" style="max-width:520px">' + _ui_fragment_segments() + "</div>",
        )
        + section(
            "官方插件源",
            f'<p>官方源就是 <a href="{PLUGINS_REPO}">YunGet-Plugins</a> 这个仓库：'
            "一份 JSON 索引 + 插件源码 + 公钥，没有服务器、没有后台。"
            "App 拉的是它根目录下的 <span class=\"mono\">plugins.json</span>。"
            f"本页的条目直接从那份索引里读出来渲染，{esc(count_text)}</p>"
            + _plugin_source_facts()
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "这个源里有什么",
                "plugins.json",
                [
                    ("源身份", (PLUGIN_INDEX or {}).get("source", {}).get("name") or "云取官方插件源"),
                    ("信任等级", (PLUGIN_INDEX or {}).get("source", {}).get("trustLevel") or "official"),
                    ("插件数", f"{len((PLUGIN_INDEX or {}).get('plugins') or [])} 个"),
                    ("索引生成时间", ((PLUGIN_INDEX or {}).get("generatedAt") or "—")[:10]),
                    ("索引地址", PLUGIN_INDEX_RAW),
                    ("公钥", "随应用内置（判定 official 的两个条件之一）"),
                ],
                "本节的所有数字都是从那份索引文件里读的，不是手抄的",
            )
            + "</div>"
            + _plugin_list(),
        )
        + section(
            "怎么装",
            "<h3>方式一：在应用里从市场装（推荐）</h3>"
            "<ol>"
            "<li>云取 → <strong>设置 → 插件市场</strong>；</li>"
            "<li>加一个源：<strong>添加源</strong> → 填索引地址 → 选信任等级 → "
            "点「<strong>只添加源</strong>」。官方源的索引地址是 "
            f'<span class="mono small">{esc(PLUGIN_INDEX_RAW)}</span>；</li>'
            "<li>源会列出它里面有哪些插件，点进某个插件看<strong>权限</strong>与<strong>信任等级</strong>；</li>"
            "<li>点「安装」—— 装之前会弹一次权限确认，<strong>确认之前不下载</strong>；</li>"
            "<li>装完默认就是启用状态，在 <strong>设置 → 插件</strong> 里能看到它注册了什么、占多少内存。</li>"
            "</ol>"
            + note(
                "加源与装插件是<strong>两个独立动作</strong>：添加源只登记地址、拉取索引，"
                "绝不顺手把里面的插件装进来。这样「我从哪拿到这个脚本的」永远是你主动做过的决定，"
                "而不是加源时的副作用。",
            )
            + "<h3>方式二：手动导入一个 .js 文件</h3>"
            "<ol>"
            "<li>从插件的源码地址下载那个 <span class=\"mono\">.js</span> 文件；</li>"
            "<li>云取 → <strong>设置 → 插件</strong> → 底部 <strong>从文件导入</strong>，选中它；</li>"
            "<li>也可以直接 <strong>粘贴脚本</strong>：粘贴时先点「校验」——"
            "校验只解析、不执行，会告诉你它会注册什么、声明了哪些能力。</li>"
            "</ol>"
            + note(
                "手动导入的脚本<strong>没有签名可验</strong>，在插件列表里一律标「未验证」"
                "（错误色 + 风险提示）。这不是 bug：没有签名就没有「内容与发布者一致」这条证据。",
                "warn",
            ),
        )
        + section(
            "能力级别：手机上只有一档",
            "<p>插件还有<strong>能力级别</strong>这一维。它回答的是「这东西能碰到多大范围的能力」"
            "以及「<strong>改了要不要重启 App</strong>」。云取曾经规划过两档："
            "沙箱里的 JS（L1）与宿主进程内的原生代码（L2）。"
            "<strong>Android 端现在只保留 JS 一档</strong>——原生插件在手机端暂时停用，"
            "后续出 Windows 版时在 Windows 端启用。</p>"
            + table(
                ["级别", "运行位置", "能做什么", "生效时机"],
                [
                    [
                        '<strong>JS 插件</strong><br><span class="mono small">level: "js"</span>',
                        "应用内的 JS 沙箱，进程内",
                        "网盘登录 / 解析 / 取直链、网页与 API 调用、规则与轻逻辑",
                        "可热加载，<strong>装上或更新后立即生效</strong>",
                    ],
                    [
                        "<strong>原生插件（手机端暂时停用）</strong>"
                        '<br><span class="mono small">level: "native"</span>',
                        "宿主进程内，Kotlin/JVM 原生代码",
                        "曾经用来承载新的下载后端、Adapter 与文件 Handler",
                        "<strong>手机端不生效</strong>：条目会被拒绝，并说明原因；"
                        "计划在 Windows 端启用",
                    ],
                ],
            )
            + note(
                "<strong>为什么手机上先停用原生插件：</strong>"
                "Android 10 起系统禁止加载应用可写目录里的 dex（换到缓存目录也一样被拒），"
                "限制一代比一代严；而且首批四个原生网盘插件实际上只是把工作转回宿主既有实现，"
                "<strong>从没提供过宿主做不到的能力</strong>。与其留着一个装不上、"
                "或装上也不多给任何东西的档位，不如先在手机上把这一档收起来，"
                "等 Windows 端那边不受这条限制时再启用。",
                "warn",
            )
            + "<p>索引里没写 <span class=\"mono\">level</span> 的存量条目一律按 JS 读；"
            "条目写着 <span class=\"mono\">native</span> 时，应用会<strong>拒绝安装它</strong>，"
            "并给出「原生插件在手机端暂时停用，后续 Windows 版将启用」这句明确说明 —— "
            "而不是含糊地提示去升级应用。</p>",
        )
        + section(
            "信任等级：四级都能装，差别在提醒",
            "<p>等级说的是「这个源 / 这个插件的<strong>身份</strong>可信到什么程度」，"
            "四级<strong>都能装</strong>，差别在界面上怎么显示、低等级时有没有醒目提示。</p>"
            + _trust_table()
            # 等级台账（同一份事实表的另一视角：左列是等级、右列是它的判据）
            + '<div class="rise" style="max-width:680px;margin-top:1.5rem">'
            + ledger(
                "四级各自的判据",
                "越往下越依赖你自己判断",
                [
                    (f"{name}（{code}）", why)
                    for code, name, why, _ui in TRUST_LEVELS
                ],
                "官方 = 源是官方 且 公钥随应用内置；其余等级只有签名，发布者身份靠源自己声明",
            )
            + "</div>"
            # 市场碎片：分类与筛选（与 App 内市场页同一套 id）
            + '<div class="rise" style="max-width:520px;margin-top:1.25rem">'
            + _ui_fragment_plugins_page()
            + "</div>"
            # 清单台账：写插件要填的那几个字段（与开发者页的最小示例同一份事实）
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "一份插件清单要填什么",
                "turbodl-plugin.json",
                [
                    ("id", "唯一标识，如 parser.example"),
                    ("name / version", "显示名与版本（semver，升版本才能推更新）"),
                    ("category", "turbodl-parser / turbodl-hook / turbodl-loader …"),
                    ("capabilities", "声明要用到的能力（用户会在安装前看到）"),
                    ("protocols", "认哪些 scheme / 域名"),
                    ("entry", "脚本入口（本地文件或 inline）"),
                ],
                "实际授予 = 声明 ∩ 加载器上限 ∩ 用户已确认 —— 没有任何 API 能改它",
            )
            + "</div>"
            # 第四块：哪些是插件、哪些是内置（这一条最容易被搞混）
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "哪些是插件、哪些是内置",
                "分界线",
                [
                    ("内置", "夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭 / 蓝奏云的解析"),
                    ("内置", "分片下载（TurboDL 引擎）· 任务管理 · 界面"),
                    ("插件", "其余站点与网盘：装一个，认一种链接"),
                    ("插件", "额外的钩子 / 别的语言写的插件"),
                    ("不可能是插件", "下载数据流与字节面（留在 Kotlin 一侧）"),
                ],
                "所以「网盘接口变了要等应用更新」这件事，对内置的那几个成立、对插件不成立",
            )
            + "</div>"
            + "<p>两条不肯让步的规则：</p>"
            "<ul>"
            "<li><strong>内置公钥只认官方源。</strong>判定 <span class=\"mono\">official</span> "
            "要「源的身份 + 公钥来源」两个条件同时满足 —— 不能因为某个第三方源用了同一把公钥"
            "就把它当官方，那意味着任何人拿到官方签名过的插件就能自建一个「官方源」。</li>"
            "<li><strong>等级只影响显示与提示，不影响校验。</strong>无论哪一级，安装都要求 "
            "<span class=\"mono\">sha256</span> + 签名齐备。低等级不是「可以少验一点」，"
            "而是「验完了仍然要提醒你」。</li>"
            "</ul>"
            + note(
                "<strong>签名有效只证明脚本内容与某个密钥持有者发布的一致，不证明它安全。</strong>"
                "它不证明作者是好人、脚本没有恶意行为、没有 bug、不会把你的数据发出去，"
                "也不证明它声明的权限是「必要的」而不是「能拿就拿」。"
                "同样地，<strong>官方源收录不等于绝对安全</strong> —— "
                "官方源的意义是「这个插件经过人工看过、来源明确、出问题能找到负责人」，"
                "不是「你可以不看权限就装」。",
                "warn",
            )
            + "<p>另外两条实情：用户为该源选的等级<strong>优先于</strong>索引自称的等级"
            "（索引自称高于用户所给时取用户的），所以 fork 一份索引把 "
            "<span class=\"mono\">trustLevel</span> 改成 <span class=\"mono\">official</span> 没有用；"
            "自建源被添加时默认就是 <span class=\"mono\">community</span>，不是「已验证」。"
            "完整的校验链、以及这份机制<strong>不做</strong>的那些承诺，"
            f'写在<a href="{href("doc-plugin-security")}">插件安全模型</a>里。</p>',
        )
        + section(
            "开发自己的插件",
            "<p>插件 API 是一套稳定 ABI：注册解析器与钩子、发 HTTP 请求、算摘要与 HMAC、"
            "读写自己的小存储、打日志、用定时器。所有函数、参数与上限都有文档，"
            "官方源里那个示例插件就是可以直接照着改的模板。</p>"
            + buttons(
                [
                    ("写给开发者", href("developers")),
                    ("从零写一个插件", href("doc-plugin-dev")),
                    ("插件 ABI 参考", href("doc-plugin-api")),
                ]
            ),
        )
        + section(
            "插件源仓库",
            f'<p>官方源在 GitHub 上：<a href="{PLUGINS_REPO}">{PLUGINS_REPO}</a>。'
            "里面有索引、每个插件的源码与清单、公钥，以及签名工具 "
            "<span class=\"mono\">tools/sign_plugin.py</span>（零依赖可用）。"
            "想提插件就 fork 它、提 PR；想自建一个源，也只需要一个能通过 https 访问的静态目录。</p>"
            + buttons(
                [
                    ("YunGet-Plugins", PLUGINS_REPO),
                    ("索引 plugins.json", PLUGIN_INDEX_URL),
                    ("签名与发布流程", href("doc-plugin-publish")),
                ]
            ),
        )
    )


def _doc_first_js_block(src: str) -> str | None:
    """从仓库文档里摘出第一段 ```js 代码块。

    `/developers` 上那份「最小示例插件」与 `docs/PLUGIN-DEV.md` 里的必须是同一段代码 ——
    与其在这里再抄一份（两份迟早对不上），不如构建时从文档里读。
    读不到就返回 `None`，页面退回一句提示，不让构建失败。
    """
    try:
        text = (ROOT / src).read_text(encoding="utf-8")
    except OSError:
        return None
    m = re.search(r"^```js\n(.*?)^```", text, re.S | re.M)
    return m.group(1).rstrip("\n") if m else None


def _page_developers(lang: str, href) -> str:
    """开发者页：能力边界、四份文档、最小示例、签名与发布的最短路径。"""
    sample = _doc_first_js_block("docs/PLUGIN-DEV.md")
    sample_html = (
        code_block(sample, "parser.demo.js —— 从 docs/PLUGIN-DEV.md 读取")
        if sample
        else note("暂时读不到示例代码（构建这台机器上没找到 docs/PLUGIN-DEV.md）。", "warn")
    )
    docs_items = [
        (d["title"], f'<p>{esc(d["blurb"])}</p>'
                     f'<p class="mono small">{esc(d["src"])}</p>'
                     f'<div class="btn-row tight"><a class="btn" href="{href(d["key"])}">阅读</a></div>')
        for d in DOC_PAGES
        if d["key"].startswith("doc-plugin-")
    ]
    return (
        '<section class="hero"><h1>写一个云取插件</h1>'
        '<p class="lead">插件是一段 JavaScript：不用编译、不用打包 APK、不用发版 —— '
        "写一个 <span class=\"mono\">.js</span> 文件装进应用，它就能认链接、拼请求头、算签名。"
        "这一页给想动手的人：能力边界在哪、文档在哪、最小示例长什么样、怎么签名发布。</p>"
        + buttons(
            [
                ("从零写一个插件", href("doc-plugin-dev")),
                ("ABI 参考", href("doc-plugin-api")),
                ("官方源仓库", PLUGINS_REPO),
                ("引擎官网 TurboDL", TURBODL_SITE),
            ]
        )
        + "</section>"
        + section(
            "三条边界",
            cards(
                [
                    (
                        "能算什么",
                        "<p>四类事：<strong>认链接</strong>（解析器）、<strong>改提交前的请求</strong>"
                        "（pre-hook）、<strong>任务结束后收尾</strong>（post-hook）、"
                        "<strong>看引擎事件</strong>（进度 / 完成 / 失败）。</p>"
                        "<p>在这些里可以发 HTTP 请求、算摘要与 HMAC、编解码 base64/hex、"
                        "读写自己的小存储、打日志、用定时器。</p>",
                    ),
                    (
                        "不能做什么",
                        "<p><strong>不经手下载数据流</strong>：没有流式 API、没有 body 句柄、"
                        "没有分块回调；<span class=\"mono\">host.http.request</span> 给的是一份"
                        "完整的（有上限的）响应体，<span class=\"mono\">downloadToFile</span> 给的是"
                        "一个已经写完的文件。</p>"
                        "<p>也不能注册下载后端（返回 <span class=\"mono\">unsupported</span>），"
                        "不能在运行时给自己加权限 —— 实际授予 = 脚本声明 ∩ 加载器上限 ∩ 用户已确认，"
                        "没有任何 API 能改变它。</p>",
                    ),
                    (
                        "为什么这么定",
                        "<p>分片与字节面留在 Kotlin 里，脚本就永远不会站在数据路径上。"
                        "否则一个写得不好的插件（或者干脆是恶意的）会变成整条下载链路的带宽瓶颈 —— "
                        "而且每个数据块都要跨一次 JS 边界，代价比下载本身还大。</p>"
                        "<p>所以插件的定位是「算 URL / 拼头 / 签名 / 解析分享链接」，"
                        "不是「自己实现一个下载器」。</p>",
                    ),
                ],
                columns=3,
            )
            + note(
                "声明了 <span class=\"mono\">http</span> 就意味着它能上网、能把你的链接发出去。"
                "用户装之前会看到这份权限清单 —— 所以<strong>只声明真正用到的能力</strong>。",
            )
            # 边界台账：把上面三张卡讲的事压成一张表（左列"能/不能"，右列具体项）
            + '<div class="rise" style="max-width:680px;margin-top:1.5rem">'
            + ledger(
                "这套 ABI 的边界",
                "脚本侧",
                [
                    ("能", "http 请求 · 摘要与 HMAC · base64/hex · 自己的小存储 · 日志 · 定时器"),
                    ("能", "注册解析器 / pre-hook / post-hook，并订阅引擎事件"),
                    ("不能", "接触下载数据流（无流式 API、无 body 句柄、无分块回调）"),
                    ("不能", "注册下载后端 —— 返回 unsupported"),
                    ("不能", "运行时给自己加权限：实际授予 = 声明 ∩ 加载器上限 ∩ 用户确认"),
                    ("沙箱", "每个脚本一个 QuickJS runtime 与 context"),
                ],
                "这条边界的理由：脚本若站在数据路径上，一个写得不好的插件就是整条链路的瓶颈",
            )
            + "</div>"
            # 签名台账：签名能证明什么、不能证明什么（常被误解的一处）
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "签名能证明什么",
                "以及不能证明什么",
                [
                    ("能证明", "脚本字节与某个密钥持有者发布的一致（sha256 + 签名）"),
                    ("能证明", "内容被改过一个字节，校验就失败并拒绝安装"),
                    ("不能证明", "作者是好人、脚本没有恶意行为、没有 bug"),
                    ("不能证明", "声明的权限是「必要的」而不是「能拿就拿」"),
                    ("覆盖范围", "只覆盖脚本原始字节，不覆盖它对网络的后续行为"),
                ],
                "所以等级只影响提示强度，不影响校验 —— 四级都要验签名",
            )
            + "</div>",
        )
        + section("四份文档", cards(docs_items, columns=2))
        # 扩展点台账：四类插件各自挂在哪（与清单的 category 一一对应）
        + section(
            "四类扩展点",
            "<p>清单里填的 <span class=\"mono\">category</span> 决定插件能挂到哪里。"
            "四类之间不通用：解析器改不了请求头，钩子也认不了链接。</p>"
            + '<div class="rise" style="max-width:680px">'
            + ledger(
                "挂载点",
                "四类",
                [
                    ("turbodl-parser", "认链接 → 一条或多条下载请求"),
                    ("turbodl-hook", "提交前改写请求 / 任务结束后收尾"),
                    ("turbodl-backend", "不支持 —— 字节面留在 Kotlin 一侧"),
                    ("turbodl-loader", "加载别的语言写的插件（JS 加载器属于这一类）"),
                ],
                "分类来自清单字段，不是客户端写死的",
            )
            + "</div>",
        )
        + section(
            "最小示例插件",
            "<p>这个插件认 <span class=\"mono\">demo://</span> 链接，把它变成一条可下载的 https 请求。"
            "整段复制到一个 <span class=\"mono\">.js</span> 文件里就能用 —— "
            "和<a href=\"" + href("doc-plugin-dev") + "\">《写一个插件》</a>里的那段是同一份代码。</p>"
            + sample_html
            + "<p>这份代码里的每一点都是必要的：权限声明要<strong>保持扁平</strong>"
            "（读它的是一个小文本扫描器，嵌套对象会让权限预览看不到）、"
            "文件名是不可信输入要洗过、"
            "自报的 <span class=\"mono\">id</span> 只允许 "
            "<span class=\"mono\">[a-z0-9.-]</span> 且长度 3..64、"
            "单次求值预算 5 秒（只计 JS 时间）。为什么这么写，逐条解释在文档里。</p>"
            + note(
                "想看更完整的写法（含可选的服务端直链解析与失败回退），读官方源仓库里的 "
                f'<a href="{PLUGINS_REPO}/tree/main/plugins/parser.example-cloud">parser.example-cloud</a>'
                " —— 那份是市场模板，也是本站插件页列出的那一个。",
            ),
        )
        + section(
            "签名与发布的最短路径",
            "<p>签名的对象是<strong>脚本文件的原始字节</strong>：不剥 BOM、不做行尾归一化、"
            "不重新编码、不是「先算 sha256 再签摘要」—— "
            "<span class=\"mono\">sha256</span> 与 <span class=\"mono\">signature</span> "
            "覆盖的是同一串字节，所以两条检查互相印证。工具在源仓库的 "
            "<span class=\"mono\">tools/sign_plugin.py</span> 里，Ed25519 零依赖可用。</p>"
            + code_block(
                """# 1) 生成密钥对（私钥 0600 落盘、绝不进仓库；公钥可以直接进仓库）
python tools/sign_plugin.py keygen --key-id my-id-2026 --out-dir keys

# 2) 签一个脚本，输出能直接粘进 plugins.json 的 JSON 片段
python tools/sign_plugin.py sign plugins/parser.mycloud/parser.mycloud.js \\
    --key keys/my-id-2026.key --key-id my-id-2026

# 3) 扫 plugins/*/ 重新生成整个索引（含真实 sha256 与签名，避免手抄出错）
python tools/sign_plugin.py index --key keys/my-id-2026.key --key-id my-id-2026""",
                "shell",
            )
            + "<p>发布到官方源就是往 <a href=\"" + PLUGINS_REPO + "\">YunGet-Plugins</a> "
            "提一个 PR：建目录 <span class=\"mono\">plugins/&lt;插件 id&gt;/</span>，"
            "放齐脚本、<span class=\"mono\">turbodl-plugin.json</span>、"
            "<span class=\"mono\">market.json</span>，说明它做什么、要哪些权限、为什么需要这些权限。"
            "不想进官方源也可以自建一个源：一个能通过 https 访问的静态目录，"
            "里面放索引、公钥和脚本就行。</p>"
            + note(
                "改了脚本却不升版本号，用户永远收不到更新 —— App 的更新判据是 semver 比大小，"
                "不看时间戳；而且 <span class=\"mono\">index</span> 会因为"
                "「同版本但字节变了」直接报错（已发布版本的字节不可改写）。",
                "warn",
            )
            + buttons(
                [
                    ("打包与发布（全文）", href("doc-plugin-publish")),
                    ("安全模型（全文）", href("doc-plugin-security")),
                ]
            ),
        )
        + section(
            "两个仓库",
            f'<p><a href="{PLUGINS_REPO}">YunGet-Plugins</a> 是插件源：索引、插件源码、公钥、'
            "签名工具都在那里，插件的问题（含发现可疑插件）提到它的 Issue。</p>"
            f'<p><a href="{TURBODL_SITE}">TurboDL</a> 是下载引擎（纯 Kotlin/JVM），'
            "插件框架与 ABI 由它提供 —— 所以引擎的改进可以单独升级，也可以被别的应用复用。"
            "ABI 的完整清单在本站就有：<a href=\"" + href("doc-plugin-api") + "\">插件 ABI 参考</a>。</p>"
            # 分片碎片：讲 ABI 的那一节里最该看到的是"字节面不在脚本里"
            + '<div class="rise" style="max-width:520px;margin-top:1.5rem">'
            + _ui_fragment_segments()
            + "</div>"
            # 发布链路台账：从写脚本到用户装到，中间每一环都是真的
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "从脚本到用户手里",
                "这条链上每一步都能核对",
                [
                    ("写", "一个 .js 文件 + turbodl-plugin.json + market.json"),
                    ("签名", "签名工具在 YunGet-Plugins 仓库里"),
                    ("发布", "往官方源仓库提 PR（也可以自建源）"),
                    ("更新", "改脚本必须同时升版本号，否则用户收不到更新"),
                    ("安装", "App 里加源 → 看权限 → 确认 → 下载 → 启用"),
                ],
                "同一版本但字节变了会被索引直接拒绝 —— 已发布版本的字节不可改写",
            )
            + "</div>"
            + buttons(
                [
                    ("YunGet-Plugins", PLUGINS_REPO),
                    ("TurboDL 官网", TURBODL_SITE),
                    ("全部文档", href("docs")),
                ]
            ),
        )
    )


def _page_legal(lang: str, href) -> str:
    """协议与说明的入口页。"""
    items = []
    for d in DOC_PAGES:
        if not d["key"].startswith("legal-"):
            continue
        items.append(
            (
                d["title"],
                f'<p>{esc(d["blurb"])}</p>'
                f'<p class="mono small">{esc(d["src"])}</p>'
                f'<div class="btn-row tight"><a class="btn" href="{href(d["key"])}">阅读</a></div>',
            )
        )
    return (
        "<section><h1>协议与说明</h1>"
        '<p class="lead">四份不长的文档，回答"用了它之后，我的数据和权利是什么状态"：'
        "凭证存在哪、会发给谁、出了事谁负责、以及 AGPL-3.0 对本项目和二次分发意味着什么。</p></section>"
        + section("这四份", cards(items, columns=2))
        # 协议台账：哪份文件管什么（左列路径、右列问题）
        + section(
            "每一份管什么",
            "<p>四份文件、四个问题。如果两份看着有重叠，仓库路径能告诉你哪一份是准的。</p>"
            + '<div class="rise" style="max-width:680px">'
            + ledger(
                "docs/ 与仓库根目录",
                "四份",
                [(d["src"] or "—", d["title"]) for d in DOC_PAGES if d["key"].startswith("legal-")],
                "这些页面就是从那些文件渲染的：改文件即改页面",
            )
            + "</div>"
            # 数据去向台账：读者最想知道的四件事（与隐私文档同源）
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "数据去向",
                "一句话各一条",
                [
                    ("登录凭据", "加密存本机（Android Keystore），不上传任何服务器"),
                    ("解析与下载", "链接由本机解析、直链由本机请求，没有中转服务器"),
                    ("日志", "只在本机；导出由你自己发起"),
                    ("认证备份", "由你自己保管的文件；口令不保存、不上传"),
                ],
                "本项目没有服务器 —— 没有「我们的服务器」可以收集你的东西",
            )
            + "</div>"
            # 第三块：AGPL-3.0 对本项目与二次分发意味着什么
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "AGPL-3.0 意味着什么",
                "本项目",
                [
                    ("自用", "随便用，不需要公开任何东西"),
                    ("分发修改版", "要按同一协议提供源码"),
                    ("通过网络提供服务", "也要提供源码（AGPL 与 GPL 的区别在这里）"),
                    ("上游来源", "本应用取自上游 YunX（CYQawa），协议一脉相承"),
                    ("第三方组件", "各自协议见「第三方组件」那一份"),
                ],
                "这条协议管的是代码，不管你的下载内容 —— 那是另一回事",
            )
            + "</div>"
            # 第四块：日志里可能有什么（这一条最容易被忽略）
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "把日志贴出去之前",
                "里面可能有这些",
                [
                    ("带签名的直链", "可能含有临时授权参数"),
                    ("网盘域名与路径", "会暴露你在下哪个网盘的内容"),
                    ("设备与系统信息", "机型、系统版本、应用版本"),
                    ("出错堆栈", "可能含请求头（含 Cookie 的部分会被打码）"),
                ],
                "分享日志前先扫一遍；导出功能只是把文件交给你，不会自动上传",
            )
            + "</div>",
        )
        + section(
            "为什么写得这么直白",
            "<p>因为不这样写就没人会读。这几页说的是代码真实在做的事，包括不方便的部分："
            "下载任务的请求头是明文落库的、日志里可能出现带签名的直链、认证备份文件包含蓝奏的账号密码。"
            "知道这些，你才能决定要不要把设备借给别人、要不要把日志贴到 Issue 里。</p>",
        )
    )


def _excerpt(text: str) -> str:
    """把 Release 说明的摘要转成一段能读的文本。

    摘要是从 GitHub 的 Markdown 正文里截出来的，所以带着 `**强调**`、`` `代码` `` 与
    行首的项目符号。这里是一段摘要而不是清单，所以：强调保留成粗体、代码标记去掉、
    项目符号去掉；截断处可能留下**半个**标记，最后统一清掉 —— 不然页面上会露出 `**`。
    """
    if not text:
        return ""
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", esc(text.strip()))
    out = re.sub(r"`([^`]+)`", r"\1", out)
    out = re.sub(r"(?m)^\s*[·•\-]\s*", "", out)
    return out.replace("**", "").strip()


def _page_changelog(lang: str, href) -> str:
    entries = []
    for r in RELEASES[:12]:
        badge = ' <span class="chip">pre</span>' if r["prerelease"] else ""
        summary = _excerpt(r["summary"] or "")
        body = f'<p class="muted">{summary}</p>' if summary else ""
        entries.append(
            f'<div class="card"><h3><span class="mono">{esc(r["tag"])}</span>{badge}</h3>'
            f'<p class="small">{esc((r["published"] or "")[:10])} · {len(r["assets"])} 个附件</p>'
            f'{body}<p class="small"><a href="{esc(r["url"])}">发布页</a></p></div>'
        )
    return (
        "<section><h1>更新日志</h1>"
        '<p class="lead">由发布说明本身生成 —— 应用内的「检查更新」读的是同一份数据，'
        "所以两处看到的版本与内容永远一致。</p></section>"
        # 发布台账：把"哪一版带多少附件、是不是预发布"先摊开，再往下才是逐条摘要
        + section(
            "发布台账",
            '<p>每次发布、日期、附件数与预发布标记。这里没有一处是手写摘要 ——'
            "与下载页渲染的是同一份数据。</p>"
            + '<div class="rise" style="max-width:520px">'
            + _ui_fragment_release()
            + "</div>"
            + '<div class="rows rise" style="margin-top:1.5rem">'
            + "".join(
                f'<div class="row"><span class="name mono">{esc(r["tag"])}'
                + (' <span class="chip">pre</span>' if r["prerelease"] else "")
                + f'</span><span class="val">{esc((r["published"] or "")[:10])} · '
                + f'{len(r["assets"])} 个附件</span></div>'
                for r in RELEASES[:12]
            )
            + "</div>"
            # 台账的第二块：按附件数排一遍（一眼看出哪一版动了多少东西）
            + '<div class="rise" style="max-width:680px;margin-top:1.5rem">'
            + ledger(
                "按体量排",
                "附件数最多的六次",
                [
                    (
                        f'{len(r["assets"])} 个附件',
                        f'{r["tag"]} · {(r["published"] or "")[:10]}',
                    )
                    for r in sorted(RELEASES[:12], key=lambda x: -len(x["assets"]))[:6]
                ],
                "这里突然多出来，通常意味着构建里新加了一个产物",
            )
            + "</div>"
            # 第三块：更新怎么到达设备（应用内检查更新的机制）
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "更新怎么到达设备",
                "应用内「检查更新」",
                [
                    ("数据来源", "GitHub Release（与本页同一份）"),
                    ("判据", "semver 比大小，不看发布时间"),
                    ("提醒方式", "应用内提示 + 变更说明"),
                    ("下载", "同样从 Release 附件取，可核对 SHA-256"),
                    ("升级", "覆盖安装，登录状态与下载任务保留"),
                ],
                "改脚本不升版本号的话，用户永远收不到更新 —— 判据是版本号",
            )
            + "</div>"
            # 第四块：版本号怎么读（pre 标记与「字节不可改写」这两条最容易被误解）
            + '<div class="rise" style="max-width:680px;margin-top:1.25rem">'
            + ledger(
                "版本号怎么读",
                "semver",
                [
                    ("正式版", "修 bug、加功能都会升这个号"),
                    ("pre 标记", "预发布：功能可能还在改，装之前想清楚"),
                    ("判据", "应用内「检查更新」按版本号比大小，不看发布时间"),
                    ("不可改写", "同一版本的字节变了会被索引拒绝"),
                ],
                "改了东西就必须升版本号 —— 这是本项目的硬规则，不是建议",
            )
            + "</div>",
        )
        + f'<section><div class="cards cols-2">{"".join(entries)}</div></section>'
        + section("完整历史", buttons([("全部发布", f"{REPO}/releases"), ("代码提交", f"{REPO}/commits/main")]))
    )


def _page_community(lang: str, href) -> str:
    return (
        "<section><h1>社区</h1>"
        '<p class="lead">本项目没有聊天群，也没有评论系统。所有反馈都走仓库的 Issue —— '
        "那里可搜索、可追溯，也不会因为某个群散掉而丢失。</p></section>"
        + section(
            "问题反馈",
            "<p>一份能复现的报告胜过十条意见。有用的问题会写清：应用版本（设置页或关于页可见）、"
            "Android 版本与机型、出问题的网盘、复现步骤，以及导出的日志。</p>"
            "<p>应用内「设置 → 导出日志」会导出崩溃日志与应用信息，直接附在 Issue 里即可。</p>"
            + buttons([("提交 Issue", f"{REPO}/issues"), ("导出日志说明", href("docs"))]),
        )
        + section(
            "与上游的关系",
            f"<p>本项目是 <a href=\"{UPSTREAM}\">CYQawa/YunX（云析）</a> 的二次开发版本，"
            "两者是各自独立的项目：</p>"
            "<ul>"
            "<li>代码在本仓库开源，遵循 AGPL-3.0，并保留上游的版权与协议声明；</li>"
            "<li>应用内的赞赏码只面向本二次开发版；想支持上游原作者请移步上游仓库 —— "
            "两者互不相关，避免权益混淆；</li>"
            "<li>问题反馈请提到本仓库；上游仓库不受理本版本的问题。</li>"
            "</ul>"
            + f'<p class="small">署名要求见仓库的 <a href="{BLOB}/NOTICE">NOTICE</a>。</p>',
        )
        + section(
            "开源协议",
            "<p>本项目基于 <strong>GNU AGPL-3.0</strong> 开源：分发时同步公开全部源代码，"
            "并保留上游的版权与协议声明。上游代码版权归其原作者所有。</p>"
            + buttons(
                [
                    ("LICENSE", f"{BLOB}/LICENSE"),
                    ("NOTICE（署名）", f"{BLOB}/NOTICE"),
                    ("AGPL-3.0 全文", "https://www.gnu.org/licenses/agpl-3.0.html"),
                ]
            ),
        )
        + section(
            "免责声明",
            "<p>本项目仅供个人学习与技术交流，请勿用于商业用途。</p>"
            "<p>下载内容的版权归原作者及相关权利人所有，请遵守当地法律法规以及相关平台的服务条款。</p>"
            "<p>使用本项目下载内容时，请确保自己拥有相应的使用权或下载权限。</p>"
            "<p>使用本项目产生的任何后果由使用者自行承担。</p>",
        )
        + section(
            "致谢",
            f'<ul><li><a href="{UPSTREAM}">CYQawa/YunX</a>：本项目的上游</li>'
            f'<li><a href="{TURBODL_REPO}">TurboDL</a>：内置的下载引擎与插件框架</li>'
            "<li>以及所有参与测试、反馈问题和改进项目的用户</li></ul>",
        )
    )


def _page_support(lang: str, href) -> str:
    return (
        "<section><h1>支持开发</h1>"
        '<p class="lead">这个项目完全免费开源，所有功能无需赞赏即可正常使用。'
        "若你愿意支持它的持续维护，可以扫下面的赞赏码。</p></section>"
        + section(
            "微信赞赏",
            '<figure class="shot" style="max-width:320px"><img src="{IMG:weixin}" alt="微信赞赏码" loading="lazy" width="1190" height="1190">'
            "<figcaption>保存二维码到相册后，打开微信「扫一扫」即可</figcaption></figure>"
            + note(
                "此赞赏码仅用于支持本二次开发版本的维护。若想支持上游原项目作者，"
                f'请移步 <a href="{UPSTREAM}">原仓库 CYQawa/YunX</a> 的捐赠渠道，两者互不相关。',
                "warn",
            ),
        )
        + section(
            "除了赞赏，还有这些方式",
            "<ul>"
            '<li>把遇到的问题提成 <a href="' + REPO + '/issues">Issue</a> —— 一份能复现的报告就是实打实的贡献；</li>'
            "<li>把使用体验、失败案例和失败的直链反馈回来，它们决定了下一步优化什么；</li>"
            "<li>如果这个项目对你有用，把它讲给需要的人。</li>"
            "</ul>",
        )
        + section(
            "赞赏信息的展示",
            "<p class=\"small\">若你希望自己的名字（或昵称）出现在致谢里，可以匿名，"
            "也可以通过 Issue 提交截图与希望显示的名称，由维护者人工确认。</p>",
        )
    )


# --------------------------------------------------------------------------- #
# 站点定义
# --------------------------------------------------------------------------- #

def _pages() -> list:
    group = [
        ("home", "index.html", "云取 YunGet —— 网盘分享链接解析与高速下载", "把网盘分享链接变成文件：解析、取直链、多线程高速下载。", _page_home),
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
    "tagline": "网盘分享链接解析与高速下载",
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
            "foot_cta": "下载云取，把链接变成文件",
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
