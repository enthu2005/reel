package com.enthu.reel;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

/**
 * Soft interface sounds, generated in code (no sound files). Switch off in Settings.
 * Each sound is a few short notes that start gently and die away quickly: a light tick for moving, a rising
 * two-note sound for choosing, a low pat at an edge. Five styles to pick from in Settings.
 */
final class SoundFx {
    private static final int RATE = 44100;
    private static AudioTrack move;
    private static AudioTrack click;
    private static AudioTrack edge;
    private static boolean enabled = true;
    private static boolean ready = false;
    private static int style = -1;
    private static long lastMove = 0;

    static final String[] STYLE_NAMES = {"Chime", "Glass", "Wood", "Marimba", "Retro"};

    private SoundFx() {
    }

    /** One note: pitch, when it starts, how loud (0..1) and how fast it fades. */
    private static final class Note {
        final double hz;
        final int startMs;
        final double gain;
        final double decayMs;
        final boolean square; // a harder, buzzier tone

        Note(double hz, int startMs, double gain, double decayMs) {
            this(hz, startMs, gain, decayMs, false);
        }

        Note(double hz, int startMs, double gain, double decayMs, boolean square) {
            this.hz = hz;
            this.startMs = startMs;
            this.gain = gain;
            this.decayMs = decayMs;
            this.square = square;
        }
    }

    static synchronized void init(Context c) {
        try {
            enabled = c.getSharedPreferences("reel_ui", Context.MODE_PRIVATE).getBoolean("sounds", true);
        } catch (Exception ignored) {
            enabled = true;
        }
        int want;
        try {
            want = c.getSharedPreferences("reel_ui", Context.MODE_PRIVATE).getInt("soundStyle", 0);
        } catch (Exception ignored) {
            want = 0;
        }
        want = Math.max(0, Math.min(STYLE_NAMES.length - 1, want));
        if (ready && want == style) return;
        try {
            AudioTrack[] old = {move, click, edge};
            build(want);
            style = want;
            ready = true;
            for (AudioTrack t : old) if (t != null) t.release();
        } catch (Exception e) {
            enabled = false;
        }
    }

    /** The three sounds of one style. */
    private static void build(int s) {
        switch (s) {
            case 1: // Glass: a clear, high clink
                move = make(90, 0.10, new Note(2093, 0, 1.0, 16));
                click = make(330, 0.17, new Note(1568, 0, 1.0, 110), new Note(2093, 55, 0.8, 130));
                edge = make(150, 0.20, new Note(262, 0, 1.0, 45));
                break;
            case 2: // Wood: dry, low taps
                move = make(60, 0.20, new Note(620, 0, 1.0, 7));
                click = make(180, 0.24, new Note(440, 0, 1.0, 28), new Note(660, 45, 0.9, 38));
                edge = make(130, 0.26, new Note(120, 0, 1.0, 30));
                break;
            case 3: // Marimba: warm and round
                move = make(100, 0.17, new Note(880, 0, 1.0, 20));
                click = make(300, 0.22, new Note(523.3, 0, 1.0, 70), new Note(784, 70, 0.95, 100));
                edge = make(170, 0.24, new Note(130.8, 0, 1.0, 55));
                break;
            case 4: // Retro: little game-style beeps
                move = make(50, 0.07, new Note(1000, 0, 1.0, 12, true));
                click = make(200, 0.09, new Note(660, 0, 1.0, 45, true), new Note(990, 55, 1.0, 60, true));
                edge = make(110, 0.10, new Note(110, 0, 1.0, 35, true));
                break;
            default: // Chime
                move = make(70, 0.13, new Note(1318.5, 0, 1.0, 11));
                click = make(260, 0.20, new Note(987.8, 0, 1.0, 60), new Note(1318.5, 60, 0.9, 85));
                edge = make(150, 0.22, new Note(174.6, 0, 1.0, 38));
        }
    }

    private static AudioTrack make(int ms, double amp, Note... notes) {
        int n = RATE * ms / 1000;
        double[] mix = new double[n];
        for (Note nt : notes) {
            int start = RATE * nt.startMs / 1000;
            for (int i = start; i < n; i++) {
                double t = (double) (i - start) / RATE;
                double attack = Math.min(1.0, t / 0.006);
                attack = attack * attack * (3 - 2 * attack); // smooth start, no click
                double env = attack * Math.exp(-t * 1000.0 / nt.decayMs);
                double w = 2 * Math.PI * nt.hz * t;
                double tone = nt.square ? Math.signum(Math.sin(w)) * 0.6 : Math.sin(w) + 0.22 * Math.sin(2 * w);
                mix[i] += tone * env * nt.gain;
            }
        }
        short[] pcm = new short[n];
        int fade = RATE / 200; // last 5 ms fade to silence
        for (int i = 0; i < n; i++) {
            double v = mix[i] * amp;
            if (i > n - fade) v *= (double) (n - i) / fade;
            pcm[i] = (short) Math.max(-32767, Math.min(32767, v * Short.MAX_VALUE));
        }
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(pcm, 0, n);
        return t;
    }

    private static void play(AudioTrack t) {
        if (!enabled || !ready || t == null) return;
        try {
            t.stop();
            t.setPlaybackHeadPosition(0);
            t.play();
        } catch (Exception ignored) {
            // sounds are optional
        }
    }

    static void move() {
        long now = SystemClock.uptimeMillis();
        if (now - lastMove < 70) return;
        lastMove = now;
        play(move);
    }

    static void click() {
        play(click);
    }

    static void edge() {
        play(edge);
    }
}
