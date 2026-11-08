## Tuner Response to Skeptic — Tension B (Desktop-Host, Phasing, Energiebudget)

**Their position:** Skeptic will zuerst Android (a) vollständig bauen, danach (b) als dünne Variante, dann Linux (sysfs) und zuletzt Windows. Desktop-v1 kommt ohne Tray aus und zeichnet nur auf, solange die App läuft (Autostart minimiert). Gründe: GNOME hat kein Tray, und unpaketierte Windows-Toasts brauchen eine AUMID. Als Budget schlägt er ≤ 1 %/Tag Mehrverbrauch vor.

**My response:** Modify. Ich übernehme das Phasing und ziehe den C++-Helper zurück. Das 1-%-Budget lehne ich ab.

**Reasoning:**
Auf dem Laptop gilt eine andere Rechnung als auf dem Handy. Beim Laden zahlt das Netzteil. Im Suspend bzw. Modern Standby friert das System jeden Userspace-Prozess ein (Linux-Freezer, Windows DAM). Für das Budget zählt also nur der wache Akkubetrieb, und dort arbeitet die Engine rein ereignisgesteuert (UPower `PropertiesChanged`, `WM_POWERBROADCAST`). Einen 60-s-Fallback-Poll gibt es im Akkubetrieb nicht. Ein verborgenes oder minimiertes Flutter-Fenster meldet `hidden`. Dann schaltet das Framework die Frames ab (kein Vsync, kein Raster), und die Dart-Event-Loop blockiert. Realistisch erwarte ich ~0 % CPU, ~0 Wakeups/s und 80–150 MB RAM. Belegter RAM kostet aber keine Akkuladung, also gilt mein Round-1-Argument gegen eine Engine im Hintergrund nur für Android (LMK, Kaltstarts). Der C++-Helper (1) dagegen bedeutet für einen Solo-Entwickler eine **dritte** Implementierung von Sitzungserkennung, Restzeit und Ziel-Alarm neben Kotlin und Dart. Dazu kämen zwei native OS-Anbindungen, Toast und libnotify in C++ sowie IPC, und das für eine Ersparnis nahe null. Ich streiche ihn.

Skeptics Variante (4) ist energetisch **derselbe Host** wie (2): ein Flutter-Prozess mit Dart-Engine, dessen Fenster nicht sichtbar ist. Sie unterscheiden sich nur im Tray-Icon. Deshalb: v1 hängt nicht vom Tray ab, das Tray kommt als Zusatz dort, wo es eines gibt (Windows, KDE, GNOME mit AppIndicator, unter Ubuntu vorinstalliert). Die AUMID wird einmal beim Erststart registriert. Das trifft jeden Host gleich, also entscheidet es nichts. Variante (3), headless `dart compile exe`, bleibt der Ausweg mit demselben Code, falls die Messung (2) widerlegt.

Gegen ≤ 1 %/Tag spricht: Genau so viel kostet das Anti-Muster, 1/min-Polling mit ≈ 40–70 mAh/Tag. Ein Budget, das auch die falsche Architektur besteht, prüft nichts. Die harte Grenze muss mit Faktor 2 Abstand darunter liegen, weil sich die batterystats-Schätzung (OEM-`power_profile.xml`) selbst um Faktor 2–3 irren kann. Meine Grenze lockere ich deshalb von 0,2 % auf 0,5 %.

**Budget (je eine Zahl):**

| Modus | Ziel | Harte Grenze | Messung |
|---|---|---|---|
| Android (a), Entladen | ≤ 5 mAh/24 h (0,1 %) | 25 mAh/24 h (0,5 %) | `batterystats --reset` → 24 h Alltag → `--charged <pkg>` (UID-Schätzung). Gate: 0 eigene Wakelocks/Alarme (Battery Historian) |
| Desktop, Akku, Fenster verborgen | ≤ 10 mW | 30 mW (≈ 0,5 % bei 50 Wh × 8 h) | Linux `powertop --csv` 1 h + `pidstat -u -w`. Windows `powercfg /srumutil` + `/sleepstudy`. Operativ ≤ 0,05 Wakeups/s, ≤ 2 CPU-s/h |

Die Ladezeit zählt nicht zum Budget. Dort gilt nur ein Deckel von ≤ 1 % CPU (Lüfter, Wärme).

**Flush bei Kill:** Meine 15 min aus Round 1 waren zu grob. Beim Entladen fallen Samples ohnehin nur bei Ereignissen an, und dann ist die CPU wach. Ein WAL-Commit mit `synchronous=NORMAL` kostet wenige ms, geschätzt < 0,5 mAh/Tag.
- **Android, Entladen:** Commit im selben Receiver-Aufruf (`goAsync`), Ereignis-Salven gebündelt. Verlust ≤ 1 Sample.
- **Android, Laden:** Commit alle 60 s. Verlust ≤ 1 min, der Strom kommt vom Netzteil.
- **Desktop:** im Akkubetrieb alle 5 min (NVMe-APST nicht minütlich wecken), am Netz alle 60 s.
- **Sofort-Flush überall:** bei Statuswechsel, Abstecken, Ziel erreicht, `onTrimMemory`, `PBT_APMSUSPEND`, logind `PrepareForSleep`/`PrepareForShutdown` und `WM_QUERYENDSESSION`.

**Endvorschlag:**
- **Phase 1:** Android (a) vollständig. Vor dem UI-Bau läuft der P0-Spike (24 h batterystats). Danach folgt (b) als dünner Trigger derselben Pipeline.
- **Phase 2:** Linux mit der Dart-Engine im Flutter-Prozess:
  - Autostart mit verborgenem Fenster, Single-Instance (ein erneuter Start holt das Fenster).
  - Tray optional, Benachrichtigung per D-Bus mit `replaces_id`.
  - **Messgate:** 1 h Akku-A/B gegen die Grenze von 30 mW.
- **Phase 3:** Windows mit derselben Engine, FFI `IOCTL_BATTERY_QUERY_STATUS`, Tray und Toast (AUMID beim Erststart).
- **Nur wenn das Messgate reißt:** Die Engine wird als headless `dart compile exe` ausgelagert. Einen C++-Helper gibt es nicht.
- **UI-Regel:** Außerhalb von `resumed` laufen kein `Timer.periodic`, keine Animation und kein blinkender Cursor.
