import 'package:flutter_test/flutter_test.dart';
import 'package:yunget_desktop/host/models.dart';

void main() {
  test('任务 JSON 解析：进度与状态', () {
    final task = DownloadTask.fromJson({
      'id': 7,
      'fileName': 'a.bin',
      'url': 'https://example.com/a.bin',
      'destPath': 'D:/dl/a.bin',
      'size': 2000,
      'downloaded': 500,
      'status': 'RUNNING',
      'error': null,
      'speed': 1024,
      'connections': 8,
    });
    expect(task.id, 7);
    expect(task.progress, 0.25);
    expect(task.isActive, true);
    expect(formatBytes(2048), '2.0 KB');
    expect(formatSpeed(0), '—');
  });

  test('设置缺字段时落到默认值', () {
    final settings = HostSettings.fromJson(const {});
    expect(settings.connections, 256);
    expect(settings.concurrentTasks, 3);
    expect(settings.readTimeoutMs, 30000);
  });
}
