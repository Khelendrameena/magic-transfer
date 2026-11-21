package com.khelendra.magictransfer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Magic Transfer v4 — koi server nahi, koi link nahi, koi mode nahi.
 * Dono phone me app kholo -> Wi-Fi Direct se khud jud jayenge (bahut fast).
 * Photo tap karo -> magic animation ke saath dusre phone me chali jayegi.
 * Dusre phone par haath phone ke upar ghumao (proximity sensor) -> animation ke saath photo hazir.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final int PORT = 8988;
    private static final int REQ_PERMS = 7;
    private static final String SERVICE_INSTANCE = "_magictransfer";
    private static final String SERVICE_TYPE = "_presence._tcp";

    private TextView status;
    private TextView logView;
    private final java.util.List<String> logLines = new ArrayList<>();
    private boolean serviceRegistered = false;
    private boolean serviceRequestAdded = false;
    private GridView grid;
    private FrameLayout overlay;
    private ImageView overlayImg;
    private TextView overlaySpark;
    private TextView waitHint;
    private GalleryAdapter adapter;
    private final List<Uri> photos = new ArrayList<>();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Wi-Fi Direct
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private String myAddress = "";
    private boolean connecting = false;
    private volatile boolean connected = false;

    // Socket
    private Socket socket;
    private DataOutputStream out;
    private final Object writeLock = new Object();

    // Receive
    private volatile byte[] waitingPhoto = null;
    private SensorManager sensors;
    private Sensor proximity;

    private Uri queuedSend = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNeededPermissions();
        setupP2p();
        loadGallery();
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    }

    // ---------------- UI ----------------
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0b14"));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("✨ Magic Transfer");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(32, 28, 32, 4);
        col.addView(title);

        status = new TextView(this);
        status.setText("Dusra phone dhoondh raha hu… dono me app khula rakho");
        status.setTextColor(Color.parseColor("#9a9ab0"));
        status.setTextSize(14);
        status.setPadding(32, 0, 32, 16);
        col.addView(status);

        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#5f5f7a"));
        logView.setTextSize(11);
        logView.setPadding(32, 0, 32, 8);
        col.addView(logView);

        android.widget.Button retry = new android.widget.Button(this);
        retry.setText("🔍 Dobara dhoondo");
        retry.setOnClickListener(v -> { log("haath se dobara search"); startDiscovery(); });
        col.addView(retry);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(6);
        grid.setVerticalSpacing(6);
        grid.setPadding(12, 4, 12, 12);
        adapter = new GalleryAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> onPhotoTapped(photos.get(pos)));
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        waitHint = new TextView(this);
        waitHint.setText("✨ Ek photo aayi hai — phone ke upar haath ghumao, photo hazir hogi");
        waitHint.setTextColor(Color.parseColor("#ffd54d"));
        waitHint.setTextSize(15);
        waitHint.setGravity(Gravity.CENTER);
        waitHint.setPadding(24, 18, 24, 18);
        waitHint.setVisibility(View.GONE);
        waitHint.setOnClickListener(v -> reveal());
        col.addView(waitHint);

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#e6000000"));
        overlay.setVisibility(View.GONE);
        overlayImg = new ImageView(this);
        overlayImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        overlay.addView(overlayImg, new FrameLayout.LayoutParams(-1, -1));
        overlaySpark = new TextView(this);
        overlaySpark.setText("✨");
        overlaySpark.setTextSize(72);
        overlaySpark.setGravity(Gravity.CENTER);
        overlay.addView(overlaySpark, new FrameLayout.LayoutParams(-1, -1));
        overlay.setOnClickListener(v -> overlay.setVisibility(View.GONE));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private void setStatus(String s) { ui.post(() -> status.setText(s)); }

    private void log(String s) {
        ui.post(() -> {
            logLines.add(0, "• " + s);
            while (logLines.size() > 5) logLines.remove(logLines.size() - 1);
            logView.setText(android.text.TextUtils.join("\n", logLines));
        });
    }

    private static String reasonText(int r) {
        switch (r) {
            case 0: return "ERROR";
            case 1: return "P2P_UNSUPPORTED";
            case 2: return "BUSY (thodi der me dobara try hoga)";
            case 3: return "NO_SERVICE_REQUESTS";
            default: return "code " + r;
        }
    }

    // ---------------- Permissions ----------------
    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_IMAGES);
            need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_PERMS) { loadGallery(); startDiscovery(); }
    }

    // ---------------- Gallery ----------------
    private void loadGallery() {
        io.execute(() -> {
            List<Uri> found = new ArrayList<>();
            String[] proj = {MediaStore.Images.Media._ID};
            try (Cursor c = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (c.moveToNext() && found.size() < 90) {
                        found.add(ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)));
                    }
                }
            } catch (Exception ignored) { }
            ui.post(() -> { photos.clear(); photos.addAll(found); adapter.notifyDataSetChanged();
                if (found.isEmpty()) setStatus("Gallery me photo nahi mile — pehle permission do, phir app dobara kholo"); });
        });
    }

    private Bitmap thumb(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(uri, new Size(320, 320), null);
            }
            long id = ContentUris.parseId(uri);
            return MediaStore.Images.Thumbnails.getThumbnail(
                    getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
        } catch (Exception e) { return null; }
    }

    private class GalleryAdapter extends BaseAdapter {
        private final Map<Integer, Bitmap> cache = new HashMap<>();
        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int i) { return photos.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            ImageView iv = convert instanceof ImageView ? (ImageView) convert : new ImageView(MainActivity.this);
            iv.setLayoutParams(new GridView.LayoutParams(340, 340));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Color.parseColor("#1c1c2e"));
            iv.setImageBitmap(null);
            iv.setTag(pos);
            Bitmap hit = cache.get(pos);
            if (hit != null) { iv.setImageBitmap(hit); return iv; }
            io.execute(() -> {
                Bitmap b = thumb(photos.get(pos));
                if (b != null) { cache.put(pos, b); ui.post(() -> {
                    if (Integer.valueOf(pos).equals(iv.getTag())) iv.setImageBitmap(b); }); }
            });
            return iv;
        }
    }

    // ---------------- Send ----------------
    private void onPhotoTapped(Uri uri) {
        if (!connected) {
            queuedSend = uri;
            setStatus("Abhi dusra phone nahi juda — jaise hi judega, ye photo chali jayegi. Dono phone paas rakho, dono me app khula ho.");
            Toast.makeText(this, "Dusra phone judne ka wait ho raha hai…", Toast.LENGTH_SHORT).show();
            return;
        }
        sendPhoto(uri, true);
    }

    private void sendPhoto(Uri uri, boolean animate) {
        io.execute(() -> {
            try {
                byte[] bytes;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n; while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    bytes = bos.toByteArray();
                }
                if (animate) ui.post(() -> playSendAnimation(bytes));
                synchronized (writeLock) {
                    out.writeUTF("PHOTO");
                    out.writeInt(bytes.length);
                    out.write(bytes);
                    out.flush();
                }
                setStatus("✨ Photo bhej di (" + (bytes.length / 1024) + " KB) — dusre phone par haath ghumao!");
            } catch (Exception e) {
                setStatus("❌ Bhej nahi paya: " + e.getMessage());
            }
        });
    }

    private void playSendAnimation(byte[] bytes) {
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (b == null) return;
        overlayImg.setImageBitmap(b);
        overlay.setVisibility(View.VISIBLE);
        overlaySpark.setAlpha(0f);
        overlayImg.setScaleX(1f); overlayImg.setScaleY(1f);
        overlayImg.setAlpha(1f); overlayImg.setTranslationY(0f);
        overlayImg.animate().scaleX(0.04f).scaleY(0.04f).translationY(-700f).alpha(0f)
                .setDuration(650).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
        overlaySpark.animate().alpha(1f).scaleX(1.6f).scaleY(1.6f).setDuration(650).start();
    }

    // ---------------- Receive + hand wave ----------------
    private void onPhotoReceived(byte[] bytes) {
        waitingPhoto = bytes;
        ui.post(() -> {
            waitHint.setVisibility(View.VISIBLE);
            waitHint.animate().alpha(0.4f).setDuration(600)
                    .withEndAction(() -> waitHint.animate().alpha(1f).setDuration(600).start()).start();
            setStatus("✨ Photo aa gayi hai — phone ke upar haath ghumao!");
        });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY
                && event.values[0] < event.sensor.getMaximumRange()
                && waitingPhoto != null) {
            reveal();
        }
    }
    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private void reveal() {
        byte[] bytes = waitingPhoto;
        if (bytes == null) return;
        waitingPhoto = null;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        ui.post(() -> {
            waitHint.setVisibility(View.GONE);
            if (b == null) { setStatus("Photo khul nahi payi"); return; }
            overlayImg.setImageBitmap(b);
            overlay.setVisibility(View.VISIBLE);
            overlayImg.setScaleX(0.15f); overlayImg.setScaleY(0.15f);
            overlayImg.setAlpha(0f); overlayImg.setRotation(-5f); overlayImg.setTranslationY(0f);
            overlaySpark.setAlpha(1f); overlaySpark.setScaleX(0.5f); overlaySpark.setScaleY(0.5f);
            overlayImg.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(550).start();
            overlaySpark.animate().alpha(0f).scaleX(2.2f).scaleY(2.2f).setDuration(700).start();
            setStatus("🎩 Photo hazir! Band karne ke liye photo par tap karo.");
        });
    }

    // ---------------- Wi-Fi Direct ----------------
    private void setupP2p() {
        p2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) { setStatus("Is phone me Wi-Fi Direct nahi hai"); return; }
        channel = p2p.initialize(this, getMainLooper(), () -> {
            // Channel toot jaye to khud dobara banao aur search phir chalao
            log("channel toota — dobara bana raha hu");
            channel = p2p.initialize(this, getMainLooper(), null);
            ui.postDelayed(this::startDiscovery, 1500);
        });
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                    WifiP2pDevice me = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    if (me != null) myAddress = me.deviceAddress;
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    android.net.NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (ni != null && ni.isConnected()) {
                        p2p.requestConnectionInfo(channel, infoListener);
                    } else {
                        connected = false; connecting = false;
                        setStatus("Connection toota — dusra phone phir dhoondh raha hu…");
                        startDiscovery();
                    }
                }
            }
        };
        startDiscovery();
        ui.postDelayed(this::retryDiscoveryLoop, 20000);
    }

    private void retryDiscoveryLoop() {
        if (!connected && !connecting) startDiscovery();
        ui.postDelayed(this::retryDiscoveryLoop, 20000);
    }

    private boolean environmentOk() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            setStatus("❌ Wi-Fi BAND hai — pehle dono phone me Wi-Fi ON karo");
            log("Wi-Fi band hai ❌");
            return false;
        }
        if (Build.VERSION.SDK_INT < 33) {
            int mode = 0;
            try { mode = android.provider.Settings.Secure.getInt(getContentResolver(), android.provider.Settings.Secure.LOCATION_MODE); } catch (Exception ignored) { }
            if (mode == 0) {
                setStatus("❌ Location BAND hai — Wi-Fi Direct ke liye dono phone me Location ON karna padta hai (Android ka rule hai)");
                log("Location band hai ❌");
                return false;
            }
        }
        return true;
    }

    private void startDiscovery() {
        if (p2p == null || channel == null) return;
        if (!environmentOk()) return;
        try {
            // Apne aap ko advertise karo taaki dusra Magic Transfer mujhe pehchan sake (sirf ek baar)
            if (!serviceRegistered) {
                Map<String, String> record = new HashMap<>();
                record.put("app", "magictransfer");
                WifiP2pDnsSdServiceInfo service = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_INSTANCE, SERVICE_TYPE, record);
                p2p.addLocalService(channel, service, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { serviceRegistered = true; log("apna naam advertise ho gaya ✓"); }
                    @Override public void onFailure(int r) { log("advertise fail: " + reasonText(r)); }
                });
            }
            p2p.setDnsSdResponseListeners(channel, (instance, type, device) -> {
                log("service mila: " + instance + " (" + (device != null ? device.deviceName : "?") + ")");
                if (instance != null && instance.startsWith(SERVICE_INSTANCE)
                        && device != null && !device.deviceAddress.equals(myAddress)
                        && !connected && !connecting) {
                    connectTo(device.deviceAddress);
                }
            }, null);
            if (!serviceRequestAdded) {
                WifiP2pDnsSdServiceRequest req = WifiP2pDnsSdServiceRequest.newInstance();
                p2p.addServiceRequest(channel, req, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { serviceRequestAdded = true; }
                    @Override public void onFailure(int r) { log("service request fail: " + reasonText(r)); }
                });
            }
            // Pehle aam peer discovery se p2p ko jagao (kuch phones par service discovery tabhi chalti hai), phir service discovery
            p2p.discoverPeers(channel, null);
            p2p.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { log("search chal raha hai…"); if (!connected) setStatus("Dusra phone dhoondh raha hu… dono me app khula rakho, Wi-Fi ON rakho"); }
                @Override public void onFailure(int r) { log("search fail: " + reasonText(r)); setStatus("Dhoondh nahi paya (" + reasonText(r) + ") — Wi-Fi ON hai? Permission diya?"); }
            });
        } catch (SecurityException e) {
            log("permission missing");
            setStatus("Permission chahiye: Nearby devices / Location allow karo");
        }
    }

    private void connectTo(String address) {
        connecting = true;
        log("judne ki koshish: " + address);
        setStatus("Dusre phone se jud raha hu… (pehli baar dusre phone par 'Accept' dabana pad sakta hai)");
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = address;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 0; // client banna pasand karo, dusra owner banega to socket seedha chalega
        try {
            p2p.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { log("connect request gaya ✓ — dusre phone par Accept karo"); }
                @Override public void onFailure(int r) { connecting = false; log("connect fail: " + reasonText(r)); setStatus("Jud nahi paya (" + reasonText(r) + ") — dobara try kar raha hu…"); }
            });
        } catch (SecurityException e) { connecting = false; log("connect permission missing"); }
    }

    private final WifiP2pManager.ConnectionInfoListener infoListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
            if (info == null || !info.groupFormed || connected) return;
            log(info.isGroupOwner ? "group ban gaya — mai owner hu, socket wait kar raha hu" : "group ban gaya — owner se jud raha hu");
            if (info.isGroupOwner) {
                io.execute(() -> {
                    try (ServerSocket server = new ServerSocket(PORT)) {
                        Socket s = server.accept();
                        onSocketReady(s);
                    } catch (Exception e) { connecting = false; }
                });
            } else {
                io.execute(() -> {
                    try {
                        Thread.sleep(1200);
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(info.groupOwnerAddress, PORT), 12000);
                        onSocketReady(s);
                    } catch (Exception e) { connecting = false; }
                });
            }
        }
    };

    private void onSocketReady(Socket s) {
        try {
            socket = s;
            out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            connected = true; connecting = false;
            log("socket jud gaya ✓✓");
            setStatus("✅ Dusra phone jud gaya! Photo tap karo — magic shuru.");
            if (queuedSend != null) { Uri q = queuedSend; queuedSend = null; sendPhoto(q, true); }
            io.execute(() -> {
                try {
                    while (true) {
                        String tag = in.readUTF();
                        if ("PHOTO".equals(tag)) {
                            int len = in.readInt();
                            byte[] buf = new byte[len];
                            in.readFully(buf);
                            onPhotoReceived(buf);
                        }
                    }
                } catch (Exception e) {
                    connected = false;
                    ui.post(() -> { setStatus("Connection band ho gaya — phir dhoondh raha hu…"); startDiscovery(); });
                }
            });
        } catch (Exception e) { connecting = false; }
    }

    // ---------------- Lifecycle ----------------
    @Override protected void onResume() {
        super.onResume();
        if (!connected && !connecting) startDiscovery();
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (receiver != null) registerReceiver(receiver, f);
        if (sensors != null && proximity != null) sensors.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override protected void onPause() {
        super.onPause();
        try { if (receiver != null) unregisterReceiver(receiver); } catch (Exception ignored) { }
        if (sensors != null) sensors.unregisterListener(this);
    }
}
