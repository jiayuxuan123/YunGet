/// 与宿主协议一一对应的数据模型。字段名刻意和 Kotlin 侧保持一致，
/// 改协议时两边 grep 同一个字符串就能找到全部位置。
class ShareFile {
  ShareFile({required this.fid, required this.name, required this.size, required this.isDir, this.time = ''});

  final String fid;
  final String name;
  final int size;
  final bool isDir;
  final String time;

  factory ShareFile.fromJson(Map<String, dynamic> json) => ShareFile(
        fid: (json['fid'] ?? '').toString(),
        name: (json['name'] ?? '').toString(),
        size: (json['size'] as num?)?.toInt() ?? 0,
        isDir: json['isDir'] == true,
        time: (json['time'] ?? '').toString(),
      );
}

class ShareResolveResult {
  ShareResolveResult({required this.session, required this.platform, required this.title, required this.isFolder});

  final String session;
  final String platform;
  final String title;
  final bool isFolder;

  factory ShareResolveResult.fromJson(Map<String, dynamic> json) => ShareResolveResult(
        session: (json['session'] ?? '').toString(),
        platform: (json['platform'] ?? '').toString(),
        title: (json['title'] ?? '').toString(),
        isFolder: json['isFolder'] == true,
      );
}

class DirectLink {
  DirectLink({required this.url, required this.size, required this.fileName, required this.headers});

  final String url;
  final int size;
  final String fileName;
  final Map<String, String> headers;

  factory DirectLink.fromJson(Map<String, dynamic> json) {
    final rawHeaders = json['headers'];
    final headers = <String, String>{};
    if (rawHeaders is Map) {
      rawHeaders.forEach((key, value) => headers[key.toString()] = value.toString());
    }
    return DirectLink(
      url: (json['url'] ?? '').toString(),
      size: (json['size'] as num?)?.toInt() ?? -1,
      fileName: (json['fileName'] ?? '').toString(),
      headers: headers,
    );
  }
}

class DownloadTask {
  DownloadTask({
    required this.id,
    required this.fileName,
    required this.url,
    required this.destPath,
    required this.size,
    required this.downloaded,
    required this.status,
    required this.error,
    required this.speed,
    required this.connections,
  });

  final int id;
  final String fileName;
  final String url;
  final String destPath;
  final int size;
  final int downloaded;
  final String status;
  final String? error;
  final int speed;
  final int connections;

  double get progress => size > 0 ? (downloaded / size).clamp(0.0, 1.0) : 0.0;
  bool get isActive => status == 'RUNNING';
  bool get isDone => status == 'DONE';
  bool get isFailed => status == 'FAILED';

  factory DownloadTask.fromJson(Map<String, dynamic> json) => DownloadTask(
        id: (json['id'] as num?)?.toInt() ?? 0,
        fileName: (json['fileName'] ?? '').toString(),
        url: (json['url'] ?? '').toString(),
        destPath: (json['destPath'] ?? '').toString(),
        size: (json['size'] as num?)?.toInt() ?? -1,
        downloaded: (json['downloaded'] as num?)?.toInt() ?? 0,
        status: (json['status'] ?? 'PAUSED').toString(),
        error: (json['error']?.toString().isNotEmpty ?? false) ? json['error'].toString() : null,
        speed: (json['speed'] as num?)?.toInt() ?? 0,
        connections: (json['connections'] as num?)?.toInt() ?? 0,
      );
}

class HostSettings {
  HostSettings({
    required this.downloadDir,
    required this.connections,
    required this.concurrentTasks,
    required this.speedLimitBytesPerSec,
    required this.proxy,
    required this.proxyHost,
    required this.proxyPort,
    required this.readTimeoutMs,
  });

  final String downloadDir;
  final int connections;
  final int concurrentTasks;
  final int speedLimitBytesPerSec;
  final String proxy;
  final String proxyHost;
  final int proxyPort;
  final int readTimeoutMs;

  HostSettings copyWith({
    String? downloadDir,
    int? connections,
    int? concurrentTasks,
    int? speedLimitBytesPerSec,
    String? proxy,
    String? proxyHost,
    int? proxyPort,
    int? readTimeoutMs,
  }) =>
      HostSettings(
        downloadDir: downloadDir ?? this.downloadDir,
        connections: connections ?? this.connections,
        concurrentTasks: concurrentTasks ?? this.concurrentTasks,
        speedLimitBytesPerSec: speedLimitBytesPerSec ?? this.speedLimitBytesPerSec,
        proxy: proxy ?? this.proxy,
        proxyHost: proxyHost ?? this.proxyHost,
        proxyPort: proxyPort ?? this.proxyPort,
        readTimeoutMs: readTimeoutMs ?? this.readTimeoutMs,
      );

  Map<String, dynamic> toJson() => {
        'downloadDir': downloadDir,
        'connections': connections,
        'concurrentTasks': concurrentTasks,
        'speedLimitBytesPerSec': speedLimitBytesPerSec,
        'proxy': proxy,
        'proxyHost': proxyHost,
        'proxyPort': proxyPort,
        'readTimeoutMs': readTimeoutMs,
      };

  factory HostSettings.fromJson(Map<String, dynamic> json) => HostSettings(
        downloadDir: (json['downloadDir'] ?? '').toString(),
        connections: (json['connections'] as num?)?.toInt() ?? 16,
        concurrentTasks: (json['concurrentTasks'] as num?)?.toInt() ?? 3,
        speedLimitBytesPerSec: (json['speedLimitBytesPerSec'] as num?)?.toInt() ?? 0,
        proxy: (json['proxy'] ?? 'system').toString(),
        proxyHost: (json['proxyHost'] ?? '').toString(),
        proxyPort: (json['proxyPort'] as num?)?.toInt() ?? 0,
        readTimeoutMs: (json['readTimeoutMs'] as num?)?.toInt() ?? 30000,
      );
}

String formatBytes(int bytes) {
  if (bytes < 0) return '未知';
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  if (bytes < 1024 * 1024 * 1024) return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
  return '${(bytes / 1024 / 1024 / 1024).toStringAsFixed(2)} GB';
}

String formatSpeed(int bytesPerSec) => bytesPerSec <= 0 ? '—' : '${formatBytes(bytesPerSec)}/s';
