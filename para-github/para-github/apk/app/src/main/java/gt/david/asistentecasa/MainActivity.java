package gt.david.asistentecasa;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla de T-Bot. Solo muestra la página de la Estación; el trabajo lo hace Estacion,
 * que sigue viva aunque salgas de la app.
 */
public class MainActivity extends Activity {

    private static final String TAG = "AsistenteCasa";
    private static final int REQ_PERMISOS = 10;
    static final int REQ_OPCIONALES = 11;
    private boolean listo = false;
    private boolean apagando = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        pantallaCompleta();
        if (tienePermisos()) prepararTodo(); else pedirPermisos();
    }

    private void prepararTodo() {
        listo = true;
        try { startForegroundService(new Intent(this, GuardianService.class)); } catch (Exception e) { Log.w(TAG, "servicio", e); }
        Estacion.get(this).mostrarEn(this);
        pedirPermisosExtra();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (listo) Estacion.get(this).mostrarEn(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listo && !apagando) {
            Estacion e = Estacion.actual();
            if (e != null) e.pasarAFondo(this);
        }
    }

    // ---------- permisos ----------
    private boolean tienePermisos() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Permisos que no son obligatorios pero T-Bot usa: ubicación (rutas) y Bluetooth (buscar dispositivos). */
    static String[] opcionales() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return p.toArray(new String[0]);
    }

    static List<String> faltan(Activity a) {
        List<String> f = new ArrayList<>();
        for (String x : opcionales()) if (a.checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) f.add(x);
        return f;
    }

    /** Pide ubicación y Bluetooth. Si Android ya no deja preguntar, abre los ajustes de la app. */
    static void pedirOpcionales(Activity a) {
        List<String> f = faltan(a);
        if (f.isEmpty()) return;
        SharedPreferences sp = a.getSharedPreferences("tbot", Context.MODE_PRIVATE);
        boolean yaPregunto = sp.getBoolean("pidioOpcionales", false);
        boolean puedePreguntar = false;
        for (String x : f) if (a.shouldShowRequestPermissionRationale(x)) puedePreguntar = true;
        if (yaPregunto && !puedePreguntar) {
            // Android ya no muestra el aviso: hay que activarlo a mano
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        sp.edit().putBoolean("pidioOpcionales", true).apply();
        a.requestPermissions(f.toArray(new String[0]), REQ_OPCIONALES);
    }

    private void pedirPermisos() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.CAMERA);
        p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        for (String x : opcionales()) p.add(x);
        getSharedPreferences("tbot", Context.MODE_PRIVATE).edit().putBoolean("pidioOpcionales", true).apply();
        requestPermissions(p.toArray(new String[0]), REQ_PERMISOS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_OPCIONALES) {
            Estacion e = Estacion.actual();
            if (e != null) e.avisarPermisos();
            pedirPermisosExtra();
            return;
        }
        if (requestCode != REQ_PERMISOS) return;
        if (tienePermisos()) {
            prepararTodo();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Faltan permisos")
                    .setMessage("T-Bot necesita la cámara y el micrófono. Actívalos en Ajustes → Apps → Estación Casa → Permisos.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Reintentar", (d, w) -> pedirPermisos())
                    .show();
        }
    }

    /** Batería sin restricciones y "Mostrar sobre otras apps" (para trabajar en segundo plano). Se piden una vez. */
    private void pedirPermisosExtra() {
        SharedPreferences sp = getSharedPreferences("tbot", Context.MODE_PRIVATE);
        if (!faltan(this).isEmpty() && !sp.getBoolean("pidioOpcionales", false)) {
            pedirOpcionales(this);   // al contestar vuelve a entrar aquí
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !sp.getBoolean("pidioBateria", false)) {
                sp.edit().putBoolean("pidioBateria", true).apply();
                @SuppressLint("BatteryLife")
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Exception e) { Log.w(TAG, "bateria", e); }

        if (!Settings.canDrawOverlays(this) && !sp.getBoolean("pidioSuperponer", false)) {
            sp.edit().putBoolean("pidioSuperponer", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Un permiso más")
                    .setMessage("Para que T-Bot y la cámara sigan funcionando cuando uses otras apps, activa \"Mostrar sobre otras apps\" para Estación Casa.")
                    .setPositiveButton("Activar", (d, w) -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Después", null)
                    .show();
        }

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

    /** "Atrás": se puede dejar T-Bot trabajando en segundo plano o apagarlo. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("T-Bot")
                .setMessage("¿Dejarlo trabajando en segundo plano o apagarlo?")
                .setPositiveButton("Segundo plano", (d, w) -> moveTaskToBack(true))
                .setNegativeButton("Apagar", (d, w) -> {
                    apagando = true;
                    stopService(new Intent(this, GuardianService.class));
                    Estacion.apagar();
                    finish();
                })
                .setNeutralButton("Cancelar", null)
                .show();
    }
}
