# Umsetzungsplan: Batterymanager (Android)

Reihenfolge nach Abhängigkeiten. Eine Phase beginnt erst, wenn das Gate der vorigen bestanden ist.

| # | Story | Phase | Agent | Abhängig von | ACs |
|---|-------|-------|-------|--------------|-----|
| US-001 | Projekt-Identität & Grundgerüst | 0 | Pathfinder | — | 5 |
| US-002 | DB-Schema v1 & Abfragen als SQL-Dateien | 0 | Architect | US-001 | 5 |
| US-003 | Engine-Spezifikation & Golden-Fixtures | 0 | Architect | US-001 | 3 |
| US-004 | Phase-0-Mess-Spike (Logger-APK) | 0 | Skeptic | US-001 | 5 |
| US-005 | Kotlin-Messpipeline in :monitor | 1 | Pathfinder | US-002, US-003, US-004 | 6 |
| US-006 | Ladesitzung, Restzeit & Ziel-Alarm | 1 | Architect | US-005 | 5 |
| US-007 | Hintergrunddienst „Immer aktiv“ | 1 | Pathfinder | US-005 | 5 |
| US-008 | Live-Lade-Benachrichtigung | 1 | Advocate | US-006, US-007 | 6 |
| US-009 | Brücke Flutter ↔ Dienst | 1 | Architect | US-007 | 4 |
| US-010 | Theme & Design-System | 1 | Artisan | US-001 | 4 |
| US-011 | Übersicht-Screen | 1 | Advocate | US-009, US-010 | 5 |
| US-012 | Drei Graphen | 1 | Artisan | US-002, US-009, US-010 | 5 |
| US-013 | Onboarding & Berechtigungen | 1 | Advocate | US-009, US-011 | 4 |
| US-014 | Einstellungen & Diagnose | 1 | Advocate | US-009, US-011 | 3 |
| US-015 | Energie-Gate & Robustheit | 1 | Tuner | US-008, US-011, US-012, US-013, US-014 | 5 |
| US-016 | Modus „Nur beim Laden“ | 2 | Pathfinder | US-015 | 5 |

## Phasen-Gates
- **Phase 0 → 1:** US-001 bis US-004 erledigt. `spec/spike-results.md` dokumentiert Einheit, Vorzeichen und Latenz, die Fixtures stammen aus echten Mitschnitten.
- **Phase 1 → 2:** Mess-Gate (US-015) auf dem Release-Build bestanden, AK1–AK7 verifiziert.
- **Phase 2 fertig:** US-016 erledigt. Ist S2 nicht erfüllt, wird der Modus als „experimentell“ gekennzeichnet.

## Parallelisierbar
- US-002 und US-003 nach US-001.
- US-010 (Theme) parallel zu US-005 bis US-009.
- US-013 und US-014 parallel nach US-011.

## Manuelle Checkliste (Methoden manual-check / metric)
- [ ] AC-005 (US-001): Monochrome Small Icons `ic_stat_battery`, `ic_stat_charging`, `ic_stat_target` sowie ein adaptives App-Icon mit Monochrom-Ebene und Splash ohne weißen Blitz sind vorhanden.
- [ ] AC-011 (US-003): `spec/engine/spec.md` beschreibt Zustände, Konstanten und Ereignisse von Normalizer, ProfileDetector, SessionMachine, Eta und TargetAlarm.
- [ ] AC-014 (US-004): Eine Logger-APK ohne UI zeichnet alle Rohquellen plus `elapsedRealtime` auf und exportiert CSV.
- [ ] AC-015 (US-004): S1: Einheit, Vorzeichen und Aktualisierungsrate von CURRENT_NOW sind für 5 Szenarien (Schnelllader, PC-USB, Laden beim Spielen, kabellos falls vorhanden, Idle) dokumentiert; Mitschnitte sind als Fixtures übernommen.
- [ ] AC-016 (US-004): S2: Die Startlatenz Job → FGS ist über ≥ 10 Einsteckvorgänge (bei 15/50/85/92 %) gemessen und der Median dokumentiert.
- [ ] AC-017 (US-004): S3: Eine 24-h-Messung des Loggers im Modus „Immer aktiv“ zeigt 0 eigene Wakelocks/Alarme/Jobs und UID ≤ 25 mAh.
- [ ] AC-018 (US-004): S4/S5: Neustart nach Boot und Update sowie Kanalwechsel per Re-Post an der FGS-Benachrichtigung sind auf dem Gerät geprüft; Ergebnisse stehen in `spec/spike-results.md`.
- [ ] AC-029 (US-006): Das Ladeprofil lernt nur aus lückenlosen %-Schritten (EMA α = 0,3); nach 3 Lernsitzungen liegt die ETA bei halber Restzeit in ≥ 80 % der Sitzungen innerhalb max(±20 %, ±5 min) (AK6).
- [ ] AC-039 (US-008): Der Ziel-Alarm klingelt einmal auf `target_alarm`, erinnert nach 10 min und wechselt nach „Verstanden“ still zurück auf `charging`.
- [ ] AC-040 (US-008): Ab API 36 nutzt die Benachrichtigung `ProgressStyle` mit Zielmarke und Promotion; nie `setColorized`, kein Full-Screen-Intent, keine Custom-RemoteViews.
- [ ] AC-066 (US-015): Mess-Gate auf dem Release-Build bestanden: UID ≤ 5 mAh/24 h (Grenze 25), 0 eigene Wakeups/WakeLocks/Jobs, CPU beim Entladen ≤ 24 s/24 h, beim Laden ≤ 15 s/h, PSS `:monitor` ≤ 25 MB (AK3).
- [ ] AC-067 (US-015): Vorzeichen in 5/5 Szenarien korrekt; Leistung über 10 min innerhalb ±20 % der ΔCHARGE_COUNTER-Referenz (AK1).

## Generierte Test-Gerüste
Unter `test-stubs/` (27 Dateien). Sie werden beim Implementieren an dieselben Pfade im Projekt kopiert und sind zunächst ROT:
- `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us001ProjektInstrumentedTest.kt`
- `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us005MesspipelineInstrumentedTest.kt`
- `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us007DienstInstrumentedTest.kt`
- `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us008BenachrichtigungInstrumentedTest.kt`
- `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us009BrueckeInstrumentedTest.kt`
- `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us014EinstellungenInstrumentedTest.kt`
- `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us016NurBeimLadenInstrumentedTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us002DbSchemaTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us003EngineSpecTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us005MesspipelineTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us006SessionEtaTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us007DienstTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us008BenachrichtigungTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us009BrueckeTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us015GateTest.kt`
- `android/app/src/test/kotlin/de/mlampert/batterymanager/Us016NurBeimLadenTest.kt`
- `test/us/us_002_db_schema_test.dart`
- `test/us/us_009_bruecke_test.dart`
- `test/us/us_010_theme_test.dart`
- `test/us/us_010_theme_widget_test.dart`
- `test/us/us_011_uebersicht_test.dart`
- `test/us/us_011_uebersicht_widget_test.dart`
- `test/us/us_012_graphen_test.dart`
- `test/us/us_012_graphen_widget_test.dart`
- `test/us/us_013_onboarding_widget_test.dart`
- `test/us/us_014_einstellungen_widget_test.dart`
- `test/us/us_016_nur_beim_laden_widget_test.dart`
