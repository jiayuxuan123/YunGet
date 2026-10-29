import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// 引擎宿主的客户端：拉起子进程，按行收发 JSON。
///
/// 为什么是子进程而不是本地 HTTP 服务：不需要监听端口，也就没有防火墙弹窗与
/// "别的程序也能连上来"的问题；父进程退出时管道断开，宿主自己就结束了。
class HostClient {
  HostClient._(this._process);

  final Process _process;
  final Map<String, Completer<Object?>> _pending = {};
  final StreamController<Map<String, dynamic>> _events =
      StreamController<Map<String, dynamic>>.broadcast();
  int _nextId = 1;
  bool _closed = false;

  /// 宿主主动推的事件（`task` 进度、`log`）。
  Stream<Map<String, dynamic>> get events => _events.stream;

  bool get isRunning => !_closed;

  /// 拉起宿主。jar 的查找顺序：环境变量 → 可执行文件旁的 host/ → 开发目录。
  static Future<HostClient> start() async {
    final jar = _locateJar();
    if (jar == null) {
      throw StateError('找不到 yunget-host.jar（可设置环境变量 YUNGET_HOST_JAR 指定路径）');
    }
    final java = _locateJava();
    final process = await Process.start(java, ['-jar', jar], runInShell: false);
    final client = HostClient._(process);
    client._pump();
    return client;
  }

  static String? _locateJar() {
    final fromEnv = Platform.environment['YUNGET_HOST_JAR'];
    if (fromEnv != null && File(fromEnv).existsSync()) return fromEnv;

    final exeDir = File(Platform.resolvedExecutable).parent;
    final bundled = File('${exeDir.path}${Platform.pathSeparator}host${Platform.pathSeparator}yunget-host.jar');
    if (bundled.existsSync()) return bundled.path;

    // 开发期：从 build/windows/x64/runner/Release/ 往上六层才是 YunGet/，
    // 再到 host/build/libs。注意必须**相对可执行文件**解析 —— 相对当前工作目录
    // 的话，从别处启动就找不到 jar（第一次运行时就是这么失败的）。
    final base = File(Platform.resolvedExecutable).parent.path;
    for (final rel in const [
      '../../../../../../host/build/libs/yunget-host.jar',
      '../../host/build/libs/yunget-host.jar',
    ]) {
      final f = File('$base${Platform.pathSeparator}$rel');
      if (f.existsSync()) return f.absolute.path;
    }
    return null;
  }

  /// 优先 javaw：它是 GUI 子系统程序，不会在屏幕上闪一个黑框。
  static String _locateJava() {
    final exeDir = File(Platform.resolvedExecutable).parent;
    final bundled = File('${exeDir.path}${Platform.pathSeparator}jre${Platform.pathSeparator}bin${Platform.pathSeparator}javaw.exe');
    if (bundled.existsSync()) return bundled.path;
    return Platform.isWindows ? 'javaw' : 'java';
  }

  void _pump() {
    _process.stdout
        .transform(const Utf8Decoder(allowMalformed: true))
        .transform(const LineSplitter())
        .listen(_onLine, onError: (Object _) {}, onDone: _onDone);
    // 宿主的 stderr 不打进协议，但保留给调试：仅打印到控制台。
    _process.stderr.transform(utf8.decoder).listen((line) {
      if (line.trim().isNotEmpty) stderr.writeln('[host] $line');
    });
    _process.exitCode.then((_) => _onDone());
  }

  void _onLine(String line) {
    if (line.trim().isEmpty) return;
    final Object? decoded;
    try {
      decoded = jsonDecode(line);
    } catch (_) {
      return;
    }
    if (decoded is! Map<String, dynamic>) return;

    final id = decoded['id'];
    if (id is String && _pending.containsKey(id)) {
      final completer = _pending.remove(id)!;
      if (decoded['ok'] == true) {
        completer.complete(decoded['result']);
      } else {
        final error = decoded['error'];
        final message = error is Map ? (error['message'] ?? '未知错误') : '未知错误';
        completer.completeError(HostException(message.toString()));
      }
      return;
    }
    _events.add(decoded);
  }

  void _onDone() {
    if (_closed) return;
    _closed = true;
    for (final completer in _pending.values) {
      if (!completer.isCompleted) {
        completer.completeError(HostException('引擎进程已退出'));
      }
    }
    _pending.clear();
    _events.close();
  }

  /// 返回值可能是对象或数组（`tasks`/`list` 就是数组），所以是 `Object?`。
  Future<Object?> call(String method, [Map<String, dynamic>? params]) async {
    if (_closed) return Future.error(HostException('引擎进程已退出'));
    final id = '${_nextId++}';
    final completer = Completer<Object?>();
    _pending[id] = completer;
    _process.stdin.writeln(jsonEncode({'id': id, 'method': method, 'params': params ?? {}}));
    // 【必须 flush】进程的 stdin 是带缓冲的 IOSink，只 writeln 不 flush 时请求会一直
    // 留在缓冲区里，宿主一个字节都收不到 —— 表现是"界面点了解析没反应、也没有报错"。
    // 命令行手工测试用管道喂输入时新行会自动刷出，恰好把这个坑盖住了。
    await _process.stdin.flush();
    return completer.future;
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    try {
      await _process.stdin.flush();
      await _process.stdin.close();
    } catch (_) {}
    // 给宿主 3 秒把任务标回暂停并关引擎，超时再强杀。
    final exited = await _process.exitCode.timeout(
      const Duration(seconds: 3),
      onTimeout: () {
        _process.kill();
        return -1;
      },
    );
    _closed = true;
    if (exited == -1) return;
  }
}

class HostException implements Exception {
  HostException(this.message);
  final String message;
  @override
  String toString() => message;
}
