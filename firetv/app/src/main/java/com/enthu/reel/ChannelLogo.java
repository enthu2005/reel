package com.enthu.reel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Locale;

/**
 * A logo drawn for a Live channel: a rounded tile in a colour that suits its kind, with a simple picture for the
 * common kinds (action, comedy, sci-fi, horror, kids) and the channel's first letters for anything else.
 */
final class ChannelLogo extends View {
    private final String name;
    private final int kind;
    private final int top;
    private final int bottom;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private static final int ACTION = 1, COMEDY = 2, SCIFI = 3, HORROR = 4, KIDS = 5, OTHER = 0;

    private ChannelLogo(Context c, String name) {
        super(c);
        this.name = name == null ? "" : name;
        String n = this.name.toLowerCase(Locale.ROOT);
        int k = OTHER;
        int hue;
        if (n.contains("action") || n.contains("adventure")) { k = ACTION; hue = 14; }
        else if (n.contains("comedy") || n.contains("sitcom")) { k = COMEDY; hue = 44; }
        else if (n.contains("sci") || n.contains("fantasy")) { k = SCIFI; hue = 215; }
        else if (n.contains("horror") || n.contains("thriller")) { k = HORROR; hue = 350; }
        else if (n.contains("kid") || n.contains("family") || n.contains("animation") || n.contains("cartoon")) { k = KIDS; hue = 285; }
        else if (n.contains("drama")) hue = 160;
        else hue = Math.floorMod(n.hashCode(), 360); // a colour of its own for channels you made
        kind = k;
        top = Color.HSVToColor(new float[]{hue, 0.62f, 0.95f});
        bottom = Color.HSVToColor(new float[]{hue, 0.8f, 0.55f});
    }

    /** A square logo tile of [size] pixels for this channel name. */
    static View tile(Context c, String name, int size) {
        ChannelLogo v = new ChannelLogo(c, name);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        return v;
    }

    private String letters() {
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (w.isEmpty() || !Character.isLetterOrDigit(w.charAt(0))) continue;
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.length() == 0 ? "TV" : sb.toString();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        paint.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(new RectF(0, 0, w, h), w * 0.22f, w * 0.22f, paint);
        paint.setShader(null);
        paint.setColor(Color.WHITE);
        float cx = w / 2, cy = h / 2, u = w / 10f; // u: a tenth of the tile
        switch (kind) {
            case ACTION: // a lightning bolt
                path.reset();
                path.moveTo(cx + 1.2f * u, cy - 3.6f * u);
                path.lineTo(cx - 2.2f * u, cy + 0.6f * u);
                path.lineTo(cx - 0.1f * u, cy + 0.6f * u);
                path.lineTo(cx - 1.2f * u, cy + 3.6f * u);
                path.lineTo(cx + 2.2f * u, cy - 0.8f * u);
                path.lineTo(cx + 0.1f * u, cy - 0.8f * u);
                path.close();
                cv.drawPath(path, paint);
                break;
            case COMEDY: // a smile
                cv.drawCircle(cx, cy, 3.6f * u, paint);
                paint.setColor(bottom);
                cv.drawCircle(cx - 1.3f * u, cy - 0.9f * u, 0.5f * u, paint);
                cv.drawCircle(cx + 1.3f * u, cy - 0.9f * u, 0.5f * u, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(0.45f * u);
                paint.setStrokeCap(Paint.Cap.ROUND);
                cv.drawArc(new RectF(cx - 1.9f * u, cy - 1.2f * u, cx + 1.9f * u, cy + 2.2f * u), 20, 140, false, paint);
                break;
            case SCIFI: // a planet with a ring
                cv.drawCircle(cx, cy, 2.4f * u, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(0.5f * u);
                cv.save();
                cv.rotate(-22, cx, cy);
                cv.drawOval(new RectF(cx - 4.2f * u, cy - 1.2f * u, cx + 4.2f * u, cy + 1.2f * u), paint);
                cv.restore();
                break;
            case HORROR: // a ghost
                path.reset();
                path.moveTo(cx - 2.8f * u, cy + 3.4f * u);
                path.lineTo(cx - 2.8f * u, cy - 0.6f * u);
                path.arcTo(new RectF(cx - 2.8f * u, cy - 3.6f * u, cx + 2.8f * u, cy + 2.0f * u), 180, 180);
                path.lineTo(cx + 2.8f * u, cy + 3.4f * u);
                path.lineTo(cx + 1.4f * u, cy + 2.4f * u);
                path.lineTo(cx, cy + 3.4f * u);
                path.lineTo(cx - 1.4f * u, cy + 2.4f * u);
                path.close();
                cv.drawPath(path, paint);
                paint.setColor(bottom);
                cv.drawCircle(cx - 1.0f * u, cy - 0.6f * u, 0.55f * u, paint);
                cv.drawCircle(cx + 1.0f * u, cy - 0.6f * u, 0.55f * u, paint);
                break;
            case KIDS: // a star
                path.reset();
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float r = (i % 2 == 0 ? 3.8f : 1.6f) * u;
                    float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                cv.drawPath(path, paint);
                break;
            default: { // the first letters
                paint.setTypeface(UiUtil.font(UiUtil.SEMIBOLD));
                String t = letters();
                paint.setTextSize(t.length() > 1 ? h * 0.42f : h * 0.52f);
                paint.setTextAlign(Paint.Align.CENTER);
                cv.drawText(t, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
            }
        }
    }
}
