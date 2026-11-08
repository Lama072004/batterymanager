## Architect Response to Pathfinder — Tension A (Datengrenze & Prozessmodell)

**Ihre Position:** Pathfinder (mit Tuner) will eine gemeinsame SQLite-Datei im WAL-Modus. Einziger Schreiber ist der native Dienst im eigenen Prozess `:monitor`, die Flutter-UI liest die Datei direkt und braucht dafür kein Dauer-IPC. Settings gehen per Intent an den Dienst, nicht über SharedPreferences, die zwischen Prozessen unsicher sind.

**Meine Haltung:** Modify. Ich gebe das Binär-Journal auf und übernehme die geteilte DB, aber mit festem Vertrag.

**Begründung:**
Die Hinweise des Conductors treffen. Mein Korruptionsargument galt für zwei SQLite-Bibliotheken *im selben Prozess*, weil fcntl-Locks prozessweit freigegeben werden. Mit `:monitor` schreibt Kotlin aber in einem anderen Prozess. Liest die UI über `sqflite`, nutzt auch sie die Framework-SQLite. Dann hat jeder Prozess genau eine Lib, und WAL über mehrere Prozesse ist regulär unterstütztes SQLite. Das Argument fällt damit weg.

Schwerer wiegt die Frage, wie die Engine ihre eigene Historie liest. Sie braucht das gelernte Ladeprofil für die ETA und muss eine Session nach einem Kill wieder aufnehmen. Im Journal-Modell hätte sie dafür einen zweiten Speicher gebraucht. Nach dem Prinzip der Data Gravity besitzt deshalb der Produzent die DB. Der eigentliche Wert meines Journals bleibt erhalten: Rohdaten sind die Wahrheit, und alles Abgeleitete lässt sich neu berechnen. Dieser Wert liegt jetzt in der Tabelle `sample`.

Offen bleibt der Einwand „Schema über zwei Runtimes“. Er löst sich, wenn das Schema aus nummerierten SQL-Dateien besteht statt aus Codegen. Jeder Schreiber führt sie mit einem Runner von etwa 30 Zeilen über `PRAGMA user_version` aus: Kotlin, Dart und später eventuell ein C++-Helper. drift entfällt, denn sein Hauptnutzen, ein Schema in Dart, wäre hier eine dritte Kopie.

Skeptics Forderung nach Rohwerten passt genau hinein. Die Strom-Normalisierung auf Android ist linear (Faktor × Vorzeichen × Zellen). Deshalb setze ich sie als **SQL-View** über eine einzeilige Profiltabelle um. Damit gibt es eine einzige Implementierung für alle Leser, und Korrekturen gelten automatisch rückwirkend. Nur die Heuristik, die das Profil erkennt, läuft in der Engine.

**Endvorschlag:**
- **Schreiber:**
  - Android: nur `:monitor` (Kotlin, Framework-SQLite, WAL, `synchronous=NORMAL`, Batch-Flush nach Tuner). Dienst und Provider nutzen dort dieselbe `BatteryDb`-Instanz.
  - Desktop: nur die Dart-Engine (`sqflite_common_ffi`).
  - Migrationen laufen ausschließlich im Schreiber, aus `spec/db/NNN_*.sql`.
- **Tabellen (schreibt die Engine):**
  - `sample` mit Rohwerten: `ts_utc`, `elapsed_ms`, `boot_count`, `level_pm`, `current_raw`, `voltage_mv`, `temp_dc`, `charge_counter_raw`, `status`, `plug`, `screen` und Qualitätsflags `q`.
  - `charge_session` mit Rohsummen (Σ I·U·Δt, Minimum und Maximum roh) und der Kurve in Rohwerten.
  - `charge_profile` mit 101 Buckets (EMA). Wird bei SessionEnd in derselben Transaktion aktualisiert.
  - `device_profile` als einzige Zeile: `scale`, `sign`, `cells`, `source` (auto/manuell), `rev`.
  - `day_stat` und `setting`.
- **Normalisierung:**
  - Die Views `sample_n` und `session_n` binden `device_profile` per `CROSS JOIN` ein. UI und Engine lesen Historie nur über diese Views.
  - Der Live-Pfad normalisiert in Kotlin mit derselben Profilzeile. Golden-Fixtures prüfen, dass Kotlin und View dasselbe liefern.
- **Engine liest:**
  - `charge_profile` bei Ladebeginn in den RAM.
  - Eine offene Session nach einem Neustart über `end_ts IS NULL`.
- **UI liest:**
  - Über `openReadOnlyDatabase` und prüft dabei `user_version`. Passt die Version nicht, zeigt sie „Verlauf wird aktualisiert“.
  - Beim Resume vergleicht sie `PRAGMA data_version` und fragt nur bei einer Änderung neu ab.
  - Downsampling per `GROUP BY` in SQL.
- **UI → Engine (Settings, Profil-Override, Flush):**
  - Über `ContentProvider.call()` in `:monitor`, nicht exportiert. Der Aufruf ist synchron und startet den Prozess bei Bedarf, auch in Modus (b). Der Provider ist der einzige Schreiber von `setting` und `device_profile`.
  - Pigeon bleibt der Vertrag gegenüber Dart. Die Kotlin-Seite im UI-Prozess leitet die Aufrufe nur weiter.
- **Live (nur bei sichtbarer UI):** Die UI bindet sich in `onStart` per `Messenger` an den Dienst. Beim Binden löst sie `flush()` aus, in `onStop` löst sie die Bindung wieder.
- **Mechanik:**
  - SQL auf SQLite 3.9 beschränken (API 24): kein UPSERT, keine Window-Funktionen, kein RETURNING. Ein Fixture-Test läuft auf einem API-24-Emulator.
  - In `Application.onCreate` keine Flutter- oder Plugin-Initialisierung, weil der Code auch in `:monitor` läuft.
- **Desktop:**
  - Gleiche SQL-Dateien und gleiche Views. Die Dart-Engine schreibt, die UI liest im selben Prozess über eine zweite Read-only-Connection.
  - Kommt später ein Headless-Helper (Tension B), übernimmt er die Rolle des Schreibers. Der Vertrag mit der UI bleibt unverändert.
