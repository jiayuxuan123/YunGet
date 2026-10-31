import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yunget_desktop/host/models.dart';
import 'package:yunget_desktop/task_query.dart';
import 'package:yunget_desktop/widgets/command_palette.dart';
import 'package:yunget_desktop/widgets/revision_builder.dart';

DownloadTask _task({
  required int id,
  required String status,
  required int createdAt,
  String? fileName,
  String url = 'https://example.com/file.bin',
  String? destPath,
  int size = 1000,
  int downloaded = 0,
  String? error,
  int speed = 0,
}) {
  return DownloadTask(
    id: id,
    fileName: fileName ?? 'task-$id.bin',
    url: url,
    destPath: destPath ?? 'D:/downloads/task-$id.bin',
    size: size,
    downloaded: downloaded,
    status: status,
    error: error,
    speed: speed,
    connections: 8,
    createdAt: createdAt,
  );
}

List<int> _ids(Iterable<DownloadTask> tasks) =>
    tasks.map((task) => task.id).toList(growable: false);

DesktopCommand _command({
  required String label,
  required String subtitle,
  required IconData icon,
  String shortcut = '',
  List<String> keywords = const [],
}) {
  return DesktopCommand(
    label: label,
    subtitle: subtitle,
    icon: icon,
    shortcut: shortcut,
    keywords: keywords,
    onSelected: () {},
  );
}

void main() {
  group('HostSettings', () {
    test('连接数缺省使用 TurboDL 引擎上限 256', () {
      final settings = HostSettings.fromJson(const <String, dynamic>{});

      expect(settings.connections, 256);
      expect(settings.toJson()['connections'], 256);
    });

    test('显式连接数在 JSON 往返时保持不变', () {
      final original = HostSettings.fromJson(const <String, dynamic>{
        'connections': 64,
      });

      expect(original.connections, 64);
      expect(original.toJson()['connections'], 64);

      final restored = HostSettings.fromJson(original.toJson());
      expect(restored.connections, 64);
    });

    test('新布尔字段缺省为 true', () {
      final settings = HostSettings.fromJson(const <String, dynamic>{});

      expect(settings.closeToTray, isTrue);
      expect(settings.notifyOnComplete, isTrue);
      expect(settings.notifyOnFailure, isTrue);
    });

    test('新布尔字段可通过 JSON 往返', () {
      final original = HostSettings(
        downloadDir: 'D:/downloads',
        connections: 8,
        concurrentTasks: 2,
        speedLimitBytesPerSec: 4096,
        proxy: 'manual',
        proxyHost: '127.0.0.1',
        proxyPort: 7897,
        readTimeoutMs: 15000,
        closeToTray: false,
        notifyOnComplete: false,
        notifyOnFailure: true,
      );

      final json = original.toJson();
      expect(json['closeToTray'], isFalse);
      expect(json['notifyOnComplete'], isFalse);
      expect(json['notifyOnFailure'], isTrue);

      final restored = HostSettings.fromJson(json);
      expect(restored.closeToTray, isFalse);
      expect(restored.notifyOnComplete, isFalse);
      expect(restored.notifyOnFailure, isTrue);
    });
  });

  group('DownloadTask', () {
    test('createdAt 从 JSON 读取并为缺省值回退到 0', () {
      final parsed = DownloadTask.fromJson(const <String, dynamic>{
        'createdAt': 1700000000123,
      });
      final fallback = DownloadTask.fromJson(const <String, dynamic>{});

      expect(parsed.createdAt, 1700000000123);
      expect(fallback.createdAt, 0);
    });

    test('只有暂停或失败的任务可以恢复', () {
      expect(_task(id: 1, status: 'PAUSED', createdAt: 1).canResume, isTrue);
      expect(_task(id: 2, status: 'FAILED', createdAt: 1).canResume, isTrue);
      expect(_task(id: 3, status: 'RUNNING', createdAt: 1).canResume, isFalse);
      expect(_task(id: 4, status: 'DONE', createdAt: 1).canResume, isFalse);
      expect(_task(id: 5, status: 'QUEUED', createdAt: 1).canResume, isFalse);
    });

    test('formatEta 覆盖秒、分钟、小时和不可计算状态', () {
      expect(
        formatEta(
          _task(
            id: 1,
            status: 'RUNNING',
            createdAt: 1,
            size: 1500,
            downloaded: 500,
            speed: 1000,
          ),
        ),
        '1 秒',
      );
      expect(
        formatEta(
          _task(
            id: 2,
            status: 'RUNNING',
            createdAt: 1,
            size: 121000,
            downloaded: 1000,
            speed: 1000,
          ),
        ),
        '2 分钟',
      );
      expect(
        formatEta(
          _task(
            id: 3,
            status: 'RUNNING',
            createdAt: 1,
            size: 5401000,
            downloaded: 1000,
            speed: 1000,
          ),
        ),
        '1.5 小时',
      );
      expect(
        formatEta(
          _task(
            id: 4,
            status: 'PAUSED',
            createdAt: 1,
            size: 1000,
            downloaded: 500,
            speed: 1000,
          ),
        ),
        '—',
      );
      expect(
        formatEta(
          _task(
            id: 5,
            status: 'RUNNING',
            createdAt: 1,
            size: 1000,
            downloaded: 1000,
            speed: 1000,
          ),
        ),
        '—',
      );
      expect(
        formatEta(
          _task(id: 6, status: 'RUNNING', createdAt: 1, size: -1, speed: 1000),
        ),
        '—',
      );
    });
  });

  group('TaskFilter 和 queryTasks', () {
    final tasks = [
      _task(id: 1, status: 'RUNNING', createdAt: 100),
      _task(id: 2, status: 'DONE', createdAt: 200),
      _task(id: 3, status: 'FAILED', createdAt: 300),
      _task(id: 4, status: 'PAUSED', createdAt: 400),
      _task(id: 5, status: 'QUEUED', createdAt: 50),
    ];

    test('各筛选器的计数覆盖组合状态', () {
      expect(taskCountFor(tasks, TaskFilter.all), 5);
      expect(taskCountFor(tasks, TaskFilter.active), 1);
      expect(taskCountFor(tasks, TaskFilter.done), 1);
      expect(taskCountFor(tasks, TaskFilter.failed), 2);
    });

    test('空查询按 createdAt 从新到旧排序', () {
      expect(_ids(queryTasks(tasks)), <int>[4, 3, 2, 1, 5]);
    });

    test('搜索忽略大小写和首尾空白并覆盖各文本字段', () {
      final searchable = [
        _task(
          id: 10,
          status: 'DONE',
          createdAt: 100,
          fileName: 'Quarterly-Report.PDF',
          url: 'https://cdn.example.com/report.pdf',
          destPath: 'D:/archive/report.pdf',
        ),
        _task(
          id: 11,
          status: 'RUNNING',
          createdAt: 200,
          fileName: 'movie.mkv',
          url: 'https://mirror.example.com/movie.mkv',
          destPath: 'E:/media/movie.mkv',
        ),
        _task(
          id: 12,
          status: 'FAILED',
          createdAt: 300,
          fileName: 'backup.zip',
          url: 'https://files.example.com/backup.zip',
          destPath: 'F:/backups/backup.zip',
          error: 'Network timeout',
        ),
        _task(
          id: 13,
          status: 'PAUSED',
          createdAt: 400,
          fileName: 'notes.txt',
          url: 'https://notes.example.com/notes.txt',
          destPath: 'G:/documents/notes.txt',
          error: 'Disk full',
        ),
      ];

      expect(_ids(queryTasks(searchable, query: ' report ')), <int>[10]);
      expect(_ids(queryTasks(searchable, query: 'MIRROR.EXAMPLE')), <int>[11]);
      expect(_ids(queryTasks(searchable, query: 'backups')), <int>[12]);
      expect(_ids(queryTasks(searchable, query: 'network timeout')), <int>[12]);
      expect(
        _ids(queryTasks(searchable, filter: TaskFilter.failed, query: 'disk')),
        <int>[13],
      );
    });
  });

  group('filterDesktopCommands', () {
    final commands = [
      _command(
        label: '新建下载',
        subtitle: 'Create a download task',
        icon: Icons.add,
        shortcut: 'Ctrl+N',
        keywords: const ['new', '添加任务'],
      ),
      _command(
        label: 'Settings',
        subtitle: '打开设置',
        icon: Icons.settings,
        shortcut: 'Ctrl+,',
        keywords: const ['preferences', '配置'],
      ),
      _command(
        label: '打开文件夹',
        subtitle: 'Reveal destination',
        icon: Icons.folder_open,
        keywords: const ['folder', '目录'],
      ),
    ];

    test('支持中文标签、副标题和英文标签', () {
      expect(
        filterDesktopCommands(commands, '新建').map((command) => command.label),
        <String>['新建下载'],
      );
      expect(
        filterDesktopCommands(commands, '设置').map((command) => command.label),
        <String>['Settings'],
      );
      expect(
        filterDesktopCommands(
          commands,
          'SETTINGS',
        ).map((command) => command.label),
        <String>['Settings'],
      );
    });

    test('支持关键字和快捷键搜索', () {
      expect(
        filterDesktopCommands(commands, '配置').map((command) => command.label),
        <String>['Settings'],
      );
      expect(
        filterDesktopCommands(
          commands,
          'folder',
        ).map((command) => command.label),
        <String>['打开文件夹'],
      );
      expect(
        filterDesktopCommands(
          commands,
          'ctrl+n',
        ).map((command) => command.label),
        <String>['新建下载'],
      );
    });

    test('空查询和纯空白查询返回全部命令并保持原顺序', () {
      expect(filterDesktopCommands(commands, ''), same(commands));
      expect(filterDesktopCommands(commands, '   '), same(commands));
      expect(filterDesktopCommands(commands, '不存在的操作'), isEmpty);
    });
  });

  testWidgets('RevisionBuilder 在页面隐藏时停止跟随进度重建', (tester) async {
    final revision = ValueNotifier<int>(0);
    addTearDown(revision.dispose);
    var builds = 0;

    Widget build({required bool enabled}) {
      return Directionality(
        textDirection: TextDirection.ltr,
        child: RevisionBuilder(
          revision: revision,
          enabled: enabled,
          builder: (_) {
            builds++;
            return Text('build-$builds');
          },
        ),
      );
    }

    await tester.pumpWidget(build(enabled: true));
    expect(builds, 1);

    revision.value++;
    await tester.pump();
    expect(builds, 2);

    await tester.pumpWidget(build(enabled: false));
    final hiddenBuilds = builds;
    revision.value++;
    await tester.pump();
    expect(builds, hiddenBuilds);

    await tester.pumpWidget(build(enabled: true));
    revision.value++;
    await tester.pump();
    expect(builds, greaterThan(hiddenBuilds));
  });
}
