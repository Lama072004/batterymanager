-- Akku-Gesundheit: alle Kapazitätsmessungen, älteste zuerst.
SELECT ts_utc, capacity_mah, design_mah, cycles, window_pm
FROM health_point
ORDER BY ts_utc;
