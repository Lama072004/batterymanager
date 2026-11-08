## Pathfinder Position — Native Akku-Datenquellen, Android-Hintergrundmodell, Live-Benachrichtigung

**Kernempfehlung:** Die gesamte Hintergrundlogik läuft nativ: auf Android als Kotlin-Service/Receiver/JobService im eigenen Prozess `:monitor`, ohne Flutter-Engine; auf dem Desktop als schlanker Dart-Kern im Tray-Prozess. Flutter ist reine UI (Pigeon + gemeinsame SQLite-Datei). Gesampelt wird ereignisgetrieben; `CURRENT_NOW` wird nur während des Ladens gepollt.

**Hauptargument:**
`battery_plus` kennt nur Prozent und Ladezustand, nicht Strom, Spannung, Temperatur oder Zyklen. `workmanager` und `flutter_foreground_task` starten im Hintergrund je eine FlutterEngine (~30–60 MB RAM, VM-Start pro Aufwachen). Das widerspricht der Strom-Priorität.

Android liefert alle Werte über öffentliche APIs (`BatteryManager`-Properties, Sticky-Extras). Der Spar-Hebel liegt in der Plattform:
- Beim Laden gibt es kein Doze; Polling und Notification-Updates kosten dort praktisch nichts.
- Beim Entladen wird nicht gepollt, nur auf ohnehin gesendete Broadcasts reagiert.
- Der Ziel-%-Alarm hängt am Übergang von `EXTRA_LEVEL`, nicht an einem Timer: kein Wakelock, keine Exact-Alarm-Berechtigung.

Als FGS-Typ passt nur `specialUse`. `dataSync` ist ab Android 15 auf 6 h pro 24 h begrenzt und darf nicht aus `BOOT_COMPLETED` starten; `specialUse` hat beides nicht, und die Play-Begründung entfällt beim Sideload.

**Risiken, wenn ignoriert:**
- **`POST_NOTIFICATIONS` (ab Android 13) wird verweigert:** Der Dienst läuft weiter, aber Lade-„Popup“ und Alarm bleiben unsichtbar. Das Kernfeature fällt aus, ohne dass der Nutzer es merkt.
- **`CURRENT_NOW` wird blind als µA mit festem Vorzeichen übernommen:** Watt-Werte sind dann um Faktor 1000 falsch, oder Laden erscheint negativ (Samsung/Huawei/ältere Geräte). Außerdem ist der Wert der Netto-Strom in den Akku (Ladegerät minus Systemlast), nicht die Netzteil-Leistung.
- **Template-Defaults bleiben stehen (`com.example.batterymanager`, Debug-Signing):** Ändert man ID oder Schlüssel später, entsteht eine neue App. Der Verlauf geht verloren, Sideload-Updates schlagen fehl.

**Abhängigkeiten zu anderen Council-Mitgliedern:**
- **Tuner:** Intervalle bestätigen (A6) und messen, ob ein eigener `:monitor`-Prozess weniger kostet als ein gemeinsamer. Takt der Notification-Updates festlegen.
- **Architect:** Single-Writer-Vertrag (nur der Service schreibt, WAL, UI liest). Settings per Intent an den Service, nicht über SharedPreferences (unsicher über Prozessgrenzen). Restzeit-Algorithmus einmal spezifizieren, Kotlin und Dart mit denselben Testvektoren.
- **Artisan:** Monochromes Statusleisten-Icon. Notification ohne Custom-RemoteViews, sonst keine Android-16-Promotion. Desktop-Akzentfarbe über `dynamic_color`.
- **Advocate:** Reihenfolge der Berechtigungsabfragen. Beschriftung „Leistung in den Akku“. OEM-Hinweise. Klar sagen, dass Modus (b) Bildschirm an/aus nur grob trennt.
- **Skeptic:** OEM-Killer (Xiaomi/Samsung/Huawei), Plausibilitätsfilter für Messwerte, GNOME ohne Tray.

---

## Anhang A — Device Integration Plan

### A1 Datenquellen-Matrix
| Wert | Android | Windows | Linux |
|---|---|---|---|
| Ladestand | `EXTRA_LEVEL/EXTRA_SCALE` | `BATTERY_STATUS.Capacity` (mWh) | UPower `Percentage`, sysfs `capacity` |
| Strom/Leistung | `BATTERY_PROPERTY_CURRENT_NOW` (µA, + = Laden, Herstellerabweichung möglich), `CURRENT_AVERAGE` (oft `Integer.MIN_VALUE`) | `BATTERY_STATUS.Rate` (mW, mit Vorzeichen, `BATTERY_UNKNOWN_RATE`) | UPower `EnergyRate` (W), `power_now` (µW) bzw. `current_now` (µA, oft ohne Vorzeichen, `status` nutzen) |
| Spannung | `EXTRA_VOLTAGE` (mV) | `BATTERY_STATUS.Voltage` (mV) | UPower `Voltage` (V), `voltage_now` (µV) |
| Temperatur | `EXTRA_TEMPERATURE` (0,1 °C) | `BatteryTemperature` (0,1 K, selten unterstützt) | `temp` (0,1 °C, selten) |
| Kapazität/Health | `CHARGE_COUNTER` (µAh) → Vollkapazität schätzen; `EXTRA_HEALTH` nur Enum; `ENERGY_COUNTER` (nWh, `getLongProperty`, selten). `STATE_OF_HEALTH` ist SystemApi, also nicht nutzbar | `BATTERY_INFORMATION.DesignedCapacity/FullChargedCapacity` (mWh), damit echtes SoH | `energy_full(_design)` (µWh) bzw. `charge_*` (µAh), UPower `Capacity` (%) |
| Zyklen | `EXTRA_CYCLE_COUNT` (ab API 34, sonst fehlt/−1) | `BATTERY_INFORMATION.CycleCount` (oft 0) | `cycle_count` (0 = unbekannt), UPower `ChargeCycles` |
| Restzeit | `computeChargeTimeRemaining()` (API 28, ms, −1; nur bis 100 %) | `GetSystemPowerStatus().BatteryLifeTime` (s, nur Entladen) | UPower `TimeToFull/TimeToEmpty` (s) |
| Quelle | `EXTRA_PLUGGED` (AC/USB/WIRELESS/DOCK) | `ACLineStatus` | `AC*/online`, `ucsi-source-psy-*` |
| Ereignisse | dynamischer Receiver: `ACTION_BATTERY_CHANGED`, `ACTION_SCREEN_ON/OFF` | `RegisterPowerSettingNotification` (`GUID_ACDC_POWER_SOURCE`, `GUID_BATTERY_PERCENTAGE_REMAINING`, `GUID_CONSOLE_DISPLAY_STATE`) → `WM_POWERBROADCAST`; `IOCTL_BATTERY_QUERY_STATUS` mit `BATTERY_WAIT_STATUS.Timeout` blockiert bis zur Änderung | UPower `PropertiesChanged` (UPower pollt selbst, also keine zusätzlichen Wakeups); Bildschirm über logind/`org.freedesktop.ScreenSaver` nur best effort |

- **Watt (Android):** `P[W] = |I[µA]| × U[mV] / 10⁹`.
- **Einheiten-Heuristik:** |I| < 20 000 beim Laden heißt mA. Vorzeichen gegen `EXTRA_STATUS` kalibrieren. Manueller Override in den Einstellungen.
- **Undokumentierte Extras:** `max_charging_current`/`max_charging_voltage` (µA/µV), best effort für „Ladegerät max. W“.
- **Android-sysfs:** per SELinux gesperrt, nicht einplanen.
- **Windows:** `SetupDiGetClassDevs(GUID_DEVCLASS_BATTERY, DIGCF_DEVICEINTERFACE)` → `CreateFile` → `IOCTL_BATTERY_QUERY_TAG`, ohne Admin. Aufruf per Dart-FFI (`package:win32`) in einer eigenen Isolate. WMI `root\wmi BatteryStatus` (ChargeRate/DischargeRate, mW) nur als Fallback (COM-Overhead).
- **Linux:** nur Einträge mit `type=Battery` und `scope!=Device` (sonst werden Maus- oder Tastatur-Akkus mitgezählt).

### A2 Android-Hintergrundmodell (laut Flutter 3.35: minSdk 24, targetSdk 36)

**(a) Dauerdienst**
- `BatteryMonitorService` mit `specialUse`, Start über `ServiceCompat.startForeground(…, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`.
- Neustart über `BootReceiver` auf `BOOT_COMPLETED` und `MY_PACKAGE_REPLACED`. `specialUse` darf auch unter Android 15 aus dem Boot starten.
- Kein Wakelock.
- Beim Laden ersetzt die Lade-Notification per `startForeground(ID_CHARGE, …)` die stumme Mini-Notification. Es gibt also nie zwei Einträge.
- Ab Android 14 kann der Nutzer die FGS-Notification wegwischen; der Dienst läuft trotzdem weiter.

**(b) Ohne Dauerdienst**
- Ein Manifest-Receiver geht nicht: `ACTION_POWER_CONNECTED` ist seit API 26 als impliziter Broadcast gesperrt.
- Realistisch ist `JobScheduler` direkt, nicht WorkManager (das zieht `WAKE_LOCK`, `ACCESS_NETWORK_STATE` und eigene Receiver ins Manifest). Job mit `setRequiresCharging(true)` und `setPersisted(true)`.
- Der Job misst, aktualisiert die Notification und plant den Folgejob (`setMinimumLatency(~5 min)` + Lade-Bedingung).
- Günstig: Beim Laden setzt der QuotaController die Standby-Kontingente aus (außer Bucket „restricted“), Doze ist inaktiv. Realistisch: Takt 5–15 min, erster Job Sekunden bis wenige Minuten nach dem Einstecken.
- Ziel-%: inexakter `AlarmManager.setWindow(ELAPSED_REALTIME_WAKEUP)` zur geschätzten Ankunftszeit; Receiver prüft den Level.
- Entladung nur grob (periodischer Job ab 15 min), ohne Bildschirm-Split.
- Optional bei abgeschalteter Akku-Optimierung: Job startet einen FGS nur für die Ladesitzung.

### A3 Kanäle und Live-Update
| Kanal | Importance | Zweck |
|---|---|---|
| `monitor` | MIN (System hebt ggf. auf LOW) | stummer Dauerdienst |
| `charging` | HIGH + `setOnlyAlertOnce(true)` | einmal Heads-up, danach stille Updates |
| `target_alarm` | HIGH, Ton `USAGE_ALARM`, Vibration | „Jetzt abstecken“ |
| `heat` | HIGH | Hitzewarnung |

- **Update-Takt:** höchstens alle 5–10 s und nur bei relevanter Änderung (>0,3 W, 1 min Restzeit oder 1 %). Das System erlaubt ca. 5 Updates/s pro App.
- **Notification-Builder:** `setOngoing`, `setProgress(100, level, false)`, `CATEGORY_PROGRESS`, `setShowWhen(false)`.
- **Android 16:** `Notification.ProgressStyle` (Segment bis Ziel-%, Punkt am Ziel), `setRequestPromotedOngoing(true)`, `setShortCriticalText("12 W")` als Chip in der Statusleiste.
  - Voraussetzungen: kein Custom-RemoteViews, nicht colorized, Kanal nicht MIN, `POST_PROMOTED_NOTIFICATIONS`.
  - Vorher `canPostPromotedNotifications()` prüfen, sonst klassische Notification.
- Kein Full-Screen-Intent.

### A4 Permission Map (Manifest)
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>          <!-- runtime, API 33+ -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS"/>  <!-- API 36 -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/> <!-- optional -->
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
```

**Bewusst nicht angefordert:**
- `INTERNET`: steht im Template nur in debug/profile, der Release-Build bleibt netzlos. Nach jedem Plugin-Merge prüfen.
- `BATTERY_STATS` (nur signature), `PACKAGE_USAGE_STATS`, `WAKE_LOCK`, `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`.

**Zeitpunkt der Abfragen:**
- `POST_NOTIFICATIONS` beim ersten Aktivieren der Lade-Benachrichtigung, mit Erklärung vorab.
- Akku-Optimierung erst, wenn der Nutzer Modus (a) wählt oder ein OEM-Kill erkannt wird.

### A5 Fallback-Matrix
| Fall | Verhalten |
|---|---|
| `CURRENT_NOW` liefert `MIN_VALUE` oder 0 | Watt aus ΔCHARGE_COUNTER bzw. Δ% × Kapazität, als „geschätzt“ markieren |
| Zyklen/Temperatur fehlen | Feld ausblenden, nicht „0“ anzeigen |
| Notifications verweigert | Banner in der App + `ACTION_APP_NOTIFICATION_SETTINGS` |
| OEM beendet den FGS | Lücke im Verlauf markieren, Hinweis auf „Akku: Nicht eingeschränkt“ |
| Windows-`Rate` unbekannt | ΔCapacity/Δt |
| GNOME ohne Tray | Autostart ohne Icon, nur Benachrichtigungen, Hinweis auf AppIndicator-Extension |

### A6 Power Impact (Vorschlag an Tuner)
| Zustand | Strategie |
|---|---|
| Laden, Bildschirm an | `CURRENT_NOW` alle 5 s, Notification höchstens alle 10 s |
| Laden, Bildschirm aus | alle 30–60 s (der Handler pausiert im Suspend; der Level-Broadcast weckt ohnehin) |
| Entladen | kein Polling; nur bei `BATTERY_CHANGED` oder Screen-Events lesen, Rate aus ΔCHARGE_COUNTER |
| Desktop | UPower-Signale bzw. `WM_POWERBROADCAST`; Fallback-Poll alle 60 s |

### A7 Flutter- und Desktop-Bausteine
- **Nutzen:** Pigeon (nur Android), `dynamic_color`, `tray_manager` + `window_manager`, `launch_at_startup` (HKCU `…\Run` bzw. `~/.config/autostart/*.desktop`), `dbus`/`upower`, `package:win32`.
- **Nicht nutzen:** `battery_plus`, `workmanager`, `flutter_foreground_task`; `flutter_local_notifications` auf Android (Notification entsteht nativ im Service).
- **Windows:** Toast nur bei Ladebeginn und Ziel-% (unpaketiert: AUMID registrieren); Live-Werte im Tray-Tooltip.
- **Linux:** `org.freedesktop.Notifications.Notify` mit `replaces_id` aktualisiert an Ort und Stelle.

---

## Anhang B — Platform Audit
| Plattform | Pass | Warn | Fail |
|---|---|---|---|
| Android | 2 | 3 | 3 |
| Windows/Linux | 1 | 2 | 0 |

- **[FAIL] Android: applicationId `com.example.*` + Debug-Signing** (`build.gradle.kts`). Fix: eigene ID und Release-Keystore vor der Erstinstallation. Aufwand: niedrig.
- **[FAIL] Android: kein monochromes Small Icon.** Notification-Guideline: Small Icons nur als Alpha-Maske. Fix: Vector `ic_stat_battery`. Aufwand: niedrig.
- **[FAIL] Android 13+: Flow für `POST_NOTIFICATIONS` fehlt.** Fix: Erklärung vorab + Weiterleitung in die Einstellungen. Aufwand: niedrig.
- **[WARN] Android 15/16: Edge-to-Edge ist erzwungen** (targetSdk 36, Opt-out entfällt). Insets/SafeArea prüfen.
- **[WARN] Android 16: Predictive Back ist standardmäßig aktiv.** `PredictiveBackPageTransitionsBuilder` ins Theme.
- **[WARN] Android 14+: FGS-Notification ist wegwischbar.** Die UX darf nicht davon abhängen.
- **[WARN] Desktop: Tray-Konventionen.** Windows: Schließen minimiert in den Tray, mit einmaligem Hinweis. GNOME hat keinen Tray. Der Linux-Build braucht `libayatana-appindicator3`.
- **[WARN] Desktop: Bildschirm an/aus ist unter Wayland unzuverlässig.** Graph 3 dort ggf. ohne Split.
- **Store-Risiko:** keins (Sideload). Beobachten: Googles angekündigte Entwickler-Verifizierung für Sideload-Apps (global ab 2027).

**Phasing:** zuerst Android (beide Modi), danach Windows + Linux gemeinsam als pure Dart, gleiches SQLite-Schema.
