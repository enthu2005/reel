package com.enthu.reel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Format badges (4K, Dolby Vision, Dolby Atmos, TrueHD, DTS-HD MA ...) worked out from the
 * stream details Plex reports for a file. They are plain styled labels, not official logos.
 */
final class Badges {
    private Badges() {
    }

    /** A friendly name for an audio stream's format. */
    static String audioName(Plex.Stream s) {
        String all = (s.codec + " " + s.profile + " " + s.extTitle + " " + s.displayTitle + " " + s.title)
                .toLowerCase(Locale.ROOT);
        String codec = s.codec.toLowerCase(Locale.ROOT);
        boolean atmos = all.contains("atmos");

        if (all.contains("truehd")) return atmos ? "Dolby Atmos (TrueHD)" : "Dolby TrueHD";
        if (codec.equals("eac3") || all.contains("e-ac-3") || all.contains("eac3")
                || all.contains("dd+") || all.contains("digital plus")) {
            return atmos ? "Dolby Atmos" : "Dolby Digital Plus";
        }
        if (codec.equals("ac3") || all.contains("dolby digital")) return "Dolby Digital";
        if (codec.contains("dca") || codec.contains("dts") || all.contains("dts")) {
            if (all.contains("dts:x") || all.contains("dts-x")) return "DTS:X";
            if (all.matches(".*\\bma\\b.*") || all.contains("dts-hd ma") || all.contains("master")) {
                return "DTS-HD MA";
            }
            if (all.matches(".*\\bhra\\b.*") || all.contains("high res")) return "DTS-HD HRA";
            return "DTS";
        }
        if (codec.equals("aac")) return "AAC";
        if (codec.equals("flac")) return "FLAC";
        if (codec.equals("opus")) return "Opus";
        if (codec.equals("mp3")) return "MP3";
        if (codec.startsWith("pcm")) return "PCM";
        return s.codec.isEmpty() ? "Audio" : s.codec.toUpperCase(Locale.ROOT);
    }

    /** "7.1", "5.1", "2.0" and so on. */
    static String channelText(Plex.Stream s) {
        if (!s.layout.isEmpty()) {
            String l = s.layout.replaceAll("\\(.*\\)", "").trim();
            if (l.matches("\\d(\\.\\d)?")) return l.contains(".") ? l : l + ".0";
        }
        switch (s.channels) {
            case 1:
                return "1.0";
            case 2:
                return "2.0";
            case 6:
                return "5.1";
            case 8:
                return "7.1";
            case 0:
                return "";
            default:
                return s.channels + "ch";
        }
    }

    private static String resolution(Plex.Item item, Plex.Stream video) {
        String r = item.videoRes.toLowerCase(Locale.ROOT);
        int h = item.videoHeight > 0 ? item.videoHeight : video.height;
        if (r.equals("4k") || h >= 2000) return "4K";
        if (r.equals("1080") || h >= 1000) return "1080p";
        if (r.equals("720") || h >= 700) return "720p";
        if (r.equals("sd") || (h > 0 && h < 700)) return "SD";
        return null;
    }

    /**
     * Optional logo pictures. Put a transparent PNG (or SVG as a vector drawable) named like one of
     * these in app/src/main/res/drawable and it is used instead of the text tag:
     * badge_4k, badge_1080p, badge_hdr10, badge_hlg, badge_dolbyvision, badge_atmos, badge_truehd,
     * badge_dolbydigitalplus, badge_dolbydigital, badge_dtshd, badge_dtsx, badge_dts.
     * Missing ones simply keep the text tag.
     */
    private static int logoFor(Context c, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        String name;
        if (t.startsWith("4k")) name = "badge_4k";
        else if (t.startsWith("1080p")) name = "badge_1080p";
        else if (t.startsWith("hdr10")) name = "badge_hdr10";
        else if (t.startsWith("hlg")) name = "badge_hlg";
        else if (t.startsWith("dolby vision")) name = "badge_dolbyvision";
        else if (t.startsWith("dolby atmos")) name = "badge_atmos";
        else if (t.startsWith("dolby truehd")) name = "badge_truehd";
        else if (t.startsWith("dolby digital plus")) name = "badge_dolbydigitalplus";
        else if (t.startsWith("dolby digital")) name = "badge_dolbydigital";
        else if (t.startsWith("dts-hd")) name = t.contains("hra") ? "badge_dtshdhra" : "badge_dtshd";
        else if (t.startsWith("dts:x")) name = "badge_dtsx";
        else if (t.startsWith("dts")) name = "badge_dts";
        else return 0;
        return c.getResources().getIdentifier(name, "drawable", c.getPackageName());
    }

    static void fill(LinearLayout row, Plex.Item item, int H) {
        row.removeAllViews();
        if (item == null || item.streams.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }

        List<String[]> badges = new ArrayList<>(); // {text, strong ? "1" : "0"}

        List<Plex.Stream> videos = item.streamsOf(1);
        if (!videos.isEmpty()) {
            Plex.Stream v = videos.get(0);
            String res = resolution(item, v);
            if (res != null) badges.add(new String[]{res, "0"});
            if (v.dovi) {
                badges.add(new String[]{"Dolby Vision", "1"});
            } else if (v.colorTrc.equalsIgnoreCase("smpte2084")) {
                badges.add(new String[]{"HDR10", "1"});
            } else if (v.colorTrc.equalsIgnoreCase("arib-std-b67")) {
                badges.add(new String[]{"HLG", "1"});
            }
        }

        // The selected audio first, then any other formats in the file.
        List<Plex.Stream> audios = new ArrayList<>(item.streamsOf(2));
        for (int i = 0; i < audios.size(); i++) {
            if (audios.get(i).selected && i > 0) {
                audios.add(0, audios.remove(i));
                break;
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        int shown = 0;
        for (Plex.Stream a : audios) {
            String name = audioName(a);
            if (!seen.add(name) || shown >= 4) continue;
            String ch = channelText(a);
            boolean strong = name.contains("Atmos") || name.contains("TrueHD")
                    || name.contains("DTS-HD") || name.contains("DTS:X");
            badges.add(new String[]{ch.isEmpty() ? name : name + "  " + ch, strong ? "1" : "0"});
            shown++;
        }

        if (badges.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);

        Context c = row.getContext();
        for (String[] b : badges) {
            // Logo images you add under res/drawable (see logoFor) replace the text tag
            int logo = logoFor(c, b[0]);
            if (logo != 0) {
                android.widget.ImageView iv = new android.widget.ImageView(c);
                iv.setImageResource(logo);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (int) (H * 0.046f));
                ilp.gravity = android.view.Gravity.CENTER_VERTICAL;
                ilp.rightMargin = (int) (H * 0.012f);
                row.addView(iv, ilp);
                continue;
            }
            boolean strong = b[1].equals("1");
            String name = b[0];
            String extra = null;
            int gapAt = name.indexOf("  ");
            if (gapAt > 0) {
                extra = name.substring(gapAt).trim();
                name = name.substring(0, gapAt);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int) (H * 0.037f));
            lp.rightMargin = (int) (H * 0.011f);
            row.addView(new Chip(c, chipLabel(name), extra, chipIcon(name), strong, H), lp);
        }
    }

    /** Short upper-case wording for a badge. */
    private static String chipLabel(String name) {
        String t = name.toLowerCase(Locale.ROOT);
        if (t.startsWith("dolby vision")) return "DOLBY VISION";
        if (t.startsWith("dolby atmos")) return "DOLBY ATMOS";
        if (t.startsWith("dolby truehd")) return "TRUEHD";
        if (t.startsWith("dolby digital plus")) return "DD+";
        if (t.startsWith("dolby digital")) return "DD";
        return name.toUpperCase(Locale.ROOT);
    }

    /** 0 none, 1 picture-quality mark (half-filled circle), 2 sound mark (three bars). */
    private static int chipIcon(String name) {
        String t = name.toLowerCase(Locale.ROOT);
        if (t.startsWith("dolby vision") || t.startsWith("hdr") || t.startsWith("hlg")) return 1;
        if (t.equals("4k") || t.endsWith("p") && t.matches("\\d+p") || t.equals("sd")) return 0;
        return 2;
    }

    /**
     * Reel's own black and white format badge: a rounded tag with a small mark and the format in
     * bold capitals. The important ones (HDR, Dolby Vision, Atmos, TrueHD, DTS-HD / DTS:X) are solid
     * white with black writing; the rest are outlined.
     */
    private static final class Chip extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final String extra;
        private final int icon;
        private final boolean solid;
        private final float h;
        private final float pad;
        private final float gap;
        private final float iconW;
        private final float stroke;

        Chip(Context c, String label, String extra, int icon, boolean solid, int H) {
            super(c);
            this.label = label;
            this.extra = extra;
            this.icon = icon;
            this.solid = solid;
            h = H * 0.037f;
            pad = h * 0.36f;
            gap = h * 0.24f;
            iconW = h * 0.42f;
            stroke = Math.max(2f, H * 0.0022f);
            text.setTypeface(UiUtil.font(UiUtil.BOLD));
            text.setTextSize(h * 0.46f);
            text.setLetterSpacing(0.06f);
            text.setColor(solid ? 0xFF000000 : 0xFFFFFFFF);
            line.setStrokeWidth(stroke);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            float w = pad * 2 + text.measureText(label);
            if (icon != 0) w += iconW + gap;
            if (extra != null) w += gap * 2 + stroke + text.measureText(extra);
            setMeasuredDimension((int) Math.ceil(w), (int) h);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float r = h * 0.22f;
            int ink = solid ? 0xFF000000 : 0xFFFFFFFF;
            RectF box = new RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
            if (solid) {
                line.setStyle(Paint.Style.FILL);
                line.setColor(0xFFFFFFFF);
                cv.drawRoundRect(box, r, r, line);
            } else {
                line.setStyle(Paint.Style.STROKE);
                line.setColor(0xCCFFFFFF);
                cv.drawRoundRect(box, r, r, line);
            }
            float x = pad;
            float cy = h / 2f;
            line.setColor(ink);
            if (icon == 1) {
                float rad = iconW / 2f;
                line.setStyle(Paint.Style.STROKE);
                cv.drawCircle(x + rad, cy, rad - stroke / 2, line);
                line.setStyle(Paint.Style.FILL);
                cv.drawArc(new RectF(x, cy - rad, x + iconW, cy + rad), -90, 180, true, line);
                x += iconW + gap;
            } else if (icon == 2) {
                line.setStyle(Paint.Style.FILL);
                float bw = iconW / 5f;
                float[] hs = {0.45f, 0.9f, 0.62f};
                for (int i = 0; i < 3; i++) {
                    float bh = h * 0.5f * hs[i];
                    float bx = x + i * bw * 2f;
                    cv.drawRoundRect(new RectF(bx, cy - bh / 2f, bx + bw, cy + bh / 2f), bw / 2f, bw / 2f, line);
                }
                x += iconW + gap;
            }
            Paint.FontMetrics fm = text.getFontMetrics();
            float base = cy - (fm.ascent + fm.descent) / 2f;
            cv.drawText(label, x, base, text);
            x += text.measureText(label);
            if (extra != null) {
                x += gap;
                line.setStyle(Paint.Style.FILL);
                line.setColor(solid ? 0x66000000 : 0x80FFFFFF);
                cv.drawRect(x, h * 0.26f, x + stroke, h * 0.74f, line);
                x += stroke + gap;
                cv.drawText(extra, x, base, text);
            }
        }
    }
}
