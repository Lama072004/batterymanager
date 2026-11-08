-- Abdeckung im Fenster (für schraffierte Lücken). Parameter: ?1 = von, ?2 = bis.
SELECT id, start_ts, end_ts, mode
FROM span
WHERE end_ts >= ?1 AND start_ts < ?2
ORDER BY start_ts;
