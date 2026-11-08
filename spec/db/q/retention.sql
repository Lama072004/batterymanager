-- Retention: Rohdaten (sample, span) 30 Tage; charge_session, session_point, day_stat unbegrenzt.
-- Läuft höchstens 1× in 24 h und NUR beim Laden (das Netzteil zahlt).
-- Anweisung 1 wird wiederholt, bis weniger als ?2 Zeilen gelöscht wurden (Batches von 5000).
-- Parameter: ?1 = Grenzzeitpunkt ts_utc in ms (jetzt − 30 Tage), ?2 = Batch-Größe

DELETE FROM sample
WHERE id IN (SELECT id FROM sample WHERE ts_utc < ?1 ORDER BY id LIMIT ?2);

-- Anweisung 2 (einmal, Parameter ?1): Spans ohne verbliebene Samples entfernen.
DELETE FROM span
WHERE end_ts < ?1
  AND NOT EXISTS (SELECT 1 FROM sample WHERE sample.span = span.id);
