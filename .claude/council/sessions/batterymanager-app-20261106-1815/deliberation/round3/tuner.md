## Tuner Final Position — Energiebudget, Sampling & Mess-Gate

**Überarbeitete Empfehlung:** Der Android-Hintergrund läuft nativ in Kotlin im Prozess `:monitor`, ohne Flutter-Engine. Beim Entladen arbeitet er rein ereignisgetrieben (BATTERY_CHANGED, SCREEN_ON/OFF) und hat keine eigene Weckquelle. Beim Laden zahlt das Netzteil: Nur dann laufen Ticks, Notification-Updates und die gesamte DB-Wartung. Auf dem Desktop läuft die Dart-Engine im versteckten Flutter-Fenster, im Akkubetrieb ebenfalls ohne Polling. Abgenommen wird über ein Mess-Gate mit exakten Zählern, einmal als P0-Spike vor dem UI-Bau und danach vor jedem Release.

**Zugeständnisse:**
- **C++-Helper gestrichen.** Eine dritte Implementierung lohnt sich nicht für eine Ersparnis von ~0 mW. Fallback ist ein headless `dart compile exe`.
- **Harte Grenze von 0,2 % auf 25 mAh/24 h (0,5 %) gelockert.** Die batterystats-Schätzung irrt sich um Faktor 2–3 (OEM-`power_profile.xml`), und 0,1 % lassen sich nicht direkt messen. Das Gate stützt sich deshalb auf Zähler.
- **Flush statt alle 15 min jetzt pro Ereignis (Entladen) bzw. alle 60 s (Laden).** Die CPU ist beim Ereignis ohnehin wach, ein Commit kostet < 0,5 mAh/Tag. Datenverlust bei einem Kill wiegt schwerer.
- **Desktop-RAM von 80–150 MB akzeptiert.** Belegter RAM kostet keine Ladung.
- **Modus (b) mit 15–20 min Startlatenz akzeptiert.** Diese Lücke wird nicht durch Polling ausgeglichen.

**Non-negotiables:**
1. **Beim Entladen 0 eigene Weckquellen:** kein AlarmManager, kein eigener WakeLock, kein periodischer Job, kein Handler- oder Timer-Tick. Ziel-% und Hitze werden nur im Ereignis-Callback geprüft.
2. **Keine Flutter-Engine im Android-Hintergrund.** `flutter_background_service`, `workmanager` und gecachte Engines sind ausgeschlossen, `libflutter.so` wird in `:monitor` nie geladen.
3. **Notification bei ausgeschaltetem Bildschirm: 0 Updates.** Kein Chronometer, die Mini-Benachrichtigung bleibt beim Entladen statisch.
4. **Wartung nur beim Laden:** Downsampling, Retention, Checkpoint und das Lernen der Ladekurve.
5. **Kein Merge ohne bestandenes Mess-Gate** auf einem Release-Build.

**Implementierungshinweise**

| Zustand | Sampling | Notification | Flush |
|---|---|---|---|
| Laden, Bildschirm an | BATTERY_CHANGED + Handler-Tick alle **10 s** (`getIntProperty`); W = I × U, EMA α = 0,3 | max. 1×/10 s, nur bei ΔW ≥ 0,5 W, Δ% ≥ 1 oder ΔETA ≥ 1 min | Batch alle 60 s |
| Laden, Bildschirm aus | BATTERY_CHANGED (healthd ~60 s) + Handler alle **30 s** (`uptimeMillis`, weckt nicht) | **0**; bei SCREEN_ON sofort 1 Update | Batch alle 60 s |
| Entladen, Bildschirm an/aus | **nur** BATTERY_CHANGED (~10 min + je 1 %) und SCREEN_ON/OFF; je Ereignis CHARGE_COUNTER, CURRENT_NOW, Temperatur | nie | pro Ereignis (`goAsync`), Salven gebündelt |
| Modus (b) | wie Laden; FGS endet beim Abstecken | wie Laden | Sofort-Flush, dann `stopSelf` |
| Desktop, Akku | UPower `PropertiesChanged` bzw. `WM_POWERBROADCAST`, **kein Poll** | nur bei Zustandswechsel | alle 5 min |
| Desktop, Netz | Poll alle 30 s (sysfs `power_now` bzw. `IOCTL_BATTERY_QUERY_STATUS`) | max. 1×/30 s | alle 60 s |

- **Sofort-Flush immer bei:** Ein- und Abstecken, Ziel erreicht, voll, `onTrimMemory`, `PBT_APMSUSPEND`, logind `PrepareForSleep` und `WM_QUERYENDSESSION`.
- **Retention:** Rohdaten 30 Tage, danach 5-min-Aggregate (min/max/avg). Pro Ladevorgang bleibt 1 Zeile dauerhaft.
- **Wartung:** höchstens 1× in 24 h, nur beim Laden (Android bevorzugt bei ausgeschaltetem Bildschirm). Sie läuft in einer Transaktion, danach folgt `PRAGMA wal_checkpoint(TRUNCATE)`. WAL mit `synchronous=NORMAL`.
- **UI:** Live-Updates nur in `resumed`, beim Laden ≤ 1 Hz, beim Entladen ≤ 0,2 Hz, sonst 0 fps. Keine Animationen. Charts zeichnen nur bei neuer Datenversion neu, mit ≤ 2 Punkten pro Pixel.

**Mess-Gate Android (Modus a, Release-Build)**
1. `adb shell dumpsys batterystats --enable full-wake-history`
2. `adb shell dumpsys batterystats --reset`, dann USB trennen (sonst gibt es kein Entlade-Accounting).
3. 24 h Alltagsbetrieb, davon ≥ 6 h Nacht im Doze.
4. `adb shell dumpsys batterystats --charged com.example.batterymanager > bs.txt` liefert UID-mAh, CPU-Zeit (u/s), Wakelocks, Wakeup-Alarme und Prozess-Starts.
5. `adb shell dumpsys alarm | grep batterymanager` und `adb shell dumpsys jobscheduler | grep batterymanager` müssen leer sein.
6. `adb bugreport br.zip` im Battery Historian auswerten: keine Wakeup-Reasons und keine Wakelocks unserer UID.
7. Beim Laden: `adb shell "cat /proc/\$(pidof com.example.batterymanager:monitor)/stat"` bei t0 und t0 + 1 h ausführen und die Felder 14 + 15 vergleichen.
8. `adb shell dumpsys meminfo com.example.batterymanager:monitor` prüfen. `grep libflutter /proc/<pid>/maps` muss leer sein.

| Kriterium | Ziel | Grenze |
|---|---|---|
| UID-Schätzung, Entladen | ≤ 5 mAh/24 h | 25 mAh |
| Eigene Wakeup-Alarme / WakeLocks / Jobs | 0 | 0 |
| CPU-Zeit, Entladen | ≤ 24 s/24 h | 48 s |
| Neustarts von `:monitor` | 0 | 1 |
| CPU-Zeit, Laden | ≤ 15 s/h | 30 s/h |
| PSS `:monitor` | ≤ 25 MB | 40 MB |

Plausibilitätsprüfung: 24 s CPU × ~150 mA ≈ 1 mAh. Selbst bei einem Schätzfehler von Faktor 3 bleibt das unter 5 mAh. Die Zähler belegen also das Budget.

**Mess-Gate Desktop** (Akkubetrieb, Fenster verborgen, feste Helligkeit, A/B je 1 h mit und ohne App)
- **Linux:** `sudo powertop --csv=pt.csv --time=3600`, `pidstat -u -w -p $(pidof batterymanager) 60 60` und der Mittelwert aus `/sys/class/power_supply/BAT0/power_now`.
- **Windows:** `powercfg /srumutil /output srum.csv`, `powercfg /sleepstudy` und `powercfg /energy /duration 60`.
- **Akzeptanz:**
  - Mehrverbrauch Δ ≤ 10 mW (Grenze 30 mW)
  - ≤ 0,05 Wakeups/s und ≤ 2 CPU-s/h
  - keine Änderung der Timer-Auflösung
  - nicht Top-Aktivator in der Sleep Study

**Release-Build-Hinweise**
- Nur Release-Builds messen (`flutter build apk --release --split-per-abi`, auf dem Desktop `--release`). Debug-Builds verfälschen die CPU-Zeit um Faktor 2–10.
- R8 aktivieren und `Log.d`/`Log.v` per `-assumenosideeffects` entfernen. Kein StrictMode, kein `debuggable`.
- Während der Messung die Entwickleroption „Wach bleiben“ ausschalten, USB trennen und Wireless-Debugging ausschalten.
- `cupertino_icons` entfernen, Impeller bleibt Standard.
- Die Einstellungszeile „Eigenverbrauch“ (CPU-Zeit, Flush- und Update-Zähler) bleibt im Release, damit das Budget im Alltag überprüfbar bleibt.
