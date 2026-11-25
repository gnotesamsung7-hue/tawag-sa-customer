package com.markdave.tawag;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One foreground service for every role.
 *
 *  transmit (staff phone)
 *    1. BLE broadcast of the called queue numbers, for customers using the Tawag app.
 *       Manufacturer data (company 0xFFFF): 'T' 'W' venueHi venueLo n1 n2 ... (max 18)
 *       Each n byte: low 7 bits = queue number (1..99), top bit = "buzz again" toggle.
 *    2. A small GATT server, for customers using the web page (Web Bluetooth, no install).
 *       INFO   (read)          "venue\nShop name"
 *       JOIN   (write)         "12" = I'm order 12, "0" = give me a new number
 *       STATUS (read + notify) "Q" waiting for a number, "W12" waiting, "C12:1" called, "D12" done
 *
 *  listen (customer phone with the app, or a "Now Serving" board when number == 0)
 */
@SuppressLint("MissingPermission")
public class PagerService extends Service {

    static final String ACTION_TRANSMIT = "tawag.TRANSMIT";
    static final String ACTION_ASSIGN = "tawag.ASSIGN";
    static final String ACTION_LISTEN = "tawag.LISTEN";
    static final String ACTION_ACK = "tawag.ACK";
    static final String ACTION_STOP = "tawag.STOP";

    static final int COMPANY_ID = 0xFFFF;
    static final int MAX_CALLS = 18;
    static final long IN_RANGE_MS = 15000;

    static final UUID SERVICE_UUID = UUID.fromString("7a1f0001-5a6e-4c1b-9a2d-7461776167aa");
    static final UUID INFO_UUID = UUID.fromString("7a1f0002-5a6e-4c1b-9a2d-7461776167aa");
    static final UUID JOIN_UUID = UUID.fromString("7a1f0003-5a6e-4c1b-9a2d-7461776167aa");
    static final UUID STATUS_UUID = UUID.fromString("7a1f0004-5a6e-4c1b-9a2d-7461776167aa");
    static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String CH_RUN = "tawag_run";
    private static final String CH_ALERT = "tawag_alert";
    private static final int NOTIF_RUN = 1;
    private static final int NOTIF_ALERT = 2;

    interface Listener {
        void onState(JSObject state);
        void onJoinRequest(String deviceId);
    }
    static volatile Listener listener;

    // Shared state (read by the plugin even when the service isn't running)
    static volatile String mode = "idle";      // idle | transmit | listen
    static volatile String status = "idle";    // transmit: on | error ; listen: waiting | calling | coming | done | board
    static volatile int venue = 0;
    static volatile int number = 0;
    static volatile int[] calls = new int[0];  // packed bytes currently broadcast / seen (board)
    static volatile long lastSeen = 0;
    static volatile String error = "";
    static final Map<String, Integer> webPagers = Collections.synchronizedMap(new HashMap<>());

    private String shopName = "";
    private int currentNudge = -1;
    private int ackedNudge = -1;
    private boolean lastInRange = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCallback;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    // GATT server (web pagers)
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic statusChar;
    private final Map<String, BluetoothDevice> devices = new HashMap<>();
    private final Set<String> subscribed = new HashSet<>();
    private final Set<String> devCalled = new HashSet<>();
    private final Map<String, String> lastSent = new HashMap<>();
    private final ArrayDeque<String> notifyQueue = new ArrayDeque<>();
    private boolean notifying = false;

    // alert
    private boolean alerting = false;
    private Vibrator vibrator;
    private ToneGenerator tone;
    private CameraManager cameraManager;
    private String torchId;
    private boolean torchOn = false;
    private PowerManager.WakeLock wakeLock;

    static JSObject stateJson() {
        JSObject o = new JSObject();
        o.put("mode", mode);
        o.put("status", status);
        o.put("venue", venue);
        o.put("number", number);
        JSArray c = new JSArray();
        JSArray nz = new JSArray();
        for (int b : calls) { c.put(b & 0x7F); nz.put((b >> 7) & 1); }
        o.put("calls", c);
        o.put("nudges", nz);
        long ago = lastSeen == 0 ? -1 : SystemClock.elapsedRealtime() - lastSeen;
        o.put("inRange", lastSeen != 0 && ago < IN_RANGE_MS);
        o.put("error", error);
        JSArray web = new JSArray();
        synchronized (webPagers) {
            for (Integer n : webPagers.values()) if (n != null && n > 0) web.put(n);
        }
        o.put("web", web);
        return o;
    }

    private void publish() {
        Listener l = listener;
        if (l != null) l.onState(stateJson());
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) { // restarted by the system with no intent: nothing to resume safely
            stopEverything();
            return START_NOT_STICKY;
        }
        switch (action) {
            case ACTION_TRANSMIT: {
                String name = intent.getStringExtra("name");
                goForeground("Tawag is broadcasting", name == null ? "" : name);
                startTransmit(intent.getIntExtra("venue", 0), name, intent.getIntArrayExtra("calls"));
                break;
            }
            case ACTION_ASSIGN:
                assignNumber(intent.getStringExtra("device"), intent.getIntExtra("number", 0));
                break;
            case ACTION_LISTEN: {
                int n = intent.getIntExtra("number", 0);
                goForeground(n == 0 ? "Now Serving board" : "Waiting for order #" + n,
                    n == 0 ? "Showing called orders" : "You'll be buzzed when it's ready");
                startListen(intent.getIntExtra("venue", 0), n);
                break;
            }
            case ACTION_ACK:
                acknowledge();
                break;
            case ACTION_STOP:
                stopEverything();
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAlert();
        stopAdvertising();
        stopGattServer();
        stopScan();
        main.removeCallbacksAndMessages(null);
        if (!"done".equals(status)) { mode = "idle"; status = "idle"; }
        publish();
        super.onDestroy();
    }

    // ---------------- notifications ----------------

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel run = new NotificationChannel(CH_RUN, "Tawag running", NotificationManager.IMPORTANCE_LOW);
        run.setShowBadge(false);
        nm.createNotificationChannel(run);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Order ready alerts", NotificationManager.IMPORTANCE_HIGH);
        alert.setSound(null, null);   // the service plays its own beeps
        alert.enableVibration(false); // and its own vibration
        alert.setBypassDnd(true);
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alert);
    }

    private PendingIntent openAppIntent(int req) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent serviceIntent(String action, int req) {
        Intent i = new Intent(this, PagerService.class).setAction(action);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String channel) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_tawag);
    }

    private Notification runNotification(String title, String text) {
        Notification.Builder b = builder(CH_RUN)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent(1));
        b.addAction(new Notification.Action.Builder(null, "Stop", serviceIntent(ACTION_STOP, 2)).build());
        return b.build();
    }

    private void goForeground(String title, String text) {
        Notification n = runNotification(title, text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_RUN, n);
        }
    }

    private void updateRun(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_RUN, runNotification(title, text));
    }

    // ---------------- Bluetooth helpers ----------------

    private BluetoothManager btManager() {
        return (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = btManager();
        return bm == null ? null : bm.getAdapter();
    }

    private void fail(String msg) {
        error = msg;
        status = "error";
        publish();
    }

    // ---------------- transmit (staff) ----------------

    private void startTransmit(int v, String name, int[] packed) {
        if (!"transmit".equals(mode)) { stopScan(); stopAlert(); }
        mode = "transmit";
        venue = v;
        shopName = name == null ? "" : name;
        error = "";
        int count = packed == null ? 0 : Math.min(packed.length, MAX_CALLS);
        calls = packed == null ? new int[0] : Arrays.copyOf(packed, count);

        byte[] payload = new byte[4 + count];
        payload[0] = 'T';
        payload[1] = 'W';
        payload[2] = (byte) ((v >> 8) & 0xFF);
        payload[3] = (byte) (v & 0xFF);
        for (int i = 0; i < count; i++) payload[4 + i] = (byte) (calls[i] & 0xFF);

        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { fail("bluetooth_off"); return; }
        if (advertiser == null) advertiser = a.getBluetoothLeAdvertiser();
        if (advertiser == null) { fail("no_advertiser"); return; }

        if (gattServer == null) startGattServer();
        refreshWebPagers();

        stopAdvertising();
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true) // the web page connects to the GATT server
            .setTimeout(0)
            .build();
        AdvertiseData data = new AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(COMPANY_ID, payload)
            .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(new ParcelUuid(SERVICE_UUID))
            .build();
        advCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings s) {
                main.post(() -> { status = "on"; error = ""; publish(); });
            }
            @Override public void onStartFailure(int errorCode) {
                main.post(() -> fail("advertise_failed_" + errorCode));
            }
        };
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advCallback);
        } catch (Exception e) {
            fail("advertise_exception");
        }
        updateRun("Tawag is broadcasting", shopName + " · " + count + " order(s) being called");
    }

    private void stopAdvertising() {
        if (advertiser != null && advCallback != null) {
            try { advertiser.stopAdvertising(advCallback); } catch (Exception ignored) {}
        }
        advCallback = null;
    }

    // ---------------- GATT server (web pagers) ----------------

    private void startGattServer() {
        BluetoothManager bm = btManager();
        if (bm == null) return;
        try {
            gattServer = bm.openGattServer(this, gattCallback);
        } catch (Exception e) {
            gattServer = null;
        }
        if (gattServer == null) return;
        BluetoothGattService svc = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic info = new BluetoothGattCharacteristic(INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattCharacteristic join = new BluetoothGattCharacteristic(JOIN_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE);
        statusChar = new BluetoothGattCharacteristic(STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ);
        statusChar.addDescriptor(new BluetoothGattDescriptor(CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        svc.addCharacteristic(info);
        svc.addCharacteristic(join);
        svc.addCharacteristic(statusChar);
        gattServer.addService(svc);
    }

    private void stopGattServer() {
        if (gattServer != null) {
            try {
                for (BluetoothDevice d : devices.values()) gattServer.cancelConnection(d);
                gattServer.close();
            } catch (Exception ignored) {}
        }
        gattServer = null;
        devices.clear();
        subscribed.clear();
        devCalled.clear();
        lastSent.clear();
        notifyQueue.clear();
        notifying = false;
        webPagers.clear();
    }

    private int nudgeFor(int n) {
        for (int b : calls) if ((b & 0x7F) == n) return (b >> 7) & 1;
        return -1;
    }

    private String statusFor(String addr) {
        Integer n = webPagers.get(addr);
        if (n == null || n == 0) return "Q";
        int nudge = nudgeFor(n);
        if (nudge >= 0) { devCalled.add(addr); return "C" + n + ":" + nudge; }
        if (devCalled.contains(addr)) return "D" + n;
        return "W" + n;
    }

    /** Push a fresh status to every subscribed web pager whose status changed. */
    private void refreshWebPagers() {
        for (String addr : devices.keySet()) {
            if (!subscribed.contains(addr)) continue;
            String s = statusFor(addr);
            if (!s.equals(lastSent.get(addr)) && !notifyQueue.contains(addr)) notifyQueue.add(addr);
        }
        sendNextNotification();
    }

    @SuppressWarnings("deprecation")
    private void sendNextNotification() {
        if (notifying || gattServer == null || statusChar == null) return;
        String addr = notifyQueue.poll();
        if (addr == null) return;
        BluetoothDevice d = devices.get(addr);
        if (d == null || !subscribed.contains(addr)) { sendNextNotification(); return; }
        String s = statusFor(addr);
        byte[] value = s.getBytes(StandardCharsets.UTF_8);
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gattServer.notifyCharacteristicChanged(d, statusChar, false, value) == BluetoothGatt.GATT_SUCCESS;
            } else {
                statusChar.setValue(value);
                ok = gattServer.notifyCharacteristicChanged(d, statusChar, false);
            }
        } catch (Exception e) { ok = false; }
        if (ok) {
            notifying = true;
            lastSent.put(addr, s);
            // safety net in case onNotificationSent never arrives
            main.postDelayed(() -> { if (notifying) { notifying = false; sendNextNotification(); } }, 1000);
        } else {
            notifyQueue.add(addr); // retry shortly
            main.postDelayed(this::sendNextNotification, 300);
        }
    }

    private void assignNumber(String addr, int n) {
        if (addr == null || !devices.containsKey(addr)) return;
        webPagers.put(addr, n);
        devCalled.remove(addr);
        lastSent.remove(addr);
        refreshWebPagers();
        publish();
    }

    private byte[] slice(byte[] value, int offset) {
        if (offset >= value.length) return new byte[0];
        return Arrays.copyOfRange(value, offset, value.length);
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int st, int newState) {
            main.post(() -> {
                String addr = device.getAddress();
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    devices.put(addr, device);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    devices.remove(addr);
                    subscribed.remove(addr);
                    lastSent.remove(addr);
                    webPagers.remove(addr);
                    devCalled.remove(addr);
                    publish();
                }
            });
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic c) {
            main.post(() -> {
                if (gattServer == null) return;
                byte[] value;
                if (INFO_UUID.equals(c.getUuid())) {
                    value = (venue + "\n" + shopName).getBytes(StandardCharsets.UTF_8);
                } else if (STATUS_UUID.equals(c.getUuid())) {
                    value = statusFor(device.getAddress()).getBytes(StandardCharsets.UTF_8);
                } else {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
                    return;
                }
                if (offset > value.length) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                } else {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice(value, offset));
                }
            });
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic c,
                                                 boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            main.post(() -> {
                if (gattServer == null) return;
                if (!JOIN_UUID.equals(c.getUuid())) {
                    if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
                    return;
                }
                if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                String addr = device.getAddress();
                devices.put(addr, device);
                int n = 0;
                try { n = Integer.parseInt(new String(value == null ? new byte[0] : value, StandardCharsets.UTF_8).trim()); }
                catch (NumberFormatException ignored) {}
                if (n < 0 || n > 99) n = 0;
                webPagers.put(addr, n);
                devCalled.remove(addr);
                lastSent.remove(addr);
                if (n == 0) {
                    Listener l = listener;
                    if (l != null) l.onJoinRequest(addr);
                }
                refreshWebPagers();
                publish();
            });
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor d,
                                             boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            main.post(() -> {
                if (gattServer == null) return;
                String addr = device.getAddress();
                devices.put(addr, device);
                if (CCCD_UUID.equals(d.getUuid())) {
                    boolean on = value != null && value.length > 0 && (value[0] & 0x03) != 0;
                    if (on) subscribed.add(addr); else subscribed.remove(addr);
                    lastSent.remove(addr);
                }
                if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                refreshWebPagers();
            });
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor d) {
            main.post(() -> {
                if (gattServer == null) return;
                byte[] v = subscribed.contains(device.getAddress())
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v);
            });
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int st) {
            main.post(() -> { notifying = false; sendNextNotification(); });
        }
    };

    // ---------------- listen (customer app / Now Serving board) ----------------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean inRange = lastSeen != 0 && SystemClock.elapsedRealtime() - lastSeen < IN_RANGE_MS;
            if (inRange != lastInRange) { lastInRange = inRange; publish(); }
            main.postDelayed(this, 3000);
        }
    };

    private final Runnable restartScan = new Runnable() {
        @Override public void run() { // some phones quietly stop very long scans
            if ("listen".equals(mode) && !"done".equals(status)) { stopScan(); startScan(); }
            main.postDelayed(this, 10 * 60 * 1000);
        }
    };

    private void startListen(int v, int n) {
        stopAdvertising();
        stopGattServer();
        stopAlert();
        mode = "listen";
        venue = v;
        number = n;
        calls = new int[0];
        status = n == 0 ? "board" : "waiting";
        error = "";
        lastSeen = 0;
        lastInRange = false;
        currentNudge = -1;
        ackedNudge = -1;
        stopScan();
        startScan();
        main.removeCallbacks(tick);
        main.removeCallbacks(restartScan);
        main.postDelayed(tick, 3000);
        main.postDelayed(restartScan, 10 * 60 * 1000);
        publish();
    }

    private void startScan() {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { fail("bluetooth_off"); return; }
        scanner = a.getBluetoothLeScanner();
        if (scanner == null) { fail("no_scanner"); return; }
        byte[] data = new byte[] { 'T', 'W', (byte) ((venue >> 8) & 0xFF), (byte) (venue & 0xFF) };
        byte[] mask = new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        // A filter is required, otherwise Android stops delivering results when the screen is off.
        ScanFilter filter = new ScanFilter.Builder().setManufacturerData(COMPANY_ID, data, mask).build();
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(number == 0 ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build();
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                ScanRecord rec = result.getScanRecord();
                if (rec == null) return;
                final byte[] p = rec.getManufacturerSpecificData(COMPANY_ID);
                if (p == null) return;
                main.post(() -> onPacket(p));
            }
            @Override public void onScanFailed(int errorCode) {
                main.post(() -> fail("scan_failed_" + errorCode));
            }
        };
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (Exception e) {
            fail("scan_exception");
        }
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanCallback = null;
    }

    private void onPacket(byte[] p) {
        if (!"listen".equals(mode) || "done".equals(status)) return;
        if (p.length < 4 || p[0] != 'T' || p[1] != 'W') return;
        int v = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        if (v != venue) return;
        lastSeen = SystemClock.elapsedRealtime();
        if ("error".equals(status)) { status = number == 0 ? "board" : "waiting"; error = ""; }

        int[] seen = new int[p.length - 4];
        for (int i = 4; i < p.length; i++) seen[i - 4] = p[i] & 0xFF;

        if (number == 0) { // Now Serving board: just report the list when it changes
            if (!Arrays.equals(seen, calls)) { calls = seen; publish(); }
            if (!lastInRange) { lastInRange = true; publish(); }
            return;
        }

        int nudge = -1;
        for (int b : seen) if ((b & 0x7F) == number) { nudge = (b >> 7) & 1; break; }

        if (nudge >= 0) {
            currentNudge = nudge;
            if ("waiting".equals(status) || ("coming".equals(status) && nudge != ackedNudge)) {
                status = "calling";
                startAlert();
                publish();
            }
        } else if ("calling".equals(status) || "coming".equals(status)) {
            status = "done"; // staff removed the number: order picked up
            finishListening();
            return;
        }
        if (!lastInRange) { lastInRange = true; publish(); }
    }

    private void acknowledge() {
        if (!"calling".equals(status)) return;
        ackedNudge = currentNudge;
        status = "coming";
        stopAlert();
        updateRun("Order #" + number + " is ready", "Head to the counter");
        publish();
    }

    private void finishListening() {
        stopAlert();
        stopScan();
        main.removeCallbacksAndMessages(null);
        publish();
        stopForegroundCompat();
        stopSelf();
    }

    private void stopEverything() {
        stopAlert();
        stopAdvertising();
        stopGattServer();
        stopScan();
        main.removeCallbacksAndMessages(null);
        mode = "idle";
        status = "idle";
        calls = new int[0];
        lastSeen = 0;
        publish();
        stopForegroundCompat();
        stopSelf();
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    // ---------------- alert: vibrate + beep + flashlight + full-screen notification ----------------

    private final Runnable beepLoop = new Runnable() {
        @Override public void run() {
            if (!alerting) return;
            try { if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700); } catch (Exception ignored) {}
            main.postDelayed(this, 1500);
        }
    };

    private final Runnable torchLoop = new Runnable() {
        @Override public void run() {
            if (!alerting) return;
            setTorch(!torchOn);
            main.postDelayed(this, 250);
        }
    };

    @SuppressWarnings("deprecation")
    private void startAlert() {
        if (alerting) return;
        alerting = true;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tawag:alert");
            wakeLock.acquire(10 * 60 * 1000L);
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        long[] pattern = new long[] { 0, 900, 400 };
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), aa);
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }

        try { tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100); } catch (Exception e) { tone = null; }
        main.post(beepLoop);

        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            torchId = null;
            if (cameraManager != null) {
                for (String id : cameraManager.getCameraIdList()) {
                    Boolean has = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (has != null && has) { torchId = id; break; }
                }
            }
        } catch (Exception e) { torchId = null; }
        if (torchId != null) main.post(torchLoop);

        Notification.Builder b = builder(CH_ALERT)
            .setContentTitle("Order #" + number + " is READY!")
            .setContentText("Please pick it up at the counter")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openAppIntent(3))
            .setFullScreenIntent(openAppIntent(4), true);
        b.addAction(new Notification.Action.Builder(null, "I'm coming", serviceIntent(ACTION_ACK, 5)).build());
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ALERT, b.build());
        updateRun("Order #" + number + " is READY!", "Please pick it up at the counter");
    }

    private void setTorch(boolean on) {
        if (cameraManager == null || torchId == null) return;
        try { cameraManager.setTorchMode(torchId, on); torchOn = on; } catch (Exception ignored) {}
    }

    private void stopAlert() {
        if (!alerting) return;
        alerting = false;
        main.removeCallbacks(beepLoop);
        main.removeCallbacks(torchLoop);
        if (vibrator != null) vibrator.cancel();
        if (tone != null) { try { tone.stopTone(); tone.release(); } catch (Exception ignored) {} tone = null; }
        setTorch(false);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_ALERT);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
}
