package com.enthu.reel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * YouTube's official embedded player in a WebView, set up for previews: starts muted (always
 * allowed), turns the sound up once it's playing, carries on if it pauses by itself, and is zoomed
 * slightly so YouTube's title bar and logo sit outside the frame. It never takes the remote's focus.
 */
final class YouTubeEmbed {
    interface Events {
        void playing();

        void ended();
    }

    private YouTubeEmbed() {
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static WebView create(Activity act, String videoId, float volume, final Events ev) {
        final Handler main = new Handler(Looper.getMainLooper());
        WebView wv = new WebView(act);
        wv.setBackgroundColor(0xFF000000);
        wv.setFocusable(false);
        wv.setFocusableInTouchMode(false);
        wv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void playing() {
                main.post(ev::playing);
            }

            @JavascriptInterface
            public void ended() {
                main.post(ev::ended);
            }
        }, "Reel");
        int vol = Math.round(volume * 100);
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "#p{position:absolute;top:-18%;left:-18%;width:136%;height:136%;pointer-events:none}</style>"
                + "</head><body><div id='p'></div>"
                + "<script src='https://www.youtube.com/iframe_api'></script><script>"
                + "var player,wanted=true,unmuted=false,unmuteAt=0,stayMuted=false,told=false;"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + videoId + "',"
                + "playerVars:{autoplay:1,mute:1,controls:0,rel:0,playsinline:1,iv_load_policy:3,disablekb:1,"
                + "fs:0,modestbranding:1,cc_load_policy:0,showinfo:0},events:{"
                + "onReady:function(e){e.target.mute();e.target.playVideo();},"
                + "onStateChange:function(e){"
                + "if(e.data==1){if(!told){told=true;Reel.playing();}"
                + "if(!unmuted&&!stayMuted&&" + vol + ">0){unmuted=true;setTimeout(function(){unmuteAt=Date.now();"
                + "player.unMute();player.setVolume(" + vol + ");},500);}}"
                + "if(e.data==2&&wanted){if(unmuted&&!stayMuted&&Date.now()-unmuteAt<1500){stayMuted=true;player.mute();}"
                + "setTimeout(function(){if(wanted)player.playVideo();},150);}"
                + "if(e.data==0)Reel.ended();},"
                + "onError:function(e){Reel.ended();}}});}"
                + "function vol(v){if(!player||!player.setVolume||stayMuted)return;unmuted=true;unmuteAt=Date.now();"
                + "player.setVolume(v);if(v>0)player.unMute();else player.mute();}"
                + "function pause(){wanted=false;if(player)player.pauseVideo();}"
                + "function resume(){wanted=true;if(player)player.playVideo();}"
                + "</script></body></html>";
        wv.loadDataWithBaseURL("https://www.enthujan.com/", html, "text/html", "UTF-8", null);
        return wv;
    }

    static void setVolume(WebView wv, float v) {
        if (wv != null) wv.evaluateJavascript("vol(" + Math.round(v * 100) + ")", null);
    }

    static void pause(WebView wv, boolean pause) {
        if (wv != null) wv.evaluateJavascript(pause ? "pause()" : "resume()", null);
    }

    static void destroy(WebView wv) {
        if (wv == null) return;
        try {
            wv.loadUrl("about:blank");
            if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
            wv.destroy();
        } catch (Exception ignored) {
        }
    }
}
