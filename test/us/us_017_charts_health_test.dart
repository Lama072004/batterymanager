// US-017: verschiebbarer Akkustand-Verlauf, neues Ladeleistungs-Diagramm, Akku-Gesundheit (Trend).
import 'package:batterymanager/core/backend.dart';
import 'package:batterymanager/core/format.dart';
import 'package:batterymanager/core/model.dart';
import 'package:batterymanager/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

const _day = 86400000;

History _history({List<HealthPoint> health = const []}) {
  final to = DateTime.now().millisecondsSinceEpoch;
  final from = to - _day;
  final levelFrom = to - 7 * _day;
  final levels = <LevelPoint>[
    for (var t = levelFrom; t < to; t += 180000) LevelPoint(1, t, 500 + (t ~/ 180000) % 400, 500 + (t ~/ 180000) % 400, null, false),
  ];
  return History(
    from: from,
    to: to,
    levelFrom: levelFrom,
    levels: levels,
    coverage: [Coverage(levelFrom, to)],
    sessions: [
      ChargeSessionInfo(
        id: 1,
        startTs: to - 3 * 3600000,
        endTs: to - 3600000,
        startLevelPm: 300,
        endLevelPm: 900,
        points: [for (var p = 300; p <= 900; p += 10) SessionPoint((p - 300) * 9, p, p < 800 ? 18000 : 18000 - (p - 800) * 120)],
      ),
    ],
    health: health,
  );
}

Future<void> _pump(WidgetTester tester, FakeBackend b) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 2.625;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(BatteryApp(backend: b));
  await tester.pump();
  await tester.pump();
}

void main() {
  group('Akku-Gesundheit: Trend', () {
    test('Zu wenige Daten → kein Trend, aber aktueller Wert', () {
      final now = DateTime(2026, 11, 1).millisecondsSinceEpoch;
      final t = HealthTrend.of([HealthPoint(now, 4900, designMah: 5000), HealthPoint(now + _day, 4950, designMah: 5000)])!;
      expect(t.slopeMahPerDay, isNull);
      expect(t.healthPct, closeTo(99, 1.1));
    });

    test('Fallende Kapazität mit Ausreißer → robuste Steigung und 80-%-Prognose', () {
      final t0 = DateTime(2026, 1, 1).millisecondsSinceEpoch;
      final pts = <HealthPoint>[
        for (var d = 0; d <= 120; d += 4) HealthPoint(t0 + d * _day, 5000 - 1.5 * d + (d % 8 == 0 ? 20 : -20), designMah: 5000),
        HealthPoint(t0 + 60 * _day, 3000, designMah: 5000), // Ausreißer
      ];
      final t = HealthTrend.of(pts)!;
      expect(t.slopeMahPerDay!, closeTo(-1.5, 0.3));
      expect(t.nowMah, closeTo(5000 - 180, 30));
      // (4000 − 4820) / −1,5 ≈ 547 Tage nach der letzten Messung
      final days = (t.reach80Ts! - (t0 + 120 * _day)) / _day;
      expect(days, inInclusiveRange(450, 650));
      expect(t.lossPctPerMonth(5000)!, closeTo(0.91, 0.2));
    });

    test('Steigende/konstante Kapazität → keine 80-%-Prognose', () {
      final t0 = DateTime(2026, 1, 1).millisecondsSinceEpoch;
      final t = HealthTrend.of([for (var d = 0; d <= 30; d += 3) HealthPoint(t0 + d * _day, 4800 + d.toDouble())])!;
      expect(t.reach80Ts, isNull);
    });
  });

  group('Diagramme', () {
    testWidgets('Akkustand-Verlauf lässt sich zurückschieben und wieder auf „jetzt“ setzen', (tester) async {
      await _pump(tester, FakeBackend(live: const Live(levelPm: 640, status: 3, plug: 0, serviceRunning: true), history: _history()));
      expect(find.text('Letzte 24 Stunden'), findsOneWidget);
      expect(find.text('Jetzt'), findsNothing);
      await tester.tap(find.byTooltip('Früher'));
      await tester.pump();
      expect(find.text('Letzte 24 Stunden'), findsNothing);
      expect(find.text('Jetzt'), findsOneWidget);
      await tester.tap(find.text('Jetzt'));
      await tester.pump();
      expect(find.text('Letzte 24 Stunden'), findsOneWidget);
      // Wischen nach rechts = in die Vergangenheit
      await tester.drag(find.bySemanticsLabel(RegExp('Akkustand-Verlauf')), const Offset(200, 0));
      await tester.pump();
      expect(find.text('Jetzt'), findsOneWidget);
    });

    testWidgets('Ladeleistung zeigt Dauer und reagiert auf Tippen', (tester) async {
      await _pump(tester, FakeBackend(live: const Live(levelPm: 640, status: 3, plug: 0, serviceRunning: true), history: _history()));
      await tester.scrollUntilVisible(find.bySemanticsLabel(RegExp('Ladeleistung des Ladevorgangs')), 300);
      expect(find.textContaining('30$nnbsp% → 90$nnbsp%'), findsOneWidget);
      expect(find.textContaining('2${nnbsp}Std.'), findsOneWidget);
      await tester.ensureVisible(find.bySemanticsLabel(RegExp('Ladeleistung des Ladevorgangs')));
      await tester.pump();
      await tester.tap(find.bySemanticsLabel(RegExp('Ladeleistung des Ladevorgangs')));
      await tester.pump();
      expect(tester.takeException(), isNull);
    });

    testWidgets('Akku-Gesundheit: Platzhalter ohne Messung, Werte mit Messungen', (tester) async {
      await _pump(tester, FakeBackend(live: const Live(levelPm: 640, status: 3, plug: 0, serviceRunning: true), history: _history()));
      await tester.scrollUntilVisible(find.text('Akku-Gesundheit'), 300);
      expect(find.textContaining('Der erste Wert erscheint nach dem nächsten Laden'), findsOneWidget);

      final t0 = DateTime.now().millisecondsSinceEpoch - 90 * _day;
      final pts = [for (var d = 0; d <= 90; d += 3) HealthPoint(t0 + d * _day, 4900 - 2.0 * d, designMah: 5000, cycles: 200 + d)];
      await tester.pumpWidget(const SizedBox.shrink());
      await _pump(tester, FakeBackend(live: const Live(levelPm: 640, status: 3, plug: 0, serviceRunning: true), history: _history(health: pts)));
      await tester.scrollUntilVisible(find.bySemanticsLabel(RegExp('Kapazitätsverlauf')), 300);
      expect(find.text('94$nnbsp%'), findsOneWidget); // 4.720 / 5.000
      expect(find.text('290'), findsOneWidget);
      expect(find.textContaining('voraussichtlich ca.'), findsOneWidget);
    });
  });
}
