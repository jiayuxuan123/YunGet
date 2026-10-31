import 'dart:io';

import 'package:file_selector/file_selector.dart';
import 'package:flutter/material.dart';

import '../app_state.dart';
import '../host/models.dart';
import '../theme.dart';

enum _SettingsSection { downloads, network, behavior, about }

/// 设置页：左侧分类，右侧表单，底部操作区固定可见。
///
/// 所有字段先写入本地草稿，点击保存后才通过 [AppState.saveSettings] 下发给宿主。
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.state});

  final AppState state;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _formKey = GlobalKey<FormState>();
  final _downloadDirController = TextEditingController();
  final _proxyHostController = TextEditingController();
  final _proxyPortController = TextEditingController();

  late HostSettings _draft;
  _SettingsSection _section = _SettingsSection.downloads;
  bool _initialized = false;
  bool _saving = false;
  bool _pickingDirectory = false;

  @override
  void initState() {
    super.initState();
    final settings = widget.state.settings;
    if (settings != null) _adoptSettings(settings);
    widget.state.settingsRevision.addListener(_onSettingsChanged);
  }

  @override
  void didUpdateWidget(covariant SettingsScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state == widget.state) return;
    oldWidget.state.settingsRevision.removeListener(_onSettingsChanged);
    widget.state.settingsRevision.addListener(_onSettingsChanged);
    _initialized = false;
    final settings = widget.state.settings;
    if (settings != null) _adoptSettings(settings);
  }

  @override
  void dispose() {
    widget.state.settingsRevision.removeListener(_onSettingsChanged);
    _downloadDirController.dispose();
    _proxyHostController.dispose();
    _proxyPortController.dispose();
    super.dispose();
  }

  void _onSettingsChanged() {
    if (!mounted || _saving) return;
    final settings = widget.state.settings;
    if (settings != null && (!_initialized || !_dirty)) {
      setState(() => _adoptSettings(settings));
    }
  }

  void _adoptSettings(HostSettings settings) {
    _draft = settings;
    _downloadDirController.text = settings.downloadDir;
    _proxyHostController.text = settings.proxyHost;
    _proxyPortController.text = settings.proxyPort > 0
        ? settings.proxyPort.toString()
        : '';
    _initialized = true;
  }

  HostSettings _materializedDraft() => _draft.copyWith(
    downloadDir: _downloadDirController.text.trim(),
    proxyHost: _proxyHostController.text.trim(),
    proxyPort: int.tryParse(_proxyPortController.text.trim()) ?? 0,
  );

  bool get _dirty {
    final saved = widget.state.settings;
    if (saved == null) return false;
    final draft = _materializedDraft();
    return draft.downloadDir != saved.downloadDir ||
        draft.connections != saved.connections ||
        draft.concurrentTasks != saved.concurrentTasks ||
        draft.speedLimitBytesPerSec != saved.speedLimitBytesPerSec ||
        draft.proxy != saved.proxy ||
        draft.proxyHost != saved.proxyHost ||
        draft.proxyPort != saved.proxyPort ||
        draft.readTimeoutMs != saved.readTimeoutMs ||
        draft.closeToTray != saved.closeToTray ||
        draft.notifyOnComplete != saved.notifyOnComplete ||
        draft.notifyOnFailure != saved.notifyOnFailure;
  }

  Future<void> _pickDownloadDirectory() async {
    setState(() => _pickingDirectory = true);
    try {
      final current = _downloadDirController.text.trim();
      final initialDirectory =
          current.isNotEmpty && Directory(current).existsSync()
          ? current
          : null;
      final selected = await getDirectoryPath(
        initialDirectory: initialDirectory,
        confirmButtonText: '选择此文件夹',
      );
      if (!mounted || selected == null) return;
      setState(() {
        _downloadDirController.text = selected;
        _draft = _draft.copyWith(downloadDir: selected);
      });
    } catch (error) {
      if (mounted) _showMessage('无法打开目录选择器：$error');
    } finally {
      if (mounted) setState(() => _pickingDirectory = false);
    }
  }

  Future<void> _openDownloadDirectory() async {
    final path = _downloadDirController.text.trim();
    if (path.isEmpty) {
      _showMessage('请先选择下载目录');
      return;
    }
    try {
      await widget.state.openDownloadDirectory(path);
    } catch (error) {
      if (mounted) _showMessage('无法打开下载目录：$error');
    }
  }

  Future<void> _save() async {
    FocusScope.of(context).unfocus();

    final directoryError = _validateDownloadDirectory(
      _downloadDirController.text,
    );
    if (directoryError != null) {
      _jumpTo(_SettingsSection.downloads);
      _showMessage(directoryError);
      return;
    }

    if (_draft.proxy == 'manual') {
      final hostError = _validateProxyHost(_proxyHostController.text);
      final portError = _validateProxyPort(_proxyPortController.text);
      if (hostError != null || portError != null) {
        _jumpTo(_SettingsSection.network);
        _showMessage(hostError ?? portError!);
        return;
      }
    }

    if (!(_formKey.currentState?.validate() ?? false)) {
      _showMessage('请检查标红的设置项');
      return;
    }

    final next = _materializedDraft();
    setState(() => _saving = true);
    try {
      await widget.state.saveSettings(next);
      if (!mounted) return;
      final saved = widget.state.settings;
      _formKey.currentState?.reset();
      setState(() {
        if (saved != null) _adoptSettings(saved);
      });
      _showMessage('设置已保存并同步到下载引擎');
    } catch (error) {
      if (mounted) _showMessage('保存失败：$error');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _resetDraft() {
    final saved = widget.state.settings;
    if (saved == null) return;
    _formKey.currentState?.reset();
    setState(() => _adoptSettings(saved));
  }

  void _jumpTo(_SettingsSection section) {
    if (_section == section) return;
    setState(() => _section = section);
  }

  void _showMessage(String message) {
    if (!mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  String? _validateDownloadDirectory(String? raw) {
    if ((raw ?? '').trim().isEmpty) return '请选择下载目录';
    return null;
  }

  String? _validateProxyHost(String? raw) {
    final value = (raw ?? '').trim();
    if (value.isEmpty) return '请输入代理地址';
    if (value.contains(RegExp(r'\s'))) return '代理地址不能包含空格';
    if (value.contains('://') ||
        value.contains('/') ||
        value.contains('?') ||
        value.contains('#')) {
      return '只填写主机名或 IP，不要带协议和路径';
    }

    final host = value.startsWith('[') && value.endsWith(']')
        ? value.substring(1, value.length - 1)
        : value;
    if (InternetAddress.tryParse(host) != null) return null;
    if (RegExp(r'^[0-9.]+$').hasMatch(host)) return '请输入有效的 IPv4 地址';
    if (host.length > 253) return '代理地址过长';

    final labelPattern = RegExp(
      r'^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$',
    );
    for (final label in host.split('.')) {
      if (!labelPattern.hasMatch(label)) return '请输入有效的主机名或 IP';
    }
    return null;
  }

  String? _validateProxyPort(String? raw) {
    final value = (raw ?? '').trim();
    if (value.isEmpty) return '请输入代理端口';
    final port = int.tryParse(value);
    if (port == null || port < 1 || port > 65535) {
      return '端口范围应为 1–65535';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    if (!_initialized) {
      final settings = widget.state.settings;
      if (settings == null) {
        return const Center(child: CircularProgressIndicator());
      }
      _adoptSettings(settings);
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(26, 22, 26, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildPageHeading(context),
          const SizedBox(height: 16),
          Expanded(
            child: LayoutBuilder(
              builder: (context, constraints) {
                final navigationWidth = constraints.maxWidth < 780
                    ? 184.0
                    : 222.0;
                return Container(
                  clipBehavior: Clip.antiAlias,
                  decoration: BoxDecoration(
                    color: YunGetColors.surface,
                    borderRadius: BorderRadius.circular(18),
                    border: Border.all(color: YunGetColors.line),
                    boxShadow: [
                      BoxShadow(
                        color: YunGetColors.ink.withValues(alpha: 0.05),
                        blurRadius: 28,
                        offset: const Offset(0, 10),
                      ),
                    ],
                  ),
                  child: Row(
                    children: [
                      SizedBox(
                        width: navigationWidth,
                        child: _buildNavigation(context),
                      ),
                      const VerticalDivider(width: 1),
                      Expanded(child: _buildContent(context)),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPageHeading(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('设置', style: theme.textTheme.headlineMedium),
              const SizedBox(height: 5),
              Text(
                '调整下载引擎和桌面端行为，所有设置都保存在本机。',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: YunGetColors.inkMuted,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 20),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: YunGetColors.tealSoft,
            borderRadius: BorderRadius.circular(999),
          ),
          child: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.bolt_rounded, size: 16, color: YunGetColors.teal),
              SizedBox(width: 6),
              Text(
                '保存后立即生效',
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

  Widget _buildNavigation(BuildContext context) {
    return ColoredBox(
      color: YunGetColors.paperDeep.withValues(alpha: 0.32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 20, 18, 12),
            child: Text(
              '设置分类',
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                color: YunGetColors.inkMuted,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.8,
              ),
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 16),
              children: [
                for (final section in _SettingsSection.values)
                  _buildNavigationTile(context, section),
              ],
            ),
          ),
          Container(
            margin: const EdgeInsets.fromLTRB(14, 0, 14, 16),
            padding: const EdgeInsets.all(13),
            decoration: BoxDecoration(
              color: YunGetColors.surface.withValues(alpha: 0.66),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: YunGetColors.line),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(
                  Icons.shield_outlined,
                  size: 18,
                  color: YunGetColors.teal,
                ),
                const SizedBox(width: 9),
                Expanded(
                  child: Text(
                    '设置与任务数据仅存储在本机。',
                    style: Theme.of(context).textTheme.bodySmall
                        ?.copyWith(color: YunGetColors.inkMuted, height: 1.4),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNavigationTile(BuildContext context, _SettingsSection section) {
    final selected = _section == section;
    final theme = Theme.of(context);
    final (icon, title, description) = switch (section) {
      _SettingsSection.downloads => (Icons.download_rounded, '下载', '目录、连接与速度'),
      _SettingsSection.network => (Icons.language_rounded, '网络', '代理与超时'),
      _SettingsSection.behavior => (
        Icons.notifications_active_outlined,
        '通知 / 托盘',
        '完成提醒与关闭行为',
      ),
      _SettingsSection.about => (Icons.info_outline_rounded, '关于', '版本与运行信息'),
    };

    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Semantics(
        button: true,
        selected: selected,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          decoration: BoxDecoration(
            color: selected ? YunGetColors.accentSoft : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: selected
                  ? YunGetColors.accent.withValues(alpha: 0.24)
                  : Colors.transparent,
            ),
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              borderRadius: BorderRadius.circular(12),
              onTap: () => setState(() => _section = section),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(11, 10, 9, 10),
                child: Row(
                  children: [
                    Container(
                      width: 34,
                      height: 34,
                      decoration: BoxDecoration(
                        color: selected
                            ? YunGetColors.accent
                            : YunGetColors.paper.withValues(alpha: 0.9),
                        borderRadius: BorderRadius.circular(9),
                      ),
                      child: Icon(
                        icon,
                        size: 18,
                        color: selected ? Colors.white : YunGetColors.inkMuted,
                      ),
                    ),
                    const SizedBox(width: 11),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            title,
                            style: theme.textTheme.labelLarge?.copyWith(
                              color: selected
                                  ? YunGetColors.ink
                                  : YunGetColors.inkMuted,
                              fontWeight: selected
                                  ? FontWeight.w700
                                  : FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            description,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: YunGetColors.inkMuted,
                              fontSize: 11,
                            ),
                          ),
                        ],
                      ),
                    ),
                    if (selected)
                      const Icon(
                        Icons.chevron_right_rounded,
                        size: 18,
                        color: YunGetColors.accent,
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildContent(BuildContext context) {
    return Column(
      children: [
        Expanded(
          child: Form(
            key: _formKey,
            child: ListView(
              key: ValueKey(_section),
              padding: const EdgeInsets.fromLTRB(28, 24, 30, 28),
              children: [
                _buildSectionHeading(context),
                const SizedBox(height: 22),
                ...switch (_section) {
                  _SettingsSection.downloads => _buildDownloadsSection(context),
                  _SettingsSection.network => _buildNetworkSection(context),
                  _SettingsSection.behavior => _buildBehaviorSection(context),
                  _SettingsSection.about => _buildAboutSection(context),
                },
              ],
            ),
          ),
        ),
        _buildSaveBar(context),
      ],
    );
  }

  Widget _buildSectionHeading(BuildContext context) {
    final theme = Theme.of(context);
    final (title, subtitle) = switch (_section) {
      _SettingsSection.downloads => ('下载', '决定文件保存到哪里，以及单个任务和全局下载的并发策略。'),
      _SettingsSection.network => ('网络', '配置下载引擎访问互联网的方式和等待响应的最长时间。'),
      _SettingsSection.behavior => ('通知 / 托盘', '控制任务结束提醒，以及点击窗口关闭按钮时的行为。'),
      _SettingsSection.about => ('关于 YunGet', '当前桌面端、下载引擎和运行环境概览。'),
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: theme.textTheme.titleLarge),
        const SizedBox(height: 6),
        Text(
          subtitle,
          style: theme.textTheme.bodyMedium?.copyWith(
            color: YunGetColors.inkMuted,
          ),
        ),
      ],
    );
  }

  List<Widget> _buildDownloadsSection(BuildContext context) {
    return [
      _buildPanel(
        context,
        title: '下载位置',
        description: '新任务会直接写入此目录；遇到同名文件时会自动加序号，不覆盖原文件。',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextFormField(
              key: const ValueKey('downloadDirectoryField'),
              controller: _downloadDirController,
              validator: _validateDownloadDirectory,
              onChanged: (value) {
                setState(() {
                  _draft = _draft.copyWith(downloadDir: value.trim());
                });
              },
              decoration: const InputDecoration(
                labelText: '下载目录',
                hintText: r'D:\Downloads\YunGet',
                prefixIcon: Icon(Icons.folder_outlined),
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 10,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: _pickingDirectory ? null : _pickDownloadDirectory,
                  icon: _pickingDirectory
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.drive_folder_upload_outlined),
                  label: Text(_pickingDirectory ? '正在选择…' : '选择文件夹'),
                ),
                TextButton.icon(
                  onPressed: _openDownloadDirectory,
                  icon: const Icon(Icons.open_in_new_rounded),
                  label: const Text('打开下载目录'),
                ),
              ],
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      _buildPanel(
        context,
        title: '传输性能',
        description: '连接数上限对齐 TurboDL 的 256，默认直接拉满；最终速度仍受服务端风控和链路质量限制。',
        child: Column(
          children: [
            _buildSliderSetting(
              context,
              label: '单任务连接数',
              description: '每个下载任务使用的并发连接数（引擎上限 256）',
              value: _draft.connections.toDouble(),
              min: 1,
              max: 256,
              divisions: 255,
              display: '${_draft.connections}',
              onChanged: (value) {
                setState(() {
                  _draft = _draft.copyWith(connections: value.round());
                });
              },
            ),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: _draft.connections == 256
                    ? null
                    : () => setState(
                        () => _draft = _draft.copyWith(connections: 256),
                      ),
                icon: const Icon(Icons.flash_on_outlined, size: 17),
                label: const Text('拉满到 256'),
              ),
            ),
            const Divider(height: 28),
            _buildSliderSetting(
              context,
              label: '同时下载任务数',
              description: '允许同时处于下载状态的任务数量',
              value: _draft.concurrentTasks.toDouble(),
              min: 1,
              max: 8,
              divisions: 7,
              display: '${_draft.concurrentTasks}',
              onChanged: (value) {
                setState(() {
                  _draft = _draft.copyWith(concurrentTasks: value.round());
                });
              },
            ),
            const Divider(height: 28),
            _buildSliderSetting(
              context,
              label: '全局限速',
              description: '所有下载任务共享此速度上限',
              value: (_draft.speedLimitBytesPerSec / (1024 * 1024))
                  .clamp(0.0, 50.0)
                  .toDouble(),
              min: 0,
              max: 50,
              divisions: 100,
              display: _draft.speedLimitBytesPerSec == 0
                  ? '不限速'
                  : '${(_draft.speedLimitBytesPerSec / (1024 * 1024)).toStringAsFixed(1)} MB/s',
              onChanged: (value) {
                setState(() {
                  _draft = _draft.copyWith(
                    speedLimitBytesPerSec: (value * 1024 * 1024).round(),
                  );
                });
              },
            ),
          ],
        ),
      ),
    ];
  }

  List<Widget> _buildNetworkSection(BuildContext context) {
    return [
      _buildPanel(
        context,
        title: '代理模式',
        description: '代理会用于分享解析和实际文件下载。手动模式需要填写可访问的 HTTP 代理。',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            LayoutBuilder(
              builder: (context, constraints) {
                final compact = constraints.maxWidth < 570;
                final itemWidth = compact
                    ? constraints.maxWidth
                    : (constraints.maxWidth - 24) / 3;
                return Wrap(
                  spacing: 12,
                  runSpacing: 10,
                  children: [
                    SizedBox(
                      width: itemWidth,
                      child: _buildProxyOption(
                        context,
                        value: 'system',
                        icon: Icons.desktop_windows_outlined,
                        title: '跟随系统',
                        description: '使用 Windows 当前代理',
                      ),
                    ),
                    SizedBox(
                      width: itemWidth,
                      child: _buildProxyOption(
                        context,
                        value: 'direct',
                        icon: Icons.bolt_outlined,
                        title: '直接连接',
                        description: '不使用任何代理',
                      ),
                    ),
                    SizedBox(
                      width: itemWidth,
                      child: _buildProxyOption(
                        context,
                        value: 'manual',
                        icon: Icons.tune_rounded,
                        title: '手动指定',
                        description: '填写主机与端口',
                      ),
                    ),
                  ],
                );
              },
            ),
            if (_draft.proxy == 'manual') ...[
              const SizedBox(height: 18),
              const Divider(height: 1),
              const SizedBox(height: 18),
              LayoutBuilder(
                builder: (context, constraints) {
                  final hostField = TextFormField(
                    key: const ValueKey('proxyHostField'),
                    controller: _proxyHostController,
                    validator: _validateProxyHost,
                    onChanged: (value) {
                      setState(() {
                        _draft = _draft.copyWith(proxyHost: value.trim());
                      });
                    },
                    decoration: const InputDecoration(
                      labelText: '代理地址',
                      hintText: '127.0.0.1',
                      prefixIcon: Icon(Icons.dns_outlined),
                    ),
                  );
                  final portField = TextFormField(
                    key: const ValueKey('proxyPortField'),
                    controller: _proxyPortController,
                    validator: _validateProxyPort,
                    keyboardType: TextInputType.number,
                    onChanged: (value) {
                      setState(() {
                        _draft = _draft.copyWith(
                          proxyPort: int.tryParse(value.trim()) ?? 0,
                        );
                      });
                    },
                    decoration: const InputDecoration(
                      labelText: '端口',
                      hintText: '7890',
                    ),
                  );
                  if (constraints.maxWidth < 520) {
                    return Column(
                      children: [
                        hostField,
                        const SizedBox(height: 12),
                        portField,
                      ],
                    );
                  }
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: hostField),
                      const SizedBox(width: 12),
                      SizedBox(width: 150, child: portField),
                    ],
                  );
                },
              ),
            ],
          ],
        ),
      ),
      const SizedBox(height: 16),
      _buildPanel(
        context,
        title: '连接超时',
        description: '远端服务器长时间没有响应时，下载引擎会放弃本次请求并报告失败。',
        child: _buildSliderSetting(
          context,
          label: '读取超时',
          description: '建议保持 30 秒，网络较慢时可适当增加',
          value: (_draft.readTimeoutMs / 1000).clamp(5.0, 300.0).toDouble(),
          min: 5,
          max: 300,
          divisions: 295,
          display: '${(_draft.readTimeoutMs / 1000).round()} 秒',
          onChanged: (value) {
            setState(() {
              _draft = _draft.copyWith(readTimeoutMs: (value * 1000).round());
            });
          },
        ),
      ),
    ];
  }

  Widget _buildProxyOption(
    BuildContext context, {
    required String value,
    required IconData icon,
    required String title,
    required String description,
  }) {
    final selected = _draft.proxy == value;
    return Semantics(
      button: true,
      selected: selected,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        decoration: BoxDecoration(
          color: selected
              ? YunGetColors.accentSoft.withValues(alpha: 0.7)
              : YunGetColors.paper.withValues(alpha: 0.52),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: selected ? YunGetColors.accent : YunGetColors.line,
            width: selected ? 1.5 : 1,
          ),
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () => setState(() {
              _draft = _draft.copyWith(proxy: value);
            }),
            child: Padding(
              padding: const EdgeInsets.all(13),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    icon,
                    size: 20,
                    color: selected
                        ? YunGetColors.accent
                        : YunGetColors.inkMuted,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          style: Theme.of(context).textTheme.labelLarge
                              ?.copyWith(
                                fontWeight: selected
                                    ? FontWeight.w700
                                    : FontWeight.w600,
                              ),
                        ),
                        const SizedBox(height: 3),
                        Text(
                          description,
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(color: YunGetColors.inkMuted),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 6),
                  Icon(
                    selected
                        ? Icons.radio_button_checked_rounded
                        : Icons.radio_button_off_rounded,
                    size: 18,
                    color: selected
                        ? YunGetColors.accent
                        : YunGetColors.inkMuted,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  List<Widget> _buildBehaviorSection(BuildContext context) {
    return [
      _buildPanel(
        context,
        title: '任务通知',
        description: '通知由 Windows 系统显示，点击通知会回到 YunGet 主界面。',
        child: Column(
          children: [
            _buildSwitchSetting(
              context,
              icon: Icons.task_alt_rounded,
              title: '下载完成时通知',
              description: '任务成功完成后显示一条系统通知',
              value: _draft.notifyOnComplete,
              onChanged: (value) {
                setState(() {
                  _draft = _draft.copyWith(notifyOnComplete: value);
                });
              },
            ),
            const Divider(height: 24),
            _buildSwitchSetting(
              context,
              icon: Icons.error_outline_rounded,
              title: '下载失败时通知',
              description: '任务失败后显示文件名和错误原因',
              value: _draft.notifyOnFailure,
              onChanged: (value) {
                setState(() {
                  _draft = _draft.copyWith(notifyOnFailure: value);
                });
              },
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      _buildPanel(
        context,
        title: '窗口与托盘',
        description: '下载过程中可以隐藏主窗口，让 YunGet 继续在后台运行。',
        child: _buildSwitchSetting(
          context,
          icon: Icons.keyboard_arrow_down_rounded,
          title: '关闭窗口时最小化到托盘',
          description: '关闭主窗口不会退出程序，可从托盘菜单恢复或退出',
          value: _draft.closeToTray,
          onChanged: (value) {
            setState(() {
              _draft = _draft.copyWith(closeToTray: value);
            });
          },
        ),
      ),
      const SizedBox(height: 16),
      Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: YunGetColors.amber.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: YunGetColors.amber.withValues(alpha: 0.38)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(
              Icons.info_outline_rounded,
              size: 19,
              color: YunGetColors.inkMuted,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                _draft.closeToTray
                    ? '关闭窗口后程序仍在托盘运行；要完全退出，请使用托盘菜单中的“退出 YunGet”。'
                    : '关闭窗口将直接退出 YunGet，正在进行的任务会被暂停并在下次启动后保留。',
                style: Theme.of(context).textTheme.bodySmall
                    ?.copyWith(color: YunGetColors.inkMuted),
              ),
            ),
          ],
        ),
      ),
    ];
  }

  List<Widget> _buildAboutSection(BuildContext context) {
    return [
      Container(
        padding: const EdgeInsets.all(22),
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [YunGetColors.accentSoft, YunGetColors.surface],
          ),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: YunGetColors.accent.withValues(alpha: 0.24),
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 66,
              height: 66,
              decoration: BoxDecoration(
                color: YunGetColors.accent,
                borderRadius: BorderRadius.circular(18),
                boxShadow: [
                  BoxShadow(
                    color: YunGetColors.accent.withValues(alpha: 0.24),
                    blurRadius: 18,
                    offset: const Offset(0, 7),
                  ),
                ],
              ),
              child: const Icon(
                Icons.cloud_download_rounded,
                size: 34,
                color: Colors.white,
              ),
            ),
            const SizedBox(width: 18),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(
                        '云取 YunGet',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(width: 10),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 3,
                        ),
                        decoration: BoxDecoration(
                          color: YunGetColors.surface.withValues(alpha: 0.78),
                          borderRadius: BorderRadius.circular(999),
                          border: Border.all(color: YunGetColors.line),
                        ),
                        child: const Text(
                          'Windows 桌面端',
                          style: TextStyle(
                            fontSize: 11,
                            color: YunGetColors.inkMuted,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 7),
                  Text(
                    '把分享链接变成稳定、可续传的下载任务。',
                    style: Theme.of(context).textTheme.bodyMedium
                        ?.copyWith(color: YunGetColors.inkMuted),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      _buildPanel(
        context,
        title: '运行信息',
        description: '当前安装的桌面程序和下载引擎版本。',
        child: Column(
          children: [
            _buildInfoRow(context, '应用版本', '0.3.0'),
            const Divider(height: 20),
            _buildInfoRow(context, '下载引擎', 'TurboDL 0.2.7.0'),
            const Divider(height: 20),
            _buildInfoRow(context, '运行平台', 'Windows'),
            const Divider(height: 20),
            _buildInfoRow(context, '数据存储', '仅本机'),
          ],
        ),
      ),
      const SizedBox(height: 16),
      _buildPanel(
        context,
        title: '桌面端能力',
        description: '当前版本已经接入以下下载与桌面能力。',
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: const [
            _FeatureTag(label: '多连接分段下载'),
            _FeatureTag(label: '断点续传'),
            _FeatureTag(label: '蓝奏云解析'),
            _FeatureTag(label: '拖放链接'),
            _FeatureTag(label: '系统托盘'),
            _FeatureTag(label: '完成通知'),
          ],
        ),
      ),
    ];
  }

  Widget _buildPanel(
    BuildContext context, {
    required String title,
    required String description,
    required Widget child,
  }) {
    return Container(
      padding: const EdgeInsets.all(19),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.46),
        borderRadius: BorderRadius.circular(15),
        border: Border.all(color: YunGetColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          Text(
            description,
            style: Theme.of(context).textTheme.bodySmall
                ?.copyWith(color: YunGetColors.inkMuted, height: 1.45),
          ),
          const SizedBox(height: 17),
          child,
        ],
      ),
    );
  }

  Widget _buildSliderSetting(
    BuildContext context, {
    required String label,
    required String description,
    required double value,
    required double min,
    required double max,
    required int divisions,
    required String display,
    required ValueChanged<double> onChanged,
  }) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: theme.textTheme.labelLarge?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    description,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: YunGetColors.inkMuted,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 16),
            Container(
              constraints: const BoxConstraints(minWidth: 72),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: YunGetColors.tealSoft,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                display,
                textAlign: TextAlign.center,
                style: theme.textTheme.labelMedium?.copyWith(
                  color: YunGetColors.teal,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Slider(
          value: value,
          min: min,
          max: max,
          divisions: divisions,
          label: display,
          onChanged: onChanged,
        ),
      ],
    );
  }

  Widget _buildSwitchSetting(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String description,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Container(
          width: 38,
          height: 38,
          decoration: BoxDecoration(
            color: value ? YunGetColors.tealSoft : YunGetColors.paper,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(
            icon,
            size: 20,
            color: value ? YunGetColors.teal : YunGetColors.inkMuted,
          ),
        ),
        const SizedBox(width: 13),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: theme.textTheme.labelLarge?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                description,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: YunGetColors.inkMuted,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 16),
        Switch(value: value, onChanged: onChanged),
      ],
    );
  }

  Widget _buildInfoRow(BuildContext context, String label, String value) {
    return Row(
      children: [
        Expanded(
          child: Text(
            label,
            style: Theme.of(context).textTheme.bodyMedium
                ?.copyWith(color: YunGetColors.inkMuted),
          ),
        ),
        Text(
          value,
          style: Theme.of(context).textTheme.labelLarge
              ?.copyWith(fontWeight: FontWeight.w700),
        ),
      ],
    );
  }

  Widget _buildSaveBar(BuildContext context) {
    final theme = Theme.of(context);
    final dirty = _dirty;
    final statusIcon = _saving
        ? Icons.sync_rounded
        : dirty
        ? Icons.circle
        : Icons.check_circle_outline_rounded;
    final statusColor = _saving
        ? YunGetColors.inkMuted
        : dirty
        ? YunGetColors.amber
        : YunGetColors.teal;
    final statusText = _saving
        ? '正在保存设置…'
        : dirty
        ? '有未保存的更改'
        : '所有设置已同步';

    return Container(
      padding: const EdgeInsets.fromLTRB(22, 13, 20, 13),
      decoration: BoxDecoration(
        color: YunGetColors.paper.withValues(alpha: 0.48),
        border: const Border(top: BorderSide(color: YunGetColors.line)),
      ),
      child: Row(
        children: [
          Icon(
            statusIcon,
            size: dirty && !_saving ? 9 : 18,
            color: statusColor,
          ),
          const SizedBox(width: 9),
          Expanded(
            child: Text(
              statusText,
              style: theme.textTheme.bodySmall?.copyWith(
                color: YunGetColors.inkMuted,
              ),
            ),
          ),
          if (dirty && !_saving)
            TextButton(onPressed: _resetDraft, child: const Text('还原更改')),
          const SizedBox(width: 8),
          FilledButton.icon(
            key: const ValueKey('saveSettingsButton'),
            onPressed: _saving || !widget.state.hostReady ? null : _save,
            icon: _saving
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Icon(Icons.save_outlined, size: 19),
            label: Text(_saving ? '保存中' : '保存设置'),
          ),
        ],
      ),
    );
  }
}

class _FeatureTag extends StatelessWidget {
  const _FeatureTag({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: YunGetColors.tealSoft.withValues(alpha: 0.8),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: YunGetColors.teal.withValues(alpha: 0.16)),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelSmall
            ?.copyWith(color: YunGetColors.teal, fontWeight: FontWeight.w600),
      ),
    );
  }
}
