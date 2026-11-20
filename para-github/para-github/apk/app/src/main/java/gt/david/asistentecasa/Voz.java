package gt.david.asistentecasa;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Locale;

/** La voz del asistente: texto a voz en español con el motor del celular (gratis y sin internet). */
public class Voz {

    public interface Aviso {
        void empezo();
        void termino();
    }

    private TextToSpeech tts;
    private final Aviso aviso;
    private volatile boolean lista = false;

    public Voz(Context ctx, Aviso aviso) {
        this.aviso = aviso;
        tts = new TextToSpeech(ctx.getApplicationContext(), st -> {
            if (st != TextToSpeech.SUCCESS) return;
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            lista = true;
            aplicar();
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { aviso.empezo(); }
            @Override public void onDone(String id) { aviso.termino(); }
            @Override @Deprecated public void onError(String id) { aviso.termino(); }
            @Override public void onError(String id, int code) { aviso.termino(); }
            @Override public void onStop(String id, boolean interrupted) { aviso.termino(); }
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

    public void hablar(String texto) {
        if (!lista || texto == null || texto.trim().isEmpty()) { aviso.termino(); return; }
        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, new Bundle(), "u" + System.nanoTime());
    }

    public void callar() { tts.stop(); }

    public void cerrar() { tts.stop(); tts.shutdown(); }
}
