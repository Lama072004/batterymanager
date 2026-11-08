# Design-Dokument: Batterymanager

Session: `batterymanager-app-20261106-1815` · Datum: 2026-11-06 · Council: Pathfinder, Tuner, Architect, Artisan, Advocate, Skeptic
Detailquellen: `deliberation/round3/*.md` (vollständige Code-, SQL- und Tabellen-Anhänge je Agent)

## Überblick

Der Batterymanager ist im Alltag **eine Benachrichtigung, keine App**. Beim Einstecken poppt eine Live-Benachrichtigung auf. Sie zeigt Ladeleistung (W), Restzeit bis zum Ladeziel und bis voll, und sie klingelt genau einmal, wenn das Ladeziel erreicht ist. Die App selbst ist ein einziger, ruhiger Scroll-Screen mit Akkustand, Detailwerten und drei Graphen. Dazu kommen die Einstellungen und ein einmaliges Onboarding. Sie folgt Material You und schaltet automatisch zwischen hell und dunkel.

Die oberste Anforderung, **fast kein Eigenverbrauch**, bestimmt die Architektur. Auf Android läuft die gesamte Hintergrundlogik nativ in Kotlin im eigenen Prozess `:monitor`, ohne Flutter-Engine. Beim Entladen hängt sie sich nur an Ereignisse, die Android ohnehin sendet, und hat keine eigene Weckquelle. Gemessen, aktualisiert und gewartet wird nur beim Laden, dann zahlt das Netzteil. Flutter ist reine Anzeige und läuft nur, solange die App sichtbar ist. Budget: **≤ 5 mAh/24 h Ziel, 25 mAh harte Grenze** (ca. 0,1 % bzw. 0,5 % pro Tag), abgenommen über messbare Zähler.

**Plattform-Umfang (Nutzerentscheidung, 2026-11-06): vorerst nur Android.** Windows und Linux werden weder gebaut noch getestet. Die Architektur hält sie als spätere Option offen (siehe „Später: Desktop“). Phasen: **Phase 0** Mess-Spike → **1** Android „Immer aktiv“ → **2** Android „Nur beim Laden“.

---

## Architektur

### Komponenten

```
Android
┌──────────────── Prozess :monitor (Kotlin, KEIN Flutter) ────────────────┐
│ MonitorService (FGS specialUse)  ChargeJobService  BootReceiver         │
│ NotificationActionReceiver       MonitorProvider (ContentProvider.call) │
│ engine/: Normalizer · ProfileDetector · SessionMachine · Eta ·          │
│          TargetAlarm · Writer/Migrator   notify/: NotificationController│
│        │ einziger Schreiber                                             │
│        ▼                                                                │
│   batterymanager.db  (SQLite, WAL, Schema aus spec/db/*.sql)            │
└────────┬───────────────────────────────▲────────────────────────────────┘
         │ read-only (sqflite =          │ call(): getState/putSettings/
         │ Framework-SQLite)             │ setProfile/flush/snapshot
         ▼                               │ Messenger: Live-Snapshot ≤ 1 Hz
┌──────────────── UI-Prozess (Flutter, nur wenn sichtbar) ────────────────┐
│ Dart UI ──Pigeon──► Kotlin MonitorBridge ──► Provider / Messenger        │
└──────────────────────────────────────────────────────────────────────────┘
```
(Desktop ist nicht im aktuellen Umfang, siehe „Später: Desktop“.)

### Grundregeln
1. **Ein Schreiber und Migrator**: der Kotlin-Dienst in `:monitor`. Der UI-Prozess schreibt nie in die DB. In `:monitor` wird nie `libflutter.so` geladen, `Application.onCreate` prüft dafür den Prozessnamen.
2. **Rohwerte speichern, beim Lesen normalisieren.** Die Rohwerte gehen zusammen mit `device_profile` (Einheit, Vorzeichen, Zellen) in die DB, die Normalisierung übernehmen SQL-Views (`sample_n`, `point_n`, `session_n`). Eine Korrektur des Profils wirkt so rückwirkend auf alle Graphen. Der Kotlin-Live-Pfad verwendet dieselbe Profilzeile.
3. **Abdeckung explizit**: Tabelle `span` (Aufzeichnungsintervalle mit `end_reason`). Lücken werden nie aus der Sample-Dichte erraten und nie interpoliert. Raten werden nur aus `elapsed_ms` innerhalb eines Spans berechnet.
4. **Engine-Kern nur in Kotlin, spezifiziert durch Golden-Fixtures**: `spec/engine/spec.md` plus `spec/engine/fixtures/*.json`. JUnit muss grün sein. Das plattformneutrale Fixture-Format bleibt, damit ein späterer Desktop-Zwilling in Dart dieselbe Spezifikation nutzen kann.
5. **Entkopplung**: Der Ziel-Alarm hängt nur am %-Übergang aus `EXTRA_LEVEL`, nicht an Strom oder Zeit. Das Ladeprofil ist einheitenfrei (Sekunden pro %). Ein falsches Geräteprofil verdirbt deshalb weder Alarm noch Lernkurve.

### Datenmodell (Schema v1, `spec/db/001_init.sql`)
Volles SQL: `deliberation/round3/architect.md`. Nur SQLite-3.9-Teilmenge (minSdk 24), `auto_vacuum=INCREMENTAL`, `journal_mode=WAL`, `synchronous=NORMAL`, Version über `user_version`.

| Tabelle | Zweck | Retention |
|---|---|---|
| `span` | Aufzeichnungsintervall: boot, mode, start/end, end_reason (sauber/Kill/Reboot/Moduswechsel) | 30 Tage |
| `sample` | Rohwerte: ts_utc, elapsed_ms, **tz_min**, level_pm, status, plug, screen (NULL = unbekannt), current_raw, voltage_raw, power_raw, counter_raw, temp_dc, Qualitäts-Bits `q` | 30 Tage |
| `charge_session` | Ladevorgang: Start/Ende, Start-/End-%, Steckertyp, Leistungsklasse, Ziel, Ziel erreicht, Energie, Peak, ETA-Fehler | unbegrenzt (~2 KB/Vorgang) |
| `session_point` | ≤ 120 Punkte je Ladevorgang (für Graph 2, rückwirkend normalisierbar) | unbegrenzt |
| `charge_profile` | gelernte Ladekurve: (plug, klass, pct) → sec_per_pct, n | unbegrenzt |
| `device_profile` | Einheit/Vorzeichen/Zellen/Leistungsquelle, Quelle (Default/auto/manuell), rev | 1 Zeile |
| `day_stat` | Tagesaggregat: Abdeckung, Bildschirm an/aus/unbekannt, Entladung je Zustand, Ladevorgänge, max. Temperatur | unbegrenzt |
| `kv` | Settings (`set.*`), Systemcursor (`sys.*`) | — |

Qualitäts-Bits pro Sample: OK, geschätzt, n/v, außerhalb Bereich, Vorzeichenkonflikt, Warmup, Ladepause, Einzelpunkt.
Graph-Serien entstehen aus `q/series.sql`: Buckets für 24 h = 3 min, für 7 Tage = 20 min, höchstens 600 Punkte, die Linie bricht an Span-Grenzen.
Die Wartung (Tages-Rollup, Löschen in 5k-Batches, `incremental_vacuum`, `wal_checkpoint(TRUNCATE)`) läuft höchstens einmal in 24 h und **nur beim Laden**.

### Schnittstellen
- **`MonitorProvider.call()`**: Authority `${applicationId}.monitor`, nicht exportiert, jedes Bundle mit `v=1`.
  - Methoden:
    - `getState` → Settings, Capabilities, Schema, profileRev, offener Span, gemessene Job-Latenz
    - `putSettings` (targetPct, alarmMask, mode, heatWarnDc, etaOn)
    - `setProfile` (iToMa, sign, vToMv, cells | auto)
    - `flush`
    - `snapshot`
  - Liefert der Provider bei einer unbekannten Methode `null`, zeigt die UI „Dienst veraltet“.
- **Messenger** (Live, nur bei sichtbarer UI):
  - Nachrichten: REGISTER, UNREGISTER, SNAPSHOT.
  - Inhalt: ts, levelPm, status, plug, screen?, powerMw?, currentMa?, voltageMv?, tempDc?, etaState, etaFullS?, etaTargetS?, etaConf, sessionStartTs?, peakMw?, energyMwh?, profileRev.
  - Push nur bei Änderung, höchstens 1 Hz.
- **Pigeon** (Dart ↔ Kotlin im UI-Prozess):
  - `@HostApi MonitorApi` spiegelt die Provider-Methoden und liefert zusätzlich `dbPath()` und `openSystemSettings(String)`.
  - `@EventChannelApi` liefert die Live-Snapshots.
- **Dart-Port `BatteryBackend`**: Implementiert wird nur `AndroidBackend` (Pigeon), dazu ein `FakeBackend` für Widget-Tests. Der Port hält einen späteren `DesktopBackend` offen.

### Restzeit-Schätzung (ETA)
1. **Warmup:** Die ersten 90 s zeigt die App „wird berechnet“. Danach bestimmt sie die Leistungsklasse aus der Warmup-Leistung (≤ 7,5 / ≤ 18 / > 18 W).
2. **Sonderzustände:** „Ladepause/Limit“ (|I| < 50 mA und % seit 10 min konstant) und „entlädt trotz Kabel“ **ersetzen** die ETA.
3. **Live-Rate:** EWMA der Sekunden pro %-Schritt. Der Strom dient nur der Interpolation zwischen %-Schritten und der Erkennung des Übergangs CC → CV.
4. **Restzeit** = Σ `sec_per_pct[plug, klass, p]` · k. Der Faktor k ist die EWMA aus Live- zu Profilrate, begrenzt auf 0,5–2. Fehlt das Profil, gilt bis 80 % die Live-Rate, darüber eine linear steigende Rate bis 3×.
5. **Konfidenz** = min(1, n̄/5) · Abdeckung · (1 − Streuung von k).
   - ab 0,7: Anzeige in 5-min-Stufen
   - ab 0,4: Anzeige als „ca.“ in 15-min-Stufen
   - darunter: „—“
   - Die Anzeige ändert sich erst ab |Δ| > max(1 min, 10 %).
6. **Lernen:** am Ende jedes Ladevorgangs, nur aus lückenlosen %-Schritten ohne Pause, EMA mit α = 0,3. `eta_err_s` misst die Qualität.

---

## Energie & Performance

| Zustand | Sampling | Benachrichtigung | Flush |
|---|---|---|---|
| Laden, Bildschirm an | BATTERY_CHANGED + Handler-Tick **10 s** (CURRENT_NOW, W = I·U, EMA α = 0,3) | höchstens 1×/10 s, nur bei ΔW ≥ 0,5 W, Δ% ≥ 1 oder ΔETA ≥ 1 min | Batch alle 60 s |
| Laden, Bildschirm aus | BATTERY_CHANGED + Handler **30 s** (`uptimeMillis`, weckt nicht) | **0 Updates**; bei SCREEN_ON sofort 1 Update | Batch alle 60 s |
| Entladen (an/aus) | **nur** BATTERY_CHANGED (~10 min + je 1 %) und SCREEN_ON/OFF; je Ereignis CHARGE_COUNTER, CURRENT_NOW, Temperatur | statisch, nie aktualisiert | pro Ereignis (`goAsync`) |
| Modus „Nur beim Laden“ | wie Laden; der FGS endet beim Abstecken | wie Laden | Sofort-Flush, dann `stopSelf` |

- **Sofort-Flush immer bei:** Einstecken, Abstecken, Ziel erreicht, voll, `onTrimMemory`, Suspend und Shutdown.
- **UI:** Live-Daten nur im Zustand `resumed`, beim Laden ≤ 1 Hz, beim Entladen ≤ 0,2 Hz. Es gibt keine Animationen. Charts zeichnen nur bei einer neuen Datenversion neu.
- **Verboten:** AlarmManager, WakeLock, periodische Jobs beim Entladen, `workmanager`, `flutter_background_service`, `flutter_foreground_task`, `battery_plus`, gecachte Flutter-Engines.

**Mess-Gate:** Es läuft auf einem Release-Build, zuerst als Phase-0-Spike und danach vor jedem Release. Die Befehle stehen in `round3/tuner.md`.

| Kriterium | Ziel | Grenze |
|---|---|---|
| UID-Schätzung beim Entladen (batterystats) | ≤ 5 mAh/24 h | 25 mAh |
| eigene Wakeup-Alarme, WakeLocks, Jobs | 0 | 0 |
| CPU-Zeit beim Entladen | ≤ 24 s/24 h | 48 s |
| CPU-Zeit beim Laden | ≤ 15 s/h | 30 s/h |
| Neustarts von `:monitor` in 24 h | 0 | 1 |
| PSS von `:monitor` | ≤ 25 MB | 40 MB |

---

## Plattform-Details

### Android
- **Identität:**
  - eigene `applicationId` (siehe offene Punkte) und Release-Keystore, beide festgelegt **vor der ersten Installation**
  - targetSdk 36, minSdk 24
  - `allowBackup=false`
  - `INTERNET` im Release entfernt
- **Berechtigungen:**
  - `POST_NOTIFICATIONS`
  - `FOREGROUND_SERVICE` und `FOREGROUND_SERVICE_SPECIAL_USE`
  - `RECEIVE_BOOT_COMPLETED`
  - `VIBRATE`
  - `POST_PROMOTED_NOTIFICATIONS`
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- **Komponenten:** Alle laufen in `android:process=":monitor"` und sind nicht exportiert. Das Manifest steht in `round3/pathfinder.md`.
  - `BatteryMonitorService`: FGS vom Typ `specialUse` mit `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  - `ChargeJobService` mit `requiresCharging` und `setPersisted`
  - `BootReceiver` für `BOOT_COMPLETED` und `MY_PACKAGE_REPLACED`
  - `NotificationActionReceiver`
  - `MonitorProvider`
- **Dynamische Receiver:** `ACTION_BATTERY_CHANGED` und `SCREEN_ON/OFF` werden nur dynamisch im Dienst registriert.
- **Modus „Nur beim Laden“:**
  - Der Job startet den FGS mit `FOREGROUND_SERVICE_IMMEDIATE`. Erlaubt ist das nur mit der Akku-Optimierungs-Ausnahme, deshalb ist sie in diesem Modus Pflicht.
  - Beim Entladen ist der Dienst höchstens gebunden aktiv, ohne `startForeground`.
- **Notification-Kanäle** (IDs und Importance sind endgültig):

| Kanal | Importance | Verwendung |
|---|---|---|
| `monitor` | MIN, stumm | Dauer-Benachrichtigung, nur im Modus „Immer aktiv“ |
| `charging` | HIGH ohne Ton | Live-Lade-Info: Heads-up 1×, danach `setOnlyAlertOnce`; ab API 36 `ProgressStyle`, `setRequestPromotedOngoing`, `setShortCriticalText` |
| `target_alarm` | HIGH, `USAGE_ALARM`, Vibration | „Jetzt abstecken“ |
| `heat` | HIGH ohne Ton | Hitzewarnung |
| `status` | DEFAULT | Dienst blockiert, Berechtigung oder Ausnahme fehlt |

- **Genau eine sichtbare App-Benachrichtigung:**
  - Ruhezustand: ID 1 auf Kanal `monitor`.
  - Beim Einstecken: `startForeground(ID 2)` auf Kanal `charging`, ID 1 wird entfernt.
  - Ziel-Alarm: Re-Post derselben ID 2 auf Kanal `target_alarm`. Ob das an einer FGS-Benachrichtigung greift, prüft Spike S5. Fallback: ID 3, die beim nächsten Update entfernt wird.
  - Gestaltung: keine Custom-RemoteViews, kein Full-Screen-Intent.
  - Aktionen: „Ziel 80 %“ schaltet durch 80 → 85 → 90 → 100. „Alarm aus“ gilt nur für diesen Ladevorgang. Beide laufen nativ über den `NotificationActionReceiver`, ohne Flutter.

### Später: Desktop (nicht im aktuellen Umfang)
Windows und Linux werden vorerst weder gebaut noch getestet. Die Ordner `windows/` und `linux/` im Projekt bleiben unangetastet. Für eine spätere Erweiterung hat das Council festgehalten:
- **Host:** Die Dart-Engine läuft als Zwilling der Kotlin-Engine im verborgenen Flutter-Prozess, ohne C++-Helper.
- **Datenquellen:** Linux über UPower bzw. sysfs, Windows über `IOCTL_BATTERY_*` und Power-Callbacks per FFI.
- **Mess-Gate:** höchstens 10 mW, Grenze 30 mW.

Die Details stehen in `round3/pathfinder.md` und `round3/tuner.md`. Der Port `BatteryBackend`, die SQL-Dateien unter `spec/db/` und die Golden-Fixtures sind schon plattformneutral angelegt.

### Pakete
- **Nutzen:**
  - `sqflite` (Android, liest read-only)
  - `dynamic_color`
  - `pigeon` (dev)
  - `sqflite_common_ffi` (nur dev: Tests der `q/*.sql`-Abfragen auf dem Rechner)
- **Nicht nutzen:** `battery_plus`, `workmanager`, `flutter_foreground_task`, `flutter_local_notifications`, `drift`, `fl_chart`, `cupertino_icons`
- **APK prüfen:** Ein mitgebündeltes `libsqlite3.so` darf auf Android nie geladen werden.

---

## User Experience

### Lade-Benachrichtigung (Modus „Immer aktiv“)
| # | Zustand | Titel | Text | Aktionen | Kanal / Signal |
|---|---|---|---|---|---|
| 0 | Bereit | Batterymanager ist bereit | Lade-Info erscheint beim Einstecken. | – | monitor, keins |
| 0h | Warm beim Entladen | Akku warm: 46 °C | Gerät abkühlen lassen. | Verstanden | heat, Heads-up 1× |
| 1 | Eingesteckt | Lädt · 42 % | Ladeleistung wird ermittelt … | Ziel 80 % · Alarm aus | charging, Heads-up 1× still |
| 2 | Lädt | Lädt mit 18 W · 42 % | 80 % in ca. 25 Min. · Voll ca. 23:25 | wie 1 | charging, still |
| 2b | Langsam / Defizit | Lädt langsam · 4,5 W / Eingesteckt, entlädt trotzdem | Restzeit / Ladegerät zu schwach. | wie 1 | charging, still |
| 2c | Ladepause | Ladepause · 80 % | Gerät pausiert das Laden (Limit/adaptiv). | wie 1 | charging, still |
| 2d | Warm beim Laden | Akku warm: 46 °C · 18 W | wie 2 | wie 1 | heat, Heads-up 1× |
| 3 | Ziel erreicht | Ladeziel 80 % erreicht | Jetzt abstecken, um den Akku zu schonen. | Verstanden | target_alarm, Ton 1×, Erinnerung nach 10 Min. |
| 4 | Voll | Voll geladen | Seit 23:25 bei 100 % | – | charging, still |
| 5 | Abgesteckt | Ladevorgang beendet | 23 % → 81 % in 52 Min. · Ø 15 W, max. 25 W | Tippen → Graph 2 | charging, still, nach 15 Min. zurück zu 0 |

- **Fortschrittsbalken:** zeigt den Akkustand mit einer Marke am Ziel, ab Android 16 als `ProgressStyle` mit Chip „18 W“.
- **Kein Countdown:** Die Benachrichtigung zeigt keinen Sekunden-Countdown, „Voll“ steht als Uhrzeit da.
- **Leistungsangabe:** Gezeigt wird die Leistung „in den Akku“ (netto), nicht die des Ladegeräts.
- **Unterschiede im Modus „Nur beim Laden“:**
  - Die Zustände 0 und 0h entfallen.
  - Die Zustände 1 und 2 kommen verspätet, mit „Erfasst seit 22:15“.
  - Neuer Zustand **3x**: Ladeziel beim Start schon überschritten. Titel „Ladeziel 80 % überschritten · 86 %“, Ton 1×.
  - Zustand 5 zeigt „Erfasst ab 57 % → 81 % · Beginn nicht erfasst“.

### Onboarding (ein Screen, danach Berechtigungen)
> **Wie soll der Batterymanager arbeiten?**
> ◉ **Immer aktiv** (empfohlen): Die Lade-Info erscheint sofort beim Einstecken, der Verlauf ist vollständig und der Verbrauch wird nach Bildschirm an und aus getrennt. Dafür zeigt Android dauerhaft eine kleine, stumme Benachrichtigung. *Mehrverbrauch: [gemessener Wert]*
> ○ **Nur beim Laden**: Es gibt keine Dauer-Benachrichtigung. Die Lade-Info erscheint aber meist erst 15–20 Minuten nach dem Einstecken, und ein früh erreichtes Ladeziel kann verpasst werden. Beim Entladen läuft nichts, deshalb hat der Verlauf Lücken und der Verbrauch ist nur ein Durchschnitt. Spart kaum Akku.
> *Jederzeit in den Einstellungen änderbar.*

**Reihenfolge der Berechtigungen** (vor jeder Abfrage eine Begründung in einem Satz; wird abgelehnt, erscheint ein Banner mit Deep-Link):
1. `POST_NOTIFICATIONS`
2. Akku-Optimierungs-Ausnahme
   - „Immer aktiv“: empfohlen, mit [Später]
   - „Nur beim Laden“: Pflicht, mit [Zu „Immer aktiv“ wechseln]
3. Bei Xiaomi, Samsung oder Huawei: Link zur Akku-Einstellung des Herstellers

### Screens
- **Übersicht** (ein Scroll-Screen, keine Bottom-Navigation):
  - **Banner-Slot** mit Rangfolge: Benachrichtigungen aus > Dienst beendet > Ausnahme fehlt > Alarm stumm
  - **Hero:** 64 %, „Lädt mit 18 W“ bzw. „Entlädt · 6 %/h“, Fortschrittsbalken mit Zielmarke, Restzeit, Chip „Ladeziel 80 %“
  - **Werte-Raster** in 2 Spalten: Temperatur, Zustand, Spannung, Strom, Kapazität (ca.), Zyklen. Fehlende Werte werden ausgeblendet.
  - **Graphen:** Umschalter `[24 Std. │ 7 Tage]` und darunter die drei Graphen
- **Ladeziel-Sheet:** 80 │ 85 │ 90 │ 100 % oder ein eigener Wert (50–100), dazu der Schalter „Alarm, wenn erreicht“.
- **Einstellungen:**
  - Hintergrundbetrieb, in „Nur beim Laden“ mit den zuletzt gemessenen Startverzögerungen
  - Ladeziel, Alarm, Erinnerung
  - Benachrichtigungen: Zusammenfassung, Hitzewarnung (Standard 45 °C, 40–50)
  - Status der Berechtigungen
  - Eigenverbrauch: CPU-Zeit, Flush- und Update-Zähler
  - Diagnose: Rohwerte, Einheit und Vorzeichen korrigieren, alle Daten löschen
- **Leer-, Lücken- und Fehlerzustände:** Die Microcopy-Tabelle steht in `round3/advocate.md`. Grundregeln:
  - Jede Lücke ist sichtbar und erklärt.
  - Ein unplausibler Wert wird als „–“ angezeigt, mit dem Hinweis „Dieses Gerät meldet keinen verlässlichen Wert.“
  - Ein fehlender Wert erscheint nie als „0 W“.

### Barrierefreiheit (Abnahmekriterium)
- **Screenreader:** Jeder Chart bekommt eine `Semantics`-Textzusammenfassung.
- **Kontrast:** Text ≥ 4,5:1, Linien und Schraffur ≥ 3:1, jeweils hell und dunkel.
- **Zustandsanzeige:** Ein Zustand wird nie nur über Farbe gezeigt, sondern immer mit Icon und Text.
- **Textgröße:** Der Hero ist auf 1,5× begrenzt, alles andere skaliert frei. Bei 200 % wird das Raster einspaltig.
- **Reduzierte Bewegung:** `disableAnimations` wird respektiert.

---

## Visual Design

- **Farben:**
  - Ein Farbpfad für alle Plattformen: `DynamicColorBuilder` → primary des Systems bzw. Seed `#4A6FA5` → `ColorScheme.fromSeed`.
  - `ThemeMode.system`, `Locale('de','DE')`.
  - Im Dunkelmodus gilt die M3-Surface, kein reines Schwarz.
- **`ThemeExtension BatteryColors`:**
  - `charge` und `warm` werden per `harmonizeWith(primary)` an die Palette angepasst, Varianten aus `DynamicSchemeVariant.fidelity`.
  - `hot` = `error`, `gap` = `outline`.
  - Damit hält der Kontrast unter jeder Material-You-Palette. Die Code-Skizze steht in `round3/artisan.md`.
- **Typografie:** Systemschrift mit `FontFeature.tabularFigures()` auf allen Zahlen-Stilen. Zahlen im Format `de_DE` mit U+202F vor der Einheit („18,4 W“).
- **Bewegung:** keine. Verboten sind unbestimmte Indikatoren, Shimmer, Pulsieren, Einblend- und Hochzähl-Animationen. Zahlen wechseln in 0 ms.
- **Charts:** `CustomPainter` in einer `RepaintBoundary`. Min/Max-Bucketing auf höchstens 1 Punkt pro Pixel. Der Path-Cache-Schlüssel ist Zeitfenster + `device_profile.rev`.

| | 1 Akkustand | 2 Ladeleistung | 3 Entladerate |
|---|---|---|---|
| Form | Linie 2 dp, 160 dp hoch | Linie 2 dp, 140 dp hoch | 2 Balkenzeilen à 28 dp |
| Achsen | x Zeit, y 0–100, Gitter bei 50 | x Akku-% 0–100, y bis zum runden Watt-Wert | direkt beschriftet (%/h) |
| Kodierung | Entladen `primary`, Laden `charge` + 12-%-Fläche, Ziel gestrichelt | gewählter Vorgang `charge`, bis zu 3 frühere `outlineVariant`; Navigation ‹ Vorgang › | an: gefüllt `primary`, aus: Kontur |
| Lücken | Linie bricht ab, Band schraffiert (45°, 6 dp), isolierte Punkte | Bruch + Schraffur | – |
| „Nur beim Laden“ | Entladephasen schraffiert zwischen Ankerpunkten, hohler Startpunkt | Kurve startet hohl, „ab 47 → 81 %“ | nur Ø-Balken „zwischen Ladevorgängen“ |

- **Benachrichtigung:**
  - Monochrome Small Icons `ic_stat_battery`, `ic_stat_charging` und `ic_stat_target`.
  - `setColor` auf `system_accent1_600` (API 31+), sonst auf den Seed. Nie `setColorized`.
  - Der Fortschritt ist immer bestimmt.
- **App-Icon:** senkrechte Kapsel, zu ⅔ gefüllt, ohne Blitz. Adaptiv mit Monochrom-Ebene, Splash ohne weißen Blitz (DayNight).

---

## Risiken & Robustheit

### Phase-0-Spike: Logger-APK ohne UI, vor dem UI-Bau
| ID | Prüfung | Kriterium |
|---|---|---|
| S1 | Messwerte, 48 h, 5 Szenarien (Schnelllader, PC-USB, Laden beim Spielen, kabellos, Idle) | Einheit, Vorzeichen und Aktualisierungsrate von CURRENT_NOW sind bekannt; ∫I·dt vs. ΔCHARGE_COUNTER |
| S2 | Startlatenz im Modus „Nur beim Laden“, ≥ 10 Einsteckvorgänge bei 15/50/85/92 % | Median > 30 min oder > 20 % ohne Start → Modus gilt als „experimentell“ |
| S3 | Energie, 24 h im Modus „Immer aktiv“ | Mess-Gate-Tabelle oben |
| S4 | Startwege BOOT/UPDATE/Job → FGS auf Android 14–16, 72 h gegen OEM-Kills | Dienst überlebt bzw. Ausfall wird erkannt |
| S5 | Kanalwechsel per Re-Post an der FGS-Benachrichtigung (Alarm-Ton) | Ton kommt, nie zwei Benachrichtigungen |

### Plausibilität (bei Verstoß: Qualitäts-Bit setzen, Wert als n/v)
| Größe | Regel |
|---|---|
| Ladestand | 0 ≤ level ≤ scale |
| Spannung | 3,0–4,6 V (1 Zelle), 6,0–9,2 V (2 seriell) |
| Temperatur | −20…+70 °C |
| Strom | 0 oder `MIN_VALUE` → n/v; \|I\| ≤ 15 A |
| Leistung | \|P\| ≤ 150 W |
| Sprünge | > 6 %/min (Laden) bzw. > 2 %/min (Entladen) → Segmentbruch |
| Zeit | Δelapsed ≤ 0, neuer Boot oder UTC-Sprung > 2 min → Segmentbruch, keine Rate über die Stelle |
| Konsistenz | Abweichung ∫I·dt vs. ΔCHARGE_COUNTER > 30 % über 10 min → Profil „unsicher“ |

**Geräteprofil:**
- **Schlüssel:** Modell + Build-Fingerprint. Nach einem OTA-Update wird neu gelernt.
- **Einheit:** über ΔCHARGE_COUNTER/∫I·dt (≈ 1 → µA, ≈ 1000 → mA).
- **Vorzeichen:** Mehrheitsvotum (≥ 80 %), nur beim Entladen erhoben.
- **Zellen:** Dual-Cell bei einer Spannung über 5,5 V.
- **Bis zur Festlegung:** Watt gilt als „geschätzt“, Graph 2 zeigt „Leistung wird kalibriert“.
- **Manueller Override:** wirkt rückwirkend.

**Health:** Angezeigt werden nur der Health-Status von Android (Gut/Überhitzt/…) und eine gekennzeichnete Kapazitätsschätzung („ca. 4.410 mAh“). Einen echten SoH-Prozentwert gibt es nicht. Zyklen erscheinen nur ab API 34 und wenn der Wert nicht −1 ist.

**Neustart, Kill, Uhrzeit, Datenbank:**
- Jede Zeile speichert UTC, elapsed, Boot und tz-Offset.
- Eine offene Sitzung wird nur fortgesetzt bei gleichem Boot, einer Lücke unter 5 min und weiterhin eingestecktem Gerät.
- `quick_check` läuft beim Start. Bei Korruption wird die Datei als `.corrupt` beiseitegelegt, neu angelegt und ein Banner gezeigt. Vor jeder Migration wird eine Kopie angelegt.

### Top-Edge-Cases
| Fall | Verhalten |
|---|---|
| CURRENT_NOW dauerhaft 0/MIN | W und mA n/v; ETA nur aus der %-Steigung, „geschätzt“ |
| Eingesteckt, netto Entladung | „Entlädt trotz Ladegerät (−x W)“, keine ETA |
| Hersteller-Limit / adaptive Pause | „Ladepause“, keine ETA, Ziel „nicht erreichbar“ |
| Einstecken bei ≥ Ziel | einmalig „Ziel bereits erreicht“, kein Alarm |
| Wackelkontakt / kabellos < 2 min | bleibt eine Sitzung, kein neues Popup |
| 79↔80-Flattern | ein Alarm pro Sitzung, 2 % Hysterese |
| Dienst tot, Ziel überschritten | beim Neustart einmalig „Ziel während Ausfall überschritten (x %)“ |
| Uhr, Sommerzeit, Zeitzone | Raten unverändert, Tagesgrenzen lokal |
| Moduswechsel beim Laden | offene Sitzung wird übernommen, nie doppelt |
| OEM-Rekalibrierung (Sprung) | Segmentbruch, keine Rate über den Sprung |

---

## Qualitätsstrategie
- **Golden-Fixtures** (`spec/engine/fixtures/*.json`): Sample-Folge (Rohwerte) + Profil → erwartete normalisierte Werte, Events, ETA ± Toleranz und das Profil danach. Quellen sind die Spike-Mitschnitte und die Edge-Cases von Skeptic.
- **Engine-Logik:** JUnit (Kotlin) prüft gegen die Fixtures.
- **SQL:** Der Migrator legt eine In-Memory-DB an, `sample_n` muss `expect.normalized` liefern. Das läuft als JVM-Test und einmal auf einem API-24-Emulator. Die `q/*.sql`-Abfragen der UI werden zusätzlich per `sqflite_common_ffi` auf dem Rechner getestet.
- **Live == View:** Der Kotlin-Normalizer muss dieselben Werte liefern wie die View.
- **Widget-Tests** mit `FakeBackend` für Leer-, Lücken- und Fehlerzustände sowie die A11y-Checkliste.
- **Plattform:** Getestet wird nur auf Android. Windows und Linux werden nicht gebaut und nicht getestet.
- **Mess-Gate vor jedem Release:** Keine Änderung wird ohne bestandenes Gate übernommen.

**Akzeptanzkriterien (übernommen von Skeptic):**
- **AK1:** Vorzeichen in 5/5 Szenarien korrekt. Die Leistung liegt über 10 min innerhalb ±20 % der ΔCHARGE_COUNTER-Referenz.
- **AK2:** Bei fehlender Quelle nie „0 W“ oder „0 mA“.
- **AK3:** Die Gates aus S3 sind bestanden.
- **AK4:** Im Modus „Immer aktiv“ erscheint das Popup ≤ 5 s nach dem Einstecken, mit genau einem Heads-up. Danach höchstens 1 Update pro 10 s, und nur wenn sich der gerundete Wert ändert.
- **AK5:** 20 Testladungen ohne verpassten oder doppelten Ziel-Alarm, auch mit Kill oder Reboot dazwischen.
- **AK6:** Nach 3 Lernsitzungen liegt die ETA bei halber Restzeit in ≥ 80 % der Sitzungen innerhalb von max(±20 %, ±5 min).
- **AK7:** Reboot, Update, Force-Stop, Uhr ±1 h, Sommerzeitwechsel: keine negativen oder unendlichen Raten, keine Doppelsitzung, Lücken markiert.
- **AK8:** „Nur beim Laden“ zeigt die gemessene Median-Latenz und verspricht nie „sofort“ oder „lückenlos“.

---

## Phasenplan
| Phase | Inhalt | Gate |
|---|---|---|
| 0 | applicationId, Keystore, targetSdk 36, Kanäle, Icons; `spec/db/001_init.sql` + Views; `spec/engine/spec.md` + Fixtures; Logger-APK (S1–S5) | Spike-Ergebnisse dokumentiert, Fixtures aus echten Mitschnitten |
| 1 | Android „Immer aktiv“: Kotlin-Engine, DB, Service, Boot, Provider, Benachrichtigung, Bridge; danach Flutter-UI (Theme, Übersicht, 3 Charts, Ladeziel-Sheet, Einstellungen, Onboarding) | Mess-Gate Android, AK1–AK7 |
| 2 | Android „Nur beim Laden“: ChargeJobService, Watchdog, Flow für die Akku-Ausnahme, Latenzanzeige, Chart-Varianten | S2 bestanden, sonst Kennzeichnung „experimentell“; AK8 |
| später | Desktop (Linux, dann Windows) – nicht im aktuellen Umfang | – |

---

## Tension Resolutions
| Spannungsfeld | Agenten | Lösung | Begründung |
|---|---|---|---|
| Datengrenze: Binär-Journal + Dart-eigene DB vs. gemeinsame SQLite | Architect ↔ Pathfinder | Eine WAL-SQLite. Einziger Schreiber ist `:monitor` bzw. die Desktop-Engine. Die UI liest read-only über `sqflite`. Rohwerte + Views. | Mit einem separaten `:monitor`-Prozess und `sqflite` (Framework-SQLite) gibt es nur eine SQLite-Bibliothek, das Korruptionsrisiko entfällt. Die Engine braucht ihre Historie (Ladeprofil, Wiederaufnahme), also besitzt der Produzent die DB. |
| Desktop-Host, Phasing, Energiebudget | Tuner ↔ Skeptic | Budget 5/25 mAh über Zähler (gilt). Desktop-Teil (Dart-Engine im verborgenen Flutter-Prozess, kein C++-Helper) ist **nach der Nutzerentscheidung „nur Android“ verschoben**. | Eine dritte Implementierung spart ~0 mW. 0,1 % lassen sich nicht direkt messen, Zähler-Gates prüfen strenger als 1 %/Tag. |
| Was liefert der Modus ohne Dauerdienst wirklich? | Pathfinder ↔ Skeptic | „Nur beim Laden“: Job (`requiresCharging`) + Akku-Ausnahme → FGS nur während des Ladens. Startlatenz ~15–20 min (Spike S2), Durchschnitts-Verbrauch ohne Bildschirm-Split. | `ACTION_POWER_CONNECTED` ist seit API 26 im Manifest tot. FGS-Start aus einem Job ist nur mit der Ausnahme erlaubt. SCREEN_ON/OFF ist nur dynamisch empfangbar. Ehrlichkeit vor falschem Versprechen. |

## Decision Log
| Entscheidung | Optionen | Gewählt | Begründung (Agent) |
|---|---|---|---|
| Hintergrund-Laufzeit Android | Flutter-Headless-Engine · native Kotlin | Kotlin in `:monitor`, nie `libflutter.so` | 25–50 MB PSS und Kaltstarts der Engine verletzen das Budget (Tuner, Pathfinder, Architect) |
| FGS-Typ | `dataSync` · `specialUse` | `specialUse` | `dataSync` ist ab Android 15 auf 6 h/24 h begrenzt und darf nicht aus `BOOT_COMPLETED` starten (Pathfinder, Skeptic) |
| Sampling Entladen | Polling · ereignisgetrieben | nur BATTERY_CHANGED + SCREEN_ON/OFF | Das Aufwachen kostet den Strom, nicht die Rechenzeit (Tuner) |
| Lade-Tick bei Bildschirm an | 5 s (Pathfinder) · 10 s (Tuner) | **10 s** | Das Budget gehört Tuner, die Benachrichtigung aktualisiert ohnehin höchstens alle 10 s (Conductor) |
| Notification-Update-Takt | 5 s (Skeptic AK4) · 10 s · 15 s (Advocate) | **≤ 1×/10 s nur bei geändertem Rundungswert**, bei Bildschirm aus 0 | Kosten in SystemUI; beim Einschalten sofort aktuell (Tuner, Advocate) |
| Datenhaltung | drift · Journal · Room · sqflite+SQL-Dateien | Schema als SQL-Dateien, Kotlin schreibt, `sqflite` liest | eine Schemaquelle, eine SQLite-Bibliothek (Architect, Pathfinder) |
| Normalisierung | beim Schreiben · beim Lesen | beim Lesen per View + `device_profile` | Korrekturen wirken rückwirkend (Skeptic, Architect) |
| Retention | Rohdaten + 5-min-Aggregat (Tuner) · Rohdaten 30 Tage + Sessions/Tage dauerhaft (Architect) | **Architect-Variante, kein 5-min-Aggregat in v1** | Kein Graph braucht mehr als 7 Tage Feinauflösung (Conductor) |
| Charts | fl_chart · CustomPainter | CustomPainter | Kontrolle über Lücken und Repaint, keine Animationen, schlank (Artisan, Tuner) |
| Dunkelmodus | M3-Surface · reines Schwarz | M3-Surface | Material You gewählt, die OLED-Ersparnis bei kurzer Nutzung ist gering (Artisan) |
| Benachrichtigungs-IDs | eine ID (Advocate) · ID 1→2 + Alarm ID 3 (Pathfinder) | ID 1 → ID 2 beim Einstecken; Alarm per Re-Post auf ID 2, Fallback ID 3 (Spike S5) | „Nie zwei gleichzeitig“ hat Vorrang, die Machbarkeit wird geprüft (Conductor) |
| Provider-Authority / Methoden | `.engine` · `.monitor` | `${applicationId}.monitor`; Methoden nach Architect | Konsistenz mit dem Prozessnamen (Conductor) |
| Modusnamen | „Immer bereit“ · „Immer aktiv“ | „Immer aktiv“ / „Nur beim Laden“ | Advocate-Wortlaut |
| Hitzewarnung | 40 °C (Advocate) · 45 °C (Skeptic) | Standard 45 °C, Hysterese 3 °C, einstellbar 40–50 °C | Schnellladen erreicht oft 40 °C, das würde Fehlalarme auslösen (Conductor) |
| Health-Anzeige | SoH-% · Enum + Schätzung | Enum + „ca.“-Kapazität, Zyklen ab API 34 | SoH ist eine System-API (Skeptic) |
| **Plattform-Umfang** | Android + Windows + Linux · nur Android | **vorerst nur Android**, kein Build und kein Test für Desktop | Nutzerentscheidung nach der Deliberation (2026-11-06) |
| Desktop-Host (später) | C++-Helper · Dart im Flutter-Prozess · Daemon | Dart im verborgenen Flutter-Prozess | eine Implementierung weniger (Tuner, Architect); verschoben |
| Phasing | alle Plattformen parallel · sequenziell | 0 → Android „Immer aktiv“ → Android „Nur beim Laden“ | Scope-Risiko eines Solo-Projekts (Skeptic) |
| Engine-Zwilling in Dart | jetzt · später | später, mit Desktop | ohne Desktop ist ein zweiter Kern reiner Ballast (Conductor) |
| Phase-0-Spike vor UI | sofort UI · erst Spike | erst Spike (S1–S5) | Falsche Zahlen wären der größte Vertrauensbruch (Skeptic) |

## Entscheidungen des Nutzers bei der Freigabe (2026-11-06)
1. **Design freigegeben** (Android-only-Fassung).
2. **applicationId / namespace:** `de.mlampert.batterymanager`.
3. **Spike-Geräte:** nur das Handy des Nutzers. Ausgeglichen wird das über die automatische Profil-Erkennung, den manuellen Override in der Diagnose und die Anzeige „geschätzt“, solange das Profil unbestätigt ist. AK1 und AK3 gelten auf diesem einen Gerät.
