import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../plex.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'profiles.dart';

/// Sign in the Plex way: show a short code, you enter it at plex.tv/link.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  String? _code;
  String _status = 'Getting a code…';
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _start();
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _start() async {
    _poll?.cancel();
    try {
      final pin = await Plex.createPin();
      final id = (pin['id'] as num).toInt();
      setState(() {
        _code = pin['code']?.toString();
        _status = 'Waiting for you to enter the code…';
      });
      _poll = Timer.periodic(const Duration(seconds: 2), (t) async {
        try {
          final token = await Plex.pollPin(id);
          if (token == null) return;
          t.cancel();
          Plex.signIn(token);
          setState(() => _status = 'Signed in. Finding your server…');
          await Plex.findServer();
          if (!mounted) return;
          Navigator.of(context).pushReplacement(
              MaterialPageRoute(builder: (_) => const ProfilesScreen()));
        } catch (e) {
          if (mounted) setState(() => _status = 'Problem: $e');
        }
      });
    } catch (e) {
      setState(() => _status = "Couldn't reach plex.tv: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xFF241A5C), Color(0xFF5A2486), Color(0xFF0A0814)],
          ),
        ),
        child: Center(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text('Reel', style: reelText(h * 0.09, weight: FontWeight.w700)),
            SizedBox(height: h * 0.04),
            Text('Go to plex.tv/link and enter this code',
                style: reelText(h * 0.032, color: Reel.text2)),
            SizedBox(height: h * 0.03),
            Text(_code ?? '••••',
                style: reelText(h * 0.12, weight: FontWeight.w700)
                    .copyWith(letterSpacing: h * 0.02)),
            SizedBox(height: h * 0.03),
            Text(_status, style: reelText(h * 0.024, color: Reel.text2)),
            SizedBox(height: h * 0.05),
            Row(mainAxisSize: MainAxisSize.min, children: [
              PillButton('Open plex.tv/link',
                  primary: true,
                  autofocus: true,
                  onPressed: () =>
                      launchUrl(Uri.parse('https://plex.tv/link'))),
              SizedBox(width: h * 0.02),
              PillButton('New code', onPressed: _start),
            ]),
          ]),
        ),
      ),
    );
  }
}
