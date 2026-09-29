内置下载引擎的许可证与来源说明
================================

本目录包含 APK 内置的 aria2-next 可执行文件所对应的许可证与署名文件。

来源
----
  aria2-next 2.8.3 官方发布版
  https://github.com/AnInsomniacy/aria2-next/releases/tag/v2.8.3
  资产：aria2-next-2.8.3-android-arm64（17,482,856 字节）
  文件：libaria2c.so（为随 APK 打包而改后缀，内容未做任何修改）
        ELF64 AArch64，PT_INTERP=/system/bin/linker64，类型 DYN(PIE)
        DT_NEEDED 仅 libm.so / libdl.so / libc.so（均为 Android 系统库）
        libcurl 8.21.0 / nghttp2 1.70.0 / OpenSSL 3.5.6 / zlib 1.3.2 /
        expat 2.8.1 / sqlite3 3.53.1 / libtorrent 2.1.1 / GPAC 26.07 /
        FFmpeg 8.1.2 全部静态链接（单文件即可运行）

  SHA-256: 99973ab5a46a405cb2ea8ba1832b2c1365a1a929a52a74b4946a005c135998e4
  （与官方 v2.8.3 的 aria2-next-2.8.3-checksums.sha256 一致，可独立复核）

  Android 平台要求：API 33+（ELF note 中标记），仅提供 arm64-v8a。

许可证
------
  aria2-next 本体：GNU GPL v2 或更高版本（GPL-2.0-or-later），见 COPYING
  二进制内部的声明（可直接从文件里提取到）：
      "either version 2 of the License, or (at your option) any later version."
  另含 OpenSSL 链接例外条款，见 LICENSE.OpenSSL
  贡献者署名见 AUTHORS

  与本应用（AGPL-3.0）的兼容性：GPL-2.0-**or-later** 允许升级到 GPLv3，
  而 GPLv3 与 AGPLv3 通过各自 §13 明文允许组合，因此可以一并分发。
  （若为 GPL-2.0-only 则不可与 AGPL-3.0 组合 —— 正是 "or later" 使这条成立。）

对应源码
--------
  未对 aria2-next 源码做任何修改，直接使用官方发布的二进制。
  因此其完整对应源码即官方 v2.8.3 标签：
  https://github.com/AnInsomniacy/aria2-next/tree/v2.8.3

  若将来改为自行交叉编译（例如裁剪体积），必须同时公开所用的
  构建脚本与全部补丁。

  aria2-next 是上游 aria2 的社区维护分支（原作者 tatsuhiro-t 的贡献仍在其中）。
  上游项目：https://github.com/aria2/aria2

为什么不用上游 aria2 1.37
-------------------------
  上游 aria2 自 1.37 后长期未更新，而 aria2-next 修复了大量上游问题，
  其中两条与我们的真实场景直接相关：
    ① 已验证的**重定向目标可复用**，过期时每个源路由只刷新一次 ——
       网盘（如夸克）原始链接会 302 到带签名的 CDN 临时直链，直链约 3 小时失效；
       旧行为是每个分片都回去撞签名入口，容易触发风控。
    ② `--stream-max-connections` 上限提到 **256**（上游 `-x` 硬上限只有 16）。

  注意：本应用当前**仍把单文件连接数限制在 16**，这是为了避免网盘风控
  （用户明确要求默认线程数不得拉满），与引擎上限无关。

  ⚠️ 兼容性提示：aria2-next 的**控制台进度行格式与上游不同** ——
      上游   [#gid 已完成B/总长B(百分比) CN:.. DL:.. ETA:..]
      本 fork [#gid [进度条] 百分比 已完成B/总长B CN:.. DL:..]
     进度解析正则已相应调整为不依赖固定锚点（见 DownloadEngineRunner）。

  另：`-s` / `-x` 在 fork 中会被接受但归一化到 `--stream-max-connections`；
  `-k`（最小分片）已被移除（发了会打一条 skipped 警告），故本应用不再传它。
