#!/usr/bin/env python3
"""YunGet 官网的内容定义（中文）。

## 这份文件里只有"事实"

页面正文全部来自仓库里**可核对**的东西：README 的功能清单与支持平台、
`build.gradle.kts` 的版本与 ABI、`NOTICE` 的署名义务、`SupportScreen.kt` 的赞赏文案、
以及 `website/data/releases.json`（由 `refresh_releases.py` 从 GitHub API 抓取）。

## 六条纪律（对应《YunGet 插件化架构设计文档》第 19 / 21 / 22 / 23 / 26 章）

1. **首屏三问答**：YunGet 是什么 / 现在能做什么 / 去哪里下载。
2. **只用真实素材**：截图只用 `images/Parsing.jpg` 与 `images/Setting.jpg` —— 另外三张
   分别含真实账号昵称与用量（Login.jpg）、明文签名直链（Link.jpg）、上游旧版界面（about.jpg），
   **不上站**。
3. **平台区不造假按钮**：Android 有真实构建就做成可下载；Windows/macOS/Linux 没有构建，
   就如实标注"规划中"且**不可点**（设计文档第 23 章：只有真正提供可用构建时才显示为可下载）。
4. **下载信息可核对**：版本、字节数、SHA-256 全部取自 GitHub API 的附件元数据。
5. **不写"绝对安全"**：不声称不存在的验证流程。
6. **插件页预留不建**：YunGet 本身还没有插件系统（那是后续升级的事），
   因此导航里**不放**这个入口 —— 不放死链，也不放"敬请期待"的空页。
   位置预留在 `content.py` 的 `RESERVED_PAGES` 里，将来加一页即可上线。
7. **文档只写一遍**：官网上的每一篇文档都是仓库里的一个 `.md` 文件，由 `mdlite.py`
   现场渲染 —— 官网一份、GitHub 一份，但源头只有一个。想改文案，改仓库里的文件即可。
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import mdlite
from sitekit import buttons, cards, code_block, esc, note, section, table

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DATA = json.loads((HERE / "data" / "releases.json").read_text(encoding="utf-8"))
LATEST = DATA["latest"]
RELEASES = DATA["releases"]
VERSION = LATEST["tag"].lstrip("v")

#: 与 app/build.gradle.kts 保持一致的构建口径（改代码时一起改）。
MIN_SDK = "Android 6.0（API 23）"
TARGET_SDK = "API 34"
#: YunGet 内置的下载引擎版本，取自 app/build.gradle.kts 的 dev.turbodl 依赖。
TURBODL_VERSION = "0.2.0.5"

REPO = "https://github.com/jiayuxuan123/YunGet"
BLOB = f"{REPO}/blob/main"
UPSTREAM = "https://github.com/CYQawa/YunX"
TURBODL_REPO = "https://github.com/jiayuxuan123/TurboDL"

APK = next((a for a in LATEST["assets"] if a["name"].endswith(".apk")), None)

#: 预留但**不生成**的页面（避免死链）。
#: 插件页要等 YunGet 真正有插件系统之后再上；这里记着它该叫什么、该放哪。
RESERVED_PAGES = [
    {
        "key": "plugins",
        "path": "plugins/index.html",
        "title": "插件",
        "why": "YunGet 的插件系统尚未发布（属后续升级）。届时此页展示：官方/第三方插件、"
               "分类与标签、详情（来源 / 版本 / 权限 / 源码地址 / 验证状态）、安装引导。",
    },
    {
        "key": "developers",
        "path": "developers/index.html",
        "title": "开发者",
        "why": "插件 API、Manifest 规范、示例与兼容层说明 —— 与插件系统一同发布。",
    },
]

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

FEATURES = [
    (
        "分享链接解析",
        "识别夸克 / UC / 迅雷 / 百度 / 139 / 123 的分享链接；支持带提取码的链接并尽量自动识别；"
        "解析后可浏览分享内的文件与目录，再取下载直链。",
    ),
    (
        "多线程分片下载",
        "基于内置的 TurboDL 引擎：分片并发 + 动态分块 + 工作窃取调度，支持断点续传、"
        "暂停 / 继续 / 删除 / 打开，服务器不支持 Range 时自动回退单流。",
    ),
    (
        "HLS 下载",
        "通过 TurboDL 插件机制接入 HLS：解析 M3U8 清单、分片并发下载、AES-128 解密。"
        "它是**下载能力**，不是播放器。",
    ),
    (
        "账号与凭证",
        "夸克 / UC / 百度 / 139 走 WebView Cookie，迅雷走密码或短信，123 走网页登录；"
        "凭证用 Android Keystore 加密落库，且只存在本机。",
    ),
    (
        "认证备份与恢复",
        "用自定义口令派生密钥，以 AES-GCM 加密导出 / 导入网盘认证信息，换机不必逐个重登。",
    ),
    (
        "取链即删",
        "转存后立即清理临时文件：百度 / 迅雷取链后清理，夸克保留到下载完成或删除任务后清理。",
    ),
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
                text, _resolver(src, href), h_offset=0 if dropped else 1, toc_max=3
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
    return (
        '<nav class="toc doc-nav"><div class="toc-title">全部页面</div><ul>'
        + "".join(items)
        + "</ul></nav>"
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


def _page_home(lang: str, href) -> str:
    dl_btn = f"下载 APK（{VERSION}）"
    chip = f"{LATEST['tag']} · {MIN_SDK} · AGPL-3.0"
    parts = [
        f'<section class="hero"><div class="hero-grid"><div>'
        f"<h1>网盘分享链接解析与高速下载</h1>"
        f'<p class="lead">云取（YunGet）把分享链接变成文件：粘贴链接即可解析、浏览、取直链，'
        f"再用内置的多线程引擎高速下载。凭据加密留在本机，不经过第三方服务器。</p>"
        + buttons([(dl_btn, href("download")), ("看文档", href("docs")), ("GitHub", REPO)])
        + f'</div><div class="hero-side"><div class="version-bar">'
        f'<span class="tag">{esc(chip)}</span>'
        f'<span>内置 TurboDL {TURBODL_VERSION}</span>'
        f"</div></div></div></section>",
        section(
            "支持平台",
            note(
                "<strong>不建议使用百度网盘</strong>，可能触发账号风控，请谨慎使用。",
                "warn",
            )
            + table(
                ["网盘", "登录方式", "能做什么"],
                [[f"<strong>{esc(n)}</strong>", esc(login), desc] for n, login, desc in PLATFORMS],
            ),
        ),
        section(
            "能做什么",
            cards([(t, f"<p>{b}</p>") for t, b in FEATURES], columns=3),
        ),
        section(
            "界面截图",
            '<p class="lead">下面是应用的真实界面（解析页与设置页）。</p>'
            + '<div class="shots">'
            + '<figure class="shot phone"><img src="{IMG:parsing}" alt="解析页" loading="lazy" width="720" height="1600">'
            + "<figcaption>解析页：粘贴分享链接，一键解析</figcaption></figure>"
            + '<figure class="shot phone"><img src="{IMG:setting}" alt="设置页" loading="lazy" width="720" height="1600">'
            + "<figcaption>设置页：下载线程数、认证备份、日志导出</figcaption></figure>"
            + "</div>"
            + note(
                "这里的截图都取自真实运行的应用。另外几张没有放上来：它们分别含真实账号昵称与用量、"
                "一条带签名的下载直链、以及上游旧版界面 —— 那些不是拿来给人看的。",
            ),
        ),
        section(
            "下载引擎",
            f"<p>下载由 <a href=\"{TURBODL_REPO}\">TurboDL</a> 承担 —— 一个纯 Kotlin/JVM 的多线程下载引擎，"
            f"当前内置版本 <span class=\"mono\">{TURBODL_VERSION}</span>。它的分片调度、动态并发与断点续传"
            f"都不是本应用自己实现的，因此也可以被别的应用复用。</p>"
            + table(
                ["能力", "说明"],
                [
                    ["分片并发", "按 HTTP Range 并行下载；服务器不支持 Range 时回退单流"],
                    ["动态分块 + 工作窃取", "慢连接不拖垮整体，消除「最后一片单线程收尾」的长尾"],
                    ["断点续传", "分片状态落盘，暂停后续传按真实进度接着下"],
                    ["限速与并发", "全局速度上限；并发任务数可调"],
                    ["代理与 DNS", "直连 / 系统 / 手动代理；系统 DNS / DoH"],
                ],
            )
            + '<p class="small">本应用把并发装配为：连接数上限 16，诊断档位上限 64 —— '
            "网盘服务端普遍有风控，拉满反而更慢。",
        ),
        section(
            "不止一个引擎",
            "<p>默认的 TurboDL 覆盖绝大多数场景，但有些任务它做不了，有些能力是历史留下来的：</p>"
            + table(
                ["引擎", "用在什么时候"],
                [
                    ["<strong>TurboDL</strong>", "默认。普通直链、网盘直链、HLS"],
                    ["内置兼容引擎", "项目早期的实现，作为兜底；不支持现场诊断，也不持久化请求头"],
                    ["aria2", "实验性，用于对照排查；<strong>仅 arm64 设备</strong>"],
                    ["Gopeed", "磁力 / BT 这类内置分片器做不到的任务，需要先导入内核"],
                ],
            )
            + '<p class="small">切换引擎在设置里，重启应用后生效。内核来源、校验方式与各自的边界，'
            f'写在<a href="{href("doc-engines")}">下载引擎</a>里。</p>',
        ),
        section(
            "从哪里开始",
            "<p>装好应用后：复制一条分享链接 → 回到应用会自动提示粘贴 → 解析 → 浏览 → 下载。</p>"
            + buttons(
                [
                    ("下载 APK", href("download")),
                    ("使用说明", href("doc-guide")),
                    ("常见问题", href("doc-faq")),
                    ("从源码构建", href("doc-build")),
                ]
            ),
        ),
    ]
    return "".join(parts)


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
        f"<span>内置 TurboDL {TURBODL_VERSION}</span></div></section>",
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
            "**现在没有可下载的构建**，所以这里也不放下载按钮。</p>"
            '<div class="platforms">'
            '<div class="platform is-available"><div class="state">可下载</div>'
            "<h3>Android</h3>"
            f'<p class="small">6.0+（API 23）· targetSdk {TARGET_SDK} · 上方 APK 即为当前版本</p></div>'
            '<div class="platform is-planned"><div class="state">规划中</div><h3>Windows</h3>'
            '<p class="small">桌面端不是把手机界面放大：需要考虑窗口、键鼠、系统文件选择器、'
            "托盘与系统代理等差异</p></div>"
            '<div class="platform is-planned"><div class="state">规划中</div><h3>macOS</h3>'
            '<p class="small">同上；内核（TurboDL）本身是纯 JVM 的，平台工作主要在界面与系统集成</p></div>'
            '<div class="platform is-planned"><div class="state">规划中</div><h3>Linux</h3>'
            '<p class="small">同上</p></div>'
            "</div>",
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
            + '<p class="small">更新说明与应用内的「检查更新」读的是同一份数据。</p>',
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
        )
        + section(
            "全部文档",
            cards(items, columns=2),
        )
        + section(
            "技术栈",
            '<p class="mono small">Kotlin · Jetpack Compose · Material 3 · Room · OkHttp · '
            f'<a href="{TURBODL_REPO}">TurboDL</a> · KSP</p>'
            "<p>下载引擎与 HLS 都由 TurboDL 提供，所以引擎的改进可以单独升级、也可以被别的应用复用。"
            "应用自己负责的是网盘解析、文件浏览、任务管理与界面。</p>",
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
        + section(
            "为什么写得这么直白",
            "<p>因为不这样写就没人会读。这几页说的是代码真实在做的事，包括不方便的部分："
            "下载任务的请求头是明文落库的、日志里可能出现带签名的直链、认证备份文件包含蓝奏的账号密码。"
            "知道这些，你才能决定要不要把设备借给别人、要不要把日志贴到 Issue 里。</p>",
        )
    )


def _page_changelog(lang: str, href) -> str:
    entries = []
    for r in RELEASES[:12]:
        badge = ' <span class="chip">pre</span>' if r["prerelease"] else ""
        summary = (r["summary"] or "").strip()
        body = f'<p class="muted">{esc(summary)}</p>' if summary else ""
        entries.append(
            f'<div class="card"><h3><span class="mono">{esc(r["tag"])}</span>{badge}</h3>'
            f'<p class="small">{esc((r["published"] or "")[:10])} · {len(r["assets"])} 个附件</p>'
            f'{body}<p class="small"><a href="{esc(r["url"])}">发布页</a></p></div>'
        )
    return (
        "<section><h1>更新日志</h1>"
        '<p class="lead">由发布说明本身生成 —— 应用内的「检查更新」读的是同一份数据，'
        "所以两处看到的版本与内容永远一致。</p></section>"
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
        ("docs", "docs/index.html", "文档", "使用说明、常见问题、下载引擎、从源码构建，以及项目说明。", _page_docs),
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
    "nav": [
        ("home", {"zh": "概览"}),
        ("download", {"zh": "下载"}),
        ("docs", {"zh": "文档"}),
        ("changelog", {"zh": "更新日志"}),
        ("community", {"zh": "社区"}),
        ("support", {"zh": "支持开发"}),
        ("legal", {"zh": "协议"}),
    ],
    "ui": {
        "zh": {
            "skip": "跳到正文",
            "menu": "菜单",
            "notfound_title": "页面不存在",
            "notfound_body": "这个地址在本站不存在。全部内容就在下面这几处：",
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
