import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'host/host_client.dart';
import 'host/models.dart';

/// 应用状态：宿主生命周期、任务表、设置、当前解析会话。
///
/// 刻意不引入状态管理库：这里只有"一份列表 + 一次设置"，ChangeNotifier 足够，
/// 少一个依赖就少一处版本冲突。
class AppState extends ChangeNotifier {
  AppState();

  HostClient? _host;
  StreamSubscription<Map<String, dynamic>>? _events;
  String? _startupError;

  final List<DownloadTask> _tasks = [];
  HostSettings? _settings;
  String? _sessionId;
  String _sessionTitle = '';
  bool _sessionIsFolder = false;
  List<ShareFile> _files = const [];
  String _currentDir = '';
  final List<String> _dirStack = [];
  final List<String> _dirNames = [];
  bool _busy = false;

  List<DownloadTask> get tasks => List.unmodifiable(_tasks);
  HostSettings? get settings => _settings;
  String? get startupError => _startupError;
  String? get sessionId => _sessionId;
  String get sessionTitle => _sessionTitle;
  bool get sessionIsFolder => _sessionIsFolder;
  List<ShareFile> get files => _files;
  bool get busy => _busy;
  String get currentDirName => _dirNames.isEmpty ? '根目录' : _dirNames.last;
  bool get canGoUp => _dirStack.isNotEmpty;
  bool get hostReady => _host != null;

  Future<void> start() async {
    try {
      _host = await HostClient.start();
      _events = _host!.events.listen(_onEvent);
      _settings = HostSettings.fromJson(_asMap(await _host!.call('settings')));
      await refreshTasks();
    } catch (e) {
      _startupError = e.toString();
      // 同时打到 stderr：GUI 里那行字容易被忽略，从命令行启动时这里能直接看到原因。
      stderr.writeln('[yunget] 引擎启动失败: $e');
    }
    notifyListeners();
  }

  Future<void> shutdown() async {
    await _events?.cancel();
    await _host?.close();
    _host = null;
  }

  void _onEvent(Map<String, dynamic> event) {
    final kind = event['event'];
    if (kind == 'task') {
      final raw = event['task'];
      if (raw is Map<String, dynamic>) {
        final task = DownloadTask.fromJson(raw);
        final index = _tasks.indexWhere((t) => t.id == task.id);
        if (index >= 0) {
          _tasks[index] = task;
        } else {
          _tasks.insert(0, task);
        }
        notifyListeners();
      }
    } else if (kind == 'log') {
      debugPrint('[host] ${event['message']}');
    }
  }

  Future<void> refreshTasks() async {
    final host = _host;
    if (host == null) return;
    final list = await host.call('tasks');
    _tasks.clear();
    if (list is List) {
      for (final item in list) {
        if (item is Map<String, dynamic>) _tasks.add(DownloadTask.fromJson(item));
      }
    }
    notifyListeners();
  }

  // ---------------------------------------------------------------- 解析

  Future<void> resolve(String link, String pwd) async {
    final host = _host;
    if (host == null) throw HostException('引擎未启动');
    _busy = true;
    notifyListeners();
    try {
      final result = _asMap(await host.call('resolve', {'link': link, 'pwd': pwd}));
      _sessionId = (result['session'] ?? '').toString();
      _sessionTitle = (result['title'] ?? '').toString();
      _sessionIsFolder = result['isFolder'] == true;
      _dirStack.clear();
      _dirNames.clear();
      _currentDir = '';
      await _loadFiles();
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  /// 解析入口：先按分享链接处理；识别不了且看起来是 http(s) 地址时，直接当直链下载。
  ///
  /// 手机端也是这个策略（分享解析失败则尝试直链）。桌面端新装时最容易被拿来试的
  /// 就是一条直链，把它做成自然行为比让用户猜"这里只支持网盘"更合适。
  Future<bool> resolveOrDirect(String link, String pwd) async {
    try {
      await resolve(link, pwd);
      return false;
    } catch (e) {
      final looksDirect = link.startsWith('http://') || link.startsWith('https://');
      if (!looksDirect) rethrow;
      final host = _host;
      if (host == null) throw HostException('引擎未启动');
      final created = _asMap(await host.call('download', {'url': link}));
      final task = DownloadTask.fromJson(created);
      if (_tasks.every((t) => t.id != task.id)) _tasks.insert(0, task);
      notifyListeners();
      return true;
    }
  }

  Future<void> _loadFiles() async {
    final host = _host;
    final session = _sessionId;
    if (host == null || session == null) return;
    final list = await host.call('list', {'session': session, 'dir': _currentDir});
    final files = <ShareFile>[];
    if (list is List) {
      for (final item in list) {
        if (item is Map<String, dynamic>) files.add(ShareFile.fromJson(item));
      }
    }
    _files = files;
    notifyListeners();
  }

  Future<void> openFolder(ShareFile folder) async {
    _dirStack.add(_currentDir);
    _dirNames.add(folder.name);
    _currentDir = folder.fid;
    await _loadFiles();
  }

  Future<void> goUp() async {
    if (_dirStack.isEmpty) return;
    _currentDir = _dirStack.removeLast();
    if (_dirNames.isNotEmpty) _dirNames.removeLast();
    await _loadFiles();
  }

  /// 取直链并提交下载。返回新建任务的 id。
  Future<int> downloadFile(ShareFile file) async {
    final host = _host;
    final session = _sessionId;
    if (host == null || session == null) throw HostException('请先解析链接');
    final link = DirectLink.fromJson(_asMap(await host.call('directLink', {'session': session, 'file': file.fid})));
    final created = _asMap(await host.call('download', {
      'url': link.url,
      'fileName': link.fileName.isNotEmpty ? link.fileName : file.name,
      'size': link.size,
      'headers': link.headers,
    }));
    final task = DownloadTask.fromJson(created);
    if (_tasks.every((t) => t.id != task.id)) _tasks.insert(0, task);
    notifyListeners();
    return task.id;
  }

  // ---------------------------------------------------------------- 下载控制

  Future<void> pauseTask(int id) async => _host?.call('pause', {'task': id});
  Future<void> resumeTask(int id) async => _host?.call('resume', {'task': id});
  Future<void> cancelTask(int id) async {
    await _host?.call('cancel', {'task': id});
    _tasks.removeWhere((t) => t.id == id);
    notifyListeners();
  }

  // ---------------------------------------------------------------- 设置

  Future<void> saveSettings(HostSettings next) async {
    final host = _host;
    if (host == null) return;
    _settings = HostSettings.fromJson(_asMap(await host.call('settings', next.toJson())));
    notifyListeners();
  }
}

/// 协议返回值可能是对象或数组，取对象时统一走这里，避免到处写强制转换。
Map<String, dynamic> _asMap(Object? value) =>
    value is Map<String, dynamic> ? value : const {};
