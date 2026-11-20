package gt.david.asistentecasa;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Control de la tele Samsung (Tizen 2016+) por wifi y búsqueda de dispositivos en la red.
 *  - Buscar: anuncio SSDP en la red local; se queda con la tele Samsung (IP, nombre, MAC).
 *  - Encender: Wake-on-LAN a la MAC (requiere "Encender con móvil" activado en la tele).
 *  - Teclas y apps: WebSocket seguro en el puerto 8002. La primera vez la tele pide "Permitir".
 */
public class Tele {

    public interface Aviso { void resultado(String json); }

    private static final String TAG = "AsistenteCasa";
    private static final Map<String, String[]> APPS = new LinkedHashMap<>();
    static {
        APPS.put("youtube", new String[]{"111299001912", "9Ur5IzDKqV.TizenYouTube"});
        APPS.put("netflix", new String[]{"3201907018807", "11101200001"});
        APPS.put("prime", new String[]{"3201910019365", "3201512006785", "org.tizen.ignition"});
        APPS.put("disney", new String[]{"3201901017640"});
        APPS.put("spotify", new String[]{"3201606009684"});
        APPS.put("navegador", new String[]{"org.tizen.browser"});
    }

    private final Context ctx;
    private final Aviso aviso;
    private final SharedPreferences sp;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor();
    private final OkHttpClient http;
    private final OkHttpClient httpRapido;
    private WebSocket ws;
    private volatile boolean listo = false;
    private final List<String> pendientes = new ArrayList<>();

    public Tele(Context c, Aviso a) {
        ctx = c.getApplicationContext();
        aviso = a;
        sp = ctx.getSharedPreferences("tele", Context.MODE_PRIVATE);
        http = clienteLocal(6);
        httpRapido = clienteLocal(2);
    }

    // ---------- avisos a la página ----------
    private void responder(String accion, boolean ok, String msg, JSONArray extra) {
        try {
            JSONObject j = new JSONObject().put("accion", accion).put("ok", ok).put("msg", msg);
            if (extra != null) j.put("dispositivos", extra);
            aviso.resultado(j.toString());
        } catch (Exception ignored) { }
    }

    public String estado() {
        try {
            return new JSONObject()
                    .put("ip", sp.getString("ip", ""))
                    .put("nombre", sp.getString("nombre", ""))
                    .put("mac", sp.getString("mac", ""))
                    .put("token", !sp.getString("token", "").isEmpty())
                    .toString();
        } catch (Exception e) { return "{}"; }
    }

    // ---------- buscar dispositivos ----------
    public void buscar() { hilo.execute(this::buscarAhora); }

    private void buscarAhora() {
        Map<String, String[]> vistos = new LinkedHashMap<>(); // ip → {server, location, st}
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm != null ? wm.createMulticastLock("tbot-ssdp") : null;
        try {
            if (lock != null) { lock.setReferenceCounted(false); lock.acquire(); }
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(700);
                String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
                byte[] b = msg.getBytes("UTF-8");
                DatagramPacket p = new DatagramPacket(b, b.length, InetAddress.getByName("239.255.255.250"), 1900);
                s.send(p);
                Thread.sleep(150);
                s.send(p);
                long fin = System.currentTimeMillis() + 4000;
                byte[] buf = new byte[4096];
                while (System.currentTimeMillis() < fin) {
                    DatagramPacket r = new DatagramPacket(buf, buf.length);
                    try { s.receive(r); } catch (SocketTimeoutException e) { continue; }
                    String txt = new String(r.getData(), 0, r.getLength(), "UTF-8");
                    String ip = r.getAddress().getHostAddress();
                    String[] d = vistos.get(ip);
                    if (d == null) d = new String[]{"", "", ""};
                    String server = cabecera(txt, "SERVER"), loc = cabecera(txt, "LOCATION"), st = cabecera(txt, "ST");
                    if (d[0].isEmpty()) d[0] = server;
                    if (d[1].isEmpty() || loc.contains("dmr") || loc.contains("MainTVServer")) d[1] = loc;
                    if (!st.isEmpty()) d[2] = d[2] + " " + st;
                    vistos.put(ip, d);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ssdp", e);
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }

        JSONArray lista = new JSONArray();
        String ipTele = null, nombreTele = null;
        for (Map.Entry<String, String[]> en : vistos.entrySet()) {
            String ip = en.getKey();
            String[] d = en.getValue();
            String nombre = "", fabricante = "", modelo = "";
            if (!d[1].isEmpty()) {
                String xml = traer(httpRapido, d[1]);
                nombre = etiqueta(xml, "friendlyName");
                fabricante = etiqueta(xml, "manufacturer");
                modelo = etiqueta(xml, "modelName");
            }
            boolean samsung = (d[0] + fabricante + d[2]).toLowerCase(Locale.ROOT).contains("samsung");
            boolean esTele = samsung && (d[2].contains("MediaRenderer") || d[2].contains("dial") || d[0].toLowerCase(Locale.ROOT).contains("tizen") || nombre.toLowerCase(Locale.ROOT).contains("tv"));
            try {
                lista.put(new JSONObject().put("ip", ip).put("nombre", nombre.isEmpty() ? (fabricante.isEmpty() ? "Dispositivo" : fabricante) : nombre)
                        .put("fabricante", fabricante).put("modelo", modelo).put("tele", esTele));
            } catch (Exception ignored) { }
            if (esTele && ipTele == null) { ipTele = ip; nombreTele = nombre; }
        }

        // si no se anunció (por ejemplo, apagada), probar la que ya conocemos
        if (ipTele == null) ipTele = sp.getString("ip", null);
        if (ipTele != null) {
            String info = traer(httpRapido, "http://" + ipTele + ":8001/api/v2/");
            if (!info.isEmpty()) {
                try {
                    JSONObject dev = new JSONObject(info).optJSONObject("device");
                    if (dev != null) {
                        String mac = dev.optString("wifiMac", "");
                        String nom = dev.optString("name", nombreTele == null ? "Samsung TV" : nombreTele);
                        SharedPreferences.Editor e = sp.edit().putString("ip", ipTele).putString("nombre", nom);
                        if (!mac.isEmpty()) e.putString("mac", mac);
                        e.apply();
                        nombreTele = nom;
                    }
                } catch (Exception ignored) { }
            } else if (nombreTele != null) {
                sp.edit().putString("ip", ipTele).putString("nombre", nombreTele).apply();
            }
        }

        String msg;
        if (nombreTele != null) msg = "Encontré tu tele " + limpiar(nombreTele) + " y " + Math.max(0, lista.length() - 1) + " dispositivos más en la red.";
        else if (lista.length() > 0) msg = "Encontré " + lista.length() + " dispositivos en la red, pero ninguna tele Samsung encendida. Préndela y vuelve a pedirme que la busque.";
        else msg = "No encontré dispositivos. Revisa que este celular esté en el mismo wifi.";
        responder("tv_buscar", nombreTele != null, msg, lista);
    }

    // ---------- encender / apagar ----------
    public void encender() {
        hilo.execute(() -> {
            String ip = sp.getString("ip", null), mac = sp.getString("mac", "");
            if (ip == null) { responder("tv_encender", false, "Todavía no conozco tu tele. Préndela una vez y dime: busca la tele.", null); return; }
            String estado = estadoEnergia(ip);
            if ("on".equals(estado)) { responder("tv_encender", true, "La tele ya está encendida.", null); return; }
            boolean wol = false;
            if (!mac.isEmpty()) wol = magia(mac, ip);
            if ("standby".equals(estado)) tecla("KEY_POWER");
            if (wol || "standby".equals(estado)) responder("tv_encender", true, "Encendiendo la tele.", null);
            else responder("tv_encender", false, "No pude encender la tele. Activa en la tele: Ajustes, General, Red, Configuración experta, Encender con móvil.", null);
        });
    }

    public void apagar() {
        hilo.execute(() -> {
            String ip = sp.getString("ip", null);
            if (ip == null) { responder("tv_apagar", false, "Todavía no conozco tu tele. Dime: busca la tele.", null); return; }
            String estado = estadoEnergia(ip);
            if ("standby".equals(estado) || "".equals(estado)) { responder("tv_apagar", true, "La tele ya está apagada.", null); return; }
            tecla("KEY_POWER");
            responder("tv_apagar", true, "Apagando la tele.", null);
        });
    }

    /** "on", "standby" o "" si no responde. */
    private String estadoEnergia(String ip) {
        String info = traer(httpRapido, "http://" + ip + ":8001/api/v2/");
        if (info.isEmpty()) return "";
        try {
            JSONObject dev = new JSONObject(info).optJSONObject("device");
            String ps = dev == null ? "" : dev.optString("PowerState", "on");
            return ps.isEmpty() ? "on" : ps.toLowerCase(Locale.ROOT);
        } catch (Exception e) { return "on"; }
    }

    /** Paquete mágico Wake-on-LAN, enviado varias veces a la red. */
    private boolean magia(String mac, String ip) {
        try {
            String[] h = mac.split("[:\\-]");
            if (h.length != 6) return false;
            byte[] m = new byte[6];
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(h[i], 16);
            byte[] pkt = new byte[102];
            for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xff;
            for (int i = 6; i < 102; i += 6) System.arraycopy(m, 0, pkt, i, 6);
            String red = ip.substring(0, ip.lastIndexOf('.')) + ".255";
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (int k = 0; k < 3; k++) {
                    for (String dest : new String[]{"255.255.255.255", red}) {
                        for (int puerto : new int[]{9, 7}) {
                            s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(dest), puerto));
                        }
                    }
                    Thread.sleep(200);
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "wol", e);
            return false;
        }
    }

    // ---------- teclas y apps ----------
    public void tecla(String codigo) {
        try {
            JSONObject m = new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject()
                    .put("Cmd", "Click").put("DataOfCmd", codigo).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"));
            enviar(m.toString());
        } catch (Exception ignored) { }
    }

    public void volumen(String que) {
        String k = "subir".equals(que) ? "KEY_VOLUP" : "bajar".equals(que) ? "KEY_VOLDOWN" : "KEY_MUTE";
        int veces = "silencio".equals(que) ? 1 : 3;
        for (int i = 0; i < veces; i++) tecla(k);
        responder("tv_volumen", true, "Listo.", null);
    }

    public void abrirApp(String nombre, String meta) {
        String[] ids = APPS.get(nombre.toLowerCase(Locale.ROOT));
        if (ids == null) { responder("tv_app", false, "No conozco esa app en la tele.", null); return; }
        // la primera opción suele ser la de las teles 2020 en adelante
        lanzar(ids[0], meta, "org.tizen.browser".equals(ids[0]) ? "NATIVE_LAUNCH" : "DEEP_LINK");
        responder("tv_app", true, "Abriendo " + nombre + " en la tele.", null);
    }

    public void youtube(String idVideo) {
        if (idVideo == null || idVideo.isEmpty()) { abrirApp("youtube", null); return; }
        lanzar("111299001912", "v=" + idVideo, "DEEP_LINK");
        // respaldo: el método DIAL que usa el botón "transmitir" de YouTube
        hilo.execute(() -> {
            String ip = sp.getString("ip", null);
            if (ip == null) return;
            try {
                Request r = new Request.Builder().url("http://" + ip + ":8080/ws/apps/YouTube")
                        .post(okhttp3.RequestBody.create("v=" + idVideo, okhttp3.MediaType.parse("text/plain"))).build();
                try (Response ignored = httpRapido.newCall(r).execute()) { }
            } catch (Exception ignored) { }
        });
        responder("tv_youtube", true, "Poniendo el video en la tele.", null);
    }

    private void lanzar(String appId, String meta, String tipo) {
        try {
            JSONObject data = new JSONObject().put("appId", appId).put("action_type", tipo);
            if (meta != null && !meta.isEmpty()) data.put("metaTag", meta);
            JSONObject m = new JSONObject().put("method", "ms.channel.emit").put("params",
                    new JSONObject().put("event", "ed.apps.launch").put("to", "host").put("data", data));
            enviar(m.toString());
        } catch (Exception ignored) { }
    }

    // ---------- conexión WebSocket con la tele ----------
    private synchronized void enviar(String texto) {
        if (listo && ws != null) { ws.send(texto); return; }
        pendientes.add(texto);
        if (ws == null) conectar();
    }

    private synchronized void conectar() {
        String ip = sp.getString("ip", null);
        if (ip == null) { responder("tv", false, "Todavía no conozco tu tele. Dime: busca la tele.", null); pendientes.clear(); return; }
        String nombre = Base64.encodeToString("T-Bot".getBytes(), Base64.NO_WRAP);
        String token = sp.getString("token", "");
        String url = "wss://" + ip + ":8002/api/v2/channels/samsung.remote.control?name=" + nombre + (token.isEmpty() ? "" : "&token=" + token);
        if (token.isEmpty()) responder("tv", true, "Mira la tele: si aparece un aviso, acepta con el control para que yo pueda manejarla.", null);
        ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket w, String text) {
                try {
                    JSONObject j = new JSONObject(text);
                    String ev = j.optString("event");
                    if ("ms.channel.connect".equals(ev)) {
                        JSONObject d = j.optJSONObject("data");
                        String tk = d == null ? "" : d.optString("token", "");
                        if (!tk.isEmpty()) sp.edit().putString("token", tk).apply();
                        List<String> cola;
                        synchronized (Tele.this) {
                            listo = true;
                            cola = new ArrayList<>(pendientes);
                            pendientes.clear();
                        }
                        for (String t : cola) w.send(t);
                    } else if ("ms.channel.unauthorized".equals(ev)) {
                        responder("tv", false, "La tele no me dio permiso. Pídeme otra acción y acepta el aviso en la tele.", null);
                        sp.edit().remove("token").apply();
                        cerrar();
                    } else if ("ms.channel.timeOut".equals(ev)) {
                        responder("tv", false, "Nadie aceptó el aviso en la tele.", null);
                        cerrar();
                    }
                } catch (Exception ignored) { }
            }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                Log.w(TAG, "tele ws", t);
                synchronized (Tele.this) {
                    boolean habiaPendientes = !pendientes.isEmpty();
                    ws = null; listo = false; pendientes.clear();
                    if (habiaPendientes) responder("tv", false, "No pude conectarme con la tele. ¿Está encendida y en el mismo wifi?", null);
                }
            }
            @Override public void onClosed(WebSocket w, int code, String reason) {
                synchronized (Tele.this) { ws = null; listo = false; }
            }
        });
    }

    private synchronized void cerrar() {
        if (ws != null) { try { ws.close(1000, "fin"); } catch (Exception ignored) { } }
        ws = null; listo = false; pendientes.clear();
    }

    public void apagarTodo() { cerrar(); hilo.shutdownNow(); }

    // ---------- utilidades ----------
    private static String cabecera(String txt, String nombre) {
        for (String l : txt.split("\r?\n")) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase(nombre)) return l.substring(i + 1).trim();
        }
        return "";
    }

    private static String etiqueta(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String limpiar(String n) {
        return n.replace("[TV]", "").replace("&quot;", "\"").trim();
    }

    private static String traer(OkHttpClient c, String url) {
        try (Response r = c.newCall(new Request.Builder().url(url).build()).execute()) {
            return r.body() != null ? r.body().string() : "";
        } catch (Exception e) { return ""; }
    }

    /** Cliente para la red de la casa: la tele usa un certificado propio, se acepta solo para su IP local. */
    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager", "BadHostnameVerifier"})
    private static OkHttpClient clienteLocal(int segundos) {
        try {
            X509TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{tm}, new SecureRandom());
            return new OkHttpClient.Builder()
                    .connectTimeout(segundos, TimeUnit.SECONDS)
                    .readTimeout(segundos * 3L, TimeUnit.SECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .sslSocketFactory(sc.getSocketFactory(), tm)
                    .hostnameVerifier((h, s) -> h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("172."))
                    .build();
        } catch (Exception e) {
            return new OkHttpClient();
        }
    }
}
