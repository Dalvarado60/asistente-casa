package gt.david.asistentecasa;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.util.Locale;

/** La voz del asistente: texto a voz en español con el motor del celular (gratis y sin internet). */
public class Voz {

    public interface Aviso {
        void empezo();
        void termino();
    }

    private TextToSpeech tts;
    private final Aviso aviso;
    private final Context ctx;
    private volatile boolean lista = false;
    /** Volumen de la voz dentro del volumen del celular (0.1 a 1). */
    private volatile float volumen = 1f;
    /** Refuerzo por encima del máximo: 0 = apagado, 1..10 = +1.5 dB por punto. */
    private volatile int refuerzo = 0;
    private volatile String idArchivo = null;
    private MediaPlayer mp;
    private LoudnessEnhancer le;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public Voz(Context ctx, Aviso aviso) {
        this.aviso = aviso;
        this.ctx = ctx.getApplicationContext();
        tts = new TextToSpeech(ctx.getApplicationContext(), st -> {
            if (st != TextToSpeech.SUCCESS) return;
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            lista = true;
            aplicar();
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { if (!id.equals(idArchivo)) aviso.empezo(); }
            @Override public void onDone(String id) {
                if (id.equals(idArchivo)) ui.post(() -> reproducir(id));
                else aviso.termino();
            }
            @Override @Deprecated public void onError(String id) { if (id.equals(idArchivo)) idArchivo = null; aviso.termino(); }
            @Override public void onError(String id, int code) { if (id.equals(idArchivo)) idArchivo = null; aviso.termino(); }
            @Override public void onStop(String id, boolean interrupted) { if (id.equals(idArchivo)) idArchivo = null; aviso.termino(); }
        });
    }

    private volatile String idioma = "es";
    private volatile float tono = 0.95f, velocidad = 1.02f;

    /** idioma: "es" o "en". tono/velocidad: 1 = normal; tono bajo = voz más grave y robótica. */
    public void configurar(String idioma, float tono, float velocidad) {
        this.idioma = idioma; this.tono = tono; this.velocidad = velocidad;
        if (lista) aplicar();
    }

    private void aplicar() {
        Locale[] opciones = "en".equals(idioma)
                ? new Locale[]{ Locale.US, Locale.UK, Locale.ENGLISH }
                : new Locale[]{ new Locale("es", "MX"), new Locale("es", "US"), new Locale("es", "ES"), new Locale("es") };
        for (Locale l : opciones) {
            int r = tts.setLanguage(l);
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break;
        }
        try {
            Voice mejor = null;
            for (Voice v : tts.getVoices()) {
                if (!idioma.equals(v.getLocale().getLanguage())) continue;
                if (v.isNetworkConnectionRequired()) continue;
                if (mejor == null || v.getQuality() > mejor.getQuality()) mejor = v;
            }
            if (mejor != null) tts.setVoice(mejor);
        } catch (Exception ignored) { }
        tts.setPitch(tono);
        tts.setSpeechRate(velocidad);
    }

    public void setVolumen(float v) { volumen = Math.max(0.1f, Math.min(1f, v)); }
    public void setRefuerzo(int r) { refuerzo = Math.max(0, Math.min(10, r)); }

    public void hablar(String texto) {
        if (!lista || texto == null || texto.trim().isEmpty()) { aviso.termino(); return; }
        ui.post(() -> { if (detenerReproductor()) aviso.termino(); });
        Bundle b = new Bundle();
        b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumen);
        String id = "u" + System.nanoTime();
        if (refuerzo > 0) {
            // con refuerzo: se graba la frase y se reproduce con amplificador (más fuerte que el máximo)
            idArchivo = id;
            File f = new File(ctx.getCacheDir(), "voz.wav");
            if (tts.synthesizeToFile(texto, b, f, id) == TextToSpeech.SUCCESS) return;
            idArchivo = null;
        }
        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, b, id);
    }

    private void reproducir(String id) {
        if (!id.equals(idArchivo)) return;
        idArchivo = null;
        detenerReproductor();
        try {
            MediaPlayer m = new MediaPlayer();
            m.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            m.setDataSource(new File(ctx.getCacheDir(), "voz.wav").getAbsolutePath());
            m.prepare();
            try {
                le = new LoudnessEnhancer(m.getAudioSessionId());
                le.setTargetGain(refuerzo * 150);
                le.setEnabled(true);
            } catch (Exception ignored) { le = null; }
            m.setOnCompletionListener(x -> { detenerReproductor(); aviso.termino(); });
            m.setOnErrorListener((x, a, b) -> { detenerReproductor(); aviso.termino(); return true; });
            mp = m;
            m.start();
            aviso.empezo();
        } catch (Exception e) {
            detenerReproductor();
            aviso.termino();
        }
    }

    /** Devuelve true si había algo sonando. */
    private boolean detenerReproductor() {
        boolean habia = mp != null;
        try { if (le != null) le.release(); } catch (Exception ignored) { }
        le = null;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        return habia;
    }

    public void callar() {
        idArchivo = null;
        tts.stop();
        ui.post(() -> { if (detenerReproductor()) aviso.termino(); });
    }

    public void cerrar() { tts.stop(); tts.shutdown(); ui.post(this::detenerReproductor); }
}
