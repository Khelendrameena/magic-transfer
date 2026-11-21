package com.khelendra.magictransfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Magic Transfer v8 — bina Wi-Fi, bina Bluetooth, bina server.
 * Photo screen->camera animated QR se jati hai; haath ka AI pattern (MediaPipe,
 * APK me bundled) photo ke saath bandha hota hai — dusre phone me wahi haath
 * scan karne par hi photo khulti hai aur gallery me save hoti hai.
 * Sab kuch phone ke andar (local) hota hai.
 */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 42;
    private static final int REQ_FILE = 43;
    private static final String HOST = "appassets.androidplatform.net";
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) {
                    String path = url.getPath(); // /assets/...
                    if (path != null && path.startsWith("/assets/")) {
                        String asset = path.substring("/assets/".length());
                        try {
                            InputStream in = getAssets().open(asset);
                            return new WebResourceResponse(mime(asset), isText(asset) ? "UTF-8" : null, in);
                        } catch (Exception e) {
                            return new WebResourceResponse("text/plain", "UTF-8", null);
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
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

        webView.addJavascriptInterface(new SaveBridge(), "AndroidSave");
        webView.loadUrl("https://" + HOST + "/assets/index.html");
    }

    /** Web page se mili photo ko gallery (Pictures/MagicTransfer) me save karta hai. */
    public class SaveBridge {
        @android.webkit.JavascriptInterface
        public void saveImage(String b64, String mime) {
            try {
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                boolean webp = "image/webp".equals(mime);
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "magic_" + System.currentTimeMillis() + (webp ? ".webp" : ".jpg"));
                v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, webp ? "image/webp" : "image/jpeg");
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MagicTransfer");
                }
                Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) { os.write(bytes); os.close(); }
                    runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Gallery me save ho gayi ✓", android.widget.Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Save nahi ho payi", android.widget.Toast.LENGTH_SHORT).show());
            }
        }
    }

    private static boolean isText(String p) {
        return p.endsWith(".html") || p.endsWith(".js") || p.endsWith(".css");
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
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
