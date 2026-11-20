package gt.david.asistentecasa;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * El "cuerpo" de T-Bot. Vive mientras la app esté encendida, aunque cierres la pantalla de la app.
 * Tiene la página (cerebro, cámara, Firebase, video), el oído, la oreja (voz a texto) y la voz.
 *
 * Truco para que funcione en segundo plano: cuando sales de la app, la página se pasa a una
 * ventanita invisible de 1×1 píxel (permiso "Mostrar sobre otras apps"). Así Android la sigue
 * considerando visible y no le apaga la cámara, el micrófono ni la conexión.
 */
public class Estacion {

    private static final String TAG = "AsistenteCasa";
    static final String URL = "https://appassets.androidplatform.net/assets/estacion.html";

    @SuppressLint("StaticFieldLeak")
    private static Estacion yo;

    public static synchronized Estacion get(Context c) {
        if (yo == null) yo = new Estacion(c.getApplicationContext());
        return yo;
    }

    public static synchronized Estacion actual() { return yo; }

    private final Context app;
    private final MutableContextWrapper ctxWeb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private Oido oido;
    private Voz voz;
    private Oreja oreja;
    private Tele tele;
    private AudioFocusRequest enfoque;
    private WeakReference<Activity> actividad = new WeakReference<>(null);
    private boolean enVentanita = false;
    private volatile boolean alFrente = false;
    private long ultimoNivel = 0;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private Estacion(Context app) {
        this.app = app;
        this.ctxWeb = new MutableContextWrapper(app);

        final WebViewAssetLoader cargador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(app))
                .build();

        web = new WebView(ctxWeb);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Puente(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return cargador.shouldInterceptRequest(request.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                ui.post(() -> {
                    boolean ok = app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            && app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                    if (ok) request.grant(request.getResources()); else request.deny();
                });
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });

        voz = new Voz(app, new Voz.Aviso() {
            @Override public void empezo() { js("casaNativo.vozEmpezo()"); }
            @Override public void termino() {
                oido.setIgnorar(false);
                js("casaNativo.vozTermino()");
            }
        });

        oido = new Oido(app, new Oido.Escucha() {
            @Override public void nivel(float db) {
                long ahora = SystemClock.elapsedRealtime();
                if (ahora - ultimoNivel < 90) return;
                ultimoNivel = ahora;
                js("casaNativo.nivel(" + String.format(Locale.US, "%.1f", db) + ")");
            }
            @Override public void despertar(String frase) { js("casaNativo.despertar(" + JSONObject.quote(frase) + ")"); }
            @Override public void comandoListo(int ms) { js("casaNativo.comando(" + ms + ")"); }
            @Override public void sinComando() { js("casaNativo.sinComando()"); }
            @Override public void estado(String e) { js("casaNativo.estado(" + JSONObject.quote(e) + ")"); }
        });

        oreja = new Oreja(app, new Oreja.Aviso() {
            @Override public void parcial(String t) { js("casaNativo.parcial(" + JSONObject.quote(t) + ")"); }
            @Override public void listo(String t) {
                oido.iniciar();
                js("casaNativo.textoFinal(" + JSONObject.quote(t) + ")");
            }
            @Override public void error(int codigo) {
                oido.iniciar();
                js("casaNativo.textoError(" + codigo + ")");
            }
        });

        tele = new Tele(app, json -> js("casaNativo.tele(" + JSONObject.quote(json) + ")"));

        web.loadUrl(URL);
    }

    /** Ejecuta código en la página, desde cualquier hilo. */
    void js(final String codigo) {
        ui.post(() -> {
            if (web != null) web.evaluateJavascript("window.casaNativo && (function(){try{" + codigo + "}catch(e){}})()", null);
        });
    }

    // ---------- dónde se muestra la página ----------

    /** La app se abrió: la página se muestra a pantalla completa. */
    void mostrarEn(Activity a) {
        actividad = new WeakReference<>(a);
        quitarDeVentanita();
        quitarDePadre();
        ctxWeb.setBaseContext(a);
        a.setContentView(web);
        alFrente = true;
        js("casaNativo.primerPlano(true)");
    }

    /** Saliste de la app: la página se pasa a la ventanita invisible para seguir trabajando. */
    void pasarAFondo(Activity a) {
        if (actividad.get() == a) actividad = new WeakReference<>(null);
        alFrente = false;
        js("casaNativo.primerPlano(false)");
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "Sin permiso de superposición: la cámara puede pausarse en segundo plano");
            return;
        }
        quitarDePadre();
        ctxWeb.setBaseContext(app);
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.alpha = 0.01f;
        try {
            wm.addView(web, lp);
            enVentanita = true;
        } catch (Exception e) {
            Log.w(TAG, "No pude crear la ventanita", e);
        }
    }

    private void quitarDeVentanita() {
        if (!enVentanita) return;
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            wm.removeViewImmediate(web);
        } catch (Exception e) { Log.w(TAG, "quitar ventanita", e); }
        enVentanita = false;
    }

    private void quitarDePadre() {
        if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
    }

    boolean alFrente() { return alFrente; }

    /** Apaga todo (botón "Apagar" de la app). */
    static synchronized void apagar() {
        if (yo == null) return;
        Estacion e = yo;
        yo = null;
        e.quitarDeVentanita();
        e.quitarDePadre();
        e.oido.cerrar();
        e.oreja.cerrar();
        e.voz.cerrar();
        e.tele.apagarTodo();
        e.soltarEnfoque();
        e.web.destroy();
        e.web = null;
    }

    // ---------- audio ----------
    private AudioManager am() { return (AudioManager) app.getSystemService(Context.AUDIO_SERVICE); }

    private void pedirEnfoque() {
        AudioManager a = am();
        if (a == null) return;
        if (enfoque == null) {
            enfoque = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build();
        }
        a.requestAudioFocus(enfoque);
    }

    private void soltarEnfoque() {
        AudioManager a = am();
        if (a != null && enfoque != null) a.abandonAudioFocusRequest(enfoque);
    }

    private void teclaMultimedia(int codigo) {
        AudioManager a = am();
        if (a == null) return;
        a.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, codigo));
        a.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, codigo));
    }

    private void traerAlFrente() {
        try {
            Intent i = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(i);
        } catch (Exception e) { Log.w(TAG, "frente", e); }
    }

    // ---------- puente JavaScript ↔ Android ----------
    public class Puente {

        @JavascriptInterface public boolean nativo() { return true; }
        @JavascriptInterface public String version() { return "6.0"; }
        @JavascriptInterface public boolean enPrimerPlano() { return alFrente; }
        @JavascriptInterface public void alFrente() { ui.post(Estacion.this::traerAlFrente); }

        @JavascriptInterface
        public String bateria() {
            Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return "null";
            int nivel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int escala = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int estado = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean cargando = estado == BatteryManager.BATTERY_STATUS_CHARGING || estado == BatteryManager.BATTERY_STATUS_FULL;
            int pct = escala > 0 ? Math.round(nivel * 100f / escala) : -1;
            return "{\"nivel\":" + pct + ",\"cargando\":" + cargando + "}";
        }

        @JavascriptInterface
        public void atenuar(final boolean si) {
            ui.post(() -> {
                Activity a = actividad.get();
                if (a == null) return;
                WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                lp.screenBrightness = si ? 0.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                a.getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public void altavoz(final boolean si) {
            ui.post(() -> {
                AudioManager a = am();
                if (a == null) return;
                try {
                    if (si) {
                        a.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        if (Build.VERSION.SDK_INT >= 31) {
                            for (AudioDeviceInfo d : a.getAvailableCommunicationDevices()) {
                                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { a.setCommunicationDevice(d); break; }
                            }
                        } else {
                            a.setSpeakerphoneOn(true);
                        }
                        a.setStreamVolume(AudioManager.STREAM_VOICE_CALL, a.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);
                    } else {
                        if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice(); else a.setSpeakerphoneOn(false);
                        a.setMode(AudioManager.MODE_NORMAL);
                    }
                } catch (Exception e) { Log.w(TAG, "altavoz", e); }
            });
        }

        // --- oído (palabra de activación) ---
        @JavascriptInterface
        public void oidoIniciar(String palabrasCsv, String clavePicovoice) {
            oido.cargarModelo(palabrasCsv.split(","));
            oido.iniciar();
        }

        @JavascriptInterface
        public void picovoice(String clave) { /* sin Porcupine en esta versión */ }

        @JavascriptInterface public void palabras(String palabrasCsv) { oido.setFrases(palabrasCsv.split(",")); }

        @JavascriptInterface
        public void micPausa(boolean si) {
            if (si) { oreja.cancelar(); oido.detener(); } else oido.iniciar();
        }

        /** Vigilante: si el oído quedó tapado o apagado, lo deja escuchando otra vez. */
        @JavascriptInterface
        public void reiniciarOido() {
            if (oreja.escuchando()) return;
            oido.setIgnorar(false);
            oido.modoDespertar();
            if (!oido.activo()) oido.iniciar();
        }

        // --- tele y red ---
        @JavascriptInterface public void teleBuscar() { tele.buscar(); }
        @JavascriptInterface public void teleEncender() { tele.encender(); }
        @JavascriptInterface public void teleApagar() { tele.apagar(); }
        @JavascriptInterface public void teleVolumen(String que) { tele.volumen(que); }
        @JavascriptInterface public void teleApp(String nombre) { tele.abrirApp(nombre, null); }
        @JavascriptInterface public void teleYoutube(String idVideo) { tele.youtube(idVideo); }
        @JavascriptInterface public void teleYoutubeBuscar(String consulta) { tele.youtubeBuscar(consulta); }
        @JavascriptInterface public void teleProbar() { tele.probar(); }

        // --- mapas y Waze ---
        /** Ruta con el tráfico actual según Waze. Contesta en casaNativo.ruta(json). */
        @JavascriptInterface
        public void wazeRuta(double la1, double lo1, double la2, double lo2) {
            new Thread(() -> {
                String r = Waze.ruta(la1, lo1, la2, lo2);
                js("casaNativo.ruta(" + JSONObject.quote(r) + ")");
            }).start();
        }

        /** Abre Waze manejando hacia el destino (por coordenadas o por nombre). */
        @JavascriptInterface
        public boolean abrirWaze(String lat, String lon, String destino) {
            boolean coords = lat != null && !lat.isEmpty() && lon != null && !lon.isEmpty();
            String uriApp = coords ? "waze://?ll=" + lat + "," + lon + "&navigate=yes" : "waze://?q=" + Uri.encode(destino == null ? "" : destino) + "&navigate=yes";
            String webUrl = coords ? "https://waze.com/ul?ll=" + lat + "," + lon + "&navigate=yes" : "https://waze.com/ul?q=" + Uri.encode(destino == null ? "" : destino) + "&navigate=yes";
            try {
                app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uriApp)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) {
                return abrirUrl(webUrl);
            }
        }

        /** Última ubicación conocida del celular "lat,lon" (o "" si no hay permiso o todavía no la sabe). */
        @JavascriptInterface
        public String ubicacion() {
            Context c = Estacion.this.app;
            if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "";
            try {
                LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) return "";
                Location mejor = null;
                for (String p : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (mejor == null || l.getTime() > mejor.getTime())) mejor = l;
                }
                if (mejor == null || System.currentTimeMillis() - mejor.getTime() > 6L * 3600000) pedirUbicacion(lm);
                return mejor == null ? "" : mejor.getLatitude() + "," + mejor.getLongitude();
            } catch (SecurityException e) { return ""; }
        }

        @SuppressWarnings("deprecation")
        private void pedirUbicacion(LocationManager lm) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    String prov = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
                    lm.requestSingleUpdate(prov, new LocationListener() {
                        @Override public void onLocationChanged(Location l) { }
                        @Override public void onStatusChanged(String p, int s, android.os.Bundle b) { }
                        @Override public void onProviderEnabled(String p) { }
                        @Override public void onProviderDisabled(String p) { }
                    }, Looper.getMainLooper());
                } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void teleTecla(String codigo) { tele.tecla(codigo); }
        @JavascriptInterface public String teleEstado() { return tele.estado(); }

        /** Abre un enlace en su app (por ejemplo, la ruta en Waze). */
        @JavascriptInterface
        public boolean abrirUrl(String url) {
            try {
                app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public boolean micActivo() { return oido.activo() || oreja.escuchando(); }
        @JavascriptInterface public void escucharComando() { oido.escucharComando(); }
        @JavascriptInterface public void modoDespertar() { oido.modoDespertar(); }

        @JavascriptInterface
        public String tomarAudio() {
            byte[] w = oido.tomarWav();
            return w == null ? "" : Base64.encodeToString(w, Base64.NO_WRAP);
        }

        // --- oreja (voz a texto de Android) ---
        @JavascriptInterface public boolean tieneOreja() { return oreja.disponible(); }

        @JavascriptInterface
        public void escucharTexto() {
            oido.detener();          // el micrófono pasa al reconocedor de Android
            oreja.escuchar();
        }

        // --- voz ---
        @JavascriptInterface
        public void hablar(String texto) {
            oido.setIgnorar(true);
            voz.hablar(texto);
        }

        @JavascriptInterface public void callar() { voz.callar(); }
        @JavascriptInterface public void vozConfig(String idioma, float tono, float velocidad) { voz.configurar(idioma, tono, velocidad); }
        @JavascriptInterface public void enfoque(boolean si) { if (si) pedirEnfoque(); else soltarEnfoque(); }

        // --- música ---
        @JavascriptInterface
        public String spotify(final String busqueda) {
            Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
            i.setPackage("com.spotify.music");
            i.putExtra(SearchManager.QUERY, busqueda);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                app.startActivity(i);
            } catch (ActivityNotFoundException e) {
                try {
                    app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(busqueda))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e2) {
                    return "sin-spotify";
                }
            }
            final boolean estabaAlFrente = alFrente;
            ui.postDelayed(() -> {
                AudioManager a = am();
                if (a != null && !a.isMusicActive()) teclaMultimedia(KeyEvent.KEYCODE_MEDIA_PLAY);
            }, 4500);
            if (estabaAlFrente) ui.postDelayed(Estacion.this::traerAlFrente, 7000);
            return Settings.canDrawOverlays(app) ? "ok" : "ok-sin-permiso";
        }

        @JavascriptInterface
        public void media(String accion) {
            switch (accion) {
                case "pausa": teclaMultimedia(KeyEvent.KEYCODE_MEDIA_PAUSE); break;
                case "reanudar": teclaMultimedia(KeyEvent.KEYCODE_MEDIA_PLAY); break;
                case "siguiente": teclaMultimedia(KeyEvent.KEYCODE_MEDIA_NEXT); break;
                case "anterior": teclaMultimedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
                case "subir": case "bajar": {
                    AudioManager a = am();
                    if (a == null) break;
                    int dir = accion.equals("subir") ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                    for (int k = 0; k < 3; k++) a.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
                    break;
                }
            }
        }

        @JavascriptInterface
        public boolean musica() { AudioManager a = am(); return a != null && a.isMusicActive(); }

        @JavascriptInterface public boolean puedeSuperponer() { return Settings.canDrawOverlays(app); }

        @JavascriptInterface
        public void pedirSuperposicion() {
            ui.post(() -> {
                try {
                    app.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) { Log.w(TAG, "superposicion", e); }
            });
        }
    }
}
