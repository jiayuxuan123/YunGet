import 'dart:io';

import 'package:flutter/services.dart';
import 'package:desktop_drop/desktop_drop.dart';
import 'package:flutter/material.dart';

import '../app_state.dart';
import '../host/models.dart';
import '../theme.dart';
import '../widgets/revision_builder.dart';

/// 解析页：粘贴/拖入链接 → 浏览分享目录 → 单个或批量下载。
class ResolveScreen extends StatefulWidget {
  const ResolveScreen({
    super.key,
    required this.state,
    required this.onOpenDownloads,
    this.active = true,
  });

  final AppState state;
  final VoidCallback onOpenDownloads;
  final bool active;

  @override
  State<ResolveScreen> createState() => _ResolveScreenState();
}

class _ResolveScreenState extends State<ResolveScreen> {
  final TextEditingController _link = TextEditingController();
  final TextEditingController _pwd = TextEditingController();
  final Set<String> _selected = {};
  String? _error;
  bool _batchBusy = false;
  bool _dragging = false;

  @override
  void dispose() {
    _link.dispose();
    _pwd.dispose();
    super.dispose();
  }

  Future<void> _resolve() async {
    final link = _link.text.trim();
    if (link.isEmpty) return;
    setState(() {
      _error = null;
      _selected.clear();
    });
    try {
      final direct = await widget.state.resolveOrDirect(link, _pwd.text.trim());
      if (direct && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('已按直链提交下载'),
            action: SnackBarAction(
              label: '查看',
              onPressed: widget.onOpenDownloads,
            ),
          ),
        );
      }
    } catch (e) {
      debugPrint('[ui] 解析失败: $e');
      if (mounted) setState(() => _error = e.toString());
    }
  }

  Future<void> _download(ShareFile file) async {
    try {
      await widget.state.downloadFile(file);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('已开始下载：${file.name}'),
          action: SnackBarAction(
            label: '查看',
            onPressed: widget.onOpenDownloads,
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('获取直链失败：$e')));
    }
  }

  Future<void> _copyDirectLink(ShareFile file) async {
    try {
      final link = await widget.state.getDirectLink(file);
      await Clipboard.setData(ClipboardData(text: link.url));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('已复制直链：${file.name}'),
          duration: const Duration(seconds: 2),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('获取直链失败：$e')),
      );
    }
  }

  Future<void> _downloadSelected() async {
    final files = widget.state.files
        .where((file) => !file.isDir && _selected.contains(file.fid))
        .toList(growable: false);
    if (files.isEmpty) return;
    setState(() => _batchBusy = true);
    try {
      final result = await widget.state.downloadFiles(files);
      if (!mounted) return;
      final failed = result.failures.isEmpty
          ? ''
          : '，${result.failures.length} 个失败';
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('已提交 ${result.started} 个下载$failed'),
          action: SnackBarAction(
            label: '查看',
            onPressed: widget.onOpenDownloads,
          ),
        ),
      );
      if (result.failures.isNotEmpty) {
        setState(() => _error = result.failures.join('\n'));
      }
    } finally {
      if (mounted) setState(() => _batchBusy = false);
    }
  }

  Future<void> _handleDrop(DropDoneDetails details) async {
    setState(() => _dragging = false);
    final link = await _extractLink(details);
    if (link == null || !mounted) return;
    _link.text = link;
    await _resolve();
  }

  Future<String?> _extractLink(DropDoneDetails details) async {
    final raw = details.rawText;
    if (raw != null) {
      final match = RegExp(r'https?://[^\s]+').firstMatch(raw);
      if (match != null) return match.group(0);
    }
    for (final item in details.files) {
      final path = item.path;
      final lower = path.toLowerCase();
      if (!lower.endsWith('.url') && !lower.endsWith('.txt')) continue;
      try {
        final text = await File(path).readAsString();
        final match = RegExp(r'https?://[^\s]+').firstMatch(text);
        if (match != null) return match.group(0);
      } catch (_) {
        // 文件可能正被其它程序占用，继续尝试下一个。
      }
    }
    return null;
  }

  void _openFolder(ShareFile folder) {
    setState(() => _selected.clear());
    widget.state.openFolder(folder);
  }

  void _goUp() {
    setState(() => _selected.clear());
    widget.state.goUp();
  }

  void _toggle(ShareFile file, bool? selected) {
    setState(() {
      if (selected == true) {
        _selected.add(file.fid);
      } else {
        _selected.remove(file.fid);
      }
    });
  }

  void _toggleAll(bool? selected) {
    setState(() {
      if (selected == true) {
        _selected.addAll(
          widget.state.files
              .where((file) => !file.isDir)
              .map((file) => file.fid),
        );
      } else {
        _selected.clear();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return RevisionBuilder(
      revision: widget.state.sessionRevision,
      enabled: widget.active,
      builder: (context) => _buildPage(context),
    );
  }

  Widget _buildPage(BuildContext context) {
    return DropTarget(
      onDragEntered: (_) => setState(() => _dragging = true),
      onDragExited: (_) => setState(() => _dragging = false),
      onDragDone: _handleDrop,
      child: Stack(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _hero(context),
                const SizedBox(height: 18),
                _inputCard(context),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  _errorCard(context),
                ],
                const SizedBox(height: 18),
                Expanded(child: _resultArea(context)),
              ],
            ),
          ),
          if (_dragging)
            Positioned.fill(
              child: IgnorePointer(
                child: Container(
                  margin: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: YunGetColors.surface.withValues(alpha: 0.94),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: YunGetColors.accent, width: 2),
                  ),
                  child: const Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.file_download_outlined,
                          size: 54,
                          color: YunGetColors.accent,
                        ),
                        SizedBox(height: 12),
                        Text(
                          '松开鼠标，开始解析',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        SizedBox(height: 4),
                        Text('支持直接拖入包含链接的 .url / .txt 文件'),
                      ],
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _hero(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '把链接放下，\n剩下交给云取。',
                style: Theme.of(context).textTheme.headlineMedium
                    ?.copyWith(height: 1.18),
              ),
              const SizedBox(height: 8),
              Text(
                '解析分享目录、批量取回文件，或直接输入一条 http(s) 下载地址。',
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: YunGetColors.inkMuted),
              ),
            ],
          ),
        ),
        const SizedBox(width: 24),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: YunGetColors.tealSoft,
            borderRadius: BorderRadius.circular(999),
          ),
          child: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.cloud_done_outlined,
                size: 16,
                color: YunGetColors.teal,
              ),
              SizedBox(width: 7),
              Text(
                '蓝奏云 · 直链',
                style: TextStyle(
                  color: YunGetColors.teal,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _inputCard(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: YunGetColors.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: YunGetColors.line),
        boxShadow: [
          BoxShadow(
            color: YunGetColors.ink.withValues(alpha: 0.05),
            blurRadius: 24,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        children: [
          LayoutBuilder(
            builder: (context, constraints) {
              final compact = constraints.maxWidth < 760;
              final fields = [
                Expanded(
                  flex: 5,
                  child: TextField(
                    key: const ValueKey('linkField'),
                    controller: _link,
                    onSubmitted: (_) => _resolve(),
                    decoration: InputDecoration(
                      labelText: '分享链接 / 直链',
                      hintText: 'https://wwxx.lanzouh.com/xxxxx',
                      prefixIcon: const Icon(Icons.link),
                      suffixIcon: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (_link.text.isNotEmpty)
                            IconButton(
                              icon: const Icon(Icons.clear, size: 18),
                              tooltip: '清空',
                              onPressed: () {
                                _link.clear();
                                setState(() {});
                              },
                            ),
                          IconButton(
                            icon: const Icon(Icons.content_paste_outlined, size: 18),
                            tooltip: '粘贴',
                            onPressed: () async {
                              final data = await Clipboard.getData(Clipboard.kTextPlain);
                              if (data?.text != null && data!.text!.isNotEmpty) {
                                _link.text = data.text!;
                                setState(() {});
                              }
                            },
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 10, height: 10),
                SizedBox(
                  width: compact ? null : 150,
                  child: TextField(
                    controller: _pwd,
                    onSubmitted: (_) => _resolve(),
                    decoration: const InputDecoration(
                      labelText: '提取码',
                      hintText: '可选',
                    ),
                  ),
                ),
                const SizedBox(width: 10, height: 10),
                FilledButton.icon(
                  key: const ValueKey('resolveButton'),
                  onPressed: widget.state.busy ? null : _resolve,
                  icon: widget.state.busy
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.arrow_forward),
                  label: const Text('解析'),
                ),
              ];
              return compact
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: fields,
                    )
                  : Row(children: fields);
            },
          ),
          if (widget.state.recentLinks.isNotEmpty) ...[
            const SizedBox(height: 12),
            SizedBox(
              height: 30,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: widget.state.recentLinks.length,
                separatorBuilder: (_, _) => const SizedBox(width: 8),
                itemBuilder: (context, index) {
                  final link = widget.state.recentLinks[index];
                  return ActionChip(
                    avatar: const Icon(Icons.history, size: 14),
                    label: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 260),
                      child: Text(link, overflow: TextOverflow.ellipsis),
                    ),
                    onPressed: () {
                      _link.text = link;
                      _resolve();
                    },
                  );
                },
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _errorCard(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 11, 8, 11),
      decoration: BoxDecoration(
        color: YunGetColors.accentSoft,
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: YunGetColors.accent.withValues(alpha: 0.3)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.error_outline, color: YunGetColors.danger, size: 20),
          const SizedBox(width: 10),
          Expanded(child: SelectableText(_error!)),
          IconButton(
            tooltip: '关闭',
            onPressed: () => setState(() => _error = null),
            icon: const Icon(Icons.close, size: 18),
          ),
        ],
      ),
    );
  }

  Widget _resultArea(BuildContext context) {
    final state = widget.state;
    if (state.sessionId == null) {
      return _emptyState(context);
    }
    final downloadables = state.files
        .where((file) => !file.isDir)
        .toList(growable: false);
    final allSelected =
        downloadables.isNotEmpty && _selected.length == downloadables.length;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 11, 12, 11),
            child: Row(
              children: [
                if (state.canGoUp)
                  IconButton(
                    tooltip: '上一级',
                    onPressed: _goUp,
                    icon: const Icon(Icons.arrow_upward),
                  ),
                const SizedBox(width: 4),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        state.sessionTitle,
                        style: Theme.of(context).textTheme.titleMedium,
                        overflow: TextOverflow.ellipsis,
                      ),
                      Text(
                        '${state.currentDirName} · ${state.files.length} 项',
                        style: Theme.of(context).textTheme.bodySmall
                            ?.copyWith(color: YunGetColors.inkMuted),
                      ),
                    ],
                  ),
                ),
                if (_selected.isNotEmpty) ...[
                  Text(
                    '已选 ${_selected.length} 个',
                    style: Theme.of(context).textTheme.labelMedium
                        ?.copyWith(color: YunGetColors.teal),
                  ),
                  const SizedBox(width: 10),
                  FilledButton.icon(
                    onPressed: _batchBusy ? null : _downloadSelected,
                    icon: _batchBusy
                        ? const SizedBox(
                            width: 15,
                            height: 15,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : const Icon(Icons.download, size: 18),
                    label: const Text('下载所选'),
                  ),
                ],
              ],
            ),
          ),
          const Divider(height: 1),
          Container(
            height: 34,
            padding: const EdgeInsets.symmetric(horizontal: 14),
            color: YunGetColors.paper.withValues(alpha: 0.65),
            child: Row(
              children: [
                SizedBox(
                  width: 38,
                  child: Checkbox(
                    value: allSelected,
                    onChanged: downloadables.isEmpty ? null : _toggleAll,
                    semanticLabel: '全选',
                  ),
                ),
                const Expanded(
                  flex: 6,
                  child: Text(
                    '名称',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                  ),
                ),
                const SizedBox(
                  width: 120,
                  child: Text(
                    '大小',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                  ),
                ),
                const SizedBox(
                  width: 70,
                  child: Text(
                    '操作',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: state.files.isEmpty
                ? const Center(child: Text('这个目录是空的'))
                : ListView.separated(
                    itemCount: state.files.length,
                    separatorBuilder: (_, _) =>
                        const Divider(height: 1, indent: 52),
                    itemBuilder: (context, index) =>
                        _fileRow(context, state.files[index]),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _fileRow(BuildContext context, ShareFile file) {
    final selected = _selected.contains(file.fid);
    return Material(
      color: selected
          ? YunGetColors.accentSoft.withValues(alpha: 0.45)
          : Colors.transparent,
      child: InkWell(
        onTap: file.isDir
            ? () => _openFolder(file)
            : () => _toggle(file, !selected),
        onDoubleTap: file.isDir ? null : () => _download(file),
        child: SizedBox(
          height: 46,
          child: Row(
            children: [
              SizedBox(
                width: 52,
                child: file.isDir
                    ? const Icon(
                        Icons.folder_outlined,
                        color: YunGetColors.amber,
                      )
                    : Checkbox(
                        value: selected,
                        onChanged: (value) => _toggle(file, value),
                      ),
              ),
              Expanded(
                flex: 6,
                child: Row(
                  children: [
                    Icon(
                      file.isDir
                          ? Icons.folder
                          : Icons.insert_drive_file_outlined,
                      size: 18,
                      color: file.isDir
                          ? YunGetColors.amber
                          : YunGetColors.inkMuted,
                    ),
                    const SizedBox(width: 9),
                    Expanded(
                      child: Text(file.name, overflow: TextOverflow.ellipsis),
                    ),
                  ],
                ),
              ),
              SizedBox(
                width: 120,
                child: Text(
                  file.isDir ? '文件夹' : formatBytes(file.size),
                  style: const TextStyle(
                    fontSize: 12,
                    color: YunGetColors.inkMuted,
                  ),
                ),
              ),
              SizedBox(
                width: 90,
                child: file.isDir
                    ? IconButton(
                        tooltip: '打开',
                        onPressed: () => _openFolder(file),
                        icon: const Icon(Icons.chevron_right),
                      )
                    : Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            tooltip: '复制直链',
                            iconSize: 18,
                            onPressed: () => _copyDirectLink(file),
                            icon: const Icon(Icons.link_outlined),
                          ),
                          IconButton(
                            tooltip: '下载',
                            iconSize: 18,
                            onPressed: () => _download(file),
                            icon: const Icon(Icons.download_outlined),
                          ),
                        ],
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _emptyState(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: YunGetColors.surface.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: YunGetColors.line),
      ),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: const BoxDecoration(
                color: YunGetColors.accentSoft,
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.content_paste_go,
                size: 30,
                color: YunGetColors.accent,
              ),
            ),
            const SizedBox(height: 14),
            Text(
              '粘贴链接后按 Enter',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 5),
            Text(
              '也可以把 .url / .txt 链接文件直接拖进窗口',
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: YunGetColors.inkMuted),
            ),
          ],
        ),
      ),
    );
  }
}
