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
    this.deviceLimitPct,
    this.etaLearned = 0,
  });

  final int levelPm, status, plug, health, targetPct, profileSource;

  /// > 0: Restzeit aus dem Lernmodell, gelernt aus so vielen Ladevorgängen.
  final int etaLearned;

  /// Erkanntes Ladelimit des Geräts (z. B. Ladeschutz 90 %), null = unbekannt.
  final int? deviceLimitPct;
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
        deviceLimitPct: _i(m['deviceLimitPct']),
        etaLearned: _i(m['etaLearned']) ?? 0,
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
    this.liveChip = false,
    this.statusIcon = false,
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
    this.deviceLimitPct,
  });

  final int? deviceLimitPct;

  static const modeAlways = 0;
  static const modeChargingOnly = 1;

  final int targetPct, mode, heatWarnDc, sign, cells, profileSource, profileRev, lastSampleId, cpuMs, flushes, notifUpdates;
  final bool alarm, summary, onboarded, serviceRunning;

  /// Android 16+: Watt dauerhaft als Chip in der Statusleiste (sonst nur Symbol).
  final bool liveChip;

  /// Symbol der Lade-Benachrichtigung in der Statusleiste (aus = nur beim Herunterziehen sichtbar).
  final bool statusIcon;
  final String? dbPath;
  final double iToMa;

  factory AppState.fromMap(Map<Object?, Object?> m) => AppState(
        targetPct: _i(m['targetPct']) ?? 80,
        alarm: (m['alarm'] as bool?) ?? true,
        mode: _i(m['mode']) ?? 0,
        heatWarnDc: _i(m['heatWarnDc']) ?? 450,
        summary: (m['summary'] as bool?) ?? true,
        onboarded: (m['onboarded'] as bool?) ?? false,
        liveChip: (m['liveChip'] as bool?) ?? false,
        statusIcon: (m['statusIcon'] as bool?) ?? false,
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
        deviceLimitPct: _i(m['deviceLimitPct']),
      );
}

class SystemStatus {
  const SystemStatus(
      {this.notificationsEnabled = true, this.ignoringBatteryOptimizations = false, this.manufacturer = '', this.monitorChannelOff = false});
  final bool notificationsEnabled, ignoringBatteryOptimizations;

  /// Kanal „Hintergrunddienst“ in Android ausgeschaltet (Dienst läuft weiter, aber ohne Symbol oben).
  final bool monitorChannelOff;
  final String manufacturer;

  factory SystemStatus.fromMap(Map<Object?, Object?> m) => SystemStatus(
        notificationsEnabled: (m['notificationsEnabled'] as bool?) ?? true,
        ignoringBatteryOptimizations: (m['ignoringBatteryOptimizations'] as bool?) ?? false,
        manufacturer: (m['manufacturer'] as String?) ?? '',
        monitorChannelOff: (m['monitorChannelOff'] as bool?) ?? false,
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

  /// Ø-Leistung nur der aktiven Ladephase: zeitgewichtet, bis der Endstand erreicht ist
  /// (danach hält das Gerät nur noch – z. B. am Ladelimit – und würde den Ø verfälschen).
  double? get activeAvgW {
    final pts = points.where((p) => p.powerMw != null).toList();
    if (pts.length < 3) return avgW;
    final maxLv = pts.map((p) => p.levelPm).reduce((a, b) => a > b ? a : b);
    final holdIdx = pts.indexWhere((p) => p.levelPm >= maxLv - 5);
    final end = holdIdx < 1 ? pts.length - 1 : holdIdx;
    var e = 0.0, t = 0.0;
    for (var i = 1; i <= end; i++) {
      final dt = (pts[i].tOffS - pts[i - 1].tOffS).toDouble();
      if (dt <= 0) continue;
      e += (pts[i - 1].powerMw! + pts[i].powerMw!) / 2 * dt;
      t += dt;
    }
    return t <= 0 ? avgW : e / t / 1000.0;
  }

  /// Spitze aus dem gleitenden Median über drei Punkte (einzelne Messspitzen zählen nicht).
  int? get smoothPeakMw {
    final w = points.map((p) => p.powerMw).whereType<int>().toList();
    if (w.length < 3) return peakMw;
    var best = 0;
    for (var i = 1; i + 1 < w.length; i++) {
      final m = ([w[i - 1], w[i], w[i + 1]]..sort())[1];
      if (m > best) best = m;
    }
    return best;
  }

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

/// Eine Kapazitätsmessung (health_point).
class HealthPoint {
  const HealthPoint(this.ts, this.capacityMah, {this.designMah, this.cycles});
  final int ts;
  final double capacityMah;
  final double? designMah;
  final int? cycles;
}

/// Trend der Akku-Gesundheit: robuste Gerade (Theil-Sen) durch die Kapazitätsmessungen.
class HealthTrend {
  const HealthTrend({required this.nowMah, this.designMah, this.slopeMahPerDay, this.reach80Ts, this.days = 0, this.n = 0});
  final double nowMah;
  final double? designMah;

  /// mAh pro Tag (negativ = Verlust); null = zu wenige Daten für eine Prognose.
  final double? slopeMahPerDay;

  /// Voraussichtlicher Zeitpunkt, an dem 80 % der Nennkapazität erreicht sind.
  final int? reach80Ts;
  final int days, n;

  double? get healthPct => designMah == null || designMah! <= 0 ? null : nowMah / designMah! * 100;

  /// Verlust in Prozentpunkten von [ref] pro Monat.
  double? lossPctPerMonth(double ref) => slopeMahPerDay == null ? null : -slopeMahPerDay! * 30.44 / ref * 100;

  static const _day = 86400000;

  /// null, wenn es keine Messung gibt. Prognose erst ab 4 Messungen über ≥ 14 Tage.
  static HealthTrend? of(List<HealthPoint> pts) {
    if (pts.isEmpty) return null;
    final sorted = [...pts]..sort((a, b) => a.ts.compareTo(b.ts));
    final design = sorted.reversed.map((p) => p.designMah).whereType<double>().firstOrNull;
    final days = ((sorted.last.ts - sorted.first.ts) / _day).round();
    // Aktueller Wert: Median der letzten bis zu 5 Messungen (eine einzelne Messung schwankt ±2 %).
    final recent = sorted.sublist(sorted.length > 5 ? sorted.length - 5 : 0).map((p) => p.capacityMah).toList()..sort();
    final median = recent[recent.length ~/ 2];
    if (sorted.length < 4 || days < 14) {
      return HealthTrend(nowMah: median, designMah: design, days: days, n: sorted.length);
    }
    final slopes = <double>[];
    for (var i = 0; i < sorted.length; i++) {
      for (var j = i + 1; j < sorted.length; j++) {
        final dt = (sorted[j].ts - sorted[i].ts) / _day;
        if (dt >= 1) slopes.add((sorted[j].capacityMah - sorted[i].capacityMah) / dt);
      }
    }
    if (slopes.isEmpty) return HealthTrend(nowMah: median, designMah: design, days: days, n: sorted.length);
    slopes.sort();
    final slope = slopes[slopes.length ~/ 2];
    final now = (sorted.map((p) => p.capacityMah - slope * (p.ts - sorted.last.ts) / _day).toList()..sort())[sorted.length ~/ 2];
    int? reach;
    final ref = design ?? sorted.first.capacityMah;
    if (slope < 0 && now > 0.8 * ref) {
      final d = (0.8 * ref - now) / slope;
      if (d < 20 * 365) reach = sorted.last.ts + (d * _day).round();
    }
    return HealthTrend(nowMah: now, designMah: design, slopeMahPerDay: slope, reach80Ts: reach, days: days, n: sorted.length);
  }
}

class History {
  const History({
    required this.from,
    required this.to,
    int? levelFrom,
    this.levels = const [],
    this.coverage = const [],
    this.sessions = const [],
    this.drain = const DrainStats(),
    this.health = const [],
  }) : levelFrom = levelFrom ?? from;

  /// Fenster für Ladevorgänge und Verbrauch (24 Std. / 7 Tage).
  final int from, to;

  /// Ab hier sind Verlaufsdaten geladen (zum Zurückscrollen im Akkustand-Graphen).
  final int levelFrom;
  final List<HealthPoint> health;
  final List<LevelPoint> levels;
  final List<Coverage> coverage;
  final List<ChargeSessionInfo> sessions;
  final DrainStats drain;

  static History empty(int from, int to) => History(from: from, to: to);
}
