import 'dart:io';

import 'package:flutter/material.dart';

import '../app_state.dart';
import '../host/models.dart';

/// 下载页：任务列表 + 进度 + 暂停/继续/删除/打开目录。
///
/// 进度来自宿主推送的事件（引擎每 200ms 一次），这里只做展示；
/// 不自己轮询，避免"界面和引擎各算一遍速度"这种经典对不上的问题。
class DownloadsScreen extends StatelessWidget {
  const DownloadsScreen({super.key, required this.state});

  final AppState state;

  Future<void> _openFolder(String path) async {
    final dir = File(path).parent.path;
    await Process.run('explorer', [dir], runInShell: false);
  }

  @override
  Widget build(BuildContext context) {
    final tasks = state.tasks;
    if (tasks.isEmpty) {
      return const Center(child: Text('还没有下载任务'));
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: tasks.length,
      separatorBuilder: (_, _) => const SizedBox(height: 8),
      itemBuilder: (context, i) {
        final task = tasks[i];
        final theme = Theme.of(context);
        return Card(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(task.fileName, style: theme.textTheme.titleMedium, overflow: TextOverflow.ellipsis),
                    ),
                    _statusChip(context, task),
                  ],
                ),
                const SizedBox(height: 8),
                LinearProgressIndicator(
                  value: task.isDone ? 1 : (task.size > 0 ? task.progress : null),
                  minHeight: 6,
                  borderRadius: BorderRadius.circular(3),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Text(
                      task.size > 0
                          ? '${formatBytes(task.downloaded)} / ${formatBytes(task.size)}'
                          : formatBytes(task.downloaded),
                      style: theme.textTheme.bodySmall,
                    ),
                    const SizedBox(width: 16),
                    if (task.isActive) ...[
                      Text(formatSpeed(task.speed), style: theme.textTheme.bodySmall),
                      const SizedBox(width: 16),
                      Text('${task.connections} 连接', style: theme.textTheme.bodySmall),
                    ],
                    if (task.error != null)
                      Expanded(
                        child: Text(
                          task.error!,
                          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    const Spacer(),
                    if (task.isActive)
                      IconButton(
                        tooltip: '暂停',
                        icon: const Icon(Icons.pause),
                        onPressed: () => state.pauseTask(task.id),
                      )
                    else if (!task.isDone)
                      IconButton(
                        tooltip: '继续',
                        icon: const Icon(Icons.play_arrow),
                        onPressed: () => state.resumeTask(task.id),
                      ),
                    IconButton(
                      tooltip: '打开所在目录',
                      icon: const Icon(Icons.folder_open),
                      onPressed: () => _openFolder(task.destPath),
                    ),
                    IconButton(
                      tooltip: task.isDone ? '从列表移除' : '取消并删除',
                      icon: const Icon(Icons.close),
                      onPressed: () => state.cancelTask(task.id),
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _statusChip(BuildContext context, DownloadTask task) {
    final (label, color) = switch (task.status) {
      'RUNNING' => ('下载中', Colors.blue),
      'PAUSED' => ('已暂停', Colors.orange),
      'DONE' => ('已完成', Colors.green),
      'FAILED' => ('失败', Colors.red),
      _ => (task.status, Colors.grey),
    };
    return Chip(
      label: Text(label, style: const TextStyle(fontSize: 12)),
      visualDensity: VisualDensity.compact,
      side: BorderSide(color: color.withValues(alpha: 0.4)),
      backgroundColor: color.withValues(alpha: 0.08),
    );
  }
}
