package com.enthu.reel;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Sits between the player and the converted (HLS) stream Plex makes.
 *
 * 1. Dolby Vision films carry a small "RPU" data block (NAL type 62/63) after every picture. ExoPlayer's HEVC reader
 *    mishandles the one at the very end of each piece and throws IllegalArgumentException, so the film stops after the
 *    first piece and the buffer never grows. The picture already plays as its HDR10 base layer, so the block is only
 *    relabelled (as a harmless SEI note), which lets the reader carry on. Nothing else in the stream is touched.
 * 2. If a [cache] is given, a piece the store holds only part of is thrown away before it is opened. The pieces are
 *    made on the fly, so finishing a half-stored one from a second download could join two different versions.
 */
final class TsFix implements DataSource {
    private static final int PKT = 188;
    private static final int SUFFIX_SEI = 40; // an HEVC NAL type the reader tolerates

    private final DataSource inner;
    private final Cache cache;

    // per opened piece
    private boolean patching;
    private boolean decided;
    private int pmtPid = -1;
    private int videoPid = -1;
    private int zeros;
    private boolean wantHeader;
    private final byte[] raw = new byte[PKT * 400];
    private int rawLen;
    private byte[] out = new byte[0];
    private int outPos;
    private int outLen;
    private boolean eof;
    private int skipLeft;
    private static volatile int lastVideoPid = -1;

    TsFix(DataSource inner, Cache cache) {
        this.inner = inner;
        this.cache = cache;
    }

    @Override
    public void addTransferListener(TransferListener l) {
        inner.addTransferListener(l);
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        if (cache != null) BigBuffer.dropIfIncomplete(cache, spec.key != null ? spec.key : spec.uri.toString());
        // A load that was cut short (a timeout) is resumed half-way through the same piece: that part is patched too,
        // from the next whole packet on, using the video stream number seen earlier
        skipLeft = (int) ((PKT - (spec.position % PKT)) % PKT);
        patching = spec.position == 0 || lastVideoPid >= 0;
        decided = false;
        pmtPid = -1;
        videoPid = spec.position == 0 ? -1 : lastVideoPid;
        zeros = 0;
        wantHeader = false;
        rawLen = 0;
        outPos = 0;
        outLen = 0;
        eof = false;
        return inner.open(spec);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (outPos < outLen) { // bytes already read ahead
            int c = Math.min(len, outLen - outPos);
            System.arraycopy(out, outPos, b, off, c);
            outPos += c;
            return c;
        }
        if (!patching) return inner.read(b, off, len);
        if (skipLeft > 0) { // the end of a packet whose start was handed over before
            int c = inner.read(b, off, Math.min(len, skipLeft));
            if (c > 0) skipLeft -= c;
            return c;
        }
        while (outPos >= outLen) {
            if (eof) return -1;
            int n = inner.read(raw, rawLen, raw.length - rawLen);
            if (n < 0) {
                eof = true;
                if (rawLen == 0) return -1;
                emit(rawLen); // a partial packet at the very end: passed on as it is
                continue;
            }
            if (n == 0) return 0;
            rawLen += n;
            if (!decided) {
                decided = true;
                if (raw[0] != 0x47) { // not a transport stream (a playlist, a subtitle): leave it alone
                    patching = false;
                    int c = Math.min(len, rawLen);
                    System.arraycopy(raw, 0, b, off, c);
                    // anything left over goes out through the normal path below
                    out = java.util.Arrays.copyOfRange(raw, 0, rawLen);
                    outPos = c;
                    outLen = rawLen;
                    rawLen = 0;
                    return c;
                }
            }
            int full = rawLen / PKT * PKT;
            if (full == 0) continue;
            for (int p = 0; p < full; p += PKT) patch(raw, p);
            emit(full);
        }
        int c = Math.min(len, outLen - outPos);
        System.arraycopy(out, outPos, b, off, c);
        outPos += c;
        return c;
    }

    /** Moves the first [n] raw bytes into the output queue. */
    private void emit(int n) {
        if (out.length < n) out = new byte[n];
        System.arraycopy(raw, 0, out, 0, n);
        outPos = 0;
        outLen = n;
        System.arraycopy(raw, n, raw, 0, rawLen - n);
        rawLen -= n;
    }

    private void patch(byte[] d, int p) {
        if ((d[p] & 0xff) != 0x47) return;
        int pid = ((d[p + 1] & 0x1f) << 8) | (d[p + 2] & 0xff);
        boolean pusi = (d[p + 1] & 0x40) != 0;
        int afc = (d[p + 3] >> 4) & 3;
        int off = p + 4;
        if ((afc & 2) != 0) off += 1 + (d[off] & 0xff);
        if ((afc & 1) == 0 || off >= p + PKT) return;
        int end = p + PKT;
        try {
            if (pid == 0 && pmtPid < 0 && pusi) {
                int o = off + 1 + (d[off] & 0xff);
                int secLen = ((d[o + 1] & 0x0f) << 8) | (d[o + 2] & 0xff);
                for (int i = o + 8; i + 4 <= o + 3 + secLen - 4; i += 4) {
                    int prog = ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff);
                    if (prog != 0) {
                        pmtPid = ((d[i + 2] & 0x1f) << 8) | (d[i + 3] & 0xff);
                        break;
                    }
                }
            } else if (pid == pmtPid && videoPid < 0 && pusi) {
                int o = off + 1 + (d[off] & 0xff);
                int secLen = ((d[o + 1] & 0x0f) << 8) | (d[o + 2] & 0xff);
                int progInfo = ((d[o + 10] & 0x0f) << 8) | (d[o + 11] & 0xff);
                int i = o + 12 + progInfo;
                int last = o + 3 + secLen - 4;
                while (i + 5 <= last) {
                    int type = d[i] & 0xff;
                    int ep = ((d[i + 1] & 0x1f) << 8) | (d[i + 2] & 0xff);
                    int es = ((d[i + 3] & 0x0f) << 8) | (d[i + 4] & 0xff);
                    if (type == 0x24) { // HEVC
                        videoPid = ep;
                        lastVideoPid = ep;
                        break;
                    }
                    i += 5 + es;
                }
            } else if (pid == videoPid) {
                for (int i = off; i < end; i++) {
                    int v = d[i] & 0xff;
                    if (wantHeader) {
                        wantHeader = false;
                        int type = (v >> 1) & 0x3f;
                        if (type == 62 || type == 63) d[i] = (byte) ((v & 0x81) | (SUFFIX_SEI << 1));
                        zeros = 0;
                        continue;
                    }
                    if (v == 0) {
                        zeros++;
                    } else {
                        if (v == 1 && zeros >= 2) wantHeader = true;
                        zeros = 0;
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException ignored) {
            // a table that doesn't parse: leave this packet as it is
        }
    }

    @Override
    public Uri getUri() {
        return inner.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return inner.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }
}
