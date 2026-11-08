-- Graph 3: Entladerate je Bildschirmzustand (1 an, 0 aus, NULL unbekannt) im Fenster.
-- Parameter: ?1 = von (ts_utc ms), ?2 = bis. Raten nur aus elapsed_ms innerhalb eines Spans,
-- nur zwischen zwei aufeinanderfolgenden Entlade-Samples (ids sind je Span lückenlos aufsteigend).
SELECT a.screen                         AS screen,
       SUM(b.elapsed_ms - a.elapsed_ms) AS dur_ms,
       SUM(a.level_pm - b.level_pm)     AS drop_pm
FROM sample a
JOIN sample b ON b.id = a.id + 1 AND b.span = a.span
WHERE a.ts_utc >= ?1 AND a.ts_utc < ?2
  AND a.plug = 0 AND b.plug = 0
  AND b.elapsed_ms > a.elapsed_ms
  AND b.elapsed_ms - a.elapsed_ms < 3600000
GROUP BY a.screen;
