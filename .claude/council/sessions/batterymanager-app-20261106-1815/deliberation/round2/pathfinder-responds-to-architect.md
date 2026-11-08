## Pathfinder Response to Architect — Tension A: Datengrenze & Prozessmodell

**Position von Architect (Round 1):** Die native Engine schreibt nur ein append-only Binär-Journal mit 24-Byte-Records. Die drift-SQLite gehört allein Dart, das beim Öffnen idempotent importiert. Eine geteilte SQLite-Datei lehnt er ab, weil sie zwei Schreiber, Migrationen in zwei Runtimes und zwei SQLite-Bibliotheken im selben Prozess bedeuten würde. Letzteres ist eine Lock- und Korruptionsfalle.

**Haltung:** Modify. Seine Einwände werden harte Regeln für die geteilte DB, das Journal entfällt. Architects Round-2-Antwort ist dort bereits angekommen; ich ergänze die Android-Mechanik.

**Begründung:**
Die Korruptionsfalle („multiple copies of SQLite linked into the same application“) gibt es nur *innerhalb eines Prozesses*, weil POSIX-`fcntl`-Locks prozessweit gelten. Zwischen Prozessen synchronisiert jede SQLite-Kopie korrekt, genau dafür sind WAL und `-shm` gebaut. Mit dem Dienst in `:monitor` bliebe ein Restrisiko nur in einem Fall: Kotlin-Code oder ein Plugin öffnet im UI-Prozess die DB über die Framework-SQLite, während drift dort seine eigene `libsqlite3.so` geladen hat. Das ließe sich nur per Disziplin verhindern, und Disziplinregeln brechen irgendwann. `sqflite` schließt den Fall konstruktiv aus, denn es nutzt auf Android die Framework-SQLite. App-weit gibt es dann genau eine Bibliothek, und nebenbei entfallen ca. 1–1,5 MB native Lib pro ABI. Als reiner Leser gegen ein fremdes Schema verlöre drift ohnehin seinen Hauptnutzen.

Das Journal hätte drei Probleme verursacht:
- Es gäbe zwei Wahrheiten, und Graphen wären erst nach dem Import aktuell.
- Die Engine könnte ihr Ladeprofil für die ETA und offene Sessions nach einem Kill nicht abfragen.
- Skeptics Normalisierung beim Lesen wirkte nur, solange Journal-Segmente existieren.

Rohwerte in `sample` plus eine View über `device_profile` lösen alle drei.

**Settings:** Eine „eigene SharedPreferences-Datei“ funktioniert nicht über Prozessgrenzen: `MODE_MULTI_PROCESS` ist seit API 23 deprecated, und jeder Prozess hat seinen eigenen Cache. Mein Round-1-Vorschlag „per Intent“ scheitert an Modus (b), denn beim Entladen läuft kein Dienst, der den Intent empfangen könnte. `ContentProvider.call()` in `:monitor` löst beides: Der Aufruf ist synchron und startet den Prozess bei Bedarf.

**Endvorschlag:**
- **Prozesse:** `:monitor` hostet FGS, JobService, BootReceiver und Provider. `Application.onCreate` prüft den Prozessnamen und initialisiert dort nichts. Tuner misst die PSS von `:monitor` gegen einen gemeinsamen Prozess.
- **DB:**
  - Eine `battery.db` mit WAL, `synchronous=NORMAL` und Batch-Flush.
  - Android: einziger Schreiber und Migrator ist `:monitor` (Framework-SQLite). Desktop: Dart (`sqflite_common_ffi`).
  - Das Schema liegt in nummerierten `spec/db/NNN_*.sql` und wird über `user_version` ausgeführt.
- **Kein drift, kein Journal.** Die Crash-Sicherheit liefert das WAL. Die UI hält keine langen Lesetransaktionen offen, sonst blockiert sie den Checkpoint und die `-wal` wächst.
- **Lesevertrag = Views** (`sample_n`, `session_n`) mit linearer Normalisierung aus `device_profile`. Die Engine liest dieselben Views. Die Profil-Heuristik läuft nur in Kotlin. Golden-Fixtures vergleichen den Kotlin-Live-Pfad mit der View.
- **UI:** `sqflite` über `openReadOnlyDatabase`, mit `user_version`-Check und `PRAGMA data_version` beim Resume. Kotlin-Code im UI-Prozess öffnet die DB nie, er leitet nur weiter.
- **Settings, Profil-Override, Flush:** über `ContentProvider.call()` (nicht exportiert) in `:monitor`, dem einzigen Schreiber von `setting` und `device_profile`. Seiteneffekte (FGS starten, Job planen) sind erlaubt, solange die UI sichtbar ist.
- **Live:** `Messenger`-Binding nur zwischen `onStart` und `onStop`. In Modus (b) beim Entladen entsteht der Dienst dabei nur gebunden, ohne `startForeground`, und endet mit dem Unbind.
- **minSdk:**
  - Laufen alle Zielgeräte mit Android 11 oder neuer, minSdk 30 setzen. Das bringt SQLite 3.28 mit UPSERT und Window-Funktionen.
  - Sonst gilt Architects 3.9-Regel samt Fixture-Test auf API 24.
