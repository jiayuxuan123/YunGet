---
name: YunGet Community
description: A mobile-first feedback inbox and quiet conversation space.
colors:
  canvas: "#f7f9fc"
  surface: "#ffffff"
  wash: "#edf2f8"
  ink: "#1c2738"
  muted: "#576477"
  line: "#dce3ed"
  accent: "#275fba"
  accent-soft: "#e8f0fd"
  dark-canvas: "#11161d"
  dark-surface: "#1b232e"
  dark-ink: "#e6edf6"
  dark-accent: "#a5c7ff"
typography:
  title:
    fontFamily: "MiSans, PingFang SC, Microsoft YaHei UI, Noto Sans CJK SC, sans-serif"
    fontSize: "23px"
    fontWeight: 750
    lineHeight: 1.35
  body:
    fontFamily: "MiSans, PingFang SC, Microsoft YaHei UI, Noto Sans CJK SC, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.65
rounded:
  surface: "16px"
  control: "12px"
spacing:
  tight: "8px"
  row: "12px"
  gutter: "16px"
  section: "24px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    height: "44px"
---

## Overview

**Creative North Star: "有进展的收件箱"**

社区是一张能持续追踪的反馈清单。云取蓝只强调当前目的地、主要操作和处理进展；常态使用灰白或深灰表面，让中文内容先被看见。

学习 Astryx 的布局边界、控件节奏和弹层生命周期，使用现有原生 HTML/JS 实现。没有引入新的客户端运行时。反馈、公告与闲聊共用导航和字阶，但根据任务使用不同结构。

## Colors

浅色使用冷灰白工作区与白色阅读表面；深色使用近黑底与深灰阅读表面。强调色为云取蓝，完成状态使用绿色。所有实际颜色以 `style.mjs` 的语义变量为准。

## Typography

采用设备已有的中文界面字体。页面标题手机 23px、桌面 26px；聊天标题 18px；反馈标题 17px；消息与公告正文 15px；列表摘要 14px；元数据 12px。输入框 16px，避免移动浏览器因小输入字号自动放大。

## Layout

页面用三行 Grid 分配导航、可滚动主体和页脚。760px 以下导航独占一行，三个入口等宽；页面水平边距 16px，320px 窄屏为 12px。桌面内容宽度上限 900px。

闲聊独占主体高度，消息列表使用 `minmax(0, 1fr)`；输入栏在同一 Grid 内占正常空间，不覆盖消息。`visualViewport` 同时修正键盘后的可用高度和弹窗位置。旋转时保留用户是否跟随最新消息的意图。

## Elevation & Depth

常态列表用分隔线与表面明度表达层级。只有原生 dialog 和短提示使用阴影；不使用大面积模糊背景。

## Shapes

列表容器 16px 圆角、控件 12px；手机弹层顶部 20px。聊天气泡一角收紧，区分消息与普通列表。标记使用统一描边 SVG。

## Components

- 反馈列表：类型、标题、两行摘要、作者与时间，处理状态位于右上。
- 写反馈：原生 dialog 中的表单，关闭保留本次内存草稿；验证步骤与填写分开。
- 闲聊：增量插入消息，阅读历史时用新消息按钮提示，输入节点不被轮询替换。
- 弹窗：保留可见关闭按钮、原生焦点约束与返回焦点，正文内部滚动；不在聊天输入栏塞入验证码。
- 动效：选择与悬浮 160ms，弹层 220ms；系统减少动态效果时关闭。

## Do's and Don'ts

- 保持反馈优先，不恢复占用首屏的大标题和三行统计卡。
- 所有可点击入口至少 44px；消息正文与 URL 完整换行。
- 禁止用 fixed 定位让输入栏盖住消息。
- 禁止轮询时重建聊天表单，禁止清空未成功发送的草稿。
- 社区设计独立于官网营销页面，不改根目录的官网设计规范。
