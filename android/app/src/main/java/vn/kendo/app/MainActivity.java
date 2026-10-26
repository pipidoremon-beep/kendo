package vn.kendo.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/*
 * Kendo - app dieu khien mach khoa xe qua Bluetooth.
 * Giao dien nam trong assets/index.html (WebView), phan Bluetooth viet bang Java o day.
 * Mo app -> tu bat Bluetooth, tu tim mach ten "KhoaXe..." va tu ket noi.
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {

    static final UUID NUS = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX  = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // dien thoai -> mach
    static final UUID TX  = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"); // mach -> dien thoai
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final String TIEN_TO_TEN = "KhoaXe";

    static final int REQ_QUYEN = 1;
    static final int REQ_BAT_BT = 2;

    final Handler h = new Handler(Looper.getMainLooper());
    WebView web;
    BluetoothAdapter adapter;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic rxChar;
    SharedPreferences prefs;

    boolean dangQuet = false;
    boolean cheDoTimBeacon = false;      // dang quet de chon beacon
    boolean daKetNoi = false;
    boolean dangDung = false;            // app dang mo tren man hinh
    boolean webSanSang = false;
    String tenMach = "";
    int mtu = 23;
    final StringBuilder bufNhan = new StringBuilder();
    final LinkedList<byte[]> hangGui = new LinkedList<>();
    boolean dangGhi = false;
    final Map<String, JSONObject> dsBeacon = new HashMap<>();

    // ======================= VONG DOI =======================
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("kendo", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.parseColor("#0A6CDB"));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Cau(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                webSanSang = true;
                batDau();
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
    }

    @Override protected void onResume() {
        super.onResume();
        dangDung = true;
        if (webSanSang) batDau();
    }

    @Override protected void onPause() {
        super.onPause();
        dangDung = false;
        dungQuet();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        dungQuet();
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
    }

    // ======================= QUYEN + BAT BLUETOOTH =======================
    String[] quyenCan() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    boolean coQuyen() {
        for (String q : quyenCan())
            if (checkSelfPermission(q) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    void batDau() {
        if (!dangDung) return;
        if (adapter == null) { trangThai("no_bt", ""); return; }
        if (!coQuyen()) { trangThai("no_perm", ""); requestPermissions(quyenCan(), REQ_QUYEN); return; }
        if (!adapter.isEnabled()) {
            trangThai("bt_off", "");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BAT_BT);
            return;
        }
        if (daKetNoi) { trangThai("connected", tenMach); return; }
        if (gatt == null) batQuet();
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] kq) {
        super.onRequestPermissionsResult(req, p, kq);
        if (coQuyen()) batDau(); else trangThai("no_perm", "");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BAT_BT) batDau();
    }

    // ======================= QUET =======================
    final ScanCallback quetCB = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { xuLyKetQua(r); }
        @Override public void onBatchScanResults(List<ScanResult> list) { for (ScanResult r : list) xuLyKetQua(r); }
        @Override public void onScanFailed(int code) {
            dangQuet = false;
            h.postDelayed(() -> batDau(), 2000);
        }
    };

    void batQuet() {
        if (dangQuet || adapter == null || !adapter.isEnabled()) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, st, quetCB);
            dangQuet = true;
            if (!daKetNoi && gatt == null) trangThai("scanning", "");
        } catch (Exception e) { log("Loi quet: " + e.getMessage()); }
    }

    void dungQuet() {
        if (!dangQuet) return;
        try { if (scanner != null) scanner.stopScan(quetCB); } catch (Exception ignored) {}
        dangQuet = false;
    }

    void xuLyKetQua(ScanResult r) {
        BluetoothDevice d = r.getDevice();
        ScanRecord rec = r.getScanRecord();
        String ten = rec != null ? rec.getDeviceName() : null;   // ten moi nhat (khong lay ten Android nho)
        if (ten == null) ten = "";
        String mac = d.getAddress();

        if (cheDoTimBeacon) {
            try {
                JSONObject o = new JSONObject();
                o.put("mac", mac.toLowerCase());
                o.put("ten", ten);
                o.put("rssi", r.getRssi());
                synchronized (dsBeacon) { dsBeacon.put(mac, o); }
            } catch (Exception ignored) {}
            return;
        }

        if (!daKetNoi && gatt == null && ten.startsWith(TIEN_TO_TEN)) {
            dungQuet();
            ketNoi(d, ten);
        }
    }

    // ======================= KET NOI =======================
    void ketNoi(BluetoothDevice d, String ten) {
        tenMach = ten;
        trangThai("connecting", ten);
        if (Build.VERSION.SDK_INT >= 23) gatt = d.connectGatt(this, false, gattCB, BluetoothDevice.TRANSPORT_LE);
        else gatt = d.connectGatt(this, false, gattCB);
        prefs.edit().putString("mac_mach", d.getAddress()).apply();
    }

    final BluetoothGattCallback gattCB = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                h.post(() -> g.requestMtu(185));
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                g.close();
                h.post(() -> {
                    boolean truocDo = daKetNoi;
                    gatt = null; rxChar = null; daKetNoi = false;
                    synchronized (hangGui) { hangGui.clear(); dangGhi = false; }
                    trangThai("disconnected", truocDo ? tenMach : "");
                    h.postDelayed(() -> batDau(), 1500);   // tu tim lai
                });
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int m, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m;
            g.discoverServices();
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService sv = g.getService(NUS);
            if (sv == null) { log("Thiet bi khong phai mach khoa xe"); g.disconnect(); return; }
            rxChar = sv.getCharacteristic(RX);
            BluetoothGattCharacteristic tx = sv.getCharacteristic(TX);
            g.setCharacteristicNotification(tx, true);
            BluetoothGattDescriptor de = tx.getDescriptor(CCCD);
            if (de != null) {
                de.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(de);
            } else sanSang();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            sanSang();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            String s = new String(v, StandardCharsets.UTF_8);
            List<String> dong = new ArrayList<>();
            synchronized (bufNhan) {
                bufNhan.append(s);
                int i;
                while ((i = bufNhan.indexOf("\n")) >= 0) {
                    String l = bufNhan.substring(0, i).trim();
                    bufNhan.delete(0, i + 1);
                    if (!l.isEmpty()) dong.add(l);
                }
            }
            for (String l : dong) js("onLine(" + JSONObject.quote(l) + ")");
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (hangGui) { dangGhi = false; }
            h.postDelayed(() -> guiTiep(), 15);
        }
    };

    void sanSang() {
        h.post(() -> {
            daKetNoi = true;
            trangThai("connected", tenMach);
            gui("xem");
            gui("song");
        });
    }

    // ======================= GUI LENH =======================
    void gui(String lenh) {
        byte[] data = (lenh + "\n").getBytes(StandardCharsets.UTF_8);
        int kc = Math.max(20, mtu - 3);
        synchronized (hangGui) {
            for (int i = 0; i < data.length; i += kc) {
                int n = Math.min(kc, data.length - i);
                byte[] p = new byte[n];
                System.arraycopy(data, i, p, 0, n);
                hangGui.add(p);
            }
        }
        guiTiep();
    }

    void guiTiep() {
        byte[] p;
        synchronized (hangGui) {
            if (dangGhi || hangGui.isEmpty() || gatt == null || rxChar == null) return;
            p = hangGui.poll();
            dangGhi = true;
        }
        rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        rxChar.setValue(p);
        if (!gatt.writeCharacteristic(rxChar)) {
            synchronized (hangGui) { dangGhi = false; hangGui.addFirst(p); }
            h.postDelayed(this::guiTiep, 100);
        }
    }

    // ======================= TIM BEACON =======================
    void timBeacon() {
        synchronized (dsBeacon) { dsBeacon.clear(); }
        cheDoTimBeacon = true;
        dungQuet();
        batQuet();
        for (int i = 1; i <= 8; i++) {
            final int lan = i;
            h.postDelayed(() -> {
                JSONArray a = new JSONArray();
                synchronized (dsBeacon) { for (JSONObject o : dsBeacon.values()) a.put(o); }
                js("onBeacons(" + JSONObject.quote(a.toString()) + "," + (lan == 8) + ")");
                if (lan == 8) { dungQuet(); cheDoTimBeacon = false; }
            }, i * 1000L);
        }
    }

    // ======================= CAU NOI VOI GIAO DIEN =======================
    class Cau {
        @JavascriptInterface public void send(String lenh) { h.post(() -> gui(lenh)); }
        @JavascriptInterface public void scanBeacons() { h.post(() -> timBeacon()); }
        @JavascriptInterface public void retry() { h.post(() -> { dungQuet(); batDau(); }); }
        @JavascriptInterface public void disconnect() { h.post(() -> { if (gatt != null) gatt.disconnect(); }); }
    }

    void trangThai(String st, String ten) {
        js("onState(" + JSONObject.quote(st) + "," + JSONObject.quote(ten) + ")");
    }

    void log(String s) { js("onLine(" + JSONObject.quote("# " + s) + ")"); }

    void js(String code) {
        h.post(() -> { if (web != null && webSanSang) web.evaluateJavascript(code, null); });
    }
}
