package gt.david.asistentecasa;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pantalla de la estación. La página (assets/estacion.html) dibuja la interfaz, maneja Firebase,
 * la cámara, la IA y el video en vivo. Esta clase pone las partes nativas:
 * oído (palabra de activación + grabar órdenes), voz, Spotify, multimedia, batería y pantalla.
 */
public class MainActivity extends Activity {

    private static final String TAG = "AsistenteCasa";
    private static final int REQ_PERMISOS = 10;
    private static final String URL_ESTACION = "https://appassets.androidplatform.net/assets/estacion.html";

    private WebView web;
    private PermissionRequest pedidoPendiente;
    private Oido oido;
    private Voz voz;
    private AudioFocusRequest enfoque;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long ultimoNivel = 0;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        pantallaCompleta();

        final WebViewAssetLoader cargador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        setContentView(web);

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
                runOnUiThread(() -> {
                    if (tienePermisos()) {
                        request.grant(request.getResources());
                    } else {
                        pedidoPendiente = request;
                        pedirPermisos();
                    }
                });
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });

        voz = new Voz(this, new Voz.Aviso() {
            @Override public void empezo() { js("casaNativo.vozEmpezo()"); }
            @Override public void termino() {
                if (oido != null) oido.setIgnorar(false);
                js("casaNativo.vozTermino()");
            }
        });

        oido = new Oido(this, new Oido.Escucha() {
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

        if (tienePermisos()) iniciarTodo(); else pedirPermisos();
    }

    /** Ejecuta código en la página, desde cualquier hilo. */
    private void js(final String codigo) {
        ui.post(() -> {
            if (web != null) web.evaluateJavascript("window.casaNativo && (function(){try{" + codigo + "}catch(e){}})()", null);
        });
    }

    private void iniciarTodo() {
        iniciarServicio();
        pedirSinAhorroBateria();
        if (web.getUrl() == null) web.loadUrl(URL_ESTACION);
    }

    // ---------- permisos ----------
    private String[] permisosNecesarios() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.CAMERA);
        p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    private boolean tienePermisos() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void pedirPermisos() { requestPermissions(permisosNecesarios(), REQ_PERMISOS); }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISOS) return;
        if (tienePermisos()) {
            if (pedidoPendiente != null) { pedidoPendiente.grant(pedidoPendiente.getResources()); pedidoPendiente = null; }
            iniciarTodo();
        } else {
            if (pedidoPendiente != null) { pedidoPendiente.deny(); pedidoPendiente = null; }
            new AlertDialog.Builder(this)
                    .setTitle("Faltan permisos")
                    .setMessage("La estación necesita la cámara y el micrófono. Actívalos en Ajustes → Apps → Estación Casa → Permisos.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Reintentar", (d, w) -> pedirPermisos())
                    .show();
        }
    }

    // ---------- servicio, batería, pantalla ----------
    private void iniciarServicio() {
        Intent i = new Intent(this, GuardianService.class);
        try { startForegroundService(i); } catch (Exception e) { Log.w(TAG, "servicio", e); }
    }

    private void pedirSinAhorroBateria() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                @SuppressLint("BatteryLife")
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception e) { Log.w(TAG, "bateria", e); }
    }

    private void pantallaCompleta() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) pantallaCompleta();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("¿Apagar la estación?")
                .setMessage("Si la cierras, la casa deja de vigilar y el asistente deja de escuchar.")
                .setPositiveButton("Apagar", (d, w) -> { stopService(new Intent(this, GuardianService.class)); finish(); })
                .setNegativeButton("Seguir", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, GuardianService.class));
        if (oido != null) oido.cerrar();
        if (voz != null) voz.cerrar();
        soltarEnfoque();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ---------- audio ----------
    private AudioManager am() { return (AudioManager) getSystemService(Context.AUDIO_SERVICE); }

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

    private boolean puedeSuperponer() {
        return Settings.canDrawOverlays(this);
    }

    private void volverAlFrente() {
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { Log.w(TAG, "frente", e); }
    }

    // ---------- puente JavaScript ↔ Android ----------
    public class Puente {

        @JavascriptInterface public boolean nativo() { return true; }
        @JavascriptInterface public String version() { return "2.0"; }

        @JavascriptInterface
        public String bateria() {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
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
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = si ? 0.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public void altavoz(final boolean si) {
            runOnUiThread(() -> {
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

        // --- oído ---
        @JavascriptInterface
        public void oidoIniciar(String palabrasCsv) {
            String[] p = palabrasCsv.split(",");
            if (oido == null) return;
            oido.cargarModelo(p);
            oido.iniciar();
        }

        @JavascriptInterface
        public void palabras(String palabrasCsv) { if (oido != null) oido.setFrases(palabrasCsv.split(",")); }

        /** Pausa el micrófono nativo (para llamadas en vivo o modo privado). */
        @JavascriptInterface
        public void micPausa(boolean si) {
            if (oido == null) return;
            if (si) oido.detener(); else oido.iniciar();
        }

        @JavascriptInterface public boolean micActivo() { return oido != null && oido.activo(); }
        @JavascriptInterface public void escucharComando() { if (oido != null) oido.escucharComando(); }
        @JavascriptInterface public void modoDespertar() { if (oido != null) oido.modoDespertar(); }

        @JavascriptInterface
        public String tomarAudio() {
            byte[] w = oido == null ? null : oido.tomarWav();
            return w == null ? "" : Base64.encodeToString(w, Base64.NO_WRAP);
        }

        // --- voz ---
        @JavascriptInterface
        public void hablar(String texto) {
            if (oido != null) oido.setIgnorar(true);
            if (voz != null) voz.hablar(texto);
        }

        @JavascriptInterface
        public void vozConfig(String idioma, float tono, float velocidad) { if (voz != null) voz.configurar(idioma, tono, velocidad); }

        @JavascriptInterface public void callar() { if (voz != null) voz.callar(); }
        @JavascriptInterface public void enfoque(boolean si) { if (si) pedirEnfoque(); else soltarEnfoque(); }

        // --- música ---
        @JavascriptInterface
        public String spotify(final String busqueda) {
            Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
            i.setPackage("com.spotify.music");
            i.putExtra(SearchManager.QUERY, busqueda);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(busqueda))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e2) {
                    return "sin-spotify";
                }
            }
            // Si Spotify abrió pero no empezó a sonar, le damos "play".
            ui.postDelayed(() -> {
                AudioManager a = am();
                if (a != null && !a.isMusicActive()) teclaMultimedia(KeyEvent.KEYCODE_MEDIA_PLAY);
            }, 4500);
            // Regresamos a la pantalla del asistente; la música sigue sonando.
            ui.postDelayed(MainActivity.this::volverAlFrente, 7000);
            return puedeSuperponer() ? "ok" : "ok-sin-permiso";
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

        @JavascriptInterface public boolean puedeSuperponer() { return MainActivity.this.puedeSuperponer(); }

        @JavascriptInterface
        public void pedirSuperposicion() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) { Log.w(TAG, "superposicion", e); }
            });
        }
    }
}
