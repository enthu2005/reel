import 'dart:async';
import 'dart:io';
import 'package:media_kit/media_kit.dart';

import 'plex.dart';

/// `reel.exe --selftest`: finds every different kind of file in your libraries (container, picture codec,
/// size, sound codec and channels), opens one or two of each with the same player the app uses (no window,
/// no sound), and checks that it starts, that sound and picture decode, that the tracks load and that
/// seeking works. The report is %LOCALAPPDATA%\Reel\selftest.txt, written as it goes. It holds titles and
/// results only, never addresses or tokens.
class SelfTest {
  static late IOSink _out;

  static void _w(String s) {
    _out.writeln(s);
    stdout.writeln(s);
  }

  static String _clean(String s) =>
      s.replaceAll(RegExp(r'X-Plex-Token=[^&\s"]+'), 'X-Plex-Token=***').replaceAll(RegExp(r'https?://[^\s/]+'), '<server>');

  static String? only; // --only=<text>: just the files whose name has this in it

  static Future<void> run() async {
    final base = Platform.environment['LOCALAPPDATA'] ?? Directory.systemTemp.path;
    final file = File('$base${Platform.pathSeparator}Reel${Platform.pathSeparator}selftest.txt');
    _out = file.openWrite();
    _w('Reel self-test  ${DateTime.now()}');
    try {
      await _run();
    } catch (e) {
      _w('SELF-TEST STOPPED: ${_clean('$e')}');
    }
    await _out.flush();
    await _out.close();
  }

  static Future<void> _run() async {
    final secs = (await Plex.sections()).where((s) => s.type == 'movie' || s.type == 'show').toList();
    // every file as raw Plex JSON: movies from the library, episodes from the "episodes" view of shows
    final all = <Map<String, dynamic>>[];
    for (final s in secs) {
      final path = '/library/sections/${s.key}/all?${s.type == 'show' ? 'type=4&' : ''}sort=titleSort';
      final j = await Plex.getJson(Plex.paged(path, 20000));
      final list = (j['MediaContainer'] as Map)['Metadata'] as List? ?? const [];
      for (final m in list) {
        final o = Map<String, dynamic>.from(m as Map);
        o['_lib'] = s.title;
        all.add(o);
      }
      _w('library "${s.title}" (${s.type}): ${list.length} files');
    }

    // one group per kind of file
    final groups = <String, List<Map<String, dynamic>>>{};
    for (final m in all) {
      final media = m['Media'] as List?;
      if (media == null || media.isEmpty) continue;
      final x = media.first as Map;
      final res = '${x['videoResolution'] ?? '?'}';
      final sig = [
        '${x['container'] ?? '?'}',
        '${x['videoCodec'] ?? '?'}${(x['videoProfile'] ?? '').toString().isEmpty ? '' : '/${x['videoProfile']}'}',
        res,
        '${x['audioCodec'] ?? '?'} ${x['audioChannels'] ?? '?'}ch',
      ].join(' | ');
      groups.putIfAbsent(sig, () => []).add(m);
    }
    _w('${groups.length} different kinds of file found:');
    final keys = groups.keys.toList()..sort();
    for (final k in keys) {
      _w('  ${groups[k]!.length.toString().padLeft(5)} x $k');
    }
    _w('');

    // one of each kind: the middle-sized file, a normal one
    final picks = <(String, Map<String, dynamic>)>[];
    for (final k in keys) {
      final l = groups[k]!..sort((a, b) => _size(a).compareTo(_size(b)));
      picks.add((k, l[l.length ~/ 2]));
    }
    if (only != null) {
      final words = only!.toLowerCase().split(',');
      picks.retainWhere((e) => words.any((w) => '${e.$2['title']}'.toLowerCase().contains(w)));
    }
    _w('Testing ${picks.length} files');
    var ok = 0, bad = 0;
    final failed = <String>[];
    for (var i = 0; i < picks.length; i++) {
      final (sig, m) = picks[i];
      final name = '${m['grandparentTitle'] == null ? '' : '${m['grandparentTitle']} - '}${m['title']} (${m['_lib']})';
      _w('[${i + 1}/${picks.length}] $name');
      _w('    kind: $sig   size: ${(_size(m) / 1048576).round()} MB');
      final r = await _play(m);
      _w('    ${r.$1 ? 'OK' : 'FAILED'}: ${r.$2}');
      if (r.$1) {
        ok++;
      } else {
        bad++;
        failed.add('$name  [$sig]  ${r.$2}');
      }
    }
    _w('');
    _w('DONE: $ok worked, $bad failed');
    for (final f in failed) {
      _w('  FAILED: $f');
    }
  }

  static int _size(Map<String, dynamic> m) {
    try {
      return ((((m['Media'] as List).first as Map)['Part'] as List).first as Map)['size'] as int;
    } catch (_) {
      return 0;
    }
  }

  /// The server-converted stream (stereo sound) the app falls back to: does it start and play?
  static Future<(bool, String)> _fallback(String ratingKey) async {
    final session = 'reel-selftest-${DateTime.now().millisecondsSinceEpoch}';
    final p = Player(configuration: const PlayerConfiguration(vo: 'null', libass: true));
    final np = p.platform as NativePlayer;
    try {
      await np.setProperty('ao', 'null');
      await np.setProperty('vid', 'auto');
      await np.setProperty('tls-verify', 'no');
      await np.setProperty('network-timeout', '30');
      final t0 = Stopwatch()..start();
      final errs = <String>[];
      final sub = p.stream.error.listen((e) => errs.add(_clean(e)));
      const asked = '';
      await p.open(Media(Plex.stereoUrl(ratingKey, session)), play: true);
      try {
        await p.stream.position.firstWhere((x) => x > const Duration(seconds: 3)).timeout(const Duration(seconds: 60));
      } on TimeoutException {
        await sub.cancel();
        return (false, 'the converted stream did not play within 60 s ($asked${errs.isEmpty ? '' : '; player said: ${errs.first}'})');
      }
      await sub.cancel();
      return (true, 'converted stream plays (started in ${t0.elapsedMilliseconds ~/ 1000} s; $asked)');
    } catch (e) {
      return (false, _clean('$e'));
    } finally {
      try {
        await p.dispose();
      } catch (_) {}
      Plex.stopTranscode(session);
    }
  }

  /// Plays it for a few seconds, seeks into the middle, tries the other sound and a subtitle track.
  static Future<(bool, String)> _play(Map<String, dynamic> m) async {
    final part = (((m['Media'] as List).first as Map)['Part'] as List).first as Map;
    final url = Plex.raw(part['key']?.toString());
    if (url == null) return (false, 'no file address');
    final p = Player(
        configuration: const PlayerConfiguration(
            vo: 'null', bufferSize: 64 * 1024 * 1024, libass: true));
    final np = p.platform as NativePlayer;
    final errors = <String>[];
    final subs = <StreamSubscription>[p.stream.error.listen((e) => errors.add(_clean(e)))];
    final notes = <String>[];
    try {
      await np.setProperty('ao', 'null'); // no sound out, but it is still decoded
      await np.setProperty('vid', 'auto'); // and the picture too (a player with no window skips it otherwise)
      await np.setProperty('tls-verify', 'no');
      await np.setProperty('network-timeout', '30');
      await np.setProperty('hwdec', 'auto-copy-safe');
      final t0 = Stopwatch()..start();
      await p.open(Media(url), play: true);
      try {
        await p.stream.duration.firstWhere((d) => d > Duration.zero).timeout(const Duration(seconds: 40));
      } on TimeoutException {
        return (false, 'did not open within 40 s${errors.isEmpty ? '' : ' (${errors.first})'}');
      }
      final opened = t0.elapsedMilliseconds;
      await Future.delayed(const Duration(milliseconds: 800));
      final tr = p.state.tracks;
      final audio = tr.audio.where((t) => t.id != 'auto' && t.id != 'no').toList();
      final subsT = tr.subtitle.where((t) => t.id != 'auto' && t.id != 'no').toList();
      // the picture decoder takes a moment to start; wait for its first frame
      var vw = '';
      for (var i = 0; i < 20 && vw.isEmpty; i++) {
        vw = await np.getProperty('video-params/w').catchError((_) => '');
        if (vw.isEmpty) await Future.delayed(const Duration(milliseconds: 250));
      }
      final vh = await np.getProperty('video-params/h').catchError((_) => '');
      final vfmt = vw.isEmpty ? 'NO PICTURE' : '${vw}x$vh ${await np.getProperty('video-format').catchError((_) => '?')}';
      final hw = await np.getProperty('hwdec-current').catchError((_) => '?');
      final gamma = await np.getProperty('video-params/gamma').catchError((_) => '');
      final acodec = await np.getProperty('audio-codec-name').catchError((_) => '?');
      notes.add('opened in $opened ms; picture $vfmt${gamma.isEmpty ? '' : ' ($gamma)'}, decoder ${hw.isEmpty ? 'software' : hw}; sound $acodec; ${audio.length} sound track(s), ${subsT.length} subtitle(s)');

      // 1. plays on its own for a few seconds
      final p0 = p.state.position;
      await Future.delayed(const Duration(seconds: 4));
      final moved = p.state.position - p0;
      final dropped = (int.tryParse(await np.getProperty('frame-drop-count').catchError((_) => '0')) ?? 0) +
          (int.tryParse(await np.getProperty('decoder-frame-drop-count').catchError((_) => '0')) ?? 0);
      final fps = await np.getProperty('estimated-vf-fps').catchError((_) => '');
      notes.add('played ${moved.inMilliseconds / 1000}s in 4 s, ${fps.isEmpty ? '' : '${double.tryParse(fps)?.toStringAsFixed(0) ?? fps} fps, '}$dropped frames dropped');
      if (vw.isEmpty && (m['Media'] as List).isNotEmpty) {
        return (false, '${notes.join('; ')}; the picture never decoded${errors.isEmpty ? '' : ' (${errors.first})'}');
      }
      if (moved < const Duration(milliseconds: 2500)) {
        final why = errors.isEmpty ? '' : errors.first;
        // the app's own answer to sound this PC cannot decode (TrueHD, DTS-HD): a 2-channel copy from the server
        if (RegExp(r'audio|decoder|truehd|dts|spdif', caseSensitive: false).hasMatch(why)) {
          final fb = await _fallback('${m['ratingKey']}');
          return (fb.$1, '${notes.join('; ')}; the own sound of the file could not be decoded ($why); the app then switches to a 2-channel copy from the server: ${fb.$2}');
        }
        return (false, '${notes.join('; ')}; STALLED, it did not keep up${why.isEmpty ? '' : ' ($why)'}');
      }

      // 2. seek to the middle
      final mid = Duration(milliseconds: p.state.duration.inMilliseconds ~/ 2);
      final s = Stopwatch()..start();
      await p.seek(mid);
      var seekOk = false;
      for (var i = 0; i < 60; i++) {
        await Future.delayed(const Duration(milliseconds: 250));
        if ((p.state.position - mid).abs() < const Duration(seconds: 8) && !p.state.buffering) {
          seekOk = true;
          break;
        }
      }
      notes.add(seekOk ? 'seek ok in ${s.elapsedMilliseconds} ms' : 'SEEK FAILED');
      if (!seekOk) return (false, notes.join('; '));
      final p1 = p.state.position;
      await Future.delayed(const Duration(seconds: 2));
      if (p.state.position - p1 < const Duration(milliseconds: 1000)) {
        return (false, '${notes.join('; ')}; stopped after the seek');
      }

      // 3. the other sound track, and a subtitle track
      if (audio.length > 1) {
        await p.setAudioTrack(audio[1]);
        final q = p.state.position;
        await Future.delayed(const Duration(seconds: 2));
        final fine = p.state.position - q > const Duration(milliseconds: 1000);
        notes.add('second sound track ${fine ? 'ok' : 'STOPPED'}');
        if (!fine) return (false, notes.join('; '));
      }
      if (subsT.isNotEmpty) {
        await p.setSubtitleTrack(subsT.first);
        final q = p.state.position;
        final sw = Stopwatch()..start();
        var fine = false;
        while (sw.elapsed < const Duration(seconds: 15)) {
          await Future.delayed(const Duration(milliseconds: 250));
          if (p.state.position - q > const Duration(milliseconds: 1000)) {
            fine = true;
            break;
          }
        }
        notes.add('subtitle (${subsT.first.codec ?? '?'}) ${fine ? 'ok, playing again after ${(sw.elapsedMilliseconds / 1000).toStringAsFixed(1)} s' : 'STOPPED for 15 s'}');
        if (!fine) return (false, notes.join('; '));
      }
      if (errors.isNotEmpty) notes.add('player said: ${errors.take(2).join(' / ')}');
      // sound this PC cannot decode: the app switches to a 2-channel copy from the server, so prove that works too
      if (errors.any((e) => RegExp(r'audio|decoder|truehd|dts|spdif', caseSensitive: false).hasMatch(e))) {
        // what the app does: another sound track in the same language that this setup can play, else the server converts
        final item = await Plex.metadata('${m['ratingKey']}');
        final tracks = item.streamsOf(2);
        final cur = tracks.isEmpty ? null : tracks.firstWhere((x) => x.selected, orElse: () => tracks.first);
        var best = -1;
        if (cur != null) {
          for (var i = 0; i < tracks.length; i++) {
            final a = tracks[i];
            if (a.id == cur.id || !a.playable || a.languageCode != cur.languageCode) continue;
            if (best < 0 || a.channels > tracks[best].channels) best = i;
          }
        }
        if (best >= 0) {
          final real = p.state.tracks.audio.where((t) => t.id != 'auto' && t.id != 'no').toList();
          if (best < real.length) {
            await p.setAudioTrack(real[best]);
            final q = p.state.position;
            await Future.delayed(const Duration(seconds: 3));
            final fine = p.state.position - q > const Duration(milliseconds: 2000);
            final aname = await np.getProperty('audio-codec-name').catchError((_) => '?');
            notes.add('the app plays "${tracks[best].label}" instead (decoded as $aname): ${fine ? 'ok' : 'STOPPED'}');
            if (!fine) return (false, notes.join('; '));
          }
        } else {
          final fb = await _fallback('${m['ratingKey']}');
          notes.add('no other playable track in that language, so the server converts the sound: ${fb.$2}');
          if (!fb.$1) return (false, notes.join('; '));
        }
      }
      return (true, notes.join('; '));
    } catch (e) {
      return (false, '${notes.join('; ')}; ${_clean('$e')}');
    } finally {
      for (final s in subs) {
        await s.cancel();
      }
      try {
        await p.dispose();
      } catch (_) {}
    }
  }
}
