package com.qqagent.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 内置 WebView 打开容器里的控制台（http://127.0.0.1:3210）。
 *
 * <p>proot 没有网络命名空间隔离，容器里的 127.0.0.1 就是手机的 127.0.0.1，
 * 所以直接开这个地址即可，不需要端口转发。
 */
public class ConsoleActivity extends Activity {
    private WebView webView;
    private ProgressBar progress;
    private LinearLayout errorBox;
    private TextView errorText;
    private String consoleUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppConfig cfg = AppConfig.load(this);
        consoleUrl = cfg.consoleUrl();
        setContentView(buildUi());
        load();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12161C"));

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        bar.setBackgroundColor(Color.parseColor("#1A2029"));
        root.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.console_title);
        title.setTextColor(Color.parseColor("#E6EDF3"));
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tLp);
        bar.addView(title);

        Button reload = new Button(this);
        reload.setText("刷新");
        reload.setAllCaps(false);
        reload.setTextSize(13);
        reload.setTextColor(Color.parseColor("#64B5F6"));
        reload.setBackgroundColor(Color.TRANSPARENT);
        reload.setOnClickListener(v -> load());
        bar.addView(reload);

        Button close = new Button(this);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setTextSize(13);
        close.setTextColor(Color.parseColor("#64B5F6"));
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(v -> finish());
        bar.addView(close);

        // 进度条
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int) (3 * getResources().getDisplayMetrics().density)));

        // WebView
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // 允许 http 明文（已配 network_security_config，这里再保险）
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setBackgroundColor(Color.parseColor("#12161C"));

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 控制台内部链接都在 WebView 里走；外部链接才跳浏览器
                String url = request.getUrl().toString();
                if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                    return false;
                }
                try {
                    startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception ignored) { }
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                errorBox.setVisibility(View.GONE);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  WebResourceError error) {
                showError("无法连接控制台：" + error.getDescription()
                    + "\n\n地址：" + consoleUrl
                    + "\n\n可能原因：\n"
                    + "· 服务还没启动（回首页点「启动」）\n"
                    + "· 启动中，稍等几秒再刷新\n"
                    + "· 端口被改过（去设置里核对）");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(newProgress);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 错误提示层
        errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setPadding(pad, pad, pad, pad);
        errorBox.setBackgroundColor(Color.parseColor("#1A2029"));
        errorBox.setVisibility(View.GONE);

        errorText = new TextView(this);
        errorText.setTextColor(Color.parseColor("#F85149"));
        errorText.setTextSize(13);
        errorText.setLineSpacing(6, 1f);
        errorBox.addView(errorText);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setAllCaps(false);
        retry.setTextColor(Color.WHITE);
        retry.setBackgroundColor(Color.parseColor("#1E88E5"));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = pad;
        retry.setLayoutParams(rLp);
        retry.setOnClickListener(v -> load());
        errorBox.addView(retry);

        root.addView(errorBox, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        errorBox.setVisibility(View.GONE);
        webView.loadUrl(consoleUrl + "/");
    }

    private void showError(String msg) {
        progress.setVisibility(View.GONE);
        errorText.setText(msg);
        errorBox.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
