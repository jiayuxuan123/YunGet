import 'host/models.dart';

enum TaskFilter {
  all('全部'),
  active('进行中'),
  done('已完成'),
  failed('失败/暂停');

  const TaskFilter(this.label);
  final String label;

  bool matches(DownloadTask task) => switch (this) {
    TaskFilter.all => true,
    TaskFilter.active => task.isActive,
    TaskFilter.done => task.isDone,
    TaskFilter.failed => task.isFailed || task.isPaused,
  };
}

int taskCountFor(List<DownloadTask> tasks, TaskFilter filter) =>
    tasks.where(filter.matches).length;

List<DownloadTask> queryTasks(
  List<DownloadTask> tasks, {
  TaskFilter filter = TaskFilter.all,
  String query = '',
}) {
  final keyword = query.trim().toLowerCase();
  final result = tasks.where((task) {
    if (!filter.matches(task)) return false;
    if (keyword.isEmpty) return true;
    return task.fileName.toLowerCase().contains(keyword) ||
        task.url.toLowerCase().contains(keyword) ||
        task.destPath.toLowerCase().contains(keyword) ||
        (task.error ?? '').toLowerCase().contains(keyword);
  }).toList();
  result.sort((a, b) => b.createdAt.compareTo(a.createdAt));
  return result;
}
