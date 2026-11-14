package com.ringcall.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自动进入待机（可在设置里关掉，默认开启）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (Prefs.autoStart(context)) {
                try {
                    CallService.start(context);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
