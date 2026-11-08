-- Graph 1 (Akkustand-Verlauf) und mittlere Leistung je Zeit-Bucket.
-- Parameter: ?1 = von (ts_utc in ms, inklusive), ?2 = bis (exklusive), ?3 = Bucket-Breite in ms
--   24 Std.: 180000 (3 min, 480 Buckets) · 7 Tage: 1200000 (20 min, 504 Buckets)
-- Ein Punkt je (Span, Bucket): Die Linie bricht an Span-Grenzen; Lücken werden nie interpoliert.
-- Höchstens Bucket-Anzahl + Anzahl Spans im Fenster Zeilen (≈ ≤ 600).
SELECT span,
       (ts_utc / ?3) * ?3       AS bucket_ts,
       MIN(ts_utc)              AS ts_first,
       MAX(ts_utc)              AS ts_last,
       MIN(level_pm)            AS level_min_pm,
       MAX(level_pm)            AS level_max_pm,
       AVG(power_mw)            AS power_avg_mw,
       MAX(CASE WHEN plug > 0 THEN 1 ELSE 0 END) AS plugged,
       COUNT(*)                 AS n
FROM sample_n
WHERE ts_utc >= ?1 AND ts_utc < ?2
GROUP BY span, ts_utc / ?3
ORDER BY bucket_ts, span;
