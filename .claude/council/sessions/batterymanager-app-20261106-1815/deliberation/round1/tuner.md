## Tuner Position — Energie-Budget & Hintergrundarchitektur

**Kernempfehlung:** Die gesamte Hintergrundlogik läuft nativ (Kotlin auf Android, kleiner C++-Helper auf Windows/Linux). Sie hängt sich nur an Ereignisse, die das System ohnehin auslöst: keine eigene Weckquelle, keine Flutter-Engine außerhalb der sichtbaren UI. Ziel für den Dauerdienst: **≤ 5 mAh/24 h (≈ 0,1 %/Tag bei 5.000 mAh), harte Grenze 0,2 %/Tag**, per `batterystats` gemessen und nicht geschätzt.

**Hauptargument:**
Strom kostet vor allem das **Aufwachen** des Geräts. Ein Wecken aus dem Suspend kostet ~0,5–1 s bei 100–300 mA. Das ist mehr als tausende Millisekunden Rechenzeit auf einer CPU, die ohnehin wach ist. Android (healthd: ~60 s beim Laden, ~10 min plus jede 1-%-Änderung beim Entladen), Windows (Power-Setting-Notifications) und Linux (UPower) messen den Akku sowieso. Wir lesen nur bei diesen Ereignissen mit. Dann kostet uns jedes Ereignis nur wenige Millisekunden: ~250 Ereignisse/Tag × ~3 ms ≈ **< 1 CPU-Sekunde/Tag**. Zweitens bezahlt **beim Laden das Netzteil**. Dort dürfen wir öfter messen (Watt-Kurve, Restzeit) und auch Wartung erledigen (Downsampling, Lernen der Ladekurve). Beim Entladen gilt **Null-Polling**. Drittens kostet eine Flutter-Engine im Hintergrund (`workmanager`, `flutter_background_service`) grob 25–50 MB PSS und 150–500 ms CPU pro Kaltstart. Sie erhöht das Risiko, dass der Low-Memory-Killer den Prozess beendet und neu startet, und verleitet zu `Timer.periodic`-Polling in Dart. Ein reiner Kotlin-Prozess bleibt bei ~15–25 MB und lädt `libflutter.so` nie. **Flutter zeigt die Daten nur an, es misst nicht selbst.**

**Risiken, wenn ignoriert:**
- **Nutzer sieht die App im eigenen Graphen:** Ein eigener Timer oder Alarm im Entlademodus (z. B. 1/min) erzeugt ~1.440 zusätzliche Wakeups pro Tag. Das sind ≈ 40–70 mAh/Tag, also ~1 % Akku. Damit verfehlt die App ihr Kernversprechen.
- **Last beim Laden und bei offener App:** Ein Notification-Update pro Sekunde zwingt SystemUI jedes Mal, die Benachrichtigung neu aufzubauen (~10–30 ms). Das belastet einen CPU-Kern dauerhaft mit 1–3 %, und Android verwirft ohnehin mehr als 5 Updates/s. Animierte Wertwechsel machen aus einem 1-Hz-Update auf 120-Hz-Displays ~36 Frames/s statt 1.
- **Flash und Restarts:** Eine Transaktion pro Messwert ergibt ~1.000 Commits/Tag und ~10 MB/Tag Flash-Writes statt < 1 MB. Ein großer Hintergrundprozess (mit Engine) wird öfter beendet, und jeder Neustart kostet erneut einen Kaltstart.

**Abhängigkeiten zu anderen Council-Mitgliedern:**
- **Pathfinder:** FGS-Typ ohne Laufzeit-Timeout (z. B. `specialUse`, nicht `dataSync`). Receiver für BATTERY_CHANGED und SCREEN_ON/OFF dynamisch im Service registrieren. Klären, welche OEMs `CHARGE_COUNTER` und `CURRENT_NOW` liefern. Windows-Toast-Update über `NotificationData`. Linux-Notifications mit `replaces_id`.
- **Architect:** Eigener Prozess `:monitor` für den Dienst (nativ, ohne Flutter) und ein getrennter UI-Prozess. Die UI liest die SQLite-DB (WAL) direkt, ohne dauerhaftes IPC. Batch-Flush-API, Aggregat-Tabellen, Zeitstempel als `INTEGER PRIMARY KEY`.
- **Artisan:** Motion-Budget = 0 Dauer-Animationen, keine animierten Zahlenwechsel, statische Charts. Dynamic Color nur einmal beim Start lesen.
- **Advocate:** Akzeptieren, dass die Notification höchstens alle 10 s aktualisiert und bei ausgeschaltetem Bildschirm stehen bleibt. Beim Einschalten ist sie sofort aktuell.
- **Skeptic:** OEM-Fallen (µA vs. mA, Vorzeichen, Akku-Killer). Beim Prozess-Kill gehen maximal die Messwerte seit dem letzten Flush verloren, höchstens 15 min. Ist das akzeptabel?

---

## Anhang: Performance-Audit (Energie) — Batterymanager

### 1. Baseline-Scorecard
Ausgangslage ist das frische Template ohne Hintergrundcode. **Baseline = Gerät ohne App**, und jedes Budget gilt als Zuwachs gegenüber dieser Baseline.

| Metrik | Ziel | Grenze | Messung |
|---|---|---|---|
| Eigenverbrauch Dauerdienst, Entladen | ≤ 5 mAh/24 h | 10 mAh/24 h (0,2 %) | `batterystats --charged <pkg>` |
| Eigene Wakeups / Alarme / Wakelocks | 0 / 0 / 0 | 0 | Battery Historian |
| CPU-Zeit Entladen | ≤ 1 s/h | 2 s/h | batterystats, Perfetto |
| CPU-Zeit Laden | ≤ 15 s/h | 30 s/h | `/proc/<pid>/stat` (batterystats zählt am Netz nicht) |
| PSS Hintergrundprozess | ≤ 25 MB | 40 MB | `dumpsys meminfo <pkg>:monitor` |
| Notification-Updates | ≤ 6/min bei Bildschirm an, 0 bei Bildschirm aus | 10/min | eigener Zähler |
| DB-Commits | 1/15 min Entladen, 1/5 min Laden | 2× | Zähler, `/proc/<pid>/io` |
| Datenvolumen | ≤ 40 KB/Tag, < 5 MB/Jahr | 10 MB/Jahr | Dateigröße |
| UI-Kaltstart bis zum ersten Frame | ≤ 700 ms (Mittelklasse) | 1.000 ms | `am start -W`, DevTools |
| UI ohne neue Daten | 0 fps | — | Performance-Overlay |
| UI live beim Laden | ≤ 1 Frame/s, ≤ 8 ms/Frame | 16 ms | `flutter run --profile` |
| Desktop-Helper | ≤ 5 MB Working Set, ≤ 1 Wakeup/min im Akkubetrieb | 10 MB | `powercfg /srumutil`, `powertop` |

### 2. Sampling-Matrix (Android)

| Zustand | Trigger | Eigenes Intervall | Notification |
|---|---|---|---|
| Entladen, Bildschirm aus (inkl. Doze) | BATTERY_CHANGED, SCREEN_ON/OFF; pro Event `CHARGE_COUNTER`, `CURRENT_NOW`, Temperatur | **keins** | keine Updates |
| Entladen, Bildschirm an | wie oben | **keins** | keine Updates |
| Laden, Bildschirm an | BATTERY_CHANGED + Handler-Tick | **10 s**, Leistung W = I × U, geglättet (EMA α≈0,3) | ≤ 1×/10 s, nur bei ΔW ≥ 0,5 W, Δ% oder ΔRestzeit ≥ 1 min |
| Laden, Bildschirm aus | BATTERY_CHANGED + Handler (weckt nicht, kein Wakelock) | **30–60 s** | 0; bei SCREEN_ON sofort 1 Update |
| UI sichtbar | Sticky-Intent (`registerReceiver(null, …)`, ohne Kosten) + eigener Receiver | 1 s nur beim Laden | — |
| Ziel-% erreicht / Hitzewarnung | Schwellwert-Prüfung bei jedem BATTERY_CHANGED | **kein AlarmManager** | einmalig |

**Verbrauch Bildschirm an vs. aus ohne Polling:** Die Differenz von `CHARGE_COUNTER` (µAh) zwischen zwei SCREEN_ON/OFF-Ereignissen ergibt die exakten mAh pro Abschnitt. Fallback: Δ% × Kapazität.
**Notification-Regeln:** `setOnlyAlertOnce`, `setSilent`, Kanal mit der niedrigsten zulässigen Wichtigkeit für die Mini-Benachrichtigung (Pathfinder prüft das für FGS). Diese wird beim Entladen nie aktualisiert. Kein Chronometer, denn SystemUI aktualisiert ihn jede Sekunde, solange er sichtbar ist.
**Modus (b):** Ein Job mit `requiresCharging` liest den Sticky-Intent und misst während der Job-Laufzeit alle 30 s. Er läuft einmal pro Ladevorgang, ohne Flutter-Engine.

### 3. Desktop
- **Windows:** `batterymon.exe` in C++/Win32 als zweites CMake-Target im vorhandenen `windows/`.
  - Auslöser sind `RegisterPowerSettingNotification` für `GUID_BATTERY_PERCENTAGE_REMAINING`, `GUID_ACDC_POWER_SOURCE` und `GUID_CONSOLE_DISPLAY_STATE`.
  - Die Leistung in mW liest der Helper per `IOCTL_BATTERY_QUERY_STATUS`. **Kein WMI**: Jede Abfrage kostet 10–50 ms plus einen fremden WmiPrvSE-Prozess.
  - Beim Laden läuft `SetCoalescableTimer` mit 30 s und 5 s Toleranz. Im Akkubetrieb gibt es keinen Timer.
- **Linux:** Helper in C++/GIO (GLib ist durch den GTK-Runner schon da).
  - Er abonniert UPower-`PropertiesChanged`; UPower pollt ohnehin.
  - Beim Laden liest `g_timeout_add_seconds(30)` den Wert `power_now`. Autostart über XDG oder `systemd --user`.
- **Flutter-Fenster beim Schließen beenden, nicht verstecken.** Ein verstecktes Fenster hält Engine und Isolate am Leben (~80–150 MB), der Helper braucht 2–5 MB.

### 4. UI-Kosten bei offener App
- Keine Dauer-Animationen, keine endlos drehenden Ladeanzeigen, keine animierten Zahlenwechsel.
- **Charts mit CustomPainter (empfohlen):**
  - Der Pfad wird aus vorab ausgedünnten Daten gebaut: SQL-Min/Max-Buckets mit ≤ 2 Punkten pro Pixel, also ~500 statt ~7.000 Punkten für 7 Tage.
  - `shouldRepaint` nur bei neuer Datenversion, `RepaintBoundary` je Chart.
  - fl_chart nur mit `duration: Duration.zero`; für drei Linien- bzw. Balken-Charts ist es unnötig.
- Live-Stream nur im Zustand `AppLifecycleState.resumed`. Bei `hidden`/`paused` alle Subscriptions und Timer kündigen. Keine gecachte FlutterEngine.
- Erste Ansicht aus dem Sticky-Intent (0 IO), Graphdaten asynchron nachladen.
- Release-Build: Impeller (Android-Standard) beibehalten, `--split-per-abi`, `cupertino_icons` entfernen.

### 5. Schreibkosten
| Aspekt | Vorgabe |
|---|---|
| Puffer | Ringpuffer im RAM; Flush nach 15 min/32 Messwerten (Entladen) bzw. 5 min (Laden); sofort bei Abstecken, `ACTION_SHUTDOWN`, `onTrimMemory`, `onDestroy` |
| SQLite | WAL, `synchronous=NORMAL` (kein fsync pro Commit), 1 Transaktion pro Flush |
| Volumen | ~700–1.000 Zeilen/Tag × ~40 B ≈ 30–40 KB/Tag |
| Aufbewahrung | Rohdaten 30 Tage, danach 5-min-Aggregate; 1 Zeile pro Ladevorgang dauerhaft |
| Wartung | Downsampling, Lernen der Ladekurve, Checkpoint **nur beim Laden und bei ausgeschaltetem Bildschirm** |

### 6. Abhängigkeiten (Bundle)
| Paket | Bewertung |
|---|---|
| `flutter_background_service`, `workmanager` | **ausschließen**: starten Dart-Isolates im Hintergrund |
| `battery_plus` | nicht nötig, der eigene MethodChannel ist schlanker |
| `fl_chart` | optional, CustomPainter bevorzugt |
| `dynamic_color` | ok, einmaliger Aufruf |

### 7. Optimierungs-Roadmap
| Prio | Maßnahme | Erwarteter Effekt | Aufwand |
|---|---|---|---|
| P0 | Nativer Hintergrund in Kotlin, Entladen nur ereignisgesteuert | −40–70 mAh/Tag ggü. Polling bzw. Engine | mittel |
| P0 | **Spike:** minimaler Receiver, 24 h `batterystats` **vor** dem UI-Bau | Budget belegt | 1–2 Tage |
| P0 | Notification-Drosselung, Bildschirm aus = 0 Updates | −90 % SystemUI-Last | gering |
| P0 | Batch-Writes, WAL/NORMAL | ~15× weniger Flash-Writes | gering |
| P1 | Prozess `:monitor`, PSS ≤ 25 MB | weniger LMK-Kills und Neustarts | gering |
| P1 | CustomPainter + SQL-Ausdünnung, 0 Animationen | 0 fps im Leerlauf, Frame ≤ 8 ms | mittel |
| P1 | C++-Helper Windows/Linux, kein WMI, Timer mit Toleranz | ≤ 5 MB, ~0 % CPU | mittel |
| P2 | Einstellungszeile „Eigenverbrauch“ (`Process.getElapsedCpuTime()`, Flush- und Update-Zähler) | Budget dauerhaft überprüft | gering |
| P2 | Perfetto-A/B mit Power-Rails (Pixel ODPM) | echte mW statt Schätzung | gering |

### 8. Messprotokoll
- **Android:**
  1. `dumpsys batterystats --reset`, dann abstecken.
  2. 24 h Normalbetrieb.
  3. `dumpsys batterystats --charged <pkg>` (mAh, CPU, Wakelocks, Alarme).
  4. `adb bugreport` mit Battery Historian auswerten.
  5. Perfetto (`sched` + `android.power` mit Power-Rails) als A/B-Messung über 1 h.
  6. Beim Laden die CPU-Zeit über `/proc/<pid>/stat` messen.
- **Windows:** `powercfg /srumutil` (Energie pro App), `powercfg /sleepstudy` (Helper darf kein Haupt-Aktivator sein), `powercfg /energy` (keine Änderung der Timer-Auflösung).
- **Linux:** `powertop` (Ziel < 0,02 Wakeups/s), `pidstat -u 60`, `voluntary_ctxt_switches`.

Alle Zahlen ohne Messung sind Größenordnungen und werden im P0-Spike überprüft.
