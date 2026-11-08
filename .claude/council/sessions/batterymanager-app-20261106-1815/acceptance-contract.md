# Acceptance Contract: Batterymanager (Android)
Session: batterymanager-app-20261106-1815 | PRD: prd.md | Status: locked

## Quality Gates
| Gate | Command | Required |
|------|---------|----------|
| analyze | `flutter analyze` | yes |
| dart-tests | `flutter test` | yes |
| kotlin-tests | `cd android && ./gradlew :app:testDebugUnitTest` | yes |
| build | `flutter build apk --release --split-per-abi` | yes |
| release-check | `tool/check_release.sh` | yes |
| energy-gate | Mess-Gate laut US-015 (manuell, Release-Build) | yes |

## Acceptance Criteria

### US-001: Projekt-Identität & Grundgerüst

#### AC-001: applicationId und namespace sind `de.mlampert.batterymanager`; der Release-Build ist mit einem eigenen Keystore signiert (nicht Debug).
- **Method:** build-output
- **Test:** `tool/check_release.sh (Merged Manifest, apksigner, pubspec)`
- **Status:** blocked-user
- **Evidence:** applicationId ok (tool/check_release.sh); Signatur noch Debug-Schlüssel – android/key.properties anlegen
- **Verified-by:** Conductor 2026-11-06

#### AC-002: minSdk 24, targetSdk 36, `allowBackup=false`; das Merged Manifest enthält keine `INTERNET`-, `WAKE_LOCK`- oder `SCHEDULE_EXACT_ALARM`-Berechtigung.
- **Method:** build-output
- **Test:** `tool/check_release.sh (Merged Manifest, apksigner, pubspec)`
- **Status:** verified
- **Evidence:** tool/check_release.sh: minSdk 24, targetSdk 36, allowBackup=false, kein INTERNET/WAKE_LOCK/EXACT_ALARM
- **Verified-by:** Conductor 2026-11-06

#### AC-003: `cupertino_icons` ist entfernt; Laufzeit-Abhängigkeiten sind nur `sqflite` und `dynamic_color` (dev: `pigeon`, `sqflite_common_ffi`, `flutter_lints`).
- **Method:** build-output
- **Test:** `tool/check_release.sh (Merged Manifest, apksigner, pubspec)`
- **Status:** verified
- **Evidence:** pubspec.yaml; tool/check_release.sh: kein cupertino_icons
- **Verified-by:** Conductor 2026-11-06

#### AC-004: Die Kanäle `monitor` (MIN), `charging` (HIGH ohne Ton), `target_alarm` (HIGH, USAGE_ALARM, Vibration), `heat` (HIGH ohne Ton) und `status` (DEFAULT) existieren mit deutschen Namen.
- **Method:** integration-test
- **Test:** `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us001ProjektInstrumentedTest.kt`
- **Status:** pending-device
- **Evidence:** Us001ProjektInstrumentedTest.kt geschrieben; braucht Gerät: ./gradlew :app:connectedDebugAndroidTest
- **Verified-by:** Conductor 2026-11-06

#### AC-005: Monochrome Small Icons `ic_stat_battery`, `ic_stat_charging`, `ic_stat_target` sowie ein adaptives App-Icon mit Monochrom-Ebene und Splash ohne weißen Blitz sind vorhanden.
- **Method:** manual-check
- **Test:** `Manuelle Prüfung auf dem Gerät (Checkliste in plan.md)`
- **Status:** pending-manual
- **Evidence:** Icons angelegt (ic_stat_*, adaptives Icon mit Monochrom-Ebene); Sichtprüfung auf dem Gerät
- **Verified-by:** Conductor 2026-11-06

### US-002: DB-Schema v1 & Abfragen als SQL-Dateien

#### AC-006: `spec/db/001_init.sql` legt `span`, `sample` (inkl. `tz_min`), `charge_session`, `session_point`, `charge_profile`, `device_profile`, `day_stat`, `kv` und die Views `pf`, `sample_n`, `point_n`, `session_n` an und nutzt nur SQLite-3.9-Syntax.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us002DbSchemaTest.kt`
- **Status:** verified
- **Evidence:** Us002DbSchemaTest.ac006 grün
- **Verified-by:** Conductor 2026-11-06

#### AC-007: Der Migrator setzt `user_version`, ist idempotent und legt vor jeder Migration eine Kopie der DB an.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us002DbSchemaTest.kt`
- **Status:** verified
- **Evidence:** Us002DbSchemaTest.ac007 grün
- **Verified-by:** Conductor 2026-11-06

#### AC-008: `sample_n` liefert für jede Golden-Fixture exakt `expect.normalized`.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us002DbSchemaTest.kt`
- **Status:** verified
- **Evidence:** Us002DbSchemaTest.ac008 grün (15 Fixtures)
- **Verified-by:** Conductor 2026-11-06

#### AC-009: `q/series.sql` liefert höchstens 600 Punkte mit Buckets von 3 min (24 h) bzw. 20 min (7 Tage), höchstens einen Punkt je (Span, Bucket) und bricht die Serie an Span-Grenzen.
- **Method:** unit-test
- **Test:** `test/us/us_002_db_schema_test.dart`
- **Status:** verified
- **Evidence:** test/us/us_002_db_schema_test.dart grün
- **Verified-by:** Conductor 2026-11-06

#### AC-010: Retention löscht `sample`/`span` älter als 30 Tage in 5k-Batches; `charge_session`, `session_point` und `day_stat` bleiben erhalten; danach `incremental_vacuum` und `wal_checkpoint(TRUNCATE)`.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us002DbSchemaTest.kt`
- **Status:** verified
- **Evidence:** Us002DbSchemaTest.ac010 grün
- **Verified-by:** Conductor 2026-11-06

### US-003: Engine-Spezifikation & Golden-Fixtures

#### AC-011: `spec/engine/spec.md` beschreibt Zustände, Konstanten und Ereignisse von Normalizer, ProfileDetector, SessionMachine, Eta und TargetAlarm.
- **Method:** manual-check
- **Test:** `Manuelle Prüfung auf dem Gerät (Checkliste in plan.md)`
- **Status:** verified
- **Evidence:** spec/engine/spec.md (Review Conductor)
- **Verified-by:** Conductor 2026-11-06

#### AC-012: Es gibt mindestens je eine Fixture für jeden der 10 Top-Edge-Cases aus dem Design.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us003EngineSpecTest.kt`
- **Status:** verified
- **Evidence:** Us003EngineSpecTest.ac012 grün
- **Verified-by:** Conductor 2026-11-06

#### AC-013: Ein Kotlin-Loader liest und validiert das Format `{profile, samples, expect:{normalized, events, eta, profileAfter}}`; ungültige Fixtures lassen den Test scheitern.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us003EngineSpecTest.kt`
- **Status:** verified
- **Evidence:** Us003EngineSpecTest.ac013 grün
- **Verified-by:** Conductor 2026-11-06

### US-004: Phase-0-Mess-Spike (Logger-APK)

#### AC-014: Eine Logger-APK ohne UI zeichnet alle Rohquellen plus `elapsedRealtime` auf und exportiert CSV.
- **Method:** manual-check
- **Test:** `Manuelle Prüfung auf dem Gerät (Checkliste in plan.md)`
- **Status:** pending-device
- **Evidence:** SpikeLoggerService implementiert, Release-APK gebaut; Lauf auf dem Handy offen
- **Verified-by:** Conductor 2026-11-06

#### AC-015: S1: Einheit, Vorzeichen und Aktualisierungsrate von CURRENT_NOW sind für 5 Szenarien (Schnelllader, PC-USB, Laden beim Spielen, kabellos falls vorhanden, Idle) dokumentiert; Mitschnitte sind als Fixtures übernommen.
- **Method:** manual-check
- **Test:** `Manuelle Prüfung auf dem Gerät (Checkliste in plan.md)`
- **Status:** pending-user
- **Evidence:** Anleitung spec/spike-anleitung.md, Vorlage spec/spike-results.md
- **Verified-by:** Conductor 2026-11-06

#### AC-016: S2: Die Startlatenz Job → FGS ist über ≥ 10 Einsteckvorgänge (bei 15/50/85/92 %) gemessen und der Median dokumentiert.
- **Method:** metric
- **Test:** `Mess-Protokoll in spec/spike-results.md bzw. spec/gate-results.md`
- **Status:** pending-user
- **Evidence:** SpikeChargeJobService + s2_jobs.csv; Messung offen
- **Verified-by:** Conductor 2026-11-06

#### AC-017: S3: Eine 24-h-Messung des Loggers im Modus „Immer aktiv“ zeigt 0 eigene Wakelocks/Alarme/Jobs und UID ≤ 25 mAh.
- **Method:** metric
- **Test:** `Mess-Protokoll in spec/spike-results.md bzw. spec/gate-results.md`
- **Status:** pending-user
- **Evidence:** 24-h-Messung offen
- **Verified-by:** Conductor 2026-11-06

#### AC-018: S4/S5: Neustart nach Boot und Update sowie Kanalwechsel per Re-Post an der FGS-Benachrichtigung sind auf dem Gerät geprüft; Ergebnisse stehen in `spec/spike-results.md`.
- **Method:** manual-check
- **Test:** `Manuelle Prüfung auf dem Gerät (Checkliste in plan.md)`
- **Status:** pending-user
- **Evidence:** SpikeBootReceiver + s4_starts.csv; S5 erst in Phase 1
- **Verified-by:** Conductor 2026-11-06

### US-005: Kotlin-Messpipeline in :monitor

#### AC-019: Beim Entladen reagiert der Sampler nur auf BATTERY_CHANGED und SCREEN_ON/OFF; es werden keine Timer, Alarme, WakeLocks oder Jobs angelegt.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us005MesspipelineTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-020: Beim Laden wird CURRENT_NOW alle 10 s (Bildschirm an) bzw. 30 s (Bildschirm aus, `uptimeMillis`) gelesen.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us005MesspipelineTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-021: Jede Sample-Zeile speichert Rohwerte, `ts_utc`, `elapsed_ms`, `tz_min`, Span/Boot und Qualitäts-Bits; Plausibilitätsverstöße ergeben n/v, nie 0.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us005MesspipelineTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-022: Der ProfileDetector erkennt Einheit (µA/mA), Vorzeichen und Dual-Cell aus den Fixtures korrekt; ein manueller Override wirkt rückwirkend über die Views.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us005MesspipelineTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-023: Flush erfolgt pro Ereignis beim Entladen, als Batch alle 60 s beim Laden und sofort bei Einstecken, Abstecken, Ziel erreicht, voll und `onTrimMemory`.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us005MesspipelineTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-024: Im Prozess `:monitor` wird `libflutter.so` nie geladen; `Application.onCreate` initialisiert dort nichts außer dem Dienst.
- **Method:** integration-test
- **Test:** `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us005MesspipelineInstrumentedTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-006: Ladesitzung, Restzeit & Ziel-Alarm

#### AC-025: Die SessionMachine erzeugt genau eine Sitzung pro Ladevorgang; Wackelkontakt < 2 min und Moduswechsel erzeugen keine zweite.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us006SessionEtaTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-026: Die ETA zeigt in den ersten 90 s „wird berechnet“, danach je Konfidenz 5-min-Stufen, „ca.“ in 15-min-Stufen oder „—“, mit Hysterese max(1 min, 10 %).
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us006SessionEtaTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-027: „Ladepause“ (|I| < 50 mA und % 10 min konstant) und „entlädt trotz Ladegerät“ ersetzen die ETA.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us006SessionEtaTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-028: Der Ziel-Alarm feuert genau einmal pro Sitzung am %-Übergang (2 % Hysterese), nicht beim Einstecken oberhalb des Ziels, und bleibt über Kill/Reboot idempotent (AK5).
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us006SessionEtaTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-029: Das Ladeprofil lernt nur aus lückenlosen %-Schritten (EMA α = 0,3); nach 3 Lernsitzungen liegt die ETA bei halber Restzeit in ≥ 80 % der Sitzungen innerhalb max(±20 %, ±5 min) (AK6).
- **Method:** metric
- **Test:** `Mess-Protokoll in spec/spike-results.md bzw. spec/gate-results.md`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-007: Hintergrunddienst „Immer aktiv“

#### AC-030: Der FGS vom Typ `specialUse` läuft in `:monitor` mit Subtype-Property und startet nach BOOT_COMPLETED, MY_PACKAGE_REPLACED und beim Öffnen der App.
- **Method:** integration-test
- **Test:** `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us007DienstInstrumentedTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-031: `ForegroundServiceStartNotAllowedException` wird gefangen und als Status (Kanal `status` + UI-Banner) gemeldet.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us007DienstTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-032: Ein Heartbeat (`span.end_ts`) erkennt Ausfälle; die Lücke wird mit `end_reason` markiert.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us007DienstTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-033: Wartung (Rollup, Retention, Vacuum, Checkpoint) läuft höchstens 1× in 24 h und nur beim Laden.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us007DienstTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-034: Eine offene Sitzung wird nach Neustart nur bei gleichem Boot, Lücke < 5 min und weiterhin eingestecktem Gerät fortgesetzt, sonst am letzten Commit geschlossen.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us007DienstTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-008: Live-Lade-Benachrichtigung

#### AC-035: Das Popup erscheint ≤ 5 s nach dem Einstecken mit genau einem Heads-up; es sind nie zwei App-Benachrichtigungen gleichzeitig sichtbar (AK4).
- **Method:** integration-test
- **Test:** `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us008BenachrichtigungInstrumentedTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-036: Die Zustände 0, 0h, 1, 2, 2b, 2c, 2d, 3, 4, 5 zeigen Titel und Text gemäß State-Matrix im Format de_DE (U+202F vor Einheiten).
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us008BenachrichtigungTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-037: Updates erfolgen höchstens 1×/10 s und nur bei ΔW ≥ 0,5 W, Δ% ≥ 1 oder ΔETA ≥ 1 min; bei Bildschirm aus 0 Updates, bei SCREEN_ON sofort 1 Update.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us008BenachrichtigungTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-038: Die Aktionen „Ziel 80 %“ (80 → 85 → 90 → 100) und „Alarm aus“ (nur dieser Vorgang) funktionieren nativ ohne Flutter.
- **Method:** integration-test
- **Test:** `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us008BenachrichtigungInstrumentedTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-039: Der Ziel-Alarm klingelt einmal auf `target_alarm`, erinnert nach 10 min und wechselt nach „Verstanden“ still zurück auf `charging`.
- **Method:** manual-check
- **Test:** `Manuelle Prüfung auf dem Gerät (Checkliste in plan.md)`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-040: Ab API 36 nutzt die Benachrichtigung `ProgressStyle` mit Zielmarke und Promotion; nie `setColorized`, kein Full-Screen-Intent, keine Custom-RemoteViews.
- **Method:** manual-check
- **Test:** `Manuelle Prüfung auf dem Gerät (Checkliste in plan.md)`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-009: Brücke Flutter ↔ Dienst

#### AC-041: `MonitorProvider.call()` implementiert `getState`, `putSettings`, `setProfile`, `flush` und `snapshot` mit `v=1`; unbekannte Methoden liefern `null`.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us009BrueckeTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-042: Der Messenger pusht Live-Snapshots nur bei Änderung, höchstens 1 Hz und nur, solange ein Client registriert ist.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us009BrueckeTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-043: Pigeon-API und Port `BatteryBackend` mit `AndroidBackend` und `FakeBackend` existieren; liefert `getState` `null`, zeigt die UI „Dienst veraltet“.
- **Method:** unit-test
- **Test:** `test/us/us_009_bruecke_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-044: Der UI-Prozess öffnet die DB ausschließlich read-only; ein Schreibversuch schlägt fehl.
- **Method:** integration-test
- **Test:** `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us009BrueckeInstrumentedTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-010: Theme & Design-System

#### AC-045: `DynamicColorBuilder` mit Seed-Fallback `#4A6FA5` und `ThemeMode.system`: Die App folgt dem System-Theme hell/dunkel.
- **Method:** e2e-test
- **Test:** `test/us/us_010_theme_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-046: `BatteryColors` (charge, warm, hot, gap) ist harmonisiert; für eine Stichprobe von Paletten gilt Text ≥ 4,5:1 und Linien ≥ 3:1, hell wie dunkel.
- **Method:** unit-test
- **Test:** `test/us/us_010_theme_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-047: Alle Zahlen-Stile nutzen `FontFeature.tabularFigures()`; Zahlen werden im Format de_DE mit U+202F vor der Einheit formatiert.
- **Method:** unit-test
- **Test:** `test/us/us_010_theme_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-048: Es gibt keine Daueranimationen: Nach dem Aufbau der Übersicht ist kein weiterer Frame geplant (`hasScheduledFrame == false`).
- **Method:** e2e-test
- **Test:** `test/us/us_010_theme_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-011: Übersicht-Screen

#### AC-049: Der Hero zeigt %, „Lädt mit X W“ bzw. „Entlädt · X %/h“, Fortschrittsbalken mit Zielmarke und Restzeit; die Textskalierung des Heros ist auf 1,5× begrenzt.
- **Method:** e2e-test
- **Test:** `test/us/us_011_uebersicht_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-050: Das Werte-Raster (2 Spalten, bei 200 % Textgröße einspaltig) blendet fehlende und unplausible Werte aus; bei fehlender Quelle nie „0 W“ oder „0 mA“ (AK2).
- **Method:** e2e-test
- **Test:** `test/us/us_011_uebersicht_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-051: Der Banner-Slot zeigt höchstens ein Banner in der Rangfolge Benachrichtigungen aus > Dienst beendet > Ausnahme fehlt > Alarm stumm, jeweils mit Deep-Link.
- **Method:** e2e-test
- **Test:** `test/us/us_011_uebersicht_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-052: Das Ladeziel-Sheet bietet 80/85/90/100 %, einen eigenen Wert 50–100 und den Schalter „Alarm, wenn erreicht“; Änderungen gehen per `putSettings` an den Dienst.
- **Method:** e2e-test
- **Test:** `test/us/us_011_uebersicht_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-053: Live-Updates laufen nur im Zustand `resumed`, ≤ 1 Hz beim Laden und ≤ 0,2 Hz beim Entladen.
- **Method:** unit-test
- **Test:** `test/us/us_011_uebersicht_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-012: Drei Graphen

#### AC-054: Akkustand (24 h / 7 Tage): Entladen in `primary`, Laden in `charge` mit Fläche, Ziel gestrichelt; Lücken als Linienbruch plus Schraffur, nie interpoliert.
- **Method:** e2e-test
- **Test:** `test/us/us_012_graphen_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-055: Ladeleistung je Ladevorgang über Akku-% mit Navigation ‹ › und bis zu 3 früheren Vorgängen; bei unbestätigtem Profil „Leistung wird kalibriert“.
- **Method:** e2e-test
- **Test:** `test/us/us_012_graphen_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-056: Entladerate: Bildschirm an und aus als zwei direkt beschriftete Balken (%/h) plus Standby-Schätzung.
- **Method:** e2e-test
- **Test:** `test/us/us_012_graphen_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-057: Alle Graphen sind `CustomPainter` in einer `RepaintBoundary`, mit ≤ 1 Punkt pro Pixel, Neuzeichnen nur bei neuer Datenversion und je einer `Semantics`-Zusammenfassung.
- **Method:** unit-test
- **Test:** `test/us/us_012_graphen_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-058: Leer-Zustände zeigen die Microcopy aus dem Design.
- **Method:** e2e-test
- **Test:** `test/us/us_012_graphen_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-013: Onboarding & Berechtigungen

#### AC-059: Ein Screen bietet „Immer aktiv“ (vorausgewählt, empfohlen) und „Nur beim Laden“ mit dem Text aus dem Design.
- **Method:** e2e-test
- **Test:** `test/us/us_013_onboarding_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-060: Reihenfolge: POST_NOTIFICATIONS → Akku-Optimierungs-Ausnahme (in „Immer aktiv“ empfohlen mit [Später], in „Nur beim Laden“ Pflicht) → OEM-Link, jeweils mit 1-Satz-Begründung.
- **Method:** e2e-test
- **Test:** `test/us/us_013_onboarding_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-061: Eine Ablehnung führt zu einem Banner mit Deep-Link statt zu einer Blockade.
- **Method:** e2e-test
- **Test:** `test/us/us_013_onboarding_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-062: Der Mehrverbrauch wird erst angezeigt, wenn ein Messwert vorliegt.
- **Method:** e2e-test
- **Test:** `test/us/us_013_onboarding_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-014: Einstellungen & Diagnose

#### AC-063: Einstellungen für Hintergrundbetrieb, Ladeziel/Alarm/Erinnerung, Zusammenfassung, Hitzewarnung (Standard 45 °C, 40–50 °C) und Berechtigungsstatus sind vorhanden.
- **Method:** e2e-test
- **Test:** `test/us/us_014_einstellungen_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-064: Die Zeile „Eigenverbrauch“ zeigt CPU-Zeit sowie Flush- und Update-Zähler des Dienstes.
- **Method:** e2e-test
- **Test:** `test/us/us_014_einstellungen_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-065: Die Diagnose zeigt Rohwerte, ein Override von Einheit/Vorzeichen/Zellen wirkt rückwirkend auf alle Graphen, und „Alle Daten löschen“ verlangt eine Bestätigung.
- **Method:** integration-test
- **Test:** `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us014EinstellungenInstrumentedTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-015: Energie-Gate & Robustheit

#### AC-066: Mess-Gate auf dem Release-Build bestanden: UID ≤ 5 mAh/24 h (Grenze 25), 0 eigene Wakeups/WakeLocks/Jobs, CPU beim Entladen ≤ 24 s/24 h, beim Laden ≤ 15 s/h, PSS `:monitor` ≤ 25 MB (AK3).
- **Method:** metric
- **Test:** `Mess-Protokoll in spec/spike-results.md bzw. spec/gate-results.md`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-067: Vorzeichen in 5/5 Szenarien korrekt; Leistung über 10 min innerhalb ±20 % der ΔCHARGE_COUNTER-Referenz (AK1).
- **Method:** metric
- **Test:** `Mess-Protokoll in spec/spike-results.md bzw. spec/gate-results.md`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-068: Reboot, Update, Force-Stop, Uhr ±1 h und Sommerzeitwechsel erzeugen keine negativen oder unendlichen Raten und keine Doppelsitzung; Lücken sind markiert (AK7).
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us015GateTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-069: Schlägt `quick_check` fehl, wird die DB als `.corrupt` beiseitegelegt, neu angelegt und ein Banner angezeigt.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us015GateTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-070: Der Release-Build nutzt R8, entfernt `Log.d`/`Log.v` und ist nicht debuggable.
- **Method:** build-output
- **Test:** `tool/check_release.sh (Merged Manifest, apksigner, pubspec)`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

### US-016: Modus „Nur beim Laden“

#### AC-071: Der ChargeJobService (`requiresCharging`, persisted) startet mit Akku-Ausnahme einen FGS nur während des Ladens; beim Abstecken folgen Sofort-Flush und `stopSelf`; beim Entladen gibt es keine Dauer-Benachrichtigung.
- **Method:** integration-test
- **Test:** `android/app/src/androidTest/kotlin/de/mlampert/batterymanager/Us016NurBeimLadenInstrumentedTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-072: Ohne Akku-Ausnahme startet der Modus nicht; ein Banner bietet [Erlauben] und [Zu „Immer aktiv“ wechseln].
- **Method:** e2e-test
- **Test:** `test/us/us_016_nur_beim_laden_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-073: Die gemessene Startlatenz wird in den Einstellungen angezeigt; bei Median > 30 min oder > 20 % ohne Start ist der Modus als „experimentell“ markiert; die App verspricht nie „sofort“ oder „lückenlos“ (AK8).
- **Method:** e2e-test
- **Test:** `test/us/us_016_nur_beim_laden_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-074: Zustand 3x („Ladeziel überschritten“) und die Zusammenfassung „Beginn nicht erfasst“ werden korrekt erzeugt.
- **Method:** unit-test
- **Test:** `android/app/src/test/kotlin/de/mlampert/batterymanager/Us016NurBeimLadenTest.kt`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

#### AC-075: Graph-Varianten: Entladephasen schraffiert zwischen Ankerpunkten, Ladekurve startet mit hohlem Punkt, Entladerate nur als Ø zwischen Ladevorgängen.
- **Method:** e2e-test
- **Test:** `test/us/us_016_nur_beim_laden_widget_test.dart`
- **Status:** pending
- **Evidence:** —
- **Verified-by:** —

## Verification Summary
| ID | Story | Criterion | Method | Status | Evidence |
|----|-------|-----------|--------|--------|----------|
| AC-001 | US-001 | applicationId und namespace sind `de.mlampert.batterymanager`; der… | build-output | blocked-user | applicationId ok (tool/check_release.sh) |
| AC-002 | US-001 | minSdk 24, targetSdk 36, `allowBackup=false`; das Merged Manifest e… | build-output | verified | tool/check_release.sh: minSdk 24, targetSdk 36, allowBackup=false, kein INTERNET/WAKE_LOCK/EXACT_ALARM |
| AC-003 | US-001 | `cupertino_icons` ist entfernt; Laufzeit-Abhängigkeiten sind nur `s… | build-output | verified | pubspec.yaml |
| AC-004 | US-001 | Die Kanäle `monitor` (MIN), `charging` (HIGH ohne Ton), `target_ala… | integration-test | pending-device | Us001ProjektInstrumentedTest.kt geschrieben |
| AC-005 | US-001 | Monochrome Small Icons `ic_stat_battery`, `ic_stat_charging`, `ic_s… | manual-check | pending-manual | Icons angelegt (ic_stat_*, adaptives Icon mit Monochrom-Ebene) |
| AC-006 | US-002 | `spec/db/001_init.sql` legt `span`, `sample` (inkl. `tz_min`), `cha… | unit-test | verified | Us002DbSchemaTest.ac006 grün |
| AC-007 | US-002 | Der Migrator setzt `user_version`, ist idempotent und legt vor jede… | unit-test | verified | Us002DbSchemaTest.ac007 grün |
| AC-008 | US-002 | `sample_n` liefert für jede Golden-Fixture exakt `expect.normalized`. | unit-test | verified | Us002DbSchemaTest.ac008 grün (15 Fixtures) |
| AC-009 | US-002 | `q/series.sql` liefert höchstens 600 Punkte mit Buckets von 3 min (… | unit-test | verified | test/us/us_002_db_schema_test.dart grün |
| AC-010 | US-002 | Retention löscht `sample`/`span` älter als 30 Tage in 5k-Batches; `… | unit-test | verified | Us002DbSchemaTest.ac010 grün |
| AC-011 | US-003 | `spec/engine/spec.md` beschreibt Zustände, Konstanten und Ereigniss… | manual-check | verified | spec/engine/spec.md (Review Conductor) |
| AC-012 | US-003 | Es gibt mindestens je eine Fixture für jeden der 10 Top-Edge-Cases… | unit-test | verified | Us003EngineSpecTest.ac012 grün |
| AC-013 | US-003 | Ein Kotlin-Loader liest und validiert das Format `{profile, samples… | unit-test | verified | Us003EngineSpecTest.ac013 grün |
| AC-014 | US-004 | Eine Logger-APK ohne UI zeichnet alle Rohquellen plus `elapsedRealt… | manual-check | pending-device | SpikeLoggerService implementiert, Release-APK gebaut |
| AC-015 | US-004 | S1: Einheit, Vorzeichen und Aktualisierungsrate von CURRENT_NOW sin… | manual-check | pending-user | Anleitung spec/spike-anleitung.md, Vorlage spec/spike-results.md |
| AC-016 | US-004 | S2: Die Startlatenz Job → FGS ist über ≥ 10 Einsteckvorgänge (bei 1… | metric | pending-user | SpikeChargeJobService + s2_jobs.csv |
| AC-017 | US-004 | S3: Eine 24-h-Messung des Loggers im Modus „Immer aktiv“ zeigt 0 ei… | metric | pending-user | 24-h-Messung offen |
| AC-018 | US-004 | S4/S5: Neustart nach Boot und Update sowie Kanalwechsel per Re-Post… | manual-check | pending-user | SpikeBootReceiver + s4_starts.csv |
| AC-019 | US-005 | Beim Entladen reagiert der Sampler nur auf BATTERY_CHANGED und SCRE… | unit-test | pending | — |
| AC-020 | US-005 | Beim Laden wird CURRENT_NOW alle 10 s (Bildschirm an) bzw. 30 s (Bi… | unit-test | pending | — |
| AC-021 | US-005 | Jede Sample-Zeile speichert Rohwerte, `ts_utc`, `elapsed_ms`, `tz_m… | unit-test | pending | — |
| AC-022 | US-005 | Der ProfileDetector erkennt Einheit (µA/mA), Vorzeichen und Dual-Ce… | unit-test | pending | — |
| AC-023 | US-005 | Flush erfolgt pro Ereignis beim Entladen, als Batch alle 60 s beim… | unit-test | pending | — |
| AC-024 | US-005 | Im Prozess `:monitor` wird `libflutter.so` nie geladen; `Applicatio… | integration-test | pending | — |
| AC-025 | US-006 | Die SessionMachine erzeugt genau eine Sitzung pro Ladevorgang; Wack… | unit-test | pending | — |
| AC-026 | US-006 | Die ETA zeigt in den ersten 90 s „wird berechnet“, danach je Konfid… | unit-test | pending | — |
| AC-027 | US-006 | „Ladepause“ (\|I\| < 50 mA und % 10 min konstant) und „entlädt trotz… | unit-test | pending | — |
| AC-028 | US-006 | Der Ziel-Alarm feuert genau einmal pro Sitzung am %-Übergang (2 % H… | unit-test | pending | — |
| AC-029 | US-006 | Das Ladeprofil lernt nur aus lückenlosen %-Schritten (EMA α = 0,3);… | metric | pending | — |
| AC-030 | US-007 | Der FGS vom Typ `specialUse` läuft in `:monitor` mit Subtype-Proper… | integration-test | pending | — |
| AC-031 | US-007 | `ForegroundServiceStartNotAllowedException` wird gefangen und als S… | unit-test | pending | — |
| AC-032 | US-007 | Ein Heartbeat (`span.end_ts`) erkennt Ausfälle; die Lücke wird mit… | unit-test | pending | — |
| AC-033 | US-007 | Wartung (Rollup, Retention, Vacuum, Checkpoint) läuft höchstens 1×… | unit-test | pending | — |
| AC-034 | US-007 | Eine offene Sitzung wird nach Neustart nur bei gleichem Boot, Lücke… | unit-test | pending | — |
| AC-035 | US-008 | Das Popup erscheint ≤ 5 s nach dem Einstecken mit genau einem Heads… | integration-test | pending | — |
| AC-036 | US-008 | Die Zustände 0, 0h, 1, 2, 2b, 2c, 2d, 3, 4, 5 zeigen Titel und Text… | unit-test | pending | — |
| AC-037 | US-008 | Updates erfolgen höchstens 1×/10 s und nur bei ΔW ≥ 0,5 W, Δ% ≥ 1 o… | unit-test | pending | — |
| AC-038 | US-008 | Die Aktionen „Ziel 80 %“ (80 → 85 → 90 → 100) und „Alarm aus“ (nur… | integration-test | pending | — |
| AC-039 | US-008 | Der Ziel-Alarm klingelt einmal auf `target_alarm`, erinnert nach 10… | manual-check | pending | — |
| AC-040 | US-008 | Ab API 36 nutzt die Benachrichtigung `ProgressStyle` mit Zielmarke… | manual-check | pending | — |
| AC-041 | US-009 | `MonitorProvider.call()` implementiert `getState`, `putSettings`, `… | unit-test | pending | — |
| AC-042 | US-009 | Der Messenger pusht Live-Snapshots nur bei Änderung, höchstens 1 Hz… | unit-test | pending | — |
| AC-043 | US-009 | Pigeon-API und Port `BatteryBackend` mit `AndroidBackend` und `Fake… | unit-test | pending | — |
| AC-044 | US-009 | Der UI-Prozess öffnet die DB ausschließlich read-only; ein Schreibv… | integration-test | pending | — |
| AC-045 | US-010 | `DynamicColorBuilder` mit Seed-Fallback `#4A6FA5` und `ThemeMode.sy… | e2e-test | pending | — |
| AC-046 | US-010 | `BatteryColors` (charge, warm, hot, gap) ist harmonisiert; für eine… | unit-test | pending | — |
| AC-047 | US-010 | Alle Zahlen-Stile nutzen `FontFeature.tabularFigures()`; Zahlen wer… | unit-test | pending | — |
| AC-048 | US-010 | Es gibt keine Daueranimationen: Nach dem Aufbau der Übersicht ist k… | e2e-test | pending | — |
| AC-049 | US-011 | Der Hero zeigt %, „Lädt mit X W“ bzw. „Entlädt · X %/h“, Fortschrit… | e2e-test | pending | — |
| AC-050 | US-011 | Das Werte-Raster (2 Spalten, bei 200 % Textgröße einspaltig) blende… | e2e-test | pending | — |
| AC-051 | US-011 | Der Banner-Slot zeigt höchstens ein Banner in der Rangfolge Benachr… | e2e-test | pending | — |
| AC-052 | US-011 | Das Ladeziel-Sheet bietet 80/85/90/100 %, einen eigenen Wert 50–100… | e2e-test | pending | — |
| AC-053 | US-011 | Live-Updates laufen nur im Zustand `resumed`, ≤ 1 Hz beim Laden und… | unit-test | pending | — |
| AC-054 | US-012 | Akkustand (24 h / 7 Tage): Entladen in `primary`, Laden in `charge`… | e2e-test | pending | — |
| AC-055 | US-012 | Ladeleistung je Ladevorgang über Akku-% mit Navigation ‹ › und bis… | e2e-test | pending | — |
| AC-056 | US-012 | Entladerate: Bildschirm an und aus als zwei direkt beschriftete Bal… | e2e-test | pending | — |
| AC-057 | US-012 | Alle Graphen sind `CustomPainter` in einer `RepaintBoundary`, mit ≤… | unit-test | pending | — |
| AC-058 | US-012 | Leer-Zustände zeigen die Microcopy aus dem Design. | e2e-test | pending | — |
| AC-059 | US-013 | Ein Screen bietet „Immer aktiv“ (vorausgewählt, empfohlen) und „Nur… | e2e-test | pending | — |
| AC-060 | US-013 | Reihenfolge: POST_NOTIFICATIONS → Akku-Optimierungs-Ausnahme (in „I… | e2e-test | pending | — |
| AC-061 | US-013 | Eine Ablehnung führt zu einem Banner mit Deep-Link statt zu einer B… | e2e-test | pending | — |
| AC-062 | US-013 | Der Mehrverbrauch wird erst angezeigt, wenn ein Messwert vorliegt. | e2e-test | pending | — |
| AC-063 | US-014 | Einstellungen für Hintergrundbetrieb, Ladeziel/Alarm/Erinnerung, Zu… | e2e-test | pending | — |
| AC-064 | US-014 | Die Zeile „Eigenverbrauch“ zeigt CPU-Zeit sowie Flush- und Update-Z… | e2e-test | pending | — |
| AC-065 | US-014 | Die Diagnose zeigt Rohwerte, ein Override von Einheit/Vorzeichen/Ze… | integration-test | pending | — |
| AC-066 | US-015 | Mess-Gate auf dem Release-Build bestanden: UID ≤ 5 mAh/24 h (Grenze… | metric | pending | — |
| AC-067 | US-015 | Vorzeichen in 5/5 Szenarien korrekt; Leistung über 10 min innerhalb… | metric | pending | — |
| AC-068 | US-015 | Reboot, Update, Force-Stop, Uhr ±1 h und Sommerzeitwechsel erzeugen… | unit-test | pending | — |
| AC-069 | US-015 | Schlägt `quick_check` fehl, wird die DB als `.corrupt` beiseitegele… | unit-test | pending | — |
| AC-070 | US-015 | Der Release-Build nutzt R8, entfernt `Log.d`/`Log.v` und ist nicht… | build-output | pending | — |
| AC-071 | US-016 | Der ChargeJobService (`requiresCharging`, persisted) startet mit Ak… | integration-test | pending | — |
| AC-072 | US-016 | Ohne Akku-Ausnahme startet der Modus nicht; ein Banner bietet [Erla… | e2e-test | pending | — |
| AC-073 | US-016 | Die gemessene Startlatenz wird in den Einstellungen angezeigt; bei… | e2e-test | pending | — |
| AC-074 | US-016 | Zustand 3x („Ladeziel überschritten“) und die Zusammenfassung „Begi… | unit-test | pending | — |
| AC-075 | US-016 | Graph-Varianten: Entladephasen schraffiert zwischen Ankerpunkten, L… | e2e-test | pending | — |

Progress: 10/75 verified · Phase 0: 10/18 verified, 1 blocked-user (Keystore), 2 pending-device, 1 pending-manual, 4 pending-user (Spike-Messungen)

GitHub-Issue: nicht erstellt (Workspace ist kein Git-Repository und hat keinen GitHub-Remote).