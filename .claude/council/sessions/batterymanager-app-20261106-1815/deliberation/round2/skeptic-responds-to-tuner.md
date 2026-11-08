## Skeptic Response to Tuner — Tension B (Desktop-Host, Phasing, Energiebudget)

**Their position:** Tuner wollte auf dem Desktop einen C++-Helper statt eines Flutter-Prozesses im Hintergrund, ein Budget von ≤ 5 mAh/24 h (≈ 0,1 %/Tag, Grenze 0,2 %) per batterystats und Flush-Batching mit bis zu 15 min Verlust bei einem Kill. In Round 2 hat er den Helper gestrichen, die Grenze auf 25 mAh (0,5 %) gelockert und den Verlust beim Entladen auf ≤ 1 Sample gesenkt.

**My response:** Modify. Bei Host und Phasing sind wir uns einig, bei der Messmethode präzisiere ich.

**Reasoning:**
Der C++-Helper war energetisch richtig, brachte aber das falsche Risiko mit: eine dritte Implementierung von Sitzung, Restzeit und Alarm neben Kotlin und Dart, die unbemerkt auseinanderläuft. Tuners eigenes Argument entscheidet die Frage: Energie kostet das Aufwachen, nicht der RAM. Meine „v1 ohne Tray“ und seine Dart-Engine im verborgenen Flutter-Fenster sind derselbe Host, das Tray ist nur Komfort. Ob „verborgen = 0 Frames, 0 Timer“ trotz Plugins (`tray_manager`, `window_manager`) stimmt, entscheidet das Messgate, nicht die Annahme.

Meine 1 %/Tag ziehe ich als Ziel zurück, denn sie ließen das 1/min-Anti-Muster durch. Ehrlich *messbar* sind aber auch 0,1 % nicht. Ein A/B-Vergleich im Alltag streut um mehrere Prozent pro Tag. Die batterystats-UID-Zahl ist ein Modell auf Basis der OEM-`power_profile.xml`. Außerdem erfasst sie Kosten nicht, die auf fremde UIDs gebucht werden: SystemUI baut unsere Benachrichtigung neu auf, und der Broadcast-Wakelock des Systems verlängert das Wachfenster. Die mAh-Zahl ist deshalb Ziel, nicht Abnahmekriterium; abgenommen wird über exakt messbare Zähler. Am Laptop ebenso: 30 mW bei 3–8 W Systemlast löst ein 1-h-A/B nicht auf, Wakeups/s und CPU-s/h schon. Auch Tuners „~250 Ereignisse/Tag“ ist eine Annahme. Manche OEMs senden `BATTERY_CHANGED` bei jeder Spannungs- oder Temperaturänderung, und mit einem Commit pro Ereignis werden daraus Tausende.

15 min Verlust wären für reine Jitter-Samples tolerabel, weil ein OEM-Kill ohnehin eine Lücke erzeugt. Für Zustandswechsel sind sie es nicht: Geht der Sitzungsbeginn oder „Alarm ausgelöst“ verloren, folgen nach dem Neustart ein falscher Verlauf oder ein Doppelalarm. Tuners R2-Regel (Commit im Receiver, Sofort-Flush bei Statuswechsel) erfüllt diese Anforderung. Ich mache sie zur Pflicht.

**Endvorschlag:**
- **Phase 0, Spike vor dem UI-Bau:** nativer Receiver + FGS, 24 h auf ≥ 2 Geräten (Pixel + Samsung). Gemessen werden batterystats-mAh, `BATTERY_CHANGED`/Tag, Commits/Tag und gleich die (b)-Startlatenz (Tension C).
- **Phase 1:** Android (a) vollständig. **Phase 1b:** (b) als Nur-beim-Laden-FGS.
- **Phase 2:** Linux. Dart-Engine im Flutter-Prozess, Autostart verborgen, Single-Instance, Tray nur mit AppIndicator.
- **Phase 3:** Windows mit demselben Kern, Tray und Toast (AUMID).
- **Nur bei gerissenem Gate:** headless `dart compile exe`, kein C++.
- **Budget Android (a), Entladen:** Ziel ≤ 5 mAh/24 h, Grenze 25 mAh/24 h (UID-Schätzung).
  - **Harte Gates (pass/fail):** 0 eigene Wakelocks, Alarme und Jobs; CPU ≤ 2 s/h; 0 Notification-Updates bei ausgeschaltetem Bildschirm; reine Jitter-Ereignisse (nur Spannung oder Temperatur) im RAM bündeln.
  - **Sanity:** A/B im Flugmodus über 2 × 24 h darf keinen erkennbaren Unterschied zeigen. Meine 1 % bleiben als Alarmschwelle.
- **Desktop-Gate:** ≤ 0,05 Wakeups/s, ≤ 2 CPU-s/h, keine Änderung der Timer-Auflösung (`powercfg /energy`), nie Top-Aktivator in `/sleepstudy`. mW nur als abgeleitete Schätzung.
- **Datenverlust:**
  - Ein-/Abstecken, Sitzung, Ziel/Alarm und Moduswechsel sofort committen.
  - Alarm zuerst anzeigen, dann das Flag schreiben, feste Notification-ID pro Sitzung: Ein Doppel ist idempotent, ein Verlust ausgeschlossen.
  - Die Lücke beginnt beim letzten Commit, nie interpolieren.
  - Linux-Flush über einen logind-Delay-Inhibitor, sonst verliert der Flush das Rennen gegen den Suspend.
- **UI:** keine Prozentzahl versprechen, stattdessen die Zeile „Eigenverbrauch (gemessen)“.
