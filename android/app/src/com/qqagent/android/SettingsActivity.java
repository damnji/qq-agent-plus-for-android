package com.qqagent.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 设置：端口、token、模型、白名单、保活开关。 */
public class SettingsActivity extends Activity {
    private AppConfig cfg;
    private EditText etConsolePort, etWsPort, etHttpPort;
    private EditText etToken, etBaseUrl, etApiKey, etModel, etGroups;
    private CheckBox cbAutoStart, cbKeepAlive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = AppConfig.load(this);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(R.color.bg));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(24), pad, dp(32));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("改动会在下次启动服务时生效。带 * 的项必须与 NapCat 侧一致。");
        hint.setTextSize(12);
        hint.setTextColor(color(R.color.text_secondary));
        hint.setPadding(0, dp(6), 0, dp(4));
        root.addView(hint);

        // ── 端口 ──
        addSection(root, "端口");
        etConsolePort = addField(root, "控制台端口", String.valueOf(cfg.consolePort),
            InputType.TYPE_CLASS_NUMBER);
        etWsPort = addField(root, "OneBot WebSocket 端口 *", String.valueOf(cfg.wsPort),
            InputType.TYPE_CLASS_NUMBER);
        etHttpPort = addField(root, "OneBot HTTP 端口 *", String.valueOf(cfg.httpPort),
            InputType.TYPE_CLASS_NUMBER);

        // ── 安全 ──
        addSection(root, "访问令牌");
        etToken = addField(root, "令牌（与 NapCat 的 token 一致）*", cfg.token,
            InputType.TYPE_CLASS_TEXT);
        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenRow.setPadding(0, dp(4), 0, 0);
        root.addView(tokenRow);
        Button gen = smallButton("重新生成");
        gen.setOnClickListener(v -> {
            etToken.setText(AppConfig.randomToken());
            Toast.makeText(this, "已生成新令牌，记得同步改 NapCat", Toast.LENGTH_LONG).show();
        });
        tokenRow.addView(gen);

        // ── 模型 ──
        addSection(root, "模型");
        etBaseUrl = addField(root, "API 地址（写到 /v1 这层）", cfg.modelBaseUrl,
            InputType.TYPE_CLASS_TEXT);
        etApiKey = addField(root, "API Key", cfg.modelApiKey,
            InputType.TYPE_CLASS_TEXT);
        etModel = addField(root, "模型名称", cfg.model,
            InputType.TYPE_CLASS_TEXT);

        // ── 白名单 ──
        addSection(root, "群白名单");
        etGroups = addField(root, "允许的群号（逗号分隔，留空=不回复任何群）",
            cfg.allowGroups, InputType.TYPE_CLASS_TEXT);

        // ── 运行 ──
        addSection(root, "运行");
        cbAutoStart = addCheck(root, "开机自动启动", cfg.autoStart);
        cbKeepAlive = addCheck(root, "后台保活（前台服务）", cfg.keepAlive);

        // ── 保存 ──
        Button save = new Button(this);
        save.setText(R.string.save);
        save.setAllCaps(false);
        save.setTextSize(16);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.accent));
        bg.setCornerRadius(dp(10));
        save.setBackground(bg);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        sLp.topMargin = dp(24);
        save.setLayoutParams(sLp);
        save.setOnClickListener(v -> onSave());
        root.addView(save);

        // ── 危险操作 ──
        addSection(root, "维护");
        Button backup = smallButton("导出配置到剪贴板");
        backup.setOnClickListener(v -> exportConfig());
        root.addView(backup);

        Button reset = smallButton("重置环境（删除 rootfs 与数据，不可恢复）");
        reset.setOnClickListener(v -> confirmReset());
        root.addView(reset);

        return scroll;
    }

    private void addSection(LinearLayout root, String name) {
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(color(R.color.accent_light));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        root.addView(tv);
    }

    private EditText addField(LinearLayout root, String label, String value, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(color(R.color.text_secondary));
        tv.setPadding(0, dp(8), 0, dp(4));
        root.addView(tv);

        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setTextSize(14);
        et.setTextColor(color(R.color.text_primary));
        et.setInputType(inputType);
        et.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.surface_alt));
        bg.setCornerRadius(dp(8));
        et.setBackground(bg);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(et, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private CheckBox addCheck(LinearLayout root, String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(14);
        cb.setTextColor(color(R.color.text_primary));
        cb.setChecked(checked);
        cb.setPadding(0, dp(6), 0, dp(6));
        root.addView(cb);
        return cb;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(color(R.color.accent_light));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(0, dp(6), 0, dp(6));
        return b;
    }

    private void onSave() {
        try {
            int cp = Integer.parseInt(etConsolePort.getText().toString().trim());
            int wp = Integer.parseInt(etWsPort.getText().toString().trim());
            int hp = Integer.parseInt(etHttpPort.getText().toString().trim());
            if (cp < 1024 || cp > 65535) throw new IllegalArgumentException("控制台端口应在 1024-65535");
            if (wp < 1024 || wp > 65535) throw new IllegalArgumentException("WS 端口应在 1024-65535");
            if (hp < 1024 || hp > 65535) throw new IllegalArgumentException("HTTP 端口应在 1024-65535");
            cfg.consolePort = cp;
            cfg.wsPort = wp;
            cfg.httpPort = hp;
            cfg.token = etToken.getText().toString().trim();
            cfg.modelBaseUrl = etBaseUrl.getText().toString().trim();
            cfg.modelApiKey = etApiKey.getText().toString().trim();
            cfg.model = etModel.getText().toString().trim();
            cfg.allowGroups = etGroups.getText().toString().trim();
            cfg.autoStart = cbAutoStart.isChecked();
            cfg.keepAlive = cbKeepAlive.isChecked();
            cfg.save(this);
            Toast.makeText(this, "已保存。重启服务后生效。", Toast.LENGTH_LONG).show();
            finish();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口必须是数字", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void exportConfig() {
        try {
            String json = cfg.toConfigJson();
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("config.json", json));
                Toast.makeText(this, "配置已复制到剪贴板", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle("重置环境")
            .setMessage("将删除：\n· Ubuntu 系统（rootfs）\n· Node 运行时\n· 项目代码与依赖\n· 聊天数据与配置\n\n"
                + "这个操作不可恢复。确定继续？")
            .setPositiveButton("确定重置", (d, w) -> doReset())
            .setNegativeButton("取消", null)
            .show();
    }

    private void doReset() {
        new Thread(() -> {
            try {
                AgentService.stop(this);
                Thread.sleep(1000);
                deleteRecursive(new java.io.File(getFilesDir(), "usr"));
                deleteRecursive(new java.io.File(getFilesDir(), "qap-data"));
                deleteRecursive(new java.io.File(getFilesDir(), "napcat-work"));
                deleteRecursive(new java.io.File(getFilesDir(), "run"));
                deleteRecursive(new java.io.File(getFilesDir(), "home"));
                runOnUiThread(() -> {
                    Toast.makeText(this, "已重置，请回首页重新安装", Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                    "重置失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
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
}
