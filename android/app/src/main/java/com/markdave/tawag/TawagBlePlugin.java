package com.markdave.tawag;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import java.util.Locale;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

@CapacitorPlugin(
    name = "TawagBle",
    permissions = {
        @Permission(alias = "ble", strings = {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        }),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION }),
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class TawagBlePlugin extends Plugin {

    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    public void load() {
        PagerService.listener = new PagerService.Listener() {
            @Override public void onState(JSObject state) { notifyListeners("state", state); }
            @Override public void onJoinRequest(String deviceId) {
                JSObject o = new JSObject();
                o.put("device", deviceId);
                notifyListeners("join", o);
            }
        };
    }

    private List<String> neededAliases() {
        List<String> a = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) a.add("ble"); else a.add("location");
        if (Build.VERSION.SDK_INT >= 33) a.add("notifications");
        return a;
    }

    private List<String> missingAliases() {
        List<String> missing = new ArrayList<>();
        for (String alias : neededAliases()) {
            if (getPermissionState(alias) != PermissionState.GRANTED) missing.add(alias);
        }
        return missing;
    }

    @PluginMethod
    public void ensurePermissions(PluginCall call) {
        List<String> missing = missingAliases();
        if (missing.isEmpty()) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        requestPermissionForAliases(missing.toArray(new String[0]), call, "permissionsDone");
    }

    @PermissionCallback
    private void permissionsDone(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", missingAliases().isEmpty());
        call.resolve(r);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject r = new JSObject();
        BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        r.put("supported", a != null);
        r.put("bluetoothOn", a != null && a.isEnabled());
        boolean locationOff = false;
        if (Build.VERSION.SDK_INT <= 30 && Build.VERSION.SDK_INT >= 28) {
            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            locationOff = lm != null && !lm.isLocationEnabled();
        }
        r.put("locationOff", locationOff);
        r.put("permissions", missingAliases().isEmpty());
        r.put("state", PagerService.stateJson());
        call.resolve(r);
    }

    @PluginMethod
    public void getState(PluginCall call) {
        call.resolve(PagerService.stateJson());
    }

    private void startService(Intent i) {
        Context c = getContext();
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    /** Staff: broadcast the list of called numbers. calls = [{n: 12, nudge: 0}, ...] */
    @PluginMethod
    public void transmit(PluginCall call) {
        int venue = call.getInt("venue", 0);
        JSArray calls = call.getArray("calls", new JSArray());
        int count = Math.min(calls.length(), PagerService.MAX_CALLS);
        int[] packed = new int[count];
        for (int i = 0; i < count; i++) {
            JSONObject o = calls.optJSONObject(i);
            if (o == null) continue;
            int n = o.optInt("n", 0) & 0x7F;
            int nudge = o.optInt("nudge", 0) & 1;
            packed[i] = n | (nudge << 7);
        }
        Intent i = new Intent(getContext(), PagerService.class).setAction(PagerService.ACTION_TRANSMIT);
        i.putExtra("venue", venue);
        i.putExtra("name", call.getString("name", ""));
        i.putExtra("calls", packed);
        try {
            startService(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not start broadcasting: " + e.getMessage());
        }
    }

    /** Customer: become the pager for queue number `number` at restaurant `venue`. */
    @PluginMethod
    public void listen(PluginCall call) {
        Intent i = new Intent(getContext(), PagerService.class).setAction(PagerService.ACTION_LISTEN);
        i.putExtra("venue", call.getInt("venue", 0));
        i.putExtra("number", call.getInt("number", 0));
        try {
            startService(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not start the pager: " + e.getMessage());
        }
    }

    /** Staff: give a web customer who scanned the counter QR their new queue number. */
    @PluginMethod
    public void assign(PluginCall call) {
        if ("transmit".equals(PagerService.mode)) {
            Intent i = new Intent(getContext(), PagerService.class).setAction(PagerService.ACTION_ASSIGN);
            i.putExtra("device", call.getString("device", ""));
            i.putExtra("number", call.getInt("number", 0));
            getContext().startService(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void ack(PluginCall call) {
        if ("listen".equals(PagerService.mode)) {
            getContext().startService(new Intent(getContext(), PagerService.class).setAction(PagerService.ACTION_ACK));
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (!"idle".equals(PagerService.mode)) {
            try {
                getContext().startService(new Intent(getContext(), PagerService.class).setAction(PagerService.ACTION_STOP));
            } catch (Exception ignored) {}
        }
        PagerService.mode = "idle";
        PagerService.status = "idle";
        call.resolve();
    }

    /** Now Serving board: say "Order number 12 is ready" through the speaker. */
    @PluginMethod
    public void speak(PluginCall call) {
        final String text = call.getString("text", "");
        if (tts == null) {
            tts = new TextToSpeech(getContext(), st -> {
                ttsReady = st == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(Locale.US);
                    tts.speak(text, TextToSpeech.QUEUE_ADD, null, "tawag");
                }
            });
        } else if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "tawag");
        }
        call.resolve();
    }

    /** Keep the screen on (staff counter phone and Now Serving board). */
    @PluginMethod
    public void keepAwake(PluginCall call) {
        final boolean on = call.getBoolean("on", true);
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) { tts.shutdown(); tts = null; }
    }

    @PluginMethod
    public void openBluetoothSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }
}
