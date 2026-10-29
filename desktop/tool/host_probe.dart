// ignore_for_file: avoid_print, avoid_relative_lib_imports
import 'dart:async';
import 'dart:io';

import '../lib/host/host_client.dart';

/// 宿主调试器：不经界面，直接把一条分享链接走完"解析 → 列表 → 取直链 → 下载"。
///
/// 用法：
///   dart run tool/host_probe.dart <分享链接> [提取码] [--download]
///
/// 需要 YUNGET_HOST_JAR 指向 yunget-host.jar（或与可执行文件同目录）。
Future<void> main(List<String> args) async {
  final link = args.isNotEmpty ? args.first : '';
  final rest = args.skip(1).toList();
  final download = rest.remove('--download');
  final pwd = rest.isNotEmpty ? rest.first : '';

  final client = await HostClient.start();
  client.events.listen((e) {
    if (e['event'] == 'log') print('[host] ${e['message']}');
  });
  try {
    final resolved = asMap(await client.call('resolve', {'link': link, 'pwd': pwd}));
    final session = (resolved['session'] ?? '').toString();
    print('解析: ${resolved['title']}  folder=${resolved['isFolder']}  platform=${resolved['platform']}');
    if (session.isEmpty) return;

    final list = await client.call('list', {'session': session, 'dir': ''}) as List;
    print('列表: ${list.length} 项');
    for (final item in list.take(10)) {
      final f = asMap(item);
      print('  - ${f['name']}  ${f['isDir'] == true ? '[目录]' : '${f['size']} B'}');
    }
    final files = list.map(asMap).where((f) => f['isDir'] != true).toList();
    if (files.isEmpty) {
      print('没有可直接下载的文件（可能是纯目录）');
      return;
    }
    final target = files.first;
    final direct = asMap(await client.call('directLink', {'session': session, 'file': target['fid']}));
    print('直链: ${direct['fileName']}  ${direct['size']} B');

    final size = (direct['size'] as num?)?.toInt() ?? -1;
    if (!download) return;
    if (size > 20 * 1024 * 1024) {
      print('文件 ${size} B 超过 20MB，跳过下载（省流量）');
      return;
    }
    final task = asMap(await client.call('download', {
      'url': direct['url'],
      'fileName': direct['fileName'],
      'size': direct['size'],
      'headers': direct['headers'],
    }));
    final id = (task['id'] as num).toInt();
    print('已提交任务 $id，等待完成…');
    for (var i = 0; i < 120; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 500));
      final list = await client.call('tasks') as List;
      final now = list.map(asMap).firstWhere((t) => (t['id'] as num) == id, orElse: () => const {});
      if (now.isEmpty) continue;
      final status = now['status'];
      if (status == 'DONE' || status == 'FAILED') {
        print('结果: $status  ${now['downloaded']}/${now['size']}  ${now['error'] ?? ''}');
        final f = File(now['destPath'].toString());
        print('文件: ${f.path} 存在=${f.existsSync()} 大小=${f.existsSync() ? f.lengthSync() : -1}');
        return;
      }
    }
    print('等待超时');
  } finally {
    await client.close();
  }
}

Map<String, dynamic> asMap(Object? v) => v is Map<String, dynamic> ? v : const {};
