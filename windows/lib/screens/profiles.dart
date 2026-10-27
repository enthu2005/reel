import 'package:flutter/material.dart';

import '../log.dart';
import '../plex.dart';
import '../ui/net_image.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'home.dart';
import 'login.dart';

class ProfilesScreen extends StatefulWidget {
  const ProfilesScreen({super.key});

  @override
  State<ProfilesScreen> createState() => _ProfilesScreenState();
}

class _ProfilesScreenState extends State<ProfilesScreen> {
  List<PlexHomeUser> _users = [];
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final users = await Plex.homeUsers();
      if (!mounted) return;
      if (users.length <= 1) {
        // Not a Plex Home (or only one profile): nothing to choose, go straight in
        Navigator.of(context).pushReplacement(fadeRoute(const HomeScreen()));
        return;
      }
      setState(() {
        _users = users;
        _loading = false;
      });
    } catch (e) {
      final msg = '$e';
      Log.w('profiles: $msg');
      if (mounted) {
        // A shared (friend) account has no Plex Home profiles: just continue with its own sign-in
        Navigator.of(context).pushReplacement(fadeRoute(const HomeScreen()));
        return;
      }
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'Could not load Plex Home profiles.\n$e';
        });
      }
    }
  }

  Future<void> _select(PlexHomeUser user) async {
    try {
      await Plex.switchHomeUser(user);
      if (!mounted) return;
      Navigator.of(context).pushReplacement(fadeRoute(const HomeScreen()));
    } catch (e) {
      if (mounted) {
        setState(() => _error = 'Could not select ${user.title}.\n$e');
      }
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
              colors: [
                Color(0xFF241A5C),
                Color(0xFF5A2486),
                Color(0xFF0A0814)
              ]),
        ),
        child: Center(
          child: _loading
              ? const CircularProgressIndicator(color: Colors.white)
              : _error != null
                  ? Column(mainAxisSize: MainAxisSize.min, children: [
                      Text(_error!,
                          textAlign: TextAlign.center,
                          style: reelText(h * 0.024, color: Reel.text2)),
                      SizedBox(height: h * 0.03),
                      PillButton('Try again', onPressed: _load),
                      SizedBox(height: h * 0.015),
                      PillButton('Sign out',
                          onPressed: () => Navigator.of(context)
                              .pushReplacement(MaterialPageRoute(
                                  builder: (_) => const LoginScreen()))),
                    ])
                  : Column(mainAxisSize: MainAxisSize.min, children: [
                      Text('Who’s watching?',
                          style: reelText(h * 0.055, weight: FontWeight.w700)),
                      SizedBox(height: h * 0.04),
                      Wrap(
                        spacing: h * 0.035,
                        runSpacing: h * 0.035,
                        alignment: WrapAlignment.center,
                        children: [
                          for (var i = 0; i < _users.length; i++)
                            _profile(_users[i], h, i == 0),
                        ],
                      ),
                    ]),
        ),
      ),
    );
  }

  Widget _profile(PlexHomeUser user, double h, bool first) {
    final image = user.thumb == null ? null : Plex.img(user.thumb, 240, 240);
    return Pressable(
      autofocus: first,
      onPressed: () => _select(user),
      builder: (context, focused) => AnimatedScale(
        scale: focused ? 1.08 : 1,
        duration: const Duration(milliseconds: 160),
        child: SizedBox(
          width: h * 0.18,
          child: Column(children: [
            Container(
              width: h * 0.14,
              height: h * 0.14,
              clipBehavior: Clip.antiAlias,
              decoration: BoxDecoration(
                color: Reel.card,
                shape: BoxShape.circle,
                border: Border.all(
                    color: focused ? Colors.white : Colors.transparent,
                    width: h * 0.006),
              ),
              child: image == null
                  ? Icon(Icons.person_rounded,
                      size: h * 0.08, color: Reel.text2)
                  : NetImage(image, fade: false),
            ),
            SizedBox(height: h * 0.015),
            Text(user.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: reelText(h * 0.025, weight: FontWeight.w600)),
          ]),
        ),
      ),
    );
  }
}
