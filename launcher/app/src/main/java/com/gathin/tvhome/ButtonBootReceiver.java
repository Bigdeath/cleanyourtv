package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class ButtonBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent i=new Intent(context,ButtonKeeperService.class);
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(i);else context.startService(i);
        }
    }
}
