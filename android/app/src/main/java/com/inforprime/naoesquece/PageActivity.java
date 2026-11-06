package com.inforprime.naoesquece;

import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/** Páginas do Não Esquece fora da tela principal: central de ajuda, política e termos. */
public class PageActivity extends AppCompatActivity {
    private static final int BG = 0xFF0A1122;
    private WebView web;
    private TextView title;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(16), 0);
        bar.setBackgroundColor(BG);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(0xFFE4EAF5);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Voltar ao app");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        title = new TextView(this);
        title.setTextColor(0xFFE4EAF5);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText("Não Esquece");
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " NaoEsquecApp/" + BuildConfig.VERSION_NAME);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                Uri u = r.getUrl();
                if (Links.isOwnPage(u)) return false;
                if (Links.isApp(u)) { finish(); return true; }
                String sc = u.getScheme() == null ? "" : u.getScheme();
                if (sc.equals("blob") || sc.equals("data") || sc.equals("about") || sc.equals("javascript")) return true;
                Links.openExternal(PageActivity.this, u);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView v, String t) {
                if (t == null) return;
                t = t.replace(" · Não Esquece", "").trim();
                if (!t.isEmpty() && !t.startsWith("http")) title.setText(t);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        Uri u = getIntent() == null ? null : getIntent().getData();
        if (u == null || !Links.isOwnPage(u)) { finish(); return; }
        web.loadUrl(u.toString());
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
