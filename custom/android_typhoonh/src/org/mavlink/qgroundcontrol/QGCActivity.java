package org.mavlink.qgroundcontrol;

import android.app.Activity;
import android.util.Log;
import android.os.PowerManager;
import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;

import java.util.List;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.Status;

import android.os.BatteryManager;

import org.qtproject.qt5.android.bindings.QtActivity;
import org.qtproject.qt5.android.bindings.QtApplication;

public class QGCActivity extends QtActivity implements TextToSpeech.OnInitListener {

    public enum ReceiverMode {
        DISABLED,
        SCANNING,
        BINDING
    }

    private static QGCActivity m_instance;
    private static final String TAG = "QGroundControl_JNI";
    private static TextToSpeech  m_tts;
    private static PowerManager.WakeLock m_wl;
    private static WifiManager mainWifi;
    private static WifiReceiver receiverWifi;
    private static ReceiverMode receiverMode = ReceiverMode.DISABLED;
    private static String currentConnection;
    private static int currentWifiRssi = 0;
    private static float batteryLevel = 0.0f;

    // WiFi: https://stackoverflow.com/questions/36098871/how-to-search-and-connect-to-a-specific-wifi-network-in-android-programmatically/36099552#36099552

    private static native void nativeNewWifiItem(String ssid, int rssi);
    private static native void nativeNewWifiRSSI();
    private static native void nativeScanComplete();
    private static native void nativeAuthError();
    private static native void nativeWifiConnected();
    private static native void nativeWifiDisconnected();
    private static native void nativeBatteryUpdate();

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //  Constructor.  Only used once to create the initial instance for the static functions.
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////
    public QGCActivity() {
        m_instance = this;
        Log.i(TAG, "Instance created");
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //  Initialize TTS and Power Management
    //
    /////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        m_tts = new TextToSpeech(this,this);
        PowerManager pm = (PowerManager)m_instance.getSystemService(Context.POWER_SERVICE);
        m_wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "QGroundControl");
        if(m_wl != null) {
            m_wl.acquire();
            Log.i(TAG, "SCREEN_BRIGHT_WAKE_LOCK acquired.");
        } else {
            Log.i(TAG, "SCREEN_BRIGHT_WAKE_LOCK not acquired!!!");
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //-- Full Screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        //-- WiFi
        mainWifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        mainWifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "QGCScanner");
        receiverWifi = new WifiReceiver();
        if(mainWifi.isWifiEnabled() == false) {
            mainWifi.setWifiEnabled(true);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        registerReceiver(receiverWifi, filter);
        findWifiConfig();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(receiverWifi);
            if(m_wl != null) {
                m_wl.release();
                Log.i(TAG, "SCREEN_BRIGHT_WAKE_LOCK released.");
            }
        } catch(Exception e) {
           Log.e(TAG, "Exception onDestroy()");
        }
        super.onDestroy();
        m_tts.shutdown();
    }

    public void onInit(int status) {
    }

    public static void say(String msg) {
        Log.i(TAG, "Say: " + msg);
        m_tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null);
    }

    public static void keepScreenOn() {
    }

    public static void restoreScreenOn() {
    }

    public static void disconnectWifi() {
        mainWifi.disconnect();
    }

    public static void reconnectWifi() {
        mainWifi.reconnect();
    }

    public static int wifiRssi() {
        return currentWifiRssi;
    }

    public static float getBatteryLevel() {
        return batteryLevel;
    }

    public static void findWifiConfig() {
        String currentCamera = "";
        List<WifiConfiguration> list = mainWifi.getConfiguredNetworks();
        for( WifiConfiguration i : list ) {
            if(i.SSID != null) {
                Log.i(TAG, "Found config: " + i.SSID + " | " + i.priority);
                if(i.status == 0) {
                    currentConnection = i.SSID;
                }
                if(i.SSID.startsWith("CGO3P") || i.SSID.startsWith("CGOPRO") || i.SSID.startsWith("CGOET")) {
                    i.priority = 1;
                    currentCamera = i.SSID;
                } else {
                    i.priority = 10;
                }
            }
        }
        if(currentConnection == "") {
            currentConnection = currentCamera;
        }
        mainWifi.saveConfiguration();
    }

    public static void resetWifi() {
        Log.i(TAG, "resetWifi()");
        List<WifiConfiguration> list = mainWifi.getConfiguredNetworks();
        //-- Disable everything
        mainWifi.disconnect();
        for( WifiConfiguration i : list ) {
            if(i.SSID != null) {
                mainWifi.disableNetwork(i.networkId);
                //mainWifi.removeNetwork(i.networkId);
            }
        }
        currentConnection = "";
        mainWifi.saveConfiguration();
        currentWifiRssi = 0;
        nativeNewWifiRSSI();
        nativeWifiDisconnected();
    }

    public static void bindSSID(String ssid, String passphrase) {
        Log.i(TAG, "Bind: " + ssid + " " + passphrase);
        try {
            List<WifiConfiguration> list = mainWifi.getConfiguredNetworks();
            //-- Disable everything else
            mainWifi.disconnect();
            for( WifiConfiguration i : list ) {
                if(i.SSID != null && !i.SSID.equals("\"" + ssid + "\"")) {
                    i.priority = 10;
                    mainWifi.disableNetwork(i.networkId);
                    //mainWifi.removeNetwork(i.networkId);
                }
            }
            mainWifi.saveConfiguration();
            receiverMode = ReceiverMode.BINDING;
            //-- If already set, just make sure it's enabled
            list = mainWifi.getConfiguredNetworks();
            for( WifiConfiguration i : list ) {
                if(i.SSID != null && i.SSID.equals("\"" + ssid + "\"")) {
                    mainWifi.enableNetwork(i.networkId, true);
                    mainWifi.reconnect();
                    mainWifi.saveConfiguration();
                    return;
                }
            }
            //-- Add new configuration
            WifiConfiguration conf = new WifiConfiguration();
            conf.SSID = "\"" + ssid + "\"";
            conf.preSharedKey = "\"" + passphrase + "\"";
            conf.priority = 1;
            mainWifi.addNetwork(conf);
            mainWifi.saveConfiguration();
            list = mainWifi.getConfiguredNetworks();
            for( WifiConfiguration i : list ) {
                if(i.SSID != null && i.SSID.equals("\"" + ssid + "\"")) {
                    mainWifi.enableNetwork(i.networkId, true);
                    mainWifi.reconnect();
                    return;
                }
            }
        } catch(Exception e) {
           Log.e(TAG, "Exception bindSSID()");
        }
    }

    public static void startWifiScan() {
        Log.i(TAG, "Start WiFi Scan");
        receiverMode = ReceiverMode.SCANNING;
        mainWifi.startScan();
    }

    public static String connectedSSID() {
        return currentConnection;
    }

    public static void enableWiFi() {
        mainWifi.setWifiEnabled(true);
    }

    public static void disableWiFi() {
        mainWifi.setWifiEnabled(false);
    }

    class WifiReceiver extends BroadcastReceiver {
        public void onReceive(Context c, Intent intent) {
            try {
                if(intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                    if(receiverMode == ReceiverMode.SCANNING) {
                        List<ScanResult> wifiList = mainWifi.getScanResults();
                        if (wifiList != null) {
                            for(int i = 0; i < wifiList.size(); i++) {
                                //Log.i(TAG, "SSID: " + wifiList.get(i).SSID + " | " + wifiList.get(i).capabilities + " | " + wifiList.get(i).frequency + " | " + wifiList.get(i).level);
                                nativeNewWifiItem(wifiList.get(i).SSID, wifiList.get(i).level);
                            }
                        }
                        nativeScanComplete();
                    }
                } else if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (info != null) {
                        NetworkInfo.DetailedState state = info.getDetailedState();
                        if (info.isConnectedOrConnecting() && info.isConnected()) {
                            if (state.compareTo(NetworkInfo.DetailedState.CONNECTED) == 0) {
                                WifiInfo wifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                                if (wifiInfo != null) {
                                    Log.i(TAG, "Connected: " + wifiInfo);
                                    currentConnection = wifiInfo.getSSID();
                                    if(receiverMode == ReceiverMode.BINDING) {
                                        receiverMode = ReceiverMode.DISABLED;
                                    }
                                    currentWifiRssi = wifiInfo.getRssi();
                                    nativeNewWifiRSSI();
                                    nativeWifiConnected();
                                }
                            }
                        } else if (state.compareTo(NetworkInfo.DetailedState.DISCONNECTED) == 0) {
                            Log.i(TAG, "WIFI Disconnected");
                            currentWifiRssi = 0;
                            currentConnection = "";
                            nativeNewWifiRSSI();
                            nativeWifiDisconnected();
                        }
                    }
                } else if (intent.getAction().equals(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)) {
                    if(receiverMode == ReceiverMode.BINDING) {
                        int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0);
                        if (error == WifiManager.ERROR_AUTHENTICATING) {
                            Log.e(TAG, "Authentication Error");
                            receiverMode = ReceiverMode.DISABLED;
                            currentConnection = "";
                            nativeAuthError();
                        }
                    }
                } else if (intent.getAction().equals(WifiManager.RSSI_CHANGED_ACTION)) {
                    WifiInfo wifiInfo = mainWifi.getConnectionInfo();
                    Log.e(TAG, "RSSI: " + wifiInfo.getRssi());
                    currentWifiRssi = wifiInfo.getRssi();
                    nativeNewWifiRSSI();
                } else if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    batteryLevel = level / (float)scale;
                    Log.e(TAG, "Battery: " + batteryLevel);
                    nativeBatteryUpdate();
                }
            } catch(Exception e) {
            }
        }
    }
}
