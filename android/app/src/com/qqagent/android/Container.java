package com.qqagent.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 容器管理：在 App 私有目录里搭一个 proot 的 Ubuntu 环境。
 *
 * <p>目录布局（与 AstrBot 泡泡版一致的思路）：
 * <pre>
 * files/                     ← getFilesDir()
 *   ├── usr/
 *   │   ├── bin/             ← proot / loader / bash / busybox 的软链
 *   │   └── tmp/             ← TMPDIR
 *   ├── home/
 *   └── var/lib/proot-distro/installed-rootfs/ubuntu/   ← rootfs
 * </pre>
 *
 * <p>为什么可执行文件要从 jniLibs 软链过来：targetSdk &gt; 28 之后，
 * App 私有目录里的文件没有执行权限，只有 APK 的 native lib 目录可以执行。
 */
final class Container {
    private static final String TAG = "QAP.Container";

    /** proot 相关二进制（在 jniLibs 里的名字 → 解出来的真名）。 */
    private static final String[][] BINARIES = {
        {"libproot.so", "proot"},
        {"libloader.so", "loader"},
        {"libbash.so", "bash"},
        {"libbusybox.so", "busybox"},
        {"liblibtalloc.so.2.so", "libtalloc.so.2"},
    };

    private final Context context;
    private final File filesDir;
    private final File usrDir;
    private final File binDir;
    private final File tmpDir;
    private final File homeDir;
    private final File rootfsDir;

    Container(Context context) {
        this.context = context.getApplicationContext();
        this.filesDir = this.context.getFilesDir();
        this.usrDir = new File(filesDir, "usr");
        this.binDir = new File(usrDir, "bin");
        this.tmpDir = new File(usrDir, "tmp");
        this.homeDir = new File(filesDir, "home");
        this.rootfsDir = new File(usrDir, "var/lib/proot-distro/installed-rootfs/ubuntu");
    }

    File getFilesDir() { return filesDir; }
    File getBinDir() { return binDir; }
    File getTmpDir() { return tmpDir; }
    File getHomeDir() { return homeDir; }
    File getRootfsDir() { return rootfsDir; }

    /** rootfs 是否已解压（用几个关键路径判断，别只看目录在不在）。 */
    boolean isRootfsReady() {
        return new File(rootfsDir, "bin").isDirectory()
            && new File(rootfsDir, "usr/bin/env").exists()
            && new File(rootfsDir, "etc").isDirectory();
    }

    /** proot 等二进制是否已就位。 */
    boolean isBinariesReady() {
        return new File(binDir, "proot").exists()
            && new File(binDir, "loader").exists()
            && new File(binDir, "bash").exists();
    }

    /** 整体环境是否可用。 */
    boolean isReady() {
        return isRootfsReady() && isBinariesReady();
    }

    /** 容器内 Node 是否已装好。 */
    boolean isNodeReady() {
        return new File(rootfsDir, "usr/local/bin/node").exists();
    }

    /** 容器内项目目录。 */
    File getAppDir() {
        return new File(rootfsDir, "root/qq-agent-plus");
    }

    /** 数据目录（放在 App 持久区，重装 rootfs 不丢）。 */
    File getDataDir() {
        File dir = new File(filesDir, "qap-data");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** NapCat 工作目录（同样持久化）。 */
    File getNapCatWorkDir() {
        File dir = new File(filesDir, "napcat-work");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 运行目录：PID 与日志。 */
    File getRunDir() {
        File dir = new File(filesDir, "run");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 确保基础目录都在。 */
    void ensureDirs() {
        for (File d : new File[]{usrDir, binDir, tmpDir, homeDir, rootfsDir,
                getDataDir(), getNapCatWorkDir(), getRunDir()}) {
            if (!d.exists() && !d.mkdirs()) {
                Log.w(TAG, "mkdirs failed: " + d);
            }
        }
    }

    /**
     * 把 jniLibs 里的可执行文件软链到 binDir。
     *
     * <p>为什么不拷贝：APK 的 nativeLibraryDir 里的文件可执行，而 files/ 下的不行
     * （targetSdk &gt; 28 的 W^X 限制）。软链过去即可执行。
     */
    boolean linkBinaries() {
        ensureDirs();
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        boolean allOk = true;
        for (String[] pair : BINARIES) {
            String sourceName = pair[0];
            String targetName = pair[1];
            File source = new File(nativeDir, sourceName);
            File target = new File(binDir, targetName);

            // 源文件不在就跳过（比如 libtalloc 在部分 ABI 上不存在）
            if (!source.exists()) {
                Log.w(TAG, "missing native lib: " + source);
                if (!"libtalloc.so.2".equals(targetName)) allOk = false;
                continue;
            }
            // 先删旧的（可能是上次的普通文件）
            if (target.exists() || isSymlink(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            try {
                // 用反射调 createSymbolicLink 以兼容 API 21+（os 包 API 26+ 才有公开方法）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.nio.file.Files.createSymbolicLink(
                        target.toPath(), source.toPath());
                } else {
                    // 老系统退回：拷贝（API < 26 的 W^X 限制较松，通常可执行）
                    copyFile(source, target);
                    //noinspection ResultOfMethodCallIgnored
                    target.setExecutable(true, false);
                }
                Log.i(TAG, "linked " + targetName + " -> " + source);
            } catch (Exception e) {
                Log.w(TAG, "symlink failed, fallback to copy: " + targetName, e);
                try {
                    copyFile(source, target);
                    //noinspection ResultOfMethodCallIgnored
                    target.setExecutable(true, false);
                } catch (IOException io) {
                    Log.e(TAG, "copy failed: " + targetName, io);
                    allOk = false;
                }
            }
        }
        // proot 需要能找到 loader 与 libtalloc：都放 bin 目录即可
        return allOk;
    }

    private static boolean isSymlink(File file) {
        try {
            File canon = file.getParentFile() == null ? file
                : new File(file.getParentFile().getCanonicalFile(), file.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /**
     * 解压 rootfs（assets/rootfs.tar.xz）到 rootfsDir。
     *
     * <p>Android 没有 tar/xz 命令，所以用 Java 自己解。xz 解压需要 XZInputStream，
     * 但 Android 标准库没有 —— 因此构建时就把 rootfs 转成 **tar.gz**（gzip 是内置的），
     * 或者用 busybox（我们已经有 arm64 的 busybox，但那是容器内的，不能在宿主跑）。
     * 这里用 gzip + 自己写的 tar 解析。
     *
     * @param progress 进度回调（0-100）
     */
    void extractRootfs(ProgressCallback progress) throws IOException {
        ensureDirs();
        if (isRootfsReady()) {
            Log.i(TAG, "rootfs already extracted");
            if (progress != null) progress.onProgress(100, "系统已就绪");
            return;
        }
        AssetManager assets = context.getAssets();
        String assetName = "rootfs.tar.gz";
        long totalSize = 0;
        // AssetManager.openFd 只对未压缩的 assets 有效；APK 里的 .gz 会被压缩存储，
        // 所以这里用 available() 粗估（只用于进度显示，不参与解压逻辑）。
        try (InputStream in = assets.open(assetName)) {
            totalSize = in.available();
        } catch (IOException ignored) { }
        Log.i(TAG, "extracting rootfs, " + (totalSize / 1024 / 1024) + " MB");

        try (InputStream raw = assets.open(assetName);
             java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(raw, 65536)) {
            extractTar(gz, rootfsDir, totalSize, progress);
        }
        if (!isRootfsReady()) {
            throw new IOException("rootfs 解压后关键路径缺失，可能镜像不完整");
        }
        Log.i(TAG, "rootfs extraction done");
        if (progress != null) progress.onProgress(100, "系统解压完成");
    }


    /** 极简 tar 解析器：够解 rootfs 就行（支持普通文件、目录、软链、硬链）。 */
    private void extractTar(InputStream in, File destDir, long totalSize,
                            ProgressCallback progress) throws IOException {
        byte[] header = new byte[512];
        long readSoFar = 0;
        long lastReport = 0;
        int fileCount = 0;
        // 长文件名（GNU tar 的 ././@LongLink）
        String longName = null;

        while (true) {
            int got = readFully(in, header, 512);
            if (got < 512) break;
            // 全零块 = 归档结束
            if (isZeroBlock(header)) break;

            String name = readString(header, 0, 100);
            String prefix = readString(header, 345, 155);
            if (prefix.length() > 0) name = prefix + "/" + name;
            long size = readOctal(header, 124, 12);
            char type = (char) (header[156] & 0xFF);

            // GNU 长文件名
            if (type == 'L') {
                byte[] nameBuf = new byte[(int) size];
                readFully(in, nameBuf, (int) size);
                longName = new String(nameBuf, "UTF-8").trim();
                skipPadding(in, size);
                continue;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }

            // 跳过 pax 扩展头
            if (type == 'x' || type == 'g') {
                skipFully(in, size);
                skipPadding(in, size);
                continue;
            }

            // 去掉开头的 ./ 或 /
            while (name.startsWith("./")) name = name.substring(2);
            while (name.startsWith("/")) name = name.substring(1);
            if (name.isEmpty() || name.equals(".")) {
                skipFully(in, size);
                skipPadding(in, size);
                continue;
            }

            File target = new File(destDir, name);
            // 防目录穿越
            if (!target.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                Log.w(TAG, "skip path traversal: " + name);
                skipFully(in, size);
                skipPadding(in, size);
                continue;
            }

            switch (type) {
                case '5': // 目录
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    break;
                case '2': // 软链
                    {
                        String linkTarget = readString(header, 157, 100);
                        File parent = target.getParentFile();
                        if (parent != null) //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        if (target.exists()) //noinspection ResultOfMethodCallIgnored
                            target.delete();
                        boolean linked = false;
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                java.nio.file.Files.createSymbolicLink(
                                    target.toPath(),
                                    java.nio.file.Paths.get(linkTarget));
                                linked = true;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "symlink failed: " + name + " -> " + linkTarget);
                        }
                        if (!linked) {
                            // API < 26（或创建失败）时的兜底：rootfs 里最关键的是
                            // bin -> usr/bin、lib -> usr/lib、sbin -> usr/sbin 这三条目录链接，
                            // 没有它们容器里 /bin/bash 就找不到。这里用"拷贝目录内容"代替软链。
                            recordDeferredLink(target, linkTarget);
                        }
                        break;
                    }
                case '1': // 硬链
                    {
                        String linkTarget = readString(header, 157, 100);
                        while (linkTarget.startsWith("./")) linkTarget = linkTarget.substring(2);
                        File src = new File(destDir, linkTarget);
                        File parent = target.getParentFile();
                        if (parent != null) //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                java.nio.file.Files.createLink(
                                    target.toPath(), src.toPath());
                            } else {
                                copyFile(src, target);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "hardlink failed: " + name);
                        }
                        break;
                    }
                case '0':
                case '\0': // 普通文件
                    {
                        File parent = target.getParentFile();
                        if (parent != null) //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        try (OutputStream out = new FileOutputStream(target)) {
                            copyExactly(in, out, size);
                        }
                        // 保留可执行位：rootfs 里的 /usr/bin/* 都要能跑
                        long mode = readOctal(header, 100, 8);
                        if ((mode & 0111) != 0) {
                            //noinspection ResultOfMethodCallIgnored
                            target.setExecutable(true, false);
                        }
                        fileCount++;
                        break;
                    }
                default:
                    // 设备文件、FIFO 等在 Android 上没意义，跳过内容
                    skipFully(in, size);
                    break;
            }
            skipPadding(in, size);
            readSoFar += 512 + ((size + 511) / 512) * 512;

            if (progress != null && totalSize > 0 && readSoFar - lastReport > totalSize / 100) {
                lastReport = readSoFar;
                int pct = (int) Math.min(99, readSoFar * 100 / totalSize);
                progress.onProgress(pct, "解压系统文件（" + fileCount + " 个）");
            }
        }
        Log.i(TAG, "tar extracted " + fileCount + " files");
        resolveDeferredLinks(destDir);
    }

    // ── 软链兜底：API < 26 建不了符号链接时，用拷贝代替 ──
    private final List<String[]> deferredLinks = new ArrayList<>();

    private void recordDeferredLink(File target, String linkTarget) {
        deferredLinks.add(new String[]{target.getAbsolutePath(), linkTarget});
    }

    /**
     * 处理解压时没能建立的符号链接。
     *
     * <p>rootfs 里最关键的是三条目录链接：<code>bin -&gt; usr/bin</code>、
     * <code>lib -&gt; usr/lib</code>、<code>sbin -&gt; usr/sbin</code>。
     * 没有它们，容器里 <code>/bin/bash</code> 就找不到，proot 直接起不来。
     * 这里在解压完成后统一处理：能建软链就建，不能就把目标目录内容拷过去。
     */
    private void resolveDeferredLinks(File destDir) {
        if (deferredLinks.isEmpty()) return;
        Log.i(TAG, "resolving " + deferredLinks.size() + " deferred symlinks");
        for (String[] pair : deferredLinks) {
            File link = new File(pair[0]);
            String linkTarget = pair[1];
            if (link.exists()) continue;
            // 目标路径：相对链接相对于 link 的父目录；绝对链接相对于 destDir
            File resolved = linkTarget.startsWith("/")
                ? new File(destDir, linkTarget.substring(1))
                : new File(link.getParentFile(), linkTarget);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.nio.file.Files.createSymbolicLink(
                        link.toPath(), java.nio.file.Paths.get(linkTarget));
                    continue;
                }
            } catch (Exception ignored) { }
            // 建不了软链：拷贝内容（只对目录做，文件级链接很少且不影响启动）
            if (resolved.isDirectory()) {
                try {
                    link.mkdirs();
                    copyDirContents(resolved, link);
                    Log.i(TAG, "materialized dir link " + link.getName() + " from " + resolved);
                } catch (Exception e) {
                    Log.w(TAG, "materialize dir link failed: " + link + " -> " + resolved, e);
                }
            }
        }
        deferredLinks.clear();
    }

    private static void copyDirContents(File srcDir, File dstDir) throws IOException {
        File[] kids = srcDir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            File dst = new File(dstDir, kid.getName());
            if (kid.isDirectory()) {
                if (!dst.exists() && !dst.mkdirs()) continue;
                copyDirContents(kid, dst);
            } else if (kid.isFile() && !dst.exists()) {
                try (InputStream in = new java.io.FileInputStream(kid);
                     OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (kid.canExecute()) //noinspection ResultOfMethodCallIgnored
                    dst.setExecutable(true, false);
            }
        }
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return off;
            off += n;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int got = in.read(buf, 0, want);
            if (got < 0) return;
            left -= got;
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long size)
            throws IOException {
        long left = size;
        byte[] buf = new byte[65536];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int got = in.read(buf, 0, want);
            if (got < 0) throw new IOException("unexpected EOF in tar entry");
            out.write(buf, 0, got);
            left -= got;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skipFully(in, pad);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static String readString(byte[] buf, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && buf[end] != 0) end++;
        try {
            return new String(buf, off, end - off, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static long readOctal(byte[] buf, int off, int len) {
        // 可能是 8 进制字符串，也可能是 base-256（高位为 1）
        if ((buf[off] & 0x80) != 0) {
            long value = buf[off] & 0x7F;
            for (int i = 1; i < len; i++) {
                value = (value << 8) | (buf[off + i] & 0xFF);
            }
            return value;
        }
        long value = 0;
        for (int i = 0; i < len; i++) {
            byte b = buf[off + i];
            if (b == 0 || b == ' ') continue;
            if (b < '0' || b > '7') continue;
            value = value * 8 + (b - '0');
        }
        return value;
    }

    /**
     * 在容器里执行命令（同步，返回输出）。
     *
     * @param command 要执行的 shell 命令（会以 `bash -lc "eval \"$COMMAND\""` 方式跑）
     */
    ExecResult exec(String command, long timeoutMs) {
        ensureDirs();
        List<String> cmd = buildProotCommand(command);
        Log.i(TAG, "exec: " + command);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // 清空环境：宿主环境变量（LD_PRELOAD 等）会干扰容器
        pb.environment().clear();
        pb.environment().put("PATH", binDir.getAbsolutePath() + ":/system/bin:/system/xbin");
        pb.environment().put("HOME", filesDir.getAbsolutePath());
        pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());

        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecResult(-1, output.toString() + "\n[超时]");
            }
            return new ExecResult(process.exitValue(), output.toString());
        } catch (Exception e) {
            Log.e(TAG, "exec failed", e);
            return new ExecResult(-1, output + "\n[异常] " + e);
        }
    }

    /** 构造 proot 启动命令（与 AstrBot 泡泡版一致的那套参数）。 */
    List<String> buildProotCommand(String command) {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(binDir, "proot").getAbsolutePath());
        cmd.add("-0");                                   // 伪装 root
        cmd.add("-r");
        cmd.add(rootfsDir.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add("/dev/pts");
        // /dev/shm 绑到 tmp：容器里没有独立 shm
        cmd.add("-b");
        cmd.add(tmpDir.getAbsolutePath() + ":" + tmpDir.getAbsolutePath());
        cmd.add("-b");
        cmd.add(tmpDir.getAbsolutePath() + ":/dev/shm");
        cmd.add("-b");
        cmd.add("/proc/self/fd:/dev/fd");
        cmd.add("-b");
        cmd.add("/proc/self/fd/0:/dev/stdin");
        cmd.add("-b");
        cmd.add("/proc/self/fd/1:/dev/stdout");
        cmd.add("-b");
        cmd.add("/proc/self/fd/2:/dev/stderr");
        // 把 App 的数据目录与 NapCat 工作目录挂进容器（持久化）
        cmd.add("-b");
        cmd.add(getDataDir().getAbsolutePath() + ":/qap-data");
        cmd.add("-b");
        cmd.add(getNapCatWorkDir().getAbsolutePath() + ":/napcat-work");
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("TERM=xterm-256color");
        cmd.add("LANG=C.UTF-8");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("PROOT_LOADER=" + new File(binDir, "loader").getAbsolutePath());
        cmd.add("LD_LIBRARY_PATH=" + binDir.getAbsolutePath());
        cmd.add("PROOT_TMP_DIR=" + tmpDir.getAbsolutePath());
        cmd.add("COMMAND_TO_EXEC=" + command);
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add("eval \"$COMMAND_TO_EXEC\"");
        return cmd;
    }

    interface ProgressCallback {
        void onProgress(int percent, String message);
    }

    static final class ExecResult {
        final int exitCode;
        final String output;
        ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
        boolean isOk() { return exitCode == 0; }
        @Override public String toString() {
            return "exit=" + exitCode + "\n" + output;
        }
    }
}
