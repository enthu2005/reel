import 'dart:ffi';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';

import 'main.dart' as app;
import 'prefs.dart';

/// The soft interface sounds: a tick when the selection moves, a click on a press, a whoosh between
/// pages and a chime when the app starts. They are made in code (no sound files) and played through
/// Windows' own PlaySound, so they start at once. Settings -> Sound effects switches them all off.
class Sound {
  static const _rate = 44100;
  static final Map<String, Pointer<Uint8>> _wav = {};
  static DateTime _last = DateTime(2000);
  static int Function(Pointer<Void>, int, int)? _play;
  static bool _tried = false;

  static bool get _on => Prefs.sounds && !app.watching.value;

  static void tick() => _go('tick', gap: 45);
  static void click() => _go('click');
  static void whoosh() => _go('whoosh', gap: 250);
  static void chime() => _go('chime');

  static void _go(String name, {int gap = 0}) {
    if (!_on || !Platform.isWindows) return;
    final now = DateTime.now();
    if (now.difference(_last).inMilliseconds < gap) return;
    _last = now;
    try {
      if (!_tried) {
        _tried = true;
        final f = DynamicLibrary.open('winmm.dll').lookupFunction<
            Int32 Function(Pointer<Void>, IntPtr, Uint32),
            int Function(Pointer<Void>, int, int)>('PlaySoundW');
        _play = f;
      }
      final play = _play;
      if (play == null) return;
      final p = _wav.putIfAbsent(name, () => _alloc(_make(name)));
      // SND_ASYNC | SND_NODEFAULT | SND_MEMORY (the buffer is never freed, it must outlive the sound)
      play(p.cast<Void>(), 0, 0x1 | 0x2 | 0x4);
    } catch (_) {
      _play = null;
    }
  }

  static Pointer<Uint8> _alloc(Uint8List b) {
    final p = malloc<Uint8>(b.length);
    p.asTypedList(b.length).setAll(0, b);
    return p;
  }

  /// The WAV bytes of one sound (for tests).
  static Uint8List sample(String name) => _make(name);

  static Uint8List _make(String name) {
    switch (name) {
      case 'tick':
        return _wavOf(_tone([(1500, 0.10)], 0.018, decay: 9));
      case 'click':
        return _wavOf(_tone([(900, 0.22), (1800, 0.08)], 0.045, decay: 6));
      case 'whoosh':
        return _wavOf(_whoosh(0.26));
      default: // chime: two bells, a fifth apart
        final a = _tone([(659, 0.20), (1318, 0.05)], 1.1, decay: 3.2);
        final b = _tone([(988, 0.17), (1976, 0.04)], 1.1, decay: 3.2);
        final off = (_rate * 0.14).round();
        final out = Float64List(a.length + off);
        for (var i = 0; i < a.length; i++) {
          out[i] += a[i];
        }
        for (var i = 0; i < b.length; i++) {
          out[i + off] += b[i];
        }
        return _wavOf(out);
    }
  }

  /// Sine partials (hz, level) with a quick start and an exponential fade.
  static Float64List _tone(List<(double, double)> parts, double secs, {required double decay}) {
    final n = (_rate * secs).round();
    final out = Float64List(n);
    for (var i = 0; i < n; i++) {
      final t = i / _rate;
      final env = min(1.0, i / (_rate * 0.002)) * exp(-decay * t / secs);
      var s = 0.0;
      for (final (hz, lvl) in parts) {
        s += sin(2 * pi * hz * t) * lvl;
      }
      out[i] = s * env;
    }
    return out;
  }

  /// Noise through a low-pass whose cut-off rises and falls, with a soft swell.
  static Float64List _whoosh(double secs) {
    final n = (_rate * secs).round();
    final rnd = Random(7);
    final out = Float64List(n);
    var lp = 0.0;
    for (var i = 0; i < n; i++) {
      final x = i / n;
      final env = sin(pi * x) * sin(pi * x);
      final k = 0.03 + 0.22 * sin(pi * x); // the cut-off sweeps up and back down
      lp += (rnd.nextDouble() * 2 - 1 - lp) * k;
      out[i] = lp * env * 0.9;
    }
    return out;
  }

  static Uint8List _wavOf(Float64List s) {
    final data = ByteData(44 + s.length * 2);
    void str(int at, String v) {
      for (var i = 0; i < v.length; i++) {
        data.setUint8(at + i, v.codeUnitAt(i));
      }
    }

    str(0, 'RIFF');
    data.setUint32(4, 36 + s.length * 2, Endian.little);
    str(8, 'WAVEfmt ');
    data.setUint32(16, 16, Endian.little);
    data.setUint16(20, 1, Endian.little);
    data.setUint16(22, 1, Endian.little);
    data.setUint32(24, _rate, Endian.little);
    data.setUint32(28, _rate * 2, Endian.little);
    data.setUint16(32, 2, Endian.little);
    data.setUint16(34, 16, Endian.little);
    str(36, 'data');
    data.setUint32(40, s.length * 2, Endian.little);
    for (var i = 0; i < s.length; i++) {
      data.setInt16(44 + i * 2, (s[i].clamp(-1.0, 1.0) * 32000).round(), Endian.little);
    }
    return data.buffer.asUint8List();
  }
}
