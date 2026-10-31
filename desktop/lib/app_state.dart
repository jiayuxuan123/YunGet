import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'host/host_client.dart';
import 'host/models.dart';

typedef TaskFinishedCallback = void Function(DownloadTask task);

/// 应用状态：宿主生命周期、任务表、设置、当前解析会话。
///
/// 刻意不引入状态管理库：这里只有"一份列表 + 一次设置"，ChangeNotifier 足够，
/// 少一个依赖就少一处版本冲突。
class AppState extends ChangeNotifier {
  AppState();

  HostClient? _host;
  StreamSubscription<Map<String, dynamic>>? _events;

  final List<DownloadTask> _tasks = [];
  HostSettings? _settings;
  String? _sessionId;
  String _sessionTitle = '';
  bool _sessionIsFolder = false;
  List<ShareFile> _files = const [];
  String _currentDir = '';
  final List<String> _dirStack = [];
  final List<String> _dirNames = [];
  final List<String> _recentLinks = [];
  bool _busy = false;

  /// 细粒度通知：任务进度不再把整个桌面外壳和三张页面一起重建。
  final ValueNotifier<int> taskRevision = ValueNotifier<int>(0);
  final ValueNotifier<int> taskSummaryRevision = ValueNotifier<int>(0);
  final ValueNotifier<int> sessionRevision = ValueNotifier<int>(0);
  final ValueNotifier<int> settingsRevision = ValueNotifier<int>(0);
  final ValueNotifier<int> hostRevision = ValueNotifier<int>(0);
  final ValueNotifier<String?> startupErrorListenable = ValueNotifier<String?>(
    null,
  );

  /// 完成/失败事件只从这里出一次，通知层不必自己比较任务状态。
  TaskFinishedCallback? onTaskFinished;

  List<DownloadTask> get tasks => List.unmodifiable(_tasks);
  HostSettings? get settings => _settings;
  String? get startupError => startupErrorListenable.value;
  String? get sessionId => _sessionId;
  String get sessionTitle => _sessionTitle;
  bool get sessionIsFolder => _sessionIsFolder;
  List<ShareFile> get files => _files;
  bool get busy => _busy;
  String get currentDirName => _dirNames.isEmpty ? '根目录' : _dirNames.last;
  bool get canGoUp => _dirStack.isNotEmpty;
  bool get hostReady => _host != null;
  List<String> get recentLinks => List.unmodifiable(_recentLinks);
  int get activeTaskCount => _tasks.where((task) => task.isActive).length;
  int get totalSpeed => _tasks.fold(0, (sum, task) => sum + task.speed);

  Future<void> start() async {
    try {
      _host = await HostClient.start();
      _events = _host!.events.listen(_onEvent);
      _settings = HostSettings.fromJson(_asMap(await _host!.call('settings')));
      hostRevision.value++;
      settingsRevision.value++;
      await refreshTasks();
    } catch (e) {
      startupErrorListenable.value = e.toString();
      // 同时打到 stderr：GUI 里那行字容易被忽略，从命令行启动时这里能直接看到原因。
      stderr.writeln('[yunget] 引擎启动失败: $e');
    }
    notifyListeners();
  }

  Future<void> retryStart() async {
    startupErrorListenable.value = null;
    notifyListeners();
    await start();
  }

  Future<void> shutdown() async {
    await _events?.cancel();
    await _host?.close();
    _host = null;
    hostRevision.value++;
  }

  void _onEvent(Map<String, dynamic> event) {
    final kind = event['event'];
    if (kind == 'task') {
      final raw = event['task'];
      if (raw is Map<String, dynamic>) {
        final task = DownloadTask.fromJson(raw);
        final index = _tasks.indexWhere((t) => t.id == task.id);
        final previous = index >= 0 ? _tasks[index] : null;
        if (index >= 0) {
          _tasks[index] = task;
        } else {
          _tasks.insert(0, task);
        }
        _taskChanged();
        final justFinished =
            (task.isDone && previous?.isDone != true) ||
            (task.isFailed && previous?.isFailed != true);
        if (justFinished) onTaskFinished?.call(task);
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
        if (item is Map<String, dynamic>) {
          _tasks.add(DownloadTask.fromJson(item));
        }
      }
    }
    _taskChanged();
  }

  // ---------------------------------------------------------------- 解析

  Future<void> resolve(String link, String pwd) async {
    final host = _host;
    if (host == null) throw HostException('引擎未启动');
    _busy = true;
    _sessionChanged();
    try {
      final result = _asMap(
        await host.call('resolve', {'link': link, 'pwd': pwd}),
      );
      _sessionId = (result['session'] ?? '').toString();
      _sessionTitle = (result['title'] ?? '').toString();
      _sessionIsFolder = result['isFolder'] == true;
      _dirStack.clear();
      _dirNames.clear();
      _currentDir = '';
      await _loadFiles();
      _rememberLink(link);
    } finally {
      _busy = false;
      _sessionChanged();
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
      final looksDirect =
          link.startsWith('http://') || link.startsWith('https://');
      if (!looksDirect) rethrow;
      final host = _host;
      if (host == null) throw HostException('引擎未启动');
      final created = _asMap(await host.call('download', {'url': link}));
      final task = DownloadTask.fromJson(created);
      if (_tasks.every((t) => t.id != task.id)) _tasks.insert(0, task);
      _taskChanged();
      _rememberLink(link);
      return true;
    }
  }

  Future<void> _loadFiles() async {
    final host = _host;
    final session = _sessionId;
    if (host == null || session == null) return;
    final list = await host.call('list', {
      'session': session,
      'dir': _currentDir,
    });
    final files = <ShareFile>[];
    if (list is List) {
      for (final item in list) {
        if (item is Map<String, dynamic>) files.add(ShareFile.fromJson(item));
      }
    }
    _files = files;
    _sessionChanged();
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
  Future<DirectLink> getDirectLink(ShareFile file) async {
    final host = _host;
    final session = _sessionId;
    if (host == null || session == null) throw HostException('请先解析链接');
    return DirectLink.fromJson(
      _asMap(
        await host.call('directLink', {'session': session, 'file': file.fid}),
      ),
    );
  }

  Future<int> downloadFile(ShareFile file) async {
    final host = _host;
    final session = _sessionId;
    if (host == null || session == null) throw HostException('请先解析链接');
    final link = DirectLink.fromJson(
      _asMap(
        await host.call('directLink', {'session': session, 'file': file.fid}),
      ),
    );
    final created = _asMap(
      await host.call('download', {
        'url': link.url,
        'fileName': link.fileName.isNotEmpty ? link.fileName : file.name,
        'size': link.size,
        'headers': link.headers,
      }),
    );
    final task = DownloadTask.fromJson(created);
    if (_tasks.every((t) => t.id != task.id)) _tasks.insert(0, task);
    _taskChanged();
    return task.id;
  }

  Future<TaskBatchResult> downloadFiles(Iterable<ShareFile> files) async {
    final targets = files.where((file) => !file.isDir).toList(growable: false);
    var started = 0;
    final failures = <String>[];
    for (final file in targets) {
      try {
        await downloadFile(file);
        started++;
      } catch (e) {
        failures.add('${file.name}: $e');
      }
    }
    return TaskBatchResult(started: started, failures: failures);
  }

  // ---------------------------------------------------------------- 下载控制

  Future<void> pauseTask(int id) async {
    final index = _tasks.indexWhere((t) => t.id == id);
    if (index >= 0) {
      _tasks[index] = _tasks[index].copyWith(
        status: 'PAUSED',
        speed: 0,
        connections: 0,
      );
      _taskChanged();
    }
    await _host?.call('pause', {'task': id});
  }

  Future<void> resumeTask(int id) async {
    final index = _tasks.indexWhere((t) => t.id == id);
    if (index >= 0) {
      _tasks[index] = _tasks[index].copyWith(
        status: 'RUNNING',
        error: null,
      );
      _taskChanged();
    }
    await _host?.call('resume', {'task': id});
  }

  Future<void> retryTask(int id) async => resumeTask(id);

  Future<void> cancelTask(int id) async {
    _tasks.removeWhere((t) => t.id == id);
    _taskChanged();
    await _host?.call('cancel', {'task': id});
  }

  Future<void> pauseAll() async {
    final active = _tasks
        .where((task) => task.isActive)
        .map((task) => task.id)
        .toList();
    if (active.isEmpty) return;
    for (var i = 0; i < _tasks.length; i++) {
      if (_tasks[i].isActive) {
        _tasks[i] = _tasks[i].copyWith(
          status: 'PAUSED',
          speed: 0,
          connections: 0,
        );
      }
    }
    _taskChanged();
    await Future.wait(active.map((id) => _host?.call('pause', {'task': id}) ?? Future.value()));
  }

  Future<void> resumeAll() async {
    final resumable = _tasks
        .where((task) => task.canResume)
        .map((task) => task.id)
        .toList();
    if (resumable.isEmpty) return;
    for (var i = 0; i < _tasks.length; i++) {
      if (_tasks[i].canResume) {
        _tasks[i] = _tasks[i].copyWith(status: 'RUNNING', error: null);
      }
    }
    _taskChanged();
    await Future.wait(resumable.map((id) => _host?.call('resume', {'task': id}) ?? Future.value()));
  }

  Future<void> removeTasks(Iterable<int> ids) async {
    final idSet = ids.toSet();
    if (idSet.isEmpty) return;
    _tasks.removeWhere((t) => idSet.contains(t.id));
    _taskChanged();
    await Future.wait(idSet.map((id) => _host?.call('cancel', {'task': id}) ?? Future.value()));
  }

  Future<void> openDownloadDirectory([String? path]) async {
    final target = path?.trim().isNotEmpty == true
        ? path!
        : (_settings?.downloadDir ?? '');
    if (target.isEmpty) throw StateError('尚未设置下载目录');
    await Directory(target).create(recursive: true);
    await Process.run('explorer', [target], runInShell: false);
  }

  Future<void> revealTask(DownloadTask task) async {
    await Process.run('explorer', [
      '/select,${task.destPath}',
    ], runInShell: false);
  }

  Future<void> openTask(DownloadTask task) async {
    await Process.run('explorer', [task.destPath], runInShell: false);
  }

  // ---------------------------------------------------------------- 设置

  Future<void> saveSettings(HostSettings next) async {
    final host = _host;
    if (host == null) return;
    _settings = HostSettings.fromJson(
      _asMap(await host.call('settings', next.toJson())),
    );
    settingsRevision.value++;
    notifyListeners();
  }

  void _rememberLink(String link) {
    _recentLinks.remove(link);
    _recentLinks.insert(0, link);
    if (_recentLinks.length > 6) {
      _recentLinks.removeRange(6, _recentLinks.length);
    }
    _sessionChanged();
  }

  void _taskChanged() {
    taskRevision.value++;
    taskSummaryRevision.value++;
    notifyListeners();
  }

  void _sessionChanged() {
    sessionRevision.value++;
    notifyListeners();
  }
}

/// 协议返回值可能是对象或数组，取对象时统一走这里，避免到处写强制转换。
Map<String, dynamic> _asMap(Object? value) =>
    value is Map<String, dynamic> ? value : const {};

class TaskBatchResult {
  TaskBatchResult({required this.started, required this.failures});

  final int started;
  final List<String> failures;
}
