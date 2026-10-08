# 从源码构建

想自己编译一个 YunGet 出来，最容易卡住的不是 Gradle，也不是 Android SDK，而是第一步：TurboDL 的 SDK 不在任何公共仓库上，得先把它发到本地 Maven，YunGet 才解析得到依赖。这篇按顺序把准备、构建和测试过一遍。

## 需要准备什么

| 需要的东西 | 说明 |
|---|---|
| JDK | 17 或更高 |
| Android SDK | 在 `local.properties` 里写一行 `sdk.dir=...`，或者设环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT` |
| Gradle | 不用单独装。用仓库自带的 wrapper，版本 `gradle-9.0.0`，distributionUrl 指向腾讯云镜像，国内网络可以直接下 |

## 关键一步：先把 TurboDL 发布到本地 Maven

YunGet 依赖 `dev.turbodl:turbodl-core`、`turbo-plugin-runtime`、`turbo-plugin-bootstrap`、`turbo-plugin-hls`，版本都是 `0.2.0.6`。这些坐标不在 Maven Central，也不在 Google 的仓库里，只存在于本地 Maven。

`settings.gradle.kts` 的仓库列表里 `mavenLocal()` 排在最前面，就是为了让这几个依赖优先从本地解析。所以在编译 YunGet 之前，先在 TurboDL 的仓库里执行：

```bash
./gradlew publishToMavenLocal
```

手上没有 TurboDL 源码的话，也可以去 TurboDL 的 Release 页面下载 mavenLocal 离线包，解到 `~/.m2/repository` 下，效果一样。

这一步漏了，构建会在依赖解析阶段直接失败，提示找不到 `dev.turbodl:*`。

## 签名

仓库根目录的 `keystore.properties` 需要四个键：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`。有这个文件，构建才会生成正式的 release 签名，同时打开 V1、V2、V3 三种签名方案。

没有它也能构建，会退回调试签名——装到手机上能跑，但不能拿去发布。`keystore.properties` 和签名文件本身都在 `.gitignore` 里，不会被提交。

## 构建

```bash
git clone https://github.com/jiayuxuan123/YunGet.git
cd YunGet
# 配置 local.properties: sdk.dir=/path/to/Android/sdk
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

两点补充：

- release 构建没有开混淆（`isMinifyEnabled = false`），不用操心混淆规则。
- 构建完还有一个 `verifyApkVersion` 任务，会打开 APK 检查里面内嵌的版本号和源码里写的一致（需要 aapt2）。

## 跑测试

```bash
./gradlew :app:testDebugUnitTest
```

测试有 10 个文件、68 个用例，全部是纯 JVM 测试：不依赖 Android 运行时，也不需要模拟器。覆盖的内容包括版本号比较、分享链接解析、迅雷口令、数据库迁移契约、加密凭证契约、下载引擎枚举，以及进度落盘的节流逻辑。

仓库里没有 `androidTest` 目录，所以跑测试之前不用准备设备或模拟器。

## 仓库里已经带上、不用你自己准备的东西

| 路径 | 是什么 |
|---|---|
| `app/src/main/jniLibs/arm64-v8a/libaria2c.so` | aria2 的二进制，只有 arm64 |
| `app/libs/gopeed-classes.jar` | Gopeed 内核的 Java 接口类 |

不过 Gopeed 的 `.so` 内核不在仓库里，它由用户在应用内导入，构建时不需要（细节见 `docs/ENGINES.md`）。

## 国内网络

`settings.gradle.kts` 里 pluginManagement 和 dependencyResolutionManagement 都配了阿里云的镜像（google 和 public 两个），兜底再走 `gradlePluginPortal()`、`google()`、`mavenCentral()`；Gradle wrapper 则走腾讯云。一般情况下不用再动这些配置。
