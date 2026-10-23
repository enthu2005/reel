package com.enthu.reel;

import androidx.media3.exoplayer.ExoPlayer;

/** Carries a playing home-screen preview over to the title's page, so it keeps playing there. */
final class PreviewHandoff {
    private PreviewHandoff() {
    }

    static ExoPlayer player;
    static String session; // Plex transcode session to stop when it's finished
    static String key;     // the title it belongs to
    static boolean clip;   // a clip of the film itself (stopped after a while), not a trailer
    static boolean rendered; // already showing pictures (else it's still loading)

    /** Takes the player (once). Returns null if there's nothing for this title. */
    static ExoPlayer take(String forKey) {
        if (player == null || forKey == null || !forKey.equals(key)) return null;
        ExoPlayer p = player;
        player = null;
        return p;
    }

    /** A player for previews: decodes the sound itself (so it can be quiet) and loads lightly. */
    static ExoPlayer newPlayer(android.content.Context ctx) {
        return newPlayer(ctx, null);
    }

    /** Same, sending extra request headers (needed when Plex converts the stream). */
    static ExoPlayer newPlayer(android.content.Context ctx, java.util.Map<String, String> headers) {
        androidx.media3.exoplayer.DefaultRenderersFactory rf =
                new androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
                    @Override
                    protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                            android.content.Context context, boolean enableFloatOutput,
                            boolean enableAudioTrackPlaybackParams) {
                        return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                                .setAudioCapabilities(
                                        androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                                .build();
                    }
                }.setEnableDecoderFallback(true);
        androidx.media3.datasource.DefaultHttpDataSource.Factory http =
                new androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(20000).setReadTimeoutMs(30000);
        if (headers != null) http.setDefaultRequestProperties(headers);
        ExoPlayer p = new ExoPlayer.Builder(ctx, rf)
                .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(6_000, 20_000, 2_500, 2_500).build())
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
                .build();
        p.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException e) {
                android.util.Log.w("ReelPreview", "preview error " + e.getErrorCodeName() + ": " + e.getMessage()
                        + " cause=" + e.getCause());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                android.util.Log.d("ReelPreview", "preview state " + state + " (1 idle, 2 buffering, 3 ready, 4 ended)");
            }

            @Override
            public void onRenderedFirstFrame() {
                android.util.Log.d("ReelPreview", "preview first picture shown");
            }
        });
        level(p);
        return p;
    }

    /**
     * Evens out loudness: boosts quiet trailers and holds loud ones down with a limiter, so every
     * preview sits at about the same level (YouTube's player already does this for its videos).
     */
    private static void level(final ExoPlayer p) {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        final android.media.audiofx.DynamicsProcessing[] dp = new android.media.audiofx.DynamicsProcessing[1];
        p.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onAudioSessionIdChanged(int sessionId) {
                attach(dp, sessionId);
            }
        });
        attach(dp, p.getAudioSessionId()); // ExoPlayer already has its session when created
        p.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override
            public void onPlayerReleased(EventTime eventTime) {
                attach(dp, 0);
            }
        });
    }

    private static void attach(android.media.audiofx.DynamicsProcessing[] dp, int sessionId) {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        try {
            if (dp[0] != null) dp[0].release();
        } catch (Throwable ignored) {
        }
        dp[0] = null;
        if (sessionId <= 0) return;
        try {
            android.media.audiofx.DynamicsProcessing.Config cfg =
                    new android.media.audiofx.DynamicsProcessing.Config.Builder(
                            android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            2, false, 0, false, 0, false, 0, true).build();
            android.media.audiofx.DynamicsProcessing d =
                    new android.media.audiofx.DynamicsProcessing(0, sessionId, cfg);
            d.setInputGainAllChannelsTo(6f);
            // attack 2 ms, release 250 ms, ratio 8:1 above -16 dB
            d.setLimiterAllChannelsTo(new android.media.audiofx.DynamicsProcessing.Limiter(
                    true, true, 0, 2f, 250f, 8f, -16f, 0f));
            d.setEnabled(true);
            dp[0] = d;
        } catch (Throwable ignored) {
            // not supported on this device: plays unlevelled
        }
    }

    static void releaseLeftover() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (session != null) {
            Plex.stopTranscode(session);
            session = null;
        }
    }
}
