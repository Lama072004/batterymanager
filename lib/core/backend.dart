import 'package:flutter/services.dart';
import 'package:sqflite/sqflite.dart';

import 'model.dart';

/// Port zwischen UI und Hintergrund. Android: [AndroidBackend]; Tests: [FakeBackend].
abstract class BatteryBackend {
  /// null = Dienst antwortet nicht mit bekannter Version („Dienst veraltet“).
  Future<AppState?> state();
  Future<Live?> snapshot();
  Future<AppState?> putSettings(Map<String, Object> changes);
  Future<AppState?> setProfile(Map<String, Object> profile);
  Future<void> clearHistory();
  Future<void> resetLimit();
  Future<void> seedDemo();
  Future<History> history(int from, int to, {required int mode});
  Future<SystemStatus> systemStatus();
  Future<bool> requestNotificationPermission();
  Future<void> requestIgnoreBatteryOptimizations();
  Future<void> openNotificationSettings();
  Future<void> openBatterySettings();
  Future<String?> startService();
}

/// Android: MethodChannel → ContentProvider.call() im Prozess :monitor; Historie read-only per sqflite.
class AndroidBackend implements BatteryBackend {
  static const _ch = MethodChannel('batterymanager/monitor');
  Database? _db;
  final _sql = <String, String>{};

  Future<Map<Object?, Object?>?> _call(String method, [Map<String, Object> args = const {}]) async {
    final r = await _ch.invokeMethod<Map<Object?, Object?>>('call', {'method': method, 'args': args});
    if (r == null || r['v'] != 1) return null;
    return r;
  }

  @override
  Future<AppState?> state() async => (await _call('getState'))?.let(AppState.fromMap);

  @override
  Future<Live?> snapshot() async => (await _call('snapshot'))?.let(Live.fromMap);

  @override
  Future<AppState?> putSettings(Map<String, Object> changes) async => (await _call('putSettings', changes))?.let(AppState.fromMap);

  @override
  Future<AppState?> setProfile(Map<String, Object> profile) async => (await _call('setProfile', profile))?.let(AppState.fromMap);

  @override
  Future<void> clearHistory() => _call('clearHistory');

  @override
  Future<void> seedDemo() => _call('seedDemo');

  @override
  Future<void> resetLimit() => _call('resetLimit');

  Future<String> _q(String name) async => _sql[name] ??= await rootBundle.loadString('spec/db/q/$name');

  Future<Database?> _open() async {
    if (_db != null) return _db;
    final path = (await state())?.dbPath;
    if (path == null) return null;
    try {
      _db = await openReadOnlyDatabase(path, singleInstance: true);
    } catch (_) {
      // WAL-Dateien noch nicht vorhanden: normal öffnen, aber nie schreiben.
      _db = await openDatabase(path, singleInstance: true);
    }
    return _db;
  }

  @override
  Future<History> history(int from, int to, {required int mode}) async {
    final db = await _open();
    if (db == null) return History.empty(from, to);
    final bucket = (to - from) <= 26 * 3600 * 1000 ? 180000 : 1200000;
    final levels = (await db.rawQuery(await _q('series.sql'), [from, to, bucket]))
        .map((r) => LevelPoint(
              r['span'] as int,
              r['bucket_ts'] as int,
              r['level_min_pm'] as int,
              r['level_max_pm'] as int,
              (r['power_avg_mw'] as num?)?.toDouble(),
              r['plugged'] == 1,
            ))
        .toList();
    final coverage = (await db.rawQuery(await _q('spans.sql'), [from, to]))
        .map((r) => Coverage(r['start_ts'] as int, r['end_ts'] as int))
        .toList();
    final sessions = <ChargeSessionInfo>[];
    for (final r in await db.rawQuery(await _q('sessions.sql'), [4])) {
      final pts = (await db.rawQuery(await _q('points.sql'), [r['id']]))
          .map((p) => SessionPoint(p['t_off_s'] as int, p['level_pm'] as int, p['power_mw'] as int?))
          .toList();
      sessions.add(ChargeSessionInfo(
        id: r['id'] as int,
        startTs: r['start_ts'] as int,
        endTs: r['end_ts'] as int?,
        startLevelPm: r['start_level_pm'] as int,
        endLevelPm: r['end_level_pm'] as int?,
        startKnown: r['start_known'] == 1,
        energyMwh: (r['energy_mwh'] as num?)?.toDouble(),
        peakMw: r['peak_mw'] as int?,
        points: pts,
      ));
    }
    final drain = mode == AppState.modeChargingOnly
        ? _drainBetweenSessions(sessions)
        : _drain(await db.rawQuery(await _q('drain.sql'), [from, to]));
    return History(from: from, to: to, levels: levels, coverage: coverage, sessions: sessions, drain: drain);
  }

  static DrainStats _drain(List<Map<String, Object?>> rows) {
    double? rate(Object? screen) {
      final r = rows.where((e) => e['screen'] == screen).firstOrNull;
      if (r == null) return null;
      final ms = (r['dur_ms'] as num).toDouble();
      if (ms < 10 * 60 * 1000) return null;
      return (r['drop_pm'] as num) / 10.0 / (ms / 3600000.0);
    }
    var totalMs = 0.0, totalDrop = 0.0;
    for (final r in rows) {
      totalMs += (r['dur_ms'] as num).toDouble();
      totalDrop += (r['drop_pm'] as num).toDouble();
    }
    return DrainStats(
      onPctPerH: rate(1),
      offPctPerH: rate(0),
      avgPctPerH: totalMs >= 10 * 60 * 1000 ? totalDrop / 10.0 / (totalMs / 3600000.0) : null,
    );
  }

  /// Modus „Nur beim Laden“: nur Ø zwischen Ladevorgängen (Ende → nächster Beginn).
  static DrainStats _drainBetweenSessions(List<ChargeSessionInfo> s) {
    var ms = 0.0, drop = 0.0;
    for (var i = 0; i + 1 < s.length; i++) {
      final newer = s[i], older = s[i + 1];
      final end = older.endTs, endLv = older.endLevelPm;
      if (end == null || endLv == null) continue;
      ms += newer.startTs - end;
      drop += endLv - newer.startLevelPm;
    }
    return DrainStats(avgPctPerH: ms > 0 ? drop / 10.0 / (ms / 3600000.0) : null, onlyAverage: true);
  }

  @override
  Future<SystemStatus> systemStatus() async =>
      SystemStatus.fromMap((await _ch.invokeMethod<Map<Object?, Object?>>('systemStatus')) ?? const {});

  @override
  Future<bool> requestNotificationPermission() async => (await _ch.invokeMethod<bool>('requestNotificationPermission')) ?? false;

  @override
  Future<void> requestIgnoreBatteryOptimizations() => _ch.invokeMethod('requestIgnoreBatteryOptimizations');

  @override
  Future<void> openNotificationSettings() => _ch.invokeMethod('openNotificationSettings');

  @override
  Future<void> openBatterySettings() => _ch.invokeMethod('openBatterySettings');

  @override
  Future<String?> startService() => _ch.invokeMethod<String>('startService');
}

/// Testdoppel ohne Plattform (Widget-Tests).
class FakeBackend implements BatteryBackend {
  FakeBackend({AppState? state, Live? live, History? history, SystemStatus? status, this.stale = false})
      : appState = state ?? const AppState(onboarded: true),
        live = live ?? const Live(levelPm: 640, status: 3, plug: 0),
        _history = history,
        status = status ?? const SystemStatus(notificationsEnabled: true, ignoringBatteryOptimizations: true);

  AppState appState;
  Live live;
  SystemStatus status;
  final History? _history;
  final bool stale;
  final calls = <String>[];

  @override
  Future<AppState?> state() async => stale ? null : appState;
  @override
  Future<Live?> snapshot() async => live;
  @override
  Future<AppState?> putSettings(Map<String, Object> changes) async {
    calls.add('putSettings:$changes');
    appState = AppState(
      targetPct: (changes['targetPct'] as int?) ?? appState.targetPct,
      alarm: (changes['alarm'] as bool?) ?? appState.alarm,
      mode: (changes['mode'] as int?) ?? appState.mode,
      heatWarnDc: (changes['heatWarnDc'] as int?) ?? appState.heatWarnDc,
      summary: (changes['summary'] as bool?) ?? appState.summary,
      onboarded: (changes['onboarded'] as bool?) ?? appState.onboarded,
      liveChip: (changes['liveChip'] as bool?) ?? appState.liveChip,
    );
    return appState;
  }

  @override
  Future<AppState?> setProfile(Map<String, Object> profile) async => appState;
  @override
  Future<void> clearHistory() async => calls.add('clearHistory');
  @override
  Future<void> seedDemo() async {}
  @override
  Future<void> resetLimit() async => calls.add('resetLimit');
  @override
  Future<History> history(int from, int to, {required int mode}) async => _history ?? History.empty(from, to);
  @override
  Future<SystemStatus> systemStatus() async => status;
  @override
  Future<bool> requestNotificationPermission() async => true;
  @override
  Future<void> requestIgnoreBatteryOptimizations() async {}
  @override
  Future<void> openNotificationSettings() async {}
  @override
  Future<void> openBatterySettings() async {}
  @override
  Future<String?> startService() async {
    calls.add('startService');
    return null;
  }
}

extension _Let<T> on T {
  R let<R>(R Function(T) f) => f(this);
}
