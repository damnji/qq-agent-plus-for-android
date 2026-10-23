package com.qqagent.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * App 侧配置（端口、token、模型、白名单），负责生成容器的 data/config.json。
 *
 * <p>为什么不直接改容器里的 config.json：容器里那份是运行时真相，
 * 但用户在手机上填的表单值要先存在 App 侧，安装时才能写进去；
 * 而且「重置环境」时 App 侧配置应该保留。
 */
final class AppConfig {
    private static final String PREFS = "qap_app_config";

    int consolePort = 3210;
    int wsPort = 3001;
    int httpPort = 3000;
    String token = "";
    String modelBaseUrl = "";
    String modelApiKey = "";
    String model = "";
    String allowGroups = "";
    boolean autoStart = false;
    boolean keepAlive = true;

    static AppConfig load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppConfig c = new AppConfig();
        c.consolePort = sp.getInt("consolePort", 3210);
        c.wsPort = sp.getInt("wsPort", 3001);
        c.httpPort = sp.getInt("httpPort", 3000);
        c.token = sp.getString("token", "");
        c.modelBaseUrl = sp.getString("modelBaseUrl", "");
        c.modelApiKey = sp.getString("modelApiKey", "");
        c.model = sp.getString("model", "");
        c.allowGroups = sp.getString("allowGroups", "");
        c.autoStart = sp.getBoolean("autoStart", false);
        c.keepAlive = sp.getBoolean("keepAlive", true);
        if (c.token == null || c.token.isEmpty()) {
            c.token = randomToken();
            c.save(context);
        }
        return c;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("consolePort", consolePort)
            .putInt("wsPort", wsPort)
            .putInt("httpPort", httpPort)
            .putString("token", token)
            .putString("modelBaseUrl", modelBaseUrl)
            .putString("modelApiKey", modelApiKey)
            .putString("model", model)
            .putString("allowGroups", allowGroups)
            .putBoolean("autoStart", autoStart)
            .putBoolean("keepAlive", keepAlive)
            .apply();
    }

    static String randomToken() {
        byte[] b = new byte[18];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 允许的群号列表（去掉空白与空项）。 */
    java.util.List<String> groupList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (allowGroups == null) return out;
        for (String part : allowGroups.split("[,，\\s]+")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    String consoleUrl() {
        return "http://127.0.0.1:" + consolePort;
    }

    /**
     * 生成容器用的 data/config.json。
     *
     * <p>注意几个刻意的默认值（与 docs/PHONE.md 一致）：
     * <ul>
     *   <li>runtime.mode = observe —— 装完不会立刻在群里说话；</li>
     *   <li>allowAllWhenEmpty = false —— 不填群号就不回复任何群；</li>
     *   <li>autoUpdate.enabled = false —— 手机上自更新要 systemctl，必然失败；</li>
     *   <li>wsHeartbeat = auto —— NapCat 收到协议层 PING 会断连接。</li>
     * </ul>
     */
    String toConfigJson() throws Exception {
        JSONObject root = new JSONObject();

        JSONObject server = new JSONObject();
        server.put("host", "127.0.0.1");
        server.put("port", consolePort);
        server.put("strictPort", true);
        server.put("token", "");
        root.put("server", server);

        JSONObject onebot = new JSONObject();
        onebot.put("wsUrl", "ws://127.0.0.1:" + wsPort);
        onebot.put("httpUrl", "http://127.0.0.1:" + httpPort);
        onebot.put("accessToken", token);
        onebot.put("httpAccessToken", token);
        onebot.put("wsHeartbeat", "auto");
        root.put("onebot", onebot);

        JSONObject runtime = new JSONObject();
        runtime.put("mode", "observe");
        runtime.put("paused", false);
        root.put("runtime", runtime);

        JSONObject api = new JSONObject();
        api.put("baseUrl", modelBaseUrl);
        api.put("apiKey", modelApiKey);
        api.put("model", model);
        root.put("api", api);

        JSONObject allow = new JSONObject();
        org.json.JSONArray groups = new org.json.JSONArray();
        for (String g : groupList()) groups.put(g);
        allow.put("groups", groups);
        allow.put("private", new org.json.JSONArray());
        root.put("allow", allow);
        root.put("allowAllWhenEmpty", false);

        JSONObject admin = new JSONObject();
        admin.put("ownerUin", "");
        root.put("admin", admin);

        JSONObject autoUpdate = new JSONObject();
        autoUpdate.put("enabled", false);
        root.put("autoUpdate", autoUpdate);

        JSONObject groupDigest = new JSONObject();
        groupDigest.put("enabled", false);
        root.put("groupDigest", groupDigest);

        JSONObject proactive = new JSONObject();
        proactive.put("enabled", false);
        root.put("proactive", proactive);

        return root.toString(2);
    }
}
