import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme.dart';

class DesktopCommand {
  DesktopCommand({
    required this.label,
    required this.subtitle,
    required this.icon,
    required this.onSelected,
    this.shortcut = '',
    this.keywords = const [],
  });

  final String label;
  final String subtitle;
  final IconData icon;
  final VoidCallback onSelected;
  final String shortcut;
  final List<String> keywords;
}

List<DesktopCommand> filterDesktopCommands(
  List<DesktopCommand> commands,
  String query,
) {
  final keyword = query.trim().toLowerCase();
  if (keyword.isEmpty) return commands;
  return commands
      .where((command) {
        final haystack = [
          command.label,
          command.subtitle,
          command.shortcut,
          ...command.keywords,
        ].join(' ').toLowerCase();
        return haystack.contains(keyword);
      })
      .toList(growable: false);
}

Future<void> showCommandPalette(
  BuildContext context,
  List<DesktopCommand> commands,
) {
  return showDialog<void>(
    context: context,
    barrierColor: YunGetColors.ink.withValues(alpha: 0.28),
    builder: (_) => CommandPaletteDialog(commands: commands),
  );
}

class CommandPaletteDialog extends StatefulWidget {
  const CommandPaletteDialog({super.key, required this.commands});

  final List<DesktopCommand> commands;

  @override
  State<CommandPaletteDialog> createState() => _CommandPaletteDialogState();
}

class _CommandPaletteDialogState extends State<CommandPaletteDialog> {
  final TextEditingController _query = TextEditingController();
  final FocusNode _keyboard = FocusNode(debugLabel: 'command-palette');
  int _selected = 0;

  List<DesktopCommand> get _matches =>
      filterDesktopCommands(widget.commands, _query.text);

  @override
  void dispose() {
    _query.dispose();
    _keyboard.dispose();
    super.dispose();
  }

  void _run(DesktopCommand command) {
    Navigator.of(context).pop();
    command.onSelected();
  }

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;
    final matches = _matches;
    if (matches.isEmpty) return KeyEventResult.ignored;
    if (event.logicalKey == LogicalKeyboardKey.arrowDown) {
      setState(
        () => _selected = (_selected + 1).clamp(0, matches.length - 1).toInt(),
      );
      return KeyEventResult.handled;
    }
    if (event.logicalKey == LogicalKeyboardKey.arrowUp) {
      setState(
        () => _selected = (_selected - 1).clamp(0, matches.length - 1).toInt(),
      );
      return KeyEventResult.handled;
    }
    if (event.logicalKey == LogicalKeyboardKey.enter ||
        event.logicalKey == LogicalKeyboardKey.numpadEnter) {
      _run(matches[_selected.clamp(0, matches.length - 1).toInt()]);
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final matches = _matches;
    if (_selected >= matches.length) {
      _selected = matches.isEmpty ? 0 : matches.length - 1;
    }
    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 32, vertical: 72),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640, maxHeight: 520),
        child: Focus(
          focusNode: _keyboard,
          onKeyEvent: _onKey,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 10),
                child: TextField(
                  controller: _query,
                  autofocus: true,
                  onChanged: (_) => setState(() => _selected = 0),
                  decoration: InputDecoration(
                    hintText: '搜索操作、页面或任务…',
                    prefixIcon: const Icon(Icons.search),
                    suffixIcon: _query.text.isEmpty
                        ? null
                        : IconButton(
                            tooltip: '清空',
                            onPressed: () {
                              _query.clear();
                              setState(() => _selected = 0);
                            },
                            icon: const Icon(Icons.close),
                          ),
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 2,
                ),
                child: Row(
                  children: [
                    Text(
                      matches.length == widget.commands.length
                          ? '常用操作'
                          : '搜索结果',
                      style: Theme.of(context).textTheme.labelMedium
                          ?.copyWith(color: YunGetColors.inkMuted),
                    ),
                    const Spacer(),
                    Text(
                      '↑↓ 选择 · Enter 执行 · Esc 关闭',
                      style: Theme.of(context).textTheme.labelSmall
                          ?.copyWith(color: YunGetColors.inkMuted),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              const Divider(height: 1),
              Expanded(
                child: matches.isEmpty
                    ? const Center(child: Text('没有匹配的操作'))
                    : ListView.builder(
                        padding: const EdgeInsets.symmetric(vertical: 6),
                        itemCount: matches.length,
                        itemBuilder: (context, index) {
                          final command = matches[index];
                          final selected = index == _selected;
                          return Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 2,
                            ),
                            child: Material(
                              color: selected
                                  ? YunGetColors.accentSoft
                                  : Colors.transparent,
                              borderRadius: BorderRadius.circular(10),
                              child: InkWell(
                                borderRadius: BorderRadius.circular(10),
                                onTap: () => _run(command),
                                onHover: (_) =>
                                    setState(() => _selected = index),
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 12,
                                    vertical: 10,
                                  ),
                                  child: Row(
                                    children: [
                                      Container(
                                        width: 34,
                                        height: 34,
                                        decoration: BoxDecoration(
                                          color: selected
                                              ? Colors.white
                                              : YunGetColors.paper,
                                          borderRadius: BorderRadius.circular(
                                            9,
                                          ),
                                        ),
                                        child: Icon(command.icon, size: 19),
                                      ),
                                      const SizedBox(width: 12),
                                      Expanded(
                                        child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.start,
                                          children: [
                                            Text(
                                              command.label,
                                              style: const TextStyle(
                                                fontWeight: FontWeight.w600,
                                              ),
                                            ),
                                            Text(
                                              command.subtitle,
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                              style: Theme.of(context)
                                                  .textTheme
                                                  .bodySmall
                                                  ?.copyWith(
                                                    color:
                                                        YunGetColors.inkMuted,
                                                  ),
                                            ),
                                          ],
                                        ),
                                      ),
                                      if (command.shortcut.isNotEmpty)
                                        _KeyHint(label: command.shortcut),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          );
                        },
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _KeyHint extends StatelessWidget {
  const _KeyHint({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
      decoration: BoxDecoration(
        color: YunGetColors.paperDeep,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: YunGetColors.line),
      ),
      child: Text(
        label,
        style: const TextStyle(fontSize: 11, color: YunGetColors.inkMuted),
      ),
    );
  }
}
