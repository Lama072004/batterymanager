## Architect Position — Gesamtarchitektur Batterymanager

**Kernempfehlung:** Pro Plattform eine schlanke **Engine** (Android: Kotlin ohne Flutter-Engine; Desktop: reines Dart im Tray-Prozess). Sie verantwortet in Echtzeit Sampling, Ladesitzung, Restzeit, Ziel-Alarm und Benachrichtigung und schreibt nur in ein append-only **Sample-Journal**. Die Flutter-App besitzt als einzige die SQLite-Historie (drift) und baut sie beim Öffnen als Projektion aus dem Journal auf. Native und Flutter sprechen ausschließlich über einen kleinen Pigeon-Vertrag.

**Hauptargument:**
„Kein Strom im Hintergrund" zwingt alles Echtzeitkritische auf Android in nativen Code, denn ein Dart-Isolate im Service kostet 30–60 MB RAM und Wake-Zeit. Historie, Aggregate, Downsampling und Graphen braucht man dagegen nur, wenn der Nutzer hinschaut. Sie gehören deshalb nach Dart und existieren dort genau einmal für alle drei Plattformen. Die Eigentumsregel lautet: *Was die Engine zur Laufzeit braucht (Settings, laufende Session, gelerntes Ladeprofil), besitzt die Engine; was nur die UI braucht (Historie), besitzt Dart.*

Die Grenze zwischen beiden Welten ist **keine geteilte SQLite-Datei**. Eine solche hätte zwei Schreiber und bräuchte Migrationen über zwei Runtimes hinweg. Mit drift/sqlite3_flutter_libs neben der Android-Framework-SQLite lägen außerdem zwei SQLite-Bibliotheken im selben Prozess, und das ist eine bekannte POSIX-Lock- und Korruptionsfalle. Stattdessen gibt es ein versioniertes Binär-Journal mit festen 24-Byte-Records:
- Die Engine ist Single Writer des Journals, Dart ist Single Writer der DB.
- Der Import ist idempotent (ts als PK). Das ist Event-Sourcing light: Die DB lässt sich jederzeit neu aufbauen.

Echt dupliziert ist nur der **Engine-Kern** (Einheiten-Normalisierung, Session-State-Machine, Restzeit, Ziel-Alarm, ca. 300 LOC) in Kotlin und Dart. Eine Spezifikation mit geteilten **Golden-Fixtures** (JSON: Sample-Folge → erwartete Events/ETA ± Toleranz) sichert ihn ab; beide Testsuiten lesen sie. Ein Rust/C++-Kern über FFI/JNI lohnt bei diesem Umfang nicht, weil er drei Toolchains bräuchte.

„Einstellbar" bedeutet nur einen anderen **Host** (Dauer-FGS vs. job-getriggerter Lauf während des Ladens). Die Logik bleibt gleich: Beide Hosts betreiben denselben `ChargeMonitor`.

Auf dem Desktop reicht **ein Flutter-Prozess mit Tray** (Fenster versteckt, keine Frames). Engine und Kollektoren sind reines Dart ohne Flutter-Import. Sollte Tuner die Idle-Kosten zu hoch messen, lassen sie sich als headless `dart compile exe` auslagern, die dasselbe Journal schreibt. Das ist ein Erweiterungspunkt und wird jetzt noch nicht gebaut.

**Risiken, wenn ignoriert:**
- **Struktur/Daten:** Eine gemeinsame SQLite-Datei für Kotlin und Dart führt zu Schema-Drift und zwei Schreibern. Mit doppelter SQLite-Lib droht stille Korruption, weil fcntl-Locks prozessweit freigegeben werden.
- **Strom/Performance:** Eine Flutter-Engine im Android-Hintergrund (workmanager-/background_service-Plugins) verletzt die Kernanforderung, und zwar bei jedem Ladevorgang.
- **Integration:** Ohne kanonische Einheiten im Vertrag (Leistung in mW, signiert, + = in den Akku, Level in ‰) und ohne `Capabilities` landet das µA/mA- und Vorzeichen-Chaos in Restzeit und Graphen. Die Desktop-UI bricht dann an fehlender Spannung oder Temperatur.

**Abhängigkeiten:**
- **Pathfinder:** FGS-Typ, Job-Trigger für Modus (b), Notification-Stil (Android 16 ProgressStyle/Live Update), Desktop-Quellen (CallNtPowerInformation/IOCTL per FFI, sysfs/UPower). Daraus folgen die `Capabilities`-Felder.
- **Tuner:** Sampling-Intervalle, Flush-Intervall des Journals (Batching), Messung der Idle-Kosten des Desktop-Trays (Entscheidung In-Process vs. Daemon).
- **Skeptic:** Hersteller-Heuristiken für CURRENT_NOW als Fixtures; Lücken, Reboot, Uhrzeitsprung.
- **Advocate:** Darstellung von Lücken in Modus (b); Semantik der Settings.
- **Artisan:** Die Chart-Komponente (CustomPainter) erhält fertig downgesampelte Serien mit ≤ 600 Punkten; Theme über dynamic_color.

---

### Anhang A — Kontext-Briefing (kurz)
- Flutter 3.35.6 / Dart 3.9.2, frisches Template; Abhängigkeiten nur cupertino_icons und flutter_lints. Keine DB, kein State-Management, kein Netzwerk.
- Android: Kotlin-DSL, Java 11, minSdk = Flutter-Default.
- **Warnung 1:** `applicationId com.example.batterymanager` vor dem ersten Build auf eine eigene ID ändern.
- **Warnung 2:** Release nutzt Debug-Signing → eigenen Keystore anlegen. Sideload-Updates brauchen gleiche ID und gleiche Signatur.
- windows/ und linux/ sind Standard-Runner. Für Batteriezugriff ist kein Runner-C++ nötig (FFI bzw. dart:io).

### Anhang B — Komponenten (C4, Container)
```
ANDROID                                                       DESKTOP (1 Prozess, Tray)
┌─ Kotlin-Engine (ohne Flutter) ───────────┐                  ┌─ Dart-Engine (ohne Flutter-Import)
│ BatterySource ─► ChargeMonitor ─► Journal│──┐               │ Win/Linux-Collector ─► ChargeMonitor
│  (BatteryManager,  (Session, ETA,   *.bin│  │               │        ─► Journal ─► Tray/Toast
│   Screen-State)     Alarm, Hitze)        │  │               └──────────────┬───────────────────
│ Host: MonitorService(FGS) | ChargeJob    │  │ import                       │ import
│ ChargeNotifier ◄─ ChargeMonitor          │  ▼                              ▼
└────────▲──────────────────────────▲──────┘  Projector ─► drift-DB (Dart-only) ─► HistoryRepository
         │ Pigeon HostApi           │ EventChannel (nur solange abonniert)        │
         └────────── Flutter-UI (Dashboard · Verlauf · Ladevorgänge · Einstellungen)┘
```

### Anhang C — Schnittstellen-Vertrag (`pigeons/battery_api.dart`)
```dart
enum ChargeState { charging, discharging, full, notCharging, unknown }
enum PlugType { none, ac, usb, wireless, dock }
enum BackgroundMode { persistentService, onChargeOnly }

class BatterySnapshot {
  int ts; int levelPermille; ChargeState state; PlugType plug; bool screenOn;
  int? powerMw;            // kanonisch, + = in den Akku
  int? currentMa; int? voltageMv; int? tempDeciC;
  int? etaFullSec; int? etaTargetSec; double etaConfidence; // 0..1
  int? sessionStartTs; int? sessionPeakMw; int? sessionEnergyMwh;
}
class Capabilities { bool power, voltage, temp, cycles, health;
  int? cycleCount; int? designCapacityMah; int? fullCapacityMah; String? health; }
class EngineSettings { int? targetPct; int alarmMask; // 1 Notif, 2 Ton, 4 Vibration
  BackgroundMode mode; int heatWarnDeciC; }

@HostApi() abstract class BatteryHostApi {
  BatterySnapshot snapshot();
  Capabilities capabilities();
  EngineSettings settings();
  void updateSettings(EngineSettings s); // Engine wendet Seiteneffekte an (FGS start/stop, Alarm)
  String flushJournal();                 // Puffer schreiben, liefert Journal-Verzeichnis
}
@EventChannelApi() abstract class BatteryEvents { BatterySnapshot live(); }
```
Die Dart-Seite kapselt das hinter dem Port `BatteryBackend` (`live`, `snapshot`, `capabilities`, `settings`, `updateSettings`, `syncHistory`). Es gibt zwei Implementierungen: `AndroidBackend` (Pigeon, Typen werden auf Domänenmodelle gemappt) und `DesktopBackend` (In-Process-Engine).

**Settings:** Besitzer ist die Engine. Android nutzt eine eigene SharedPreferences-Datei, *nicht* die des Plugins; der Desktop eine JSON-Datei im App-Support-Verzeichnis. Die UI schreibt nur über `updateSettings`, weil jede Änderung Seiteneffekte auslöst.

### Anhang D — Journal v1
Ein Segment pro Tag (`journal/YYYYMMDD.bin`) mit Header `BMJ1` + Version, danach 24-Byte-Records (little-endian):

| Off | Typ | Feld |
|---|---|---|
| 0 | i64 | ts (ms UTC) |
| 8 | u8 | kind: 0 Sample · 1 SessionStart · 2 SessionEnd · 3 TargetReached · 4 ScreenOn · 5 ScreenOff · 6 EngineStart/Gap |
| 9 | u8 | plug(3 Bit) · state(3 Bit) · screen(1 Bit) |
| 10 | i16 | levelPermille |
| 12 | i32 | powerMw (INT32_MIN = n/a) |
| 16 | i32 | currentMa |
| 20 | u16 | voltageMv |
| 22 | i16 | tempDeciC |

- Ein unvollständiger Rest-Record wird ignoriert; das macht das Format crash-sicher.
- Die Engine löscht Segmente nach 35 Tagen.
- Volumen: ca. 3k Records/Tag ≈ 72 KB.

### Anhang E — Schema (drift, nur Dart; RLS entfällt: lokal, Einzelnutzer, App-Sandbox)

| Entität | Zweck | Retention |
|---|---|---|
| `sample` | Rohzeitreihe | 30 Tage |
| `charge_session` | Ladevorgänge inkl. Kurve | unbegrenzt (~1 KB/Stück) |
| `day_stat` | Tagesaggregate an/aus | unbegrenzt |
| `kv` | Journal-Cursor, letztes Rollup | – |

Beziehungen sind **zeitlich, ohne FK**, damit die Retention Samples unabhängig löschen kann:
```
charge_session 1──N sample  (sample.ts BETWEEN start_ts AND end_ts)
day_stat       1──N sample  (lokales Datum)
```

```sql
CREATE TABLE sample (
  ts INTEGER PRIMARY KEY, level_pm INTEGER NOT NULL CHECK(level_pm BETWEEN 0 AND 1000),
  power_mw INTEGER, current_ma INTEGER, voltage_mv INTEGER, temp_dc INTEGER,
  plug INTEGER NOT NULL, state INTEGER NOT NULL, screen_on INTEGER NOT NULL
) WITHOUT ROWID;                                   -- geclustert nach ts → Range-Scans
CREATE TABLE charge_session (
  id INTEGER PRIMARY KEY, start_ts INTEGER NOT NULL UNIQUE, end_ts INTEGER,
  start_level_pm INTEGER NOT NULL, end_level_pm INTEGER, plug INTEGER NOT NULL,
  peak_power_mw INTEGER, avg_power_mw INTEGER, energy_mwh INTEGER,
  target_pct INTEGER, target_reached_ts INTEGER, end_reason INTEGER,  -- 0 ab,1 voll,2 Lücke
  has_gap INTEGER NOT NULL DEFAULT 0, curve BLOB,    -- ≤120 Pkt (t_off_s, mW, ‰)
  updated_at INTEGER NOT NULL
);
CREATE TABLE day_stat (
  day TEXT PRIMARY KEY, screen_on_s INTEGER NOT NULL, screen_off_s INTEGER NOT NULL,
  drain_on_pm INTEGER NOT NULL, drain_off_pm INTEGER NOT NULL,
  energy_out_on_mwh INTEGER, energy_out_off_mwh INTEGER, energy_in_mwh INTEGER,
  charge_count INTEGER NOT NULL, max_temp_dc INTEGER, updated_at INTEGER NOT NULL
);
CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- Down: DROP TABLE kv; DROP TABLE day_stat; DROP TABLE charge_session; DROP TABLE sample;
```

**Indizes:**
- Der PK `sample.ts` deckt alle Graph-Abfragen ab.
- `UNIQUE(start_ts)` deckt die Sitzungsliste und den idempotenten Upsert ab.
- Weitere Indizes sind nicht nötig.

**Denormalisierung:**
- `peak/avg/energy` und `curve` werden einmal beim Import von SessionEnd berechnet. Graph 2 funktioniert so auch nach Ablauf der Sample-Retention.
- `day_stat` wird nur für abgeschlossene Tage gerollt; den heutigen Tag berechnet die App on-the-fly.

**Downsampling zur Abfragezeit:**
- Abfrage: `GROUP BY ts / :bucketMs` mit MIN/MAX(level) und AVG(power), maximal ca. 600 Punkte.
- Bucket-Größe: 24 h → 3 min, 7 d → 15 min.
- Persistierte Downsample-Tabellen gibt es nicht (YAGNI); 7 Tage sind ca. 15k Zeilen.

### Anhang F — Projektstruktur
```
lib/
  main.dart                 # Composition Root: Backend je Plattform
  app/                      # App, Theme (dynamic_color), Navigation
  core/model/  core/backend/battery_backend.dart  core/format/ (de: W, „1 h 23 min")
  engine/                   # reines Dart: normalizer, session_detector, eta, target_alarm
  platform/android/         # Pigeon-Code + AndroidBackend
  platform/desktop/         # DesktopBackend, collector_linux/_windows, tray, notifier
  data/journal/  data/db/   # reader/writer, drift-Tabellen, projector, retention, history_repository
  features/{dashboard,history,sessions,settings}/   # je controller.dart + view
  ui/charts/                # ein schlanker CustomPainter-Liniengraph
pigeons/battery_api.dart
spec/engine-fixtures/*.json + spec/journal-v1.md   # von Kotlin- UND Dart-Tests gelesen
android/app/src/main/kotlin/<id>/
  api/ engine/ collect/ service/{MonitorService,ChargeJob,BootReceiver} journal/ notify/ settings/
```
**State-Management:** kein Framework.
- Pro Feature ein `ChangeNotifier` mit `ListenableBuilder`/`StreamBuilder`; Abhängigkeiten per Konstruktor.
- Der Live-Stream wird über `AppLifecycleListener` nur im Vordergrund abonniert. Dadurch stoppt das native Polling, sobald die App im Hintergrund ist.
- Riverpod erst, wenn es mehr als ca. 6 Screens werden.

### Anhang G — Phasing
0. **Spike:**
   - Rohwerte-Logger auf den Zielgeräten.
   - Journal v1, Pigeon-Vertrag und Fixtures festlegen.
   - Eigene applicationId und Release-Keystore.
1. **Android-MVP:**
   - Kotlin-Engine mit FGS-Modus.
   - Live-Benachrichtigung mit W, Restzeit und Ziel-Alarm.
   - Dashboard, Einstellungen, Graph 1.
2. **Android komplett:**
   - Modus (b), Graph 2 und 3.
   - Rollups und Retention.
   - Gelerntes Ladeprofil (EMA pro 1-%-Bucket, Fallback auf eine CC/CV-Heuristik).
   - Hitzewarnung.
3. **Linux:** sysfs per dart:io, kein nativer Code.
4. **Windows:** FFI.
   - Die Dart-Engine wird gegen dieselben Fixtures getestet.
   - Tray und Toast.
