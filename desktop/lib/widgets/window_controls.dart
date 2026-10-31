import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:window_manager/window_manager.dart';

/// A compact Windows-style title bar control group.
///
/// The widget owns only window chrome behavior. Closing delegates to
/// [WindowManager.close], so tray or application lifecycle policy remains in the
/// surrounding desktop integration layer.
class WindowControls extends StatefulWidget {
  const WindowControls({
    super.key,
    this.backgroundColor = Colors.transparent,
    this.iconColor = const Color(0xFF4C5661),
    this.hoverColor = const Color(0x1418212A),
    this.pressedColor = const Color(0x2418212A),
    this.focusColor = const Color(0xFFE85D3F),
    this.closeIconColor,
    this.closeHoverColor = const Color(0xFFC42B1C),
    this.closePressedColor = const Color(0xFF9C2116),
    this.closeHoverIconColor = Colors.white,
    this.closePressedIconColor = Colors.white,
    this.minimizeTooltip = '最小化',
    this.maximizeTooltip = '最大化',
    this.restoreTooltip = '还原',
    this.closeTooltip = '关闭',
    this.animationDuration = const Duration(milliseconds: 120),
  });

  /// Background of each button in its idle state.
  final Color backgroundColor;

  /// Foreground color shared by minimize and maximize controls.
  final Color iconColor;

  /// Background color while a regular control is hovered or focused.
  final Color hoverColor;

  /// Background color while a regular control is pressed.
  final Color pressedColor;

  /// Border color used to indicate keyboard focus.
  final Color focusColor;

  /// Foreground color of the close control in its idle state.
  final Color? closeIconColor;

  /// Close-control background while hovered.
  final Color closeHoverColor;

  /// Close-control background while pressed.
  final Color closePressedColor;

  /// Close-control foreground while hovered.
  final Color closeHoverIconColor;

  /// Close-control foreground while pressed.
  final Color closePressedIconColor;

  final String minimizeTooltip;
  final String maximizeTooltip;
  final String restoreTooltip;
  final String closeTooltip;

  /// Duration shared by hover, press, focus, and icon transitions.
  final Duration animationDuration;

  @override
  State<WindowControls> createState() => _WindowControlsState();
}

class _WindowControlsState extends State<WindowControls> with WindowListener {
  static const _buttonWidth = 46.0;
  static const _buttonHeight = 44.0;

  bool _isMaximized = false;
  bool _maximizeBusy = false;
  int _maximizeOperation = 0;
  int _maximizeStateRequest = 0;

  @override
  void initState() {
    super.initState();
    windowManager.addListener(this);
    unawaited(_refreshMaximizedState());
  }

  @override
  void dispose() {
    _maximizeOperation++;
    _maximizeStateRequest++;
    windowManager.removeListener(this);
    super.dispose();
  }

  @override
  void onWindowMaximize() {
    _applyMaximizedEvent(true);
  }

  @override
  void onWindowUnmaximize() {
    _applyMaximizedEvent(false);
  }

  @override
  void onWindowRestore() {
    unawaited(_refreshMaximizedState());
  }

  void _applyMaximizedEvent(bool isMaximized) {
    // A native event is newer than any query that may still be in flight.
    _maximizeStateRequest++;
    _setMaximized(isMaximized);
  }

  void _setMaximized(bool value) {
    if (!mounted || _isMaximized == value) return;
    setState(() => _isMaximized = value);
  }

  Future<void> _refreshMaximizedState() async {
    final request = ++_maximizeStateRequest;
    try {
      final isMaximized = await windowManager.isMaximized();
      if (!mounted || request != _maximizeStateRequest) return;
      _setMaximized(isMaximized);
    } catch (error) {
      debugPrint('[window_controls] 读取最大化状态失败: $error');
    }
  }

  Future<void> _toggleMaximize() async {
    if (_maximizeBusy || !mounted) return;

    final operation = ++_maximizeOperation;
    setState(() => _maximizeBusy = true);

    try {
      // Read from the native window instead of trusting a potentially stale
      // event snapshot, then reconcile again after the command completes.
      final isMaximized = await windowManager.isMaximized();
      if (!mounted || operation != _maximizeOperation) return;

      if (isMaximized) {
        await windowManager.unmaximize();
      } else {
        await windowManager.maximize();
      }

      if (!mounted || operation != _maximizeOperation) return;
      await _refreshMaximizedState();
    } catch (error) {
      debugPrint('[window_controls] 切换最大化状态失败: $error');
      if (mounted && operation == _maximizeOperation) {
        await _refreshMaximizedState();
      }
    } finally {
      if (mounted && operation == _maximizeOperation) {
        setState(() => _maximizeBusy = false);
      }
    }
  }

  void _minimize() {
    unawaited(_runWindowCommand(windowManager.minimize));
  }

  void _close() {
    unawaited(_runWindowCommand(windowManager.close));
  }

  Future<void> _runWindowCommand(Future<void> Function() command) async {
    try {
      await command();
    } catch (error) {
      debugPrint('[window_controls] 窗口操作失败: $error');
    }
  }

  @override
  Widget build(BuildContext context) {
    final regularIconColor = widget.iconColor;
    final closeIconColor = widget.closeIconColor ?? widget.iconColor;
    final focusBorderColor = widget.focusColor.withValues(alpha: 0.72);

    return SizedBox(
      height: _buttonHeight,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _WindowControlButton(
            width: _buttonWidth,
            height: _buttonHeight,
            tooltip: widget.minimizeTooltip,
            icon: Icons.remove_rounded,
            iconColor: regularIconColor,
            backgroundColor: widget.backgroundColor,
            hoverColor: widget.hoverColor,
            pressedColor: widget.pressedColor,
            focusBorderColor: focusBorderColor,
            animationDuration: widget.animationDuration,
            onPressed: _minimize,
          ),
          _WindowControlButton(
            width: _buttonWidth,
            height: _buttonHeight,
            tooltip: _isMaximized
                ? widget.restoreTooltip
                : widget.maximizeTooltip,
            icon: _isMaximized
                ? Icons.filter_none_rounded
                : Icons.crop_square_rounded,
            iconColor: regularIconColor,
            backgroundColor: widget.backgroundColor,
            hoverColor: widget.hoverColor,
            pressedColor: widget.pressedColor,
            focusBorderColor: focusBorderColor,
            animationDuration: widget.animationDuration,
            onPressed: _maximizeBusy
                ? null
                : () => unawaited(_toggleMaximize()),
          ),
          _WindowControlButton(
            width: _buttonWidth,
            height: _buttonHeight,
            tooltip: widget.closeTooltip,
            icon: Icons.close_rounded,
            iconColor: closeIconColor,
            hoverIconColor: widget.closeHoverIconColor,
            pressedIconColor: widget.closePressedIconColor,
            backgroundColor: widget.backgroundColor,
            hoverColor: widget.closeHoverColor,
            pressedColor: widget.closePressedColor,
            focusBorderColor: focusBorderColor,
            animationDuration: widget.animationDuration,
            onPressed: _close,
          ),
        ],
      ),
    );
  }
}

class _WindowControlButton extends StatefulWidget {
  const _WindowControlButton({
    required this.width,
    required this.height,
    required this.tooltip,
    required this.icon,
    required this.iconColor,
    required this.backgroundColor,
    required this.hoverColor,
    required this.pressedColor,
    required this.focusBorderColor,
    required this.animationDuration,
    required this.onPressed,
    this.hoverIconColor,
    this.pressedIconColor,
  });

  final double width;
  final double height;
  final String tooltip;
  final IconData icon;
  final Color iconColor;
  final Color backgroundColor;
  final Color hoverColor;
  final Color pressedColor;
  final Color focusBorderColor;
  final Duration animationDuration;
  final VoidCallback? onPressed;
  final Color? hoverIconColor;
  final Color? pressedIconColor;

  @override
  State<_WindowControlButton> createState() => _WindowControlButtonState();
}

class _WindowControlButtonState extends State<_WindowControlButton> {
  bool _hovered = false;
  bool _pressed = false;
  bool _focused = false;

  bool get _enabled => widget.onPressed != null;

  Color get _backgroundColor {
    if (!_enabled) return widget.backgroundColor;
    if (_pressed) return widget.pressedColor;
    if (_hovered || _focused) return widget.hoverColor;
    return widget.backgroundColor;
  }

  Color get _iconColor {
    if (!_enabled) return widget.iconColor.withValues(alpha: 0.38);
    if (_pressed) {
      return widget.pressedIconColor ?? widget.iconColor;
    }
    if (_hovered) {
      return widget.hoverIconColor ?? widget.iconColor;
    }
    return widget.iconColor;
  }

  void _setHovered(bool value) {
    if (!mounted) return;
    final nextPressed = value ? _pressed : false;
    if (_hovered == value && _pressed == nextPressed) return;
    setState(() {
      _hovered = value;
      _pressed = nextPressed;
    });
  }

  void _setPressed(bool value) {
    if (!mounted || !_enabled || _pressed == value) return;
    setState(() => _pressed = value);
  }

  void _setFocused(bool value) {
    if (!mounted || _focused == value) return;
    setState(() => _focused = value);
  }

  void _activate() {
    widget.onPressed?.call();
  }

  @override
  Widget build(BuildContext context) {
    final focusBorder = _focused && _enabled
        ? Border.all(color: widget.focusBorderColor, width: 1.25)
        : Border.all(color: Colors.transparent, width: 1.25);

    return Tooltip(
      message: widget.tooltip,
      waitDuration: const Duration(milliseconds: 450),
      exitDuration: const Duration(milliseconds: 80),
      excludeFromSemantics: true,
      child: FocusableActionDetector(
        enabled: _enabled,
        mouseCursor: _enabled
            ? SystemMouseCursors.click
            : SystemMouseCursors.basic,
        onShowHoverHighlight: _setHovered,
        onShowFocusHighlight: _setFocused,
        shortcuts: const <ShortcutActivator, Intent>{
          SingleActivator(LogicalKeyboardKey.enter): ActivateIntent(),
          SingleActivator(LogicalKeyboardKey.space): ActivateIntent(),
        },
        actions: <Type, Action<Intent>>{
          ActivateIntent: CallbackAction<ActivateIntent>(
            onInvoke: (_) {
              _activate();
              return null;
            },
          ),
        },
        child: Semantics(
          button: true,
          enabled: _enabled,
          label: widget.tooltip,
          onTap: _enabled ? _activate : null,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: _enabled ? (_) => _setPressed(true) : null,
            onTapUp: _enabled ? (_) => _setPressed(false) : null,
            onTapCancel: _enabled ? () => _setPressed(false) : null,
            onTap: _enabled ? _activate : null,
            child: AnimatedContainer(
              duration: widget.animationDuration,
              curve: Curves.easeOutCubic,
              width: widget.width,
              height: widget.height,
              decoration: BoxDecoration(
                color: _backgroundColor,
                border: focusBorder,
                borderRadius: BorderRadius.circular(7),
              ),
              child: Center(
                child: AnimatedSwitcher(
                  duration: widget.animationDuration,
                  reverseDuration: widget.animationDuration,
                  switchInCurve: Curves.easeOutCubic,
                  switchOutCurve: Curves.easeInCubic,
                  transitionBuilder: (child, animation) {
                    final curved = CurvedAnimation(
                      parent: animation,
                      curve: Curves.easeOutCubic,
                    );
                    return FadeTransition(
                      opacity: curved,
                      child: ScaleTransition(
                        scale: Tween<double>(
                          begin: 0.88,
                          end: 1,
                        ).animate(curved),
                        child: child,
                      ),
                    );
                  },
                  child: Icon(
                    widget.icon,
                    key: ValueKey<IconData>(widget.icon),
                    size: 17,
                    color: _iconColor,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
