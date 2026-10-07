# YunGet（云取）

> 网盘分享链接解析与高速下载的 Android 应用。粘贴分享链接，就能浏览分享内容并直接下载文件。
>
> **本项目是 [CYQawa/YunX（云析）](https://github.com/CYQawa/YunX) 的二次开发版本**，在其协议与架构基础上进行功能扩展，并将下载能力逐步迁移至独立的 [TurboDL](https://github.com/jiayuxuan123/TurboDL) 下载引擎。遵循 GNU AGPL-3.0 协议开源。

---

## 与上游（YunX）的关系

- 本仓库 fork 自上游 [CYQawa/YunX](https://github.com/CYQawa/YunX)，感谢原作者的工作。
- 上游协议为 **GNU AGPL-3.0**，本项目同样以 **AGPL-3.0** 继续开源，并保留原始版权与协议声明。
- 本项目在上游协议解析、网盘功能和 Android 应用结构基础上进行了二次开发。
- 下载引擎已逐步从项目内部实现迁移至独立的 [TurboDL](https://github.com/jiayuxuan123/TurboDL) SDK，以便将下载核心与具体应用解耦，并支持后续独立迭代。
- 当前 TurboDL 已用于 YunGet 的普通直链多线程下载，并逐步承担原有下载引擎的核心职责。
- HLS 下载能力通过 TurboDL 的插件化机制接入，后续其他下载协议或能力也将以插件形式扩展。
- **赞赏渠道说明**：应用内「支持开发」页的赞赏码仅面向**本二次开发版本**的维护；若想支持上游原项目作者，请移步 [上游仓库](https://github.com/CYQawa/YunX) 的捐赠渠道，避免权益混淆。

## 支持平台

> **不建议使用百度网盘，可能导致账号被风控，请谨慎使用。**

- 夸克网盘
- UC 网盘
- 迅雷网盘
- 百度网盘
- 123 云盘
- 139 网盘（和彩云）

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

## TurboDL 下载引擎

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
    └── HLS Plugin
```

这种设计允许不同项目根据自己的需求选择能力，而不需要所有项目都携带完整的下载功能。

后续可以继续扩展：

```text
TurboDL
├── Core
├── HTTP/HTTPS
├── HLS Plugin
├── 其他协议插件
└── 第三方扩展
```

插件可以独立维护、独立发布和独立接入，从而降低核心引擎与具体协议之间的耦合。

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
- Jetpack Compose
- Material 3
- Room（凭证与下载任务持久化）
- OkHttp（网络请求与 HTTP 下载）
- TurboDL（通用下载引擎 SDK）
- KSP

## 构建

要求：

- minSdk 23
- targetSdk 34
- JDK 17+

```bash
git clone https://github.com/jiayuxuan123/YunGet.git
cd YunGet

# 配置 local.properties 指向 Android SDK
# sdk.dir=/path/to/Android/sdk

./gradlew :app:assembleRelease
```

也可以使用 Android Studio 打开项目直接构建。

国内网络环境可根据实际情况将 Gradle 与依赖仓库配置为可访问的镜像，相关配置位于：

```text
gradle/wrapper/gradle-wrapper.properties
settings.gradle.kts
```

## 关于 TurboDL

TurboDL 是本项目后续重点使用的独立下载引擎。

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
