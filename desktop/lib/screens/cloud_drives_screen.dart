import 'package:flutter/material.dart';
import '../theme.dart';

/// 网盘平台数据项（对标移动端支持平台与账号管理）
class CloudPlatformItem {
  const CloudPlatformItem({
    required this.name,
    required this.category,
    required this.loginMethod,
    required this.capabilities,
    required this.description,
    required this.color,
    required this.icon,
    this.warning,
  });

  final String name;
  final String category;
  final String loginMethod;
  final List<String> capabilities;
  final String description;
  final Color color;
  final IconData icon;
  final String? warning;
}

/// 对标移动端「支持平台与账号」页：展示十个网盘能力与状态
class CloudDrivesScreen extends StatefulWidget {
  const CloudDrivesScreen({
    super.key,
    required this.onNavigateToResolve,
  });

  final VoidCallback onNavigateToResolve;

  @override
  State<CloudDrivesScreen> createState() => _CloudDrivesScreenState();
}

class _CloudDrivesScreenState extends State<CloudDrivesScreen> {
  static const _platforms = <CloudPlatformItem>[
    CloudPlatformItem(
      name: '蓝奏云',
      category: '直链网盘',
      loginMethod: '支持免登录直下 / 网页登录',
      capabilities: ['免登录直下', '提取码解析', '文件夹翻页', '多线程直写'],
      description: '完全支持分享解析、密码提取与文件夹浏览。桌面端原生直链取回与多线程分片下载。',
      color: Color(0xFF2563EB),
      icon: Icons.cloud_done_outlined,
    ),
    CloudPlatformItem(
      name: '夸克网盘',
      category: '大容量网盘',
      loginMethod: 'WebView Cookie 登录',
      capabilities: ['分享解析', '目录浏览', '提取直链', '分片并发'],
      description: '未登录时支持公开文件直下；登录后可直接浏览分享文件夹与全速取链。',
      color: Color(0xFFE85D3F),
      icon: Icons.cloud_queue_outlined,
    ),
    CloudPlatformItem(
      name: 'UC 网盘',
      category: '大容量网盘',
      loginMethod: 'WebView Cookie 登录',
      capabilities: ['分享解析', '目录浏览', '提取直链', '高速下载'],
      description: '阿里系架构，与夸克接口相通；支持大文件分片提取与多连接拉取。',
      color: Color(0xFFF97316),
      icon: Icons.cloud_outlined,
    ),
    CloudPlatformItem(
      name: '迅雷云盘',
      category: '下载网盘',
      loginMethod: '账号密码 / 短信验证码',
      capabilities: ['分享解析', '取链后自动清理', '分片并发'],
      description: '取链后自动清理云端临时转存目录，不占用个人网盘存储空间。',
      color: Color(0xFF0284C7),
      icon: Icons.bolt_outlined,
    ),
    CloudPlatformItem(
      name: '123 云盘',
      category: '极速网盘',
      loginMethod: '账号密码换取 JWT',
      capabilities: ['免客户端', '目录浏览', '多线程直下'],
      description: '不限速直链分发；解析后可一键拉取全部文件。',
      color: Color(0xFF10B981),
      icon: Icons.speed_outlined,
    ),
    CloudPlatformItem(
      name: '139 网盘（和彩云）',
      category: '运营商网盘',
      loginMethod: '网页快捷登录',
      capabilities: ['分享解析', '多线程并发', '断点续传'],
      description: '支持移动和彩云分享链接解析与大文件断点续传。',
      color: Color(0xFF06B6D4),
      icon: Icons.cloud_sync_outlined,
    ),
    CloudPlatformItem(
      name: '115 网盘',
      category: '大文件网盘',
      loginMethod: '应用内登录页',
      capabilities: ['大文件分片', '多线程并发', '直链提取'],
      description: '支持超大文件分享解析，建议单任务连接数设置为 16~64。',
      color: Color(0xFF8B5CF6),
      icon: Icons.storage_outlined,
    ),
    CloudPlatformItem(
      name: '光鸭云盘',
      category: '免登直链',
      loginMethod: '免登录直下',
      capabilities: ['免登录直下', '提取码自动识别', '分片并发'],
      description: '无需账号即可直接取回分享文件，链接粘贴即可下载。',
      color: Color(0xFFF59E0B),
      icon: Icons.public_outlined,
    ),
    CloudPlatformItem(
      name: '蓝奏云优享版',
      category: '专有版',
      loginMethod: '账号密码',
      capabilities: ['专有直链', '提取码识别', '分片并发'],
      description: '优享版专有解析通道，支持专属域名的分享提取。',
      color: Color(0xFF6366F1),
      icon: Icons.stars_outlined,
    ),
    CloudPlatformItem(
      name: '百度网盘',
      category: '大容量网盘',
      loginMethod: '网页登录',
      capabilities: ['分享解析', '目录浏览'],
      description: '支持分享目录解析与文件取链。',
      warning: '频繁解析可能触发账号风控，建议优先使用其他网盘或免登录平台。',
      color: Color(0xFFDC2626),
      icon: Icons.warning_amber_rounded,
    ),
  ];

  String _filter = '全部';

  @override
  Widget build(BuildContext context) {
    final categories = ['全部', '免登直下', '大容量网盘', '运营商网盘'];
    final filtered = _platforms.where((p) {
      if (_filter == '全部') return true;
      if (_filter == '免登直下') {
        return p.capabilities.contains('免登录直下');
      }
      if (_filter == '大容量网盘') {
        return p.category == '大容量网盘' || p.category == '下载网盘';
      }
      if (_filter == '运营商网盘') return p.category == '运营商网盘';
      return true;
    }).toList();

    return Padding(
      padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '支持平台与网盘',
                      style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                            fontWeight: FontWeight.w800,
                            letterSpacing: -0.5,
                          ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      '对标移动端 10 个网盘平台。粘贴分享链接自动识别，直链交给 TurboDL 多线程引擎拉取。',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            color: YunGetColors.inkMuted,
                          ),
                    ),
                  ],
                ),
              ),
              FilledButton.icon(
                onPressed: widget.onNavigateToResolve,
                icon: const Icon(Icons.add_link_rounded, size: 18),
                label: const Text('去粘贴解析'),
                style: FilledButton.styleFrom(
                  backgroundColor: YunGetColors.accent,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          // 分类切换芯片
          Row(
            children: categories.map((cat) {
              final active = _filter == cat;
              return Padding(
                padding: const EdgeInsets.only(right: 8),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 140),
                  curve: Curves.easeOutCubic,
                  child: ChoiceChip(
                    label: Text(cat),
                    selected: active,
                    onSelected: (_) => setState(() => _filter = cat),
                    selectedColor: YunGetColors.accentSoft,
                    backgroundColor: YunGetColors.surface,
                    labelStyle: TextStyle(
                      fontSize: 12,
                      fontWeight: active ? FontWeight.w700 : FontWeight.w500,
                      color: active ? YunGetColors.accent : YunGetColors.ink,
                    ),
                    side: BorderSide(
                      color: active ? YunGetColors.accent : YunGetColors.line,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
          const SizedBox(height: 16),
          Expanded(
            child: GridView.builder(
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                maxCrossAxisExtent: 420,
                mainAxisExtent: 186,
                crossAxisSpacing: 14,
                mainAxisSpacing: 14,
              ),
              itemCount: filtered.length,
              itemBuilder: (context, index) {
                final item = filtered[index];
                return _PlatformCard(
                  item: item,
                  onAction: widget.onNavigateToResolve,
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _PlatformCard extends StatefulWidget {
  const _PlatformCard({required this.item, required this.onAction});

  final CloudPlatformItem item;
  final VoidCallback onAction;

  @override
  State<_PlatformCard> createState() => _PlatformCardState();
}

class _PlatformCardState extends State<_PlatformCard> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        curve: Curves.easeOutCubic,
        transform: Matrix4.translationValues(0, _hovered ? -3 : 0, 0),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: YunGetColors.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: _hovered ? item.color.withValues(alpha: 0.6) : YunGetColors.line,
            width: _hovered ? 1.5 : 1,
          ),
          boxShadow: [
            BoxShadow(
              color: _hovered
                  ? item.color.withValues(alpha: 0.12)
                  : Colors.black.withValues(alpha: 0.03),
              blurRadius: _hovered ? 18 : 8,
              offset: Offset(0, _hovered ? 6 : 3),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: item.color.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(item.icon, color: item.color, size: 20),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item.name,
                        style: const TextStyle(
                          fontWeight: FontWeight.w800,
                          fontSize: 15,
                          color: YunGetColors.ink,
                        ),
                      ),
                      Text(
                        item.loginMethod,
                        style: TextStyle(
                          fontSize: 11,
                          color: YunGetColors.inkMuted.withValues(alpha: 0.9),
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
                InkWell(
                  onTap: widget.onAction,
                  borderRadius: BorderRadius.circular(8),
                  child: Padding(
                    padding: const EdgeInsets.all(6),
                    child: Icon(
                      Icons.arrow_forward_rounded,
                      size: 16,
                      color: _hovered ? item.color : YunGetColors.inkMuted,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              item.description,
              style: const TextStyle(
                fontSize: 12,
                color: YunGetColors.inkMuted,
                height: 1.4,
              ),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            if (item.warning != null) ...[
              const SizedBox(height: 4),
              Text(
                item.warning!,
                style: const TextStyle(
                  fontSize: 11,
                  color: YunGetColors.danger,
                  fontWeight: FontWeight.w600,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
            const Spacer(),
            Wrap(
              spacing: 6,
              runSpacing: 4,
              children: item.capabilities.map((cap) {
                return Container(
                  padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                  decoration: BoxDecoration(
                    color: YunGetColors.paper,
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: YunGetColors.line),
                  ),
                  child: Text(
                    cap,
                    style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                      color: YunGetColors.ink,
                    ),
                  ),
                );
              }).toList(),
            ),
          ],
        ),
      ),
    );
  }
}
