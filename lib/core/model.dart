// Datenmodell der UI. Alle Werte kommen normalisiert aus dem Dienst bzw. den SQL-Views.

int? _i(Object? v) => v == null ? null : (v as num).toInt();
double? _d(Object? v) => v == null ? null : (v as num).toDouble();

/// Live-Zustand (MonitorProvider.snapshot).
class Live {
  const Live({
    required this.levelPm,
    required this.status,
    required this.plug,
    this.screen,
    this.currentMa,
    this.voltageMv,
    this.powerMw,
    this.tempDc,
    this.health = 1,
    this.cycleCount,
    this.capacityMah,
    this.etaState = 'idle',
    this.etaTargetS,
    this.etaFullS,
    this.estimated = false,
    this.targetPct = 80,
    this.alarm = true,
    this.alarmMuted = false,
    this.sessionStartUtc,
    this.sessionStartPct,
    this.startKnown = true,
    this.fullSinceUtc,
    this.peakMw,
    this.serviceRunning = false,
    this.profileSource = 0,
  });

  final int levelPm, status, plug, health, targetPct, profileSource;
  final int? screen, currentMa, voltageMv, powerMw, tempDc, cycleCount, capacityMah;
  final String etaState;
  final int? etaTargetS, etaFullS, sessionStartUtc, sessionStartPct, fullSinceUtc, peakMw;
  final bool estimated, alarm, alarmMuted, startKnown, serviceRunning;

  int get pct => levelPm ~/ 10;
  bool get charging => plug > 0;

  factory Live.fromMap(Map<Object?, Object?> m) => Live(
        levelPm: _i(m['levelPm']) ?? 0,
        status: _i(m['status']) ?? 1,
        plug: _i(m['plug']) ?? 0,
        screen: _i(m['screen']),
        currentMa: _i(m['currentMa']),
        voltageMv: _i(m['voltageMv']),
        powerMw: _i(m['powerMw']),
        tempDc: _i(m['tempDc']),
        health: _i(m['health']) ?? 1,
        cycleCount: _i(m['cycleCount']),
        capacityMah: _i(m['capacityMah']),
        etaState: (m['etaState'] as String?) ?? 'idle',
        etaTargetS: _i(m['etaTargetS']),
        etaFullS: _i(m['etaFullS']),
        estimated: (m['estimated'] as bool?) ?? false,
        targetPct: _i(m['targetPct']) ?? 80,
        alarm: (m['alarm'] as bool?) ?? true,
        alarmMuted: (m['alarmMuted'] as bool?) ?? false,
        sessionStartUtc: _i(m['sessionStartUtc']),
        sessionStartPct: _i(m['sessionStartPct']),
        startKnown: (m['startKnown'] as bool?) ?? true,
        fullSinceUtc: _i(m['fullSinceUtc']),
        peakMw: _i(m['peakMw']),
        serviceRunning: (m['serviceRunning'] as bool?) ?? false,
        profileSource: _i(m['profileSource']) ?? 0,
      );
}

/// Einstellungen + Dienststatus (MonitorProvider.getState).
class AppState {
  const AppState({
    this.targetPct = 80,
    this.alarm = true,
    this.mode = 0,
    this.heatWarnDc = 450,
    this.summary = true,
    this.onboarded = false,
    this.dbPath,
    this.iToMa = 0.001,
    this.sign = 1,
    this.cells = 1,
    this.profileSource = 0,
    this.profileRev = 0,
    this.lastSampleId = 0,
    this.serviceRunning = false,
    this.cpuMs = 0,
    this.flushes = 0,
    this.notifUpdates = 0,
  });

  static const modeAlways = 0;
  static const modeChargingOnly = 1;

  final int targetPct, mode, heatWarnDc, sign, cells, profileSource, profileRev, lastSampleId, cpuMs, flushes, notifUpdates;
  final bool alarm, summary, onboarded, serviceRunning;
  final String? dbPath;
  final double iToMa;

  factory AppState.fromMap(Map<Object?, Object?> m) => AppState(
        targetPct: _i(m['targetPct']) ?? 80,
        alarm: (m['alarm'] as bool?) ?? true,
        mode: _i(m['mode']) ?? 0,
        heatWarnDc: _i(m['heatWarnDc']) ?? 450,
        summary: (m['summary'] as bool?) ?? true,
        onboarded: (m['onboarded'] as bool?) ?? false,
        dbPath: m['dbPath'] as String?,
        iToMa: _d(m['iToMa']) ?? 0.001,
        sign: _i(m['sign']) ?? 1,
        cells: _i(m['cells']) ?? 1,
        profileSource: _i(m['profileSource']) ?? 0,
        profileRev: _i(m['profileRev']) ?? 0,
        lastSampleId: _i(m['lastSampleId']) ?? 0,
        serviceRunning: (m['serviceRunning'] as bool?) ?? false,
        cpuMs: _i(m['cpuMs']) ?? 0,
        flushes: _i(m['flushes']) ?? 0,
        notifUpdates: _i(m['notifUpdates']) ?? 0,
      );
}

class SystemStatus {
  const SystemStatus({this.notificationsEnabled = true, this.ignoringBatteryOptimizations = false, this.manufacturer = ''});
  final bool notificationsEnabled, ignoringBatteryOptimizations;
  final String manufacturer;

  factory SystemStatus.fromMap(Map<Object?, Object?> m) => SystemStatus(
        notificationsEnabled: (m['notificationsEnabled'] as bool?) ?? true,
        ignoringBatteryOptimizations: (m['ignoringBatteryOptimizations'] as bool?) ?? false,
        manufacturer: (m['manufacturer'] as String?) ?? '',
      );
}

/// Ein Bucket aus q/series.sql.
class LevelPoint {
  const LevelPoint(this.span, this.bucketTs, this.levelMinPm, this.levelMaxPm, this.powerAvgMw, this.plugged);
  final int span, bucketTs, levelMinPm, levelMaxPm;
  final double? powerAvgMw;
  final bool plugged;
  double get levelPct => (levelMinPm + levelMaxPm) / 20.0;
}

class Coverage {
  const Coverage(this.start, this.end);
  final int start, end;
}

class SessionPoint {
  const SessionPoint(this.tOffS, this.levelPm, this.powerMw);
  final int tOffS, levelPm;
  final int? powerMw;
}

class ChargeSessionInfo {
  const ChargeSessionInfo({
    required this.id,
    required this.startTs,
    this.endTs,
    required this.startLevelPm,
    this.endLevelPm,
    this.startKnown = true,
    this.energyMwh,
    this.peakMw,
    this.points = const [],
  });
  final int id, startTs, startLevelPm;
  final int? endTs, endLevelPm, peakMw;
  final bool startKnown;
  final double? energyMwh;
  final List<SessionPoint> points;

  double? get avgW {
    final e = energyMwh;
    final end = endTs;
    if (e == null || end == null || end <= startTs) return null;
    return e / ((end - startTs) / 3600000.0) / 1000.0;
  }
}

/// Graph 3: Entladerate in %/h.
class DrainStats {
  const DrainStats({this.onPctPerH, this.offPctPerH, this.avgPctPerH, this.onlyAverage = false});
  final double? onPctPerH, offPctPerH, avgPctPerH;
  final bool onlyAverage;
  bool get isEmpty => onPctPerH == null && offPctPerH == null && avgPctPerH == null;
}

class History {
  const History({
    required this.from,
    required this.to,
    this.levels = const [],
    this.coverage = const [],
    this.sessions = const [],
    this.drain = const DrainStats(),
  });
  final int from, to;
  final List<LevelPoint> levels;
  final List<Coverage> coverage;
  final List<ChargeSessionInfo> sessions;
  final DrainStats drain;

  static History empty(int from, int to) => History(from: from, to: to);
}
