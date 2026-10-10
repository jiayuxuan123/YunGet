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
TURBODL_VERSION = "0.2.0.8"

REPO = "https://github.com/jiayuxuan123/YunGet"
BLOB = f"{REPO}/blob/main"
UPSTREAM = "https://github.com/CYQawa/YunX"
TURBODL_REPO = "https://github.com/jiayuxuan123/TurboDL"

APK = next((a for a in LATEST["assets"] if a["name"].endswith(".apk")), None)

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


def _plugin_card(entry: dict) -> tuple[str, str]:
    """索引里的一个插件 → 一张卡片。字段全用 `.get` 取，索引缺字段也不至于崩构建。"""
    versions = entry.get("versions") or []
    latest = versions[0] if versions else {}
    version = latest.get("version") or "—"

    chips = [f'<span class="chip">v{esc(version)}</span>']
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
    if latest.get("minHostVersion"):
        bits.append(f'最低宿主版本 <span class="mono">{esc(latest["minHostVersion"])}</span>。')
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
    """插件列表。读不到索引 / 索引里没有条目时都只显示一句实话，不摆占位卡片。"""
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
    return cards([_plugin_card(e) for e in entries], columns=2)


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
            ),
        )
        + section(
            "官方插件源",
            f'<p>官方源就是 <a href="{PLUGINS_REPO}">YunGet-Plugins</a> 这个仓库：'
            "一份 JSON 索引 + 插件源码 + 公钥，没有服务器、没有后台。"
            "App 拉的是它根目录下的 <span class=\"mono\">plugins.json</span>。"
            f"本页的条目直接从那份索引里读出来渲染，{esc(count_text)}</p>"
            + _plugin_source_facts()
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
            "能力级别：L1 与 L2",
            "<p>除了「可不可信」，插件还有<strong>能力级别</strong>这一维。它回答的是"
            "「这东西能碰到多大范围的能力」以及「<strong>改了要不要重启 App</strong>」—— "
            "这两件事与信任等级无关：L1 插件可能是官方签名的，L2 插件也可能来自你信任的源。</p>"
            '<div class="table-wrap"><table><thead><tr><th>级别</th><th>运行位置</th>'
            "<th>能做什么</th><th>生效时机</th></tr></thead><tbody>"
            '<tr><td><strong>L1 · JS 插件</strong><br><span class="mono small">level: "js"</span></td>'
            "<td>应用内的 JS 沙箱，进程内</td>"
            "<td>网盘登录 / 解析 / 取直链、网页与 API 调用、规则与轻逻辑</td>"
            "<td>可热加载，<strong>装上或更新后立即生效</strong></td></tr>"
            '<tr><td><strong>L2 · 原生插件</strong><br><span class="mono small">level: "native"</span></td>'
            "<td>宿主进程内，Kotlin/JVM 原生代码</td>"
            "<td>接入其他下载器的插件生态（Adapter）、新的下载后端、文件 Handler（播放器）、系统级集成</td>"
            "<td><strong>不能热加载，必须重启 App</strong></td></tr>"
            "</tbody></table></div>"
            + note(
                "<strong>L2 不能热加载是结构性的，不是偷懒。</strong>"
                "原生插件注册进来的类在进程生命周期内无法替换（类加载器换不掉已加载的类），"
                "它还可能持有播放器、硬件解码器、原生库句柄这类不可逆的系统资源，"
                "而且它跑在宿主进程内、没有沙箱。L1 之所以能热加载，恰恰因为它在沙箱里："
                "所有能力都要过宿主的权限门，运行时可以整体重建。",
            )
            + note(
                "<strong>L2 的能力与宿主等同。</strong>它声明的能力只作展示，不是限制 —— "
                "装一个 L2 插件，等同于允许那段原生代码在你的设备上以应用的权限执行。"
                "我们把这一点写在安装确认里而不是藏起来：写轻了（暗示有沙箱）是误导，"
                "写重了（说成「危险」）会让这类插件没人敢用。"
                "是否可信仍然由签名与信任链决定，而不是由这个级别决定。",
                "warn",
            )
            + "<p>索引里没写 <span class=\"mono\">level</span> 的存量条目一律按 L1 读；"
            "写了当前应用不认识的值时，那条<strong>不会被安装</strong>，"
            "并会在市场页显示「未显示」的原因（通常是提示升级应用）。</p>",
        )
        + section(
            "信任等级：四级都能装，差别在提醒",
            "<p>等级说的是「这个源 / 这个插件的<strong>身份</strong>可信到什么程度」，"
            "四级<strong>都能装</strong>，差别在界面上怎么显示、低等级时有没有醒目提示。</p>"
            + _trust_table()
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
            ),
        )
        + section("四份文档", cards(docs_items, columns=2))
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
    "nav": [
        ("home", {"zh": "概览"}),
        ("download", {"zh": "下载"}),
        ("plugins", {"zh": "插件"}),
        ("docs", {"zh": "文档"}),
        ("developers", {"zh": "开发者"}),
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
