package gt.david.asistentecasa;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Actualiza T-Bot sin desinstalar: busca la última versión publicada en GitHub (Releases),
 * la descarga y le pide a Android que la instale encima. Contesta en casaNativo.actualizacion(json).
 */
public class Actualizador {

    public interface Aviso { void resultado(String json); }

    public static final String REPO = "Dalvarado60/asistente-casa";
    public static final String ACCION_INSTALAR = "gt.david.asistentecasa.INSTALAR";

    private final Context ctx;
    private final Aviso aviso;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
    private volatile boolean trabajando = false;

    public Actualizador(Context c, Aviso a) {
        ctx = c.getApplicationContext();
        aviso = a;
    }

    private void responder(String estado, String msg, int pct) {
        try {
            aviso.resultado(new JSONObject().put("estado", estado).put("msg", msg).put("pct", pct).toString());
        } catch (Exception ignored) { }
    }

    public static long versionCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** {"version":"7.37","codigo":137,"firma":"AB12CD34"} */
    @SuppressWarnings("deprecation")
    public static String info(Context c) {
        String nombre = "", firma = "";
        try {
            PackageManager pm = c.getPackageManager();
            nombre = pm.getPackageInfo(c.getPackageName(), 0).versionName;
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo p = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = p.signingInfo.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs != null && sigs.length > 0) {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) sb.append(String.format(Locale.ROOT, "%02X", d[i]));
                firma = sb.toString();
            }
        } catch (Exception ignored) { }
        try {
            return new JSONObject().put("version", nombre).put("codigo", versionCode(c)).put("firma", firma).toString();
        } catch (Exception e) { return "{}"; }
    }

    /** instalar=false solo revisa; instalar=true descarga e instala si hay una versión nueva. */
    public void revisar(boolean instalar) {
        if (trabajando) { responder("ocupado", "Ya estoy actualizando, espera tantito.", -1); return; }
        hilo.execute(() -> {
            trabajando = true;
            try { revisarAhora(instalar); }
            catch (Exception e) {
                Log.w("AsistenteCasa", "actualizar", e);
                responder("error", "No pude actualizar: " + e.getMessage(), -1);
            } finally { trabajando = false; }
        });
    }

    private void revisarAhora(boolean instalar) throws Exception {
        responder("buscando", "Buscando versión nueva…", -1);
        Request r = new Request.Builder().url("https://api.github.com/repos/" + REPO + "/releases/latest")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "T-Bot").build();
        JSONObject rel;
        try (Response res = http.newCall(r).execute()) {
            if (res.code() == 404) { responder("nada", "Todavía no hay versiones publicadas en GitHub. Compila una vez con el archivo de compilación nuevo.", -1); return; }
            if (res.code() != 200 || res.body() == null) { responder("error", "GitHub no contestó (" + res.code() + "). Prueba en un rato.", -1); return; }
            rel = new JSONObject(res.body().string());
        }
        long nueva = 0;
        String tag = rel.optString("tag_name", "");
        try { nueva = Long.parseLong(tag.replaceAll("[^0-9]", "")); } catch (Exception ignored) { }
        String url = null;
        long tam = 0;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) { url = a.optString("browser_download_url"); tam = a.optLong("size"); break; }
        }
        long actual = versionCode(ctx);
        if (url == null) { responder("error", "La versión publicada no trae la APK.", -1); return; }
        if (nueva <= actual) { responder("al-dia", "Ya tienes la versión más nueva.", -1); return; }
        if (!instalar) { responder("hay", "Hay una versión nueva lista para instalar.", -1); return; }

        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); } catch (Exception ignored) { }
            responder("permiso", "Activa \"Permitir de esta fuente\" para T-Bot y vuelve a tocar Actualizar.", -1);
            return;
        }

        responder("bajando", "Descargando la versión nueva…", 0);
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(ctx.getPackageName());
        if (tam > 0) sp.setSize(tam);
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try (Response res = http.newCall(new Request.Builder().url(url).header("User-Agent", "T-Bot").build()).execute()) {
            if (res.code() != 200 || res.body() == null) { s.abandon(); responder("error", "No se pudo descargar (" + res.code() + ").", -1); return; }
            long total = tam > 0 ? tam : res.body().contentLength();
            try (InputStream in = res.body().byteStream(); OutputStream out = s.openWrite("tbot.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[64 * 1024];
                long llevo = 0;
                int ultimo = 0, n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    llevo += n;
                    if (total > 0) {
                        int pct = (int) (llevo * 100 / total);
                        if (pct >= ultimo + 10) { ultimo = pct; responder("bajando", "Descargando… " + pct + "%", pct); }
                    }
                }
                s.fsync(out);
            }
        } catch (Exception e) {
            s.abandon();
            throw e;
        }
        Intent i = new Intent(ctx, InstalarReceptor.class).setAction(ACCION_INSTALAR);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent p = PendingIntent.getBroadcast(ctx, id, i, flags);
        responder("instalando", "Listo. Toca Instalar en el aviso de Android. T-Bot se reinicia solo.", 100);
        s.commit(p.getIntentSender());
        s.close();
    }
}
