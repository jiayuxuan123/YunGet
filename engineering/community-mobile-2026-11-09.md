# 社区手机界面重写

## 变更范围

仅更新 `community/` 的网页与相关测试。应用仍为 YunGet 2.7.28，原「公告与社区」入口刷新后加载新界面，不需要额外安装 APK。

- 手机导航改为反馈、公告、闲聊三个等宽入口，删除占用首屏的大标题、长说明与统计卡。
- 反馈列表前置，点击「写反馈」进入表单。保留问题、建议、提问、产品选择、私密反馈和版本信息。
- 闲聊消息区与输入栏通过 Grid 分配独立空间；输入栏不使用浮动覆盖定位。
- Visual Viewport 高度及偏移用于处理键盘；320px 窄屏验证控件使用 compact 尺寸。
- 自动刷新增量追加消息，不重建输入框，不丢文字或焦点。阅读历史时显示新消息入口，由用户决定何时跳转。
- 修复旋转改变换行后，跟随最新消息的位置偏移。
- 表单、昵称和验证步骤之间保留草稿；提交失败可重试，同一发送动作保留幂等键。
- 公告保留完整正文和受限 HTTPS 链接；支持浅色、深色、跟随系统和减少动态效果。
- 后台在手机上单列显示。补齐隐藏消息、隐藏回复的界面入口，并修复反馈详情未返回管理员可见隐藏状态的问题。

保留原 Worker/D1、公告 API、会话、Turnstile 服务端校验与 Cloudflare Access。无数据库迁移。

## 验证

- 社区全部 Node 测试：10 项通过，0 失败、0 跳过。包括浏览器脚本实际语法、内容转义、后台鉴权和隐藏状态隔离。
- 浏览器集成：33 项通过；额外 4 项验证 320px compact 验证、仅 Visual Viewport 缩短时的弹窗与保存按钮、720px 导航。
- 视口包括 320×568、390×844、390×430、720×740、1440×900。检查反馈、公告、闲聊、回复、后台编辑、主题与焦点。
- 自动刷新检查使用 36 条示例消息，验证草稿、焦点、历史阅读位置和新消息提示。
- 局部检查与回归均在本地夹具进行；写操作和验证码替身只访问 `127.0.0.1:8801`，未往正式社区插入测试消息。
- 公网实测新版资源、访客填写及取消昵称后的草稿保留、浅深色聊天、公告 API；后台仍返回 302 到 Cloudflare Access。
- 本轮没有实际连接的 Android 设备，视口测试不代表真机触摸或软键盘验收。
- 改动仅涉及社区网页，未重复运行 Android/引擎全量测试。

证据目录：`output/playwright/community-mobile/`（本地忽略，不提交）。包括 `evidence.json`、`checks-final.log`、`keyboard-modal-checks.log` 和浅深色截图。按用户要求，本轮由主智能体完成实现和有界截图复核，未分派子代理。

## 部署

- 地址：<https://community.yunget.yunl.us.ci/>
- Worker：`yunget-community`
- 部署版本：`38b8a982-bb9b-4a78-8b35-abb0e9430bdf`
- Wrangler 4.158.0，dry-run 成功；发布包 79.66 KiB，gzip 22.77 KiB。
- 新 CSS/JS 入口带资源版本参数，避免沿用旧版缓存。

## 设计依据

采用 Astryx 的 ChatLayout、消息列表与输入栏分离、控件触控范围及分组经验，保留轻量原生网页实现。社区的实际令牌与结构记录在 `community/DESIGN.md`，独立于官网营销视觉规范。

Turnstile 尺寸与生命周期按[官方配置说明](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/widget-configurations/)和[显式渲染说明](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/)核对。
