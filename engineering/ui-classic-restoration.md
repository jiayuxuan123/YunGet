# 经典界面恢复与设置精简

基线：`v2.7.27` / `474aa019`。经典实现来源：`v2.7.26` / `210e35eb`。

## 路径

`SettingsRepository.uiStyleMode` → `ThemeController` → `LocalAppUiStyle` → 四个主页面入口。
选中经典模式时，在创建新版页面状态之前返回经典页面。两个模式使用同一组父级 ViewModel。

设置说明通过 `show_setting_descriptions` 持久化；`LocalShowSettingDescriptions` 同时驱动新版、经典设置行和主题页。
需要显示的当前值由 `valueText` 显式传入，和可隐藏的解释分开。
删除确认、权限说明、安全提示和错误原因不受这个开关控制。

## 检查范围

- 七个经典页面与原 tag 对比，ViewModel 操作集合未缺项；任务详情复用现有面板。
- Compose 预览使用实际经典组件，对照新版浅色、深色、大字体、平板及说明开关。
- 单元测试覆盖隐藏说明时保留当前值，以及纯说明行不会留下空文本。
- 本地构建版本 `2.7.28` / `76`，不覆盖 `v2.7.27` Release。

## Astryx 的使用方式

阅读并实际查询了 [官方 MCP 实现](https://github.com/meta/astryx/blob/main/apps/docsite/src/app/mcp/route.ts)、
[控件节奏](https://github.com/meta/astryx/blob/main/docs/design/control-rhythm.md)、
[空间层级](https://github.com/meta/astryx/blob/main/docs/design/spatial-hierarchy.md) 和
[移动排版](https://github.com/meta/astryx/blob/main/docs/design/mobile-typography.md)。其中控件节奏与空间层级文档仍标为 draft，作为设计参考，不视作已验证的性能结论。

官方组件面向 React，Android 继续使用 Compose Material 3。
本轮将其“可见控件尺寸与触控范围分开”“先用间距分组”“开关标签与控件统一交互”转为原生实现：
主题页取消只为一个开关增加的独立卡片，说明开关合入界面风格分组；外观开关整行可点且只有一个切换回调。
保留系统字号和原生触控范围，没有引入 WebView、React 或额外运行时。

CLI `0.7.11` 已安装。Codex 增加官方 Streamable HTTP MCP `https://astryx.atmeta.com/mcp`，
初始化、tools/list、search(Switch)、get(Switch)、get(spacing) 实际成功。服务只暴露 search/get。

## 经典恢复阶段验证（本轮视觉细化前）

- `:app:testDebugUnitTest`：46 个套件，362 项测试，0 失败、0 错误、0 跳过。
- `:app:updateDebugScreenshotTest`：21 个实际 Compose 组件预览生成成功；人工检查经典/新版对照、设置说明开关、深浅色、1.3 倍字体和主题页。
- `:app:assembleRelease :app:verifyApkVersion`：四种 ABI/通用 APK 全部构建成功，版本均为 `2.7.28` / `76`。
- 四个 APK 的 ZIP 完整性与发布签名校验全部通过，签名和现有已发布版本一致。
- 七个经典页面源码与原 tag 的 ViewModel 操作集合对照无缺项；这是静态接线检查，不替代完整实机操作。
- 没有新增运行时 UI 依赖，没有发布、推送或部署。
- 当前 ADB 没有已连接设备，本轮不报告真机触控、切换持久化实机验收或帧率结论。

本地包和对照图位于 `_audit/ui-redesign/2.7.28/`，机器可读记录为 `evidence.json`。
通用包 SHA-256：`04cbde2dbb4fa01a8a04673fc3a81d709e77a02aee0ac8e8d6a660e1898064f4`。

后续本地视觉细化记录见 [2026-11-09 细化](ui-refinement-2026-11-09.md)。
本轮最新成品使用 `_audit/ui-redesign/2.7.28-refined/`，不与以上阶段性安装包混用哈希。
