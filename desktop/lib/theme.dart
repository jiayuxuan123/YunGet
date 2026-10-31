import 'package:flutter/material.dart';

abstract final class YunGetColors {
  static const ink = Color(0xFF18212A);
  static const inkMuted = Color(0xFF59636D);
  static const paper = Color(0xFFF2EEE6);
  static const paperDeep = Color(0xFFE9E1D5);
  static const surface = Color(0xFFFFFCF7);
  static const line = Color(0xFFD8D0C4);
  static const accent = Color(0xFFE85D3F);
  static const accentSoft = Color(0xFFFFE7DF);
  static const teal = Color(0xFF23756D);
  static const tealSoft = Color(0xFFDDF0EB);
  static const amber = Color(0xFFF0B84A);
  static const danger = Color(0xFFC43D35);
}

ThemeData buildYunGetTheme() {
  final scheme =
      ColorScheme.fromSeed(
        seedColor: YunGetColors.accent,
        brightness: Brightness.light,
        surface: YunGetColors.surface,
      ).copyWith(
        primary: YunGetColors.accent,
        onPrimary: Colors.white,
        secondary: YunGetColors.teal,
        onSecondary: Colors.white,
        surface: YunGetColors.surface,
        onSurface: YunGetColors.ink,
        outline: YunGetColors.line,
        error: YunGetColors.danger,
      );

  final baseTextTheme = Typography.material2021(
    platform: TargetPlatform.windows,
  ).black;
  final textTheme = baseTextTheme
      .apply(
        fontFamily: 'Microsoft YaHei UI',
        fontFamilyFallback: const ['Segoe UI', 'Noto Sans SC'],
        bodyColor: YunGetColors.ink,
        displayColor: YunGetColors.ink,
      )
      .copyWith(
        displaySmall: baseTextTheme.displaySmall?.copyWith(
          fontFamily: 'Segoe UI Variable Display',
          fontWeight: FontWeight.w700,
          letterSpacing: -1.2,
          color: YunGetColors.ink,
        ),
        headlineMedium: baseTextTheme.headlineMedium?.copyWith(
          fontFamily: 'Microsoft YaHei UI',
          fontWeight: FontWeight.w700,
          letterSpacing: -0.5,
          color: YunGetColors.ink,
        ),
        titleLarge: baseTextTheme.titleLarge?.copyWith(
          fontFamily: 'Microsoft YaHei UI',
          fontWeight: FontWeight.w700,
          letterSpacing: -0.2,
          color: YunGetColors.ink,
        ),
        titleMedium: baseTextTheme.titleMedium?.copyWith(
          fontFamily: 'Microsoft YaHei UI',
          fontWeight: FontWeight.w600,
          color: YunGetColors.ink,
        ),
        bodyLarge: baseTextTheme.bodyLarge?.copyWith(height: 1.45),
        bodyMedium: baseTextTheme.bodyMedium?.copyWith(height: 1.45),
        labelLarge: baseTextTheme.labelLarge?.copyWith(
          fontWeight: FontWeight.w600,
        ),
      );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    textTheme: textTheme,
    fontFamily: 'Microsoft YaHei UI',
    fontFamilyFallback: const ['Segoe UI', 'Noto Sans SC'],
    scaffoldBackgroundColor: Colors.transparent,
    visualDensity: VisualDensity.compact,
    // Sparkle 在 Windows 软件渲染/高 DPI 下会反复生成粒子层，按钮点击明显更重。
    splashFactory: InkRipple.splashFactory,
    dividerTheme: const DividerThemeData(
      color: YunGetColors.line,
      thickness: 1,
    ),
    cardTheme: CardThemeData(
      color: YunGetColors.surface,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: const BorderSide(color: YunGetColors.line),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white.withValues(alpha: 0.72),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(11),
        borderSide: const BorderSide(color: YunGetColors.line),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(11),
        borderSide: const BorderSide(color: YunGetColors.line),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(11),
        borderSide: const BorderSide(color: YunGetColors.accent, width: 1.5),
      ),
      labelStyle: const TextStyle(color: YunGetColors.inkMuted),
      helperStyle: const TextStyle(color: YunGetColors.inkMuted),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(11)),
        textStyle: const TextStyle(fontWeight: FontWeight.w600),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
        side: const BorderSide(color: YunGetColors.line),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(11)),
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        foregroundColor: YunGetColors.inkMuted,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(9)),
      ),
    ),
    sliderTheme: const SliderThemeData(
      trackHeight: 3,
      thumbShape: RoundSliderThumbShape(enabledThumbRadius: 6),
      overlayShape: RoundSliderOverlayShape(overlayRadius: 14),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: YunGetColors.ink,
      contentTextStyle: const TextStyle(color: Colors.white),
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: YunGetColors.surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
    ),
    tooltipTheme: TooltipThemeData(
      decoration: BoxDecoration(
        color: YunGetColors.ink,
        borderRadius: BorderRadius.circular(7),
      ),
      textStyle: const TextStyle(color: Colors.white, fontSize: 12),
    ),
    scrollbarTheme: ScrollbarThemeData(
      thumbColor: WidgetStatePropertyAll(
        YunGetColors.inkMuted.withValues(alpha: 0.35),
      ),
      radius: const Radius.circular(8),
      thickness: const WidgetStatePropertyAll(7),
    ),
  );
}
