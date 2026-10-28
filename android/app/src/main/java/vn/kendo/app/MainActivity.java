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
    BluetoothManager bm;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic rxChar;
    SharedPreferences prefs;

    boolean dangQuet = false;
    boolean cheDoTimBeacon = false;      // dang quet de chon beacon
    boolean daKetNoi = false;
    boolean dangDung = false;            // app dang mo tren man hinh
    boolean webSanSang = false;
    boolean tuKetNoi = true;             // tu tim va ket noi lai (tat khi dien thoai chua la chia)
    String tenMach = "";
    int mtu = 23;
    final StringBuilder bufNhan = new StringBuilder();
    final LinkedList<byte[]> hangGui = new LinkedList<>();
    boolean dangGhi = false;
    final Map<String, JSONObject> dsBeacon = new HashMap<>();
    boolean cheDoTimMach = false;        // dang liet ke cac mach KhoaXe de chon
    final Map<String, JSONObject> dsMach = new HashMap<>();

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

        bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
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
        if (!tuKetNoi) { trangThai("paused", ""); return; }
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
            // Dien thoai da ghep doi va dang noi san voi mach -> mach co the khong phat song tim kiem.
            // Sau 4 giay khong thay thi ket noi thang vao mach da ghep doi.
            h.removeCallbacks(thuKetNoiTrucTiep);
            h.postDelayed(thuKetNoiTrucTiep, 4000);
        } catch (Exception e) { log("Loi quet: " + e.getMessage()); }
    }

    final Runnable thuKetNoiTrucTiep = new Runnable() {
        @Override public void run() {
            if (!tuKetNoi || daKetNoi || gatt != null || cheDoTimBeacon || cheDoTimMach || adapter == null || !adapter.isEnabled()) return;
            String macDaLuu = prefs.getString("mac_mach", "");
            BluetoothDevice chon = null;
            try {
                // Da chon mach -> CHI ket noi dung mach do (nha co 2 xe khong bi vao nham)
                if (bm != null) for (BluetoothDevice d : bm.getConnectedDevices(BluetoothProfile.GATT)) {
                    String n = d.getName();
                    if (macDaLuu.isEmpty() ? (n != null && n.startsWith(TIEN_TO_TEN)) : d.getAddress().equalsIgnoreCase(macDaLuu)) { chon = d; break; }
                }
                if (chon == null) for (BluetoothDevice d : adapter.getBondedDevices()) {
                    String n = d.getName();
                    if (macDaLuu.isEmpty() ? (n != null && n.startsWith(TIEN_TO_TEN)) : d.getAddress().equalsIgnoreCase(macDaLuu)) { chon = d; break; }
                }
            } catch (Exception e) { log("Loi tim mach da ghep: " + e.getMessage()); }
            if (chon != null) {
                log("Ket noi thang vao mach da ghep doi " + chon.getAddress());
                dungQuet();
                String n = chon.getName();
                ketNoi(chon, n != null ? n : "KhoaXe");
            }
        }
    };

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

        if (cheDoTimMach) {
            if (!ten.startsWith(TIEN_TO_TEN)) return;
            try {
                synchronized (dsMach) {
                    JSONObject o = dsMach.get(mac);
                    if (o == null) { o = new JSONObject(); o.put("mac", mac); o.put("ghep", false); }
                    o.put("ten", ten);
                    o.put("rssi", r.getRssi());
                    dsMach.put(mac, o);
                }
            } catch (Exception ignored) {}
            return;
        }

        String macDaLuu = prefs.getString("mac_mach", "");
        if (tuKetNoi && !daKetNoi && gatt == null && ten.startsWith(TIEN_TO_TEN)
                && (macDaLuu.isEmpty() || macDaLuu.equalsIgnoreCase(mac))) {
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
                    if (gatt != null && gatt != g) return;   // ket noi cu (truoc khi doi mach)
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
            gui("song 1");
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

    // ======================= CHON MACH (nha co nhieu xe) =======================
    void timMach() {
        if (adapter == null || !adapter.isEnabled()) { js("onBoards('[]',true)"); return; }
        String macDaLuu = prefs.getString("mac_mach", "");
        synchronized (dsMach) {
            dsMach.clear();
            try {
                // mach da ghep doi (co the dang noi san nen khong thay khi quet)
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    String n = d.getName();
                    if (n == null || !n.startsWith(TIEN_TO_TEN)) continue;
                    JSONObject o = new JSONObject();
                    o.put("mac", d.getAddress()); o.put("ten", n); o.put("rssi", JSONObject.NULL); o.put("ghep", true);
                    dsMach.put(d.getAddress(), o);
                }
            } catch (Exception e) { log("Loi doc mach da ghep: " + e.getMessage()); }
        }
        cheDoTimMach = true;
        dungQuet();
        batQuet();
        for (int i = 1; i <= 5; i++) {
            final int lan = i;
            h.postDelayed(() -> {
                JSONArray a = new JSONArray();
                String cur = prefs.getString("mac_mach", "");
                synchronized (dsMach) {
                    for (JSONObject o : dsMach.values()) {
                        try { o.put("cur", o.getString("mac").equalsIgnoreCase(cur) && daKetNoi); } catch (Exception ignored) {}
                        a.put(o);
                    }
                }
                js("onBoards(" + JSONObject.quote(a.toString()) + "," + (lan == 5) + ")");
                if (lan == 5) {
                    dungQuet(); cheDoTimMach = false;
                    if (!daKetNoi && gatt == null) batDau();
                }
            }, i * 1000L);
        }
    }

    void chonMach(String mac) {
        cheDoTimMach = false;
        dungQuet();
        prefs.edit().putString("mac_mach", mac).apply();
        tuKetNoi = true;
        if (gatt != null) {
            if (daKetNoi && gatt.getDevice().getAddress().equalsIgnoreCase(mac)) { trangThai("connected", tenMach); return; }
            BluetoothGatt cu = gatt;
            gatt = null; rxChar = null; daKetNoi = false;
            synchronized (hangGui) { hangGui.clear(); dangGhi = false; }
            try { cu.disconnect(); cu.close(); } catch (Exception ignored) {}
            trangThai("disconnected", "");
        }
        h.postDelayed(() -> batDau(), 800);
    }

    // ======================= CAU NOI VOI GIAO DIEN =======================
    class Cau {
        @JavascriptInterface public void send(String lenh) { h.post(() -> gui(lenh)); }
        @JavascriptInterface public void scanBeacons() { h.post(() -> timBeacon()); }
        @JavascriptInterface public void retry() { h.post(() -> { tuKetNoi = true; dungQuet(); batDau(); }); }
        @JavascriptInterface public void setAuto(boolean b) { h.post(() -> { tuKetNoi = b; if (!b) dungQuet(); }); }
        @JavascriptInterface public void listBoards() { h.post(() -> timMach()); }
        @JavascriptInterface public void chooseBoard(String mac) { h.post(() -> chonMach(mac)); }
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
