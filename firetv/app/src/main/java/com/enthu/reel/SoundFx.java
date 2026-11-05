package com.enthu.reel;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

/**
 * Soft interface sounds, generated in code (no sound files). Switch off in Settings.
 * Each sound is a few bell-like notes (a pure tone with a quiet octave above it) that start gently and
 * die away quickly: a light tick for moving, a rising two-note chime for choosing, a low pat at an edge.
 */
final class SoundFx {
    private static final int RATE = 44100;
    private static AudioTrack move;
    private static AudioTrack click;
    private static AudioTrack edge;
    private static boolean enabled = true;
    private static boolean ready = false;
    private static long lastMove = 0;

    private SoundFx() {
    }

    /** One note: pitch, when it starts, how loud (0..1) and how fast it fades. */
    private static final class Note {
        final double hz;
        final int startMs;
        final double gain;
        final double decayMs;

        Note(double hz, int startMs, double gain, double decayMs) {
            this.hz = hz;
            this.startMs = startMs;
            this.gain = gain;
            this.decayMs = decayMs;
        }
    }

    static synchronized void init(Context c) {
        try {
            enabled = c.getSharedPreferences("reel_ui", Context.MODE_PRIVATE).getBoolean("sounds", true);
        } catch (Exception ignored) {
            enabled = true;
        }
        if (ready) return;
        try {
            // Moving: a tiny, high, dry tick (E6)
            move = make(70, 0.13, new Note(1318.5, 0, 1.0, 11));
            // Choosing: B5 then E6, like a soft two-note chime
            click = make(260, 0.20, new Note(987.8, 0, 1.0, 60), new Note(1318.5, 60, 0.9, 85));
            // Edge: a low, rounded pat
            edge = make(150, 0.22, new Note(174.6, 0, 1.0, 38));
            ready = true;
        } catch (Exception e) {
            enabled = false;
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
                mix[i] += (Math.sin(w) + 0.22 * Math.sin(2 * w)) * env * nt.gain;
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
