package com.qqagent.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面：状态卡片 + 安装向导 + 启停控制 + 入口。
 *
 * <p>刻意用原生 View 而不是引入 Flutter/Compose：本机没有 Flutter SDK，
 * 而且这点 UI 用原生写反而更小更快（APK 体积、启动速度）。
 */
public class MainActivity extends Activity {
    private static final String TAG = "QAP.Main";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Container container;
    private AppConfig cfg;
    private Installer installer;

    // 视图
    private TextView tvStatusDot;
    private TextView tvStatus;
    private TextView tvDetail;
    private ProgressBar progressBar;
    private TextView tvProgressText;
    private LinearLayout stepsBox;
    private Button btnPrimary;
    private Button btnStop;
    private Button btnConsole;
    private Button btnLogs;
    private Button btnSettings;
    private LinearLayout logsBox;

    private boolean installing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        container = new Container(this);
        cfg = AppConfig.load(this);
        setContentView(buildUi());
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        startStatePolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(pollTask);
    }

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            refreshState();
            ui.postDelayed(this, 2000);
        }
    };

    private void startStatePolling() {
        ui.removeCallbacks(pollTask);
        ui.postDelayed(pollTask, 2000);
    }

    // ── UI 构建 ──

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(R.color.bg));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, dp(28));
        scroll.addView(root);

        // 标题
        TextView title = new TextView(this);
        title.setText(R.string.home_title);
        title.setTextColor(color(R.color.text_primary));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.home_subtitle);
        subtitle.setTextColor(color(R.color.text_secondary));
        subtitle.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        subtitle.setLayoutParams(subLp);
        root.addView(subtitle);

        // 状态卡片
        LinearLayout statusCard = card();
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(20);
        statusCard.setLayoutParams(cardLp);
        root.addView(statusCard);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.addView(statusRow);

        tvStatusDot = new TextView(this);
        tvStatusDot.setText("●");
        tvStatusDot.setTextSize(18);
        tvStatusDot.setTextColor(color(R.color.text_secondary));
        statusRow.addView(tvStatusDot);

        tvStatus = new TextView(this);
        tvStatus.setText(R.string.status_not_installed);
        tvStatus.setTextSize(17);
        tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvStatus.setTextColor(color(R.color.text_primary));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.leftMargin = dp(8);
        tvStatus.setLayoutParams(stLp);
        statusRow.addView(tvStatus);

        tvDetail = new TextView(this);
        tvDetail.setTextSize(12);
        tvDetail.setTextColor(color(R.color.text_secondary));
        tvDetail.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detLp.topMargin = dp(8);
        tvDetail.setLayoutParams(detLp);
        statusCard.addView(tvDetail);

        // 进度条
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pbLp.topMargin = dp(14);
        progressBar.setLayoutParams(pbLp);
        statusCard.addView(progressBar);

        tvProgressText = new TextView(this);
        tvProgressText.setTextSize(12);
        tvProgressText.setTextColor(color(R.color.accent_light));
        tvProgressText.setVisibility(View.GONE);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ptLp.topMargin = dp(6);
        tvProgressText.setLayoutParams(ptLp);
        statusCard.addView(tvProgressText);

        // 安装步骤列表
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        stepsBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(12);
        stepsBox.setLayoutParams(sbLp);
        statusCard.addView(stepsBox);

        // 按钮组
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(18);
        buttons.setLayoutParams(btnLp);
        root.addView(buttons);

        btnPrimary = new Button(this);
        btnPrimary.setText(R.string.btn_install);
        styleButton(btnPrimary, true);
        buttons.addView(btnPrimary);
        btnPrimary.setOnClickListener(v -> onPrimaryClick());

        btnStop = new Button(this);
        btnStop.setText(R.string.btn_stop);
        styleButton(btnStop, false);
        btnStop.setVisibility(View.GONE);
        buttons.addView(btnStop);
        btnStop.setOnClickListener(v -> stopAgent());

        btnConsole = new Button(this);
        btnConsole.setText(R.string.btn_console);
        styleButton(btnConsole, false);
        btnConsole.setVisibility(View.GONE);
        buttons.addView(btnConsole);
        btnConsole.setOnClickListener(v -> openConsole());

        btnLogs = new Button(this);
        btnLogs.setText(R.string.btn_logs);
        styleButton(btnLogs, false);
        buttons.addView(btnLogs);
        btnLogs.setOnClickListener(v -> showLogs());

        btnSettings = new Button(this);
        btnSettings.setText(R.string.btn_settings);
        styleButton(btnSettings, false);
        buttons.addView(btnSettings);
        btnSettings.setOnClickListener(v -> openSettings());

        // 日志预览
        LinearLayout logCard = card();
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lcLp.topMargin = dp(18);
        logCard.setLayoutParams(lcLp);
        root.addView(logCard);

        TextView logTitle = new TextView(this);
        logTitle.setText("最近日志");
        logTitle.setTextSize(14);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(color(R.color.text_primary));
        logCard.addView(logTitle);

        logsBox = new LinearLayout(this);
        logsBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = dp(8);
        logsBox.setLayoutParams(lbLp);
        logCard.addView(logsBox);

        return scroll;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.surface));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        return card;
    }

    private void styleButton(Button b, boolean primary) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(primary ? 52 : 44));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setAllCaps(false);
        b.setTextSize(primary ? 16 : 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (primary) {
            bg.setColor(color(R.color.accent));
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(color(R.color.surface_alt));
            b.setTextColor(color(R.color.accent_light));
        }
        b.setBackground(bg);
    }

    private int color(int res) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return getColor(res);
        }
        //noinspection deprecation
        return getResources().getColor(res);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── 状态刷新 ──

    private void refreshState() {
        if (installing) return;
        Installer.Step ready = detectReady();
        AgentRunner runner = AgentService.getRunner(this);
        boolean running = runner.isRunning();

        if (running) {
            tvStatusDot.setTextColor(color(R.color.success));
            tvStatus.setText(R.string.status_running);
            long up = runner.uptimeMs() / 1000;
            tvDetail.setText("控制台  http://127.0.0.1:" + cfg.consolePort
                + "\n运行时长  " + formatDuration(up)
                + "\n重启次数  " + runner.getRestartCount()
                + (runner.isConsoleUp() ? "\n控制台状态  可访问" : "\n控制台状态  启动中…"));
            btnPrimary.setText(R.string.btn_restart);
            btnPrimary.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            btnConsole.setVisibility(View.VISIBLE);
        } else if (ready == Installer.Step.DONE) {
            tvStatusDot.setTextColor(color(R.color.warning));
            tvStatus.setText(R.string.status_installed);
            tvDetail.setText("环境已安装，服务未运行\n控制台端口  " + cfg.consolePort
                + "\nOneBot  " + cfg.wsPort + " / " + cfg.httpPort);
            btnPrimary.setText(R.string.btn_start);
            btnPrimary.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.GONE);
            btnConsole.setVisibility(View.VISIBLE);
        } else {
            tvStatusDot.setTextColor(color(R.color.danger));
            tvStatus.setText(R.string.status_not_installed);
            tvDetail.setText("下一步：" + ready.label + "\n安装约需 3-8 分钟（取决于手机性能）");
            btnPrimary.setText(R.string.btn_install);
            btnPrimary.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.GONE);
            btnConsole.setVisibility(View.GONE);
        }
        refreshLogPreview(runner);
    }

    private Installer.Step detectReady() {
        if (!container.isRootfsReady()) return Installer.Step.ROOTFS;
        if (!container.isBinariesReady()) return Installer.Step.BINARIES;
        if (!container.isNodeReady()) return Installer.Step.NODE;
        if (!new java.io.File(container.getAppDir(), "package.json").exists())
            return Installer.Step.APP;
        if (!new java.io.File(container.getAppDir(), "node_modules/ws").exists())
            return Installer.Step.DEPS;
        if (!new java.io.File(container.getDataDir(), "config.json").exists())
            return Installer.Step.CONFIG;
        return Installer.Step.DONE;
    }

    private void refreshLogPreview(AgentRunner runner) {
        String tail = runner.tailLog(6);
        logsBox.removeAllViews();
        if (tail.trim().isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("（暂无日志）");
            tv.setTextSize(11);
            tv.setTextColor(color(R.color.text_secondary));
            logsBox.addView(tv);
            return;
        }
        for (String line : tail.split("\n")) {
            if (line.trim().isEmpty()) continue;
            TextView tv = new TextView(this);
            tv.setText(line);
            tv.setTextSize(11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(color(R.color.text_secondary));
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            logsBox.addView(tv);
        }
    }

    private static String formatDuration(long sec) {
        if (sec < 60) return sec + " 秒";
        long m = sec / 60;
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时 " + (m % 60) + " 分钟";
    }

    // ── 动作 ──

    private void onPrimaryClick() {
        Installer.Step ready = detectReady();
        if (ready == Installer.Step.DONE) {
            AgentRunner runner = AgentService.getRunner(this);
            if (runner.isRunning()) {
                Toast.makeText(this, "正在重启…", Toast.LENGTH_SHORT).show();
                AgentService.stop(this);
                ui.postDelayed(() -> AgentService.start(this), 1200);
            } else {
                startAgent();
            }
        } else {
            startInstall();
        }
    }

    private void startAgent() {
        if (!container.isReady() || !container.isNodeReady()) {
            Toast.makeText(this, "环境未安装完整", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "启动中…", Toast.LENGTH_SHORT).show();
        AgentService.start(this);
        ui.postDelayed(this::refreshState, 1500);
    }

    private void stopAgent() {
        AgentService.stop(this);
        ui.postDelayed(this::refreshState, 800);
    }

    private void openConsole() {
        Intent i = new Intent(this, ConsoleActivity.class);
        startActivity(i);
    }

    private void startInstall() {
        installing = true;
        progressBar.setVisibility(View.VISIBLE);
        tvProgressText.setVisibility(View.VISIBLE);
        stepsBox.setVisibility(View.VISIBLE);
        stepsBox.removeAllViews();
        btnPrimary.setEnabled(false);

        installer = new Installer(this);
        installer.install(new Installer.Listener() {
            @Override public void onProgress(Installer.Step step, int percent, String message) {
                ui.post(() -> {
                    progressBar.setProgress(percent);
                    tvProgressText.setText(percent + "%  " + step.label + " · " + message);
                    tvStatus.setText("安装中…");
                    tvStatusDot.setTextColor(color(R.color.accent));
                    renderSteps(step);
                });
            }
            @Override public void onLog(String line) {
                ui.post(() -> appendInstallLog(line));
            }
            @Override public void onFinished(boolean success, String errorMessage) {
                ui.post(() -> {
                    installing = false;
                    btnPrimary.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    tvProgressText.setVisibility(View.GONE);
                    if (success) {
                        Toast.makeText(MainActivity.this, "安装完成", Toast.LENGTH_LONG).show();
                        // 装完直接问是否启动
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("安装完成")
                            .setMessage("环境已就绪。是否现在启动服务？\n\n"
                                + "提示：默认是「只观察不发言」模式，"
                                + "需要在控制台里填模型、加群白名单、把运行模式切到 active，"
                                + "它才会真的在群里回复。")
                            .setPositiveButton("启动", (d, w) -> startAgent())
                            .setNegativeButton("稍后", null)
                            .show();
                    } else {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("安装失败")
                            .setMessage(errorMessage == null ? "未知错误" : errorMessage)
                            .setPositiveButton("重试", (d, w) -> startInstall())
                            .setNegativeButton("关闭", null)
                            .show();
                    }
                    refreshState();
                });
            }
        });
    }

    private void renderSteps(Installer.Step current) {
        stepsBox.removeAllViews();
        for (Installer.Step s : Installer.Step.values()) {
            if (s == Installer.Step.DONE) continue;
            TextView tv = new TextView(this);
            String mark;
            int col;
            if (s.ordinal() < current.ordinal()) {
                mark = "✓"; col = color(R.color.success);
            } else if (s == current) {
                mark = "●"; col = color(R.color.accent);
            } else {
                mark = "○"; col = color(R.color.text_secondary);
            }
            tv.setText(mark + "  " + s.label);
            tv.setTextSize(12);
            tv.setTextColor(col);
            tv.setPadding(0, dp(3), 0, dp(3));
            stepsBox.addView(tv);
        }
    }

    private void appendInstallLog(String line) {
        // 安装日志暂时只保留在 stepsBox 下方的一段文本里
        // 简化：追加到 logsBox
        TextView tv = new TextView(this);
        tv.setText(line);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(color(R.color.text_secondary));
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        logsBox.addView(tv, 0);
        while (logsBox.getChildCount() > 8) logsBox.removeViewAt(logsBox.getChildCount() - 1);
    }

    private void showLogs() {
        AgentRunner runner = AgentService.getRunner(this);
        String log = runner.tailLog(300);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(log.isEmpty() ? "（暂无日志）" : log);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(color(R.color.text_primary));
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("服务日志")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空", (d, w) -> runner.clearLog())
            .show();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }
}
