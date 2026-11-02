import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:reel/plex.dart';
import 'package:reel/prefs.dart';
import 'package:reel/screens/settings.dart';
import 'package:reel/screens/speedtest.dart';
import 'package:reel/sound.dart';
import 'package:reel/ui/stream_graph.dart';
import 'package:reel/ui/theme.dart';
import 'package:reel/ui/title_card.dart';
import 'package:reel/ui/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

final shots = Directory(Platform.environment['SHOTS'] ?? 'build/shots');
HttpServer? fake;

Future<void> shot(WidgetTester t, String name, GlobalKey key) async {
  await t.runAsync(() async {
    final b = key.currentContext!.findRenderObject() as RenderRepaintBoundary;
    final img = await b.toImage(pixelRatio: 1);
    final bytes = (await img.toByteData(format: ui.ImageByteFormat.png))!;
    shots.createSync(recursive: true);
    File('${shots.path}/$name.png').writeAsBytesSync(bytes.buffer.asUint8List());
  });
}

Widget host(GlobalKey k, Widget child) => MaterialApp(
      theme: reelTheme(),
      home: RepaintBoundary(key: k, child: Scaffold(backgroundColor: Colors.black, body: child)),
    );

/// A tiny stand-in for a Plex server: sections, a film list, one film with a big file, ranges.
Future<HttpServer> startFake() async {
  final s = await HttpServer.bind('127.0.0.1', 0);
  final big = Uint8List(400 * 1024 * 1024 ~/ 8); // 50 MB of zeros, repeated by ranges
  s.listen((r) async {
    final p = r.uri.path;
    Future<void> json(Object o) async {
      r.response.headers.contentType = ContentType.json;
      r.response.write(jsonEncode(o));
      await r.response.close();
    }

    if (p == '/identity') return json({'MediaContainer': {'machineIdentifier': 'x'}});
    if (p == '/library/sections') {
      return json({
        'MediaContainer': {
          'Directory': [
            {'key': '1', 'type': 'movie', 'title': 'Films', 'Location': []}
          ]
        }
      });
    }
    Map<String, dynamic> film() => {
          'ratingKey': '10',
          'type': 'movie',
          'title': 'Test Film',
          'year': 2020,
          'Media': [
            {
              'bitrate': 20000,
              'Part': [
                {'key': '/library/parts/5/file.mkv', 'size': 200 * 1024 * 1024}
              ]
            }
          ]
        };
    if (p == '/library/sections/1/all') {
      return json({'MediaContainer': {'Metadata': [film()]}});
    }
    if (p.startsWith('/library/metadata/')) {
      return json({'MediaContainer': {'Metadata': [film()]}});
    }
    if (p.startsWith('/library/parts/')) {
      r.response.statusCode = 206;
      r.response.headers.contentType = ContentType.binary;
      // stream for as long as the client keeps reading
      try {
        for (var i = 0; i < 200; i++) {
          r.response.add(big);
          await r.response.flush();
        }
      } catch (_) {}
      try {
        await r.response.close();
      } catch (_) {}
      return;
    }
    r.response.statusCode = 404;
    await r.response.close();
  });
  return s;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    HttpOverrides.global = null;
    fake = await startFake();
    SharedPreferences.setMockInitialValues({
      'server': 'http://127.0.0.1:${fake!.port}',
      'token': 'abc',
    });
    await Prefs.load();
    final loader = FontLoader('Inter');
    for (final f in ['regular', 'medium', 'semibold', 'bold']) {
      final file = File('assets/fonts/inter_$f.ttf');
      if (file.existsSync()) loader.addFont(Future.value(ByteData.view(file.readAsBytesSync().buffer)));
    }
    await loader.load();
  });

  tearDownAll(() async => fake?.close(force: true));

  test('sounds are valid, audible WAV files', () {
    for (final n in ['tick', 'click', 'whoosh', 'chime']) {
      final b = Sound.sample(n);
      expect(String.fromCharCodes(b.sublist(0, 4)), 'RIFF');
      expect(String.fromCharCodes(b.sublist(8, 12)), 'WAVE');
      final d = ByteData.view(b.buffer);
      expect(d.getUint32(40, Endian.little), b.length - 44);
      var peak = 0;
      for (var i = 44; i < b.length; i += 2) {
        final v = d.getInt16(i, Endian.little).abs();
        if (v > peak) peak = v;
      }
      expect(peak, greaterThan(500), reason: '$n is silent');
      expect(peak, lessThan(32000), reason: '$n clips');
      // ends quietly (no click at the end)
      expect(d.getInt16(b.length - 2, Endian.little).abs(), lessThan(1500), reason: '$n ends loud');
    }
  });

  testWidgets('title card fades in, holds, fades out and finishes', (t) async {
    await t.binding.setSurfaceSize(const Size(1920, 1080));
    final k = GlobalKey();
    var done = false;
    await t.pumpWidget(host(
        k,
        Stack(children: [
          Container(color: const Color(0xFF203040)),
          Positioned.fill(
              child: TitleCard(
                  title: 'The Long Title of a Film', line: '2021', onDone: () => done = true)),
        ])));
    await t.pump(const Duration(milliseconds: 300));
    await shot(t, 'titlecard_in', k);
    await t.pump(const Duration(milliseconds: 2200));
    await shot(t, 'titlecard_hold', k);
    expect(done, false);
    await t.pump(const Duration(milliseconds: 3000));
    expect(done, true);
    expect(tester(t), isNull);
  });

  testWidgets('title card copes with a very long title and no year', (t) async {
    await t.binding.setSurfaceSize(const Size(1280, 720));
    final k = GlobalKey();
    await t.pumpWidget(host(
        k,
        TitleCard(
            title: 'An Extremely Long Film Title That Goes On And On And On Forever And Ever Amen',
            line: '',
            onDone: () {})));
    await t.pump(const Duration(milliseconds: 2000));
    await shot(t, 'titlecard_long', k);
    expect(t.takeException(), isNull);
    await t.pump(const Duration(seconds: 5));
  });

  testWidgets('stream graph chart paints with data, empty and spikes', (t) async {
    await t.binding.setSurfaceSize(const Size(900, 500));
    final k = GlobalKey();
    final data = [for (var i = 0; i < 60; i++) 20 + 10 * (i % 7) + (i == 40 ? 120 : 0).toDouble()];
    Widget chart(List<double> d, Color c, {bool bars = false}) => SizedBox(
        height: 80,
        width: 800,
        child: CustomPaint(painter: ChartPainter(d, c, 60, bars, 5)));
    await t.pumpWidget(host(
        k,
        Padding(
            padding: const EdgeInsets.all(40),
            child: Column(children: [
              chart(data, Reel.accent),
              const SizedBox(height: 20),
              chart(const [], Colors.green),
              const SizedBox(height: 20),
              chart(const [0, 0, 3, 0, 0, 1, 0, 8, 0], Colors.red, bars: true),
              const SizedBox(height: 20),
              chart(const [4], Colors.cyan),
            ]))));
    expect(t.takeException(), isNull);
    await shot(t, 'graph', k);
  });

  testWidgets('poster card: tilt follows the mouse, no errors, flat again on exit', (t) async {
    await t.binding.setSurfaceSize(const Size(1280, 720));
    final k = GlobalKey();
    final it = PlexItem()
      ..key = '1'
      ..type = 'movie'
      ..title = 'Some Film'
      ..year = 2019
      ..viewOffset = 600000
      ..duration = 6000000;
    await t.pumpWidget(host(
        k,
        Center(
            child: PosterCard(
                item: it, width: 400, height: 225, landscape: true, preview: false, onPressed: () {}))));
    final g = await t.createGesture(kind: PointerDeviceKind.mouse);
    await g.addPointer(location: const Offset(10, 10));
    await g.moveTo(t.getCenter(find.byType(PosterCard)));
    await t.pump(const Duration(milliseconds: 400));
    await g.moveTo(t.getTopLeft(find.byType(PosterCard)) + const Offset(40, 30));
    await t.pump(const Duration(milliseconds: 16));
    await t.pump(const Duration(milliseconds: 400));
    await shot(t, 'tilt_topleft', k);
    await g.moveTo(t.getBottomRight(find.byType(PosterCard)) - const Offset(40, 30));
    await t.pump(const Duration(milliseconds: 16));
    await t.pump(const Duration(milliseconds: 400));
    await shot(t, 'tilt_bottomright', k);
    await g.moveTo(const Offset(5, 5));
    await t.pump(const Duration(milliseconds: 600));
    expect(t.takeException(), isNull);
    await g.removePointer();
  });

  testWidgets('card row keeps its scroll spot when built away and back', (t) async {
    await t.binding.setSurfaceSize(const Size(1280, 720));
    final items = [
      for (var i = 0; i < 60; i++)
        PlexItem()
          ..key = '$i'
          ..type = 'movie'
          ..title = 'Film $i'
    ];
    final show = ValueNotifier(true);
    final k = GlobalKey();
    await t.pumpWidget(host(
        k,
        ValueListenableBuilder<bool>(
            valueListenable: show,
            builder: (c, on, _) => on
                ? CardRow(
                    key: const ValueKey('a'),
                    memory: 'tab_Row',
                    title: 'Row',
                    items: items,
                    previews: false,
                    onPressed: (_) {})
                : const SizedBox())));
    final list = find.byType(ListView).first;
    await t.drag(list, const Offset(-1500, 0));
    await t.pumpAndSettle();
    final before = (t.state<ScrollableState>(find.descendant(of: list, matching: find.byType(Scrollable)).first)).position.pixels;
    expect(before, greaterThan(500));
    show.value = false; // the row is thrown away (scrolled far off screen in Home)
    await t.pump();
    show.value = true;
    await t.pump();
    final after = (t.state<ScrollableState>(find.descendant(of: find.byType(ListView).first, matching: find.byType(Scrollable)).first)).position.pixels;
    expect(after, closeTo(before, 1));
    // a row with another name starts at the left
    expect(t.takeException(), isNull);
  });

  testWidgets('speed test against a stand-in server: finds a film, measures, gives a verdict', (t) async {
    await t.binding.setSurfaceSize(const Size(1920, 1080));
    final k = GlobalKey();
    await t.pumpWidget(host(k, const SpeedTestScreen()));
    await t.pump();
    await shot(t, 'speed_idle', k);
    await t.tap(find.text('Start'));
    await t.pump();
    // real network and real time: wait for it to finish (7 s + 6 s + lookups)
    for (var i = 0; i < 60 && find.text('Test again').evaluate().isEmpty; i++) {
      await t.runAsync(() => Future.delayed(const Duration(milliseconds: 500)));
      await t.pump();
      if (i == 8) await shot(t, 'speed_running', k);
    }
    await shot(t, 'speed_done', k);
    expect(find.text('Test again'), findsOneWidget, reason: 'the test never finished');
    expect(find.textContaining('One stream'), findsOneWidget);
    expect(find.textContaining('Reply time'), findsOneWidget);
    expect(t.takeException(), isNull);
    await t.pumpWidget(const SizedBox()); // leave the page, then let idle connection timers run out
    await t.pump(const Duration(seconds: 100));
  }, timeout: const Timeout(Duration(minutes: 2)));

  testWidgets('speed test says so plainly when the server is unreachable', (t) async {
    await t.binding.setSurfaceSize(const Size(1280, 720));
    SharedPreferences.setMockInitialValues({'server': 'http://127.0.0.1:1', 'token': 'abc'});
    await Prefs.load();
    final k = GlobalKey();
    await t.pumpWidget(host(k, const SpeedTestScreen()));
    await t.tap(find.text('Start'));
    for (var i = 0; i < 30 && find.text('Start').evaluate().isEmpty; i++) {
      await t.runAsync(() => Future.delayed(const Duration(milliseconds: 300)));
      await t.pump();
    }
    await shot(t, 'speed_error', k);
    expect(find.text('Start'), findsOneWidget);
    expect(t.takeException(), isNull);
    await t.pumpWidget(const SizedBox());
    await t.pump(const Duration(seconds: 100));
    SharedPreferences.setMockInitialValues({'server': 'http://127.0.0.1:${fake!.port}', 'token': 'abc'});
    await Prefs.load();
  });

  testWidgets('settings page builds and shows the new rows', (t) async {
    await t.binding.setSurfaceSize(const Size(1920, 1080));
    final k = GlobalKey();
    await t.pumpWidget(host(k, const SettingsScreen()));
    await t.pump(const Duration(milliseconds: 300));
    expect(find.text('Sound effects'), findsOneWidget);
    expect(find.text('Title cards'), findsOneWidget);
    expect(find.text('Speed to your server'), findsOneWidget);
    expect(t.takeException(), isNull);
  });
}

Object? tester(WidgetTester t) => t.takeException();
