package gt.david.asistentecasa;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Pide a Waze la ruta con el tráfico de este momento (el mismo cálculo que usa su mapa en vivo).
 * Devuelve {ok, km, min, minSinTrafico, puntos:[[lat,lon]...], fuente:"waze"} o {ok:false, error}.
 */
public final class Waze {

    private static final OkHttpClient C = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    private Waze() { }

    public static String ruta(double la1, double lo1, double la2, double lo2) {
        String ultimoError = "sin respuesta";
        for (String region : new String[]{"row-RoutingManager", "RoutingManager"}) {
            try {
                String desde = String.format(Locale.US, "x:%.6f y:%.6f", lo1, la1);
                String hasta = String.format(Locale.US, "x:%.6f y:%.6f", lo2, la2);
                String url = "https://www.waze.com/" + region + "/routingRequest?from=" + URLEncoder.encode(desde, "UTF-8")
                        + "&to=" + URLEncoder.encode(hasta, "UTF-8")
                        + "&at=0&returnJSON=true&returnGeometries=true&returnInstructions=true&timeout=60000&nPaths=1&options=AVOID_TRAILS%3At";
                Request r = new Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                        .header("Referer", "https://www.waze.com/live-map/")
                        .build();
                try (Response res = C.newCall(r).execute()) {
                    String txt = res.body() != null ? res.body().string() : "";
                    if (res.code() != 200 || !txt.startsWith("{")) { ultimoError = "HTTP " + res.code(); continue; }
                    JSONObject j = new JSONObject(txt);
                    JSONObject resp = j.optJSONObject("response");
                    JSONArray coords = j.optJSONArray("coords");
                    JSONArray alts = j.optJSONArray("alternatives");
                    if (resp == null && alts != null && alts.length() > 0) {
                        JSONObject a = alts.getJSONObject(0);
                        resp = a.optJSONObject("response");
                        if (coords == null) coords = a.optJSONArray("coords");
                    }
                    if (resp == null) { ultimoError = j.optString("error", "sin ruta"); continue; }
                    JSONArray results = resp.optJSONArray("results");
                    if (results == null || results.length() == 0) { ultimoError = "sin tramos"; continue; }
                    double metros = 0, seg = 0, segLibre = 0;
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject t = results.getJSONObject(i);
                        metros += t.optDouble("length", 0);
                        seg += t.optDouble("crossTime", 0);
                        segLibre += t.optDouble("crossTimeWithoutRealTime", t.optDouble("crossTime", 0));
                    }
                    JSONArray puntos = new JSONArray();
                    if (coords != null) {
                        int paso = Math.max(1, coords.length() / 400);
                        for (int i = 0; i < coords.length(); i += paso) {
                            JSONObject p = coords.getJSONObject(i);
                            puntos.put(new JSONArray().put(p.optDouble("y")).put(p.optDouble("x")));
                        }
                    }
                    return new JSONObject().put("ok", true).put("fuente", "waze")
                            .put("km", metros / 1000.0).put("min", seg / 60.0).put("minSinTrafico", segLibre / 60.0)
                            .put("ruta", resp.optString("routeName", "")).put("puntos", puntos).toString();
                }
            } catch (Exception e) {
                ultimoError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                Log.w("AsistenteCasa", "waze " + region, e);
            }
        }
        try { return new JSONObject().put("ok", false).put("error", ultimoError).toString(); }
        catch (Exception e) { return "{\"ok\":false}"; }
    }
}
