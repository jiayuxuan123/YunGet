# 云取桌面客户端

Windows 客户端和 Android 共用根目录 `gradle.properties` 里的 `yungetVersion`。新客户端的界面使用 Compose Multiplatform。平板与桌面共用宽屏导航，窄窗口切换为底部导航。

- `shared-ui` 提供导航、图标、亮暗配色、字阶、设置分组、HTTP 版本选择和分享链接输入。
- `core` 提供纯 Kotlin 的网盘协议、分享链接解析和数据模型。
- `desktopApp` 对接 Windows 文件选择、桌面打开文件和 TurboDL DLL。桌面任务、输出和断点由新宿主管理。

## Windows x64 测试构建

准备 JDK 21，将 TurboDL 与云取置于同一父目录，并使用 TurboDL 安装输出的 `bin` 目录。该目录应包含 `turbodl_engine.dll` 及同次构建的传输、BT 和媒体依赖。

```powershell
.\desktopApp\build-windows.ps1 -NativeBin <TurboDL-bin> -Jdk <JDK-21> -OutputDirectory <输出目录>
```

从输出目录启动 `YunGet\YunGet.exe`。便携包包含 Java 运行时和引擎库。窗口可缩放，同一套导航在平板宽度和紧凑宽度之间切换。

## 当前测试范围

支持 HTTP/HTTPS、FTP、HLS/DASH、磁力、本地种子和本地 Metalink 输入。HTTP 分片下载可在设置中选择最高 512 条并发请求，实际并发取决于文件大小、服务器 Range 支持和全局连接预算。

「我的网盘」支持搜索平台。小飞机使用现有个人盘界面。城通、阿里云盘、天翼云盘、腾讯微云、联通沃盘和 PikPak 使用独立的 Edge 或 Chrome 会话，在官网登录、浏览和下载。各网盘保存自己的本机会话，可在关闭该网盘窗口后从云取退出。智能解析识别网页版网盘分享后提供官网入口，城通已支持的公开分享继续使用内置解析。

Windows 本地集成测试覆盖从同一任务记录重启并恢复、校验文件内容、保留已占用的目标文件以及仅重试保存。旧 Flutter 客户端的源码保留为历史归档。
