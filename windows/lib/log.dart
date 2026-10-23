import 'dart:async';
import 'dart:io';

/// Writes what the app is doing to a file, so a slow or missing picture can be looked at afterwards:
///   %LOCALAPPDATA%\Reel\reel.log
/// Fresh each time the app starts, and capped so it can't fill the disk.
class Log {
  static IOSink? _sink;
  static int _lines = 0;
  static const _maxLines = 40000;
  static final DateTime _t0 = DateTime.now();
  static String path = '';

  static void start() {
    try {
      final base = Platform.environment['LOCALAPPDATA'] ?? Directory.systemTemp.path;
      final dir = Directory('$base${Platform.pathSeparator}Reel');
      dir.createSync(recursive: true);
      path = '${dir.path}${Platform.pathSeparator}reel.log';
      _sink = File(path).openWrite(mode: FileMode.write);
      w('Reel started  ${DateTime.now()}');
      Timer.periodic(const Duration(seconds: 3), (_) => flush());
    } catch (_) {
      _sink = null;
    }
  }

  /// Milliseconds since the app started, so lines can be lined up against each other.
  static int get ms => DateTime.now().difference(_t0).inMilliseconds;

  static void w(String line) {
    final s = _sink;
    if (s == null || _lines > _maxLines) return;
    _lines++;
    try {
      s.writeln('${ms.toString().padLeft(7)}  $line');
    } catch (_) {}
  }

  static Future<void> flush() async {
    try {
      await _sink?.flush();
    } catch (_) {}
  }
}
