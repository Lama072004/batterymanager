// UI-Widget-Tests mit FakeBackend: US-010 (Theme, keine Daueranimation), US-011 (Übersicht, Banner, AK2),
// US-012 (Graphen), US-013 (Onboarding), US-016 (Modus „Nur beim Laden“ Varianten).
import 'package:batterymanager/core/backend.dart';
import 'package:batterymanager/core/format.dart';
import 'package:batterymanager/core/model.dart';
import 'package:batterymanager/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

History demoHistory() {
  final to = DateTime(2026, 11, 6, 18).millisecondsSinceEpoch;
  final from = to - 24 * 3600 * 1000;
  final levels = <LevelPoint>[];
  for (var t = from; t < to - 2 * 3600 * 1000; t += 180000) {
    final i = (t - from) ~/ 180000;
    levels.add(LevelPoint(1, t, 900 - i, 900 - i, -2000, false));
  }
  return History(
    from: from,
    to: to,
    levels: levels,
    coverage: [Coverage(from, to - 2 * 3600 * 1000)],
    sessions: [
      ChargeSessionInfo(
        id: 1,
        startTs: from + 3600000,
        endTs: from + 7200000,
        startLevelPm: 230,
        endLevelPm: 810,
        energyMwh: 15000,
        peakMw: 25000,
        points: [for (var p = 230; p <= 810; p += 20) SessionPoint((p - 230) * 6, p, 25000 - (p - 230) * 20)],
      ),
    ],
    drain: const DrainStats(onPctPerH: 9.1, offPctPerH: 0.8, avgPctPerH: 4.2),
  );
}

Future<void> pumpApp(WidgetTester tester, FakeBackend b) async {
  // Smartphone-Maße (1080 × 2400 px, 420 dpi)
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 2.625;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(BatteryApp(backend: b));
  await tester.pump();
  await tester.pump();
}

Future<void> unpump(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox.shrink());
}

void main() {
  group('US-011: Übersicht', () {
    testWidgets('AC-050 (AK2): fehlende Leistung erscheint nie als „0 W“ / „0 mA“', (tester) async {
      await pumpApp(tester, FakeBackend(live: const Live(levelPm: 420, status: 2, plug: 1, etaState: 'none')));
      expect(find.textContaining('0${nnbsp}W'), findsNothing);
      expect(find.textContaining('0${nnbsp}mA'), findsNothing);
      expect(find.text('Lädt'), findsOneWidget);
      expect(find.text('Restzeit: —'), findsOneWidget);
      await unpump(tester);
    });

    testWidgets('AC-049: Hero zeigt %, Leistung und Restzeit beim Laden', (tester) async {
      await pumpApp(
        tester,
        FakeBackend(
          live: const Live(
            levelPm: 420, status: 2, plug: 1, powerMw: 18400, voltageMv: 4210, currentMa: 4370, tempDc: 312, health: 2,
            etaState: 'approx', etaTargetS: 1500, etaFullS: 4200, targetPct: 80, serviceRunning: true,
          ),
          history: demoHistory(),
        ),
      );
      expect(find.text('Lädt mit 18${nnbsp}W'), findsOneWidget);
      expect(find.text('80$nnbsp% in ca. 30${nnbsp}Min.'), findsOneWidget);
      expect(find.text('4,21${nnbsp}V'), findsOneWidget);
      expect(find.text('Gut'), findsOneWidget);
      expect(find.text('Ladeziel 80$nnbsp%'), findsOneWidget);
      await unpump(tester);
    });

    testWidgets('AC-051: höchstens ein Banner, Benachrichtigungen aus hat Vorrang', (tester) async {
      await pumpApp(
        tester,
        FakeBackend(
          status: const SystemStatus(notificationsEnabled: false, ignoringBatteryOptimizations: false),
          live: const Live(levelPm: 500, status: 3, plug: 0, serviceRunning: false),
        ),
      );
      expect(find.textContaining('Benachrichtigungen sind aus'), findsOneWidget);
      expect(find.textContaining('Hintergrunddienst beendet'), findsNothing);
      await unpump(tester);
    });

    testWidgets('AC-052: Ladeziel-Sheet ändert das Ziel über putSettings', (tester) async {
      final b = FakeBackend(live: const Live(levelPm: 500, status: 3, plug: 0, serviceRunning: true));
      await pumpApp(tester, b);
      await tester.tap(find.text('Ladeziel 80$nnbsp%'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('90$nnbsp%'));
      await tester.pump();
      expect(b.calls.any((c) => c.contains('targetPct: 90')), isTrue);
      await unpump(tester);
    });
  });

  group('US-010 / US-012: Theme und Graphen', () {
    testWidgets('AC-047: keine Daueranimation – nach dem Aufbau ist kein Frame geplant', (tester) async {
      await pumpApp(tester, FakeBackend(live: const Live(levelPm: 640, status: 3, plug: 0, serviceRunning: true), history: demoHistory()));
      await tester.pump(const Duration(milliseconds: 100));
      expect(tester.binding.hasScheduledFrame, isFalse);
      await unpump(tester);
    });

    testWidgets('AC-055/057/058: drei Graphen mit Semantik-Zusammenfassung und Lücken-Hinweis', (tester) async {
      await pumpApp(tester, FakeBackend(live: const Live(levelPm: 640, status: 3, plug: 0, serviceRunning: true), history: demoHistory()));
      await tester.scrollUntilVisible(find.text('Entladerate'), 300);
      expect(find.bySemanticsLabel(RegExp('Akkustand-Verlauf')), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('Ladeleistung des Ladevorgangs')), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('Entladerate: Bildschirm an')), findsOneWidget);
      expect(find.textContaining('Keine Daten'), findsOneWidget);
      expect(find.textContaining('23$nnbsp% → 81$nnbsp%'), findsOneWidget);
      await unpump(tester);
    });

    testWidgets('AC-059: Leer-Zustände mit Microcopy', (tester) async {
      await pumpApp(tester, FakeBackend(live: const Live(levelPm: 640, status: 3, plug: 0, serviceRunning: true)));
      expect(find.textContaining('Der Verlauf füllt sich ab jetzt'), findsOneWidget);
      await tester.scrollUntilVisible(find.textContaining('Dein Verbrauch erscheint'), 300);
      expect(find.textContaining('Noch kein Ladevorgang aufgezeichnet'), findsOneWidget);
      await unpump(tester);
    });

    testWidgets('AC-075: Modus „Nur beim Laden“ zeigt nur Ø-Verbrauch ohne Bildschirm-Split', (tester) async {
      await pumpApp(
        tester,
        FakeBackend(
          state: const AppState(onboarded: true, mode: AppState.modeChargingOnly),
          status: const SystemStatus(ignoringBatteryOptimizations: true),
          live: const Live(levelPm: 640, status: 3, plug: 0),
          history: History(from: 0, to: 1, drain: const DrainStats(avgPctPerH: 3.4, onlyAverage: true)),
        ),
      );
      await tester.scrollUntilVisible(find.text('Verbrauch (Durchschnitt)'), 300);
      expect(find.text('Ø zwischen Ladevorgängen'), findsOneWidget);
      expect(find.text('Bildschirm an'), findsNothing);
      await unpump(tester);
    });
  });

  group('US-013: Onboarding', () {
    testWidgets('AC-060/061: „Immer aktiv“ vorausgewählt, Berechtigungen mit Begründung, dann onboarded', (tester) async {
      final b = FakeBackend(state: const AppState(onboarded: false), status: const SystemStatus(ignoringBatteryOptimizations: false));
      await pumpApp(tester, b);
      expect(find.text('Wie soll der Batterymanager arbeiten?'), findsOneWidget);
      expect(find.text('Immer aktiv (empfohlen)'), findsOneWidget);
      await tester.scrollUntilVisible(find.text('Weiter'), 200);
      await tester.tap(find.text('Weiter'));
      await tester.pumpAndSettle();
      expect(find.textContaining('braucht der Batterymanager Benachrichtigungen'), findsOneWidget);
      await tester.tap(find.text('Weiter').last);
      await tester.pumpAndSettle();
      expect(find.textContaining('Empfohlen, damit Android den Dienst nicht beendet'), findsOneWidget);
      await tester.tap(find.text('Später'));
      await tester.pumpAndSettle();
      expect(b.calls.any((c) => c.contains('mode: 0') && c.contains('onboarded: true')), isTrue);
      await unpump(tester);
    });
  });

  group('Formatierung (de_DE)', () {
    test('Zahlen, Einheiten und Dauer', () {
      expect(fmtWatts(18400), '18${nnbsp}W');
      expect(fmtWatts(4500), '4,5${nnbsp}W');
      expect(fmtMa(1840), '1.840${nnbsp}mA');
      expect(fmtVolt(4210), '4,21${nnbsp}V');
      expect(fmtDuration(1500), '25${nnbsp}Min.');
      expect(fmtDuration(4200, step: 5), '1${nnbsp}Std. 10${nnbsp}Min.');
    });
  });
}
