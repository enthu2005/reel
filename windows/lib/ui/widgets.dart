import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../log.dart';
import '../plex.dart';
import 'net_image.dart';
import 'preview.dart';
import 'motion.dart';
import 'theme.dart';

/// Anything you can move to with the arrow keys (or a remote / controller) and press with Enter.
/// Keyboard only for now: the mouse doesn't move focus or press anything.
class Pressable extends StatefulWidget {
  final Widget Function(BuildContext context, bool focused) builder;
  final VoidCallback? onPressed;
  final VoidCallback? onLongPress;
  final ValueChanged<bool>? onFocusChange;
  final bool autofocus;
  final FocusNode? focusNode;
  final double scrollAlignment;

  const Pressable({
    super.key,
    required this.builder,
    this.onPressed,
    this.onLongPress,
    this.onFocusChange,
    this.autofocus = false,
    this.focusNode,
    this.scrollAlignment = 0.25,
  });

  @override
  State<Pressable> createState() => _PressableState();
}

class _PressableState extends State<Pressable> {
  late FocusNode _node = widget.focusNode ?? FocusNode();
  bool _focused = false;
  bool _byMouse = false;
  DateTime? _downAt;

  @override
  void didUpdateWidget(covariant Pressable old) {
    super.didUpdateWidget(old);
    if (widget.focusNode != null && widget.focusNode != _node)
      _node = widget.focusNode!;
  }

  @override
  void dispose() {
    if (widget.focusNode == null) _node.dispose();
    super.dispose();
  }

  KeyEventResult _onKey(FocusNode n, KeyEvent e) {
    // Alt+Enter is full screen (handled by the app), not a press
    if (HardwareKeyboard.instance.isAltPressed) return KeyEventResult.ignored;
    final enter = e.logicalKey == LogicalKeyboardKey.enter ||
        e.logicalKey == LogicalKeyboardKey.numpadEnter ||
        e.logicalKey == LogicalKeyboardKey.select ||
        e.logicalKey == LogicalKeyboardKey.gameButtonA ||
        e.logicalKey == LogicalKeyboardKey.space;
    if (!enter) return KeyEventResult.ignored;
    // Holding Enter (about half a second) is a long press, like holding OK on the Fire TV remote.
    if (e is KeyDownEvent) {
      _downAt = DateTime.now();
      return KeyEventResult.handled;
    }
    if (e is KeyRepeatEvent) {
      if (_downAt != null &&
          widget.onLongPress != null &&
          DateTime.now().difference(_downAt!) >
              const Duration(milliseconds: 500)) {
        _downAt = null;
        widget.onLongPress!();
      }
      return KeyEventResult.handled;
    }
    if (e is KeyUpEvent) {
      if (_downAt != null) widget.onPressed?.call();
      _downAt = null;
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final focus = Focus(
      focusNode: _node,
      autofocus: widget.autofocus,
      onKeyEvent: _onKey,
      onFocusChange: (f) {
        setState(() => _focused = f);
        if (f) SystemSound.play(SystemSoundType.click);
        widget.onFocusChange?.call(f);
        if (f && widget.scrollAlignment >= 0 && !_byMouse) {
          Scrollable.ensureVisible(context,
              alignment: widget.scrollAlignment,
              alignmentPolicy: ScrollPositionAlignmentPolicy.explicit,
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOutCubic);
        }
      },
      child: widget.builder(context, _focused),
    );
    // The mouse works too: moving over something selects it, a click presses it, holding clicks
    // and holds it. (Hover only counts when the mouse actually moves, so a list sliding under a
    // still mouse never steals the selection.)
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onHover: (e) {
        if (e.delta == Offset.zero || _node.hasFocus)
          return; // only when the mouse really moves
        _byMouse =
            true; // the list stays where it is; only keys scroll to what's selected
        _node.requestFocus();
        Future.delayed(
            const Duration(milliseconds: 80), () => _byMouse = false);
      },
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () {
          _byMouse = true;
          _node.requestFocus();
          Future.delayed(
              const Duration(milliseconds: 80), () => _byMouse = false);
          widget.onPressed?.call();
        },
        onLongPress: widget.onLongPress,
        child: focus,
      ),
    );
  }
}

class HoverButton extends StatefulWidget {
  final IconData icon;
  final double size;
  final String? tooltip;
  final VoidCallback onPressed;

  const HoverButton(this.icon,
      {super.key, required this.size, this.tooltip, required this.onPressed});

  @override
  State<HoverButton> createState() => _HoverButtonState();
}

class _HoverButtonState extends State<HoverButton> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final button = MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: GestureDetector(
        onTap: widget.onPressed,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 140),
          padding: EdgeInsets.all(widget.size * 0.18),
          decoration: BoxDecoration(
            color: _hovered ? Colors.white : const Color(0x66000000),
            shape: BoxShape.circle,
          ),
          child: Icon(widget.icon,
              size: widget.size * 0.58,
              color: _hovered ? Colors.black : Colors.white),
        ),
      ),
    );
    return widget.tooltip == null
        ? button
        : Tooltip(message: widget.tooltip!, child: button);
  }
}

/// A gentle cross-fade between pages (the preview video grows out of the card through it).
Route<T> fadeRoute<T>(Widget page) => PageRouteBuilder<T>(
      transitionDuration: Motion.pageFade,
      reverseTransitionDuration: Motion.pageFade,
      pageBuilder: (_, __, ___) => page,
      transitionsBuilder: (_, a, __, child) => FadeTransition(
          opacity: CurvedAnimation(parent: a, curve: Curves.easeOut),
          child: child),
    );

/// Picture and sound labels (4K, Dolby Vision, Dolby Atmos, DTS-HD MA, 5.1…) as small outlined tags.
class Badges extends StatelessWidget {
  final List<String> items;
  const Badges(this.items, {super.key});

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    if (items.isEmpty) return const SizedBox.shrink();
    return Wrap(spacing: h * 0.008, runSpacing: h * 0.008, children: [
      for (final b in items)
        Container(
          padding:
              EdgeInsets.symmetric(horizontal: h * 0.008, vertical: h * 0.002),
          decoration: BoxDecoration(
            border: Border.all(color: const Color(0xA6FFFFFF), width: 1.2),
            borderRadius: BorderRadius.circular(h * 0.006),
          ),
          child: Text(b,
              style: reelText(h * 0.0165,
                  weight: FontWeight.w700,
                  color: const Color(0xE6FFFFFF),
                  height: 1.3)),
        ),
    ]);
  }
}

/// Apple TV pill button: see-through at rest, solid white with dark text when focused.
class PillButton extends StatelessWidget {
  final String label;
  final IconData? icon;
  final VoidCallback onPressed;
  final bool primary;
  final bool autofocus;
  final FocusNode? focusNode;
  final ValueChanged<bool>? onFocusChange;
  final double scrollAlignment;

  const PillButton(this.label,
      {super.key,
      required this.onPressed,
      this.icon,
      this.primary = false,
      this.autofocus = false,
      this.focusNode,
      this.scrollAlignment = 0.0,
      this.onFocusChange});

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    return Pressable(
      autofocus: autofocus,
      focusNode: focusNode,
      onPressed: onPressed,
      onFocusChange: onFocusChange,
      scrollAlignment: scrollAlignment,
      builder: (c, f) => AnimatedScale(
        scale: f ? 1.08 : 1,
        duration: const Duration(milliseconds: 160),
        curve: Curves.easeOut,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          padding:
              EdgeInsets.symmetric(horizontal: h * 0.03, vertical: h * 0.016),
          constraints: BoxConstraints(minWidth: primary ? h * 0.18 : 0),
          decoration: BoxDecoration(
            color: f
                ? Colors.white
                : (primary ? const Color(0x4DFFFFFF) : Reel.fill),
            borderRadius: BorderRadius.circular(h),
            boxShadow: f
                ? [
                    BoxShadow(
                        color: Colors.black.withOpacity(0.45),
                        blurRadius: h * 0.03)
                  ]
                : null,
          ),
          child: Row(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (icon != null) ...[
                  Icon(icon,
                      size: h * 0.03, color: f ? Colors.black : Colors.white),
                  SizedBox(width: h * 0.01),
                ],
                Text(label,
                    style: reelText(h * 0.024,
                        weight: FontWeight.w600,
                        color: f ? Colors.black : Colors.white)),
              ]),
        ),
      ),
    );
  }
}

/// A round icon-only button that sits beside the pill buttons (same height).
class IconPill extends StatelessWidget {
  final IconData icon;
  final VoidCallback onPressed;
  final FocusNode? focusNode;
  final ValueChanged<bool>? onFocusChange;
  final double scrollAlignment;

  const IconPill(this.icon,
      {super.key,
      required this.onPressed,
      this.focusNode,
      this.onFocusChange,
      this.scrollAlignment = 0.0});

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final d = h * 0.03 + h * 0.016 * 2; // matches a PillButton's height
    return Pressable(
      focusNode: focusNode,
      onPressed: onPressed,
      onFocusChange: onFocusChange,
      scrollAlignment: scrollAlignment,
      builder: (c, f) => AnimatedScale(
        scale: f ? 1.08 : 1,
        duration: const Duration(milliseconds: 160),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          width: d,
          height: d,
          decoration: BoxDecoration(
            color: f ? Colors.white : Reel.fill,
            shape: BoxShape.circle,
            boxShadow: f
                ? [
                    BoxShadow(
                        color: Colors.black.withOpacity(0.45),
                        blurRadius: h * 0.03)
                  ]
                : null,
          ),
          child: Icon(icon,
              size: h * 0.028, color: f ? Colors.black : Colors.white),
        ),
      ),
    );
  }
}

/// A poster (or wide still) that lifts and catches the light when focused, with the title underneath.
class PosterCard extends StatelessWidget {
  final PlexItem item;
  final double width;
  final double height;
  final bool landscape;
  final bool showTitle;
  final String? subtitle;
  final VoidCallback onPressed;
  final VoidCallback? onLongPress;
  final ValueChanged<bool>? onFocusChange;
  final bool autofocus;
  final bool preview;

  /// Height of the title + subtitle under a card. Fixed, so rows can be sized exactly.
  static double textBlock(double h) => h * 0.064;

  const PosterCard({
    super.key,
    required this.item,
    required this.width,
    required this.height,
    required this.onPressed,
    this.landscape = true,
    this.showTitle = false,
    this.subtitle,
    this.onLongPress,
    this.onFocusChange,
    this.autofocus = false,
    this.preview = true,
  });

  static String? _imageOf(PlexItem item, bool landscape) {
    if (!landscape) return item.poster;
    if ((item.type == 'movie' || item.type == 'show') && item.art != null)
      return item.art;
    return item.thumb ?? item.poster;
  }

  /// Sizes rounded up to steps of 40 px, so the same picture in two rows is only fetched once.
  static int step(double v, double dpr) => ((v * dpr) / 40).ceil() * 40;

  /// Asks for at most 1.25x the on-screen size: sharp enough, and far fewer bytes on slow Wi-Fi.
  static String? imageUrl(
      PlexItem item, double width, double height, bool landscape, double dpr) {
    // One fixed size per kind of card, whatever the window size, so every poster is saved once and
    // found again (and can be saved ahead of time by Warmup).
    if (landscape && width > 520)
      return Plex.img(_imageOf(item, true), 1280, 720); // big spotlight cards
    return landscape
        ? Plex.img(_imageOf(item, true), 560, 315)
        : Plex.img(_imageOf(item, false), 400, 600);
  }

  String get _sub {
    if (subtitle != null) return subtitle!;
    final parts = <String>[];
    if (item.type == 'genre') return '';
    if (item.isSeerr) {
      if (item.year > 0) parts.add('${item.year}');
      if (item.note != null) parts.add(item.note!);
      return parts.join(' · ');
    }
    if (item.type == 'episode') {
      parts.add(
          '${item.parentIndex > 0 ? 'S${item.parentIndex}, ' : ''}E${item.index}');
    } else if (item.year > 0) {
      parts.add('${item.year}');
    }
    if (item.viewOffset > 0 && item.duration > 0) {
      final left = ((item.duration - item.viewOffset) / 60000).ceil();
      parts.add(left >= 60
          ? '${left ~/ 60} hr ${left % 60} min left'
          : '$left min left');
    }
    return parts.join(' · ');
  }

  /// A white tick on what you've finished, or a NEW pill on what arrived this week.
  Widget _mark(double h) {
    if (item.isWatched) {
      final d = h * 0.03;
      return Container(
        width: d,
        height: d,
        decoration: const BoxDecoration(
            color: Color(0xB3000000), shape: BoxShape.circle),
        child: Icon(Icons.check_rounded, color: Colors.white, size: d * 0.72),
      );
    }
    if (item.isNew) {
      return Container(
        padding: EdgeInsets.symmetric(horizontal: h * 0.009, vertical: h * 0.003),
        decoration: BoxDecoration(
            color: Colors.white, borderRadius: BorderRadius.circular(h)),
        child: Text('NEW',
            style: reelText(h * 0.014,
                weight: FontWeight.w800, color: Colors.black)),
      );
    }
    return const SizedBox.shrink();
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final radius = BorderRadius.circular(h * 0.014);
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final url = imageUrl(item, width, height, landscape, dpr);
    if (url == null) {
      ImageLoader.noPicture.add(item.displayTitle);
      Log.w(
          'NO POSTER for "${item.displayTitle}"  (thumb=${item.thumb} art=${item.art} gpThumb=${item.gpThumb})');
    }
    final progress = item.viewOffset > 0 && item.duration > 0
        ? item.viewOffset / item.duration
        : 0.0;
    final textOn = landscape || showTitle;
    final tb = textBlock(h);
    return Padding(
      padding: EdgeInsets.all(h * 0.011),
      child: Pressable(
        autofocus: autofocus,
        onPressed: onPressed,
        onLongPress: onLongPress,
        onFocusChange: onFocusChange,
        builder: (c, f) {
          // Only the focused card (or the one whose preview is open on another page) listens for the
          // preview; every other card stays put, so moving around never rebuilds the whole screen.
          if (f && preview) Preview.heroOwner = c;
          final heldNow = preview &&
              Preview.held(item.key) &&
              identical(Preview.heroOwner, c);
          final live = preview && (f || heldNow);
          Widget card(String? playing) {
            final held = heldNow;
            // While its preview plays, a poster card widens to 16:9 so the video fits.
            final wide = preview && (f || held) && !landscape;
            final cw = wide && !landscape ? height * 16 / 9 : width;
            final cardHeight = wide ? cw * 9 / 16 : height;
            // Only the width animates; heights are set straight away so nothing ever overflows.
            return SizedBox(
              height: cardHeight + (textOn ? tb : 0),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 320),
                curve: Curves.easeOutCubic,
                width: cw,
                child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      AnimatedScale(
                        scale: f ? (wide ? 1.06 : 1.1) : 1,
                        duration: const Duration(milliseconds: 180),
                        curve: Curves.easeOutCubic,
                        child: SizedBox(
                          height: cardHeight,
                          child: AnimatedContainer(
                            duration: const Duration(milliseconds: 320),
                            curve: Curves.easeOutCubic,
                            width: cw,
                            decoration: BoxDecoration(
                              borderRadius: radius,
                              color: Reel.card,
                              boxShadow: f
                                  ? [
                                      BoxShadow(
                                          color: const Color(0x99000000),
                                          blurRadius: h * 0.022,
                                          offset: Offset(0, h * 0.01))
                                    ]
                                  : null,
                            ),
                            child: ClipRRect(
                              borderRadius: radius,
                              child: Stack(fit: StackFit.expand, children: [
                                Padding(
                                  padding: EdgeInsets.all(width * 0.08),
                                  child: Center(
                                    child: Text(item.displayTitle,
                                        maxLines: 3,
                                        textAlign: TextAlign.center,
                                        overflow: TextOverflow.ellipsis,
                                        style: reelText(width * 0.1,
                                            weight: FontWeight.w600,
                                            color: Reel.text3)),
                                  ),
                                ),
                                if (url != null)
                                  NetImage(url,
                                      fade: false,
                                      decodeWidth: step(width,
                                          dpr.clamp(1.0, 1.25).toDouble())),
                                if (live)
                                  PreviewVideo.hero(item.key, active: true),
                                if (progress > 0)
                                  Positioned(
                                    left: width * 0.06,
                                    right: width * 0.06,
                                    bottom: cardHeight * 0.07,
                                    child: Container(
                                      height: (h * 0.0055).clamp(3.0, 8.0),
                                      decoration: BoxDecoration(
                                          color: const Color(0x59FFFFFF),
                                          borderRadius:
                                              BorderRadius.circular(4)),
                                      alignment: Alignment.centerLeft,
                                      child: FractionallySizedBox(
                                        widthFactor: progress.clamp(0.02, 1.0),
                                        child: Container(
                                            decoration: BoxDecoration(
                                                color: Colors.white,
                                                borderRadius:
                                                    BorderRadius.circular(4))),
                                      ),
                                    ),
                                  ),
                                if (!item.isSeerr && item.type != 'genre')
                                  Positioned(
                                    top: cardHeight * 0.07,
                                    right: width * 0.05,
                                    child: _mark(h),
                                  ),
                                // the light sheen on a focused card
                                if (f)
                                  const DecoratedBox(
                                    decoration: BoxDecoration(
                                      gradient: LinearGradient(
                                        begin: Alignment.topLeft,
                                        end: Alignment.bottomRight,
                                        colors: [
                                          Color(0x2EFFFFFF),
                                          Color(0x08FFFFFF),
                                          Color(0x00FFFFFF)
                                        ],
                                      ),
                                    ),
                                  ),
                              ]),
                            ),
                          ),
                        ),
                      ),
                      if (textOn)
                        // Slides down a little when the card grows, without changing the layout size.
                        AnimatedSlide(
                          duration: const Duration(milliseconds: 180),
                          curve: Curves.easeOutCubic,
                          offset: Offset(0, f ? (height * 0.05) / tb : 0),
                          child: SizedBox(
                            height: tb,
                            child: Padding(
                              padding: EdgeInsets.only(top: h * 0.012),
                              child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    SizedBox(
                                      height: h * 0.028,
                                      child: Text(
                                        item.type == 'episode' && !landscape
                                            ? item.title
                                            : item.displayTitle,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: reelText(h * 0.021,
                                            weight: FontWeight.w500,
                                            color: f
                                                ? Colors.white
                                                : Colors.white70,
                                            height: 1.25),
                                      ),
                                    ),
                                    if (_sub.isNotEmpty)
                                      SizedBox(
                                        height: h * 0.024,
                                        child: Text(_sub,
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                            style: reelText(h * 0.018,
                                                color: Reel.text2,
                                                height: 1.25)),
                                      ),
                                  ]),
                            ),
                          ),
                        ),
                    ]),
              ),
            );
          }

          // Always built the same way, whether or not this card has focus: swapping the shape of the
          // widgets underneath threw away the picture that had already been fetched and started again.
          return ValueListenableBuilder<String?>(
            valueListenable: Preview.showing,
            builder: (c2, playing, _) => card(live ? playing : null),
          );
        },
      ),
    );
  }
}

/// A titled horizontal row of cards.
class CardRow extends StatefulWidget {
  final String title;
  final List<PlexItem> items;
  final bool landscape;
  final bool titles;
  final void Function(PlexItem) onPressed;
  final void Function(PlexItem)? onFocused;
  final void Function(PlexItem)? onLongPress;
  final bool previews;
  final bool autofocusFirst;
  final bool big;
  final Duration previewDelay;

  static final Expando<bool> _warmed = Expando();

  const CardRow({
    super.key,
    required this.title,
    required this.items,
    required this.onPressed,
    this.onFocused,
    this.onLongPress,
    this.previews = true,
    this.autofocusFirst = false,
    this.big = false,
    this.previewDelay = Motion.previewDelay,
    this.landscape = true,
    this.titles = false,
  });

  @override
  State<CardRow> createState() => _CardRowState();
}

class _CardRowState extends State<CardRow> {
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _move(double amount) {
    if (!_scroll.hasClients) return;
    final target =
        (_scroll.offset + amount).clamp(0.0, _scroll.position.maxScrollExtent);
    _scroll.animateTo(target,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOutCubic);
  }

  @override
  Widget build(BuildContext context) {
    final h = Reel.h(context);
    final w = Reel.w(context);
    final ch =
        widget.big ? h * 0.40 : (widget.landscape ? h * 0.185 : h * 0.28);
    final cw = widget.landscape ? ch * 16 / 9 : ch * 2 / 3;
    final textH =
        (widget.landscape || widget.titles) ? PosterCard.textBlock(h) : 0.0;
    final padV =
        ch * 0.07; // room above and below for the 1.1x grow and its shadow
    // Fetch the whole row's pictures quietly in the background (once per list).
    if (CardRow._warmed[widget.items] != true) {
      CardRow._warmed[widget.items] = true;
      final dpr = MediaQuery.devicePixelRatioOf(context);
      // just the first couple of screens' worth: more would clog a slow connection
      final urls = widget.items
          .take(40)
          .map((it) => PosterCard.imageUrl(it, cw, ch, widget.landscape, dpr))
          .toList();
      // The first screenful of a row goes in the on-screen queue, not the background one, so a row
      // never arrives with empty cards at its far end.
      for (final u in urls.take(14)) {
        if (u != null) ImageLoader.load(u);
      }
      ImageLoader.prefetch(urls.skip(14));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Padding(
        padding: EdgeInsets.only(left: w * 0.045 + h * 0.011, top: h * 0.03),
        child: Text(widget.title,
            style: reelText(h * 0.03, weight: FontWeight.w600)),
      ),
      SizedBox(
        height: ch + textH + h * 0.022 + padV * 2 + 2,
        child: Stack(children: [
          ListView.builder(
            controller: _scroll,
            scrollDirection: Axis.horizontal,
            clipBehavior: Clip.none,
            // build well ahead so moving right with the keys always finds the next card
            cacheExtent: w * 2.5,
            padding:
                EdgeInsets.symmetric(horizontal: w * 0.045, vertical: padV),
            itemCount: widget.items.length,
            itemBuilder: (c, i) => Align(
              alignment: Alignment.topCenter,
              child: PosterCard(
                item: widget.items[i],
                width: cw,
                height: ch,
                landscape: widget.landscape,
                showTitle: widget.titles,
                preview: widget.previews,
                autofocus: widget.autofocusFirst && i == 0,
                onPressed: () {
                  if (widget.previews) Preview.handOff();
                  widget.onPressed(widget.items[i]);
                },
                // hold Enter: just the preview, full screen
                onLongPress: widget.onLongPress == null
                    ? null
                    : () {
                        Preview.handOff();
                        widget.onLongPress!(widget.items[i]);
                      },
                onFocusChange: (f) {
                  if (f) {
                    widget.onFocused?.call(widget.items[i]);
                    if (widget.previews)
                      Preview.focus(widget.items[i],
                          delay: widget.previewDelay);
                  } else if (widget.previews) {
                    Preview.blur(widget.items[i]);
                  }
                },
              ),
            ),
          ),
          Positioned(
              left: w * 0.012,
              top: 0,
              bottom: 0,
              child: Center(
                child: HoverButton(Icons.chevron_left_rounded,
                    size: h * 0.055,
                    tooltip: 'Previous',
                    onPressed: () => _move(-w * 0.65)),
              )),
          Positioned(
              right: w * 0.012,
              top: 0,
              bottom: 0,
              child: Center(
                child: HoverButton(Icons.chevron_right_rounded,
                    size: h * 0.055,
                    tooltip: 'Next',
                    onPressed: () => _move(w * 0.65)),
              )),
        ]),
      ),
    ]);
  }
}
