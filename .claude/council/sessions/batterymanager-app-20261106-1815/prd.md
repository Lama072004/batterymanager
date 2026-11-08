# PRD: Batterymanager (Android)

Session: `batterymanager-app-20261106-1815` · Design: `design.md` · Stand: 2026-11-06

## Overview
Eine minimalistische Android-App (Flutter-UI, nativer Kotlin-Hintergrund), die Akkuzustand, Ladeleistung in Watt und Verbrauch zeigt. Ihr Kern ist eine Live-Lade-Benachrichtigung: Beim Einstecken zeigt sie Watt und Restzeit bis zum einstellbaren Ladeziel und bis voll und klingelt genau einmal, wenn das Ziel erreicht ist. Die App ist ein Scroll-Screen in Material You (hell/dunkel automatisch) mit drei Graphen. Oberste Anforderung ist der minimale Eigenverbrauch: Die Hintergrundlogik läuft ohne Flutter-Engine und rein ereignisgetrieben, mit einem Budget von ≤ 5 mAh/24 h (Grenze 25 mAh).

## Goals
- Live-Lade-Benachrichtigung mit Watt, Restzeit bis Ladeziel und bis voll sowie einem Ziel-Alarm („Jetzt abstecken“)
- Ehrliche, korrekte Messwerte: Rohwerte + Geräteprofil + Plausibilität; „n/v“ statt falscher Zahlen
- Drei Graphen: Akkustand-Verlauf (24 h / 7 Tage), Ladeleistung je Ladevorgang, Entladerate Bildschirm an/aus
- Eigenverbrauch ≤ 5 mAh/24 h, nachgewiesen über ein Mess-Gate
- Wählbarer Hintergrundbetrieb: „Immer aktiv“ (Standard) und „Nur beim Laden“
- Minimalistisches Material-You-Design, hell/dunkel nach System, deutsch

## Non-Goals
- Windows und Linux: weder Build noch Test in diesem Umfang (als spätere Option im Design dokumentiert)
- iOS, Web, macOS
- Laden physisch stoppen (kein Root-Support)
- Akkuverbrauch pro App, Usage-Access-Berechtigung
- Temperatur-Graph (Temperatur nur als Wert plus Hitzewarnung)
- Play Store, Werbung, Tracking, Netzwerkzugriff
- Echter State-of-Health-Prozentwert (nur Health-Status von Android plus Kapazitätsschätzung)

## Quality Gates
- `flutter analyze`: keine Fehler und keine Warnungen
- `flutter test`: Dart-Unit- und Widget-Tests grün
- `cd android && ./gradlew :app:testDebugUnitTest` (Windows: `gradlew.bat`): Kotlin-Unit-Tests inklusive Golden-Fixtures grün
- `flutter build apk --release --split-per-abi`: Release-Build erfolgreich, signiert mit eigenem Keystore
- `tool/check_release.sh`: Merged Manifest ohne INTERNET/WAKE_LOCK/SCHEDULE_EXACT_ALARM, nicht debuggable
- Mess-Gate (manuell, Release-Build auf dem Handy des Nutzers): Kriterien aus US-015

## User Stories

### US-001: Projekt-Identität & Grundgerüst

**Phase:** 0 · **Agent:** Pathfinder · **Abhängig von:** —

**Description:** Als Entwickler möchte ich App-ID, Signatur, SDK-Stände, Berechtigungen und Notification-Kanäle vor der ersten Installation endgültig festlegen, damit spätere Updates nie Daten verlieren.

**Acceptance Criteria:**
- [ ] AC-001: applicationId und namespace sind `de.mlampert.batterymanager`; der Release-Build ist mit einem eigenen Keystore signiert (nicht Debug).
- [ ] AC-002: minSdk 24, targetSdk 36, `allowBackup=false`; das Merged Manifest enthält keine `INTERNET`-, `WAKE_LOCK`- oder `SCHEDULE_EXACT_ALARM`-Berechtigung.
- [ ] AC-003: `cupertino_icons` ist entfernt; Laufzeit-Abhängigkeiten sind nur `sqflite` und `dynamic_color` (dev: `pigeon`, `sqflite_common_ffi`, `flutter_lints`).
- [ ] AC-004: Die Kanäle `monitor` (MIN), `charging` (HIGH ohne Ton), `target_alarm` (HIGH, USAGE_ALARM, Vibration), `heat` (HIGH ohne Ton) und `status` (DEFAULT) existieren mit deutschen Namen.
- [ ] AC-005: Monochrome Small Icons `ic_stat_battery`, `ic_stat_charging`, `ic_stat_target` sowie ein adaptives App-Icon mit Monochrom-Ebene und Splash ohne weißen Blitz sind vorhanden.

### US-002: DB-Schema v1 & Abfragen als SQL-Dateien

**Phase:** 0 · **Agent:** Architect · **Abhängig von:** US-001

**Description:** Als Entwickler möchte ich das Datenbankschema, die Normalisierungs-Views und alle Abfragen als versionierte SQL-Dateien, damit Dienst und UI dieselbe einzige Schemaquelle nutzen.

**Acceptance Criteria:**
- [ ] AC-006: `spec/db/001_init.sql` legt `span`, `sample` (inkl. `tz_min`), `charge_session`, `session_point`, `charge_profile`, `device_profile`, `day_stat`, `kv` und die Views `pf`, `sample_n`, `point_n`, `session_n` an und nutzt nur SQLite-3.9-Syntax.
- [ ] AC-007: Der Migrator setzt `user_version`, ist idempotent und legt vor jeder Migration eine Kopie der DB an.
- [ ] AC-008: `sample_n` liefert für jede Golden-Fixture exakt `expect.normalized`.
- [ ] AC-009: `q/series.sql` liefert höchstens 600 Punkte mit Buckets von 3 min (24 h) bzw. 20 min (7 Tage), höchstens einen Punkt je (Span, Bucket) und bricht die Serie an Span-Grenzen.
- [ ] AC-010: Retention löscht `sample`/`span` älter als 30 Tage in 5k-Batches; `charge_session`, `session_point` und `day_stat` bleiben erhalten; danach `incremental_vacuum` und `wal_checkpoint(TRUNCATE)`.

### US-003: Engine-Spezifikation & Golden-Fixtures

**Phase:** 0 · **Agent:** Architect · **Abhängig von:** US-001

**Description:** Als Entwickler möchte ich eine schriftliche Engine-Spezifikation mit Golden-Fixtures, damit Normalisierung, Sitzungen, ETA und Alarm testbar und eindeutig definiert sind.

**Acceptance Criteria:**
- [ ] AC-011: `spec/engine/spec.md` beschreibt Zustände, Konstanten und Ereignisse von Normalizer, ProfileDetector, SessionMachine, Eta und TargetAlarm.
- [ ] AC-012: Es gibt mindestens je eine Fixture für jeden der 10 Top-Edge-Cases aus dem Design.
- [ ] AC-013: Ein Kotlin-Loader liest und validiert das Format `{profile, samples, expect:{normalized, events, eta, profileAfter}}`; ungültige Fixtures lassen den Test scheitern.

### US-004: Phase-0-Mess-Spike (Logger-APK)

**Phase:** 0 · **Agent:** Skeptic · **Abhängig von:** US-001

**Description:** Als Entwickler möchte ich vor dem UI-Bau auf meinem Handy messen, wie es Strom, Spannung und Ladezustand meldet und was der Hintergrund kostet, damit die App keine falschen Zahlen zeigt.

**Acceptance Criteria:**
- [ ] AC-014: Eine Logger-APK ohne UI zeichnet alle Rohquellen plus `elapsedRealtime` auf und exportiert CSV.
- [ ] AC-015: S1: Einheit, Vorzeichen und Aktualisierungsrate von CURRENT_NOW sind für 5 Szenarien (Schnelllader, PC-USB, Laden beim Spielen, kabellos falls vorhanden, Idle) dokumentiert; Mitschnitte sind als Fixtures übernommen.
- [ ] AC-016: S2: Die Startlatenz Job → FGS ist über ≥ 10 Einsteckvorgänge (bei 15/50/85/92 %) gemessen und der Median dokumentiert.
- [ ] AC-017: S3: Eine 24-h-Messung des Loggers im Modus „Immer aktiv“ zeigt 0 eigene Wakelocks/Alarme/Jobs und UID ≤ 25 mAh.
- [ ] AC-018: S4/S5: Neustart nach Boot und Update sowie Kanalwechsel per Re-Post an der FGS-Benachrichtigung sind auf dem Gerät geprüft; Ergebnisse stehen in `spec/spike-results.md`.

### US-005: Kotlin-Messpipeline in :monitor

**Phase:** 1 · **Agent:** Pathfinder · **Abhängig von:** US-002, US-003, US-004

**Description:** Als Nutzer möchte ich, dass Akkuwerte korrekt und ohne spürbaren Eigenverbrauch erfasst werden.

**Acceptance Criteria:**
- [ ] AC-019: Beim Entladen reagiert der Sampler nur auf BATTERY_CHANGED und SCREEN_ON/OFF; es werden keine Timer, Alarme, WakeLocks oder Jobs angelegt.
- [ ] AC-020: Beim Laden wird CURRENT_NOW alle 10 s (Bildschirm an) bzw. 30 s (Bildschirm aus, `uptimeMillis`) gelesen.
- [ ] AC-021: Jede Sample-Zeile speichert Rohwerte, `ts_utc`, `elapsed_ms`, `tz_min`, Span/Boot und Qualitäts-Bits; Plausibilitätsverstöße ergeben n/v, nie 0.
- [ ] AC-022: Der ProfileDetector erkennt Einheit (µA/mA), Vorzeichen und Dual-Cell aus den Fixtures korrekt; ein manueller Override wirkt rückwirkend über die Views.
- [ ] AC-023: Flush erfolgt pro Ereignis beim Entladen, als Batch alle 60 s beim Laden und sofort bei Einstecken, Abstecken, Ziel erreicht, voll und `onTrimMemory`.
- [ ] AC-024: Im Prozess `:monitor` wird `libflutter.so` nie geladen; `Application.onCreate` initialisiert dort nichts außer dem Dienst.

### US-006: Ladesitzung, Restzeit & Ziel-Alarm

**Phase:** 1 · **Agent:** Architect · **Abhängig von:** US-005

**Description:** Als Nutzer möchte ich eine ehrliche Restzeit bis zum Ladeziel und bis voll sowie genau einen Alarm beim Ziel.

**Acceptance Criteria:**
- [ ] AC-025: Die SessionMachine erzeugt genau eine Sitzung pro Ladevorgang; Wackelkontakt < 2 min und Moduswechsel erzeugen keine zweite.
- [ ] AC-026: Die ETA zeigt in den ersten 90 s „wird berechnet“, danach je Konfidenz 5-min-Stufen, „ca.“ in 15-min-Stufen oder „—“, mit Hysterese max(1 min, 10 %).
- [ ] AC-027: „Ladepause“ (|I| < 50 mA und % 10 min konstant) und „entlädt trotz Ladegerät“ ersetzen die ETA.
- [ ] AC-028: Der Ziel-Alarm feuert genau einmal pro Sitzung am %-Übergang (2 % Hysterese), nicht beim Einstecken oberhalb des Ziels, und bleibt über Kill/Reboot idempotent (AK5).
- [ ] AC-029: Das Ladeprofil lernt nur aus lückenlosen %-Schritten (EMA α = 0,3); nach 3 Lernsitzungen liegt die ETA bei halber Restzeit in ≥ 80 % der Sitzungen innerhalb max(±20 %, ±5 min) (AK6).

### US-007: Hintergrunddienst „Immer aktiv“

**Phase:** 1 · **Agent:** Pathfinder · **Abhängig von:** US-005

**Description:** Als Nutzer möchte ich, dass der Batterymanager zuverlässig im Hintergrund bereit ist, auch nach Neustart und Update.

**Acceptance Criteria:**
- [ ] AC-030: Der FGS vom Typ `specialUse` läuft in `:monitor` mit Subtype-Property und startet nach BOOT_COMPLETED, MY_PACKAGE_REPLACED und beim Öffnen der App.
- [ ] AC-031: `ForegroundServiceStartNotAllowedException` wird gefangen und als Status (Kanal `status` + UI-Banner) gemeldet.
- [ ] AC-032: Ein Heartbeat (`span.end_ts`) erkennt Ausfälle; die Lücke wird mit `end_reason` markiert.
- [ ] AC-033: Wartung (Rollup, Retention, Vacuum, Checkpoint) läuft höchstens 1× in 24 h und nur beim Laden.
- [ ] AC-034: Eine offene Sitzung wird nach Neustart nur bei gleichem Boot, Lücke < 5 min und weiterhin eingestecktem Gerät fortgesetzt, sonst am letzten Commit geschlossen.

### US-008: Live-Lade-Benachrichtigung

**Phase:** 1 · **Agent:** Advocate · **Abhängig von:** US-006, US-007

**Description:** Als Nutzer möchte ich beim Einstecken ohne App-Öffnen sehen, wie schnell geladen wird und wann das Ziel bzw. voll erreicht ist.

**Acceptance Criteria:**
- [ ] AC-035: Das Popup erscheint ≤ 5 s nach dem Einstecken mit genau einem Heads-up; es sind nie zwei App-Benachrichtigungen gleichzeitig sichtbar (AK4).
- [ ] AC-036: Die Zustände 0, 0h, 1, 2, 2b, 2c, 2d, 3, 4, 5 zeigen Titel und Text gemäß State-Matrix im Format de_DE (U+202F vor Einheiten).
- [ ] AC-037: Updates erfolgen höchstens 1×/10 s und nur bei ΔW ≥ 0,5 W, Δ% ≥ 1 oder ΔETA ≥ 1 min; bei Bildschirm aus 0 Updates, bei SCREEN_ON sofort 1 Update.
- [ ] AC-038: Die Aktionen „Ziel 80 %“ (80 → 85 → 90 → 100) und „Alarm aus“ (nur dieser Vorgang) funktionieren nativ ohne Flutter.
- [ ] AC-039: Der Ziel-Alarm klingelt einmal auf `target_alarm`, erinnert nach 10 min und wechselt nach „Verstanden“ still zurück auf `charging`.
- [ ] AC-040: Ab API 36 nutzt die Benachrichtigung `ProgressStyle` mit Zielmarke und Promotion; nie `setColorized`, kein Full-Screen-Intent, keine Custom-RemoteViews.

### US-009: Brücke Flutter ↔ Dienst

**Phase:** 1 · **Agent:** Architect · **Abhängig von:** US-007

**Description:** Als Entwickler möchte ich eine schmale, versionierte Schnittstelle zwischen UI und Dienst, damit die UI nur liest und nichts im Hintergrund kostet.

**Acceptance Criteria:**
- [ ] AC-041: `MonitorProvider.call()` implementiert `getState`, `putSettings`, `setProfile`, `flush` und `snapshot` mit `v=1`; unbekannte Methoden liefern `null`.
- [ ] AC-042: Der Messenger pusht Live-Snapshots nur bei Änderung, höchstens 1 Hz und nur, solange ein Client registriert ist.
- [ ] AC-043: Pigeon-API und Port `BatteryBackend` mit `AndroidBackend` und `FakeBackend` existieren; liefert `getState` `null`, zeigt die UI „Dienst veraltet“.
- [ ] AC-044: Der UI-Prozess öffnet die DB ausschließlich read-only; ein Schreibversuch schlägt fehl.

### US-010: Theme & Design-System

**Phase:** 1 · **Agent:** Artisan · **Abhängig von:** US-001

**Description:** Als Nutzer möchte ich eine ruhige, minimalistische App in meinen Systemfarben, die automatisch hell oder dunkel ist.

**Acceptance Criteria:**
- [ ] AC-045: `DynamicColorBuilder` mit Seed-Fallback `#4A6FA5` und `ThemeMode.system`: Die App folgt dem System-Theme hell/dunkel.
- [ ] AC-046: `BatteryColors` (charge, warm, hot, gap) ist harmonisiert; für eine Stichprobe von Paletten gilt Text ≥ 4,5:1 und Linien ≥ 3:1, hell wie dunkel.
- [ ] AC-047: Alle Zahlen-Stile nutzen `FontFeature.tabularFigures()`; Zahlen werden im Format de_DE mit U+202F vor der Einheit formatiert.
- [ ] AC-048: Es gibt keine Daueranimationen: Nach dem Aufbau der Übersicht ist kein weiterer Frame geplant (`hasScheduledFrame == false`).

### US-011: Übersicht-Screen

**Phase:** 1 · **Agent:** Advocate · **Abhängig von:** US-009, US-010

**Description:** Als Nutzer möchte ich auf einen Blick Akkustand, Ladeleistung bzw. Verbrauch, Restzeit und Detailwerte sehen.

**Acceptance Criteria:**
- [ ] AC-049: Der Hero zeigt %, „Lädt mit X W“ bzw. „Entlädt · X %/h“, Fortschrittsbalken mit Zielmarke und Restzeit; die Textskalierung des Heros ist auf 1,5× begrenzt.
- [ ] AC-050: Das Werte-Raster (2 Spalten, bei 200 % Textgröße einspaltig) blendet fehlende und unplausible Werte aus; bei fehlender Quelle nie „0 W“ oder „0 mA“ (AK2).
- [ ] AC-051: Der Banner-Slot zeigt höchstens ein Banner in der Rangfolge Benachrichtigungen aus > Dienst beendet > Ausnahme fehlt > Alarm stumm, jeweils mit Deep-Link.
- [ ] AC-052: Das Ladeziel-Sheet bietet 80/85/90/100 %, einen eigenen Wert 50–100 und den Schalter „Alarm, wenn erreicht“; Änderungen gehen per `putSettings` an den Dienst.
- [ ] AC-053: Live-Updates laufen nur im Zustand `resumed`, ≤ 1 Hz beim Laden und ≤ 0,2 Hz beim Entladen.

### US-012: Drei Graphen

**Phase:** 1 · **Agent:** Artisan · **Abhängig von:** US-002, US-009, US-010

**Description:** Als Nutzer möchte ich Akkustand-Verlauf, Ladeleistung je Ladevorgang und meine Entladerate als klare Graphen sehen.

**Acceptance Criteria:**
- [ ] AC-054: Akkustand (24 h / 7 Tage): Entladen in `primary`, Laden in `charge` mit Fläche, Ziel gestrichelt; Lücken als Linienbruch plus Schraffur, nie interpoliert.
- [ ] AC-055: Ladeleistung je Ladevorgang über Akku-% mit Navigation ‹ › und bis zu 3 früheren Vorgängen; bei unbestätigtem Profil „Leistung wird kalibriert“.
- [ ] AC-056: Entladerate: Bildschirm an und aus als zwei direkt beschriftete Balken (%/h) plus Standby-Schätzung.
- [ ] AC-057: Alle Graphen sind `CustomPainter` in einer `RepaintBoundary`, mit ≤ 1 Punkt pro Pixel, Neuzeichnen nur bei neuer Datenversion und je einer `Semantics`-Zusammenfassung.
- [ ] AC-058: Leer-Zustände zeigen die Microcopy aus dem Design.

### US-013: Onboarding & Berechtigungen

**Phase:** 1 · **Agent:** Advocate · **Abhängig von:** US-009, US-011

**Description:** Als Nutzer möchte ich beim ersten Start ehrlich erklärt bekommen, welcher Modus was kostet, und nur begründete Berechtigungsfragen sehen.

**Acceptance Criteria:**
- [ ] AC-059: Ein Screen bietet „Immer aktiv“ (vorausgewählt, empfohlen) und „Nur beim Laden“ mit dem Text aus dem Design.
- [ ] AC-060: Reihenfolge: POST_NOTIFICATIONS → Akku-Optimierungs-Ausnahme (in „Immer aktiv“ empfohlen mit [Später], in „Nur beim Laden“ Pflicht) → OEM-Link, jeweils mit 1-Satz-Begründung.
- [ ] AC-061: Eine Ablehnung führt zu einem Banner mit Deep-Link statt zu einer Blockade.
- [ ] AC-062: Der Mehrverbrauch wird erst angezeigt, wenn ein Messwert vorliegt.

### US-014: Einstellungen & Diagnose

**Phase:** 1 · **Agent:** Advocate · **Abhängig von:** US-009, US-011

**Description:** Als Nutzer möchte ich Modus, Ladeziel, Warnungen und Messkorrekturen selbst einstellen und den Eigenverbrauch der App sehen.

**Acceptance Criteria:**
- [ ] AC-063: Einstellungen für Hintergrundbetrieb, Ladeziel/Alarm/Erinnerung, Zusammenfassung, Hitzewarnung (Standard 45 °C, 40–50 °C) und Berechtigungsstatus sind vorhanden.
- [ ] AC-064: Die Zeile „Eigenverbrauch“ zeigt CPU-Zeit sowie Flush- und Update-Zähler des Dienstes.
- [ ] AC-065: Die Diagnose zeigt Rohwerte, ein Override von Einheit/Vorzeichen/Zellen wirkt rückwirkend auf alle Graphen, und „Alle Daten löschen“ verlangt eine Bestätigung.

### US-015: Energie-Gate & Robustheit

**Phase:** 1 · **Agent:** Tuner · **Abhängig von:** US-008, US-011, US-012, US-013, US-014

**Description:** Als Nutzer möchte ich mich darauf verlassen, dass die App kaum Akku kostet und auch nach Ausfällen korrekte Daten zeigt.

**Acceptance Criteria:**
- [ ] AC-066: Mess-Gate auf dem Release-Build bestanden: UID ≤ 5 mAh/24 h (Grenze 25), 0 eigene Wakeups/WakeLocks/Jobs, CPU beim Entladen ≤ 24 s/24 h, beim Laden ≤ 15 s/h, PSS `:monitor` ≤ 25 MB (AK3).
- [ ] AC-067: Vorzeichen in 5/5 Szenarien korrekt; Leistung über 10 min innerhalb ±20 % der ΔCHARGE_COUNTER-Referenz (AK1).
- [ ] AC-068: Reboot, Update, Force-Stop, Uhr ±1 h und Sommerzeitwechsel erzeugen keine negativen oder unendlichen Raten und keine Doppelsitzung; Lücken sind markiert (AK7).
- [ ] AC-069: Schlägt `quick_check` fehl, wird die DB als `.corrupt` beiseitegelegt, neu angelegt und ein Banner angezeigt.
- [ ] AC-070: Der Release-Build nutzt R8, entfernt `Log.d`/`Log.v` und ist nicht debuggable.

### US-016: Modus „Nur beim Laden“

**Phase:** 2 · **Agent:** Pathfinder · **Abhängig von:** US-015

**Description:** Als Nutzer ohne Dauer-Benachrichtigung möchte ich trotzdem beim Laden die Live-Info bekommen und ehrlich wissen, was fehlt.

**Acceptance Criteria:**
- [ ] AC-071: Der ChargeJobService (`requiresCharging`, persisted) startet mit Akku-Ausnahme einen FGS nur während des Ladens; beim Abstecken folgen Sofort-Flush und `stopSelf`; beim Entladen gibt es keine Dauer-Benachrichtigung.
- [ ] AC-072: Ohne Akku-Ausnahme startet der Modus nicht; ein Banner bietet [Erlauben] und [Zu „Immer aktiv“ wechseln].
- [ ] AC-073: Die gemessene Startlatenz wird in den Einstellungen angezeigt; bei Median > 30 min oder > 20 % ohne Start ist der Modus als „experimentell“ markiert; die App verspricht nie „sofort“ oder „lückenlos“ (AK8).
- [ ] AC-074: Zustand 3x („Ladeziel überschritten“) und die Zusammenfassung „Beginn nicht erfasst“ werden korrekt erzeugt.
- [ ] AC-075: Graph-Varianten: Entladephasen schraffiert zwischen Ankerpunkten, Ladekurve startet mit hohlem Punkt, Entladerate nur als Ø zwischen Ladevorgängen.

## Technical Notes
- **Architektur:** Kotlin-Dienst im Prozess `:monitor` als einziger Schreiber einer SQLite-Datei (WAL). Die Flutter-UI liest read-only per `sqflite` und spricht über Pigeon → `ContentProvider.call()` und Messenger mit dem Dienst. Details stehen in `design.md` unter „Architektur“.
- **Schema:** `spec/db/001_init.sql` plus `spec/db/q/*.sql`, als Assets in Android (Gradle) und Flutter (pubspec) eingebunden. Volles SQL steht in `deliberation/round3/architect.md`.
- **Engine-Spezifikation:** `spec/engine/spec.md` mit Golden-Fixtures in `spec/engine/fixtures/*.json`.
- **Manifest, Kanäle, Pigeon-API:** siehe `deliberation/round3/pathfinder.md`.
- **Sampling- und Gate-Tabellen, adb-Befehle:** siehe `deliberation/round3/tuner.md`.
- **State-Matrix, Onboarding-Text, Microcopy:** siehe `deliberation/round3/advocate.md`.
- **Theme-Code, Chart-Spezifikation, Icons:** siehe `deliberation/round3/artisan.md`.
- **Plausibilität, Geräteprofil, Edge-Cases:** siehe `deliberation/round3/skeptic.md`.
- **Spike-Gerät:** nur das Handy des Nutzers. Deshalb bleiben automatische Profil-Erkennung, manueller Override und die Anzeige „geschätzt“ Pflicht.

## Dependencies
- **Flutter-Pakete:** `sqflite`, `dynamic_color`. Dev: `pigeon`, `sqflite_common_ffi`, `flutter_lints`. `cupertino_icons` wird entfernt.
- **Android-Testabhängigkeiten:** `junit`, `org.robolectric:robolectric` (SQLite im JVM-Test), `androidx.test:runner`, `androidx.test.ext:junit`.
- **Werkzeuge:** Flutter 3.35.6 / Dart 3.9.2 (installiert), Android SDK mit API 36, `adb`, Battery Historian (optional, für das Mess-Gate).
- **Keine Netz- oder Backend-Abhängigkeiten.**
