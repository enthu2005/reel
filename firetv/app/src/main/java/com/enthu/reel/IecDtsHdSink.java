package com.enthu.reel;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import java.nio.ByteBuffer;

/**
 * Sends DTS-HD Master Audio / High Resolution / DTS:X to the receiver the way Kodi does: each DTS-HD frame is
 * wrapped in an IEC 61937 burst and played as a raw 8 channel, 192 kHz stream (AudioTrack encoding
 * IEC61937). The Fire TV's own DTS-HD path only carries the DTS core, but a raw stream is passed along
 * untouched. Every other format goes straight to the normal audio sink.
 */
@UnstableApi
final class IecDtsHdSink extends ForwardingAudioSink {
    /** Set by the player before each film: use the raw stream for DTS-HD tracks. */
    static volatile boolean enabled = false;

    private static final String TAG = "ReelPlayer";
    private static final int ENCODING_IEC61937 = 13; // AudioFormat.ENCODING_IEC61937 (API 23)
    private static final int RATE = 192000;
    private static final int CHANNELS = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
    private static final int FRAME_BYTES = 16; // 8 channels x 16 bit
    private static final int[] CORE_RATES = {0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0,
            12000, 24000, 48000, 96000, 192000};
    private static final byte[] START_CODE = {0x01, 0, 0, 0, 0, 0, 0, 0, (byte) 0xfe, (byte) 0xfe};

    private final AudioSink inner;
    private @Nullable Listener listener;
    private boolean iec;
    private boolean playing;
    private boolean endOfStream;
    private boolean started;
    private boolean advancingReported;
    private @Nullable Format format;
    private @Nullable AudioTrack track;
    private @Nullable byte[] pending;
    private int pendingOffset;
    // The Fire TV counts the playback position of a raw stream in units that differ between devices, so
    // time is kept with a plain clock that runs while the track plays instead.
    private byte[] burstBuf;
    private int skipped;
    private int packed;
    private boolean haveFirst;
    private long firstPtsUs;
    private long writtenDurationUs;
    private long playedBeforeNs;
    private long playStartNs;

    private final android.content.Context context;

    IecDtsHdSink(android.content.Context context, AudioSink inner) {
        super(inner);
        this.inner = inner;
        this.context = context.getApplicationContext();
    }

    /** What the Fire TV's HDMI / ARC outputs say they accept, for the Info page and the log. */
    static String hdmiInfo(android.content.Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(android.content.Context.AUDIO_SERVICE);
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t != android.media.AudioDeviceInfo.TYPE_HDMI && t != android.media.AudioDeviceInfo.TYPE_HDMI_ARC
                        && t != 29 && t != android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL) continue;
                sb.append("  ").append(t == 9 ? "HDMI" : t == 10 ? "HDMI ARC" : t == 29 ? "HDMI eARC" : "digital out").append('\n');
                sb.append("    rates ").append(java.util.Arrays.toString(d.getSampleRates())).append('\n');
                StringBuilder ch = new StringBuilder();
                for (int m : d.getChannelMasks()) ch.append(Integer.bitCount(m)).append("ch ");
                sb.append("    channels ").append(ch.toString().trim()).append('\n');
                StringBuilder en = new StringBuilder();
                for (int e : d.getEncodings()) {
                    en.append(e == 13 ? "IEC61937" : e == 5 ? "AC3" : e == 6 ? "E-AC3" : e == 7 ? "DTS" : e == 8 ? "DTS-HD"
                            : e == 14 ? "TrueHD" : e == 18 ? "E-AC3-JOC" : e == 2 ? "PCM16" : String.valueOf(e)).append(' ');
                }
                sb.append("    formats ").append(en.toString().trim()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  (couldn't read: ").append(t).append(")\n");
        }
        return sb.length() == 0 ? "  none reported\n" : sb.toString();
    }

    private static boolean wanted(Format f) {
        return enabled && f != null && MimeTypes.AUDIO_DTS_HD.equals(f.sampleMimeType);
    }

    @Override public void setListener(Listener l) {
        listener = l;
        super.setListener(l);
    }

    @Override public boolean supportsFormat(Format f) {
        return wanted(f) || super.supportsFormat(f);
    }

    @Override public int getFormatSupport(Format f) {
        return wanted(f) ? SINK_FORMAT_SUPPORTED_DIRECTLY : super.getFormatSupport(f);
    }

    @Override
    public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
            throws ConfigurationException {
        releaseTrack();
        format = inputFormat;
        iec = wanted(inputFormat);
        endOfStream = false;
        if (iec) {
            Log.d(TAG, "DTS-HD: sending the raw IEC 61937 stream (8 ch, 192 kHz), like Kodi");
            super.reset();
        } else {
            super.configure(inputFormat, specifiedBufferSize, outputChannels);
        }
    }

    @Override public void play() {
        playing = true;
        super.play();
        if (iec && track != null && !started && haveFirst) startTrack();
    }

    @Override public void pause() {
        playing = false;
        super.pause();
        if (iec && track != null) {
            try {
                track.pause();
            } catch (Exception ignored) {
            }
            if (started) playedBeforeNs += System.nanoTime() - playStartNs;
            started = false;
        }
    }

    @Override public void flush() {
        if (iec) {
            releaseTrack();
            endOfStream = false;
        }
        super.flush();
    }

    @Override public void reset() {
        releaseTrack();
        endOfStream = false;
        iec = false;
        super.reset();
    }

    private long playedUs() {
        long ns = playedBeforeNs + (started ? System.nanoTime() - playStartNs : 0);
        return ns / 1000;
    }

    @Override public long getCurrentPositionUs(boolean sourceEnded) {
        if (!iec) return super.getCurrentPositionUs(sourceEnded);
        if (track == null || !haveFirst) return CURRENT_POSITION_NOT_SET;
        if (started && !advancingReported && listener != null) {
            advancingReported = true;
            listener.onPositionAdvancing(System.currentTimeMillis());
        }
        return firstPtsUs + Math.min(playedUs(), writtenDurationUs);
    }

    @Override public void handleDiscontinuity() {
        if (!iec) super.handleDiscontinuity();
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
            throws InitializationException, WriteException {
        if (!iec) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
        if (track == null) createTrack();
        if (pending != null) {
            writePending();
            if (pending != null) return false;
        }
        if (!buffer.hasRemaining()) return true;
        byte[] frame = new byte[buffer.remaining()];
        buffer.get(frame);
        long[] meta = new long[1];
        byte[] burst = pack(frame, meta);
        if (burst == null) {
            if (skipped++ < 5) {
                Log.d(TAG, "raw DTS-HD: skipped a frame of " + frame.length + " bytes, reason " + meta[0]
                        + " (-1 no core sync, -3 too big, below -3 = period " + (-2 - meta[0]) + "), first bytes "
                        + (frame.length >= 4 ? String.format("%02x %02x %02x %02x", frame[0], frame[1], frame[2], frame[3]) : "(short)"));
            }
            return true;
        }
        if (!haveFirst) {
            haveFirst = true;
            firstPtsUs = presentationTimeUs;
            writtenDurationUs = 0;
        }
        writtenDurationUs += meta[0];
        pending = burst;
        pendingOffset = 0;
        writePending();
        packed++;
        if (packed <= 3 || packed % 500 == 0) {
            Log.d(TAG, "raw DTS-HD burst #" + packed + ": frame " + frame.length + " bytes -> burst " + burst.length
                    + " bytes, " + meta[0] + " us, written " + writtenDurationUs / 1000 + " ms, played "
                    + playedUs() / 1000 + " ms, started " + started + ", playing " + playing
                    + ", pending " + (pending != null));
        }
        if (playing && !started) startTrack();
        return true;
    }

    @Override public void playToEndOfStream() throws WriteException {
        if (!iec) {
            super.playToEndOfStream();
            return;
        }
        endOfStream = true;
        writePending();
    }

    @Override public boolean isEnded() {
        if (!iec) return super.isEnded();
        return endOfStream && pending == null && playedUs() >= writtenDurationUs;
    }

    @Override public boolean hasPendingData() {
        if (!iec) return super.hasPendingData();
        return pending != null || (track != null && playedUs() < writtenDurationUs);
    }

    // ---------------------------------------------------------------------------------------------

    private static final int[] MASKS = {AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 0x3FC /* CHANNEL_OUT_7POINT1 */};

    private void createTrack() throws InitializationException {
        Exception last = null;
        int lastSize = 0;
        for (int mask : MASKS) {
            int min = AudioTrack.getMinBufferSize(RATE, mask, ENCODING_IEC61937);
            int size = Math.max(min > 0 ? min : 0, 32768 * 6);
            lastSize = size;
            try {
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
                AudioFormat af = new AudioFormat.Builder()
                        .setEncoding(ENCODING_IEC61937)
                        .setSampleRate(RATE)
                        .setChannelMask(mask)
                        .build();
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(aa)
                        .setAudioFormat(af)
                        .setBufferSizeInBytes(size)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                    t.release();
                    last = new IllegalStateException("AudioTrack not initialised, mask " + mask);
                    continue;
                }
                track = t;
                started = false;
                advancingReported = false;
                haveFirst = false;
                packed = 0;
                skipped = 0;
                writtenDurationUs = 0;
                playedBeforeNs = 0;
                Log.d(TAG, "raw DTS-HD AudioTrack opened (mask " + mask + "), buffer " + size + " bytes");
                return;
            } catch (Exception e) {
                last = e;
                Log.d(TAG, "raw DTS-HD AudioTrack refused (mask " + mask + "): " + e);
            }
        }
        Log.d(TAG, "HDMI outputs the Fire TV reports:\n" + hdmiInfo(context));
        throw new InitializationException(AudioTrack.STATE_UNINITIALIZED, RATE, MASKS[0], lastSize, format,
                false, last);
    }

    private void startTrack() {
        try {
            track.play();
            playStartNs = System.nanoTime();
            started = true;
        } catch (Exception e) {
            Log.d(TAG, "raw DTS-HD AudioTrack couldn't start: " + e);
        }
    }

    private void releaseTrack() {
        pending = null;
        pendingOffset = 0;
        haveFirst = false;
        writtenDurationUs = 0;
        playedBeforeNs = 0;
        started = false;
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (Exception ignored) {
            }
            try {
                t.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void writePending() throws WriteException {
        if (pending == null || track == null) return;
        int n = track.write(pending, pendingOffset, pending.length - pendingOffset, AudioTrack.WRITE_NON_BLOCKING);
        if (n < 0) throw new WriteException(n, format, false);
        pendingOffset += n;
        if (pendingOffset >= pending.length) {
            pending = null;
            pendingOffset = 0;
        }
    }

    /**
     * Wraps one DTS-HD access unit (core frame plus its HD extension) in an IEC 61937 burst, the same way Kodi's
     * CAEPackIEC61937::PackDTSHD does. meta[0] gets the duration of the burst in microseconds.
     */
    @Nullable
    private byte[] pack(byte[] f, long[] meta) {
        if (f.length < 12 || (f[0] & 0xFF) != 0x7F || (f[1] & 0xFF) != 0xFE
                || (f[2] & 0xFF) != 0x80 || (f[3] & 0xFF) != 0x01) {
            meta[0] = -1; // no DTS core sync at the start
            return null;
        }
        int blocks = (((f[4] & 0x01) << 6) | ((f[5] & 0xFF) >> 2)) + 1;
        int sfreq = (f[8] >> 2) & 0x0F;
        int coreRate = CORE_RATES[sfreq];
        if (coreRate == 0) coreRate = 48000;
        int samples = blocks * 32;
        long periodL = (long) RATE * 4L * samples / coreRate;
        int subtype;
        if (periodL == 512) subtype = 0;
        else if (periodL == 1024) subtype = 1;
        else if (periodL == 2048) subtype = 2;
        else if (periodL == 4096) subtype = 3;
        else if (periodL == 8192) subtype = 4;
        else if (periodL == 16384) subtype = 5;
        else {
            meta[0] = -2 - periodL; // period we can't send
            return null;
        }
        int period = (int) periodL;
        int burstSize = period << 2;
        int dataSize = START_CODE.length + 2 + f.length;
        if (dataSize + 8 > burstSize) {
            meta[0] = -3;
            return null;
        }

        byte[] src = new byte[dataSize + (dataSize & 1)];
        System.arraycopy(START_CODE, 0, src, 0, START_CODE.length);
        src[START_CODE.length] = (byte) ((f.length >> 8) & 0xFF);
        src[START_CODE.length + 1] = (byte) (f.length & 0xFF);
        System.arraycopy(f, 0, src, START_CODE.length + 2, f.length);

        byte[] out = burstBuf != null && burstBuf.length == burstSize ? burstBuf : new byte[burstSize];
        java.util.Arrays.fill(out, (byte) 0);
        burstBuf = out;
        out[0] = 0x72;
        out[1] = (byte) 0xF8;
        out[2] = 0x1F;
        out[3] = 0x4E;
        int type = 0x11 | (subtype << 8);
        out[4] = (byte) (type & 0xFF);
        out[5] = (byte) ((type >> 8) & 0xFF);
        int length = ((dataSize + 0x17) & ~0x0F) - 0x08;
        out[6] = (byte) (length & 0xFF);
        out[7] = (byte) ((length >> 8) & 0xFF);
        for (int i = 0; i + 1 < src.length; i += 2) {
            out[8 + i] = src[i + 1];
            out[8 + i + 1] = src[i];
        }
        meta[0] = (long) samples * 1_000_000L / coreRate;
        return out;
    }
}
