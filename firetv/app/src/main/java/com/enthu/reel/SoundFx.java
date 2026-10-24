package com.enthu.reel;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

/** Soft interface sounds, generated in code (no sound files). Switch off in Settings. */
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

    static synchronized void init(Context c) {
        try {
            enabled = c.getSharedPreferences("reel_ui", Context.MODE_PRIVATE).getBoolean("sounds", true);
        } catch (Exception ignored) {
            enabled = true;
        }
        if (ready) return;
        try {
            move = make(new double[]{1500}, 26, 0.16);
            click = make(new double[]{880, 1320}, 75, 0.28);
            edge = make(new double[]{190}, 110, 0.35);
            ready = true;
        } catch (Exception e) {
            enabled = false;
        }
    }

    private static AudioTrack make(double[] freqs, int ms, double amp) {
        int n = RATE * ms / 1000;
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.exp(-6.0 * i / n) * Math.min(1.0, i / 80.0); // quick fade in, soft fade out
            double v = 0;
            for (double f : freqs) v += Math.sin(2 * Math.PI * f * t);
            pcm[i] = (short) (v / freqs.length * env * amp * Short.MAX_VALUE);
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
