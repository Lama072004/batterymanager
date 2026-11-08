## Pathfinder Final Position — Native Datenquellen, Android-Hintergrundmodell, Desktop-Host

**Überarbeitete Empfehlung:** Auf Android läuft die gesamte Hintergrundlogik in Kotlin im Prozess `:monitor`. Dazu gehören ein FGS vom Typ `specialUse`, ein JobService, ein BootReceiver und ein nicht exportierter ContentProvider. Einziger Schreiber der WAL-SQLite ist `:monitor`. Er speichert Rohwerte plus `device_profile`. Flutter ist reine UI: Sie liest per `sqflite` read-only und spricht über Pigeon → Provider/Messenger mit dem Dienst. Modus (b) „Nur beim Laden“ ist ein dünner Job-Zünder derselben Pipeline. Auf dem Desktop ist der Host eine ereignisgetriebene Dart-Engine im verborgenen Flutter-Prozess (Linux vor Windows).

**Zugeständnisse:**
- Settings laufen über `ContentProvider.call()` statt über Intents, denn in (b) gibt es beim Entladen keinen Empfänger.
- (b)-Latenz: realistisch 15–20 min statt „Sekunden“. Job-Tick-Pfad und `setWindow`-Alarm sind gestrichen, weil die Fakten von Skeptic stimmen.
- Desktop: Das Tray ist optional. Im Akkubetrieb gibt es keinen 60-s-Poll (Tuner).
- minSdk bleibt 24. SQL nutzt nur das SQLite-3.9-Subset (Architect-Regel) und wird per Fixture-Test auf API 24 geprüft. Das ist eine Regel statt einer Fallunterscheidung.

**Non-negotiables:**
1. Im Android-Hintergrund läuft keine Flutter-Engine. `Application.onCreate` prüft den Prozessnamen und initialisiert in `:monitor` nichts. Kein `WAKE_LOCK`, kein `SCHEDULE_EXACT_ALARM`, kein `INTERNET` im Release (Prüfung über das Merged Manifest).
2. Single Writer `:monitor`. Kotlin-Code im UI-Prozess öffnet die DB nie. Strom wird nie blind als µA mit festem Vorzeichen übernommen: Gespeichert wird der Rohwert, normalisiert wird per View. Fehlende Werte werden ausgeblendet, nie als 0 angezeigt.
3. Es gibt genau eine FGS-Notification: Beim Ladebeginn ersetzt ID 2 die ID 1. Keine Custom-RemoteViews, monochromes `ic_stat_battery`, kein Full-Screen-Intent.
4. Fehlt etwas, sieht der Nutzer es: verweigertes `POST_NOTIFICATIONS`, entzogene Akku-Ausnahme oder `ForegroundServiceStartNotAllowedException` führen zu einem Banner und dem Kanal `status`.
5. Eigene `applicationId` und Release-Keystore stehen fest, bevor die App zum ersten Mal installiert wird.

**Implementierungshinweise**

*Manifest (`android/app/src/main`):*
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>
<service android:name=".monitor.BatteryMonitorService" android:process=":monitor"
    android:foregroundServiceType="specialUse" android:exported="false">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
      android:value="Akku-Lademonitor mit Live-Ladeanzeige"/>
</service>
<service android:name=".monitor.ChargeJobService" android:process=":monitor"
    android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false"/>
<receiver android:name=".monitor.BootReceiver" android:process=":monitor" android:exported="false">
  <intent-filter>
    <action android:name="android.intent.action.BOOT_COMPLETED"/>
    <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
  </intent-filter>
</receiver>
<receiver android:name=".monitor.NotificationActionReceiver" android:process=":monitor" android:exported="false"/>
<provider android:name=".monitor.MonitorProvider" android:process=":monitor"
    android:authorities="${applicationId}.monitor" android:exported="false"/>
```
`ACTION_BATTERY_CHANGED` und `SCREEN_ON/OFF` werden nur dynamisch im Dienst registriert. `CURRENT_NOW` wird nur beim Laden per Handler-Loop gelesen: 5 s bei Bildschirm an, 30–60 s bei Bildschirm aus. In (b) startet der Job den FGS über `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`.

*Notification-Kanäle (IDs stabil, Namen deutsch):*

| Kanal | Importance | Notif-ID | Verhalten |
|---|---|---|---|
| `monitor` | MIN | 1 | stumm, nur (a) |
| `charging` | HIGH, ohne Ton | 2 | einmal Heads-up, danach `setOnlyAlertOnce`; Update ≤ 1×/10 s nur bei Δ > 0,3 W / 1 % / 1 min; API 36: `ProgressStyle` + `setRequestPromotedOngoing` + `setShortCriticalText` |
| `target_alarm` | HIGH, `USAGE_ALARM`, Vibration | 3 | „Jetzt abstecken“, Aktion „Stumm“ |
| `heat` | HIGH | 4 | Hitzewarnung |
| `status` | DEFAULT | 5 | Dienst blockiert, Ausnahme fehlt |

*Schnittstelle:*
- **Pigeon** (`pigeons/monitor.dart`, Pigeon ≥ 22):
  - `@HostApi MonitorApi`: `getStatus()`, `putSetting(key, value)`, `setMode(int)`, `setProfileOverride(scale, sign, cells)`, `flush()`, `dbPath()`, `openSystemSettings(String)`.
  - `@EventChannelApi LiveEvents`: `LiveSample live()` mit den Feldern `tsMs, levelPm, watts, voltageMv, tempDc?, status, plug, etaFullS?, etaTargetS?, estimated`.
- **UI-Prozess (Kotlin `MonitorBridge`):** Pigeon-Aufrufe gehen an `contentResolver.call("content://<appId>.monitor", method, arg, extras)`. Methoden: `get_status | put_setting | set_mode | set_profile | flush`.
- **Messenger:** In `onStart` per `bindService(BIND_AUTO_CREATE)` binden, dann `MSG_SUBSCRIBE` mit `replyTo` senden. Der Dienst antwortet mit `MSG_LIVE` (Bundle aus Primitiven, ≤ 1 Hz) an den EventSink. In `onStop` folgen `MSG_UNSUBSCRIBE` und `unbind`. In (b) entsteht der Dienst beim Entladen nur gebunden, ohne `startForeground`.

*Desktop-Datenquellen:*
- **Linux:**
  - **UPower per `dbus`:** `EnumerateDevices`, gefiltert auf `Type=Battery` und `PowerSupply=true`. Gelesen werden `Percentage`, `EnergyRate` (ohne Vorzeichen, deshalb über `State`), `Voltage`, `Energy*`, `Temperature`, `ChargeCycles` und `TimeToFull/Empty`. Einziger Trigger ist `PropertiesChanged`.
  - **sysfs-Fallback:** `/sys/class/power_supply/*` mit `type=Battery` und `scope≠Device`. Liest `charge_control_end_threshold` read-only als effektives Ladeziel.
  - **Flush und Bildschirm:** logind `PrepareForSleep/Shutdown` löst den Flush aus. `ScreenSaver.ActiveChanged` liefert Bildschirm an/aus best effort.
  - **Benachrichtigungen:** `org.freedesktop.Notifications.Notify` mit `replaces_id`.
- **Windows:**
  - **Messwerte über `win32`/`ffi`:** `SetupDiGetClassDevs(GUID_DEVCLASS_BATTERY)` → `IOCTL_BATTERY_QUERY_TAG/INFORMATION/STATUS`. Liefert Kapazitäten in mWh, Rate in mW, Spannung in mV und `CycleCount`.
  - **Ereignisse:** `PowerSettingRegisterNotification(DEVICE_NOTIFY_CALLBACK)` für `GUID_ACDC_POWER_SOURCE`, `GUID_BATTERY_PERCENTAGE_REMAINING` und `GUID_CONSOLE_DISPLAY_STATE`, dazu `PowerRegisterSuspendResumeNotification`. Callbacks laufen über `NativeCallable.listener`, ohne C++-Helper. Gegebenenfalls eine eigene `powrprof.dll`-Bindung.
  - **Herunterfahren:** `WM_QUERYENDSESSION` über einen Hook in `flutter_window.cpp` → Flush.

*Pakete:*
- **Nutzen:**
  - `sqflite` (Android), `sqflite_common_ffi` (nur Desktop, Plattform-Weiche), `dynamic_color`, `pigeon` (dev)
  - Desktop: `dbus`, `win32`, `ffi`, `window_manager`, `tray_manager` (Linux: `libayatana-appindicator3`), `launch_at_startup`, `local_notifier` (Windows-Toast, AUMID beim Erststart)
- **Nicht nutzen:** `battery_plus`, `workmanager`, `flutter_foreground_task`, `flutter_local_notifications`, `drift`
- **APK prüfen:** Ein mitgebündeltes `libsqlite3.so` darf auf Android nie geladen werden.

*Reihenfolge:*
- **Phase 0:**
  - `applicationId`, Keystore, targetSdk 36, `ic_stat_battery`.
  - `spec/db/001_init.sql` mit Views und Golden-Fixtures.
  - Spikes: P0 batterystats (24 h) und Latenz Einstecken → Job.
- **Phase 1, Android (a):**
  - Kotlin-Komponenten: `BatteryDb`, `Sampler`, `DeviceProfile`-Heuristik, `SessionTracker`, `EtaEstimator`, `NotificationController`, Service, Boot, Provider, Bridge.
  - Danach die UI mit Berechtigungsflow und 3 CustomPainter-Graphen.
  - Gate: ≤ 25 mAh/24 h.
- **Phase 2, Android (b):** `ChargeJobService` mit `requiresCharging` und `setPersisted`, Watchdog-Job, Flow für die Akku-Ausnahme, Anzeige der gemessenen Latenz.
- **Phase 3, Linux:** UPower, D-Bus-Benachrichtigungen, Autostart verborgen, Gate 30 mW.
- **Phase 4, Windows:** IOCTL, Power-Callbacks, Toast, Tray.
