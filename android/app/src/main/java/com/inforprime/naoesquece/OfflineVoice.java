package com.inforprime.naoesquece;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;

/**
 * Português do ditado no próprio celular (sem internet).
 * Android 13+: confere se está instalado, sendo baixado/atualizado ou disponível para baixar, e baixa.
 * Android 12 ou mais antigo: o Android não deixa o app conferir; só abre as configurações de voz.
 * Tudo aqui roda na linha principal (exigência do SpeechRecognizer).
 */
final class OfflineVoice {
    private static final String TAG = "NaoEsqueceVoz";

    interface Cb {
        void done(JSONObject r);
    }

    interface Sink {
        void send(String type, String data);
    }

    static boolean has(List<String> l, String lang) {
        if (l == null) return false;
        String want = lang.replace('_', '-').toLowerCase();
        String base = want.split("-")[0];
        for (String x : l) {
            if (x == null) continue;
            String v = x.replace('_', '-').toLowerCase();
            if (v.equals(want) || v.equals(base)) return true;
        }
        return false;
    }

    private static Intent intent(String lang) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        return i;
    }

    static void check(Context c, String lang, Cb cb) {
        final JSONObject r = new JSONObject();
        final boolean[] sent = {false};
        final Handler h = new Handler(Looper.getMainLooper());
        try {
            r.put("api", Build.VERSION.SDK_INT);
            r.put("rec", Dictation.available(c));
            if (Build.VERSION.SDK_INT < 33) {
                r.put("st", "antigo");
                cb.done(r);
                return;
            }
            boolean od = SpeechRecognizer.isOnDeviceRecognitionAvailable(c);
            r.put("onDevice", od);
            if (!od) {
                r.put("st", "sem-servico");
                cb.done(r);
                return;
            }
            final SpeechRecognizer sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
            final Runnable fin = () -> {
                if (sent[0]) return;
                sent[0] = true;
                try {
                    sr.destroy();
                } catch (Exception ignored) {
                }
                cb.done(r);
            };
            h.postDelayed(() -> {
                if (sent[0]) return;
                try {
                    r.put("st", "erro");
                    r.put("err", "tempo");
                } catch (Exception ignored) {
                }
                fin.run();
            }, 9000);
            sr.checkRecognitionSupport(intent(lang), c.getMainExecutor(), new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport s) {
                    try {
                        String st = has(s.getInstalledOnDeviceLanguages(), lang) ? "instalado"
                                : has(s.getPendingOnDeviceLanguages(), lang) ? "baixando"
                                : has(s.getSupportedOnDeviceLanguages(), lang) ? "disponivel" : "sem-idioma";
                        r.put("st", st);
                        r.put("online", has(s.getOnlineLanguages(), lang));
                        r.put("instalados", String.join(",", s.getInstalledOnDeviceLanguages()));
                        r.put("baixando", String.join(",", s.getPendingOnDeviceLanguages()));
                    } catch (Exception e) {
                        Log.w(TAG, "resultado", e);
                    }
                    h.post(fin);
                }

                @Override
                public void onError(int error) {
                    try {
                        r.put("st", "erro");
                        r.put("err", error);
                    } catch (Exception ignored) {
                    }
                    h.post(fin);
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "conferir", e);
            try {
                r.put("st", "erro");
                r.put("err", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
            if (!sent[0]) {
                sent[0] = true;
                cb.done(r);
            }
        }
    }

    /** Baixa (ou atualiza) o português. Android 14: com a porcentagem. Android 13: o celular baixa e o app confere depois. */
    static void download(Context c, String lang, Sink sink) {
        if (Build.VERSION.SDK_INT < 33) {
            sink.send("erro", "antigo");
            return;
        }
        try {
            final SpeechRecognizer sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
            if (Build.VERSION.SDK_INT >= 34) {
                sr.triggerModelDownload(intent(lang), c.getMainExecutor(), new ModelDownloadListener() {
                    @Override
                    public void onProgress(int completedPercent) {
                        sink.send("progresso", String.valueOf(completedPercent));
                    }

                    @Override
                    public void onSuccess() {
                        sink.send("ok", "");
                        sr.destroy();
                    }

                    @Override
                    public void onScheduled() {
                        sink.send("agendado", "");
                        sr.destroy();
                    }

                    @Override
                    public void onError(int error) {
                        sink.send("erro", String.valueOf(error));
                        sr.destroy();
                    }
                });
            } else {
                sr.triggerModelDownload(intent(lang));
                sink.send("iniciado", "");
                new Handler(Looper.getMainLooper()).postDelayed(sr::destroy, 3000);
            }
        } catch (Throwable e) {
            Log.w(TAG, "baixar", e);
            sink.send("erro", "falhou");
        }
    }

    /** Configurações de voz do celular ou a Play Store nos "Serviços de fala do Google". */
    static boolean open(Context c, String what) {
        try {
            Intent i;
            if ("loja".equals(what)) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts"));
                if (i.resolveActivity(c.getPackageManager()) == null)
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts"));
            } else i = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
