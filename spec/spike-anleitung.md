# Phase 0: Mess-Spike auf deinem Handy (US-004)

Ziel: Bevor die Oberfläche gebaut wird, messen wir auf deinem Gerät:
- wie es Strom, Spannung und Ladestand meldet (Einheit und Vorzeichen),
- wie spät der Modus „Nur beim Laden“ anspringt,
- was der Hintergrund an Akku kostet.

Die Ergebnisse trägst du in `spec/spike-results.md` ein.

Der Logger läuft ohne UI und ohne Flutter im Prozess `:monitor`. Gesteuert wird er per `adb`, die Daten landen als CSV auf dem Handy.

## Vorbereitung

1. Entwickleroptionen und USB-Debugging am Handy einschalten, Handy per USB verbinden. `adb devices` muss es anzeigen.
2. Release-Build bauen und installieren. Release ist wichtig, weil Debug-Builds den Verbrauch um ein Vielfaches verfälschen.
   ```bash
   flutter build apk --release --split-per-abi
   adb install -r build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
   ```
   Ohne `android/key.properties` wird mit dem Debug-Schlüssel signiert. Das ist für den Spike in Ordnung, vor der ersten echten Nutzung aber nicht (siehe `android/key.properties.example`).
3. Benachrichtigungen erlauben (Android 13+):
   ```bash
   adb shell pm grant de.mlampert.batterymanager android.permission.POST_NOTIFICATIONS
   ```

**Steuerung:**
```bash
adb shell am start -n de.mlampert.batterymanager/.MainActivity --es spike start     # Logger an
adb shell am start -n de.mlampert.batterymanager/.MainActivity --es spike stop      # Logger aus
adb shell am start -n de.mlampert.batterymanager/.MainActivity --es spike job_on    # S2-Job an
adb shell am start -n de.mlampert.batterymanager/.MainActivity --es spike job_off   # S2-Job aus
```

**Daten abholen:**
```bash
adb pull /sdcard/Android/data/de.mlampert.batterymanager/files/spike/ spike-daten/
```

**Dateien:**
- `s1_samples.csv`: alle Rohwerte
- `s2_jobs.csv`: Startzeiten des Jobs
- `s4_starts.csv`: Startversuche des Dienstes (ok oder Fehler)

## S1: Messwerte (ca. 48 h, nebenbei)

Logger starten (`spike start`), dann normal weiterbenutzen und diese Situationen je ≥ 15 min abdecken:

| # | Szenario |
|---|---|
| 1 | Schnellladegerät, Bildschirm aus |
| 2 | Laden am PC-USB-Anschluss |
| 3 | Laden beim Spielen oder Video (hohe Last) |
| 4 | Kabellos laden (falls möglich) |
| 5 | Ungenutzt liegen lassen, Bildschirm aus |

Eintragen in `spike-results.md`:
- **Einheit:** Steigt `charge_counter` über 10 min etwa um ∫`current_now`·dt (≈ 1 → µA) oder um das ≈ 1000-Fache (→ mA)?
- **Vorzeichen:** Ist `current_now` beim Laden positiv oder negativ?
- **Aktualisierung:** Wie oft ändert sich `current_now` (alle paar Sekunden oder nur selten)?
- **Fehlende Werte:** Liefert das Gerät `cycle_count`, `current_avg` und `energy_counter`, oder sind die Spalten leer bzw. −1?

## S2: Startverzögerung „Nur beim Laden“ (≥ 10 Einsteckvorgänge)

1. Der Logger läuft weiter (er liefert den Einsteckzeitpunkt), zusätzlich `spike job_on`.
2. Bei etwa 15 %, 50 %, 85 % und 92 % Akku jeweils einstecken, ≥ 30 min stecken lassen, dann abstecken. Insgesamt ≥ 10-mal.
3. Latenz = Zeit in `s2_jobs.csv` minus Einsteckzeit in `s1_samples.csv` (erste Zeile mit `plugged > 0`).
4. Median und Anzahl der Einsteckvorgänge ohne Jobstart eintragen.
   - Median > 30 min oder > 20 % ohne Start → der Modus gilt als „experimentell“.

## S3: Eigenverbrauch (24 h, Logger im Modus „Immer aktiv“)

1. Logger läuft (`spike start`), Release-Build. Die Entwickleroption „Aktiv lassen“ ist aus.
2. Messung zurücksetzen, dann USB trennen (sonst wird nichts als Entladung gezählt):
   ```bash
   adb shell dumpsys batterystats --enable full-wake-history
   adb shell dumpsys batterystats --reset
   ```
3. 24 h normal benutzen, davon ≥ 6 h nachts liegen lassen.
4. Wieder verbinden und auswerten:
   ```bash
   adb shell dumpsys batterystats --charged de.mlampert.batterymanager > bs.txt
   adb shell dumpsys alarm | grep batterymanager
   adb shell dumpsys jobscheduler | grep batterymanager
   adb shell "grep libflutter /proc/\$(pidof de.mlampert.batterymanager:monitor)/maps"
   adb shell dumpsys meminfo de.mlampert.batterymanager:monitor
   ```

**Akzeptanz:**
- Schätzung für die App-UID ≤ 25 mAh/24 h, Ziel ≤ 5 mAh.
- Keine eigenen WakeLocks, Alarme oder Wakeup-Gründe. Ausnahme ist der S2-Job, wenn er gerade aktiv ist; für S3 also vorher `job_off` setzen.
- `libflutter` taucht in `:monitor` nicht auf.
- PSS ≤ 40 MB.
- Die CPU-Zeit steht im CSV in der Spalte `cpu_ms`.

## S4/S5: Startwege und Benachrichtigung

- **Neustart:** Handy neu starten. In `s4_starts.csv` muss eine Zeile `boot,…,ok` erscheinen, und der Logger läuft wieder.
- **Update:** `adb install -r …` erneut ausführen. Erwartet wird eine Zeile `package_replaced,…,ok`.
- **„Nur beim Laden“ ohne laufenden Logger:**
  1. `spike stop` und `spike job_on`.
  2. Einmal ohne Akku-Ausnahme einstecken: Erwartet wird `fgs_start` = `ForegroundServiceStartNotAllowedException` (ab Android 12).
  3. Dann die Ausnahme setzen und erneut einstecken: Erwartet wird `ok`.
     ```bash
     adb shell dumpsys deviceidle whitelist +de.mlampert.batterymanager
     ```
- **S5, Kanalwechsel:** Folgt in Phase 1 mit der echten Lade-Benachrichtigung. Bis dahin offen.

## Fehlerbehebung Build
Meldet Gradle `java.io.IOException: Unable to establish loopback connection`, findet Java (JDK 16+ unter Windows) kein brauchbares Temp-Verzeichnis für interne Sockets. Abhilfe vor dem Build:
```bash
mkdir -p /d/tmp/jdk
```
```bash
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=D:/tmp/jdk"
```
