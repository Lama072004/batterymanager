// Deutsche Formatierung: Dezimalkomma, schmales geschütztes Leerzeichen (U+202F) vor Einheiten.
const nnbsp = ' ';

String _dec(double v, int digits) => v.toStringAsFixed(digits).replaceAll('.', ',');

String _thousands(int v) {
  final s = v.abs().toString();
  final b = StringBuffer(v < 0 ? '−' : '');
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) b.write('.');
    b.write(s[i]);
  }
  return b.toString();
}

String fmtPct(int p) => '$p$nnbsp%';

String fmtWatts(int mw) {
  final w = mw / 1000.0;
  final s = w.abs() >= 10 ? w.round().toString() : _dec(w, 1);
  return '${s.replaceFirst('-', '−')}${nnbsp}W';
}

String fmtMa(int ma) => '${_thousands(ma)}${nnbsp}mA';

String fmtVolt(int mv) => '${_dec(mv / 1000.0, 2)}${nnbsp}V';

String fmtTemp(int dc) => '${_dec(dc / 10.0, 1)}$nnbsp°C';

String fmtMah(int mah) => '${_thousands(mah)}${nnbsp}mAh';

String fmtRate(double pctPerH) => '${_dec(pctPerH, pctPerH < 10 ? 1 : 0)}$nnbsp%/h';

/// Gerundete Dauer in [step]-Minuten-Stufen („25 Min.“, „1 Std. 10 Min.“).
String fmtDuration(int seconds, {int step = 5}) {
  var m = ((seconds / 60.0) / step).round() * step;
  if (m < step) m = step;
  if (m < 60) return '$m${nnbsp}Min.';
  final h = m ~/ 60, r = m % 60;
  return r == 0 ? '$h${nnbsp}Std.' : '$h${nnbsp}Std. $r${nnbsp}Min.';
}

String _two(int v) => v.toString().padLeft(2, '0');

String fmtClock(DateTime t) => '${_two(t.hour)}:${_two(t.minute)}';

String fmtClockUtc(int utcMs) => fmtClock(DateTime.fromMillisecondsSinceEpoch(utcMs));

const weekdays = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];

String healthLabel(int h) => switch (h) {
      2 => 'Gut',
      3 => 'Überhitzt',
      4 => 'Defekt',
      5 => 'Überspannung',
      6 => 'Fehler',
      7 => 'Zu kalt',
      _ => '',
    };
