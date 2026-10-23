package com.qqagent.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台服务：托管容器进程，防止被系统在后台杀掉。
 *
 * <p>为什么要它：安卓会在内存紧张时回收后台进程；挂成前台服务（带常驻通知）
 * 才能长时间稳定跑。proot 里没有 systemd，这就是"进程托管"的唯一可靠手段。
 */
public class AgentService extends Service {
    private static final String TAG = "QAP.Service";
    private static final String CHANNEL_ID = "qap_agent";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.qqagent.android.START";
    public static final String ACTION_STOP = "com.qqagent.android.STOP";

    private static AgentRunner runner;
    private static final Object LOCK = new Object();

    /** 供 UI 读取运行状态。 */
    static AgentRunner getRunner(Context context) {
        synchronized (LOCK) {
            if (runner == null) runner = new AgentRunner(context.getApplicationContext());
            return runner;
        }
    }

    static boolean isServiceRunning() {
        synchronized (LOCK) {
            return runner != null && runner.isRunning();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        getRunner(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAgent();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("正在启动…", false));
        AppConfig cfg = AppConfig.load(this);
        if (!cfg.keepAlive) {
            Log.i(TAG, "keepAlive disabled, but still running as foreground (user started)");
        }
        startAgent();
        // START_STICKY：被系统杀掉后尝试重建
        return START_STICKY;
    }

    private void startAgent() {
        AgentRunner r = getRunner(this);
        if (r.isRunning()) {
            updateNotification("运行中", true);
            return;
        }
        r.start(new AgentRunner.StateListener() {
            @Override public void onState(String state, String detail) {
                if ("running".equals(state)) {
                    updateNotification("运行中 · 控制台 " + AppConfig.load(AgentService.this).consolePort, true);
                } else if ("restarting".equals(state)) {
                    updateNotification("正在重启…", true);
                } else if ("stopped".equals(state)) {
                    updateNotification("已停止", false);
                }
            }
            @Override public void onLog(String line) {
                // 日志已落盘，通知里不刷
            }
        });
    }

    private void stopAgent() {
        AgentRunner r = getRunner(this);
        r.stop();
    }

    @Override
    public void onDestroy() {
        // 注意：这里不停 agent —— 用户可能只是关了 Activity。
        // 真正停止走 ACTION_STOP。
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── 通知 ──

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN);   // MIN：不打扰
            ch.setDescription(getString(R.string.notif_channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text, boolean running) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(R.string.notif_title_running))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(running)
            .setContentIntent(pi)
            .setPriority(Notification.PRIORITY_MIN);

        if (running) {
            Intent stop = new Intent(this, AgentService.class);
            stop.setAction(ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_btn_stop), stopPi).build());
        }
        return b.build();
    }

    private void updateNotification(String text, boolean running) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text, running));
    }

    /** 启动服务的便捷方法。 */
    static void start(Context context) {
        Intent i = new Intent(context, AgentService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    static void stop(Context context) {
        Intent i = new Intent(context, AgentService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }
}
