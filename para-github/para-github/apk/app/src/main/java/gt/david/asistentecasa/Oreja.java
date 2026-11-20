package gt.david.asistentecasa;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Pasa la voz a texto con el reconocedor de Android (Google). Es rápido y va escribiendo
 * mientras la persona habla. Se usa después de la palabra de activación.
 */
public class Oreja {

    public interface Aviso {
        void parcial(String texto);
        void listo(String texto);
        void error(int codigo);
    }

    private final Context ctx;
    private Aviso aviso;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private volatile boolean escuchando = false;
    private int reintentos = 0;

    public Oreja(Context ctx, Aviso aviso) {
        this.ctx = ctx.getApplicationContext();
        this.aviso = aviso;
    }

    public boolean disponible() {
        try { return SpeechRecognizer.isRecognitionAvailable(ctx); } catch (Exception e) { return false; }
    }

    public boolean escuchando() { return escuchando; }

    public void escuchar() {
        ui.post(() -> {
            try {
                if (sr == null) {
                    sr = SpeechRecognizer.createSpeechRecognizer(ctx);
                    sr.setRecognitionListener(oyente);
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L);
                escuchando = true;
                silenciarPitido(true);
                sr.startListening(i);
                ui.postDelayed(() -> silenciarPitido(false), 900);
            } catch (Exception e) {
                escuchando = false;
                aviso.error(-1);
            }
        });
    }

    /** El reconocedor de Google hace "bip" cada vez que empieza; en modo conversación eso molesta. */
    private void silenciarPitido(boolean si) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int dir = si ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
        } catch (Exception ignored) { }
    }

    public void cancelar() {
        ui.post(() -> {
            escuchando = false;
            try { if (sr != null) sr.cancel(); } catch (Exception ignored) { }
        });
    }

    public void cerrar() {
        ui.post(() -> {
            escuchando = false;
            try { if (sr != null) { sr.destroy(); sr = null; } } catch (Exception ignored) { }
        });
    }

    private final RecognitionListener oyente = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onError(int error) {
            if (!escuchando) return;
            // 8 = ocupado: se reinicia el reconocedor y se vuelve a intentar una vez
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && reintentos < 2) {
                reintentos++;
                try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { }
                sr = null;
                escuchando = false;
                ui.postDelayed(Oreja.this::escuchar, 600);
                return;
            }
            reintentos = 0;
            escuchando = false;
            aviso.error(error);
        }

        @Override public void onResults(Bundle results) {
            reintentos = 0;
            if (!escuchando) return;
            escuchando = false;
            aviso.listo(primero(results));
        }

        @Override public void onPartialResults(Bundle partial) {
            String t = primero(partial);
            if (!t.isEmpty()) aviso.parcial(t);
        }
    };

    private static String primero(Bundle b) {
        if (b == null) return "";
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r != null && !r.isEmpty() && r.get(0) != null) ? r.get(0) : "";
    }
}
