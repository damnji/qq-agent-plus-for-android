package com.qqagent.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 安装器：把「解压 rootfs → 装 Node → 部署项目 → 装依赖 → 写配置」串起来。
 *
 * <p>每一步都设计成**可中断、可重入**：手机安装可能被来电、切后台打断，
 * 重进 App 应能从中断处继续（判断依据是产物文件在不在，而不是记一个进度数字）。
 */
final class Installer {
    private static final String TAG = "QAP.Installer";

    /** 安装步骤。 */
    enum Step {
        ROOTFS("解压 Ubuntu 系统", 0, 45),
        BINARIES("准备运行环境", 45, 48),
        NODE("安装 Node.js", 48, 70),
        APP("部署 QQ Agent", 70, 82),
        DEPS("安装依赖", 82, 92),
        CONFIG("写入配置", 92, 97),
        DONE("完成", 100, 100);

        final String label;
        final int fromPercent;
        final int toPercent;

        Step(String label, int from, int to) {
            this.label = label;
            this.fromPercent = from;
            this.toPercent = to;
        }
    }

    interface Listener {
        /** @param step 当前步骤；@param percent 0-100 总进度；@param message 明细 */
        void onProgress(Step step, int percent, String message);
        /** 需要用户交互（比如问 API Key）时暂停，由 UI 决定；这里只上报日志。 */
        void onLog(String line);
        void onFinished(boolean success, String errorMessage);
    }

    private final Context context;
    private final Container container;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Process currentProcess;

    Installer(Context context) {
        this.context = context.getApplicationContext();
        this.container = new Container(this.context);
    }

    void cancel() {
        cancelled.set(true);
        Process p = currentProcess;
        if (p != null) p.destroyForcibly();
    }

    boolean isCancelled() { return cancelled.get(); }

    /** 已装到哪一步（用于恢复）。 */
    Step detectStep() {
        if (!container.isRootfsReady()) return Step.ROOTFS;
        if (!container.isBinariesReady()) return Step.BINARIES;
        if (!container.isNodeReady()) return Step.NODE;
        File appDir = container.getAppDir();
        if (!new File(appDir, "package.json").exists()) return Step.APP;
        if (!new File(appDir, "node_modules/ws").exists()) return Step.DEPS;
        if (!new File(container.getDataDir(), "config.json").exists()) return Step.CONFIG;
        return Step.DONE;
    }

    /** 后台线程跑完整安装。 */
    void install(Listener listener) {
        new Thread(() -> {
            try {
                runInstall(listener);
                if (!cancelled.get()) {
                    listener.onFinished(true, null);
                } else {
                    listener.onFinished(false, "已取消");
                }
            } catch (Exception e) {
                Log.e(TAG, "install failed", e);
                listener.onFinished(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }, "qap-installer").start();
    }

    private void runInstall(Listener listener) throws Exception {
        Step start = detectStep();
        listener.onLog("从步骤「" + start.label + "」开始");

        // ── 1. rootfs ──
        if (start.ordinal() <= Step.ROOTFS.ordinal()) {
            report(listener, Step.ROOTFS, 0, "开始解压系统（约 60MB，需要几分钟）");
            container.extractRootfs((pct, msg) -> {
                if (cancelled.get()) throw new RuntimeException("已取消");
                int overall = Step.ROOTFS.fromPercent
                    + (Step.ROOTFS.toPercent - Step.ROOTFS.fromPercent) * pct / 100;
                report(listener, Step.ROOTFS, overall, msg);
            });
        }

        // ── 2. 二进制软链 ──
        if (start.ordinal() <= Step.BINARIES.ordinal()) {
            report(listener, Step.BINARIES, Step.BINARIES.fromPercent, "准备 proot 运行环境");
            if (!container.linkBinaries()) {
                throw new Exception("proot 二进制准备失败：APK 里缺少 arm64 的 jniLibs");
            }
            if (!container.isBinariesReady()) {
                throw new Exception("proot 二进制缺失，请确认安装的是 arm64 版本的 APK");
            }
            report(listener, Step.BINARIES, Step.BINARIES.toPercent, "运行环境就绪");
        }

        // ── 3. Node ──
        if (start.ordinal() <= Step.NODE.ordinal()) {
            if (cancelled.get()) throw new RuntimeException("已取消");
            report(listener, Step.NODE, Step.NODE.fromPercent, "安装 Node.js");
            installNode(listener);
        }

        // ── 4. 项目 ──
        if (start.ordinal() <= Step.APP.ordinal()) {
            if (cancelled.get()) throw new RuntimeException("已取消");
            report(listener, Step.APP, Step.APP.fromPercent, "部署 QQ Agent");
            deployApp(listener);
        }

        // ── 5. 依赖 ──
        if (start.ordinal() <= Step.DEPS.ordinal()) {
            if (cancelled.get()) throw new RuntimeException("已取消");
            report(listener, Step.DEPS, Step.DEPS.fromPercent, "安装依赖（npm ci）");
            installDeps(listener);
        }

        // ── 6. 配置 ──
        if (start.ordinal() <= Step.CONFIG.ordinal()) {
            if (cancelled.get()) throw new RuntimeException("已取消");
            report(listener, Step.CONFIG, Step.CONFIG.fromPercent, "写入配置");
            writeConfig(listener);
        }

        report(listener, Step.DONE, 100, "安装完成");
        listener.onLog("环境已就绪，可以启动服务了");
    }

    /** 把 assets 里的 Node arm64 tar.gz 解压到 rootfs 的 /usr/local。 */
    private void installNode(Listener listener) throws Exception {
        if (container.isNodeReady()) {
            report(listener, Step.NODE, Step.NODE.toPercent, "Node 已安装，跳过");
            return;
        }
        // Node 的 tar.gz 里顶层是 node-vX-linux-arm64/，要 strip 一层
        AssetManager assets = context.getAssets();
        String asset = "node-arm64.tar.gz";
        File stage = new File(container.getTmpDir(), "node-stage");
        if (stage.exists()) deleteRecursive(stage);
        //noinspection ResultOfMethodCallIgnored
        stage.mkdirs();

        report(listener, Step.NODE, Step.NODE.fromPercent + 2, "解压 Node 运行时（约 30MB）");
        try (InputStream in = assets.open(asset);
             java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(in, 65536)) {
            extractTarStrip(gz, stage, 1);
        }
        // 找到解出来的 node 目录
        File[] children = stage.listFiles();
        if (children == null || children.length == 0) {
            throw new Exception("Node 压缩包解压后为空");
        }
        File nodeRoot = children[0];
        File localUsr = new File(container.getRootfsDir(), "usr/local");
        if (!localUsr.exists() && !localUsr.mkdirs()) {
            throw new Exception("无法创建 /usr/local");
        }
        report(listener, Step.NODE, Step.NODE.fromPercent + 8, "安装到系统目录");
        copyTree(nodeRoot, localUsr);
        deleteRecursive(stage);

        // 校验
        if (!container.isNodeReady()) {
            throw new Exception("Node 安装失败：/usr/local/bin/node 不存在");
        }
        Container.ExecResult r = container.exec(
            "node -v && node -e \"import('node:sqlite').then(()=>console.log('sqlite-ok'))\"", 60000);
        listener.onLog(r.output);
        if (!r.output.contains("sqlite-ok")) {
            throw new Exception("Node 已装但 node:sqlite 不可用，版本可能太旧");
        }
        report(listener, Step.NODE, Step.NODE.toPercent, "Node 就绪");
    }

    /** 把 assets 里的项目源码解压到容器 /root/qq-agent-plus。 */
    private void deployApp(Listener listener) throws Exception {
        File appDir = container.getAppDir();
        if (new File(appDir, "package.json").exists()) {
            report(listener, Step.APP, Step.APP.toPercent, "项目已存在，跳过");
            return;
        }
        AssetManager assets = context.getAssets();
        report(listener, Step.APP, Step.APP.fromPercent + 2, "解压项目源码");
        try (InputStream in = assets.open("app.tar.gz");
             java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(in, 65536)) {
            extractTarStrip(gz, appDir, 1);
        }
        if (!new File(appDir, "package.json").exists()) {
            throw new Exception("项目解压后没有 package.json");
        }
        report(listener, Step.APP, Step.APP.toPercent, "项目已就位");
    }

    /** npm ci（用项目自带的 node_modules 优先，其次联网）。 */
    private void installDeps(Listener listener) throws Exception {
        File appDir = container.getAppDir();
        if (new File(appDir, "node_modules/ws").exists()) {
            report(listener, Step.DEPS, Step.DEPS.toPercent, "依赖已存在，跳过");
            return;
        }
        // 优先用随包附带的 node_modules（离线、快、不受墙影响）
        AssetManager assets = context.getAssets();
        boolean hasBundled = false;
        try {
            assets.open("node_modules.tar.gz").close();
            hasBundled = true;
        } catch (IOException ignored) {
            // 没有随包依赖
        }
        if (hasBundled) {
            report(listener, Step.DEPS, Step.DEPS.fromPercent + 2, "解压随包依赖");
            try (InputStream in = assets.open("node_modules.tar.gz");
                 java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(in, 65536)) {
                extractTarStrip(gz, appDir, 0);
            }
        } else {
            report(listener, Step.DEPS, Step.DEPS.fromPercent + 2,
                "联网安装依赖（可能需要几分钟）");
            Container.ExecResult r = container.exec(
                "cd /root/qq-agent-plus && "
                + "npm config set registry https://registry.npmmirror.com/ && "
                + "npm ci --omit=dev --no-audit --no-fund 2>&1 | tail -20",
                15 * 60 * 1000);
            listener.onLog(r.output);
            if (!r.isOk()) {
                throw new Exception("npm ci 失败（可在设置里改用随包依赖，或检查网络）");
            }
        }
        if (!new File(appDir, "node_modules/ws").exists()) {
            throw new Exception("依赖安装后仍缺 ws");
        }
        report(listener, Step.DEPS, Step.DEPS.toPercent, "依赖就绪");
    }

    /** 写 data/config.json（只补缺，不覆盖已有）。 */
    private void writeConfig(Listener listener) throws Exception {
        File cfg = new File(container.getDataDir(), "config.json");
        if (cfg.exists()) {
            report(listener, Step.CONFIG, Step.CONFIG.toPercent, "配置已存在，保留");
            return;
        }
        AppConfig ac = AppConfig.load(context);
        String json = ac.toConfigJson();
        try (FileOutputStream out = new FileOutputStream(cfg)) {
            out.write(json.getBytes("UTF-8"));
        }
        //noinspection ResultOfMethodCallIgnored
        cfg.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        cfg.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        cfg.setWritable(true, true);
        report(listener, Step.CONFIG, Step.CONFIG.toPercent, "配置已写入");
    }

    private void report(Listener listener, Step step, int percent, String message) {
        if (cancelled.get()) throw new RuntimeException("已取消");
        listener.onProgress(step, Math.max(0, Math.min(100, percent)), message);
    }

    // ── tar 工具（stripComponents 支持） ──

    private static void extractTarStrip(InputStream in, File destDir, int strip)
            throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("mkdirs failed: " + destDir);
        }
        byte[] header = new byte[512];
        String longName = null;
        while (true) {
            int got = readFully(in, header, 512);
            if (got < 512 || isZero(header)) break;
            String name = readStr(header, 0, 100);
            String prefix = readStr(header, 345, 155);
            if (prefix.length() > 0) name = prefix + "/" + name;
            long size = readOct(header, 124, 12);
            char type = (char) (header[156] & 0xFF);

            if (type == 'L') {
                byte[] nb = new byte[(int) size];
                readFully(in, nb, (int) size);
                longName = new String(nb, "UTF-8").trim();
                skipPad(in, size);
                continue;
            }
            if (longName != null) { name = longName; longName = null; }
            if (type == 'x' || type == 'g') { skip(in, size); skipPad(in, size); continue; }

            // strip 前 N 层
            String rel = name;
            for (int i = 0; i < strip; i++) {
                int slash = rel.indexOf('/');
                if (slash < 0) { rel = null; break; }
                rel = rel.substring(slash + 1);
            }
            if (rel == null || rel.isEmpty()) { skip(in, size); skipPad(in, size); continue; }
            while (rel.startsWith("./")) rel = rel.substring(2);
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.isEmpty()) { skip(in, size); skipPad(in, size); continue; }

            File target = new File(destDir, rel);
            if (!target.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                skip(in, size); skipPad(in, size); continue;
            }
            switch (type) {
                case '5': //noinspection ResultOfMethodCallIgnored
                    target.mkdirs(); break;
                case '2': {
                    String link = readStr(header, 157, 100);
                    File p = target.getParentFile();
                    if (p != null) //noinspection ResultOfMethodCallIgnored
                        p.mkdirs();
                    if (target.exists()) //noinspection ResultOfMethodCallIgnored
                        target.delete();
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(), java.nio.file.Paths.get(link));
                        }
                    } catch (Exception ignored) { }
                    break;
                }
                case '0':
                case '\0': {
                    File p = target.getParentFile();
                    if (p != null) //noinspection ResultOfMethodCallIgnored
                        p.mkdirs();
                    try (OutputStream out = new FileOutputStream(target)) {
                        copyExact(in, out, size);
                    }
                    long mode = readOct(header, 100, 8);
                    if ((mode & 0111) != 0) //noinspection ResultOfMethodCallIgnored
                        target.setExecutable(true, false);
                    break;
                }
                default: skip(in, size); break;
            }
            skipPad(in, size);
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("mkdirs " + dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else if (src.isFile()) {
            File p = dst.getParentFile();
            if (p != null && !p.exists()) //noinspection ResultOfMethodCallIgnored
                p.mkdirs();
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (src.canExecute()) //noinspection ResultOfMethodCallIgnored
                dst.setExecutable(true, false);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return off;
            off += n;
        }
        return len;
    }
    private static void skip(InputStream in, long n) throws IOException {
        long left = n; byte[] b = new byte[8192];
        while (left > 0) { int g = in.read(b, 0, (int) Math.min(b.length, left)); if (g < 0) return; left -= g; }
    }
    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512; if (pad > 0) skip(in, pad);
    }
    private static void copyExact(InputStream in, OutputStream out, long size) throws IOException {
        long left = size; byte[] b = new byte[65536];
        while (left > 0) {
            int g = in.read(b, 0, (int) Math.min(b.length, left));
            if (g < 0) throw new IOException("unexpected EOF");
            out.write(b, 0, g); left -= g;
        }
    }
    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }
    private static String readStr(byte[] b, int off, int len) {
        int end = off, max = off + len;
        while (end < max && b[end] != 0) end++;
        try { return new String(b, off, end - off, "UTF-8"); } catch (Exception e) { return ""; }
    }
    private static long readOct(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7F;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = 0; i < len; i++) {
            byte c = b[off + i];
            if (c >= '0' && c <= '7') v = v * 8 + (c - '0');
        }
        return v;
    }
}
