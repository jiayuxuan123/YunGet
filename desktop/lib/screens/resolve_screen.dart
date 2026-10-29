import 'package:flutter/material.dart';

import '../app_state.dart';
import '../host/models.dart';

/// 解析页：粘贴分享链接 → 列出文件 → 下载。
///
/// 桌面端的粘贴习惯和手机不同（没有剪贴板监听），所以这里只有一个输入框 +
/// Ctrl+V 直接可用的默认行为；提取码单独一格，留空表示分享没有密码。
class ResolveScreen extends StatefulWidget {
  const ResolveScreen({super.key, required this.state, required this.onOpenDownloads});

  final AppState state;
  final VoidCallback onOpenDownloads;

  @override
  State<ResolveScreen> createState() => _ResolveScreenState();
}

class _ResolveScreenState extends State<ResolveScreen> {
  final TextEditingController _link = TextEditingController();
  final TextEditingController _pwd = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _link.dispose();
    _pwd.dispose();
    super.dispose();
  }

  Future<void> _resolve() async {
    final link = _link.text.trim();
    if (link.isEmpty) return;
    setState(() => _error = null);
    try {
      final direct = await widget.state.resolveOrDirect(link, _pwd.text.trim());
      if (direct && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: const Text('已按直链提交下载'), action: SnackBarAction(label: '查看', onPressed: widget.onOpenDownloads)),
        );
      }
    } catch (e) {
      debugPrint('[ui] 解析失败: $e');
      setState(() => _error = e.toString());
    }
  }

  Future<void> _download(ShareFile file) async {
    try {
      await widget.state.downloadFile(file);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已开始下载：${file.name}'), action: SnackBarAction(label: '查看', onPressed: widget.onOpenDownloads)),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('获取直链失败：$e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                flex: 4,
                child: TextField(
                  key: const ValueKey('linkField'),
                  controller: _link,
                  onSubmitted: (_) => _resolve(),
                  decoration: const InputDecoration(
                    labelText: '分享链接',
                    hintText: '粘贴网盘分享链接，例如 https://wwxx.lanzouh.com/xxxxx',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.link),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              SizedBox(
                width: 140,
                child: TextField(
                  controller: _pwd,
                  onSubmitted: (_) => _resolve(),
                  decoration: const InputDecoration(labelText: '提取码（可选）', border: OutlineInputBorder()),
                ),
              ),
              const SizedBox(width: 12),
              FilledButton.icon(
                key: const ValueKey('resolveButton'),
                onPressed: state.busy ? null : _resolve,
                icon: state.busy
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.search),
                label: const Text('解析'),
              ),
            ],
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          const SizedBox(height: 16),
          if (state.sessionId != null) ...[
            Row(
              children: [
                if (state.canGoUp)
                  IconButton(
                    tooltip: '上一级',
                    onPressed: state.goUp,
                    icon: const Icon(Icons.arrow_upward),
                  ),
                Expanded(
                  child: Text(
                    '${state.sessionTitle}  ·  ${state.currentDirName}',
                    style: Theme.of(context).textTheme.titleMedium,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Text('${state.files.length} 项', style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
            const Divider(),
          ],
          Expanded(
            child: state.sessionId == null
                ? const Center(child: Text('粘贴分享链接后点击「解析」'))
                : ListView.separated(
                    itemCount: state.files.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (context, i) {
                      final file = state.files[i];
                      return ListTile(
                        leading: Icon(file.isDir ? Icons.folder : Icons.insert_drive_file),
                        title: Text(file.name, overflow: TextOverflow.ellipsis),
                        subtitle: file.isDir ? const Text('文件夹') : Text(formatBytes(file.size)),
                        trailing: file.isDir
                            ? const Icon(Icons.chevron_right)
                            : IconButton(
                                tooltip: '下载',
                                onPressed: () => _download(file),
                                icon: const Icon(Icons.download),
                              ),
                        onTap: file.isDir ? () => state.openFolder(file) : () => _download(file),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
