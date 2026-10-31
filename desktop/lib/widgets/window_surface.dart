import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';

import '../theme.dart';

/// 无系统标题栏时的窗口表面：自己画边框、圆角，并保留四边缩放。
class WindowSurface extends StatefulWidget {
  const WindowSurface({super.key, required this.child});

  final Widget child;

  @override
  State<WindowSurface> createState() => _WindowSurfaceState();
}

class _WindowSurfaceState extends State<WindowSurface> with WindowListener {
  bool _maximized = false;
  bool _fullScreen = false;

  @override
  void initState() {
    super.initState();
    windowManager.addListener(this);
    _syncWindowState();
  }

  Future<void> _syncWindowState() async {
    final maximized = await windowManager.isMaximized();
    final fullScreen = await windowManager.isFullScreen();
    if (!mounted) return;
    setState(() {
      _maximized = maximized;
      _fullScreen = fullScreen;
    });
  }

  @override
  void dispose() {
    windowManager.removeListener(this);
    super.dispose();
  }

  @override
  void onWindowMaximize() {
    if (mounted) setState(() => _maximized = true);
  }

  @override
  void onWindowUnmaximize() {
    if (mounted) setState(() => _maximized = false);
  }

  @override
  void onWindowEnterFullScreen() {
    if (mounted) setState(() => _fullScreen = true);
  }

  @override
  void onWindowLeaveFullScreen() {
    if (mounted) setState(() => _fullScreen = false);
  }

  @override
  Widget build(BuildContext context) {
    final edgeToEdge = _maximized || _fullScreen;
    final radius = BorderRadius.circular(edgeToEdge ? 0 : 14);
    return DragToResizeArea(
      enableResizeEdges: edgeToEdge ? const [] : ResizeEdge.values,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        curve: Curves.easeOutCubic,
        decoration: BoxDecoration(
          color: YunGetColors.paper,
          borderRadius: radius,
          border: Border.all(
            color: edgeToEdge
                ? Colors.transparent
                : YunGetColors.ink.withValues(alpha: 0.24),
            width: 1.2,
          ),
          boxShadow: edgeToEdge
              ? null
              : [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.22),
                    blurRadius: 32,
                    offset: const Offset(0, 10),
                  ),
                ],
        ),
        child: ClipRRect(borderRadius: radius, child: widget.child),
      ),
    );
  }
}
