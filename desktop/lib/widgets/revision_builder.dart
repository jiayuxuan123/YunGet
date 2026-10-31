import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// Rebuilds only when a revision changes, and can pause while its page is hidden.
class RevisionBuilder extends StatefulWidget {
  const RevisionBuilder({
    super.key,
    required this.revision,
    required this.builder,
    this.enabled = true,
  });

  final ValueListenable<int> revision;
  final bool enabled;
  final WidgetBuilder builder;

  @override
  State<RevisionBuilder> createState() => _RevisionBuilderState();
}

class _RevisionBuilderState extends State<RevisionBuilder> {
  @override
  void initState() {
    super.initState();
    _listen();
  }

  @override
  void didUpdateWidget(covariant RevisionBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.revision != widget.revision ||
        oldWidget.enabled != widget.enabled) {
      oldWidget.revision.removeListener(_changed);
      _listen();
    }
  }

  void _listen() {
    if (widget.enabled) widget.revision.addListener(_changed);
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    widget.revision.removeListener(_changed);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.builder(context);
}
