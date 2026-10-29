import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:yunget_desktop/main.dart';

/// 端到端：真实拉起引擎子进程，粘一条直链，点解析，等到任务完成。
///
/// 两个坑都记在这里，避免以后又踩：
/// 1. 断言必须看磁盘（任务表里出现本次新增的 DONE、文件真的落盘）。
///    第一版只看界面上有没有「已完成」三个字，结果被上一次留下的任务骗过去了。
/// 2. 等下载完成不能用 `tester.pump(时长)` 或裸 `Future.delayed` —— 测试体跑在受控时钟里，
///    它们不会真的让时间流逝，表现为"测试挂住直到超时"。真实等待要放进 `tester.runAsync`。
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('直链下载端到端', (tester) async {
    await tester.pumpWidget(const YunGetApp());

    // 等宿主就绪（顶栏"引擎启动中…"消失）。
    for (var i = 0; i < 40; i++) {
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 250)));
      await tester.pump();
      if (find.text('引擎启动中…').evaluate().isEmpty) break;
    }

    await tester.enterText(
      find.byKey(const ValueKey('linkField')),
      'https://speed.cloudflare.com/__down?bytes=1000000',
    );
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(find.byKey(const ValueKey('resolveButton')));

    // 用正斜杠拼路径：Windows 的 Dart 文件 API 接受它，也免得反斜杠在源码里
    // 被当成转义（`\t` 会变成制表符，路径直接失效 —— 这个坑真踩过）。
    final lad = Platform.environment['LOCALAPPDATA'] ?? '';
    final tasksFile = File('$lad/YunGet/tasks.json');
    debugPrint('[test] 任务表=${tasksFile.path} 存在=${tasksFile.existsSync()}');
    var finished = false;

    // 真实等待：整个轮询放进 runAsync，文件系统与计时器才是真的。
    await tester.runAsync(() async {
      for (var i = 0; i < 60; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 500));
        if (!tasksFile.existsSync()) continue;
        final data = jsonDecode(tasksFile.readAsStringSync()) as Map<String, dynamic>;
        final tasks = (data['tasks'] as List).cast<Map<String, dynamic>>();
        final hit = tasks.where((t) => (t['fileName'] as String).startsWith('__down') && t['status'] == 'DONE');
        if (hit.isEmpty) continue;
        final task = hit.last;
        expect(task['downloaded'], task['size'], reason: '完成的任务字节数必须齐');
        final file = File(task['destPath'] as String);
        expect(file.existsSync(), true, reason: '文件必须真的落盘');
        expect(file.lengthSync(), 1000000);
        finished = true;
        return;
      }
    });

    await tester.pump();
    expect(finished, true, reason: '30 秒内应出现一个已完成的直链下载任务');
  });
}
