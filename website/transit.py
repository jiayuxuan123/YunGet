#!/usr/bin/env python3
"""YunGet 官网的 Transit Wayfinding 页面组合。

产品事实与路由仍由 ``content.py`` 提供；这里只负责表达与交互结构。
页面默认完整可见，JavaScript 只增强菜单、主题和本地链接识别。
"""

from __future__ import annotations

from html import escape
from urllib.parse import urlsplit

from sitekit import code_block, esc, md_inline, table


def _mb(size: int) -> str:
    return f"{size / 1048576:.1f}&nbsp;MB"


def _edge(facts: dict, url: str) -> str:
    prefix = "https://github.com/jiayuxuan123/YunGet/releases/download/"
    if url.startswith(prefix):
        return facts["download_edge"] + "/yunget/releases/download/" + url[len(prefix):]
    return url


def _apk_by_name(facts: dict, needle: str) -> dict | None:
    for asset in facts["apks"]:
        if needle in asset["name"].lower():
            return asset
    return None


def _arrow() -> str:
    return (
        '<svg class="icon-arrow" viewBox="0 0 24 24" fill="none" '
        'stroke="currentColor" stroke-width="1.8" aria-hidden="true">'
        '<path d="M5 12h14M13 6l6 6-6 6"/></svg>'
    )


def _button(label: str, url: str, primary: bool = False, external: bool = False) -> str:
    cls = "button button-primary" if primary else "button"
    attrs = ' rel="noopener"' if external and url.startswith("http") else ""
    return f'<a class="{cls}" href="{esc(url)}"{attrs}>{label}{_arrow()}</a>'


def _page_intro(title: str, lead: str, links: list[tuple[str, str]] | None = None) -> str:
    actions = ""
    if links:
        actions = '<div class="page-actions">' + "".join(
            _button(label, url, i == 0) for i, (label, url) in enumerate(links)
        ) + "</div>"
    return (
        '<section class="page-intro"><div class="wrap intro-grid">'
        f'<h1>{esc(title)}</h1><div class="intro-copy"><p>{lead}</p>{actions}</div>'
        "</div></section>"
    )


def _section(title: str, lead: str, body: str, anchor: str = "") -> str:
    aid = f' id="{anchor}"' if anchor else ""
    lead_html = f'<p class="section-lead">{lead}</p>' if lead else ""
    return (
        f'<section class="section"{aid}><div class="wrap">'
        f'<header class="section-head"><h2>{esc(title)}</h2>{lead_html}</header>'
        f"{body}</div></section>"
    )


def _route_stations(items: list[tuple[str, str, str]], rich: bool = False) -> str:
    """一条连续路线上的站点。编号表示真实顺序，不是装饰章节号。"""
    rows = []
    for i, (title, body, meta) in enumerate(items, 1):
        rows.append(
            '<li class="route-stop">'
            f'<span class="route-node" aria-hidden="true">{i:02d}</span>'
            '<div class="route-stop-copy">'
            f'<div class="route-stop-title"><strong>{esc(title)}</strong>'
            f'<span>{esc(meta)}</span></div>'
            f'<p>{body}</p></div></li>'
        )
    cls = "route-map route-map-rich" if rich else "route-map"
    return f'<ol class="{cls}" data-route>' + "".join(rows) + "</ol>"


def _timetable(headers: list[str], rows: list[list[str]], extra_class: str = "") -> str:
    cls = f"table-wrap timetable {extra_class}".strip()
    return table(headers, rows).replace('class="table-wrap"', f'class="{cls}"', 1)


def _status(key: str, label: str) -> str:
    return f'<span class="status status-{esc(key)}">{esc(label)}</span>'


def _empty_plugin_state(facts: dict) -> str:
    return (
        '<div class="notice notice-warning"><strong>插件索引没有载入。</strong>'
        "构建时没有读到同级目录里的 <code>YunGet-Plugins/plugins.json</code>。"
        f'页面不填充占位条目，请以 <a href="{esc(facts["plugin_index_url"])}">官方索引</a> 为准。</div>'
    )


def resolver(facts: dict) -> str:
    hosts = "、".join(item[0] for item in facts["platforms"])
    return (
        '<form class="resolver-console" data-resolver novalidate>'
        '<div class="resolver-label-row">'
        '<label for="share-input">粘贴分享文案或链接</label>'
        '<span>仅识别链接结构，不上传输入</span></div>'
        '<div class="resolver-input-row">'
        '<input id="share-input" name="share" type="text" autocomplete="off" '
        'spellcheck="false" placeholder="https://pan.quark.cn/s/… 或带提取码的整段分享文案">'
        '<button class="button button-primary" type="submit">识别链接</button></div>'
        '<p class="resolver-note" data-resolver-note>'
        f'目前支持识别的平台：{esc(hosts)}。识别只发生在当前浏览器页面。'
        "</p>"
        '<div class="resolver-result" data-resolver-result aria-live="polite">'
        '<div class="resolver-idle"><span class="route-node">00</span>'
        "<p>等待输入。这里只判断网盘主机与提取码；真正的目录解析、登录和取直链在 YunGet 应用内完成。</p>"
        "</div></div></form>"
    )


def home(facts: dict, href) -> str:
    latest = facts["latest"]
    universal = facts["apk"]
    size = _mb(universal["size"]) if universal else "以 Release 页为准"
    plugin_index = facts["plugin_index"] or {}
    plugins = plugin_index.get("plugins") or []
    plugin_rows = []
    for entry in plugins[:4]:
        version = ((entry.get("versions") or [{}])[0]).get("version") or ""
        plugin_rows.append(
            f'<li><span>{esc(entry.get("name") or entry.get("id") or "未命名")}</span>'
            f'<small>{esc(version)}</small></li>'
        )
    plugins_html = (
        '<ul class="compact-list">' + "".join(plugin_rows) + "</ul>"
        if plugin_rows
        else _empty_plugin_state(facts)
    )
    faq_items = [
        (
            "要登录吗？",
            "取决于网盘、分享链接和平台当前策略。完整目录浏览与直链提取可能需要登录；"
            "以平台当时的要求为准，应用会停在对应的登录步骤。",
        ),
        (
            "解析和下载会经过 YunGet 的中转服务器吗？",
            "不会。YunGet 不运营网盘文件中转服务。解析、取直链和下载路径都在设备上的应用内执行，"
            "网络请求发往对应网盘与文件地址。",
        ),
        (
            "账号凭证放在哪里？",
            "网盘凭证保存在应用数据库中，使用 Android Keystore 生成、不可导出的密钥加密。"
            "下载任务的请求头不在同一加密范围内，具体边界见隐私说明。",
        ),
        (
            "应该下载哪个 APK？",
            f"不确定时选通用包 <code>{esc(universal['name'] if universal else 'universal APK')}</code>。"
            "它兼容现有更新器；知道设备架构时，也可以选更小的 ABI 专用包。",
        ),
        (
            "插件能做什么？",
            "Android 版目前只运行 JavaScript 插件，原生插件已停用。插件负责分享识别、解析和取直链，"
            "下载仍由 TurboDL 执行；手动导入的脚本会标为未验证。",
        ),
    ]
    faq = "".join(
        f'<details class="faq-item"><summary>{esc(q)}</summary><div><p>{esc(a)}</p></div></details>'
        for q, a in faq_items
    )

    hero_route = _route_stations(
        [
            ("分享链接", "从聊天、浏览器或分享菜单进入。带提取码的整段文案也可以粘贴。", "输入"),
            ("平台解析", "应用向对应网盘请求目录或文件信息；平台需要时先完成登录。", "设备发起"),
            ("直链", "按当前权限换取可下载源地址；有效期由网盘决定。", "条件步骤"),
            ("TurboDL", "支持分片、断点续传和连接数调整；具体速度取决于服务端。", f"引擎 {facts['turbodl_version']}"),
            ("本地文件", "任务写入设备下载目录，完成后可直接打开或继续管理。", "终点"),
        ],
        rich=True,
    )

    platform_rows = [
        [
            f"<strong>{esc(name)}</strong>",
            esc(login),
            md_inline(capability),
            "已声明",
        ]
        for name, login, capability in facts["platforms"]
    ]

    return (
        '<section class="home-hero"><div class="wrap hero-grid">'
        '<div class="hero-copy">'
        "<h1>把分享链接，送上本地文件这一站。</h1>"
        '<p class="hero-lead">云取 YunGet 是面向 Android 的网盘链接解析与下载工具。'
        "它在设备上解析分享、浏览文件，再把选中的内容交给内置 TurboDL 下载；"
        "YunGet 不运营网盘中转服务器。</p>"
        '<div class="hero-actions">'
        + _button(f"下载 Android APK · {facts['version']}", href("download"), True)
        + _button("查看工作路线", "#how")
        + "</div>"
        '<dl class="hero-facts">'
        "<div><dt>平台</dt><dd>Android 7.0+</dd></div>"
        "<div><dt>通用包</dt><dd>" + size + "</dd></div>"
        "<div><dt>登录</dt><dd>视平台与链接而定</dd></div>"
        "<div><dt>许可</dt><dd>AGPL-3.0</dd></div>"
        "</dl></div>"
        '<div class="hero-route-panel"><div class="route-panel-title">'
        "<strong>一条链接的到站路线</strong><span>LOCAL ROUTE</span></div>"
        + hero_route
        + "</div></div></section>"
        '<section class="resolver-section" id="recognize"><div class="wrap">'
        '<header class="section-head inline-head"><h2>先识别手里的链接</h2>'
        "<p class=\"section-lead\">不伪装成已经解析完成。这个控件只做浏览器本地识别，"
        "然后告诉你在 YunGet 里下一步会发生什么。</p></header>"
        + resolver(facts)
        + "</div></section>"
        + _section(
            "一条路线，五个明确站点",
            "从输入到文件在本地落盘，每一步都有边界。平台需要登录时，应用会停在对应的登录步骤，而不是绕开权限。",
            _route_stations(
                [
                    (
                        "粘贴或分享进入",
                        "剪贴板里有可识别链接时，解析页会提示粘贴；也可以手动输入整段分享文案。",
                        "入口",
                    ),
                    (
                        "完成必要的平台登录",
                        "在应用内打开对应平台的登录流程。凭证保存在本机，之后请求发往该网盘。",
                        "按需",
                    ),
                    (
                        "浏览并选择文件",
                        "分享可能是文件夹。解析成功后可逐层浏览、按文件或多选入队。",
                        "选择",
                    ),
                    (
                        "交给 TurboDL",
                        "HTTP、HTTPS 与网盘直链进入同一套下载流程，支持暂停、续传和失败重试。",
                        "执行",
                    ),
                    (
                        "写入本地目录",
                        "默认保存到公共 Download 目录；自定义目录受 Android 存储权限约束。",
                        "到站",
                    ),
                ]
            ),
            "how",
        )
        + _section(
            "当前支持线路",
            f"应用当前声明 {len(facts['platforms'])} 个平台。线路可用性会受网盘接口变化影响，"
            "实际结果以应用内解析为准。",
            _timetable(["平台", "登录方式", "已声明的能力", "状态"], platform_rows),
        )
        + _section(
            "数据边界写清楚",
            "这里只写代码与隐私说明能核对的范围，不把泛化保证当成验证结果。",
            '<div class="split-feature">'
            '<div class="feature-copy">'
            '<div class="detail-list">'
            "<div><strong>解析路径</strong><p>分享解析、文件选择和直链获取由设备上的应用发起；没有 YunGet 文件中转服务器。</p></div>"
            "<div><strong>凭证加密</strong><p>网盘 Cookie、token 等凭证使用 AES-256-GCM 加密，密钥由 Android Keystore 生成且不可导出。</p></div>"
            "<div><strong>仍然需要注意</strong><p>下载任务的请求头在应用数据库中以明文保存；导出日志也可能包含带签名的临时直链。</p></div>"
            "</div>"
            + _button("阅读完整隐私说明", href("legal-privacy"))
            + "</div>"
            '<figure class="device-figure"><img src="{IMG:setting}" width="720" height="1600" '
            'loading="lazy" alt="YunGet 设置页中的下载线程、通知与日志选项">'
            "<figcaption>设置页：下载线程、通知与诊断入口。</figcaption></figure>"
            "</div>",
        )
        + _section(
            "TurboDL 在路线里做什么",
            "引擎负责把已选中的源地址变成可断点恢复的本地任务，不负责绕过网盘权限。",
            '<div class="engine-sheet">'
            '<div class="engine-mark"><span>TurboDL</span>'
            f'<strong>{esc(facts["turbodl_version"])}</strong><small>内置下载引擎</small></div>'
            '<div class="engine-rows">'
            "<div><span>传输</span><p>HTTP Range 分片、动态补片与失败重试。</p></div>"
            "<div><span>恢复</span><p>按磁盘上已经写完的字节继续，不必从零开始。</p></div>"
            "<div><span>并发</span><p>连接数可以调整；服务端限速和平台风控仍会决定最终速度。</p></div>"
            "</div></div>"
            + _button("查看下载引擎说明", href("doc-engines")),
        )
        + _section(
            "插件是更新解析线路的方式",
            (
                f"官方索引当前收录 {len(plugins)} 个条目。Android 版只启用 JavaScript 插件，"
                "下载引擎不交给插件重写。"
                if plugins
                else "官方插件索引当前未载入；页面不生成虚假条目。"
            ),
            '<div class="plugin-window"><div class="window-head">'
            f"<strong>官方索引</strong><span>{len(plugins)} 个条目</span></div>"
            + plugins_html
            + '<div class="window-actions">'
            + _button("浏览插件", href("plugins"))
            + _button("开发文档", href("developers"))
            + "</div></div>",
        )
        + _section("常见问题", "内容默认展开遵循浏览器原生行为；没有 JavaScript 也能阅读。", f'<div class="faq-list">{faq}</div>')
        + '<section class="end-station"><div class="wrap end-grid">'
        '<div><span class="end-node" aria-hidden="true"></span>'
        f"<h2>终点站：拿到 {facts['version']} 安装包</h2>"
        f"<p>{esc(facts['min_sdk'])} · 通用 APK {size} · AGPL-3.0</p></div>"
        '<div class="end-actions">'
        + _button("下载通用 APK", href("download"), True)
        + _button("查看发布记录", href("changelog"))
        + "</div></div></section>"
    )


def download(facts: dict, href) -> str:
    universal = facts["apk"]
    if not universal:
        return _page_intro("下载", "当前发布数据里没有 APK 附件。", [(("GitHub Release"), facts["latest"]["url"])])
    version = facts["version"]
    sha = universal.get("sha256") or ""
    arch_rows = []
    descriptions = {
        "universal": ("通用兼容包", "不确定设备架构时选它；也是旧更新器兼容入口。"),
        "arm64": ("ARM64", "大多数近年的 Android 手机。"),
        "armeabi": ("ARMv7", "仍在使用 32 位 ARM 的设备。"),
        "x86_64": ("x86_64", "对应架构的平板、Chromebook 或测试设备。"),
    }
    for asset in facts["apks"]:
        name = asset["name"].lower()
        key = "universal" if "universal" in name else "x86_64" if "x86" in name else "arm64" if "arm64" in name else "armeabi"
        label, why = descriptions[key]
        arch_rows.append(
            [
                f"<strong>{esc(label)}</strong><br><span class=\"mono small\">{esc(asset['name'])}</span>",
                why,
                _mb(asset["size"]),
                f'<span class="mono small">{esc((asset.get("sha256") or "")[:16])}…</span>',
                _button("下载", _edge(facts, asset["url"])),
            ]
        )
    release_rows = [
        [
            f'<span class="mono">{esc(item["tag"])}</span>'
            + (' <span class="status status-planned">预发布</span>' if item.get("prerelease") else ""),
            esc((item.get("published") or "")[:10]),
            f"{len(item.get('assets') or [])}",
            f'<a href="{esc(item["url"])}">发布页</a>',
        ]
        for item in facts["releases"][:10]
    ]
    checksum = (
        code_block(
            f"# Linux / macOS\nsha256sum {universal['name']}\n"
            f"# 期望值\n{sha}",
            "SHA-256",
        )
        if sha
        else ""
    )
    return (
        _page_intro(
            "下载 YunGet",
            f"当前版本 <strong>{esc(facts['latest']['tag'])}</strong>，"
            f"发布于 {esc((facts['latest'].get('published') or '')[:10])}。"
            "先选通用包；明确知道设备架构时再选专用包。",
        )
        + '<section class="download-hero"><div class="wrap download-grid">'
        '<div class="primary-download">'
        '<div class="download-heading"><span class="status status-ready">Android 可用</span>'
        f"<h2>{esc(universal['name'])}</h2><p>通用 APK · {_mb(universal['size'])} · {esc(facts['min_sdk'])}</p></div>"
        '<div class="download-actions">'
        + _button("从 Cloudflare 下载", _edge(facts, universal["url"]), True)
        + _button("GitHub 原始附件", universal["url"], external=True)
        + "</div>"
        f'<div class="checksum"><span>SHA-256</span><code>{sha}</code></div>'
        "</div>"
        '<div class="install-board"><h2>安装只走三步</h2><ol>'
        "<li><span>1</span><div><strong>下载 APK</strong><p>核对文件名与版本号。</p></div></li>"
        "<li><span>2</span><div><strong>允许本次安装</strong><p>应用未上架商店，系统会提示未知来源。</p></div></li>"
        "<li><span>3</span><div><strong>覆盖安装</strong><p>同签名版本可保留登录状态和下载任务。</p></div></li>"
        "</ol></div></div></section>"
        + _section(
            "按设备架构选择",
            "通用包是默认答案。ABI 专用包更小，但必须与设备架构匹配。",
            _timetable(["安装包", "适用情况", "大小", "SHA-256", ""], arch_rows)
            + checksum
            + '<p class="small">校验命令与完整摘要对应本页所列附件。若文件名或摘要不一致，不要安装。</p>',
            "packages",
        )
        + _section(
            "平台状态",
            "只有 Android 提供可下载构建。没有构建的平台不会显示可点击的下载按钮。",
            _timetable(
                ["平台", "状态", "当前说明"],
                [
                    ["Android", _status("ready", "可下载"), f"{esc(facts['min_sdk'])}；targetSdk {esc(facts['target_sdk'])}"],
                    ["Windows", _status("planned", "规划中"), "尚无公开安装包。"],
                    ["macOS", _status("planned", "规划中"), "尚无公开安装包。"],
                    ["Linux", _status("planned", "规划中"), "尚无公开安装包。"],
                ],
            ),
        )
        + _section(
            "历史版本",
            "历史列表只用于核对；新安装优先使用当前版本。",
            '<details class="history-drawer"><summary>展开最近 10 次发布</summary>'
            + _timetable(["版本", "日期", "附件数", "入口"], release_rows)
            + "</details>",
            "history",
        )
        + _section(
            "安装前后还可能需要什么",
            "这些设置不影响 APK 真伪，但会影响下载的保存位置与后台行为。",
            '<div class="detail-list detail-columns">'
            "<div><strong>Android 11 及以上</strong><p>默认 Download 目录无需额外授权；改到其他公共目录时，系统可能要求“所有文件访问”。</p></div>"
            "<div><strong>后台下载</strong><p>长任务需要通知、前台服务与保持唤醒；系统省电策略仍可能中断任务。</p></div>"
            "<div><strong>更新</strong><p>应用检查更新读取 GitHub Release；同签名版本可覆盖安装。</p></div>"
            "<div><strong>安全边界</strong><p>只从本页列出的 Release 附件下载，并核对 SHA-256。签名摘要不能替代对文件来源的核对。</p></div>"
            "</div>",
        )
    )


def _doc_route_rows(facts: dict, keys: list[str], href) -> str:
    by_key = {item["key"]: item for item in facts["doc_pages"]}
    rows = []
    for key in keys:
        item = by_key.get(key)
        if not item:
            continue
        rows.append(
            '<li class="doc-stop"><a href="' + href(key) + '">'
            f'<span class="doc-stop-title">{esc(item["title"])}</span>'
            f'<span class="doc-stop-copy">{esc(item["blurb"])}</span>'
            f'<span class="doc-stop-file">{esc(item.get("src") or "代码生成")}</span>'
            + _arrow()
            + "</a></li>"
        )
    return '<ul class="doc-route">' + "".join(rows) + "</ul>"


def docs(facts: dict, href) -> str:
    return (
        _page_intro(
            "文档",
            "每一页都镜像仓库里的同一份源文件。官网和 GitHub 不维护两套说法；"
            "首次使用可以先走下面这条阅读路线。",
            [("先看使用说明", href("doc-guide")), ("查看全部发布", href("changelog"))],
        )
        + _section(
            "从这里开始",
            "第一次安装，按“项目说明 → 使用说明 → 常见问题”的顺序最快。",
            _doc_route_rows(facts, ["doc-readme", "doc-guide", "doc-faq"], href),
            "start",
        )
        + _section(
            "理解下载实现",
            "想从源码运行或排查下载问题，再看构建与引擎文档。",
            _doc_route_rows(facts, ["doc-build", "doc-engines"], href),
            "implementation",
        )
        + _section(
            "插件开发",
            "Android 版只启用 JavaScript 插件。先读边界，再看 ABI 与发布流程。",
            _doc_route_rows(
                facts,
                ["doc-plugin-dev", "doc-plugin-api", "doc-plugin-publish", "doc-plugin-security"],
                href,
            ),
            "plugins",
        )
        + _section(
            "协议与说明",
            "下载与使用前需要知道的版权、隐私和责任边界都保留在这里。",
            _doc_route_rows(
                facts,
                ["legal-license", "legal-notice", "legal-privacy", "legal-terms"],
                href,
            ),
            "legal",
        )
    )


CATEGORY_LABELS = {
    "turbodl-parser": "链接解析",
    "turbodl-backend": "下载后端",
    "turbodl-adapter": "适配器",
    "turbodl-protocol": "协议",
    "turbodl-hook": "生命周期钩子",
    "turbodl-loader": "加载器",
    "yung-support": "支持页",
}


def _plugin_entry(entry: dict) -> str:
    versions = entry.get("versions") or []
    latest = versions[0] if versions else {}
    chips = [
        f'<span class="tag">{esc(CATEGORY_LABELS.get(entry.get("category"), entry.get("category") or "未分类"))}</span>',
        f'<span class="tag">{esc(entry.get("level") or "js")}</span>',
        f'<span class="tag">v{esc(latest.get("version") or "—")}</span>',
    ]
    for protocol in entry.get("protocols") or []:
        chips.append(f'<span class="tag">{esc(protocol)}://</span>')
    links = []
    for label, url in (
        ("源码", entry.get("sourceUrl") or entry.get("homepage")),
        ("清单", entry.get("manifestUrl")),
        ("脚本", latest.get("downloadUrl")),
    ):
        if url:
            links.append(f'<a href="{esc(url)}">{label}</a>')
    link_html = '<span class="plugin-links">' + " · ".join(links) + "</span>" if links else ""
    return (
        '<li class="plugin-row"><div class="plugin-main">'
        f'<h3>{esc(entry.get("name") or entry.get("id") or "未命名")}</h3>'
        f'<p>{esc(entry.get("summary") or "")}</p>'
        f'<div class="tag-row">{"".join(chips)}</div></div>'
        f'<div class="plugin-side">{link_html}</div></li>'
    )


def _plugin_groups(facts: dict) -> str:
    index = facts["plugin_index"]
    if not index:
        return _empty_plugin_state(facts)
    entries = index.get("plugins") or []
    if not entries:
        return '<div class="notice">索引已加载，但当前没有插件条目。</div>'
    groups: dict[str, list[dict]] = {}
    for entry in entries:
        category = entry.get("category") or ""
        groups.setdefault(category, []).append(entry)
    sections = []
    for category, members in sorted(groups.items(), key=lambda item: CATEGORY_LABELS.get(item[0], item[0])):
        label = CATEGORY_LABELS.get(category, category or "未分类")
        sections.append(
            f'<section class="plugin-group"><h3>{esc(label)}</h3><ul>'
            + "".join(_plugin_entry(entry) for entry in members)
            + "</ul></section>"
        )
    return "".join(sections)


def plugins(facts: dict, href) -> str:
    index = facts["plugin_index"] or {}
    entries = index.get("plugins") or []
    generated = (index.get("generatedAt") or "")[:10]
    source = index.get("source") or {}
    count_line = (
        f"官方索引当前收录 {len(entries)} 个条目"
        + (f"，生成于 {generated}" if generated else "")
        + "。页面数量与条目直接来自同一份 <code>plugins.json</code>。"
    )
    trust_rows = [
        ["<strong>官方</strong>", "来源是官方源，且签名密钥与该源匹配。", "显示官方标记", "仍需查看权限与脚本行为"],
        ["<strong>已验证</strong>", "用户添加的源，但清单签名与来源密钥可以核对。", "显示已验证标记", "仍需自行判断来源"],
        ["<strong>社区</strong>", "用户添加、仍带签名信息的源。", "显示社区标记", "不等同官方审核"],
        ["<strong>未验证</strong>", "没有可核对的签名或密钥，例如手动导入的脚本。", "明确警示", "执行前应审阅代码"],
    ]
    source_rows = []
    if index:
        verification = index.get("verification") or {}
        source_rows.append(
            [
                esc(source.get("name") or "官方插件源"),
                esc(source.get("trustLevel") or "official"),
                esc(verification.get("algorithm") or "—"),
                esc(verification.get("payload") or "—"),
                f'<a href="{esc(facts["plugin_index_url"])}">plugins.json</a>',
            ]
        )
    return (
        _page_intro(
            "插件",
            "插件把“识别分享、解析目录、获取直链”做成可更新的 JavaScript 线路；"
            "下载仍由应用内置的 TurboDL 执行。原生插件在 Android 版已停用。",
            [("阅读插件安全模型", href("doc-plugin-security")), ("写一个插件", href("developers"))],
        )
        + _section(
            "官方索引",
            count_line,
            _plugin_groups(facts)
            + (
                _timetable(["来源", "信任等级", "签名算法", "签名对象", "索引"], source_rows)
                if source_rows
                else ""
            ),
            "index",
        )
        + _section(
            "安装插件的两条路",
            "优先从应用内插件市场安装；手动导入的文件不会被包装成“已验证”。",
            '<div class="install-methods">'
            '<div class="method"><div><h3>应用内插件市场</h3>'
            f'<p>打开“插件 → 插件市场”，添加来源 <code>{esc(facts["plugin_index_raw"])}</code>，'
            "查看权限和信任等级后安装。已安装插件可以检查更新与卸载。</p></div></div>"
            '<div class="method method-warning"><div><h3>手动导入 <span class="status status-unverified">未验证</span></h3>'
            "<p>从本地选择 <code>.js</code> 文件或粘贴脚本。手动导入没有来源签名链，"
            "必须视为未验证代码；安装前应自己阅读脚本与权限声明。</p></div></div></div>",
            "install",
        )
        + _section(
            "信任等级阅读方式",
            "等级描述来源与签名，不描述脚本行为是否安全，也不代替代码审阅。",
            _timetable(["等级", "成立条件", "界面表现", "仍需判断"], trust_rows),
            "trust",
        )
        + _section(
            "能力边界",
            "插件是解析器，不是第二个下载引擎，也不是远端代理。",
            '<div class="detail-list detail-columns">'
            "<div><strong>可以</strong><p>识别分享、拼接请求、读取网盘页面或 API 响应、返回结构化下载信息。</p></div>"
            "<div><strong>不可以</strong><p>接管 TurboDL 的分片下载，或把应用伪装成网盘文件中转服务。</p></div>"
            "<div><strong>签名验证</strong><p>可以证明脚本字节与某个密钥发布者一致；它不能证明作者可信或代码没有恶意。</p></div>"
            "<div><strong>网络权限</strong><p>脚本的 <code>http</code> 权限意味着它可以把请求发往声明允许的目标；安装前要读权限。</p></div>"
            "</div>",
        )
        + _section(
            "继续阅读",
            "需要写、签、发插件时，从开发者页进入完整文档链。",
            _doc_route_rows(
                facts,
                ["doc-plugin-dev", "doc-plugin-api", "doc-plugin-publish", "doc-plugin-security"],
                href,
            ),
        )
    )


def developers(facts: dict, href) -> str:
    sample = (
        "plugin.protocols = [\"demo://\"];\n\n"
        "plugin.onResolve(async (ctx) => {\n"
        "  const url = await host.http.request({\n"
        "    method: \"GET\",\n"
        "    url: \"https://example.com/file\",\n"
        "  });\n"
        "  return {\n"
        "    name: host.path.basename(url),\n"
        "    url,\n"
        "  };\n"
        "});"
    )
    return (
        _page_intro(
            "写一个解析插件",
            "插件是一段受宿主能力约束的 JavaScript。它把分享信息变成结构化下载源，"
            "不接管分片下载，也不直接运行原生扩展。",
            [("开始阅读", href("doc-plugin-dev")), ("ABI 参考", href("doc-plugin-api"))],
        )
        + _section(
            "先看边界，再写代码",
            "宿主只暴露在清单中声明并经用户确认的能力。",
            _route_stations(
                [
                    ("声明协议", "插件说明自己识别哪些协议或链接形态，应用按协议建立索引。", "manifest"),
                    ("申请权限", "网络、小存储、日志等能力需要写入清单；安装前展示给用户。", "capability"),
                    ("解析与取链", "脚本读取页面或 API 响应，返回文件名、地址和必要请求头。", "runtime"),
                    ("交给 TurboDL", "分片、并发、续传和本地写入仍由 Kotlin 下载引擎执行。", "engine"),
                ]
            ),
        )
        + _section(
            "最小脚本形状",
            "示例只展示入口结构。真实实现请以仓库中的文档与示例插件为准。",
            code_block(sample, "parser.demo.js"),
            "example",
        )
        + _section(
            "开发路径",
            "从最小解析器开始，随后补 ABI、签名与发布。",
            _doc_route_rows(
                facts,
                ["doc-plugin-dev", "doc-plugin-api", "doc-plugin-publish", "doc-plugin-security"],
                href,
            ),
            "path",
        )
        + _section(
            "发布前检查",
            "同一版本不得改写字节；改动脚本就要升版本并重新签名。",
            '<div class="detail-list detail-columns">'
            "<div><strong>清单</strong><p>插件 id、名称、版本、分类、协议与入口必须完整。</p></div>"
            "<div><strong>签名</strong><p>对脚本原始字节计算 SHA-256，并使用声明密钥签名。</p></div>"
            "<div><strong>索引</strong><p>提交到官方源，或部署一个可通过 HTTPS 访问的静态源。</p></div>"
            "<div><strong>验证</strong><p>验证签名只证明发布链一致，不证明代码安全。</p></div>"
            "</div>",
        )
    )


def changelog(facts: dict, href) -> str:
    rows = []
    for release in facts["releases"][:16]:
        summary = escape((release.get("summary") or "").replace("**", "").replace("`", ""))
        rows.append(
            '<article class="release-row">'
            f'<div class="release-id"><strong>{esc(release["tag"])}</strong>'
            f'<span>{esc((release.get("published") or "")[:10])}</span></div>'
            f'<p>{summary}</p>'
            f'<a href="{esc(release["url"])}">发布页</a></article>'
        )
    return (
        _page_intro(
            "更新日志",
            "版本、日期与附件数量来自 GitHub Release 数据；应用内检查更新读取同一来源。",
            [("下载当前版本", href("download")), ("GitHub Releases", f"{facts['repo']}/releases")],
        )
        + _section("最近发布", "完整发布说明保留在 GitHub；这里列出便于扫描的版本路线。", "".join(rows))
    )


def community(facts: dict, href) -> str:
    return (
        _page_intro(
            "社区与反馈",
            "项目没有聊天群或评论系统。可复现的问题、日志和失败案例都走 GitHub Issues。",
            [("提交 Issue", f"{facts['repo']}/issues"), ("阅读使用说明", href("doc-guide"))],
        )
        + _section(
            "一份有效的报告",
            "先给出环境与复现步骤，再附上脱敏后的日志。",
            _route_stations(
                [
                    ("应用版本", "在设置的关于页查看当前版本。", "环境"),
                    ("设备与系统", "写明手机型号、Android 版本和安装包架构。", "环境"),
                    ("复现步骤", "从哪条链接开始，点了什么，期望与实际分别是什么。", "过程"),
                    ("脱敏日志", "导出后先移除完整直链、文件名或其他不想公开的信息。", "证据"),
                ]
            ),
        )
        + _section(
            "与上游项目的关系",
            "YunGet 是 CYQawa/YunX 的二次开发版本；两个项目各自维护。",
            '<div class="detail-list detail-columns">'
            "<div><strong>代码与许可</strong><p>本仓库遵循 AGPL-3.0，并保留上游版权与协议声明。</p></div>"
            "<div><strong>问题归属</strong><p>本版本的问题提交到 YunGet Issues，上游不负责本版本改动。</p></div>"
            "<div><strong>赞赏</strong><p>应用内赞赏入口只面向本二次开发版本，与上游捐赠渠道无关。</p></div>"
            "<div><strong>上游</strong><p><a href=\"https://github.com/CYQawa/YunX\">CYQawa/YunX</a></p></div>"
            "</div>",
        )
    )


def support(facts: dict, href) -> str:
    return (
        _page_intro(
            "支持开发",
            "项目免费开源，所有功能都不依赖赞赏。若愿意支持持续维护，可以使用应用内说明的赞赏入口。",
            [("查看 Issues", f"{facts['repo']}/issues"), ("阅读许可", href("legal-license"))],
        )
        + _section(
            "赞赏说明",
            "赞赏面向本二次开发版本的维护，与上游作者无隐性关联。",
            '<figure class="support-figure"><img src="{IMG:weixin}" width="1190" height="1190" '
            'loading="lazy" alt="云取 YunGet 的微信赞赏码"><figcaption>微信赞赏码。保存后可用微信扫一扫。</figcaption></figure>'
            '<div class="notice notice-warning">若想支持上游原项目，请前往 CYQawa/YunX 项目的捐赠渠道。'
            "两者互不相关。</div>",
        )
        + _section(
            "不用赞赏也能帮上忙",
            "可复现的问题和清楚的失败案例，比一句“挺好用”更能推动项目。",
            '<div class="detail-list">'
            "<div><strong>报一个能复现的问题</strong><p>附版本、机型、网盘和脱敏日志。</p></div>"
            "<div><strong>反馈平台变化</strong><p>说明哪条线路何时失效，以及界面给出的具体错误。</p></div>"
            "<div><strong>把边界讲清楚</strong><p>推荐给需要本地解析与下载的人，不用“万能下载器”误导。</p></div>"
            "</div>",
        )
    )


def legal(facts: dict, href) -> str:
    return (
        _page_intro(
            "协议与说明",
            "这里保留下载、使用、隐私与开源分发所需的真实文件。",
            [("隐私说明", href("legal-privacy")), ("AGPL-3.0 原文", href("legal-license"))],
        )
        + _section(
            "四份文件",
            "文档正文由仓库源文件直接生成，不在官网另写一套摘要。",
            _doc_route_rows(
                facts,
                ["legal-license", "legal-notice", "legal-privacy", "legal-terms"],
                href,
            ),
        )
        + _section(
            "数据边界摘要",
            "完整范围和例外以隐私说明原文为准。",
            '<div class="detail-list detail-columns">'
            "<div><strong>没有统计与广告</strong><p>站点不加载分析脚本，应用没有广告 SDK。</p></div>"
            "<div><strong>没有自动日志上报</strong><p>诊断与崩溃信息保存在本地，导出需要手动操作。</p></div>"
            "<div><strong>凭证加密</strong><p>网盘凭证由 Android Keystore 保护的密钥加密。</p></div>"
            "<div><strong>请求头例外</strong><p>下载任务请求头在应用数据库中明文保存，详见隐私说明。</p></div>"
            "</div>",
        )
    )
