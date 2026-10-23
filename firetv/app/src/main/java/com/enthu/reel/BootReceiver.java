package com.enthu.reel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After the Fire TV restarts, put the Up Next row back on the home screen. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pr = goAsync();
        TvRecommendations.refresh(context, pr::finish);
    }
}
