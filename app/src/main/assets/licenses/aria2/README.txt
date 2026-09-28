aria2c（APK 内置可执行文件）的许可证与来源说明
================================================

本目录包含 APK 内置的 aria2c 可执行文件所对应的许可证与署名文件。

来源
----
  aria2 1.37.0 官方发布版
  https://github.com/aria2/aria2/releases/tag/release-1.37.0
  资产：aria2-1.37.0-aarch64-linux-android-build1.zip
  文件：aria2c（6,100,888 字节）
        ELF64 AArch64，PT_INTERP=/system/bin/linker64，类型 DYN(PIE)
        DT_NEEDED 仅 libm.so / libdl.so / libc.so（均为 Android 系统库）
        openssl 1.1.1k / expat 2.4.1 / zlib 1.2.11 / c-ares 1.17.2 / libssh2 1.9.0 全部静态链接

  SHA-256: 9397aac0de54c8c1...（完整值见项目构建记录）

许可证
------
  aria2 本体：GNU GPL v2 或更高版本（GPL-2.0-or-later），见 COPYING
  另含 OpenSSL 链接例外条款，见 LICENSE.OpenSSL
  贡献者署名见 AUTHORS

  与本应用（AGPL-3.0）的兼容性：GPL-2.0-**or-later** 允许升级到 GPLv3，
  而 GPLv3 与 AGPLv3 通过各自 §13 明文允许组合，因此可以一并分发。
  （若 aria2 是 GPL-2.0-only，则不可与 AGPL-3.0 组合 —— 正是 "or later" 使这条成立。）

对应源码
--------
  未对 aria2 源码做任何修改，直接使用官方发布的二进制。
  因此其完整对应源码即官方 release-1.37.0 标签：
  https://github.com/aria2/aria2/tree/release-1.37.0

  若将来改为自行交叉编译（例如裁剪体积），必须同时公开所用的
  构建脚本与全部补丁。
