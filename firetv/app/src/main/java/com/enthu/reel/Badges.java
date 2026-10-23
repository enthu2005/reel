package com.enthu.reel;

import android.content.Context;
import android.graphics.Color;
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
            boolean strong = b[1].equals("1");
            TextView t = new TextView(c);
            t.setText(b[0]);
            // Apple TV style: small outlined grey tags.
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, H * 0.018f);
            t.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
            t.setSingleLine(true);
            int ph = (int) (H * 0.009f);
            int pv = (int) (H * 0.004f);
            t.setPadding(ph, pv, ph, pv);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(H * 0.006f);
            bg.setColor(0x00000000);
            bg.setStroke(Math.max(2, (int) (H * 0.0018f)), strong ? 0xCCFFFFFF : 0x80FFFFFF);
            t.setTextColor(strong ? 0xF2FFFFFF : 0xB3FFFFFF);
            t.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (H * 0.01f);
            row.addView(t, lp);
        }
    }
}
