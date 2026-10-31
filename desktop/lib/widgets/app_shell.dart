import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:window_manager/window_manager.dart';

import '../app_state.dart';
import '../host/models.dart';
import '../screens/cloud_drives_screen.dart';
import '../screens/downloads_screen.dart';
import '../screens/resolve_screen.dart';
import '../screens/settings_screen.dart';
import '../theme.dart';
import 'command_palette.dart';
import 'desktop_mark.dart';
import 'window_controls.dart';

class AppShell extends StatefulWidget {
  const AppShell({super.key, required this.state, required this.onQuit});

  final AppState state;
  final Future<void> Function() onQuit;

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  static const _tabTitles = ['解析台', '下载队列', '网盘平台', '设置'];
  static const _tabIcons = [
    Icons.travel_explore_rounded,
    Icons.downloading_rounded,
    Icons.cloud_outlined,
    Icons.tune_rounded,
  ];

  final PageController _pageController = PageController();
  int _tab = 0;

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  void _select(int index) {
    if (index == _tab) return;
    setState(() => _tab = index);
    if (_pageController.hasClients) {
      if (MediaQuery.maybeOf(context)?.disableAnimations ?? false) {
        _pageController.jumpToPage(index);
        return;
      }
      unawaited(
        _pageController.animateToPage(
          index,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOutCubic,
        ),
      );
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    try {
      await action();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('$error')));
    }
  }

  void _showCommands() {
    showCommandPalette(context, [
      DesktopCommand(
        label: '解析新链接',
        subtitle: '回到解析台，粘贴分享链接或直链',
        icon: Icons.add_link,
        shortcut: 'Ctrl 1',
        onSelected: () => _select(0),
      ),
      DesktopCommand(
        label: '查看下载队列',
        subtitle: '${widget.state.activeTaskCount} 个进行中',
        icon: Icons.downloading,
        shortcut: 'Ctrl 2',
        onSelected: () => _select(1),
      ),
      DesktopCommand(
        label: '查看支持网盘平台',
        subtitle: '对标移动端 10 个网盘平台与账号支持',
        icon: Icons.cloud_outlined,
        shortcut: 'Ctrl 3',
        onSelected: () => _select(2),
      ),
      DesktopCommand(
        label: '打开设置',
        subtitle: '连接数、代理、托盘与通知',
        icon: Icons.tune,
        shortcut: 'Ctrl 4',
        onSelected: () => _select(3),
      ),
      DesktopCommand(
        label: '刷新任务',
        subtitle: '重新读取宿主任务表',
        icon: Icons.refresh,
        shortcut: 'F5',
        onSelected: () => unawaited(_run(widget.state.refreshTasks)),
      ),
      DesktopCommand(
        label: '暂停全部任务',
        subtitle: '保留分片，下一次继续',
        icon: Icons.pause_circle_outline,
        onSelected: () => unawaited(_run(widget.state.pauseAll)),
      ),
      DesktopCommand(
        label: '继续全部任务',
        subtitle: '恢复暂停或失败的下载',
        icon: Icons.play_circle_outline,
        onSelected: () => unawaited(_run(widget.state.resumeAll)),
      ),
      DesktopCommand(
        label: '打开下载目录',
        subtitle: widget.state.settings?.downloadDir ?? '在设置中选择目录',
        icon: Icons.folder_open,
        shortcut: 'Ctrl O',
        onSelected: () => unawaited(_run(widget.state.openDownloadDirectory)),
      ),
      DesktopCommand(
        label: '退出 YunGet',
        subtitle: '任务会保存为暂停状态，可下次续传',
        icon: Icons.power_settings_new,
        onSelected: () => unawaited(widget.onQuit()),
      ),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final shortcuts = <ShortcutActivator, VoidCallback>{
      const SingleActivator(LogicalKeyboardKey.keyK, control: true):
          _showCommands,
      const SingleActivator(LogicalKeyboardKey.keyK, meta: true): _showCommands,
      const SingleActivator(LogicalKeyboardKey.digit1, control: true): () =>
          _select(0),
      const SingleActivator(LogicalKeyboardKey.digit2, control: true): () =>
          _select(1),
      const SingleActivator(LogicalKeyboardKey.digit3, control: true): () =>
          _select(2),
      const SingleActivator(LogicalKeyboardKey.digit4, control: true): () =>
          _select(3),
      const SingleActivator(LogicalKeyboardKey.digit1, meta: true): () =>
          _select(0),
      const SingleActivator(LogicalKeyboardKey.digit2, meta: true): () =>
          _select(1),
      const SingleActivator(LogicalKeyboardKey.digit3, meta: true): () =>
          _select(2),
      const SingleActivator(LogicalKeyboardKey.digit4, meta: true): () =>
          _select(3),
      const SingleActivator(LogicalKeyboardKey.keyO, control: true): () =>
          unawaited(_run(widget.state.openDownloadDirectory)),
      const SingleActivator(LogicalKeyboardKey.keyO, meta: true): () =>
          unawaited(_run(widget.state.openDownloadDirectory)),
      const SingleActivator(LogicalKeyboardKey.f5): () =>
          unawaited(_run(widget.state.refreshTasks)),
    };

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Focus(
        autofocus: true,
        child: CallbackShortcuts(
          bindings: shortcuts,
          child: DecoratedBox(
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  YunGetColors.paper,
                  Color(0xFFF8F3EA),
                  YunGetColors.paperDeep,
                ],
                stops: [0, 0.55, 1],
              ),
            ),
            child: Column(
              children: [
                _topHeader(context),
                Expanded(
                  child: PageView(
                    controller: _pageController,
                    physics: const NeverScrollableScrollPhysics(),
                    onPageChanged: (index) {
                      if (_tab != index) setState(() => _tab = index);
                    },
                    children: [
                      _KeepAlivePage(
                        child: RepaintBoundary(
                          child: ResolveScreen(
                            state: widget.state,
                            onOpenDownloads: () => _select(1),
                            active: _tab == 0,
                          ),
                        ),
                      ),
                      _KeepAlivePage(
                        child: RepaintBoundary(
                          child: DownloadsScreen(
                            state: widget.state,
                            active: _tab == 1,
                          ),
                        ),
                      ),
                      _KeepAlivePage(
                        child: RepaintBoundary(
                          child: CloudDrivesScreen(
                            onNavigateToResolve: () => _select(0),
                          ),
                        ),
                      ),
                      _KeepAlivePage(
                        child: RepaintBoundary(
                          child: SettingsScreen(state: widget.state),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// PCL 风格的顶部自定义标题栏 + 选项卡导航 + 窗口操作按钮
  Widget _topHeader(BuildContext context) {
    return Container(
      height: 54,
      decoration: const BoxDecoration(
        color: YunGetColors.ink,
        border: Border(bottom: BorderSide(color: Color(0x33000000), width: 1)),
      ),
      child: Row(
        children: [
          // 左侧：品牌 Logo + 名称
          Padding(
            padding: const EdgeInsets.only(left: 14, right: 18),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 32,
                  height: 32,
                  padding: const EdgeInsets.all(4),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const DesktopMark(size: 24),
                ),
                const SizedBox(width: 10),
                const Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '云取',
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.w900,
                        fontSize: 16,
                        letterSpacing: 1.2,
                      ),
                    ),
                    Text(
                      'YUNGET DESKTOP',
                      style: TextStyle(
                        color: Color(0xFF8B97A2),
                        fontWeight: FontWeight.w600,
                        fontSize: 8.5,
                        letterSpacing: 0.8,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          // 中间：PCL 风格胶囊选项卡按钮组
          Row(
            mainAxisSize: MainAxisSize.min,
            children: List.generate(_tabTitles.length, (index) {
              final active = _tab == index;
              final title = _tabTitles[index];
              final icon = _tabIcons[index];
              final isDownloads = index == 1;
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: _PclTabButton(
                  title: title,
                  icon: icon,
                  active: active,
                  badge: isDownloads
                      ? ListenableBuilder(
                          listenable: widget.state.taskSummaryRevision,
                          builder: (context, _) {
                            final count = widget.state.activeTaskCount;
                            if (count <= 0) return const SizedBox.shrink();
                            return Container(
                              margin: const EdgeInsets.only(left: 6),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 6,
                                vertical: 2,
                              ),
                              decoration: BoxDecoration(
                                color: active ? Colors.white : YunGetColors.accent,
                                borderRadius: BorderRadius.circular(999),
                              ),
                              child: Text(
                                '$count',
                                style: TextStyle(
                                  color: active ? YunGetColors.accent : Colors.white,
                                  fontSize: 10,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            );
                          },
                        )
                      : null,
                  onTap: () => _select(index),
                ),
              );
            }),
          ),
          // 空白可拖动区域（双击最大化 / 还原）
          Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.translucent,
              onDoubleTap: () async {
                final isMax = await windowManager.isMaximized();
                if (isMax) {
                  await windowManager.unmaximize();
                } else {
                  await windowManager.maximize();
                }
              },
              child: const DragToMoveArea(
                child: SizedBox(height: 54),
              ),
            ),
          ),
          // 实时总速度胶囊
          _LiveSpeed(state: widget.state),
          const SizedBox(width: 8),
          // 快捷指令按钮
          _CommandButton(onTap: _showCommands),
          const SizedBox(width: 8),
          // 自定义窗口控制按钮组（高对比度，绝对不会变白看不见）
          const WindowControls(
            backgroundColor: Colors.transparent,
            iconColor: Color(0xFFD3DCE6),
            hoverColor: Color(0x33FFFFFF),
            pressedColor: Color(0x55FFFFFF),
            focusColor: YunGetColors.accent,
            closeIconColor: Color(0xFFD3DCE6),
            closeHoverColor: Color(0xFFE81123),
            closePressedColor: Color(0xFFC42B1C),
            closeHoverIconColor: Colors.white,
            closePressedIconColor: Colors.white,
          ),
        ],
      ),
    );
  }
}

/// PCL 样式的胶囊选项卡按钮
class _PclTabButton extends StatefulWidget {
  const _PclTabButton({
    required this.title,
    required this.icon,
    required this.active,
    required this.onTap,
    this.badge,
  });

  final String title;
  final IconData icon;
  final bool active;
  final VoidCallback onTap;
  final Widget? badge;

  @override
  State<_PclTabButton> createState() => _PclTabButtonState();
}

class _PclTabButtonState extends State<_PclTabButton> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final active = widget.active;
    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          decoration: BoxDecoration(
            color: active
                ? YunGetColors.accent
                : (_hovered ? const Color(0x24FFFFFF) : Colors.transparent),
            borderRadius: BorderRadius.circular(20),
            boxShadow: active
                ? [
                    BoxShadow(
                      color: YunGetColors.accent.withValues(alpha: 0.4),
                      blurRadius: 10,
                      offset: const Offset(0, 3),
                    ),
                  ]
                : null,
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                widget.icon,
                size: 17,
                color: active
                    ? Colors.white
                    : (_hovered ? Colors.white : const Color(0xFFB0BAC3)),
              ),
              const SizedBox(width: 7),
              Text(
                widget.title,
                style: TextStyle(
                  color: active
                      ? Colors.white
                      : (_hovered ? Colors.white : const Color(0xFFB0BAC3)),
                  fontWeight: active ? FontWeight.w800 : FontWeight.w600,
                  fontSize: 13.5,
                  letterSpacing: 0.3,
                ),
              ),
              if (widget.badge != null) widget.badge!,
            ],
          ),
        ),
      ),
    );
  }
}

class _LiveSpeed extends StatelessWidget {
  const _LiveSpeed({required this.state});

  final AppState state;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<int>(
      valueListenable: state.taskSummaryRevision,
      builder: (context, _, _) {
        final speed = state.totalSpeed;
        final hasSpeed = speed > 0;
        return AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOutCubic,
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(
            color: hasSpeed
                ? YunGetColors.teal.withValues(alpha: 0.22)
                : const Color(0x18FFFFFF),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: hasSpeed
                  ? YunGetColors.teal.withValues(alpha: 0.5)
                  : const Color(0x22FFFFFF),
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.speed_rounded,
                size: 14,
                color: hasSpeed ? const Color(0xFF61D7A7) : const Color(0xFF8B97A2),
              ),
              const SizedBox(width: 5),
              Text(
                hasSpeed ? formatSpeed(speed) : '空闲',
                style: TextStyle(
                  color: hasSpeed ? Colors.white : const Color(0xFF8B97A2),
                  fontWeight: FontWeight.w700,
                  fontSize: 11,
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _CommandButton extends StatefulWidget {
  const _CommandButton({required this.onTap});

  final VoidCallback onTap;

  @override
  State<_CommandButton> createState() => _CommandButtonState();
}

class _CommandButtonState extends State<_CommandButton> {
  bool _hovered = false;
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: widget.onTap,
        onTapDown: (_) => setState(() => _pressed = true),
        onTapUp: (_) => setState(() => _pressed = false),
        onTapCancel: () => setState(() => _pressed = false),
        child: AnimatedScale(
          scale: _pressed ? 0.98 : 1,
          duration: const Duration(milliseconds: 80),
          curve: Curves.easeOutCubic,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 140),
            height: 32,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: BoxDecoration(
              color: _hovered ? const Color(0x26FFFFFF) : const Color(0x14FFFFFF),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(
                color: _hovered ? const Color(0x44FFFFFF) : const Color(0x22FFFFFF),
              ),
            ),
            child: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.search_rounded, size: 14, color: Color(0xFFD3DCE6)),
                SizedBox(width: 6),
                Text(
                  '快捷命令',
                  style: TextStyle(
                    color: Color(0xFFD3DCE6),
                    fontSize: 11.5,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                SizedBox(width: 6),
                _ShortcutKey('Ctrl K'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ShortcutKey extends StatelessWidget {
  const _ShortcutKey(this.label);

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        label,
        style: const TextStyle(
          color: Color(0xFFA1ACB7),
          fontSize: 9.5,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _KeepAlivePage extends StatefulWidget {
  const _KeepAlivePage({required this.child});

  final Widget child;

  @override
  State<_KeepAlivePage> createState() => _KeepAlivePageState();
}

class _KeepAlivePageState extends State<_KeepAlivePage>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return widget.child;
  }
}
