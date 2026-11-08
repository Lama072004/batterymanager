// US-002: DB-Schema v1 & Abfragen (Akzeptanzvertrag AC-009)
// Prüft spec/db/q/series.sql gegen das echte Schema per sqflite_common_ffi auf dem Rechner.
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// Zerlegt ein SQL-Skript (Kommentare `--` entfernen, an `;` außerhalb von Quotes trennen).
List<String> splitSql(String script) {
  final out = <String>[];
  final cur = StringBuffer();
  String? quote;
  var i = 0;
  while (i < script.length) {
    final ch = script[i];
    if (quote != null) {
      cur.write(ch);
      if (ch == quote) quote = null;
      i++;
      continue;
    }
    if (ch == '-' && i + 1 < script.length && script[i + 1] == '-') {
      while (i < script.length && script[i] != '\n') {
        i++;
      }
      continue;
    }
    if (ch == "'" || ch == '"') {
      quote = ch;
      cur.write(ch);
    } else if (ch == ';') {
      final s = cur.toString().trim();
      if (s.isNotEmpty) out.add(s);
      cur.clear();
    } else {
      cur.write(ch);
    }
    i++;
  }
  final s = cur.toString().trim();
  if (s.isNotEmpty) out.add(s);
  return out;
}

Future<Database> freshDb() async {
  final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
  for (final stmt in splitSql(File('spec/db/001_init.sql').readAsStringSync())) {
    await db.execute(stmt);
  }
  return db;
}

void main() {
  sqfliteFfiInit();
  final seriesSql = File('spec/db/q/series.sql').readAsStringSync();
  const t0 = 1762430400000; // 2025-11-06 12:00 UTC
  const minute = 60000;

  group('US-002: DB-Schema v1 & Abfragen als SQL-Dateien', () {
    test('AC-009: series.sql liefert höchstens einen Punkt je (Span, Bucket), Buckets 3 min (24 h) bzw. '
        '20 min (7 Tage), und bricht an Span-Grenzen', () async {
      final db = await freshDb();
      addTearDown(db.close);

      // 7 Tage Daten, eine Messung pro Minute, mit einer Lücke (Dienst beendet) am Tag 6.
      // Span 1: Tag 0..6 bis 10:00, Lücke 1 h, Span 2: danach bis Ende.
      const days7 = 7 * 24 * 60;
      const gapStart = 6 * 24 * 60 + 10 * 60;
      const gapEnd = gapStart + 60;
      await db.insert('span', {'id': 1, 'boot': 1, 'mode': 0, 'start_ts': t0, 'end_ts': t0 + gapStart * minute});
      await db.insert('span', {'id': 2, 'boot': 1, 'mode': 0, 'start_ts': t0 + gapEnd * minute, 'end_ts': t0 + days7 * minute});
      final batch = db.batch();
      for (var m = 0; m < days7; m++) {
        if (m > gapStart && m < gapEnd) continue;
        final span = m <= gapStart ? 1 : 2;
        final charging = (m ~/ 300) % 4 == 0;
        batch.insert('sample', {
          'span': span,
          'ts_utc': t0 + m * minute,
          'elapsed_ms': m * minute,
          'level_pm': 200 + (m % 600),
          'status': charging ? 2 : 3,
          'plug': charging ? 1 : 0,
          'current_raw': charging ? 1500000 : -300000,
          'voltage_raw': 4100,
        });
      }
      await batch.commit(noResult: true);

      final to = t0 + days7 * minute;

      // 24 Std. mit 3-min-Buckets (enthält die Span-Grenze).
      const b24 = 3 * minute;
      final from24 = to - 24 * 60 * minute;
      final r24 = await db.rawQuery(seriesSql, [from24, to, b24]);
      expect(r24.length, lessThanOrEqualTo(480 + 2));
      expect(r24.length, greaterThan(400));
      _expectOnePointPerSpanBucket(r24);
      expect(r24.map((r) => r['span']).toSet(), {1, 2}, reason: 'Fenster enthält beide Spans');
      // Kein Bucket mischt Spans; in der Lücke gibt es keine Punkte (nicht interpoliert).
      final gapFrom = t0 + (gapStart + 3) * minute;
      final gapTo = t0 + (gapEnd - 3) * minute;
      expect(r24.where((r) => (r['bucket_ts'] as int) > gapFrom && (r['bucket_ts'] as int) < gapTo), isEmpty);

      // 7 Tage mit 20-min-Buckets.
      const b7 = 20 * minute;
      final r7 = await db.rawQuery(seriesSql, [t0, to, b7]);
      expect(r7.length, lessThanOrEqualTo(504 + 2));
      expect(r7.length, lessThanOrEqualTo(600));
      _expectOnePointPerSpanBucket(r7);
      for (final r in r7) {
        expect((r['bucket_ts'] as int) % b7, 0);
        expect(r['level_min_pm'] as int, lessThanOrEqualTo(r['level_max_pm'] as int));
      }
      // Leistung kommt normalisiert (mW) aus dem View: 1,5 A · 4,1 V = 6150 mW beim Laden.
      final chargingAvgs = r24.where((r) => r['plugged'] == 1).map((r) => (r['power_avg_mw'] as num).round());
      expect(chargingAvgs.reduce((a, b) => a > b ? a : b), 6150);
    });
  });
}

void _expectOnePointPerSpanBucket(List<Map<String, Object?>> rows) {
  final keys = rows.map((r) => '${r['span']}/${r['bucket_ts']}').toList();
  expect(keys.toSet().length, keys.length, reason: 'höchstens ein Punkt je (Span, Bucket)');
}
