package com.enthu.reel;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;

/** Full-screen photo viewer. Left and right move through the album. */
public class PhotoActivity extends Activity {

    private ArrayList<String> urls = new ArrayList<>();
    private int index;
    private ImageView image;
    private TextView counter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SoundFx.init(this);
        ArrayList<String> u = getIntent().getStringArrayListExtra("urls");
        if (u != null) urls = u;
        index = getIntent().getIntExtra("index", 0);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        counter = new TextView(this);
        counter.setTextColor(0xCCFFFFFF);
        counter.setTextSize(TypedValue.COMPLEX_UNIT_PX, dm.heightPixels * 0.03f);
        counter.setShadowLayer(8, 0, 2, 0xAA000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (dm.heightPixels * 0.04f);
        root.addView(counter, lp);
        setContentView(root);
        show();
    }

    private void show() {
        if (urls.isEmpty()) {
            finish();
            return;
        }
        Glide.with(this).load(urls.get(index)).fitCenter().into(image);
        counter.setText((index + 1) + " / " + urls.size());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            if (index < urls.size() - 1) {
                index++;
                SoundFx.move();
                show();
            } else {
                SoundFx.edge();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (index > 0) {
                index--;
                SoundFx.move();
                show();
            } else {
                SoundFx.edge();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
