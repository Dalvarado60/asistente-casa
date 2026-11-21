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
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Control de la tele Samsung (Tizen) por wifi.
 * Cada orden se intenta de varias formas y se COMPRUEBA con la tele antes de decir "listo":
 *  - Apps: lista de apps instaladas → REST (puerto 8001) → WebSocket (8002) → DIAL (8080).
 *  - YouTube: busca el video en youtube.com desde el celular y lo manda por DIAL o enlace directo.
 *  - Todo lo que pasa queda anotado en "detalle" para el diagnóstico.
 */
public class Tele {

    public interface Aviso { void resultado(String json); }

    private static final String TAG = "AsistenteCasa";
    /** Identificadores conocidos de cada app en las teles Samsung (varían por año y región). */
    private static final Map<String, String[]> APPS = new LinkedHashMap<>();
    /** Palabras para reconocer la app en la lista de apps instaladas de la tele. */
    private static final Map<String, String[]> PALABRAS = new LinkedHashMap<>();
    /** Nombre de la app para el método DIAL. */
    private static final Map<String, String> DIAL = new LinkedHashMap<>();
    private static final Map<String, String> BONITO = new LinkedHashMap<>();
    static {
        APPS.put("youtube", new String[]{"111299001912", "9Ur5IzDKqV.TizenYouTube"});
        APPS.put("netflix", new String[]{"3201907018807", "11101200001", "RN1MCdNq8t.Netflix"});
        APPS.put("prime", new String[]{"3201910019365", "3201512006785", "org.tizen.ignition", "evKhCgZelL.AmazonIgnitionLauncher2"});
        APPS.put("disney", new String[]{"3201901017640", "MCmYXNxgcu.DisneyPlus"});
        APPS.put("spotify", new String[]{"3201606009684", "rJeHak5zRg.Spotify"});
        APPS.put("navegador", new String[]{"org.tizen.browser", "3201907018784"});
        PALABRAS.put("youtube", new String[]{"youtube"});
        PALABRAS.put("netflix", new String[]{"netflix"});
        PALABRAS.put("prime", new String[]{"prime video", "amazon", "prime"});
        PALABRAS.put("disney", new String[]{"disney"});
        PALABRAS.put("spotify", new String[]{"spotify"});
        PALABRAS.put("navegador", new String[]{"internet", "browser", "navegador"});
        DIAL.put("youtube", "YouTube");
        DIAL.put("netflix", "Netflix");
        DIAL.put("prime", "AmazonInstantVideo");
        BONITO.put("youtube", "YouTube");
        BONITO.put("netflix", "Netflix");
        BONITO.put("prime", "Prime Video");
        BONITO.put("disney", "Disney+");
        BONITO.put("spotify", "Spotify");
        BONITO.put("navegador", "el navegador");
    }
    private static final MediaType TEXTO = MediaType.parse("text/plain; charset=utf-8");

    private final Context ctx;
    private final Aviso aviso;
    private final SharedPreferences sp;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor();
    private final OkHttpClient http;       // red de la casa (acepta el certificado propio de la tele)
    private final OkHttpClient httpRapido;
    private final OkHttpClient web;        // internet normal (YouTube)

    private volatile WebSocket ws;
    private volatile boolean listo = false;
    private volatile String estadoCon = "";
    private volatile CountDownLatch latchCon, latchApps, latchLanzar;
    private volatile JSONArray appsTele;
    private volatile long appsHora = 0;
    private volatile String respuestaLanzar = "";
    private volatile boolean imeActivo = false;            // la tele tiene un teclado abierto (caja de búsqueda)
    private final List<String> ultimosVideos = new ArrayList<>();
    private int videoActual = 0;
    private final StringBuilder bitacora = new StringBuilder();

    public Tele(Context c, Aviso a) {
        ctx = c.getApplicationContext();
        aviso = a;
        sp = ctx.getSharedPreferences("tele", Context.MODE_PRIVATE);
        http = clienteLocal(5);
        httpRapido = clienteLocal(2);
        web = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build();
    }

    // =====================================================================
    // Avisos a la página
    // =====================================================================
    private void anotar(String s) {
        Log.i(TAG, "tele: " + s);
        synchronized (bitacora) {
            if (bitacora.length() > 6000) bitacora.delete(0, 2000);
            bitacora.append(s).append('\n');
        }
    }

    private String sacarBitacora() {
        synchronized (bitacora) {
            String s = bitacora.toString();
            bitacora.setLength(0);
            return s;
        }
    }

    private void responder(String accion, boolean ok, String msg, JSONArray extra) {
        try {
            JSONObject j = new JSONObject().put("accion", accion).put("ok", ok).put("msg", msg).put("detalle", sacarBitacora());
            if (extra != null) j.put("dispositivos", extra);
            aviso.resultado(j.toString());
        } catch (Exception ignored) { }
    }

    /** Aviso hablado mientras sigue trabajando (no termina la orden). */
    private void avisar(String msg) {
        try { aviso.resultado(new JSONObject().put("accion", "aviso").put("ok", true).put("msg", msg).toString()); }
        catch (Exception ignored) { }
    }

    public String estado() {
        try {
            return new JSONObject()
                    .put("ip", sp.getString("ip", ""))
                    .put("nombre", sp.getString("nombre", ""))
                    .put("mac", sp.getString("mac", ""))
                    .put("token", !sp.getString("token", "").isEmpty())
                    .put("conectada", listo)
                    .toString();
        } catch (Exception e) { return "{}"; }
    }

    private String ip() { return sp.getString("ip", null); }

    // =====================================================================
    // Buscar la tele y los dispositivos de la red
    // =====================================================================
    public void buscar() {
        hilo.execute(() -> {
            JSONArray lista = descubrir();
            String nombre = sp.getString("nombre", "");
            boolean hay = lista.length() > 0;
            boolean tele = false;
            for (int i = 0; i < lista.length(); i++) if (lista.optJSONObject(i).optBoolean("tele")) tele = true;
            String msg;
            if (tele) msg = "Encontré tu tele " + limpiar(nombre) + " y " + Math.max(0, lista.length() - 1) + " dispositivos más en la red.";
            else if (hay) msg = "Encontré " + lista.length() + " dispositivos en la red, pero ninguna tele Samsung encendida. Préndela y vuelve a pedirme que la busque.";
            else msg = "No encontré dispositivos. Revisa que este celular esté en el mismo wifi.";
            responder("tv_buscar", tele, msg, lista);
        });
    }

    /** Para el escáner de red: lo que anuncian los aparatos (nombre, fabricante, modelo). */
    public JSONArray descubrirLista() { return descubrir(); }

    /** Anuncio SSDP en la red. Guarda IP, nombre, MAC y la dirección DIAL de la tele. */
    private JSONArray descubrir() {
        Map<String, String[]> vistos = new LinkedHashMap<>(); // ip → {server, st, locations separadas por espacio}
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm != null ? wm.createMulticastLock("tbot-ssdp") : null;
        try {
            if (lock != null) { lock.setReferenceCounted(false); lock.acquire(); }
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(700);
                InetAddress grupo = InetAddress.getByName("239.255.255.250");
                for (String st : new String[]{"ssdp:all", "urn:dial-multiscreen-org:service:dial:1", "urn:samsung.com:device:RemoteControlReceiver:1"}) {
                    String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n";
                    byte[] b = msg.getBytes("UTF-8");
                    s.send(new DatagramPacket(b, b.length, grupo, 1900));
                    Thread.sleep(120);
                }
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
                    if (!st.isEmpty() && !d[1].contains(st)) d[1] = d[1] + " " + st;
                    if (!loc.isEmpty() && !d[2].contains(loc)) d[2] = d[2] + " " + loc;
                    vistos.put(ip, d);
                }
            }
        } catch (Exception e) {
            anotar("Error buscando en la red: " + e.getMessage());
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }

        JSONArray lista = new JSONArray();
        String ipTele = null, nombreTele = null, dialTele = null;
        for (Map.Entry<String, String[]> en : vistos.entrySet()) {
            String ip = en.getKey();
            String[] d = en.getValue();
            String nombre = "", fabricante = "", modelo = "", dial = "";
            for (String loc : d[2].trim().split(" ")) {
                if (loc.isEmpty()) continue;
                String[] xmlYApp = traerConCabecera(httpRapido, loc, "Application-URL");
                String xml = xmlYApp[0];
                if (!xmlYApp[1].isEmpty()) dial = xmlYApp[1];
                if (nombre.isEmpty()) nombre = etiqueta(xml, "friendlyName");
                if (fabricante.isEmpty()) fabricante = etiqueta(xml, "manufacturer");
                if (modelo.isEmpty()) modelo = etiqueta(xml, "modelName");
            }
            String todo = (d[0] + " " + fabricante + " " + d[1]).toLowerCase(Locale.ROOT);
            boolean samsung = todo.contains("samsung");
            boolean esTele = samsung && (d[1].contains("MediaRenderer") || d[1].contains("dial") || d[1].contains("RemoteControlReceiver")
                    || todo.contains("tizen") || nombre.toLowerCase(Locale.ROOT).contains("tv"));
            try {
                lista.put(new JSONObject().put("ip", ip).put("nombre", nombre.isEmpty() ? (fabricante.isEmpty() ? "Dispositivo" : fabricante) : nombre)
                        .put("fabricante", fabricante).put("modelo", modelo).put("tele", esTele));
            } catch (Exception ignored) { }
            if (esTele && ipTele == null) { ipTele = ip; nombreTele = nombre; dialTele = dial; }
        }

        if (ipTele == null) ipTele = ip(); // apagada: probar la que ya conocemos
        if (ipTele != null) {
            JSONObject dev = infoTele(ipTele);
            SharedPreferences.Editor e = sp.edit();
            if (dev != null) {
                String mac = dev.optString("wifiMac", "");
                String nom = dev.optString("name", nombreTele == null ? "Samsung TV" : nombreTele);
                e.putString("ip", ipTele).putString("nombre", nom);
                if (!mac.isEmpty()) e.putString("mac", mac);
                nombreTele = nom;
                anotar("Tele: " + nom + " · " + dev.optString("modelName") + " · " + ipTele + " · energía " + dev.optString("PowerState", "on"));
            } else if (nombreTele != null) {
                e.putString("ip", ipTele).putString("nombre", nombreTele);
            }
            if (dialTele != null && !dialTele.isEmpty()) { e.putString("dial", dialTele.endsWith("/") ? dialTele : dialTele + "/"); anotar("DIAL: " + dialTele); }
            e.apply();
        }
        return lista;
    }

    private JSONObject infoTele(String ip) {
        String info = traer(httpRapido, "http://" + ip + ":8001/api/v2/");
        if (info.isEmpty()) return null;
        try { return new JSONObject(info).optJSONObject("device"); } catch (Exception e) { return null; }
    }

    /** "on", "standby" o "" si no responde. */
    private String estadoEnergia(String ip) {
        JSONObject dev = infoTele(ip);
        if (dev == null) return "";
        String ps = dev.optString("PowerState", "on");
        return ps.isEmpty() ? "on" : ps.toLowerCase(Locale.ROOT);
    }

    /**
     * Deja la tele lista para recibir órdenes: la busca si cambió de IP y la prende si está apagada.
     * Devuelve la IP o null (y en ese caso ya respondió el motivo).
     */
    private String teleLista(String accion) {
        String ip = ip();
        if (ip == null) { descubrir(); ip = ip(); }
        if (ip == null) { responder(accion, false, "Todavía no encuentro tu tele. Préndela y dime: busca la tele.", null); return null; }
        String e = estadoEnergia(ip);
        if (e.isEmpty()) {
            anotar("La tele no contesta en " + ip + ", la busco otra vez");
            descubrir();
            ip = ip();
            e = estadoEnergia(ip);
        }
        if (e.isEmpty() || "standby".equals(e)) {
            if (sp.getString("mac", "").isEmpty() && e.isEmpty()) {
                responder(accion, false, "La tele no responde. ¿Está prendida y conectada al mismo wifi?", null);
                return null;
            }
            avisar("La tele está apagada. La prendo primero.");
            if (!encenderAhora(ip)) {
                responder(accion, false, "No logré prender la tele. Revisa en la tele: Configuración, General, Red, Configuración experta, Encender con móvil.", null);
                return null;
            }
            dormir(3000); // que termine de arrancar
        }
        return ip;
    }

    // =====================================================================
    // Encender / apagar
    // =====================================================================
    public void encender() {
        hilo.execute(() -> {
            String ip = ip();
            if (ip == null) { descubrir(); ip = ip(); }
            if (ip == null) { responder("tv_encender", false, "Todavía no conozco tu tele. Préndela una vez y dime: busca la tele.", null); return; }
            if ("on".equals(estadoEnergia(ip))) { responder("tv_encender", true, "La tele ya está encendida.", null); return; }
            if (encenderAhora(ip)) responder("tv_encender", true, "Listo, la tele ya prendió.", null);
            else responder("tv_encender", false, "No logré prender la tele. Revisa en la tele: Configuración, General, Red, Configuración experta, Encender con móvil.", null);
        });
    }

    private boolean encenderAhora(String ip) {
        String mac = sp.getString("mac", "");
        String e = estadoEnergia(ip);
        if ("on".equals(e)) return true;
        anotar("Prendiendo (estado: " + (e.isEmpty() ? "sin respuesta" : e) + ", MAC " + (mac.isEmpty() ? "desconocida" : mac) + ")");
        if (!mac.isEmpty()) magia(mac, ip);
        if ("standby".equals(e) && "ok".equals(conectar(10))) mandarTecla("KEY_POWER");
        for (int i = 0; i < 16; i++) {
            dormir(1000);
            if ("on".equals(estadoEnergia(ip))) { anotar("La tele prendió en " + (i + 1) + " s"); return true; }
            if (i == 5 && !mac.isEmpty()) magia(mac, ip);
        }
        anotar("La tele no prendió");
        return false;
    }

    public void apagar() {
        hilo.execute(() -> {
            String ip = ip();
            if (ip == null) { responder("tv_apagar", false, "Todavía no conozco tu tele. Dime: busca la tele.", null); return; }
            String e = estadoEnergia(ip);
            if (!"on".equals(e)) { responder("tv_apagar", true, "La tele ya está apagada.", null); return; }
            String c = conectar(35);
            if (!"ok".equals(c)) { responder("tv_apagar", false, razon(c), null); return; }
            mandarTecla("KEY_POWER");
            for (int i = 0; i < 6; i++) {
                dormir(1000);
                if (!"on".equals(estadoEnergia(ip))) { responder("tv_apagar", true, "Listo, apagué la tele.", null); return; }
            }
            responder("tv_apagar", true, "Le mandé la orden de apagarse a la tele.", null);
        });
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
                    for (String dest : new String[]{"255.255.255.255", red, ip}) {
                        for (int puerto : new int[]{9, 7}) {
                            s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(dest), puerto));
                        }
                    }
                    Thread.sleep(200);
                }
            }
            anotar("Wake-on-LAN enviado");
            return true;
        } catch (Exception e) {
            anotar("Wake-on-LAN falló: " + e.getMessage());
            return false;
        }
    }

    // =====================================================================
    // Teclas y volumen
    // =====================================================================
    public void tecla(String codigo) {
        hilo.execute(() -> {
            String c = conectar(35);
            if (!"ok".equals(c)) { responder("tv_tecla", false, razon(c), null); return; }
            boolean ok = mandarTecla(codigo);
            responder("tv_tecla", ok, ok ? "Listo." : "La tele no recibió la tecla.", null);
        });
    }

    public void volumen(String que) {
        hilo.execute(() -> {
            String ip = teleLista("tv_volumen");
            if (ip == null) return;
            String c = conectar(35);
            if (!"ok".equals(c)) { responder("tv_volumen", false, razon(c), null); return; }
            String k = "subir".equals(que) ? "KEY_VOLUP" : "bajar".equals(que) ? "KEY_VOLDOWN" : "KEY_MUTE";
            int veces = "silencio".equals(que) ? 1 : 4;
            boolean ok = true;
            for (int i = 0; i < veces; i++) { ok &= mandarTecla(k); dormir(180); }
            responder("tv_volumen", ok, ok ? "Listo." : "La tele no recibió la orden.", null);
        });
    }

    private boolean mandarTecla(String codigo) {
        try {
            JSONObject m = new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject()
                    .put("Cmd", "Click").put("DataOfCmd", codigo).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"));
            WebSocket w = ws;
            boolean ok = listo && w != null && w.send(m.toString());
            anotar("Tecla " + codigo + ": " + (ok ? "enviada" : "no se pudo"));
            return ok;
        } catch (Exception e) { return false; }
    }

    // =====================================================================
    // Apps
    // =====================================================================
    public void abrirApp(String nombre, String meta) {
        hilo.execute(() -> abrirAppAhora(nombre == null ? "" : nombre.toLowerCase(Locale.ROOT), "tv_app", null));
    }

    /** Números de la app: primero los que la tele dice tener instalados, luego los conocidos. */
    private List<String> idsDe(String clave) {
        List<String> ids = new ArrayList<>();
        if (listo) {
            JSONArray inst = appsInstaladas();
            String[] pal = PALABRAS.get(clave);
            if (inst != null && pal != null) for (int i = 0; i < inst.length(); i++) {
                JSONObject a = inst.optJSONObject(i);
                if (a == null) continue;
                String n = a.optString("name").toLowerCase(Locale.ROOT);
                for (String p : pal) if (n.contains(p) && !ids.contains(a.optString("appId"))) ids.add(a.optString("appId"));
            }
        }
        String[] fijos = APPS.get(clave);
        if (fijos != null) for (String f : fijos) if (!ids.contains(f)) ids.add(f);
        return ids;
    }

    /** Devuelve true si la abrió. Si msgOk es null usa el mensaje normal. */
    private boolean abrirAppAhora(String clave, String accion, String msgOk) {
        String[] fijos = APPS.get(clave);
        String bonito = BONITO.containsKey(clave) ? BONITO.get(clave) : clave;
        if (fijos == null) { responder(accion, false, "No conozco esa app en la tele.", null); return false; }
        String ip = teleLista(accion);
        if (ip == null) return false;

        List<String> ids = new ArrayList<>();
        String c = conectar(35);
        if ("ok".equals(c)) {
            JSONArray inst = appsInstaladas();
            if (inst != null) {
                String[] pal = PALABRAS.get(clave);
                for (int i = 0; i < inst.length(); i++) {
                    JSONObject a = inst.optJSONObject(i);
                    if (a == null) continue;
                    String n = a.optString("name").toLowerCase(Locale.ROOT);
                    for (String p : pal) if (n.contains(p) && !ids.contains(a.optString("appId"))) ids.add(a.optString("appId"));
                }
                anotar("En la lista de la tele, " + bonito + " = " + (ids.isEmpty() ? "no aparece" : ids));
            }
        } else {
            anotar("Sin WebSocket (" + c + "), pruebo por REST y DIAL");
        }
        for (String f : fijos) if (!ids.contains(f)) ids.add(f);

        for (String id : ids) {
            if (lanzarYComprobar(ip, id, null)) {
                responder(accion, true, msgOk != null ? msgOk : "Listo, " + bonito + " está abierto en la tele.", null);
                return true;
            }
        }
        String d = DIAL.get(clave);
        if (d != null && dial(ip, d, "")) {
            responder(accion, true, msgOk != null ? msgOk : "Listo, abrí " + bonito + " en la tele.", null);
            return true;
        }
        if (!"ok".equals(c)) responder(accion, false, razon(c), null);
        else responder(accion, false, "No pude abrir " + bonito + " en la tele. ¿La tienes instalada? Dime: prueba la tele, y te muestro qué pasó.", null);
        return false;
    }

    /** Intenta abrir una app por REST y por WebSocket y confirma con la tele que quedó abierta. */
    private boolean lanzarYComprobar(String ip, String id, String meta) {
        anotar("App " + id + ":");
        if (meta == null) {
            int code = post(http, "http://" + ip + ":8001/api/v2/applications/" + id, "");
            anotar("  REST 8001 → " + code);
            if (code == 404) return false; // no está instalada con ese número
            if (code == 200 && !"cerrada".equals(comprobar(ip, id, 8))) return true;
        }
        if (listo) {
            for (String tipo : new String[]{"DEEP_LINK", "NATIVE_LAUNCH"}) {
                String r = lanzarWs(id, meta, tipo);
                anotar("  WebSocket " + tipo + " → " + (r.isEmpty() ? "sin respuesta" : r));
                if (r.contains("404")) return false;
                if (r.contains("200")) {
                    String v = comprobar(ip, id, 8);
                    if (!"cerrada".equals(v) && !"no-instalada".equals(v)) return true;
                } else if (r.isEmpty() && "visible".equals(comprobar(ip, id, 5))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "visible", "cerrada", "no-instalada" o "desconocido" (la tele no deja preguntar). */
    private String comprobar(String ip, String id, int intentos) {
        for (int i = 0; i < intentos; i++) {
            dormir(1000);
            try (Response r = http.newCall(new Request.Builder().url("http://" + ip + ":8001/api/v2/applications/" + id).build()).execute()) {
                if (r.code() == 404) return "no-instalada";
                if (r.code() != 200 || r.body() == null) return "desconocido";
                JSONObject j = new JSONObject(r.body().string());
                if (j.optBoolean("visible", false)) { anotar("  ✓ abierta en pantalla"); return "visible"; }
            } catch (Exception e) { return "desconocido"; }
        }
        anotar("  ✗ no se ve abierta");
        return "cerrada";
    }

    private String lanzarWs(String appId, String meta, String tipo) {
        try {
            JSONObject data = new JSONObject().put("appId", appId).put("action_type", tipo);
            if (meta != null && !meta.isEmpty()) data.put("metaTag", meta);
            JSONObject m = new JSONObject().put("method", "ms.channel.emit").put("params",
                    new JSONObject().put("event", "ed.apps.launch").put("to", "host").put("data", data));
            CountDownLatch l = new CountDownLatch(1);
            latchLanzar = l;
            respuestaLanzar = "";
            WebSocket w = ws;
            if (w == null || !w.send(m.toString())) return "no se pudo enviar";
            l.await(4, TimeUnit.SECONDS);
            return respuestaLanzar;
        } catch (Exception e) { return "error " + e.getMessage(); }
    }

    private JSONArray appsInstaladas() {
        if (appsTele != null && System.currentTimeMillis() - appsHora < 10 * 60000) return appsTele;
        try {
            CountDownLatch l = new CountDownLatch(1);
            latchApps = l;
            JSONObject m = new JSONObject().put("method", "ms.channel.emit").put("params",
                    new JSONObject().put("event", "ed.installedApp.get").put("to", "host"));
            WebSocket w = ws;
            if (w == null || !w.send(m.toString())) return null;
            if (!l.await(5, TimeUnit.SECONDS)) { anotar("La tele no mandó su lista de apps"); return null; }
            return appsTele;
        } catch (Exception e) { return null; }
    }

    /** Método DIAL (el que usan los botones de "transmitir"). */
    private boolean dial(String ip, String app, String cuerpo) {
        List<String> bases = new ArrayList<>();
        String guardada = sp.getString("dial", "");
        if (!guardada.isEmpty()) bases.add(guardada);
        for (String b : new String[]{"http://" + ip + ":8080/ws/apps/", "http://" + ip + ":8080/ws/app/"}) if (!bases.contains(b)) bases.add(b);
        for (String base : bases) {
            int code = post(httpRapido, base + app, cuerpo);
            anotar("  DIAL " + base + app + " → " + code);
            if (code == 200 || code == 201) {
                sp.edit().putString("dial", base).apply();
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Netflix: abrir una película o serie directo (número de netflix.com/title/...)
    // =====================================================================
    public void netflix(String titulo) {
        hilo.execute(() -> {
            String ip = teleLista("tv_netflix");
            if (ip == null) return;
            String id = titulo == null ? "" : titulo.replaceAll("[^0-9]", "");
            if (id.isEmpty()) { abrirAppAhora("netflix", "tv_netflix", null); return; }
            anotar("Netflix título " + id);
            if ("ok".equals(conectar(35))) {
                for (String appId : idsDe("netflix")) {
                    String r = lanzarWs(appId, "m=" + id + "&", "DEEP_LINK");
                    anotar("  WebSocket Netflix " + appId + " → " + (r.isEmpty() ? "sin respuesta" : r));
                    if (r.contains("404")) continue;
                    if (r.contains("200")) { responder("tv_netflix", true, "Listo, abriendo eso en Netflix.", null); return; }
                }
            }
            if (dial(ip, "Netflix", "m=https://www.netflix.com/title/" + id + "&source_type=4")) {
                responder("tv_netflix", true, "Listo, abriendo eso en Netflix.", null);
                return;
            }
            abrirAppAhora("netflix", "tv_netflix", "Abrí Netflix, pero no pude ir directo a esa película. Búscala con el control o dime: escribe, y el nombre.");
        });
    }

    // =====================================================================
    // Escribir en la tele (cuando hay una caja de búsqueda abierta)
    // =====================================================================
    public void escribir(String texto, boolean enter) {
        hilo.execute(() -> {
            String c = conectar(35);
            if (!"ok".equals(c)) { responder("tv_escribir", false, razon(c), null); return; }
            try {
                String b64 = Base64.encodeToString(texto.getBytes("UTF-8"), Base64.NO_WRAP);
                JSONObject m = new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject()
                        .put("Cmd", b64).put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString"));
                WebSocket w = ws;
                boolean ok = w != null && w.send(m.toString());
                anotar("Escribir \"" + texto + "\": " + (ok ? "enviado" : "no se pudo") + (imeActivo ? " (teclado abierto)" : " (no vi teclado abierto)"));
                if (ok && w != null) w.send(new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject().put("TypeOfRemote", "SendInputEnd")).toString());
                if (ok && enter) { dormir(400); mandarTecla("KEY_ENTER"); }
                String msg = !ok ? "La tele no recibió el texto." : imeActivo ? "Listo, lo escribí." : "Lo mandé. Si no apareció, primero abre la búsqueda en la tele y me lo vuelves a pedir.";
                responder("tv_escribir", ok, msg, null);
            } catch (Exception e) { responder("tv_escribir", false, "No pude escribir en la tele.", null); }
        });
    }

    // =====================================================================
    // YouTube
    // =====================================================================
    /** Busca el video en YouTube desde el celular y lo pone en la tele. */
    public void youtubeBuscar(String consulta) {
        hilo.execute(() -> {
            String ip = teleLista("tv_youtube");
            if (ip == null) return;
            String id = buscarVideo(consulta);
            videoActual = 0;
            if (id == null) {
                abrirAppAhora("youtube", "tv_youtube", "No encontré ese video, pero abrí YouTube en la tele.");
                return;
            }
            ponerVideo(ip, id);
        });
    }

    /** "Pon el siguiente": el siguiente resultado de la última búsqueda. */
    public void youtubeSiguiente() {
        hilo.execute(() -> {
            if (videoActual + 1 >= ultimosVideos.size()) { responder("tv_youtube", false, "Ya no tengo más resultados de esa búsqueda. Pídeme otra.", null); return; }
            videoActual++;
            String ip = teleLista("tv_youtube");
            if (ip != null) ponerVideo(ip, ultimosVideos.get(videoActual));
        });
    }

    /** Pone un video ya conocido (por su código de 11 letras). */
    public void youtube(String idVideo) {
        hilo.execute(() -> {
            if (idVideo == null || idVideo.isEmpty()) { abrirAppAhora("youtube", "tv_youtube", null); return; }
            String ip = teleLista("tv_youtube");
            if (ip != null) ponerVideo(ip, idVideo);
        });
    }

    private void ponerVideo(String ip, String id) {
        anotar("Video: https://youtu.be/" + id);
        if (dial(ip, "YouTube", "v=" + id)) { responder("tv_youtube", true, "Listo, puse el video en la tele.", null); return; }
        String c = conectar(35);
        if ("ok".equals(c)) {
            for (String appId : APPS.get("youtube")) {
                String r = lanzarWs(appId, "v=" + id, "DEEP_LINK");
                anotar("  WebSocket YouTube " + appId + " → " + (r.isEmpty() ? "sin respuesta" : r));
                if (r.contains("200")) {
                    String v = comprobar(ip, appId, 10);
                    if (!"cerrada".equals(v) && !"no-instalada".equals(v)) { responder("tv_youtube", true, "Listo, puse el video en la tele.", null); return; }
                }
            }
        }
        abrirAppAhora("youtube", "tv_youtube", "Abrí YouTube en la tele, pero no logré poner el video solo. Búscalo con el control.");
    }

    private String buscarVideo(String consulta) {
        try {
            String url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(consulta, "UTF-8") + "&hl=es-419&gl=GT";
            Request r = new Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                    .header("Accept-Language", "es-419,es;q=0.9")
                    .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                    .build();
            try (Response res = web.newCall(r).execute()) {
                String html = res.body() != null ? res.body().string() : "";
                ultimosVideos.clear();
                Matcher m = Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\"").matcher(html);
                while (m.find() && ultimosVideos.size() < 8) if (!ultimosVideos.contains(m.group(1))) ultimosVideos.add(m.group(1));
                if (ultimosVideos.isEmpty()) {
                    m = Pattern.compile("/watch\\?v=([\\w-]{11})").matcher(html);
                    while (m.find() && ultimosVideos.size() < 8) if (!ultimosVideos.contains(m.group(1))) ultimosVideos.add(m.group(1));
                }
                if (!ultimosVideos.isEmpty()) { anotar("Encontré en YouTube: " + consulta + " (" + ultimosVideos.size() + " resultados)"); return ultimosVideos.get(0); }
                anotar("YouTube no dio resultados (" + res.code() + ")");
            }
        } catch (Exception e) {
            anotar("No pude buscar en YouTube: " + e.getMessage());
        }
        return null;
    }

    // =====================================================================
    // Diagnóstico: "prueba la tele"
    // =====================================================================
    public void probar() {
        hilo.execute(() -> {
            anotar("— Prueba de la tele (T-Bot 6) —");
            String ip = ip();
            if (ip == null) { descubrir(); ip = ip(); }
            if (ip == null) { responder("tv_probar", false, "No encuentro ninguna tele Samsung en la red. Préndela y prueba otra vez.", null); return; }
            JSONObject dev = infoTele(ip);
            if (dev == null) {
                anotar("✗ La tele no contesta en " + ip + " (puerto 8001)");
                descubrir();
                ip = ip();
                dev = ip == null ? null : infoTele(ip);
            }
            if (dev == null) { responder("tv_probar", false, "La tele no contesta. ¿Está prendida y en el mismo wifi que este celular?", null); return; }
            anotar("✓ Tele " + dev.optString("name") + " (" + dev.optString("modelName") + ") en " + ip);
            anotar("  Energía: " + dev.optString("PowerState", "on") + " · MAC: " + dev.optString("wifiMac", "?") + " · Pide permiso: " + dev.optString("TokenAuthSupport", "?"));
            anotar("  Permiso guardado: " + (sp.getString("token", "").isEmpty() ? "no" : "sí"));
            String c = conectar(40);
            boolean ok = "ok".equals(c);
            anotar((ok ? "✓" : "✗") + " Control remoto (WebSocket): " + c);
            int apps = 0;
            if (ok) {
                JSONArray inst = appsInstaladas();
                apps = inst == null ? 0 : inst.length();
                if (inst != null) {
                    StringBuilder n = new StringBuilder();
                    for (int i = 0; i < inst.length() && i < 25; i++) n.append(inst.optJSONObject(i).optString("name")).append(" [").append(inst.optJSONObject(i).optString("appId")).append("]  ");
                    anotar("  Apps: " + n);
                }
            }
            int yt = codigo(httpRapido, "http://" + ip + ":8001/api/v2/applications/" + APPS.get("youtube")[0]);
            anotar("  REST apps (8001): " + (yt == 200 ? "✓ responde" : "→ " + yt));
            String base = sp.getString("dial", "http://" + ip + ":8080/ws/apps/");
            int dy = codigo(httpRapido, base + "YouTube");
            anotar("  DIAL (" + base + "): " + (dy == 200 ? "✓ responde" : "→ " + dy));
            String msg;
            if (ok) msg = "La tele responde y me dio permiso." + (apps > 0 ? " Veo " + apps + " apps instaladas." : "") + " Te dejo el detalle en pantalla.";
            else msg = razon(c) + " Te dejo el detalle en pantalla.";
            responder("tv_probar", ok, msg, null);
        });
    }

    // =====================================================================
    // Conexión WebSocket con la tele
    // =====================================================================
    /** Conecta (o reutiliza la conexión) y espera la respuesta de la tele. Devuelve "ok" o el motivo. */
    private String conectar(int seg) {
        if (listo && ws != null) return "ok";
        String ip = ip();
        if (ip == null) return "sin-ip";
        String r = conectarA("wss://" + ip + ":8002", seg);
        if (r.startsWith("error")) {
            anotar("Pruebo el puerto 8001");
            String r2 = conectarA("ws://" + ip + ":8001", 8);
            if ("ok".equals(r2)) return r2;
        }
        return r;
    }

    private String conectarA(String base, int seg) {
        cerrarWs();
        String nombre = Base64.encodeToString("T-Bot".getBytes(), Base64.NO_WRAP);
        String token = sp.getString("token", "");
        String url = base + "/api/v2/channels/samsung.remote.control?name=" + nombre + (token.isEmpty() ? "" : "&token=" + token);
        CountDownLatch l = new CountDownLatch(1);
        latchCon = l;
        estadoCon = "la tele no contestó";
        anotar("Conectando " + base + (token.isEmpty() ? " (primera vez)" : " (con permiso guardado)"));
        WebSocket nuevo = http.newWebSocket(new Request.Builder().url(url).build(), new Oyente(l));
        ws = nuevo;
        try {
            if (!l.await(4, TimeUnit.SECONDS)) {
                avisar("Mira la tele. Si aparece un aviso de T-Bot, dale Permitir con el control.");
                l.await(Math.max(2, seg - 4), TimeUnit.SECONDS);
            }
        } catch (InterruptedException ignored) { }
        if (!listo) { if (ws == nuevo) cerrarWs(); }
        anotar("Conexión: " + (listo ? "ok" : estadoCon));
        return listo ? "ok" : estadoCon;
    }

    private class Oyente extends WebSocketListener {
        private final CountDownLatch l;
        Oyente(CountDownLatch l) { this.l = l; }

        @Override public void onMessage(WebSocket w, String text) {
            try {
                JSONObject j = new JSONObject(text);
                String ev = j.optString("event");
                if ("ms.channel.connect".equals(ev)) {
                    JSONObject d = j.optJSONObject("data");
                    String tk = d == null ? "" : d.optString("token", "");
                    if (!tk.isEmpty()) sp.edit().putString("token", tk).apply();
                    listo = true;
                    estadoCon = "ok";
                    l.countDown();
                } else if ("ms.channel.unauthorized".equals(ev)) {
                    estadoCon = "denegado";
                    sp.edit().remove("token").apply();
                    l.countDown();
                } else if ("ms.channel.timeOut".equals(ev)) {
                    estadoCon = "nadie aceptó";
                    l.countDown();
                } else if ("ed.installedApp.get".equals(ev)) {
                    JSONObject d = j.optJSONObject("data");
                    JSONArray a = d == null ? null : d.optJSONArray("data");
                    if (a != null) { appsTele = a; appsHora = System.currentTimeMillis(); }
                    CountDownLatch la = latchApps;
                    if (la != null) la.countDown();
                } else if ("ed.apps.launch".equals(ev)) {
                    respuestaLanzar = String.valueOf(j.opt("data"));
                    CountDownLatch ll = latchLanzar;
                    if (ll != null) ll.countDown();
                } else if ("ms.remote.imeStart".equals(ev)) {
                    imeActivo = true;
                } else if ("ms.remote.imeEnd".equals(ev) || "ms.remote.imeDone".equals(ev)) {
                    imeActivo = false;
                } else if (!"ed.edenTV.update".equals(ev) && !"ms.channel.clientConnect".equals(ev) && !"ms.channel.clientDisconnect".equals(ev)) {
                    anotar("Tele dice: " + (text.length() > 140 ? text.substring(0, 140) + "…" : text));
                }
            } catch (Exception ignored) { }
        }

        @Override public void onFailure(WebSocket w, Throwable t, Response r) {
            String motivo = "error " + (r != null ? "HTTP " + r.code() + " " : "") + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            Log.w(TAG, "tele ws", t);
            if (w == ws) { ws = null; listo = false; }
            if (l.getCount() > 0) { estadoCon = motivo; l.countDown(); }
            else anotar("Se cortó la conexión con la tele: " + motivo);
            CountDownLatch la = latchApps; if (la != null) la.countDown();
            CountDownLatch ll = latchLanzar; if (ll != null) ll.countDown();
        }

        @Override public void onClosing(WebSocket w, int code, String reason) {
            if (w == ws) { ws = null; listo = false; }
            try { w.close(1000, null); } catch (Exception ignored) { }
            if (l.getCount() > 0) { estadoCon = "la tele cerró la conexión (" + code + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")"; l.countDown(); }
        }

        @Override public void onClosed(WebSocket w, int code, String reason) {
            if (w == ws) { ws = null; listo = false; }
        }
    }

    /** Explicación hablada de por qué no se pudo. */
    private String razon(String c) {
        if (c == null) c = "";
        if ("sin-ip".equals(c)) return "Todavía no conozco tu tele. Préndela y dime: busca la tele.";
        if ("denegado".equals(c)) return "La tele no me deja controlarla. En la tele ve a Configuración, General, Administrador de dispositivos externos, Administrador de conexión de dispositivos, Lista de dispositivos, y pon T-Bot en Permitir.";
        if ("nadie aceptó".equals(c) || "la tele no contestó".equals(c)) return "No aceptaron el aviso en la tele. Pídemelo otra vez y dale Permitir con el control.";
        if (c.startsWith("la tele cerró")) return "La tele cerró la conexión. Revisa que T-Bot esté en Permitir en la lista de dispositivos de la tele.";
        return "No pude conectarme con el control de la tele. ¿Está prendida y en el mismo wifi?";
    }

    private void cerrarWs() {
        WebSocket w = ws;
        ws = null;
        listo = false;
        if (w != null) { try { w.close(1000, "fin"); } catch (Exception ignored) { } }
    }

    public void apagarTodo() { cerrarWs(); hilo.shutdownNow(); }

    // =====================================================================
    // Utilidades
    // =====================================================================
    private static void dormir(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

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

    /** Devuelve {cuerpo, valor de la cabecera}. */
    private static String[] traerConCabecera(OkHttpClient c, String url, String cab) {
        try (Response r = c.newCall(new Request.Builder().url(url).build()).execute()) {
            String h = r.header(cab);
            return new String[]{r.body() != null ? r.body().string() : "", h == null ? "" : h};
        } catch (Exception e) { return new String[]{"", ""}; }
    }

    private static int codigo(OkHttpClient c, String url) {
        try (Response r = c.newCall(new Request.Builder().url(url).build()).execute()) {
            return r.code();
        } catch (Exception e) { return -1; }
    }

    private int post(OkHttpClient c, String url, String cuerpo) {
        try (Response r = c.newCall(new Request.Builder().url(url).post(RequestBody.create(cuerpo == null ? "" : cuerpo, TEXTO)).build()).execute()) {
            return r.code();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Cliente para la red de la casa: la tele usa un certificado propio, se acepta solo para IPs locales. */
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
