# Interview Transcript — Batterymanager Flutter-App

## Nachtrag während Intake
**User:** "und die app soll simplistisch aussehen" → minimalistisches Design ist Pflicht.

## Round 1

**Q (Pathfinder):** Plattform? Projekt hat Android/Windows/Linux, kein iOS. iOS liefert keine Watt/Spannung/Strom/Temperatur.
**A:** Android, Windows und Linux.

**Q (Pathfinder/Advocate):** Wie soll das Lade-Popup erscheinen, wenn die App geschlossen ist?
**A:** Live-Benachrichtigung (aufpoppend, mit Watt, Restzeit, Fortschritt; live aktualisiert).

**Q (Skeptic):** Apps können das Laden nicht physisch stoppen (ohne Root). Was passiert bei Erreichen der Ziel-%?
**A:** Alarm + Restzeit — Restzeit bis Ziel-% anzeigen, bei Erreichen Ton/Vibration/Benachrichtigung "Jetzt abstecken".

**Q (Advocate/Alchemist):** Pro-App-Verbrauch ist für normale Apps nicht verfügbar. Was soll "Batterieverbrauch" zeigen?
**A:** Entladerate + Bildschirm — %/Stunde, mA-Verbrauch, Bildschirm an vs. aus, Verlaufsgraph. Keine Extra-Berechtigung.

## Round 2

**Q (Tuner/Pathfinder):** Sofortiges Lade-Popup braucht unter Android einen Foreground-Service mit dauerhafter stummer Mini-Benachrichtigung. Okay?
**A:** Einstellbar — Nutzer wählt (z.B. beim ersten Start/in Einstellungen) zwischen Dauerdienst (sofortiges Popup, lückenloser Verlauf) und ohne Dauerdienst (verzögert/unzuverlässig, Lücken).

**Q (Advocate/Artisan):** Welche Graphen?
**A:** Akku-% Verlauf, Ladeleistung (Watt), Verbrauch/Entladerate. (KEIN Temperatur-Graph — Temperatur nur als Wert.)

**Q (Strategist/Herald):** Verteilung?
**A:** Privat / Sideload — keine Store-Auflagen, kein Tracking, keine Werbung.

**Q (Artisan):** Minimalistischer Stil?
**A:** Material You — Systemfarben (Android 12+ dynamic color), Desktop-Fallback auf Akzent-/Standardfarbe. Hell/Dunkel folgt automatisch dem System.

## Nachtrag nach der Deliberation (2026-11-06)
**User:** "und die app soll aktuell aber auch nur auf android laufen also alles andere muss nicht getestet werden"
→ Plattform-Umfang vorerst nur Android; Windows/Linux weder bauen noch testen. Design entsprechend angepasst (Desktop als "später" dokumentiert).
