# YunGet（云取）

> 网盘分享链接解析与高速下载的 Android 应用。粘贴分享链接，就能浏览分享内容并直接下载文件。
>
> **本项目是 [CYQawa/YunX（云析）](https://github.com/CYQawa/YunX) 的二次开发版本**，在其协议与架构基础上进行功能扩展，并将下载能力逐步迁移至独立的 [TurboDL](https://github.com/jiayuxuan123/TurboDL) 下载引擎。遵循 GNU AGPL-3.0 协议开源。

本次更新修复下载页连接数长期显示 0，以及部分旧版本升级时下载任务表迁移失败的问题，详见[2.7.35 完整更新日志](engineering/releases/2.7.35.md)。

当前版本 **2.7.35**（versionCode 83）· Android 7.0+（minSdk 24，targetSdk 34）· 内置 TurboDL 0.5.0.6

**官网**：[jiayuxuan123.github.io/YunGet](https://jiayuxuan123.github.io/YunGet/) —— 下载、使用说明、常见问题、隐私与条款

**文档**：[使用说明](docs/GUIDE.md) · [常见问题](docs/FAQ.md) · [下载引擎](docs/ENGINES.md) · [从源码构建](docs/BUILD.md) · [隐私说明](docs/PRIVACY.md) · [使用条款](docs/TERMS.md) · [插件开发](docs/PLUGIN-DEV.md) · [插件 ABI 参考](docs/PLUGIN-API.md) · [插件发布](docs/PLUGIN-PUBLISH.md) · [插件安全模型](docs/PLUGIN-SECURITY.md)

---

## 与上游（YunX）的关系

- 本仓库 fork 自上游 [CYQawa/YunX](https://github.com/CYQawa/YunX)，感谢原作者的工作。
- 上游协议为 **GNU AGPL-3.0**，本项目同样以 **AGPL-3.0** 继续开源，并保留原始版权与协议声明（见 [NOTICE](NOTICE)）。
- 本项目在上游协议解析、网盘功能和 Android 应用结构基础上进行了二次开发。
- 下载能力已迁移到独立的 [TurboDL](https://github.com/jiayuxuan123/TurboDL) SDK：日常的直链下载走它，HLS 通过它的插件机制接入。项目早期自己写的那套下载实现仍留在代码里，作为可选的兜底引擎。
- **赞赏渠道说明**：应用内「支持开发」页的赞赏码仅面向**本二次开发版本**的维护；若想支持上游原项目作者，请移步 [上游仓库](https://github.com/CYQawa/YunX) 的捐赠渠道，避免权益混淆。

## 2.7.35 更新

- TurboDL 升级至 `0.5.0.6`，插件路由下载现在也会上报活动连接、请求、字节和 HTTP 状态。
- 路由下载继续使用客户端全局连接预算，不会因多任务而绕过总连接上限。
- 数据库升级至版本 26，重新整理旧版下载任务表，保留下载进度、账号、收藏、插件和社区身份。
- 迁移复制任务数校验失败时回滚并保留原数据库，不再要求清除软件数据。

## 2.7.34 更新

- 下载失败时保留最近一次真实 HTTP 状态码：限流显示具体 `429` 或 `503`，授权/时效显示具体 `401`、`403` 或 `410`。
- 分片请求开始后即标记为传输阶段；服务器未返回首字节就限流时，不再误报成探测失败。
- 匿名统计后台在旧版只上报合并类别时显示 `429/503（类别）` 或 `401/403/410（类别）`，不再一律显示“未上报”。
- 内置引擎升级至 TurboDL 0.5.0.5；下载接口、旧断点和插件接口保持兼容。

## 2.7.33 更新

- 「公告与社区」升级为完整社区：独立公开 UID、找回码、发帖、图片、投票、评论、点赞和处理状态。
- 主界面收藏按钮左移，原位置新增社区通知入口和未读角标；社区、反馈进度与公告统一进入通知列表。
- 普通成员不再看到开发者后台和匿名统计入口；社区仍使用同域验证、Cloudflare Access 和文本转义。
- 开发者后台在手机上直接提供社区与帖子、匿名统计、旧闲聊和公开社区入口；普通用户也可从「我的」进入旧闲聊。
- 修复手机底栏五个入口错位；选择图片导致 WebView 重新加载时，页面、草稿和已上传图片会恢复。
- 重新裁切自适应、圆形、单色和旧版启动图标，修复云朵下方箭头被手机图标形状裁掉的问题。
- 内置 TurboDL 0.5.0.4：探测遇到 429/503 时遵守 Retry-After，长时间限流直接保留断点，不连续重试；暂停和取消会及时关闭阻塞中的探测请求。

## 2.7.31 更新

- 下载完成后按文件类型显示“可以安装 / 可以预览 / 可以打开”；本地文件缺失时不再提供误导性的打开提示。
- 下载页支持左右滑动在“下载”和“已完成”之间切换，并保留各自列表位置。
- 新版和经典解析页都可扫描二维码，把内容填入解析框；识别完全在设备本机完成。
- 新增可关闭的匿名使用统计：首次展示说明，关闭后清空待发送队列；开发者后台可查看聚合趋势，不记录链接、文件名、账号、Cookie、二维码内容或 IP。
- 更新隐私说明，明确匿名统计的字段、保留期限和关闭方式。

## 2.7.30 更新

- 新任务保存分片、慢启动及连接上限等参数；恢复使用保存的策略，同时遵守当前用户设置的上限。
- 具备来源记录的夸克任务支持在地址过期后，使用原账号受控换链并验证有效断点。
- 保存失败时保留完整缓存，重试优先继续保存；保存提交失败不会被标记为下载完成。
- 下载、媒体处理和保存状态独立记录；暂停或重试后的旧失败回调不能覆盖新任务。
- 内置 TurboDL 0.5.0.3，增加同源限流协调和任务诊断；详情中可查看传输参数、开始接收与传输耗时。

## 2.7.28 更新

- 修复经典模式只切换部分外观的问题，恢复 2.7.26 的实际解析、网盘、下载和设置布局；两种界面共用账号、任务和引擎。
- 新增“显示设置说明”开关，默认简洁显示，目录、连接数、限速等当前值继续可见。
- 新版采用柔和的云取蓝与灰白层次，减少重复图标底板和边框，优化设置值对齐、大字号换行与深色阅读。
- 主题选项支持滑动选中反馈，按可用空间横排或竖排；下载页压缩批量操作，暂停、完成与失败分别表达状态。
- 「公告与社区」接入 Cloudflare 社区：普通用户无需开发者登录即可填写反馈，提交时再设置昵称并完成人机验证；开发者后台仅用于处理反馈和公告。
- 保留经典任务详情、媒体预览、安装及目录内任务操作；TurboDL 保持 0.5.0.1。

完整更新见 [2.7.28 变更](engineering/releases/2.7.28.md)。

## 2.7.27 更新

- 全面重写主要页面为 Google Material 3 Expressive：采用分组列表、鲜明蓝色强调、近黑深色表面与统一圆角层级，主界面、下载页和设置页使用一致的视觉语言。
- 文案与字阶重新标定：设置行标题和图内主标题为 16sp，正文 14sp，辅助说明 12sp；下载量、剩余时间、连接数等关键数据不再使用注释字号。
- 动效提供增强、标准和关闭三档；页面过渡、选择反馈、加载指示器与波浪进度条使用同一偏好。
- 宽窗口采用导航侧栏，包含竖屏平板；手机保留四个目的地的底栏。
- 接入经确认的纯白底云取图标，支持 Android 自适应与单色主题图标。
- 主题页新增字号与间距控制：默认按窗口宽高比自动适配高屏、窄屏和平板，也可关闭自动后手动滑杆调节。
- 主题页新增「经典界面 / 新版界面」切换：经典模式恢复 2.7.26 的大标题顶栏、经典字阶与标准 Material 组件形态，新版模式保留分组卡片与 Expressive 动效。
- 「支持开发」页新增微信、支付宝双赞赏码，支持点选和左右滑动翻页。
- 去除未使用的 Cupertino 与玻璃框架依赖，长文件名仅聚焦时滚动，引导背景改为随页面变化。
- 延续 TurboDL 0.5.0.1 与原有账号、下载、上传、预览和插件功能。

## 2.7.26 更新

- 内置 TurboDL 升级至 **0.5.0.1**，保留 Android Kotlin/ART 下载路径。
- 修复 aria2 可选内核下载仅依赖 GitHub API 和直链的问题：增加官网版本清单、官方加速、设置中的下载镜像及直连回退。
- 内核包下载校验 SHA-256、包名、发布签名和 ARM64 二进制；失败通道不会留下可安装的半包。
- 新增内核下载取消操作，复用已校验的缓存包，并明确首次安装授权后的操作提示。
- 下载引擎页显示实际内置版本，并接入「公告与社区」更新说明入口。
- TurboDL 的 DoH 响应在解析前限制为 64 KiB。

四种应用 APK 均支持覆盖安装；aria2 仍为独立可选 ARM64 内核，无需重新下载已安装且可用的内核。

## 2.7.25 更新

同步上游十家个人盘上传、139 口令、缩略图、在线预览与账号排序；新增基于 Media3 的内嵌播放器，可查看在线及已下载的本地媒体。延续现有 Material 风格，补充导航、列表及进度动效。

TurboDL 保持内置，Gopeed 联网获取内核，aria2 拆成独立可选 ARM64 内核 APK。普通使用只需安装应用 APK；不确定设备架构时选择 universal。应用更新器与官网下载入口均排除可选内核包。Release 提供四种应用包、一个可选内核和 SHA-256 校验文件。

真实账号上传、真机播放及不同 OEM 的可选内核运行仍待设备验收。完整变更与验证范围见 [迁入记录](engineering/upstream-sync-2026-11-06.md)。

## 支持平台

> **不建议使用百度网盘，可能导致账号被风控，请谨慎使用。**

| 网盘 | 登录方式 |
|---|---|
| 夸克网盘 / UC 网盘 | 应用内网页登录 |
| 迅雷网盘 | 账号密码 / 短信 |
| 百度网盘 | 应用内网页登录 |
| 123 云盘 | 网页登录 |
| 139 网盘（和彩云） | 应用内网页登录 |
| 115 网盘 / 光鸭云盘 | 应用内登录页 |
| 蓝奏云 | 网页登录 |
| 蓝奏云优享版 | 账号密码 |

网盘接口是抓包分析得到的，官方一改就可能失效 —— 某个平台在某段时间不可用属于常态，以实际运行结果为准。

## 功能

### 网盘解析

- **分享链接解析**：识别夸克 / UC / 迅雷 / 百度 / 139 / 123 的分享链接
- **提取码处理**：支持带提取码的分享链接，并尽可能自动识别
- **文件浏览**：解析后浏览分享内的文件和目录
- **直链获取**：获取可用于下载的文件地址

### 高速下载

- **普通 HTTP/HTTPS 直链多线程下载**
- 基于 **TurboDL** 下载引擎
- 支持多线程分片并发
- 支持断点续传
- 支持暂停 / 继续 / 删除 / 打开
- 支持动态并发调节
- 支持自适应分块
- 当前 TurboDL 单任务最高支持 **原生256 路并发本应用限制最高 64**
- 根据服务器实际能力调整有效并发，避免因为可用分片不足而创建大量无效线程
- 支持服务器不支持 Range 时回退到单流下载

### HLS 下载

- 通过 TurboDL 插件机制接入 **HLS 下载能力**
- 支持解析 HLS M3U8 播放列表
- 支持 HLS Segment 并发下载
- HLS 能力与核心下载引擎解耦，不依赖 YunGet 内部实现
- 后续可继续通过插件扩展其他流媒体/下载协议

> HLS 插件定位为**下载能力**，并非视频播放器，不负责实时播放。

### 账号与认证

- **登录**：夸克 / UC / 百度 / 139 使用 WebView Cookie；迅雷使用密码/短信；123 使用账号密码换取 JWT
- **认证备份**：使用用户口令派生密钥，以 AES-GCM 加密 Cookie/JWT 备份文件
- **剪贴板识别**：复制分享链接后回到应用，提示一键粘贴解析

### 其他

- **临时转存清理**
  - 百度 / 迅雷取链后清理
  - 夸克保留到下载完成或删除任务后清理
- **桌面图标切换**
  - 下载图标（默认）
  - 经典图标
  - 云 X 图标
- 主题与外观相关设置

## 下载引擎

应用里能见到四套下载实现，设置里可以随时换，重建任务后生效：

| 引擎 | 说明 |
|---|---|
| **TurboDL**（默认） | 独立发布的下载 SDK。普通直链、网盘直链、HLS 都走它 |
| 内置兼容引擎 | 项目早期的实现，固定较满的并发 + 4&nbsp;MB 分块 + 工作窃取；作为兜底 |
| aria2 | 实验性，用于对照排查；按需下载独立内核 APK，**仅 arm64 设备** |
| Gopeed | 磁力 / BT 这类内置分片器做不到的任务。内核不随 APK 分发，需在应用内导入 |

选的引擎跑不起来时报错，而不是悄悄换一个继续下 —— 换个下载器默默下完，比直接失败更难排查。
各自的校验方式、内核来源与边界见 [下载引擎](docs/ENGINES.md)。

## TurboDL 下载引擎（默认）

YunGet 当前正在将下载能力从应用内部实现逐步迁移至独立的 **[TurboDL](https://github.com/jiayuxuan123/TurboDL)**。

TurboDL 的设计目标是：

> **将下载核心从具体应用中分离出来，提供一个可复用、可扩展、插件化的通用下载 SDK。**

YunGet 与 TurboDL 的职责划分如下：

```text
YunGet
│
├── 网盘登录
├── 分享链接解析
├── 文件列表
├── 提取下载地址
└── 下载任务管理
        │
        ▼
     TurboDL
        │
        ├── HTTP/HTTPS 下载
        ├── 多线程分片
        ├── 并发调度
        ├── 断点续传
        ├── 重试
        ├── 失败回退
        └── 插件扩展
```

这种分离方式可以让 TurboDL 独立应用于其他 Android 项目或其他需要通用下载能力的项目，而 YunGet 本身则专注于网盘解析与用户侧功能。

### 当前 TurboDL 能力

- HTTP/HTTPS 普通直链下载
- 多线程 Range 分片下载
- 断点续传
- 并发调度
- 自适应分块
- 失败重试
- Range 不受支持时自动回退
- HLS 插件化下载
- 可扩展的插件机制
- 单任务最高 **256 路并发**

### 并发模型

TurboDL 中的并发参数表示**最大并发上限**，而不是强制要求任何情况下都必须产生同样数量的 HTTP 请求。

例如：

```text
最大并发：64
当前可用分片：5
实际并发：5
```

当任务拥有足够多的可调度分片时：

```text
最大并发：64
可调度分片：128
实际并发：64
```

因此实际并发会受到文件大小、分块策略、服务器能力以及当前调度状态等因素影响。

TurboDL 会尽可能利用用户设置的最大并发，同时避免为了“凑线程数”而产生大量无意义的小任务。

当前最高并发配置为：

```text
256
```

这代表 TurboDL 单任务允许的最大并发上限。实际运行并发仍由调度器根据任务与网络环境决定。

## TurboDL 插件化

TurboDL 的核心保持相对精简，其他下载协议和扩展能力通过插件机制接入。

当前已经接入：

```text
TurboDL Core
    │
    ├── HTTP/HTTPS 下载
    │
    ├── HLS Plugin
    │
    └── JS Plugin Loader ──→ 用户脚本（可声明任意协议）
```

插件可以独立维护、独立发布和独立接入，从而降低核心引擎与具体协议之间的耦合。

### 一个插件可以管多个协议

插件在清单里声明自己支持哪些协议，而不是"一个协议一个插件"：

```json
{
  "id": "dev.example.magnet",
  "protocols": ["magnet", "bt"]
}
```

引擎在加载时建立「协议 → 插件」索引，之后按协议查表。之所以不做成一个协议一个插件：插件数量一多，加载与切换的开销、以及插件之间的选择冲突都会跟着涨。

### 在应用里写插件

应用内置脚本编辑器，保存前会先做语法预检（写错了当场报，不会等下载时才发现脚本没加载），并附模板可照着改。也可以从**插件市场**添加插件源、浏览、一键安装与检查更新。

### 插件安全模型

安装与更新走**同一条校验链**，共六道：可验证性 → 最低宿主版本 → 体积 → 哈希与签名 → 脚本自报身份 → 清单一致性。两者共用同一段校验代码是有意的——如果更新比安装宽松，攻击者只要先让你装一个无害的旧版本，再用"更新"把恶意代码送进来。

**签名不等于安全。** 签名只证明"这个脚本来自它声称的作者"，不证明它做的事是安全的。信任分级看两项：来源身份，以及是否命中应用内置的公钥。

详见 [插件安全模型](docs/PLUGIN-SECURITY.md)。

插件开发规范请参考：

- [插件开发约定（中文）](https://github.com/jiayuxuan123/TurboDL/blob/main/docs/i18n/plugins/CONVENTION_zh-CN.md)
- [Plugin Convention](https://github.com/jiayuxuan123/TurboDL/blob/main/docs/plugins/CONVENTION.md)

## 使用

### 网盘下载

1. 在「网盘」页登录需要使用的网盘账号。
2. 在「解析」页粘贴分享链接（可带提取码）。
3. 浏览分享内容。
4. 点击需要下载的文件。
5. 获取下载直链后提交至下载任务。
6. 在「下载」页查看任务状态。
7. 支持暂停 / 继续 / 删除 / 打开。

### 普通直链下载

TurboDL 已用于 YunGet 的普通 HTTP/HTTPS 直链下载。

下载任务会交由 TurboDL 处理，包括：

- 分片
- 并发调度
- 断点续传
- 重试
- 进度统计
- 下载完成
- 失败回退

### HLS 下载

HLS 下载通过 TurboDL 插件提供。

基本流程：

```text
M3U8
 ↓
解析 Playlist
 ↓
获取 Segment
 ↓
提交 TurboDL
 ↓
并发下载 Segment
 ↓
完成后生成下载结果
```

HLS 下载功能主要面向**完整资源下载**，而不是实时播放。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Room（凭证与下载任务持久化，凭证字段加密存储）
- OkHttp（网络请求与 HTTP 下载）
- TurboDL（通用下载引擎 SDK，含 HLS 插件）
- Gopeed（可选内核，用于磁力 / BT 这类任务；不随 APK 分发，需在应用内导入）
- aria2（独立可选内核 APK，实验性，仅支持 arm64-v8a）
- KSP

## 安装包与缓存

Release 同时提供三个按架构拆分的包和一个通用包：

| 包 | 适用设备 | 当前约体积 |
| --- | --- | --- |
| `app-arm64-v8a-release.apk` | 绝大多数现代 Android 手机 | 约 26.8 MB |
| `app-armeabi-v7a-release.apk` | 32 位 ARM 老设备 | 约 26.7 MB |
| `app-x86_64-release.apk` | 64 位模拟器/测试设备 | 约 26.8 MB |
| `0-YunGet-2.7.27-universal.apk`（构建产物 `app-universal-release.apk`） | 通用包，也是旧版更新器的过渡包 | 约 26.2 MB |

aria2 已拆为按需下载安装的独立 ARM64 内核 APK，Gopeed 内核也按需获取，主包不再内置这两个内核。

**过渡版本规则：**2.7.22 及更早的更新器只会取 Release 中第一个 `.apk`，不认识 ABI。
目前 GitHub API 返回的首个 APK 是 `0-YunGet-2.7.27-universal.apk`；发布时必须实际检查 API 返回顺序，不能只依赖上传顺序或文件名。旧版用户先升级到 2.7.23；
2.7.23 起更新器会按 `Build.SUPPORTED_ABIS` 选择精确 ABI，找不到才使用兼容 32 位包或 universal。
后续 Release 也应保留 universal 包，不能只上传三个分架构包。

**下载缓存不等于 APK 体积。** 暂停/失败任务的可续传文件、临时下载文件和插件数据位于应用私有目录，
系统“应用存储”里看到的数据增长不会因为 ABI 分包而消失；这些目录需要单独的上限、清理和空间提示策略。

## 构建

要求：

- JDK 17+
- Android SDK（`local.properties` 里的 `sdk.dir`，或环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT`）
- `compileSdk 36` / `minSdk 24` / `targetSdk 34`

**获取 TurboDL SDK。** 依赖里的 `dev.turbodl:*:0.5.0.1` 从 TurboDL Release 的离线包或本地 Maven 解析：

```kotlin
// settings.gradle.kts 的 dependencyResolutionManagement
repositories {
    mavenLocal() // 先导入 TurboDL Release 的 mavenLocal 离线包
    google()
    mavenCentral()
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("dev.turbodl:turbodl-core:0.5.0.1")
}
```

从 TurboDL 的 Release 页取 mavenLocal 离线包，
解压到 `~/.m2/repository`（本地开发时 `settings.gradle.kts` 里的 `mavenLocal()` 会优先命中）。

> `mavenLocal()` 排在仓库列表**最前**是有意的：本地开发时优先用它，
> 避免每次构建都去远端拉一个还在改的版本。但这也意味着**本机 `~/.m2` 里的旧版本
> 会遮住远端的新版本** —— 若改了引擎版本却构建报"解析不到 0.2.x.x"，
> 先确认本地 Maven 里有没有那个坐标。

正式签名需要仓库根目录的 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` /
`keyPassword` 四个键）；没有它也能构建，会退回调试签名 —— 能装能跑，但不能用来发布。

```bash
git clone https://github.com/jiayuxuan123/YunGet.git
cd YunGet

# 配置 local.properties 指向 Android SDK
# sdk.dir=/path/to/Android/sdk

./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

也可以使用 Android Studio 打开项目直接构建。

跑单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

10 个测试文件、68 个用例，全部是纯 JVM 测试（不依赖 Android 运行时，不需要模拟器）；
仓库里没有 `androidTest` 目录。覆盖版本号比较、分享链接解析、迅雷口令、数据库迁移契约、
加密凭证契约、下载引擎枚举与进度落盘节流等。

国内网络环境可根据实际情况将 Gradle 与依赖仓库配置为可访问的镜像（仓库里已配好阿里云与腾讯云镜像，
一般不用改），相关配置位于：

```text
gradle/wrapper/gradle-wrapper.properties
settings.gradle.kts
```

更详细的说明见 [从源码构建](docs/BUILD.md)。

## 关于 TurboDL

TurboDL 是本项目的主要下载引擎。

项目地址：

[https://github.com/jiayuxuan123/TurboDL](https://github.com/jiayuxuan123/TurboDL)

TurboDL 与 YunGet 的职责划分如下：

```text
┌─────────────────────────────────────┐
│               YunGet                │
│                                     │
│  网盘登录 / 分享解析 / 文件浏览      │
│  提取下载地址 / 下载任务管理          │
└──────────────────┬──────────────────┘
                   │
                   │ 下载任务
                   ▼
┌─────────────────────────────────────┐
│              TurboDL                │
│                                     │
│  HTTP/HTTPS                         │
│  多线程分片                          │
│  并发调度                            │
│  断点续传                            │
│  重试                                │
│  失败回退                            │
│  HLS 插件                            │
│  其他插件                            │
└─────────────────────────────────────┘
```

这种架构可以避免将完整下载逻辑直接耦合到 YunGet 中。

TurboDL 可以独立用于其他项目，而 YunGet 只需要将获取到的下载地址交给 TurboDL 即可。

## 关于协议逆向

部分网盘平台的解析基于抓包分析与开源项目（如 alist）的协议研究整理，接口可能随官方调整而失效，请以实际运行结果为准。

本项目沿用上游 YunX 的协议实现思路，并在此基础上进行二次开发。

## 免责声明

本项目仅供个人学习与技术交流，请勿用于商业用途。

下载内容的版权归原作者及相关权利人所有，请遵守当地法律法规以及相关平台的服务条款。

使用本项目下载内容时，请确保自己拥有相应的使用权或下载权限。

使用本项目产生的任何后果由使用者自行承担。

## 开源协议

本项目基于 **GNU AGPL-3.0** 协议开源，详见根目录 [LICENSE](LICENSE)。

作为 [CYQawa/YunX](https://github.com/CYQawa/YunX)（同为 AGPL-3.0）的二次开发版本，本项目在分发时同步公开全部源码，履行 AGPL-3.0 的开源义务。

原项目版权归其原作者所有。

## 致谢

感谢：

- [CYQawa/YunX](https://github.com/CYQawa/YunX)
- [TurboDL](https://github.com/jiayuxuan123/TurboDL)
- 以及所有参与测试、反馈问题和改进项目的用户。

<details>
<summary>开发路线图：TurboDL 工业级建设与 YunGet 移动端适配</summary>

**2026-11-04 执行入口更新：**完整任务卡、参数契约、网络与竞品验收、低成本模型交接现集中维护在
TurboDL 仓库的 `engineering/industrial/README.md`。
本地下一个执行者先读 `../TurboDL/engineering/industrial/HANDOFF.md`，以及执行板中的
8 GiB 公网预算和风险登记。下文保留 2026-11-03
历史分析及执行记录；其中已修复前的“观察”不能当作当前缺陷，完成状态以新台账的实际证据为准。
规划后的第一轮引擎修复和实测已记录在 TurboDL 的
`engineering/industrial/evidence/2026-11-04-engine-pass1.md`；这只是阶段证据，不代表全部路线图完成。

## 路线图的使用方法

这是可逐项执行的工程计划，基于 2026-11-03 的本地源码梳理；不是已实现功能清单。
`TurboDL/` 与 `YunGet/` 是两个独立仓库。以下路径分别相对对应仓库根目录。
每项的“观察”是已看到的源码事实；“风险”是仍需用测试证实的推断。
未列出的运行结果一律视为未验证，不沿用历史注释里的性能数字作为当前结论。

执行时按任务编号和依赖顺序推进：先复用列出的测试，再为具体缺口增加能复现问题的测试，
完成实现和验收后记录版本、命令、结果及未覆盖项。任务不可因为代码已有同名功能就标完成。
同一轮只拿相邻且相关的小批次；允许经用户指定模型的子智能体处理互不重叠的材料。

### 不可偏离的产品边界

- TurboDL 是通用 SDK：提供协议、调度、状态、故障信息和策略接口，不识别或硬编码云取及具体网盘。
- YunGet 负责网盘账号、解析、直链更新、平台配置、Android 后台与界面。账号稳定靠可配置限流和尊重服务端反馈，不能保证永不触发风控。
- 保留自适应；应用可通过 SDK 控制初始值、上限、退避和恢复。不能通过全局关闭自适应掩盖启动慢或吞吐问题。
- Windows 端暂停开发和发布；以后先设计与 Android 共享代码的方案，不再单独维护另一套客户端。
- 测试使用临时目录、测试数据及可公开或获授权的链接，不读取、导出账号目录或凭据；进程只按确认的 PID 结束。
- 修改、构建和本地验证是工作的一部分；发布、覆盖或删除线上产物依工作区规则先取得明确授权。

## 0. 固定基线与对标口径

**当前可见基线：**TurboDL HEAD 为 `1cea73c`，`build.gradle.kts` 版本为 `0.4.0.0`；
YunGet 当前工作版本为 `2.7.23`、`minSdk=24`、`targetSdk=34`；各 TurboDL 模块已切换到 `0.4.1.0`。
这只是本次静态快照；执行任何任务前先看 `git status --short` 和各自 HEAD，不能覆盖已有修改。

**对标范围：**逐项与 [aria2](https://aria2.github.io/manual/en/html/aria2c.html)、
[Gopeed](https://github.com/GopeedLab/gopeed) 和 [XDM](https://github.com/subhra74/xdm) 的具体版本比较。
aria2 官方文档列出 HTTP(S)、FTP、SFTP、BitTorrent、Metalink 和多来源；Gopeed 项目列出
HTTP、BT、Magnet 和 ed2k。协议能力、长期稳定性、资源成本、API 与功能都要进入矩阵，
不能只看一项下载速度。尚未实现或尚未验证的协议保持缺口，不能写成“工业级已达成”。

**完成定义：**每个任务要有源码改动、针对性自动化或真实设备证据、失败和回滚方式；
所有关键矩阵行完成且经集成回归后，才能评价整套方案达到目标。网络加速的 `1.3x`
是服务端支持分段且网络有余量时的可复现目标，不是所有来源都能保证的结果。

**覆盖检查表：**下表是要求全部推进的范围，并非声称当前能力不存在或已达标。
具体基准版本、结果和状态在 P0 测试时补齐；任何一行未完成都不能给整体对标画句号。

| 能力 | 执行任务 | 本规划时的状态 |
| --- | --- | --- |
| HTTP(S)、范围请求、文件正确性 | TD-01、TD-02 | 代码已存在，边界待验证 |
| 断点、数据持久化、崩溃恢复 | TD-02、TD-03、YG-02 | 代码已存在，安全性待验证 |
| 身份校验、摘要、多个来源 | TD-03、TD-04 | 部分代码已存在，可靠性待验证 |
| 任务生命周期、排队、公平和优先级 | TD-05、TD-10、TD-16 | 部分代码已存在，能力与兼容性待验证 |
| 通用 SDK、策略、兼容与事件 | TD-05、TD-06、TD-08、TD-11 | 已有公开接口，完整契约待验证 |
| 吞吐、延迟、自适应、尾部速度 | TD-07、TD-08、TD-09 | 现有基准不足以证明当前目标 |
| 网络协议、代理、DNS、TLS | TD-11 | 已有选项，稳定性和来源边界待验证 |
| 内存、磁盘、连接、任务资源 | TD-09、TD-10 | 已有预算机制，极限与恢复待验证 |
| HLS 和媒体处理 | TD-12 | 静态 VOD 有实现，live/fMP4 等有明确限制 |
| 插件、脚本与供应链 | TD-13、REL-02 | 已有模块，隔离和分发待验证 |
| FTP/SFTP、BT/Magnet、Metalink、ed2k | TD-14 | TurboDL 协议能力未全部实现，必须逐项完成或列明阻塞 |
| CLI、错误、日志、诊断 | TD-15 | 已有基础，统一语义和脱敏待验证 |
| Android 接入、界面、后台与存储 | YG-01 至 YG-07 | 已有移动应用，具体问题和体验待验证 |
| 安装、升级、回退、交付 | TD-06、REL-01、REL-02 | 项目已有构建入口，兼容与产物待验证 |

## 1. 第一批：数据安全、续传与 SDK 兼容

### TD-01：分片响应与 416 处理

- **观察：**`turbodl-core/.../SegmentDownloader.kt` 的 `downloadSegmentInto` 和旧
  `downloadSegment` 遇到 416 返回 `SegmentResult.OK`；`SegmentScheduler.run` 在结束时仍会检查未完成分片。
  因此最终不一定误报成功，但未完成分片可能失去正常重试机会，需先复现。
- **实施：**在两条写入路径中，对尚未完成的 416 返回可归因的失败并走有界重试；
  收到响应时若尾部分割已使该片真实完成，重新读取末端并允许完成。保留 429/503 与授权过期各自的处理。
  同时验证 206 的 `Content-Range` 起止、总长和实际字节数，拒绝错位或无法证明范围正确的数据。
- **测试：**扩展 `AuditFixesRegressionTest`、`RegressionFixesTest`；构造一次 416 后恢复、
  持续 416、请求期间尾部分割、缺失/错误范围及短读。验证重试次数、结果、写入位置和最终 SHA-256。
- **完成：**可恢复的单次错误能恢复；持续错误有明确失败；未收到的字节不能标记完成。

### TD-02：动态切片与续传侧车一致性

- **观察：**`SegmentScheduler.tryTailAssist` 和 `ensureSegmentSupply` 将新分片追加到
  `segmentList`；`persistProgress` 把该列表交给 `SegmentStore.persist`；后者的
  `readStateFile` 要求记录按起点连续排序。动态分裂后的顺序与读取约束可能冲突，需复现。
- **实施：**在一致性锁或等效快照下生成不可变分片快照，按起点排序并验证完整覆盖、
  无重叠和写入量边界；再写入临时侧车并替换。检查数据落盘先于进度承诺，防止断电后侧车超前。
  合并检查点批量同步，避免每个小块都强制刷盘造成吞吐回退。保持旧侧车格式可读；
  若必须升级格式，写清旧版读取、备份及失败恢复策略。
- **测试：**在 `SegmentStoreTest`、`TailAssistTest`、`ResumeAfterPauseTest` 中覆盖
  多片中间分裂、并发写入快照、暂停/杀进程/重启、主侧车截断而 `.bak` 完好、空洞和重叠。
  用不同偏移的非周期数据与 SHA-256 判定，不只比较文件长度。
- **完成：**合法分裂后的记录可续传；损坏记录不被当成完整文件；已有数据不因一次读失败被静默删除。

### TD-03：续传身份与保留策略

- **观察：**`BuiltinHttpBackend.prepareResume` 会结合大小、校验器和
  `verifyResumeFingerprint` 的局部样本作判断。相同长度、相同样本并不能证明整个文件相同；
  校验器变化也可能来自 CDN 节点。现有取舍需要明确告知调用方。
- **实施：**把“已验证相同 / 已验证不同 / 无法判断”保持为不同结果。强校验器与预期摘要可用时
  优先使用；无法证明一致时保留旧字节，通过通用策略让宿主选择等待验证或明确接受弱校验风险，
  不把局部指纹包装成全文件证明。针对已部署应用制定兼容默认值和升级提示，不能悄悄令常用网盘全部无法续传。
  禁止把“暂时无法探测”当成删除旧数据的理由。
- **测试：**扩展 `ResumeIntegrityTest`、`ResumeProbeTest`、`ProbeFailureKeepsPartsTest`：
  同大小且样本相同但后半段不同、强校验器变化、临时 403、失网、旧数据缺失与恢复后摘要错误。
- **完成：**每一种结果可由宿主识别，风险策略有文档，无法确认时用户的旧数据仍可恢复。

### TD-04：镜像一致性、隔离与重定向

- **观察：**`BuiltinHttpBackend.downloadWithStore` 将主地址和镜像放进 `MirrorPool`，
  共用 `taskHeaders`；`SegmentDownloader` 按任务缓存 `resolvedUrlByTask`；目前 206 的总长检查
  只在能解析出总长时比较。相同大小不是相同内容的证明，跨源凭据传播和缓存串源需测试。
- **实施：**为每个来源独立记录原地址、解析终址、来源身份及退避；凭据按来源和重定向策略作用，
  默认不能无意把 Cookie/Authorization 发送给另一个来源。没有可信的共同内容证明时，
  采用安全的来源策略或由调用方显式承担风险；最终摘要存在时必须校验。镜像失败不能污染其他来源。
- **测试：**扩展 `MirrorPoolTest`、`RedirectReuseTest`、`ChecksumTest`：相同大小不同内容、
  不同大小、缺总长、主源和镜像同时重定向到不同地址、跨端口接收请求头、源失效后恢复。
- **完成：**混源结果要么验证正确要么明确失败；敏感头不会越过约定边界；恢复不循环撞错误终址。

### TD-05：状态机、事件与取消竞态

- **观察：**`TurboClient.submit` 在返回 ID 前发出创建/排队事件并启动协程；`resume` 内部有
  `runBlocking` 等待清理；`TurboEvent` 是 `SharedFlow`，`progress` 是 `StateFlow`。
- **实施：**为 `submit/await/pause/resume/cancel/shutdown` 约定幂等性、终态及事件顺序；
  设计不阻塞 UI 线程的异步恢复入口，并保留旧公开 API 的兼容路径。区分可恢复失败、用户取消和完成。
  重要终态需要可查询，不能只依赖可能被错过的瞬时事件。
- **测试：**扩展 `TurboClientTest`、`ShutdownDrainContractTest`、`TaskSlotFairnessTest`：
  秒级小任务、连续暂停/恢复/删除、排队时取消、恢复时关闭、同时提交和并发任务槽回收。
- **完成：**每次尝试恰有一个终态；没有永久等待、重复执行或主线程长阻塞。

### TD-06：0.3.1 到当前 SDK 的真实兼容

- **观察：**`Models.kt` 的 `DownloadRequest` 在 `0.3.1.0` 后于 `connectionsOverride` 与
  `stableKey` 之间新增了 `mirrors` 构造参数；YunGet 的六个 SDK 模块仍依赖 `0.3.1.0`，
  `settings.gradle.kts` 优先使用 `mavenLocal()`。源码默认参数不等于 JVM 二进制兼容。
- **实施：**固定两版本真实构建产物，比较公开类、构造器、`copy/componentN`、默认参数、
  插件版本握手及消费者代码。对破坏性差异提供兼容构造入口或明确版本迁移，不能仅调整字段顺序就宣称兼容。
  应用升级时统一 core、runtime、bootstrap、HLS、parser、JS 版本，并记录实际解析的文件及哈希。
- **测试：**旧版编译的 Kotlin/Java 消费程序在新版运行；新版重新编译；插件加载/禁用；
  Android 依赖解析使用 `./gradlew.bat :app:dependencyInsight --dependency turbodl-core --configuration debugRuntimeClasspath`。
- **完成：**有可复现的兼容报告和升级/回退步骤；本地 Maven 缺包或旧包不会被误认为新版本。

## 2. 第二批：速度、自适应和资源上限

### TD-07：建立可信对比台

- **观察：**`_bench/run_concurrency_matrix.py` 已支持隔离 JVM、固定/自适应模式和连接档位；
  当前 `ok` 判断是进程退出码加文件大小，不能仅凭它证明内容正确。
- **实施：**增加基准清单和机器可读结果：版本、时间、链接身份、代理、文件大小和预期 SHA-256、
  并发配置、实际活动连接、首字节、总耗时、吞吐、错误重试、CPU 和内存；每次计算完整摘要并验对。
  为 aria2、Gopeed 和普通单流加入同条件调用，区分 H1/H2、冷暖连接、磁盘和网络瓶颈。
- **测试：**本地 Range 服务器先覆盖无限速、每连接限速、总量限速、连接速度不均和 429；
  再用获授权公网资源对照。档位 1/4/8/16/32/64/128/256，覆盖 31.6 MB 附近小文件及更大文件；
  样本顺序交错、每档至少 3 次，保留原始值、中位数和波动，噪声过大时增加次数。
  当前 Node 服务器的生成数据具有重复周期，范围错位测试要另用有明确种子的非周期文件和独立摘要。
- **完成：**同文件同条件可重复比较；任何摘要不符或输出不全的样本直接失败，不能参与提速结论。

### TD-08：自适应与慢启动协作

- **观察：**`AdaptiveConcurrencyController` 有 `UPWARD/DOWNWARD`；`SegmentScheduler` 中
  `rampUpIfHealthy`、`observeThroughput`、`throttleDown`、`recoverCeilingIfDue` 共同影响目标；
  云取 `TurboDownloadManager.buildConfig` 显式设置 `DOWNWARD`，慢启动由设置提供。
- **实施：**定义配置上限、起始目标、实际连接、背压上限、已接受档位五个不同概念；
  将正常爬升、吞吐探索、限流降档、冷却恢复的优先级写成状态转移规则。宿主通过通用策略配置
  选择快起步或保守起步，不能引擎内判断网盘。31.6 MB 文件不能因互相等待一直停在低档。
- **测试：**扩展 `AdaptiveConcurrencyControllerTest`、`AdaptiveConcurrencyTest`、
  `ConcurrencySaturationTest`、`SlowStartWarmUpTest`、`BackpressureRecoveryTest`：
  先满速后收敛、保守起步、限流降档/恢复、多任务争抢、活跃任务中改上限、下载将结束时禁止无意义探测。
- **完成：**每连接限速且服务端允许时提升连接确实提升总吞吐；总量封顶时可收敛；
  429/503 按 Retry-After 和冷却策略响应；无持续卡低档或周期性掉速。

### TD-09：分片调度、尾速与写盘

- **实施入口：**`SegmentScheduler.run/tryTailAssist/ensureSegmentSupply`、
  `SegmentStore.writeAt/writeBufferAt/ensureLength`、`TurboConfig` 的块大小和缓冲预算。
  先分别计量建连、等待、写盘、进度回调和尾段耗时；只改证明存在的瓶颈。
  限制分片数及每任务内存，不让 256 的配置直接产生无限小分片或大数组；允许慢分片被安全接管。
- **测试：**`TailAssistTest`、`SegmentShrinkSafetyTest`、`WorkStealingValueTest`、
  `IoBufferBudgetTest`、`DirectWriteContractTest`；加快慢连接、磁盘慢、短文件、高并发及 SHA 校验。
- **完成：**资源曲线和尾部耗时可解释；吞吐改进未以损坏、丢进度或不受控内存为代价。

### TD-10：全局资源与公平性

- **实施入口：**`ConnectionBudget.acquire/release/limitChanged`、`TurboClient.awaitSlot/releaseSlot`、
  `HttpClientFactory`、`SpeedLimiter`。统一任务槽、连接槽、每主机上限、全局限速和取消时归还；
  检查连接池、DNS 执行器、线程、文件句柄和缓冲随任务数的上界。
- **测试：**`ConnectionBudgetTest`、`GlobalConnectionBudgetIntegrationTest`、
  `TaskSlotFairnessTest`、`IoDispatcherCeilingTest`，加 100/1000 个短任务、等待者取消、动态改预算、
  慢任务和快任务共存、关机清理。记录峰值及结束后的回落值。
- **完成：**预算不超发、不饥饿、取消不泄漏许可，长时间运行资源不随完成任务数无界增长。

### TD-11：传输、代理、重定向与诊断

- **实施入口：**`HttpClientFactory`、`SegmentDownloader.probe/probeWithRetry/warmUp`、
  `TurboDiagnostics`、`TurboClient.runTask`。梳理代理、DNS、TLS、H1/H2、超时和预热；
  校正 `runTask` 的 `Metadata` 目前用总大小推测 `supportsRange`、并使用原请求 URL 填 `resolvedUrl` 的语义，
  由真实探测结果向后兼容地传递。既有 HLS `HlsRequestPolicy` 的同源判断可供 HTTP 侧设计参考。
- **测试：**`ProbeMetadataTest`、`RedirectPathTest`、`TransportConfigHotSwapTest`、
  `HttpVersionPolicyTest`、`GzipIdentityTest`，覆盖跨源重定向、证书失败、代理切换、探测失败和地址过期。
- **完成：**事件与真实协议结果一致；请求头遵循来源边界；任何诊断输出默认不暴露签名 URL 或凭据。

## 3. 第三批：功能覆盖、媒体和插件生态

### TD-12：HLS 实际边界与扩展

- **观察：**`turbo-plugin-hls/.../HlsManifest.kt` 和 `HlsBackend.kt` 已有静态播放列表、
  BYTERANGE 和 AES-128 相关代码；代码明确拒绝未结束 live/event、fMP4 `EXT-X-MAP`、
  discontinuity 与 DRM/SAMPLE-AES。不能把 HLS 写成全部支持。
- **实施：**先完善当前 VOD 的主列表选择、分片恢复、重试、授权及密钥隔离；再按独立里程碑
  设计 live/event、fMP4 初始化段、分段时间线及音视频封装所需媒体管线。遇到不支持的加密或
  容器必须返回明确错误；不承诺绕过 DRM。
- **测试：**`HlsManifestParserTest`、`HlsBackendTest`、`HlsRequestPolicyTest` 加本地可控样例，
  验证分片顺序、密钥/IV、跨源头、恢复和播放器可读性。
- **完成：**每种受支持格式都有样例和完整结果校验，未实现格式不会静默产出坏文件。

### TD-13：插件生命周期与隔离

- **实施入口：**`turbo-plugin-runtime/.../PluginHost.kt`、`ServiceRegistry.kt`、`Disposer.kt`、
  `ext/BackendRegistry.kt`、`turbo-plugin-bootstrap/.../TurboBootstrap.kt`，以及 JS 模块的
  `JsRuntime`、`JsLifecycle`、`JsHostApi`、`JsSecretStore`。
  明确加载、版本握手、权限、注册、任务运行、卸载、失败清理；插件不可越权读取其他插件秘密或任意文件。
- **测试：**`PluginFailureBehaviourTest`、`ProtocolRegistryTest`、`JsIsolationTest`、
  `JsSecretScopeTest`、`JsLifecycleTest`；覆盖运行中卸载、恶意大输入、异常/超时、重复加载和宿主关闭。
- **完成：**插件失败不拖死核心，权限边界及版本要求有可执行验证，卸载释放资源。

### TD-14：FTP/SFTP、BT/Magnet、ed2k、Metalink 的完整对标路径

- **现状：**TurboDL 核心的 `BuiltinHttpBackend.supports` 识别 HTTP(S)；应用还有 Gopeed 和 aria2 等可选引擎。
  不能把其他引擎已有的协议直接算成 TurboDL SDK 已支持。
- **实施：**以 `DownloadBackend`、runtime `ProtocolRegistry/BackendRegistry` 为扩展边界：
  先定义 URI 路由、任务进度、暂停、恢复、错误、资源预算、凭据与取消的一致契约；
  再分别交付 FTP/SFTP、BT/Magnet、Metalink 和 ed2k 的可选后端或有文档的成熟实现适配。
  对 Android ABI、依赖许可证、原生进程/库隔离、文件路径和回退逐项验收；不能先删除现有 Gopeed/aria2 兜底。
- **测试：**各协议用合法测试资源和已知哈希，验证新增、暂停、重启、资源限制与异常；BT 验证元数据、
  选文件、分片校验和可配置分享策略；Metalink 验证多来源及分片摘要。逐协议列出与对标版本的差距。
- **完成：**协议有可用实现、自动化和 Android 可分发方案，或明确记录阻塞与待实现项；
  在这些缺口关闭前不得宣称已全面达到多协议对标目标。

### TD-15：CLI、可观测性与文档

- **实施入口：**`turbodl-cli/.../Main.kt`、`TurboDiagnostics.kt`、SDK `Models.kt`；CLI 参数、
  退出码和 JSON 事件与 SDK 状态保持一致。新增可检索错误分类与可选的结构化诊断，
  隐藏敏感 URL 参数、路径、Cookie、授权头及插件秘密。
- **测试：**正常/暂停/404/429/超时/校验失败的退出码和 JSON；对诊断文本做敏感值脱敏测试。
- **完成：**CLI 与宿主拿到同一语义，排障信息有用且不会默认泄密。

### TD-16：队列、优先级与任务管理能力

- **观察：**`TurboClient` 有任务并发槽及 FIFO 等待队列，`DownloadRequest` 提供任务参数与
  `tags`；YunGet 的 `DownloadManager` 定义入队、开始、暂停和删除。现有优先级、调度持久化及批量
  管理能力需与竞品逐项比较，不能仅凭 FIFO 队列宣称等价。
- **实施：**在现有任务 ID 和状态机上设计队列查询、批量操作、优先级调整、任务去重与定时策略；
  SDK 提供通用的队列和策略能力，时间表及 Android 持久化由宿主选择。先保证旧调用者行为，
  再增加有界队列和可取消等待；不能让低优先级任务永久饥饿或越过并发预算。
- **测试：**排队时暂停/取消、优先级调整、相同 URL 不同目标、重复提交、任务失败与重启恢复，
  分别验证任务槽和连接槽、调用顺序和 Room 数据。对照 aria2 和 Gopeed 的对应行为记录差异。
- **完成：**队列决策可观察、可取消且可重启恢复，批量操作不会造成资源泄漏或错误任务重启。

## 4. YunGet：移动端体验及引擎适配

### YG-01：任务映射与事件不丢失

- **观察：**`TurboDownloadManager.start` 中 `client.submit(request)` 返回后才填
  `turboIds` 和 `turboIdToRoomId`；`onTurboEvent` 找不到映射会直接返回。
  极短任务及异步 DB 更新可能出现事件先于映射、旧回调覆盖新状态或重复提交的竞态，需测试。
- **实施：**以 Room 任务 ID 加尝试代次建立单任务串行状态机；先注册可关联的稳定标识再启动，
  或订阅/查询可恢复快照补齐早到事件；对每次尝试只接受当前代次结果。按 SDK 契约实现幂等暂停、恢复、删除。
- **测试：**给 `TurboDownloadManager` 注入可控假 SDK/DAO：同步完成、失败立即回调、连续双击、
  暂停后立刻恢复、删除后迟到完成、多个任务乱序、应用重建。
- **完成：**无重复下载、永远“下载中”或已删除任务复活；Room、通知和 UI 最终状态一致。

### YG-02：失败后保留可续传文件

- **观察：**`onTurboEvent` 的 `TurboEvent.Failed` 分支执行 `turboOutputs.remove(roomId)?.delete()`；
  `start` 把输出放在 `task_<id>.part` 并用 `stableKey = "room-<id>"`。直接写入模式下，
  输出文件也是续传数据；失败删除它可能让恢复从零开始，端到端结果待复现。
- **实施：**按错误分类处理临时文件：普通网络失败和链接失效保留可恢复输出与侧车；
  只有用户确认删除或内容被证明不可信时走明确清理；失败提示提供重试/刷新链接。
  成功保存后才能清理缓存；保存失败保留源文件，避免重复下载。
- **测试：**下载部分数据后 403、断网、磁盘满、保存权限失效、校验失败、用户删除；
  断言缓存/侧车、Room 状态、恢复起点及最终哈希。不要用用户真实文件测试。
- **完成：**暂时失败可续，明确删除才清理，保存失败不会丢掉已下完的数据。

### YG-03：真实状态、线程数和交互反馈

- **观察：**`onTurboEvent` 把 `p.activeConnections.coerceAtLeast(1)` 写入 `DownloadStats.chunkCount`；
  `DownloadScreen.DownloadTaskCard` 显示该值为“线程”；`DownloadViewModel` 直接转发操作。
  0 个活动连接会被展示为 1，配置值与实际值也需要区分。
- **实施：**`DownloadManager/DownloadStats` 传递真实活动连接及上限，未知状态显示“--”，不伪造 1。
  `DownloadScreen` 展示等待、连接、下载、退避、校验、保存、暂停、失败等用户可理解的阶段；
  `DownloadViewModel` 为点击提供立即的进行中反馈，待 SDK/DB 确认后提交状态，失败时回滚并提示。
  禁止重复点击引发重复操作；批量操作按每个任务独立反馈。
- **测试：**0/未知/高并发、等待槽、限流冷却、事件迟到、命令失败、列表快速滚动与多任务。
  用 Compose 测试或设备操作验证按下后首帧反馈、触控面积、无障碍标签及系统动画关闭。
- **完成：**界面数字与引擎一致，按钮即时有反馈且不虚报操作成功。

### YG-04：设置与平台策略下发

- **观察：**`SettingsRepository` 当前上限 256，`downloadThreadsFor` 返回全局值；
  `TurboDownloadManager.buildConfig/refreshConfigIfChanged` 将设置映射到引擎，
  `EngineAdvancedScreen` 中部分文字把协议和背压效果写得过于绝对。
- **实施：**保留用户连接上限，不自动将它等同于实际连接；明确自适应、慢启动、背压、
  全局任务/连接预算及 H1/H2 的关系。允许应用按来源选择安全配置或用户自定义，配置只从应用下发。
  未验证的设置即时生效行为标明“新任务生效”；纠正“HTTP/2 必然更慢”等绝对化文案。
- **测试：**`EngineAdvancedConfigTest`、`ThreadCountAdviceTest` 加设置边界、重启持久化、
  当前任务与新任务、快起步/限流/恢复、多个来源并行及 SDK 更新后的参数映射。
- **完成：**用户配置有明确含义且可观察；默认保持自适应，服务端压力受宿主策略约束。

### YG-05：后台、存储和网络变化

- **实施入口：**`DownloadService`、`DownloadSaver`、`DownloadPathPolicy`、
  `TurboDownloadManager.recoverInterruptedTasks/onTurboCompleted`、`DownloadTaskDao`。
  建立前台服务与活动任务的一对一引用关系；进程重启后恢复明确状态；SAF/MediaStore 保存采用
  可恢复的临时结果和提交步骤；网络切换或省电策略引发的暂停要显示原因。
- **测试：**Android 7+ 的代表设备/API、通知权限拒绝、后台限制、Wi-Fi/移动网络切换、
  进程杀死、SAF 权限撤销、空间不足、同名文件、保存中断和并发任务结束。
- **完成：**真实设备上通知、任务、文件状态一致，不覆盖原文件，不把保存失败伪装成下载成功。

### YG-06：隐私与数据迁移

- **观察：**`TurboDownloadManager.enqueue` 会记录完整 URL，并将 `headers` JSON 写入
  `DownloadTaskEntity.requestHeadersJson`；这些字段可能含签名链接或 Cookie。此处只查看源码，不读取用户数据库。
- **实施：**统一 URL/头脱敏；任务续传所需秘密迁移到 Android Keystore 保护的存储或等效方案，
  Room 仅保存可恢复引用。旧数据迁移必须兼容中断、密钥不可用、旧版回退和用户现有任务；
  不为迁移直接删除原有任务或凭据。核对诊断日志、崩溃日志和导出流程。
- **测试：**用虚构 Cookie、授权头、带签名 URL 的临时测试库，检查日志/导出不包含秘密；
  验证旧记录迁移、恢复、密钥失效的明确提示和回滚。
- **完成：**用户现有任务可安全迁移或被明确保留；默认日志和导出不泄露测试秘密。

### YG-07：适度视觉升级

- **实施入口：**`ui/screens/DownloadScreen.kt`、`ResolveScreen.kt`、`SettingsScreen.kt`、
  `DownloadEngineScreen.kt`、`EngineAdvancedScreen.kt` 及 `ui/components`、`ui/theme`。
  在现有移动端基础上向 Google Material 3 的统一观感升级，先盘点并修复现有小 bug，
  再统一任务卡、空状态、操作反馈、状态颜色、动效和设置说明；合并重复组件而不重做产品信息结构。
  视觉升级必须保留已有功能和信息层级，不能为了“换皮”变得更难用或更丑。
  大字体、长文件名、深浅主题和窄屏必须可读。
- **测试：**关键页面的正常、加载、空、失败、多任务状态，触控目标至少 48dp；
  检查 TalkBack 标签、系统减少动画、横竖屏及真实设备掉帧。
- **完成：**视觉和交互一致、动作及时、信息可读；不引入新的 PC 客户端。

### YG-08：ABI 分包与更新器兼容

- **实施入口：**`app/build.gradle.kts`、`AndroidManifest.xml`、`data/update/UpdateChecker.kt`、
  `data/update/UpdateVersion.kt` 和发布脚本。
- **实施：**生成 arm64-v8a、armeabi-v7a、x86_64 与 universal 四种 Release APK；
  更新器按设备 ABI 优先级选择精确包，缺失时回退到兼容包或 universal。
  每次仍保留 universal 包，兼容只识别 Release 中第一个 APK 的旧版更新器。
- **测试：**验证四种 APK 的包名、`versionCode`、`versionName` 和可安装性；
  更新器覆盖精确匹配、arm64 回退、x86 回退、未知 ABI 与 universal 场景。
- **完成：**新版使用分架构包减小安装体积，旧版可经 universal 桥升级；
  发布清单不会因缺少 universal 或选错 ABI 而中断更新。

## 5. 跨项目交付与验证

### REL-01：构建、测试和真实依赖

1. 在 TurboDL 仓库：`./gradlew.bat :turbodl-core:test --no-daemon`；对应插件各自运行
   `:turbo-plugin-runtime:test`、`:turbo-plugin-hls:test`、`:turbo-plugin-js:test`、
   `:turbo-plugin-parser:test`、`:turbo-plugin-bootstrap:test`，再运行 `:turbodl-cli:fatJar`。
2. 在 YunGet 仓库：`./gradlew.bat :app:testDebugUnitTest --no-daemon`，按需要执行
   `:app:assembleDebug`；涉及发布候选时再运行项目已有的 APK 版本验证任务。
3. 先运行对应任务的单类测试；影响生命周期、存储、公开 API 或发布时再跑完整相关套件。
   失败保留报告和原因；不通过降低断言、跳过测试或只报告成功数量来掩盖。
4. Android 真机、真实账号或公网链路未具备时明确列为未验证；不要用桌面 JVM 结果替代 Android 结果。

### REL-02：版本、产物和发布

- 按 TD-06 完成真实 SDK 升级及插件模块版本统一，再做 YunGet 的端到端适配；
  不把 `mavenLocal` 存在的旧产物当成已升级。记录构建 JDK、目标 Android 版本、提交和产物哈希。
- 发布前生成模块、版本、`.jar/.pom/.module`、CLI、Maven 包及 APK 的清单，逐项校验数量和哈希，
  包含新旧版本升级/回退与真实依赖验证；避免只发少量附件就称完整发布。
- 合入、推送、发布及覆盖线上产物之前遵守工作区授权规则；保持 Windows 产物缺席。

## 6. 具体执行顺序与阶段门禁

| 顺序 | 任务 | 进入下一步的条件 |
| --- | --- | --- |
| 1 | TD-01、TD-02、YG-02 | 范围与断点数据安全有可复现测试，失败不误删已下载数据 |
| 2 | TD-03、TD-04、TD-05、YG-01 | 身份、镜像、任务状态与事件竞态有回归，安全默认已说明 |
| 3 | TD-06、TD-11、YG-03、YG-04 | SDK 与应用版本、元数据及设置/真实状态映射能端到端验证 |
| 4 | TD-07、TD-08、TD-09、TD-10 | 有可复现单流及竞品基线、真实性校验、吞吐和资源结果 |
| 5 | TD-12、TD-13、TD-14、TD-15、TD-16 | 媒体、插件、CLI、队列与每种协议逐项交付或公开记录阻塞 |
| 6 | YG-05、YG-06、YG-07 | 移动端后台、隐私、保存及界面通过针对性和设备验证 |
| 7 | REL-01、REL-02 | 完整相关测试、兼容报告、产物清单和用户授权均就绪 |

任务间可并行分析，但修改同一文件、公开契约或同一数据迁移的任务先串行合并。
发现前一阶段的未解决数据损坏、凭据泄漏或兼容性问题时，暂停依赖它的后续工作。
持续维护每项状态：未开始、进行中、受阻、已完成；记录代码提交、测试命令、实际结果、未验证范围和下一步。

### 2026-11-03 执行记录（只记录已做部分，不代表整项验收）

| 任务 | 当前状态 | 已完成证据 | 仍缺 |
| --- | --- | --- | --- |
| TD-01 | 进行中 | 416 三条回归用例；一次 416 后重试成功 | 错误归因、更多 CDN 变体 |
| TD-02 | 进行中 | 动态尾段侧车排序/残缺快照测试；相关测试回归 | 真实断电/重启压力 |
| TD-04 | 进行中 | 镜像终址按原始来源隔离；跨源及跨 origin 302 凭据过滤测试 | 真实镜像验证 |
| TD-05 | 进行中 | 暂停/完成竞态、事件单消费者顺序、合并/完成/失败事件测试 | 全状态迁移矩阵与 Android 接入 |
| TD-06 | 进行中 | `JvmOverloads` 旧构造器兼容测试；YunGet 已升级至 `0.4.1.0` | 旧字节码整体兼容 |
| TD-07 | 进行中 | 基准工具增加本地 SHA-256 与预期摘要校验 | 完整矩阵、竞品同条件结果 |
| YG-01 | 进行中 | 稳定键恢复短任务事件映射；映射单元测试 | 真实短任务集成 |
| YG-02 | 进行中 | 普通失败保留可续传输出、完整性失败清理策略测试 | 保存/重启/磁盘满设备流程 |
| YG-03 | 进行中 | 实际连接数与配置上限分开显示；0 路不再伪造为 1 | 设备截图和 Compose 交互测试 |
| YG-07 | 进行中 | 空态防闪、窄屏批量栏、部分 48dp 触控目标、可取消取链、文件缺失反馈 | 全页面视觉/无障碍设备检查 |
| YG-08 | 已完成构建 | 四种 APK 均验证为 `2.7.23`/`versionCode 71`；更新器 ABI 单元测试通过 | 真机安装升级验证 |
| REL-02 | 待发布 | core 74 suites/228 tests、app 32 suites/282 tests 全绿；四包已构建 | 用户授权后提交、推送和创建 Release |

核心与 app 完整单元测试已通过；Android 真机、网络矩阵、旧版升级链路和发布验证仍未完成。

本地回环、每连接 100&nbsp;KB/s 的连接阶梯测试：引擎下载 16 MiB 文件，快速起跑且开启自适应；curl 单流下载其中 1 MiB。下表速率统一以 MiB/s 计算，8 路为两次中位数，其余档位各一次。

| 并发上限 | 实测吞吐 | 相对单流 |
|---:|---:|---:|
| 1（curl 单流） | 0.104 MiB/s | 1.0x |
| 8 | 0.74 MiB/s | 7.1x |
| 16 | 1.49 MiB/s | 14.3x |
| 32 | 2.73 MiB/s | 26.2x |
| 64 | 4.74 MiB/s | 45.6x |
| 128 | 6.71 MiB/s | 64.5x |
| 256 | 7.64 MiB/s | 73.5x |

这组数据表明本地限速模型中增加连接数能提高吞吐。单流与多连接下载的数据量不同，
比值只作参考；公网 CDN、网盘风控和聚合限速仍需单独验证。

</details>
