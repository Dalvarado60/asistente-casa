package gt.david.asistentecasa;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla principal: un WebView a pantalla completa que corre la página de la estación
 * (assets/estacion.html). La página hace el trabajo (Firebase, cámara, micrófono, IA, video);
 * esta clase solo da permisos, mantiene la pantalla viva y ofrece un "puente" a Android.
 */
public class MainActivity extends Activity {

    private static final String TAG = "AsistenteCasa";
    private static final int REQ_PERMISOS = 10;
    // Dirección https "falsa" para servir los assets: así la página es un contexto seguro
    // y el navegador interno permite cámara y micrófono.
    private static final String URL_ESTACION = "https://appassets.androidplatform.net/assets/estacion.html";

    private WebView web;
    private PermissionRequest pedidoPendiente;

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
        web.setBackgroundColor(0xFF0B0B14);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

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

        if (tienePermisos()) {
            iniciarTodo();
        } else {
            pedirPermisos();
        }
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

    private void pedirPermisos() {
        requestPermissions(permisosNecesarios(), REQ_PERMISOS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISOS) return;
        if (tienePermisos()) {
            if (pedidoPendiente != null) {
                pedidoPendiente.grant(pedidoPendiente.getResources());
                pedidoPendiente = null;
            }
            iniciarTodo();
        } else {
            if (pedidoPendiente != null) { pedidoPendiente.deny(); pedidoPendiente = null; }
            new AlertDialog.Builder(this)
                    .setTitle("Faltan permisos")
                    .setMessage("La estación necesita la cámara y el micrófono para funcionar. Actívalos en Ajustes → Apps → Estación Casa → Permisos.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("Reintentar", (d, w) -> pedirPermisos())
                    .show();
        }
    }

    // ---------- servicio y batería ----------
    private void iniciarServicio() {
        Intent i = new Intent(this, GuardianService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo iniciar el servicio", e);
        }
    }

    private void pedirSinAhorroBateria() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                @SuppressLint("BatteryLife")
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo pedir exclusión de batería", e);
        }
    }

    private void pantallaCompleta() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) pantallaCompleta();
    }

    // El botón "atrás" no cierra la estación por accidente.
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("¿Apagar la estación?")
                .setMessage("Si la cierras, la casa deja de vigilar.")
                .setPositiveButton("Apagar", (d, w) -> {
                    stopService(new Intent(this, GuardianService.class));
                    finish();
                })
                .setNegativeButton("Seguir vigilando", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, GuardianService.class));
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ---------- puente JavaScript ↔ Android ----------
    public class Puente {

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
                lp.screenBrightness = si ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            });
        }

        /** En llamada: el audio sale por la bocina (no por el auricular) para que la familia escuche. */
        @JavascriptInterface
        public void altavoz(final boolean si) {
            runOnUiThread(() -> {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;
                try {
                    if (si) {
                        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        if (Build.VERSION.SDK_INT >= 31) {
                            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { am.setCommunicationDevice(d); break; }
                            }
                        } else {
                            am.setSpeakerphoneOn(true);
                        }
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0);
                    } else {
                        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); else am.setSpeakerphoneOn(false);
                        am.setMode(AudioManager.MODE_NORMAL);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "altavoz", e);
                }
            });
        }

        @JavascriptInterface
        public String version() {
            return "1.0";
        }
    }
}
