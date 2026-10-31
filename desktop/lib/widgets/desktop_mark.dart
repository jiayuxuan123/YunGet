import 'package:flutter/material.dart';

/// 云取的桌面标记：一条云带压住下载箭头，形成自己的字形而不是通用云图标。
class DesktopMark extends StatelessWidget {
  const DesktopMark({
    super.key,
    this.size = 40,
    this.color = const Color(0xFFE85D3F),
  });

  final double size;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(painter: _MarkPainter(color)),
    );
  }
}

class _MarkPainter extends CustomPainter {
  const _MarkPainter(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final scale = size.width / 40;
    final cloud = Path()
      ..moveTo(9 * scale, 25 * scale)
      ..cubicTo(
        4 * scale,
        25 * scale,
        2 * scale,
        20 * scale,
        5 * scale,
        16 * scale,
      )
      ..cubicTo(
        6 * scale,
        10 * scale,
        12 * scale,
        7 * scale,
        17 * scale,
        10 * scale,
      )
      ..cubicTo(
        21 * scale,
        4 * scale,
        31 * scale,
        5 * scale,
        33 * scale,
        13 * scale,
      )
      ..cubicTo(
        39 * scale,
        14 * scale,
        40 * scale,
        22 * scale,
        34 * scale,
        26 * scale,
      )
      ..lineTo(10 * scale, 26 * scale)
      ..close();
    canvas.drawPath(cloud, Paint()..color = color);

    final cut = Paint()..color = Colors.white.withValues(alpha: 0.96);
    canvas.drawCircle(Offset(20 * scale, 18.5 * scale), 6.2 * scale, cut);
    final arrow = Path()
      ..moveTo(20 * scale, 12.5 * scale)
      ..lineTo(25 * scale, 18.5 * scale)
      ..lineTo(22 * scale, 18.5 * scale)
      ..lineTo(22 * scale, 24 * scale)
      ..lineTo(18 * scale, 24 * scale)
      ..lineTo(18 * scale, 18.5 * scale)
      ..lineTo(15 * scale, 18.5 * scale)
      ..close();
    canvas.drawPath(arrow, Paint()..color = color);
  }

  @override
  bool shouldRepaint(covariant _MarkPainter oldDelegate) =>
      oldDelegate.color != color;
}
