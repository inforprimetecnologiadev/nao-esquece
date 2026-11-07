package com.inforprime.naoesquece;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Ditado contínuo, sem a janela "Fale agora" do Google: o app continua ouvindo mesmo com silêncio
 * (quando o reconhecedor encerra um trecho, o próximo começa sozinho) até a pessoa tocar em Concluir.
 * Pausar guarda o que já foi dito; Continuar segue de onde parou.
 * Eventos para a página: estado (ouvindo, pausado, fim, erro), trecho parcial, trecho final e nível do som.
 */
final class Dictation {
    private static final String TAG = "NaoEsqueceDitado";

    interface Sink {
        void send(String type, String data);
    }

    /** O reconhecedor de voz (no teste, um falso). */
    interface Engine {
        void start(Intent i, RecognitionListener l);

        void stop();

        void cancel();

        void destroy();
    }

    static final int IDLE = 0, LISTENING = 1, PAUSING = 2, PAUSED = 3, FINISHING = 4, DONE = 5;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Engine engine;
    private final Sink sink;
    private final String lang, pkg;
    private int state = IDLE, gen = 0, fails = 0;
    private boolean offline = false;
    private String partial = "";
    private long lastLevel = 0, failWindow = 0;

    Dictation(Engine engine, Sink sink, String lang, String pkg) {
        this.engine = engine;
        this.sink = sink;
        this.lang = lang == null || lang.isEmpty() ? "pt-BR" : lang;
        this.pkg = pkg;
    }

    static boolean available(Context c) {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(c)) return true;
            return Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Reconhecedor de voz do celular (o padrão do sistema; sem ele, o que roda no aparelho). */
    static Engine android(Context c) {
        return new Engine() {
            SpeechRecognizer sr;

            @Override
            public void start(Intent i, RecognitionListener l) {
                if (sr == null) {
                    if (SpeechRecognizer.isRecognitionAvailable(c)) sr = SpeechRecognizer.createSpeechRecognizer(c);
                    else if (Build.VERSION.SDK_INT >= 31) sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
                    else throw new IllegalStateException("sem reconhecedor");
                }
                sr.setRecognitionListener(l);
                sr.startListening(i);
            }

            @Override
            public void stop() {
                if (sr != null) sr.stopListening();
            }

            @Override
            public void cancel() {
                if (sr != null) sr.cancel();
            }

            @Override
            public void destroy() {
                if (sr != null) {
                    try {
                        sr.destroy();
                    } catch (Exception ignored) {
                    }
                    sr = null;
                }
            }
        };
    }

    int state() {
        return state;
    }

    boolean active() {
        return state != IDLE && state != DONE;
    }

    void start() {
        if (state != IDLE) return;
        state = LISTENING;
        listen();
    }

    void pause() {
        if (state != LISTENING) return;
        state = PAUSING;
        engine.stop(); // o reconhecedor entrega o último trecho e para
        final int g = gen;
        h.postDelayed(() -> {
            if (state == PAUSING && g == gen) { // não respondeu: guarda o parcial e para assim mesmo
                commitPartial();
                engine.cancel();
                state = PAUSED;
                sink.send("estado", "pausado");
            }
        }, 1800);
    }

    void resume() {
        if (state != PAUSED) return;
        state = LISTENING;
        fails = 0;
        listen();
    }

    void finish() {
        if (state == PAUSED || state == IDLE) {
            end();
            return;
        }
        if (state != LISTENING && state != PAUSING) return;
        state = FINISHING;
        engine.stop();
        final int g = gen;
        h.postDelayed(() -> {
            if (state == FINISHING && g == gen) {
                commitPartial();
                end();
            }
        }, 2500);
    }

    void cancel() {
        state = DONE;
        gen++;
        h.removeCallbacksAndMessages(null);
        try {
            engine.cancel();
        } catch (Exception ignored) {
        }
        engine.destroy();
    }

    private void end() {
        state = DONE;
        gen++;
        h.removeCallbacksAndMessages(null);
        try {
            engine.cancel();
        } catch (Exception ignored) {
        }
        engine.destroy();
        sink.send("estado", "fim");
    }

    Intent intent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (pkg != null) i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, pkg);
        // pede para esperar mais o silêncio (nem todo celular respeita; o recomeço automático cobre o resto)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L);
        if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        // Android 13+: pontuação e letras maiúsculas automáticas
        if (Build.VERSION.SDK_INT >= 33) i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
        return i;
    }

    private void listen() {
        if (state != LISTENING) return;
        partial = "";
        final int g = ++gen;
        try {
            engine.start(intent(), listener(g));
            sink.send("estado", "ouvindo");
        } catch (Exception e) {
            Log.w(TAG, "start", e);
            retryLater(400, true);
        }
    }

    private void retryLater(long ms, boolean recreate) {
        if (state != LISTENING) return;
        long now = SystemClock.elapsedRealtime();
        if (fails == 0 || now - failWindow > 30_000) { fails = 0; failWindow = now; }
        if (++fails > 12) { // muitas falhas seguidas em 30 s
            state = PAUSED;
            sink.send("erro", "falhou");
            sink.send("estado", "pausado");
            return;
        }
        if (recreate) engine.destroy();
        final int g = gen;
        h.postDelayed(() -> {
            if (g == gen) listen();
        }, ms);
    }

    private static String best(Bundle b) {
        if (b == null) return "";
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() || l.get(0) == null ? "" : l.get(0).trim();
    }

    private void commit(String t) {
        partial = "";
        if (t != null && !t.trim().isEmpty()) sink.send("final", t.trim());
    }

    private void commitPartial() {
        String p = partial;
        partial = "";
        if (!p.isEmpty()) sink.send("final", p);
    }

    /** Trecho acabou (resultado ou silêncio): continua ouvindo, ou pausa, ou termina. */
    private void segmentDone() {
        if (state == LISTENING) {
            final int g = gen;
            h.postDelayed(() -> {
                if (g == gen) listen();
            }, 50);
        } else if (state == PAUSING) {
            state = PAUSED;
            sink.send("estado", "pausado");
        } else if (state == FINISHING) {
            end();
        }
    }

    RecognitionListener listener(final int g) {
        return new RecognitionListener() {
            private boolean mine() {
                return g == gen;
            }

            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
                if (mine()) fails = 0;
            }

            @Override
            public void onRmsChanged(float rms) {
                long t = SystemClock.uptimeMillis();
                if (!mine() || t - lastLevel < 70) return;
                lastLevel = t;
                sink.send("nivel", String.valueOf(Math.round(rms * 10) / 10f));
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                if (!mine()) return;
                Log.i(TAG, "erro " + error + " estado " + state);
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: // silêncio: não é erro, só recomeça
                        commitPartial();
                        fails = 0;
                        segmentDone();
                        return;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        state = PAUSED;
                        sink.send("erro", "permissao");
                        sink.send("estado", "pausado");
                        return;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_SERVER:
                    case 11: // ERROR_SERVER_DISCONNECTED
                        commitPartial();
                        if (state != LISTENING) { segmentDone(); return; }
                        if (!offline) { // sem internet: tenta o reconhecimento que roda no próprio celular
                            offline = true;
                            sink.send("aviso", "offline");
                            retryLater(200, true);
                        } else {
                            state = PAUSED;
                            sink.send("erro", "rede");
                            sink.send("estado", "pausado");
                        }
                        return;
                    case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
                    case 13: // ERROR_LANGUAGE_UNAVAILABLE
                        if (offline) {
                            offline = false;
                            retryLater(200, true);
                        } else {
                            state = PAUSED;
                            sink.send("erro", "idioma");
                            sink.send("estado", "pausado");
                        }
                        return;
                    default: // ocupado, cliente, áudio…: recria o reconhecedor e continua
                        commitPartial();
                        if (state == LISTENING) retryLater(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 500 : 250, true);
                        else segmentDone();
                }
            }

            @Override
            public void onResults(Bundle results) {
                if (!mine()) return;
                String t = best(results);
                commit(t.isEmpty() ? partial : t);
                segmentDone();
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (!mine()) return;
                String t = best(partialResults);
                if (t.isEmpty()) return;
                partial = t;
                sink.send("parcial", t);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        };
    }
}
