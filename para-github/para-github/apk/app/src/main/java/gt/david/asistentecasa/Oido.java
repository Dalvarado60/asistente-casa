package gt.david.asistentecasa;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * El "oído" del asistente. Un solo hilo lee el micrófono (16 kHz, mono) y hace tres cosas:
 *  1. Manda el nivel de sonido a la página (para detectar ruidos y animar el núcleo).
 *  2. Escucha la palabra de activación con Vosk (reconocimiento sin internet).
 *  3. Cuando se le pide, graba la orden hasta que la persona deja de hablar y la entrega como WAV.
 */
public class Oido {

    public interface Escucha {
        void nivel(float db);
        void despertar(String frase);
        void comandoListo(int ms);
        void sinComando();
        void estado(String s);
    }

    private static final String TAG = "AsistenteCasa";
    private static final int SR = 16000;
    private static final int DORMIDO = 0, COMANDO = 1, ESPERA = 2;

    private final Context ctx;
    private final Escucha cb;
    private Model model;
    private Recognizer rec;
    private final Object candado = new Object();
    private List<String> frases = new ArrayList<>();


    private Thread hilo;
    private volatile boolean corriendo = false;
    private volatile boolean ignorar = false;
    private volatile int modo = DORMIDO;
    private volatile byte[] ultimoWav = null;
    private volatile float piso = -60f;

    public Oido(Context ctx, Escucha cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    // ---------- modelo de voz ----------
    public void cargarModelo(String[] palabras) {
        setFrases(palabras);
        try { LibVosk.setLogLevel(LogLevel.WARNINGS); } catch (Throwable t) { /* opcional */ }
        StorageService.unpack(ctx, "model-es", "model",
                m -> { model = m; crearReconocedor(); cb.estado("oido-listo"); },
                e -> { Log.e(TAG, "modelo", e); cb.estado("oido-error: " + e.getMessage()); });
    }

    public void setFrases(String[] palabras) {
        List<String> l = new ArrayList<>();
        for (String p : palabras) {
            String n = normalizar(p);
            if (!n.isEmpty()) l.add(n);
        }
        frases = l;
        crearReconocedor();
    }

    private void crearReconocedor() {
        if (model == null) return;
        JSONArray g = new JSONArray();
        for (String f : frases) g.put(f);
        g.put("[unk]");
        synchronized (candado) {
            try {
                if (rec != null) rec.close();
                rec = new Recognizer(model, SR, g.toString());
            } catch (Exception e) {
                Log.e(TAG, "reconocedor", e);
                rec = null;
                cb.estado("oido-error: " + e.getMessage());
            }
        }
    }

    // ---------- control ----------
    public synchronized void iniciar() {
        if (corriendo) return;
        corriendo = true;
        hilo = new Thread(this::bucle, "oido");
        hilo.start();
    }

    public void detener() {
        Thread h;
        synchronized (this) {
            corriendo = false;
            h = hilo;
            hilo = null;
        }
        if (h != null) {
            try { h.join(1500); } catch (InterruptedException ignored) { }
        }
    }

    public boolean activo() { return corriendo; }

    public void setIgnorar(boolean si) { ignorar = si; }

    /** Empieza a grabar una orden (después del saludo). */
    public void escucharComando() { modo = COMANDO; }

    /** Vuelve a esperar la palabra de activación. */
    public void modoDespertar() {
        synchronized (candado) { if (rec != null) rec.reset(); }
        modo = DORMIDO;
    }

    public byte[] tomarWav() {
        byte[] w = ultimoWav;
        ultimoWav = null;
        return w;
    }

    public void cerrar() {
        detener();
        synchronized (candado) {
            if (rec != null) { rec.close(); rec = null; }
        }
        if (model != null) { model.close(); model = null; }
    }

    // ---------- bucle del micrófono ----------
    private void bucle() {
        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SR) * 2);
        } catch (Exception e) {
            cb.estado("mic-error: " + e.getMessage());
            corriendo = false;
            return;
        }
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            cb.estado("mic-error: no se pudo abrir el micrófono");
            ar.release();
            corriendo = false;
            return;
        }
        ar.startRecording();
        cb.estado("mic-on");

        short[] buf = new short[1600]; // 100 ms
        ByteArrayOutputStream orden = null;
        long inicioOrden = 0, ultimaVoz = 0;
        boolean huboVoz = false;

        while (corriendo) {
            int n = ar.read(buf, 0, buf.length);
            if (n <= 0) continue;

            double s = 0;
            for (int i = 0; i < n; i++) s += (double) buf[i] * buf[i];
            float db = (float) (20 * Math.log10(Math.sqrt(s / n) / 32768.0 + 1e-9));
            cb.nivel(db);

            if (ignorar) { orden = null; continue; }
            int m = modo;

            if (m == DORMIDO) {
                if (db < piso) piso = piso * 0.9f + db * 0.1f; else piso = piso * 0.998f + db * 0.002f;
                synchronized (candado) {
                    if (rec != null && rec.acceptWaveForm(buf, n)) {
                        String txt = leer(rec.getResult(), "text");
                        String hit = coincide(txt);
                        if (hit != null) {
                            rec.reset();
                            modo = ESPERA;
                            cb.despertar(hit);
                        }
                    }
                }
            } else if (m == COMANDO) {
                long ahora = System.currentTimeMillis();
                if (orden == null) {
                    orden = new ByteArrayOutputStream();
                    inicioOrden = ahora; ultimaVoz = ahora; huboVoz = false;
                }
                for (int i = 0; i < n; i++) { orden.write(buf[i] & 0xff); orden.write((buf[i] >> 8) & 0xff); }
                float umbral = Math.max(piso + 12f, -52f);
                if (db > umbral) { huboVoz = true; ultimaVoz = ahora; }
                long dur = ahora - inicioOrden;
                boolean fin = (huboVoz && ahora - ultimaVoz > 1300) || dur > 10000 || (!huboVoz && dur > 8000);
                if (fin) {
                    modo = ESPERA;
                    if (huboVoz) {
                        ultimoWav = wav(orden.toByteArray());
                        cb.comandoListo((int) dur);
                    } else {
                        cb.sinComando();
                    }
                    orden = null;
                }
            } else {
                orden = null;
            }
        }
        try { ar.stop(); } catch (Exception ignored) { }
        ar.release();
        cb.estado("mic-off");
    }

    private String coincide(String txt) {
        if (txt == null || txt.isEmpty()) return null;
        String t = normalizar(txt);
        for (String f : frases) {
            if (t.equals(f) || t.endsWith(" " + f) || t.startsWith(f + " ")) return f;
        }
        return null;
    }

    private static String leer(String json, String campo) {
        try { return new JSONObject(json).optString(campo, ""); } catch (Exception e) { return ""; }
    }

    static String normalizar(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).trim(), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replaceAll("[^a-zñ ]", "").replaceAll("\\s+", " ").trim();
    }

    private static byte[] wav(byte[] pcm) {
        int total = pcm.length + 36, byteRate = SR * 2;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        h[4] = (byte) total; h[5] = (byte) (total >> 8); h[6] = (byte) (total >> 16); h[7] = (byte) (total >> 24);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        h[16] = 16; h[20] = 1; h[22] = 1;
        h[24] = (byte) SR; h[25] = (byte) (SR >> 8); h[26] = (byte) (SR >> 16); h[27] = (byte) (SR >> 24);
        h[28] = (byte) byteRate; h[29] = (byte) (byteRate >> 8); h[30] = (byte) (byteRate >> 16); h[31] = (byte) (byteRate >> 24);
        h[32] = 2; h[34] = 16;
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        h[40] = (byte) pcm.length; h[41] = (byte) (pcm.length >> 8); h[42] = (byte) (pcm.length >> 16); h[43] = (byte) (pcm.length >> 24);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
}
