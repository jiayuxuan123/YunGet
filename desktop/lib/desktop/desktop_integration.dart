import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:local_notifier/local_notifier.dart';
import 'package:tray_manager/tray_manager.dart';
import 'package:window_manager/window_manager.dart';

import '../app_state.dart';
import '../host/models.dart';

/// Windows 外壳能力：托盘、关闭到托盘、任务通知。
///
/// 业务状态仍只在 [AppState]；这里只是一个生命周期适配层，避免把
/// window/tray 插件散落到每个页面里。
class DesktopIntegration with WindowListener {
  DesktopIntegration({required this.state});

  final AppState state;
  TrayIcon? _tray;
  Menu? _menu;
  final List<MenuItem> _menuItems = [];
  final List<LocalNotification> _notifications = [];
  Timer? _trayTooltipTimer;
  String? _lastTrayTooltip;
  DateTime _lastTrayTooltipAt = DateTime.fromMillisecondsSinceEpoch(0);
  bool _initialized = false;
  bool _quitting = false;

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;
    windowManager.addListener(this);
    await windowManager.setPreventClose(true);
    state.addListener(_onStateChanged);

    try {
      await localNotifier.setup(appName: 'YunGet');
    } catch (error) {
      debugPrint('[desktop] 通知初始化失败: $error');
    }

    await _createTray();
  }

  Future<void> _createTray() async {
    try {
      final tray = TrayIcon.create();
      if (tray == null) return;
      tray.icon = ImageAsset.fromAsset('windows/runner/resources/app_icon.ico');
      tray.setTooltip('云取 YunGet');
      tray.setContextMenuTrigger(ContextMenuTrigger.rightClicked);

      final menu = Menu.create();
      if (menu == null) {
        tray.dispose();
        return;
      }
      final show = MenuItem.createWithLabelAndType(
        '打开主界面',
        MenuItemType.normal,
      )!;
      final pauseAll = MenuItem.createWithLabelAndType(
        '暂停全部任务',
        MenuItemType.normal,
      )!;
      final resumeAll = MenuItem.createWithLabelAndType(
        '继续全部任务',
        MenuItemType.normal,
      )!;
      final openDir = MenuItem.createWithLabelAndType(
        '打开下载目录',
        MenuItemType.normal,
      )!;
      final exit = MenuItem.createWithLabelAndType(
        '退出 YunGet',
        MenuItemType.normal,
      )!;

      show.addListener((event) {
        if (event is MenuItemClickedEvent) unawaited(showWindow());
      });
      pauseAll.addListener((event) {
        if (event is MenuItemClickedEvent) unawaited(state.pauseAll());
      });
      resumeAll.addListener((event) {
        if (event is MenuItemClickedEvent) unawaited(state.resumeAll());
      });
      openDir.addListener((event) {
        if (event is MenuItemClickedEvent) {
          unawaited(state.openDownloadDirectory());
        }
      });
      exit.addListener((event) {
        if (event is MenuItemClickedEvent) unawaited(quit());
      });

      menu
        ..addItem(show)
        ..addSeparator()
        ..addItem(pauseAll)
        ..addItem(resumeAll)
        ..addItem(openDir)
        ..addSeparator()
        ..addItem(exit);

      tray.addListener((event) {
        if (event is TrayIconClickedEvent ||
            event is TrayIconDoubleClickedEvent) {
          unawaited(showWindow());
        }
      });
      tray.setContextMenu(menu);
      tray.setVisible(true);

      _tray = tray;
      _menu = menu;
      _menuItems.addAll([show, pauseAll, resumeAll, openDir, exit]);
      _onStateChanged();
    } catch (error) {
      debugPrint('[desktop] 托盘初始化失败: $error');
    }
  }

  void _onStateChanged() {
    final tray = _tray;
    if (tray == null) return;
    final elapsed = DateTime.now().difference(_lastTrayTooltipAt);
    if (elapsed >= const Duration(seconds: 1)) {
      _updateTrayTooltip(tray);
      return;
    }
    _trayTooltipTimer ??= Timer(const Duration(seconds: 1) - elapsed, () {
      _trayTooltipTimer = null;
      final current = _tray;
      if (current != null) _updateTrayTooltip(current);
    });
  }

  void _updateTrayTooltip(TrayIcon tray) {
    _lastTrayTooltipAt = DateTime.now();
    final active = state.activeTaskCount;
    final speed = state.totalSpeed;
    final summary = active == 0
        ? '云取 YunGet · 空闲'
        : '云取 YunGet · $active 个任务 · ${formatSpeed(speed)}';
    if (summary != _lastTrayTooltip) {
      _lastTrayTooltip = summary;
      tray.setTooltip(summary);
    }
  }

  void notifyTask(DownloadTask task) {
    final done = task.isDone;
    final enabled = done
        ? (state.settings?.notifyOnComplete ?? true)
        : (state.settings?.notifyOnFailure ?? true);
    if (!enabled) return;

    final notification = LocalNotification(
      title: done ? '下载完成' : '下载失败',
      body: done ? task.fileName : '${task.fileName}\n${task.error ?? '未知错误'}',
    );
    notification.onClick = () => unawaited(showWindow());
    notification.onClose = (_) => _notifications.remove(notification);
    notification.show();
    _notifications.add(notification);
    if (_notifications.length > 20) {
      _notifications.removeAt(0);
    }
  }

  Future<void> showWindow() async {
    await windowManager.show();
    if (await windowManager.isMinimized()) {
      await windowManager.restore();
    }
    await windowManager.focus();
  }

  Future<void> hideToTray() async {
    await windowManager.hide();
  }

  Future<void> quit() async {
    if (_quitting) return;
    _quitting = true;
    _trayTooltipTimer?.cancel();
    await state.shutdown();
    try {
      _tray?.setVisible(false);
      _tray?.dispose();
      _menu?.dispose();
    } catch (_) {}
    _tray = null;
    _menu = null;
    await windowManager.destroy();
  }

  @override
  void onWindowClose() {
    if (_quitting) return;
    final closeToTray = state.settings?.closeToTray ?? true;
    if (closeToTray) {
      unawaited(hideToTray());
    } else {
      unawaited(quit());
    }
  }

  void dispose() {
    _trayTooltipTimer?.cancel();
    state.removeListener(_onStateChanged);
    windowManager.removeListener(this);
    try {
      _tray?.setVisible(false);
      _tray?.dispose();
      _menu?.dispose();
    } catch (_) {}
    _tray = null;
    _menu = null;
    _menuItems.clear();
  }
}
