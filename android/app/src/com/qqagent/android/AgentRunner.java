package com.qqagent.android;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 服务守护：负责把容器里的 node 跑起来，挂了重起。
 *
 * <p>等价于 docs/PHONE.md 里的 scripts/phone/supervise.sh，但用 Java 实现，
 * 好处是能跟随前台服务的生命周期（进程被杀时一起停，不会留孤儿）。
 */
final class AgentRunner {
    private static final String TAG = "QAP.Runner";
    private static final int RESTART_DELAY_MS = 5000;

    private final Context context;
    private final Container container;
    private final File logFile;
    private final File pidFile;

    private volatile boolean shouldRun;
    private volatile Process process;
    private Thread loopThread;
    private int restartCount;
    private long startedAt;

    interface StateListener {
        void onState(String state, String detail);
        void onLog(String line);
    }

    AgentRunner(Context context) {
        this.context = context.getApplicationContext();
        this.container = new Container(this.context);
        this.logFile = new File(container.getRunDir(), "server.log");
        this.pidFile = new File(container.getRunDir(), "server.pid");
    }

    File getLogFile() { return logFile; }

    boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    long uptimeMs() {
        return isRunning() ? System.currentTimeMillis() - startedAt : 0;
    }

    int getRestartCount() { return restartCount; }

    /** 启动守护线程。 */
    void start(StateListener listener) {
        if (shouldRun) {
            Log.i(TAG, "already running");
            return;
        }
        shouldRun = true;
        restartCount = 0;
        loopThread = new Thread(() -> loop(listener), "qap-supervisor");
        loopThread.start();
    }

    void stop() {
        shouldRun = false;
        Process p = process;
        if (p != null) {
            p.destroy();  // SIGTERM 等价
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) p.destroyForcibly();
        }
        process = null;
        //noinspection ResultOfMethodCallIgnored
        pidFile.delete();
        Thread t = loopThread;
        if (t != null) t.interrupt();
        loopThread = null;
    }

    private void loop(StateListener listener) {
        while (shouldRun) {
            try {
                runOnce(listener);
            } catch (Exception e) {
                Log.e(TAG, "run failed", e);
                appendLog("[supervisor] 启动失败：" + e.getMessage());
                listener.onLog("[supervisor] 启动失败：" + e.getMessage());
            }
            if (!shouldRun) break;
            restartCount++;
            appendLog("[supervisor] " + (RESTART_DELAY_MS / 1000) + " 秒后重起（第 "
                + restartCount + " 次）");
            listener.onState("restarting", "等待重启");
            try {
                Thread.sleep(RESTART_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        listener.onState("stopped", null);
    }

    private void runOnce(StateListener listener) throws Exception {
        if (!container.isReady()) throw new Exception("容器环境未就绪");
        if (!container.isNodeReady()) throw new Exception("Node 未安装");
        File appDir = container.getAppDir();
        if (!new File(appDir, "node_modules/ws").exists()) {
            throw new Exception("依赖未安装");
        }

        // 在容器里跑 node src/server.js（前台，输出重定向到日志）
        String cmd = "cd /root/qq-agent-plus && "
            + "export QQ_AGENT_DATA_DIR=/qap-data && "
            + "exec node src/server.js";
        List<String> full = container.buildProotCommand(cmd);

        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("PATH", container.getBinDir().getAbsolutePath()
            + ":/system/bin:/system/xbin");
        pb.environment().put("HOME", container.getFilesDir().getAbsolutePath());
        pb.environment().put("TMPDIR", container.getTmpDir().getAbsolutePath());

        appendLog("=== " + now() + " 启动 node src/server.js ===");
        process = pb.start();
        startedAt = System.currentTimeMillis();
        //noinspection ResultOfMethodCallIgnored
        writePid(process);
        listener.onState("running", null);

        // 实时读输出写日志
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(line);
                listener.onLog(line);
            }
        }
        int code = process.waitFor();
        appendLog("=== node 退出，code=" + code + " ===");
        process = null;
        //noinspection ResultOfMethodCallIgnored
        pidFile.delete();
    }

    private void writePid(Process p) {
        try {
            long pid = -1;
            try {
                // Android 9+ 有 Process.pid()；反射兜底
                java.lang.reflect.Method m = Process.class.getMethod("pid");
                Object v = m.invoke(p);
                if (v instanceof Long) pid = (Long) v;
            } catch (Exception ignored) { }
            if (pid <= 0) {
                // 老系统读 /proc
                java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                pid = f.getInt(p);
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(pidFile), "UTF-8")) {
                w.write(String.valueOf(pid));
            }
        } catch (Exception e) {
            Log.w(TAG, "write pid failed", e);
        }
    }

    private void appendLog(String line) {
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(logFile, true), "UTF-8")) {
            w.write(line);
            w.write('\n');
        } catch (Exception e) {
            Log.w(TAG, "append log failed", e);
        }
    }

    /** 清空日志（设置里"清空日志"用）。 */
    void clearLog() {
        //noinspection ResultOfMethodCallIgnored
        logFile.delete();
    }

    /** 读日志尾部。 */
    String tailLog(int maxLines) {
        if (!logFile.exists()) return "";
        java.util.ArrayDeque<String> ring = new java.util.ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(logFile), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                ring.addLast(line);
                if (ring.size() > maxLines) ring.removeFirst();
            }
        } catch (Exception e) {
            return "[读日志失败] " + e;
        }
        StringBuilder sb = new StringBuilder();
        for (String l : ring) sb.append(l).append('\n');
        return sb.toString();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** 探测控制台是否已可访问（HTTP 200）。 */
    boolean isConsoleUp() {
        AppConfig cfg = AppConfig.load(context);
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(cfg.consoleUrl() + "/").openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
