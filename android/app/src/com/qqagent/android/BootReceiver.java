package com.qqagent.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机自启：用户勾了"开机自动启动"才拉起前台服务。 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "QAP.Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        AppConfig cfg = AppConfig.load(context);
        if (!cfg.autoStart) {
            Log.i(TAG, "autoStart disabled, skip");
            return;
        }
        Container c = new Container(context);
        if (!c.isReady()) {
            Log.i(TAG, "container not ready, skip");
            return;
        }
        Log.i(TAG, "boot completed -> start service");
        AgentService.start(context);
    }
}
