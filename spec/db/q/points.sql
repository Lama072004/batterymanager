-- Graph 2: Punkte eines Ladevorgangs (normalisiert). Parameter: ?1 = Sitzungs-id.
SELECT t_off_s, level_pm, power_mw
FROM point_n
WHERE session = ?1
ORDER BY t_off_s;
