# 文析研究对应的云取应用改进

固定参考 `z7786/wenxi@28e7b4576f5c3a600a44240967c03e21ef579a1f`，2026-11-10已核对为当时main。完整研究与原始内核对照放在同一工作区的兄弟仓库：`TurboDL/engineering/research/wenxi-2026-11-10/`；机器可读任务卡为 `ADOPTION.csv`。

本轮没有替换云取UI、引入对方实现文件、私有服务配置或供应商统计。以下是实际入口和落地边界，不能把参考项目的菜单直接当成云取已实现的功能。

## 优先处理

| 方向 | 云取入口 | 需要确认的行为 |
| --- | --- | --- |
| 来源和并发策略 | `app/src/main/kotlin/com/yunget/app/data/download/TurboDownloadManager.kt` | 把来源路线、用户上限、实际连接、探测/刷新/校验/导出分开诊断；账号和来源策略由宿主负责 |
| aria2执行桥接 | 同目录`Aria2CommandBuilder.kt`、`Aria2Readout.kt`、`DownloadEngineRunner.kt` | 本批已修1..256参数透传、稳定GID/状态目录、真实CN、稀疏文件误报与恢复测速基线 |
| 校验与保存 | 同目录`DownloadPostProcessor.kt`、`DownloadSaver.kt` | 当前先缓存再保存；先测完整复制和排队时间，再考虑可选直接目标与独立后处理队列 |
| 账号所有权 | `app/src/main/kotlin/com/yunget/app/data/db/SecureAccountDaos.kt`及凭据仓库 | 已有能力先差分审计：切账号、换链接、暂停、删除时的迟到结果不能污染新任务 |
| 凭据存储 | `app/src/main/kotlin/com/yunget/app/data/security/CredentialCipher.kt` | 保留Keystore加密和密钥丢失处理，不仿照参考项目的明文状态树 |
| 媒体功能 | 应用现有播放器/预览入口 | 先核对已有能力，再按需加可读区间复用、续播、定时停止、字幕；界面独立设计 |
| 文件和扩展能力 | 现有网盘浏览、分享详情和插件入口 | 排序、分页、上传、家庭云或互联按来源能力逐项实现；无实现的按钮不得只做外观 |

## 源码结论

- 文析按来源选不同取链路线，UC还验证返回的是所选原文件；不能把所有差异归为线程数。
- 它区分网络完成、校验、保存和清理，Android原生摘要避免逐块跨Flutter桥传输。云取可以学习阶段和IO边界，不需要更换主语言或UI框架。
- 文析通用HTTP在缓存跨主机终址后重新附带原始凭据，已用虚构凭据在本地确认；云取/TurboDL保留自己的跨origin隔离。
- 文析当前Dart状态树写入含校验和的明文JSON；原生bbolt加密是另一层。云取保留现有凭据保护。
- 参考项目HLS下载拒绝加密、ByteRange和直播；TurboDL已有对应部分能力，不为了统一代码而降级。

## 本轮验证与状态

云取最近一次参数/readout定向测试2套件8项通过；本批更广23项回归和2.52倍受控aria2并发收益见 `aria2-and-reference-review-2026-11-10.md`。这些数据来自本地源和同版本内核，不是手机真实账号测速。

原始文析helper已实际构建；选定HTTP9个顶层测试、恢复4个顶层测试均通过，另含8个子用例。源码研究和性能数据详见兄弟仓库的 `EVIDENCE.md` / `EVIDENCE.json`。原始日志与研究程序放在D盘，临时下载文件逐轮删除。

本维护批次已随 2.7.29 发布，内置 TurboDL 0.5.0.2。客户端 app 373 项与应用 core 26 项测试通过，四种 APK 和独立内核包完成版本、签名与完整性检查。完整日志包含本轮引擎、aria2 和同期社区更新；研究中的其他功能任务保持独立待办。
