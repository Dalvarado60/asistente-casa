package gt.david.asistentecasa;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Busca todo lo que hay en la red wifi de la casa y alrededor por Bluetooth. */
public final class Escaner {

    private static final String TAG = "AsistenteCasa";

    private Escaner() { }

    // =====================================================================
    // RED WIFI: prueba las 254 direcciones de la red + lo que anuncian los aparatos
    // =====================================================================
    @SuppressWarnings("deprecation")
    public static JSONObject red(Context ctx, Tele tele) {
        JSONObject out = new JSONObject();
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ipInt = wm != null ? wm.getConnectionInfo().getIpAddress() : 0;
            if (ipInt == 0) return out.put("ok", false).put("error", "Este celular no está conectado al wifi.");
            String propia = ip(ipInt);
            String puerta = wm.getDhcpInfo() != null ? ip(wm.getDhcpInfo().gateway) : "";
            String base = propia.substring(0, propia.lastIndexOf('.') + 1);

            // lo que anuncian los aparatos (teles, Chromecast, impresoras, router…) en paralelo
            final JSONArray[] ssdp = {new JSONArray()};
            Thread hiloSsdp = new Thread(() -> { try { ssdp[0] = tele.descubrirLista(); } catch (Exception ignored) { } });
            hiloSsdp.start();

            Map<String, String> vivos = new ConcurrentHashMap<>(); // ip → nombre de red
            ExecutorService ex = Executors.newFixedThreadPool(48);
            for (int i = 1; i < 255; i++) {
                final String ip = base + i;
                if (ip.equals(propia)) continue;
                ex.execute(() -> {
                    if (vivo(ip)) {
                        String nombre = "";
                        try {
                            String h = InetAddress.getByName(ip).getCanonicalHostName();
                            if (h != null && !h.equals(ip)) nombre = h.replaceAll("\\.(lan|local|home|localdomain)$", "");
                        } catch (Exception ignored) { }
                        vivos.put(ip, nombre);
                    }
                });
            }
            ex.shutdown();
            ex.awaitTermination(20, TimeUnit.SECONDS);
            ex.shutdownNow();
            hiloSsdp.join(6000);

            Map<String, JSONObject> anunciados = new LinkedHashMap<>();
            for (int i = 0; i < ssdp[0].length(); i++) {
                JSONObject o = ssdp[0].optJSONObject(i);
                if (o != null) { anunciados.put(o.optString("ip"), o); vivos.putIfAbsent(o.optString("ip"), ""); }
            }

            List<String> ips = new ArrayList<>(vivos.keySet());
            Collections.sort(ips, (a, b) -> Integer.compare(ultimo(a), ultimo(b)));
            JSONArray lista = new JSONArray();
            lista.put(new JSONObject().put("ip", propia).put("nombre", "Este celular (T-Bot)").put("tipo", "tbot"));
            for (String ip : ips) {
                JSONObject a = anunciados.get(ip);
                String nombre = a != null && !a.optString("nombre").isEmpty() && !"Dispositivo".equals(a.optString("nombre")) ? a.optString("nombre") : vivos.get(ip);
                JSONObject d = new JSONObject().put("ip", ip).put("nombre", nombre == null ? "" : nombre);
                if (a != null) d.put("fabricante", a.optString("fabricante")).put("modelo", a.optString("modelo")).put("tele", a.optBoolean("tele"));
                if (ip.equals(puerta)) d.put("tipo", "router");
                lista.put(d);
            }
            return out.put("ok", true).put("propia", propia).put("router", puerta).put("dispositivos", lista);
        } catch (Exception e) {
            Log.w(TAG, "escaner red", e);
            try { return out.put("ok", false).put("error", e.getMessage()); } catch (Exception x) { return out; }
        }
    }

    /** ¿Responde algo en esa dirección? (ping o un puerto común, aunque lo rechace) */
    private static boolean vivo(String ip) {
        try { if (InetAddress.getByName(ip).isReachable(350)) return true; } catch (Exception ignored) { }
        for (int puerto : new int[]{80, 443, 62078, 8080}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, puerto), 220);
                return true;
            } catch (ConnectException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("ECONNREFUSED") || m.contains("refused")) return true; // el aparato existe, solo cerró el puerto
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static String ip(int n) {
        return (n & 0xff) + "." + ((n >> 8) & 0xff) + "." + ((n >> 16) & 0xff) + "." + ((n >> 24) & 0xff);
    }

    private static int ultimo(String ip) {
        try { return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1)); } catch (Exception e) { return 0; }
    }

    // =====================================================================
    // BLUETOOTH: emparejados + los que están cerca (clásico y de bajo consumo)
    // =====================================================================
    public static boolean puedeBluetooth(Context c) {
        if (Build.VERSION.SDK_INT >= 31)
            return c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public static JSONObject bluetooth(Context ctx, int segundos) {
        JSONObject out = new JSONObject();
        Context app = ctx.getApplicationContext();
        try {
            BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            if (ad == null) return out.put("ok", false).put("error", "sin-bluetooth");
            if (!puedeBluetooth(app)) return out.put("ok", false).put("error", "sin-permiso");
            if (!ad.isEnabled()) return out.put("ok", false).put("error", "apagado");

            Map<String, JSONObject> vistos = new ConcurrentHashMap<>();
            try {
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    vistos.put(d.getAddress(), dispositivo(d, null, true));
                }
            } catch (Exception ignored) { }

            BroadcastReceiver rec = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (!BluetoothDevice.ACTION_FOUND.equals(i.getAction())) return;
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    short rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    try {
                        JSONObject o = vistos.get(d.getAddress());
                        if (o == null) vistos.put(d.getAddress(), dispositivo(d, rssi == Short.MIN_VALUE ? null : (int) rssi, false));
                        else if (rssi != Short.MIN_VALUE) o.put("rssi", rssi).put("cerca", true);
                    } catch (Exception ignored) { }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rec, new IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED);
            else app.registerReceiver(rec, new IntentFilter(BluetoothDevice.ACTION_FOUND));

            ScanCallback cb = new ScanCallback() {
                @Override public void onScanResult(int tipo, ScanResult r) {
                    try {
                        BluetoothDevice d = r.getDevice();
                        String nombre = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                        JSONObject o = vistos.get(d.getAddress());
                        if (o == null) {
                            o = dispositivo(d, r.getRssi(), false);
                            if ((o.optString("nombre").isEmpty()) && nombre != null) o.put("nombre", nombre);
                            o.put("ble", true);
                            vistos.put(d.getAddress(), o);
                        } else {
                            o.put("rssi", r.getRssi()).put("cerca", true);
                        }
                    } catch (Exception ignored) { }
                }
            };
            BluetoothLeScanner le = ad.getBluetoothLeScanner();
            try { ad.startDiscovery(); } catch (Exception e) { Log.w(TAG, "bt clásico", e); }
            try { if (le != null) le.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb); } catch (Exception e) { Log.w(TAG, "ble", e); }
            Thread.sleep(segundos * 1000L);
            try { ad.cancelDiscovery(); } catch (Exception ignored) { }
            try { if (le != null) le.stopScan(cb); } catch (Exception ignored) { }
            try { app.unregisterReceiver(rec); } catch (Exception ignored) { }

            List<JSONObject> lista = new ArrayList<>(vistos.values());
            Collections.sort(lista, (a, b) -> Integer.compare(b.optInt("rssi", -999), a.optInt("rssi", -999)));
            JSONArray conNombre = new JSONArray();
            int sinNombre = 0;
            for (JSONObject o : lista) {
                if (o.optString("nombre").isEmpty() && !o.optBoolean("emparejado")) { sinNombre++; continue; }
                conNombre.put(o);
            }
            return out.put("ok", true).put("dispositivos", conNombre).put("sinNombre", sinNombre);
        } catch (Exception e) {
            Log.w(TAG, "escaner bt", e);
            try { return out.put("ok", false).put("error", String.valueOf(e.getMessage())); } catch (Exception x) { return out; }
        }
    }

    @SuppressLint("MissingPermission")
    private static JSONObject dispositivo(BluetoothDevice d, Integer rssi, boolean emparejado) throws Exception {
        String nombre = "";
        try { if (d.getName() != null) nombre = d.getName(); } catch (Exception ignored) { }
        String clase = "";
        try {
            BluetoothClass bc = d.getBluetoothClass();
            if (bc != null) clase = claseBt(bc.getMajorDeviceClass());
        } catch (Exception ignored) { }
        JSONObject o = new JSONObject().put("nombre", nombre).put("mac", d.getAddress()).put("clase", clase).put("emparejado", emparejado);
        if (rssi != null) o.put("rssi", rssi).put("cerca", true);
        return o;
    }

    private static String claseBt(int mayor) {
        switch (mayor) {
            case BluetoothClass.Device.Major.PHONE: return "celular";
            case BluetoothClass.Device.Major.COMPUTER: return "computadora";
            case BluetoothClass.Device.Major.AUDIO_VIDEO: return "audio";
            case BluetoothClass.Device.Major.WEARABLE: return "reloj";
            case BluetoothClass.Device.Major.PERIPHERAL: return "accesorio";
            case BluetoothClass.Device.Major.IMAGING: return "impresora";
            case BluetoothClass.Device.Major.HEALTH: return "salud";
            case BluetoothClass.Device.Major.TOY: return "juguete";
            default: return "";
        }
    }

    static String minus(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
