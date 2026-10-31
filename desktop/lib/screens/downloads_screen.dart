import 'package:flutter/material.dart';

import '../app_state.dart';
import '../host/models.dart';
import '../task_query.dart';
import '../theme.dart';
import '../widgets/revision_builder.dart';

/// 下载页：左侧任务表、右侧详情。
///
/// 任务和进度只消费 [AppState] 推送的事件，本页不创建定时器，也不主动轮询。
class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key, required this.state, this.active = true});

  final AppState state;
  final bool active;

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  final TextEditingController _search = TextEditingController();
  final Set<int> _selectedIds = {};

  TaskFilter _filter = TaskFilter.all;
  String _query = '';
  int? _activeId;

  @override
  void didUpdateWidget(covariant DownloadsScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    final taskIds = widget.state.tasks.map((task) => task.id).toSet();
    _selectedIds.removeWhere((id) => !taskIds.contains(id));
    if (_activeId != null && !taskIds.contains(_activeId)) {
      _activeId = null;
    }
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  List<DownloadTask> get _visibleTasks =>
      queryTasks(widget.state.tasks, filter: _filter, query: _query);

  DownloadTask? _activeTask(List<DownloadTask> visible) {
    if (visible.isEmpty) return null;
    if (_activeId != null) {
      for (final task in visible) {
        if (task.id == _activeId) return task;
      }
    }
    return visible.first;
  }

  void _setFilter(TaskFilter filter) {
    if (filter == _filter) return;
    setState(() {
      _filter = filter;
      _query = '';
      _activeId = null;
      _selectedIds.clear();
      _search.clear();
    });
  }

  void _onSearchChanged(String value) {
    setState(() {
      _query = value;
      _activeId = null;
      _selectedIds.clear();
    });
  }

  void _clearSearch() {
    _search.clear();
    setState(() {
      _query = '';
      _activeId = null;
      _selectedIds.clear();
    });
  }

  void _clearQuery() {
    _search.clear();
    setState(() {
      _filter = TaskFilter.all;
      _query = '';
      _activeId = null;
      _selectedIds.clear();
    });
  }

  void _showTask(DownloadTask task) {
    setState(() => _activeId = task.id);
  }

  void _toggleSelection(int id) {
    setState(() {
      if (!_selectedIds.add(id)) _selectedIds.remove(id);
    });
  }

  void _toggleAll(List<DownloadTask> visible, bool? selected) {
    setState(() {
      final ids = visible.map((task) => task.id);
      if (selected == true) {
        _selectedIds.addAll(ids);
      } else {
        _selectedIds.removeAll(ids);
      }
    });
  }

  void _showMessage(String message, {bool error = false}) {
    if (!mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: error ? YunGetColors.danger : null,
      ),
    );
  }

  Future<bool> _perform(
    Future<void> Function() action, {
    String? successMessage,
  }) async {
    try {
      await action();
      if (successMessage != null) _showMessage(successMessage);
      return true;
    } catch (error) {
      _showMessage('操作失败：$error', error: true);
      return false;
    }
  }

  Future<void> _pauseTask(DownloadTask task) async {
    await _perform(
      () => widget.state.pauseTask(task.id),
      successMessage: '已暂停：${task.fileName}',
    );
  }

  Future<void> _resumeTask(DownloadTask task) async {
    await _perform(
      () => widget.state.resumeTask(task.id),
      successMessage: '已继续：${task.fileName}',
    );
  }

  Future<void> _retryTask(DownloadTask task) async {
    await _perform(
      () => widget.state.retryTask(task.id),
      successMessage: '正在重试：${task.fileName}',
    );
  }

  Future<void> _pauseSelected(List<DownloadTask> tasks) async {
    final targets = tasks
        .where((task) => task.isActive)
        .toList(growable: false);
    if (targets.isEmpty) return;
    await _perform(() async {
      for (final task in targets) {
        await widget.state.pauseTask(task.id);
      }
    }, successMessage: '已暂停 ${targets.length} 个任务');
  }

  Future<void> _resumeSelected(List<DownloadTask> tasks) async {
    final targets = tasks
        .where((task) => task.canResume)
        .toList(growable: false);
    if (targets.isEmpty) return;
    await _perform(() async {
      for (final task in targets) {
        await widget.state.resumeTask(task.id);
      }
    }, successMessage: '已继续 ${targets.length} 个任务');
  }

  Future<void> _openTask(DownloadTask task) async {
    await _perform(() => widget.state.openTask(task));
  }

  Future<void> _revealTask(DownloadTask task) async {
    await _perform(() => widget.state.revealTask(task));
  }

  Future<void> _removeTasks(List<DownloadTask> tasks) async {
    if (tasks.isEmpty) return;
    final confirmed = await _confirmRemove(tasks);
    if (!confirmed || !mounted) return;

    final ids = tasks.map((task) => task.id).toSet();
    final removed = await _perform(
      () => widget.state.removeTasks(ids),
      successMessage: ids.length == 1 ? '任务已移除' : '已移除 ${ids.length} 个任务',
    );
    if (!removed || !mounted) return;
    setState(() {
      _selectedIds.removeAll(ids);
      if (ids.contains(_activeId)) _activeId = null;
    });
  }

  Future<bool> _confirmRemove(List<DownloadTask> tasks) async {
    final incomplete = tasks.any((task) => !task.isDone);
    final count = tasks.length;
    final content = incomplete
        ? '未完成的任务会被取消，下载中的临时内容可能一并删除。此操作无法撤销。'
        : '任务将从下载列表移除，已下载的文件不会被恢复。';
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        icon: const Icon(Icons.delete_outline, color: YunGetColors.danger),
        title: Text(count == 1 ? '移除这个任务？' : '移除 $count 个任务？'),
        content: Text(content),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: YunGetColors.danger),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('移除'),
          ),
        ],
      ),
    );
    return result == true;
  }

  @override
  Widget build(BuildContext context) {
    return RevisionBuilder(
      revision: widget.state.taskRevision,
      enabled: widget.active,
      builder: (context) => _buildPage(context),
    );
  }

  Widget _buildPage(BuildContext context) {
    final allTasks = widget.state.tasks;
    final visible = _visibleTasks;
    final selected = visible
        .where((task) => _selectedIds.contains(task.id))
        .toList(growable: false);
    final active = _activeTask(visible);

    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _pageHeader(context, allTasks),
          const SizedBox(height: 14),
          _filterBar(context, visible.length),
          const SizedBox(height: 14),
          Expanded(
            child: _workspace(
              context: context,
              visible: visible,
              selected: selected,
              active: active,
            ),
          ),
        ],
      ),
    );
  }

  Widget _pageHeader(BuildContext context, List<DownloadTask> tasks) {
    final summary = tasks.isEmpty
        ? '还没有下载任务'
        : '共 ${tasks.length} 个任务 · ${widget.state.activeTaskCount} 个进行中 · '
              '${widget.state.totalSpeed > 0 ? formatSpeed(widget.state.totalSpeed) : '当前空闲'}';
    final title = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('下载任务', style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 3),
        Text(
          summary,
          style: Theme.of(context).textTheme.bodySmall
              ?.copyWith(color: YunGetColors.inkMuted),
        ),
      ],
    );

    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < 760;
        final search = _searchField(context);
        if (compact) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [title, const SizedBox(height: 12), search],
          );
        }
        return Row(
          children: [
            Expanded(child: title),
            const SizedBox(width: 24),
            SizedBox(width: 310, child: search),
          ],
        );
      },
    );
  }

  Widget _searchField(BuildContext context) {
    return TextField(
      key: const ValueKey('downloadSearch'),
      controller: _search,
      onChanged: _onSearchChanged,
      textInputAction: TextInputAction.search,
      decoration: InputDecoration(
        hintText: '搜索文件名、地址或保存路径',
        prefixIcon: const Icon(Icons.search, size: 20),
        suffixIcon: _query.isEmpty
            ? null
            : IconButton(
                tooltip: '清空搜索',
                onPressed: _clearSearch,
                icon: const Icon(Icons.close, size: 18),
              ),
      ),
    );
  }

  Widget _filterBar(BuildContext context, int resultCount) {
    return Row(
      children: [
        Expanded(
          child: Wrap(
            spacing: 7,
            runSpacing: 7,
            children: TaskFilter.values
                .map((filter) {
                  final selected = filter == _filter;
                  final count = taskCountFor(widget.state.tasks, filter);
                  return ChoiceChip(
                    key: ValueKey('downloadFilter-${filter.name}'),
                    selected: selected,
                    showCheckmark: false,
                    onSelected: (_) => _setFilter(filter),
                    selectedColor: YunGetColors.ink,
                    backgroundColor: YunGetColors.surface.withValues(
                      alpha: 0.72,
                    ),
                    side: BorderSide(
                      color: selected ? YunGetColors.ink : YunGetColors.line,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(999),
                    ),
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    label: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          filter.label,
                          style: TextStyle(
                            color: selected
                                ? Colors.white
                                : YunGetColors.inkMuted,
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 1,
                          ),
                          decoration: BoxDecoration(
                            color: selected
                                ? Colors.white.withValues(alpha: 0.16)
                                : YunGetColors.paperDeep,
                            borderRadius: BorderRadius.circular(99),
                          ),
                          child: Text(
                            '$count',
                            style: TextStyle(
                              color: selected
                                  ? Colors.white
                                  : YunGetColors.inkMuted,
                              fontSize: 10,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                      ],
                    ),
                  );
                })
                .toList(growable: false),
          ),
        ),
        if (_query.trim().isNotEmpty) ...[
          const SizedBox(width: 12),
          Text(
            '$resultCount 个结果',
            style: Theme.of(context).textTheme.bodySmall
                ?.copyWith(color: YunGetColors.inkMuted),
          ),
        ],
      ],
    );
  }

  Widget _workspace({
    required BuildContext context,
    required List<DownloadTask> visible,
    required List<DownloadTask> selected,
    required DownloadTask? active,
  }) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final detailWidth = constraints.maxWidth >= 1120
            ? 390.0
            : constraints.maxWidth >= 900
            ? 350.0
            : constraints.maxWidth >= 700
            ? 310.0
            : 270.0;
        return Row(
          children: [
            Expanded(
              child: _taskListPanel(
                context: context,
                visible: visible,
                selected: selected,
                activeId: active?.id,
              ),
            ),
            const SizedBox(width: 14),
            SizedBox(width: detailWidth, child: _detailPanel(context, active)),
          ],
        );
      },
    );
  }

  Widget _taskListPanel({
    required BuildContext context,
    required List<DownloadTask> visible,
    required List<DownloadTask> selected,
    required int? activeId,
  }) {
    return Material(
      color: YunGetColors.surface,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: const BorderSide(color: YunGetColors.line),
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final showProgress = constraints.maxWidth >= 560;
          final selectedIds = selected.map((task) => task.id).toSet();
          final allSelected =
              visible.isNotEmpty &&
              visible.every((task) => selectedIds.contains(task.id));
          final someSelected =
              !allSelected &&
              visible.any((task) => selectedIds.contains(task.id));
          final pauseTargets = selected
              .where((task) => task.isActive)
              .toList(growable: false);
          final resumeTargets = selected
              .where((task) => task.canResume)
              .toList(growable: false);

          return Column(
            children: [
              Container(
                height: 45,
                padding: const EdgeInsets.symmetric(horizontal: 10),
                color: YunGetColors.paper.withValues(alpha: 0.48),
                child: Row(
                  children: [
                    SizedBox(
                      width: 32,
                      child: Checkbox(
                        value: allSelected
                            ? true
                            : (someSelected ? null : false),
                        tristate: true,
                        activeColor: YunGetColors.accent,
                        onChanged: (value) => _toggleAll(visible, value),
                        semanticLabel: '全选',
                      ),
                    ),
                    const Text(
                      '全选',
                      style: TextStyle(
                        fontSize: 12,
                        color: YunGetColors.inkMuted,
                      ),
                    ),
                    if (selected.isNotEmpty) ...[
                      const SizedBox(width: 10),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 7,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: YunGetColors.accentSoft,
                          borderRadius: BorderRadius.circular(99),
                        ),
                        child: Text(
                          '已选 ${selected.length}',
                          style: const TextStyle(
                            color: YunGetColors.accent,
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                    const Spacer(),
                    _batchAction(
                      icon: Icons.pause,
                      label: '暂停',
                      onPressed: pauseTargets.isEmpty
                          ? null
                          : () => _pauseSelected(pauseTargets),
                    ),
                    _batchAction(
                      icon: Icons.play_arrow,
                      label: '继续',
                      onPressed: resumeTargets.isEmpty
                          ? null
                          : () => _resumeSelected(resumeTargets),
                    ),
                    _batchAction(
                      icon: Icons.delete_outline,
                      label: '移除',
                      color: YunGetColors.danger,
                      onPressed: selected.isEmpty
                          ? null
                          : () => _removeTasks(selected),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              _listHeader(showProgress),
              const Divider(height: 1),
              Expanded(
                child: visible.isEmpty
                    ? _listEmptyState(context)
                    : Scrollbar(
                        child: ListView.separated(
                          padding: EdgeInsets.zero,
                          itemCount: visible.length,
                          separatorBuilder: (_, _) =>
                              const Divider(height: 1, indent: 84),
                          itemBuilder: (context, index) {
                            final task = visible[index];
                            return _taskRow(
                              context: context,
                              task: task,
                              selected: selectedIds.contains(task.id),
                              active: activeId == task.id,
                              showProgress: showProgress,
                            );
                          },
                        ),
                      ),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _batchAction({
    required IconData icon,
    required String label,
    required VoidCallback? onPressed,
    Color? color,
  }) {
    return TextButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 16),
      label: Text(label),
      style: TextButton.styleFrom(
        foregroundColor: color ?? YunGetColors.inkMuted,
        disabledForegroundColor: YunGetColors.inkMuted.withValues(alpha: 0.35),
        minimumSize: const Size(0, 30),
        padding: const EdgeInsets.symmetric(horizontal: 8),
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        visualDensity: VisualDensity.compact,
        textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
      ),
    );
  }

  Widget _listHeader(bool showProgress) {
    const labelStyle = TextStyle(
      fontSize: 11,
      fontWeight: FontWeight.w600,
      color: YunGetColors.inkMuted,
    );
    return Container(
      height: 31,
      padding: const EdgeInsets.symmetric(horizontal: 10),
      color: YunGetColors.paper.withValues(alpha: 0.28),
      child: Row(
        children: [
          const SizedBox(width: 32),
          const SizedBox(width: 34),
          const Expanded(child: Text('任务', style: labelStyle)),
          if (showProgress)
            const SizedBox(width: 132, child: Text('进度', style: labelStyle)),
          const SizedBox(width: 76, child: Text('状态', style: labelStyle)),
        ],
      ),
    );
  }

  Widget _taskRow({
    required BuildContext context,
    required DownloadTask task,
    required bool selected,
    required bool active,
    required bool showProgress,
  }) {
    final status = _statusVisual(task);
    final name = task.fileName.isEmpty ? '未命名下载' : task.fileName;
    final transferred = task.size > 0
        ? '${formatBytes(task.downloaded)} / ${formatBytes(task.size)}'
        : formatBytes(task.downloaded);
    final speed = task.isActive ? ' · ${formatSpeed(task.speed)}' : '';

    return Container(
      decoration: BoxDecoration(
        border: Border(
          left: BorderSide(
            color: active ? YunGetColors.accent : Colors.transparent,
            width: 3,
          ),
        ),
      ),
      child: Material(
        color: selected
            ? YunGetColors.accentSoft.withValues(alpha: 0.58)
            : active
            ? YunGetColors.paper.withValues(alpha: 0.44)
            : Colors.transparent,
        child: InkWell(
          onTap: () => _showTask(task),
          onDoubleTap: task.isDone ? () => _openTask(task) : null,
          hoverColor: YunGetColors.accentSoft.withValues(alpha: 0.28),
          child: SizedBox(
            height: 58,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10),
              child: Row(
                children: [
                  SizedBox(
                    width: 32,
                    child: Checkbox(
                      value: selected,
                      activeColor: YunGetColors.accent,
                      onChanged: (_) => _toggleSelection(task.id),
                      semanticLabel: '选择 $name',
                    ),
                  ),
                  SizedBox(
                    width: 34,
                    child: Center(
                      child: Container(
                        width: 27,
                        height: 27,
                        decoration: BoxDecoration(
                          color: status.softColor,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(status.icon, size: 15, color: status.color),
                      ),
                    ),
                  ),
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: YunGetColors.ink,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          '$transferred$speed',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: YunGetColors.inkMuted,
                            fontSize: 10.5,
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (showProgress)
                    SizedBox(
                      width: 132,
                      child: Padding(
                        padding: const EdgeInsets.only(right: 16),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    _progressText(task),
                                    style: const TextStyle(
                                      color: YunGetColors.inkMuted,
                                      fontSize: 10,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 4),
                            LinearProgressIndicator(
                              value: _progressValue(task),
                              minHeight: 4,
                              borderRadius: BorderRadius.circular(99),
                              backgroundColor: YunGetColors.paperDeep,
                              color: status.color,
                            ),
                          ],
                        ),
                      ),
                    ),
                  SizedBox(
                    width: 76,
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: _StatusBadge(status: status),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _listEmptyState(BuildContext context) {
    final noTasks = widget.state.tasks.isEmpty;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 58,
              height: 58,
              decoration: const BoxDecoration(
                color: YunGetColors.paperDeep,
                shape: BoxShape.circle,
              ),
              child: Icon(
                noTasks ? Icons.download_done_outlined : Icons.search_off,
                size: 28,
                color: YunGetColors.inkMuted,
              ),
            ),
            const SizedBox(height: 13),
            Text(
              noTasks ? '还没有下载任务' : '没有符合条件的任务',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 5),
            Text(
              noTasks ? '从解析页添加链接或直链后，任务会出现在这里。' : '换一个筛选条件，或清空当前搜索。',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: YunGetColors.inkMuted),
            ),
            if (!noTasks) ...[
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: _clearQuery,
                icon: const Icon(Icons.filter_alt_off_outlined, size: 17),
                label: const Text('清除筛选与搜索'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _detailPanel(BuildContext context, DownloadTask? task) {
    return Material(
      color: YunGetColors.surface,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: const BorderSide(color: YunGetColors.line),
      ),
      child: Column(
        children: [
          Container(
            height: 45,
            padding: const EdgeInsets.symmetric(horizontal: 14),
            color: YunGetColors.paper.withValues(alpha: 0.48),
            child: Row(
              children: [
                const Icon(Icons.tune, size: 17, color: YunGetColors.inkMuted),
                const SizedBox(width: 8),
                const Text(
                  '任务详情',
                  style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
                ),
                const Spacer(),
                if (task != null)
                  Text(
                    '#${task.id}',
                    style: const TextStyle(
                      fontSize: 11,
                      color: YunGetColors.inkMuted,
                    ),
                  ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: task == null
                ? _detailEmptyState(context)
                : _taskDetails(context, task),
          ),
        ],
      ),
    );
  }

  Widget _detailEmptyState(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.ads_click, size: 34, color: YunGetColors.inkMuted),
            const SizedBox(height: 10),
            Text('选择一个任务查看详情', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(
              '进度、连接数、保存路径和任务操作都在这里。',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: YunGetColors.inkMuted),
            ),
          ],
        ),
      ),
    );
  }

  Widget _taskDetails(BuildContext context, DownloadTask task) {
    final status = _statusVisual(task);
    final name = task.fileName.isEmpty ? '未命名下载' : task.fileName;
    final transferred = task.size > 0
        ? '${formatBytes(task.downloaded)} / ${formatBytes(task.size)}'
        : formatBytes(task.downloaded);

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: status.softColor,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  Icons.insert_drive_file_outlined,
                  color: status.color,
                  size: 22,
                ),
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleMedium
                          ?.copyWith(height: 1.25),
                    ),
                    const SizedBox(height: 6),
                    _StatusBadge(status: status),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.all(13),
            decoration: BoxDecoration(
              color: YunGetColors.paper.withValues(alpha: 0.58),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: YunGetColors.line),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Text(
                      _progressText(task),
                      style: TextStyle(
                        color: status.color,
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const Spacer(),
                    Text(
                      transferred,
                      style: const TextStyle(
                        fontSize: 11,
                        color: YunGetColors.inkMuted,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                LinearProgressIndicator(
                  value: _progressValue(task),
                  minHeight: 7,
                  borderRadius: BorderRadius.circular(99),
                  backgroundColor: YunGetColors.paperDeep,
                  color: status.color,
                ),
              ],
            ),
          ),
          if (task.error != null) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(11),
              decoration: BoxDecoration(
                color: YunGetColors.danger.withValues(alpha: 0.07),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: YunGetColors.danger.withValues(alpha: 0.24),
                ),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(
                    Icons.error_outline,
                    size: 18,
                    color: YunGetColors.danger,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: SelectableText(
                      task.error!,
                      style: const TextStyle(
                        color: YunGetColors.danger,
                        fontSize: 11.5,
                        height: 1.4,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: _infoTile(
                  icon: Icons.speed,
                  label: '速度',
                  value: task.isActive ? formatSpeed(task.speed) : '—',
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _infoTile(
                  icon: Icons.hub_outlined,
                  label: '连接数',
                  value: '${task.connections}',
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: _infoTile(
                  icon: Icons.timer_outlined,
                  label: '剩余时间',
                  value: formatEta(task),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _infoTile(
                  icon: Icons.schedule,
                  label: '创建时间',
                  value: formatTimestamp(task.createdAt),
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),
          _sectionLabel('操作'),
          const SizedBox(height: 9),
          _primaryAction(task),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: Tooltip(
                  message: task.isDone ? '打开已完成的文件' : '下载完成后可打开',
                  child: OutlinedButton.icon(
                    onPressed: task.isDone && task.destPath.isNotEmpty
                        ? () => _openTask(task)
                        : null,
                    icon: const Icon(Icons.open_in_new, size: 17),
                    label: const Text('打开文件'),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 11,
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: task.destPath.isEmpty
                      ? null
                      : () => _revealTask(task),
                  icon: const Icon(Icons.folder_open, size: 17),
                  label: const Text('定位文件'),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 11,
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          TextButton.icon(
            onPressed: () => _removeTasks([task]),
            icon: const Icon(Icons.delete_outline, size: 17),
            label: const Text('从列表移除'),
            style: TextButton.styleFrom(
              foregroundColor: YunGetColors.danger,
              minimumSize: const Size.fromHeight(38),
            ),
          ),
          const SizedBox(height: 16),
          _sectionLabel('保存位置'),
          const SizedBox(height: 7),
          _selectableValue(task.destPath),
          const SizedBox(height: 14),
          _sectionLabel('来源地址'),
          const SizedBox(height: 7),
          _selectableValue(task.url),
        ],
      ),
    );
  }

  Widget _infoTile({
    required IconData icon,
    required String label,
    required String value,
  }) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
      decoration: BoxDecoration(
        color: YunGetColors.paper.withValues(alpha: 0.42),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: YunGetColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 13, color: YunGetColors.inkMuted),
              const SizedBox(width: 5),
              Text(
                label,
                style: const TextStyle(
                  fontSize: 10,
                  color: YunGetColors.inkMuted,
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: YunGetColors.ink,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }

  Widget _sectionLabel(String label) {
    return Text(
      label,
      style: const TextStyle(
        color: YunGetColors.inkMuted,
        fontSize: 10,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.8,
      ),
    );
  }

  Widget _selectableValue(String value) {
    return Container(
      constraints: const BoxConstraints(minHeight: 38),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
      decoration: BoxDecoration(
        color: YunGetColors.paper.withValues(alpha: 0.42),
        borderRadius: BorderRadius.circular(9),
        border: Border.all(color: YunGetColors.line),
      ),
      child: SelectableText(
        value.isEmpty ? '—' : value,
        style: const TextStyle(
          color: YunGetColors.inkMuted,
          fontFamily: 'Cascadia Mono',
          fontFamilyFallback: ['Consolas', 'Microsoft YaHei UI'],
          fontSize: 10.5,
          height: 1.45,
        ),
      ),
    );
  }

  Widget _primaryAction(DownloadTask task) {
    if (task.isActive) {
      return FilledButton.icon(
        onPressed: () => _pauseTask(task),
        icon: const Icon(Icons.pause, size: 18),
        label: const Text('暂停下载'),
      );
    }
    if (task.isFailed) {
      return FilledButton.icon(
        style: FilledButton.styleFrom(backgroundColor: YunGetColors.danger),
        onPressed: () => _retryTask(task),
        icon: const Icon(Icons.refresh, size: 18),
        label: const Text('重试下载'),
      );
    }
    if (task.isPaused) {
      return FilledButton.icon(
        onPressed: () => _resumeTask(task),
        icon: const Icon(Icons.play_arrow, size: 18),
        label: const Text('继续下载'),
      );
    }
    if (task.isDone) {
      return FilledButton.icon(
        onPressed: task.destPath.isEmpty ? null : () => _openTask(task),
        icon: const Icon(Icons.open_in_new, size: 18),
        label: const Text('打开文件'),
      );
    }
    return const FilledButton(onPressed: null, child: Text('暂无可用操作'));
  }

  double? _progressValue(DownloadTask task) {
    if (task.isDone) return 1;
    if (task.size > 0) return task.progress;
    return task.isActive ? null : 0;
  }

  String _progressText(DownloadTask task) {
    if (task.size > 0) return '${(task.progress * 100).toStringAsFixed(1)}%';
    return task.isDone ? '已完成' : '—';
  }

  _TaskStatusVisual _statusVisual(DownloadTask task) {
    return switch (task.status) {
      'RUNNING' => const _TaskStatusVisual(
        label: '下载中',
        color: YunGetColors.accent,
        softColor: YunGetColors.accentSoft,
        icon: Icons.downloading,
      ),
      'PAUSED' => _TaskStatusVisual(
        label: '已暂停',
        color: YunGetColors.amber,
        softColor: YunGetColors.amber.withValues(alpha: 0.16),
        icon: Icons.pause,
      ),
      'DONE' => const _TaskStatusVisual(
        label: '已完成',
        color: YunGetColors.teal,
        softColor: YunGetColors.tealSoft,
        icon: Icons.check,
      ),
      'FAILED' => _TaskStatusVisual(
        label: '失败',
        color: YunGetColors.danger,
        softColor: YunGetColors.danger.withValues(alpha: 0.09),
        icon: Icons.priority_high,
      ),
      'QUEUED' => const _TaskStatusVisual(
        label: '等待中',
        color: YunGetColors.inkMuted,
        softColor: YunGetColors.paperDeep,
        icon: Icons.hourglass_empty,
      ),
      'CANCELED' => const _TaskStatusVisual(
        label: '已取消',
        color: YunGetColors.inkMuted,
        softColor: YunGetColors.paperDeep,
        icon: Icons.close,
      ),
      _ => _TaskStatusVisual(
        label: task.status.isEmpty ? '未知' : task.status,
        color: YunGetColors.inkMuted,
        softColor: YunGetColors.paperDeep,
        icon: Icons.help_outline,
      ),
    };
  }
}

class _TaskStatusVisual {
  const _TaskStatusVisual({
    required this.label,
    required this.color,
    required this.softColor,
    required this.icon,
  });

  final String label;
  final Color color;
  final Color softColor;
  final IconData icon;
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.status});

  final _TaskStatusVisual status;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: status.softColor,
        borderRadius: BorderRadius.circular(99),
      ),
      child: Text(
        status.label,
        style: TextStyle(
          color: status.color,
          fontSize: 10,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}
