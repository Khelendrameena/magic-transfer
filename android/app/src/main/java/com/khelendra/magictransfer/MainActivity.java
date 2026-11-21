package com.khelendra.magictransfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 42;
    private static final int REQ_FILE = 43;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("mt", MODE_PRIVATE);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        String url = prefs.getString("server", "");
        if (url.isEmpty()) showSetup();
        else showWeb(url);
    }

    private void showSetup() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 80, 48, 48);
        root.setBackgroundColor(Color.parseColor("#0b0b14"));

        TextView title = new TextView(this);
        title.setText("✨ Magic Transfer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Apne PC par server chalao (python server.py + cloudflared tunnel) aur jo https link mile wo yaha dalo. Dono phone me yahi app + wahi link hoga — pehchan sirf haath ki hogi.");
        hint.setTextColor(Color.parseColor("#9a9ab0"));
        hint.setTextSize(14);
        hint.setPadding(0, 32, 0, 32);
        root.addView(hint);

        final EditText input = new EditText(this);
        input.setHint("https://xxxx.trycloudflare.com");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#666680"));
        input.setText(prefs.getString("server", ""));
        root.addView(input);

        Button go = new Button(this);
        go.setText("Kholo →");
        go.setOnClickListener(v -> {
            String u = input.getText().toString().trim();
            if (!u.startsWith("http")) { Toast.makeText(this, "Sahi link dalo (https://…)", Toast.LENGTH_SHORT).show(); return; }
            prefs.edit().putString("server", u).apply();
            showWeb(u);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 32;
        root.addView(go, lp);
        setContentView(root);
    }

    private void showWeb(String url) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0b14"));
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button gear = new Button(this);
        gear.setText("⚙");
        gear.setAlpha(0.75f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(120, 120);
        glp.gravity = Gravity.BOTTOM | Gravity.END;
        glp.bottomMargin = 24; glp.rightMargin = 24;
        gear.setOnClickListener(v -> { prefs.edit().remove("server").apply(); showSetup(); });
        root.addView(gear, glp);
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Web page (MediaPipe) camera maangti hai — app ke paas permission hai to de do
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(request.getResources());
                } else {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    request.deny();
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, "Photo chuno"), REQ_FILE);
                return true;
            }
        });
        webView.loadUrl(url);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
