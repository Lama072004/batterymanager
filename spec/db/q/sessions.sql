-- Graph 2: letzte Ladevorgänge (normalisiert). Parameter: ?1 = Anzahl.
SELECT id, start_ts, end_ts, start_level_pm, end_level_pm, start_known, target_pct,
       energy_mwh, peak_mw
FROM session_n
ORDER BY start_ts DESC
LIMIT ?1;
