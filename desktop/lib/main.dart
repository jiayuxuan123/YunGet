import 'dart:async';

import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';

import 'app_state.dart';
import 'desktop/desktop_integration.dart';
import 'theme.dart';
import 'widgets/app_shell.dart';
import 'widgets/desktop_mark.dart';
import 'widgets/window_surface.dart';

Future<void> main(List<String> arguments) async {
  WidgetsFlutterBinding.ensureInitialized();
  await windowManager.ensureInitialized();
  await windowManager.setAsFrameless();

  final startMinimized = arguments.contains('--minimized');
  const options = WindowOptions(
    size: Size(1280, 780),
    minimumSize: Size(980, 640),
    center: true,
    backgroundColor: Colors.transparent,
    title: '云取 YunGet',
    titleBarStyle: TitleBarStyle.hidden,
  );
  // 这一步不能 await：它要等 Flutter 第一帧结束才回调，而 runApp 还没执行时
  // 第一帧永远不会来，窗口会一直隐藏。
  unawaited(
    windowManager.waitUntilReadyToShow(options, () async {
      await windowManager.setTitleBarStyle(
        TitleBarStyle.hidden,
        windowButtonVisibility: false,
      );
      if (startMinimized) {
        await windowManager.hide();
      } else {
        await windowManager.show();
        await windowManager.focus();
      }
    }),
  );

  runApp(const YunGetApp());
}

class YunGetApp extends StatefulWidget {
  const YunGetApp({super.key});

  @override
  State<YunGetApp> createState() => _YunGetAppState();
}

class _YunGetAppState extends State<YunGetApp> {
  final AppState _state = AppState();
  late final DesktopIntegration _desktop = DesktopIntegration(state: _state);

  @override
  void initState() {
    super.initState();
    _state.onTaskFinished = _desktop.notifyTask;
    unawaited(_desktop.initialize());
    unawaited(_state.start());
  }

  @override
  void dispose() {
    _desktop.dispose();
    unawaited(_state.shutdown());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '云取 YunGet',
      debugShowCheckedModeBanner: false,
      theme: buildYunGetTheme(),
      builder: (context, child) =>
          WindowSurface(child: child ?? const SizedBox.shrink()),
      home: ValueListenableBuilder<String?>(
        valueListenable: _state.startupErrorListenable,
        builder: (context, startupError, _) {
          if (startupError != null) {
            return _StartupError(state: _state);
          }
          return AppShell(state: _state, onQuit: _desktop.quit);
        },
      ),
    );
  }
}

class _StartupError extends StatelessWidget {
  const _StartupError({required this.state});

  final AppState state;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [YunGetColors.paper, YunGetColors.paperDeep],
          ),
        ),
        child: Center(
          child: Container(
            width: 620,
            padding: const EdgeInsets.all(28),
            decoration: BoxDecoration(
              color: YunGetColors.surface,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: YunGetColors.line),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Row(
                  children: [
                    DesktopMark(size: 42),
                    SizedBox(width: 14),
                    Text(
                      '下载引擎没有启动',
                      style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 18),
                const Text('请确认安装目录中的 host/yunget-host.jar 与精简 JRE 没有被安全软件隔离。'),
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: YunGetColors.accentSoft,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: SelectableText(
                    state.startupError!,
                    style: const TextStyle(fontSize: 12, height: 1.5),
                  ),
                ),
                const SizedBox(height: 20),
                Align(
                  alignment: Alignment.centerRight,
                  child: FilledButton.icon(
                    onPressed: () => unawaited(state.retryStart()),
                    icon: const Icon(Icons.refresh),
                    label: const Text('重新启动引擎'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
