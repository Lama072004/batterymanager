import 'dart:async';

import 'package:flutter/widgets.dart';

import 'backend.dart';
import 'model.dart';

/// Hält den UI-Zustand. Live-Werte nur, solange die App sichtbar ist (resumed):
/// beim Laden ≤ 1 Hz, beim Entladen alle 5 s, sonst 0 – keine Arbeit im Hintergrund.
class AppController extends ChangeNotifier with WidgetsBindingObserver {
  AppController(this.backend, {DateTime Function()? clock}) : _clock = clock ?? DateTime.now;

  final BatteryBackend backend;
  final DateTime Function() _clock;

  AppState? state;
  Live? live;
  SystemStatus status = const SystemStatus();
  History? history;
  bool stale = false;
  bool loading = true;
  bool week = false;
  int selectedSession = 0;

  Timer? _timer;
  int _historyForSample = -1;
  DateTime _historyAt = DateTime.fromMillisecondsSinceEpoch(0);
  bool _resumed = true;

  Future<void> start() async {
    WidgetsBinding.instance.addObserver(this);
    await refreshAll();
    _schedule();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _resumed = state == AppLifecycleState.resumed;
    if (_resumed) {
      refreshAll();
      _schedule();
    } else {
      _timer?.cancel();
    }
  }

  void _schedule() {
    _timer?.cancel();
    if (!_resumed) return;
    final period = (live?.charging ?? false) ? const Duration(seconds: 1) : const Duration(seconds: 5);
    _timer = Timer(period, () async {
      await _tick();
      _schedule();
    });
  }

  Future<void> refreshAll() async {
    final s = await backend.state();
    stale = s == null;
    state = s ?? state;
    status = await backend.systemStatus();
    if (s != null && s.onboarded && s.mode == AppState.modeAlways && !s.serviceRunning) {
      await backend.startService();
    }
    live = await backend.snapshot();
    await loadHistory();
    loading = false;
    notifyListeners();
  }

  Future<void> _tick() async {
    final l = await backend.snapshot();
    if (l == null) return;
    live = l;
    // Historie nur bei neuen Daten und höchstens alle 30 s neu laden.
    final s = await backend.state();
    if (s != null) state = s;
    final now = _clock();
    if (s != null && s.lastSampleId != _historyForSample && now.difference(_historyAt).inSeconds >= 30) {
      await loadHistory();
    }
    notifyListeners();
  }

  Future<void> loadHistory() async {
    final to = _clock().millisecondsSinceEpoch;
    final from = to - (week ? 7 * 24 : 24) * 3600 * 1000;
    history = await backend.history(from, to, mode: state?.mode ?? 0);
    _historyForSample = state?.lastSampleId ?? -1;
    _historyAt = _clock();
    if (selectedSession >= (history?.sessions.length ?? 0)) selectedSession = 0;
  }

  Future<void> setWeek(bool w) async {
    week = w;
    await loadHistory();
    notifyListeners();
  }

  void selectSession(int i) {
    selectedSession = i;
    notifyListeners();
  }

  Future<void> putSettings(Map<String, Object> changes) async {
    final s = await backend.putSettings(changes);
    if (s != null) state = s;
    if (changes.containsKey('mode') && state?.mode == AppState.modeAlways) await backend.startService();
    live = await backend.snapshot() ?? live;
    notifyListeners();
  }

  Future<void> setProfile(Map<String, Object> p) async {
    final s = await backend.setProfile(p);
    if (s != null) state = s;
    await loadHistory();
    notifyListeners();
  }

  Future<void> clearHistory() async {
    await backend.clearHistory();
    await loadHistory();
    notifyListeners();
  }

  Future<void> resetLimit() async {
    await backend.resetLimit();
    state = await backend.state() ?? state;
    live = await backend.snapshot() ?? live;
    notifyListeners();
  }

  Future<void> seedDemo() async {
    await backend.seedDemo();
    await loadHistory();
    notifyListeners();
  }

  Future<void> refreshStatus() async {
    status = await backend.systemStatus();
    notifyListeners();
  }
}
