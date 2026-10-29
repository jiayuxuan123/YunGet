import 'package:flutter/material.dart';

import 'app_state.dart';
import 'screens/downloads_screen.dart';
import 'screens/resolve_screen.dart';
import 'screens/settings_screen.dart';

void main() {
  runApp(const YunGetApp());
}

class YunGetApp extends StatefulWidget {
  const YunGetApp({super.key});

  @override
  State<YunGetApp> createState() => _YunGetAppState();
}

class _YunGetAppState extends State<YunGetApp> {
  final AppState _state = AppState();
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    _state.start();
  }

  @override
  void dispose() {
    _state.shutdown();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '云取 YunGet',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF3B6EF6)),
        useMaterial3: true,
      ),
      home: AnimatedBuilder(
        animation: _state,
        builder: (context, _) {
          if (_state.startupError != null) {
            return Scaffold(
              body: Center(
                child: Padding(
                  padding: const EdgeInsets.all(32),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.error_outline, size: 48),
                      const SizedBox(height: 12),
                      const Text('下载引擎启动失败'),
                      const SizedBox(height: 8),
                      SelectableText(_state.startupError!, style: Theme.of(context).textTheme.bodySmall),
                    ],
                  ),
                ),
              ),
            );
          }
          return Scaffold(
            appBar: AppBar(
              title: const Text('云取 YunGet'),
              actions: [
                if (!_state.hostReady)
                  const Padding(
                    padding: EdgeInsets.only(right: 16),
                    child: Center(child: Text('引擎启动中…')),
                  ),
              ],
            ),
            body: Row(
              children: [
                NavigationRail(
                  selectedIndex: _tab,
                  onDestinationSelected: (i) => setState(() => _tab = i),
                  labelType: NavigationRailLabelType.all,
                  destinations: const [
                    NavigationRailDestination(icon: Icon(Icons.link), label: Text('解析')),
                    NavigationRailDestination(icon: Icon(Icons.download), label: Text('下载')),
                    NavigationRailDestination(icon: Icon(Icons.settings), label: Text('设置')),
                  ],
                ),
                const VerticalDivider(width: 1),
                Expanded(
                  child: switch (_tab) {
                    0 => ResolveScreen(state: _state, onOpenDownloads: () => setState(() => _tab = 1)),
                    1 => DownloadsScreen(state: _state),
                    _ => SettingsScreen(state: _state),
                  },
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
