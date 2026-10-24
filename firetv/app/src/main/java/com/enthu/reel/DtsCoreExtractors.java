package com.enthu.reel;

import android.net.Uri;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import java.io.IOException;
import java.util.Map;

/**
 * Plays DTS-HD (Master Audio, High Res, DTS:X) as plain DTS core. The HD part rides behind the core
 * frame inside every sample, so cutting each sample after the core frame leaves a normal DTS stream
 * that any receiver accepts, with no conversion on the server.
 */
public final class DtsCoreExtractors implements ExtractorsFactory {
    /** Play Dolby Vision files as their plain HDR10 (HEVC) picture; set by the player before it starts. */
    public static volatile boolean dvAsHdr10 = false;

    /** Cut DTS-HD down to its DTS core. Off lets DTS-HD MA / DTS:X go to the receiver untouched. */
    public static volatile boolean stripToCore = false;

    /** The chosen audio track is DTS-HD (Master Audio / High Res / DTS:X); set by the player. */
    public static volatile boolean trackIsDtsHd = false;

    private final DefaultExtractorsFactory base = new DefaultExtractorsFactory();

    /** Subtitle timing offset for subtitles inside the file (+ = later). */
    static volatile long textShiftUs = 0;

    // Pass subtitle settings on to the real extractors, so text subtitles keep working
    // (Media3 parses them while reading the file).
    public ExtractorsFactory setSubtitleParserFactory(androidx.media3.extractor.text.SubtitleParser.Factory f) {
        call("setSubtitleParserFactory", androidx.media3.extractor.text.SubtitleParser.Factory.class, f);
        return this;
    }

    public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
        call("experimentalSetTextTrackTranscodingEnabled", boolean.class, enabled);
        return this;
    }

    private void call(String name, Class<?> type, Object arg) {
        try {
            base.getClass().getMethod(name, type).invoke(base, arg);
        } catch (Throwable ignored) {
            // not in this Media3 version
        }
    }

    @Override
    public Extractor[] createExtractors() {
        Extractor[] in = base.createExtractors();
        Extractor[] out = new Extractor[in.length];
        for (int i = 0; i < in.length; i++) out[i] = new Wrap(in[i]);
        return out;
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, java.util.List<String>> headers) {
        Extractor[] in = base.createExtractors(uri, headers);
        Extractor[] out = new Extractor[in.length];
        for (int i = 0; i < in.length; i++) out[i] = new Wrap(in[i]);
        return out;
    }

    private static final class Wrap implements Extractor {
        private final Extractor d;
        Wrap(Extractor d) { this.d = d; }
        @Override public boolean sniff(ExtractorInput input) throws IOException { return d.sniff(input); }
        @Override public void init(final ExtractorOutput o) {
            d.init(new ExtractorOutput() {
                @Override public TrackOutput track(int id, int type) {
                    TrackOutput t = o.track(id, type);
                    if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO) return new CoreOut(t);
                    if (type == androidx.media3.common.C.TRACK_TYPE_VIDEO && dvAsHdr10) return new VideoOut(t);
                    if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) return new ShiftOut(t);
                    return t;
                }
                @Override public void endTracks() { o.endTracks(); }
                @Override public void seekMap(SeekMap m) { o.seekMap(m); }
            });
        }
        @Override public int read(ExtractorInput input, PositionHolder p) throws IOException { return d.read(input, p); }
        @Override public void seek(long position, long timeUs) { d.seek(position, timeUs); }
        @Override public void release() { d.release(); }
    }

    /** Tells the decoder a Dolby Vision file is ordinary HEVC, so it uses its plain HEVC decoder. */
    private static final class VideoOut implements TrackOutput {
        private final TrackOutput d;

        VideoOut(TrackOutput d) { this.d = d; }

        @Override public void format(Format f) {
            if (MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType)) {
                android.util.Log.d("ReelPlayer", "Dolby Vision " + f.codecs + " played as plain HEVC (HDR10)");
                f = f.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build();
            }
            d.format(f);
        }

        @Override public int sampleData(DataReader input, int length, boolean allowEnd, int part) throws IOException {
            return d.sampleData(input, length, allowEnd, part);
        }

        @Override public void sampleData(ParsableByteArray data, int length, int part) {
            d.sampleData(data, length, part);
        }

        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData crypto) {
            d.sampleMetadata(timeUs, flags, size, offset, crypto);
        }
    }

    /** Moves subtitle samples earlier or later by textShiftUs. */
    private static final class ShiftOut implements TrackOutput {
        private final TrackOutput d;
        private final long shift = textShiftUs;

        ShiftOut(TrackOutput d) { this.d = d; }

        @Override public void format(Format f) { d.format(f); }

        @Override public int sampleData(DataReader input, int length, boolean allowEnd, int part) throws IOException {
            return d.sampleData(input, length, allowEnd, part);
        }

        @Override public void sampleData(ParsableByteArray data, int length, int part) {
            d.sampleData(data, length, part);
        }

        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData crypto) {
            d.sampleMetadata(Math.max(0, timeUs + shift), flags, size, offset, crypto);
        }
    }

    private static final class CoreOut implements TrackOutput {
        private final TrackOutput d;
        private byte[] buf = new byte[1 << 16];
        private int len = 0;
        private boolean dts = false;

        CoreOut(TrackOutput d) { this.d = d; }

        @Override public void format(Format f) {
            String m = f.sampleMimeType;
            dts = stripToCore && m != null && m.startsWith("audio/vnd.dts");
            if (dts) {
                Format.Builder b = f.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS);
                if (f.channelCount > 6) b.setChannelCount(6);
                // DTS-HD MA often reports 96 kHz; its core is 48 kHz and that's all the receiver is sent.
                if (f.sampleRate > 48000) b.setSampleRate(48000);
                f = b.build();
            }
            else if (m != null && m.equals(MimeTypes.AUDIO_DTS) && trackIsDtsHd) {
                // MKV files tag DTS-HD MA as plain DTS. Tell the audio output it is DTS-HD, otherwise it
                // opens a core-only stream and fails on the 8 channels.
                android.util.Log.d("ReelPlayer", "DTS-HD track labelled DTS-HD for passthrough");
                // Keep the file's own rate (48 kHz): the sink works out how long each block lasts from it, and a
                // wrong rate makes every block look out of step with the clock.
                f = f.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(8).build();
            }
            d.format(f);
        }

        private void ensure(int extra) {
            if (len + extra > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
        }

        @Override public int sampleData(DataReader input, int length, boolean allowEnd, int part) throws IOException {
            if (!dts) return d.sampleData(input, length, allowEnd, part);
            ensure(length);
            int n = input.read(buf, len, length);
            if (n > 0) len += n;
            return n;
        }

        @Override public void sampleData(ParsableByteArray data, int length, int part) {
            if (!dts) { d.sampleData(data, length, part); return; }
            ensure(length);
            data.readBytes(buf, len, length);
            len += length;
        }

        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData crypto) {
            if (!dts) { d.sampleMetadata(timeUs, flags, size, offset, crypto); return; }
            int out = size;
            if (size > 10 && (buf[0] & 0xFF) == 0x7F && (buf[1] & 0xFF) == 0xFE
                    && (buf[2] & 0xFF) == 0x80 && (buf[3] & 0xFF) == 0x01) {
                int fsize = ((((buf[5] & 0x03) << 12) | ((buf[6] & 0xFF) << 4) | ((buf[7] >> 4) & 0x0F))) + 1;
                // Only cut when the HD extension (sync 64 58 20 25) really follows the core frame.
                if (fsize > 0 && fsize + 4 <= size
                        && (buf[fsize] & 0xFF) == 0x64 && (buf[fsize + 1] & 0xFF) == 0x58
                        && (buf[fsize + 2] & 0xFF) == 0x20 && (buf[fsize + 3] & 0xFF) == 0x25) {
                    out = fsize;
                }
            }
            d.sampleData(new ParsableByteArray(buf, out), out);
            d.sampleMetadata(timeUs, flags, out, 0, crypto);
            int rest = len - size;
            if (rest > 0) System.arraycopy(buf, size, buf, 0, rest);
            len = Math.max(rest, 0);
        }
    }
}
