
import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';

/// Fallback-Seed ohne Material You (design.md).
const kSeed = Color(0xFF4A6FA5);
const _tnum = [FontFeature.tabularFigures()];

/// Ein Farbpfad für alle Fälle: Systemfarbe bzw. Seed → ColorScheme.fromSeed.
ThemeData buildTheme(Color seed, Brightness b) {
  final s = ColorScheme.fromSeed(seedColor: seed, brightness: b);
  final base = ThemeData(colorScheme: s, useMaterial3: true);
  TextStyle? n(TextStyle? t) => t?.copyWith(fontFeatures: _tnum);
  final tt = base.textTheme;
  return base.copyWith(
    textTheme: tt.copyWith(
      displayLarge: n(tt.displayLarge)?.copyWith(fontWeight: FontWeight.w300, letterSpacing: -1.5),
      headlineSmall: n(tt.headlineSmall),
      titleLarge: n(tt.titleLarge),
      titleMedium: n(tt.titleMedium),
      bodyLarge: n(tt.bodyLarge),
      bodyMedium: n(tt.bodyMedium),
      labelMedium: n(tt.labelMedium),
      labelSmall: n(tt.labelSmall),
    ),
    extensions: [BatteryColors.from(s)],
    cardTheme: CardThemeData(
      elevation: 0,
      margin: EdgeInsets.zero,
      color: s.surfaceContainerLow,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    ),
    // Keine Übergangs-Animationen (Stromsparen, ruhige UI).
    pageTransitionsTheme: const PageTransitionsTheme(builders: {
      TargetPlatform.android: _NoTransition(),
    }),
    splashFactory: NoSplash.splashFactory,
  );
}

class _NoTransition extends PageTransitionsBuilder {
  const _NoTransition();
  @override
  Widget buildTransitions<T>(PageRoute<T> r, BuildContext c, Animation<double> a, Animation<double> s, Widget child) => child;
}

/// Zustandsfarben, harmonisiert mit der (Material-You-)Primärfarbe.
@immutable
class BatteryColors extends ThemeExtension<BatteryColors> {
  const BatteryColors({
    required this.charge,
    required this.chargeContainer,
    required this.onChargeContainer,
    required this.warm,
    required this.hot,
    required this.gap,
  });

  final Color charge, chargeContainer, onChargeContainer, warm, hot, gap;

  factory BatteryColors.from(ColorScheme s) {
    ColorScheme role(int src) => ColorScheme.fromSeed(
          seedColor: Color(src).harmonizeWith(s.primary),
          brightness: s.brightness,
          dynamicSchemeVariant: DynamicSchemeVariant.fidelity,
        );
    final c = role(0xFF2E9E4F), w = role(0xFFE8A13A);
    return BatteryColors(
      charge: c.primary,
      chargeContainer: c.primaryContainer,
      onChargeContainer: c.onPrimaryContainer,
      warm: w.primary,
      hot: s.error,
      gap: s.outline,
    );
  }

  @override
  BatteryColors copyWith({Color? charge, Color? chargeContainer, Color? onChargeContainer, Color? warm, Color? hot, Color? gap}) =>
      BatteryColors(
        charge: charge ?? this.charge,
        chargeContainer: chargeContainer ?? this.chargeContainer,
        onChargeContainer: onChargeContainer ?? this.onChargeContainer,
        warm: warm ?? this.warm,
        hot: hot ?? this.hot,
        gap: gap ?? this.gap,
      );

  @override
  BatteryColors lerp(BatteryColors? o, double t) => o == null
      ? this
      : BatteryColors(
          charge: Color.lerp(charge, o.charge, t)!,
          chargeContainer: Color.lerp(chargeContainer, o.chargeContainer, t)!,
          onChargeContainer: Color.lerp(onChargeContainer, o.onChargeContainer, t)!,
          warm: Color.lerp(warm, o.warm, t)!,
          hot: Color.lerp(hot, o.hot, t)!,
          gap: Color.lerp(gap, o.gap, t)!,
        );
}

extension BatteryColorsX on BuildContext {
  BatteryColors get battery => Theme.of(this).extension<BatteryColors>()!;
}
