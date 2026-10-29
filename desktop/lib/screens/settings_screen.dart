import 'package:flutter/material.dart';

import '../app_state.dart';
import '../host/models.dart';

/// 设置页：与手机端同名的引擎参数，改完立即下发宿主并重建引擎客户端。
///
/// 这里不做"高级/简化"分层 —— 桌面用户至少要知道连接数与限速在哪，
/// 其余的默认值是经过实测的，改坏了只会更慢。
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.state});

  final AppState state;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late HostSettings _draft;
  bool _initialized = false;
  bool _saving = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final settings = widget.state.settings;
    if (!_initialized && settings != null) {
      _draft = settings;
      _initialized = true;
    }
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await widget.state.saveSettings(_draft);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已保存')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('保存失败：$e')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_initialized) {
      return const Center(child: CircularProgressIndicator());
    }
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        _section('下载'),
        TextFormField(
          initialValue: _draft.downloadDir,
          decoration: const InputDecoration(
            labelText: '下载目录',
            helperText: '文件直接写入这里；同名文件会自动加序号，不会覆盖',
            border: OutlineInputBorder(),
          ),
          onChanged: (v) => _draft = _draft.copyWith(downloadDir: v),
        ),
        const SizedBox(height: 16),
        _slider(
          label: '连接数（单任务）',
          value: _draft.connections.toDouble(),
          min: 1,
          max: 64,
          divisions: 63,
          display: '${_draft.connections}',
          onChanged: (v) => setState(() => _draft = _draft.copyWith(connections: v.round())),
        ),
        _slider(
          label: '同时下载任务数',
          value: _draft.concurrentTasks.toDouble(),
          min: 1,
          max: 8,
          divisions: 7,
          display: '${_draft.concurrentTasks}',
          onChanged: (v) => setState(() => _draft = _draft.copyWith(concurrentTasks: v.round())),
        ),
        _slider(
          label: '全局限速',
          value: (_draft.speedLimitBytesPerSec / 1024).clamp(0, 51200),
          min: 0,
          max: 51200,
          divisions: 100,
          display: _draft.speedLimitBytesPerSec == 0 ? '不限' : formatSpeed(_draft.speedLimitBytesPerSec),
          onChanged: (v) => setState(
            () => _draft = _draft.copyWith(speedLimitBytesPerSec: (v * 1024).round()),
          ),
        ),
        const SizedBox(height: 8),
        _section('网络'),
        DropdownButtonFormField<String>(
          initialValue: _draft.proxy,
          decoration: const InputDecoration(labelText: '代理', border: OutlineInputBorder()),
          items: const [
            DropdownMenuItem(value: 'system', child: Text('跟随系统')),
            DropdownMenuItem(value: 'direct', child: Text('直连（不使用代理）')),
            DropdownMenuItem(value: 'manual', child: Text('手动指定')),
          ],
          onChanged: (v) => setState(() => _draft = _draft.copyWith(proxy: v ?? 'system')),
        ),
        if (_draft.proxy == 'manual') ...[
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextFormField(
                  initialValue: _draft.proxyHost,
                  decoration: const InputDecoration(labelText: '代理地址', hintText: '127.0.0.1', border: OutlineInputBorder()),
                  onChanged: (v) => _draft = _draft.copyWith(proxyHost: v.trim()),
                ),
              ),
              const SizedBox(width: 12),
              SizedBox(
                width: 140,
                child: TextFormField(
                  initialValue: _draft.proxyPort == 0 ? '' : '${_draft.proxyPort}',
                  decoration: const InputDecoration(labelText: '端口', hintText: '7897', border: OutlineInputBorder()),
                  onChanged: (v) => _draft = _draft.copyWith(proxyPort: int.tryParse(v.trim()) ?? 0),
                ),
              ),
            ],
          ),
        ],
        const SizedBox(height: 16),
        _slider(
          label: '读超时',
          value: (_draft.readTimeoutMs / 1000).clamp(5, 300),
          min: 5,
          max: 300,
          divisions: 59,
          display: '${(_draft.readTimeoutMs / 1000).round()} 秒',
          onChanged: (v) => setState(() => _draft = _draft.copyWith(readTimeoutMs: (v * 1000).round())),
        ),
        const SizedBox(height: 20),
        Align(
          alignment: Alignment.centerRight,
          child: FilledButton.icon(
            onPressed: _saving ? null : _save,
            icon: const Icon(Icons.save),
            label: const Text('保存'),
          ),
        ),
      ],
    );
  }

  Widget _section(String title) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Text(title, style: Theme.of(context).textTheme.titleMedium),
      );

  Widget _slider({
    required String label,
    required double value,
    required double min,
    required double max,
    required int divisions,
    required String display,
    required ValueChanged<double> onChanged,
  }) {
    final clamped = value.clamp(min, max);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(width: 160, child: Text(label)),
          Expanded(
            child: Slider(
              value: clamped,
              min: min,
              max: max,
              divisions: divisions,
              label: display,
              onChanged: onChanged,
            ),
          ),
          SizedBox(width: 90, child: Text(display, textAlign: TextAlign.end)),
        ],
      ),
    );
  }
}
