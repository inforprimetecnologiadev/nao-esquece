package com.inforprime.naoesquece;

import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import java.io.ByteArrayOutputStream;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.os.ParcelFileDescriptor;
import android.provider.CalendarContract;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Roda num Android de verdade (emulador): o alarme toca, o aviso chega, o calendário recebe o evento. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AlarmTest {
    private static final String TAG = "NaoEsqueceTeste";
    private Context c;
    private UiAutomation ui;
    private NotificationManager nm;

    @Before
    public void setUp() throws Exception {
        c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        nm = c.getSystemService(NotificationManager.class);
        String p = c.getPackageName();
        sh("pm grant " + p + " android.permission.POST_NOTIFICATIONS");
        sh("pm grant " + p + " android.permission.READ_CALENDAR");
        sh("pm grant " + p + " android.permission.WRITE_CALENDAR");
        sh("pm grant " + p + " android.permission.RECORD_AUDIO");
        sh("input keyevent KEYCODE_WAKEUP");
        sh("wm dismiss-keyguard");
        sh("mkdir -p /sdcard/Download/ne");
    }

    private String sh(String cmd) {
        try {
            ParcelFileDescriptor pfd = ui.executeShellCommand(cmd);
            StringBuilder b = new StringBuilder();
            try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.append(new String(buf, 0, n));
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void shot(String name) {
        sh("screencap -p /sdcard/Download/ne/" + name + ".png");
        Log.i(TAG, "print " + name);
    }

    private boolean notif(String tag) {
        for (StatusBarNotification s : nm.getActiveNotifications()) if (tag.equals(s.getTag())) return true;
        return false;
    }

    private boolean fgs() {
        for (StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() == 4242) return true;
        return false;
    }

    private static boolean waitFor(Callable<Boolean> f, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (f.call()) return true;
            Thread.sleep(250);
        }
        return f.call();
    }

    private static String local(long t) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(new Date(t));
    }

    @Test
    public void a_permissoes() throws Exception {
        JSONObject st = Reminders.state(c);
        Log.i(TAG, "estado " + st);
        assertTrue("notificações", st.optBoolean("notif"));
        assertTrue("alarme exato", st.optBoolean("exact"));
        assertTrue("tela cheia", st.optBoolean("full"));
        assertTrue("calendário", st.optBoolean("cal"));
    }

    @Test
    public void b_botaoTestar() throws Exception {
        Reminders.test(c, "ambos");
        assertTrue("aviso do teste", waitFor(() -> notif("aviso:teste"), 5000));
        sh("cmd statusbar expand-notifications");
        Thread.sleep(1500);
        shot("1-aviso-teste");
        sh("cmd statusbar collapse");
        sh("input keyevent KEYCODE_SLEEP"); // tela apagada e bloqueada
        assertTrue("alarme tocando", waitFor(AlarmService::isRinging, 45000));
        assertTrue("notificação do alarme", waitFor(this::fgs, 5000));
        Thread.sleep(4000);
        shot("2-alarme-tela-bloqueada");
        Log.i(TAG, "atividade em cima: " + sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' "));
        Reminders.act(c, "stop", AlarmService.current());
        assertTrue("alarme parou", waitFor(() -> !AlarmService.isRinging(), 8000));
        assertTrue("notificação do alarme sumiu", waitFor(() -> !fgs(), 8000));
        sh("input keyevent KEYCODE_WAKEUP");
        sh("wm dismiss-keyguard");
    }

    @Test
    public void c_lembreteDoApp() throws Exception {
        // atividade daqui a ~3 min, alarme 1 min antes dela: aviso em ~1 min, alarme em ~2 min
        long at = ((System.currentTimeMillis() + 200_000L) / 60_000L) * 60_000L;
        JSONArray l = new JSONArray();
        JSONObject a = new JSONObject();
        a.put("id", "t1");
        a.put("at", local(at));
        a.put("before", 1);
        a.put("title", "Reunião com o gerente");
        a.put("text", "Levar o relatório impresso");
        a.put("mode", "som");
        l.put(a);
        assertEquals(1, Reminders.sync(c, l.toString()));
        Log.i(TAG, "alarmes do sistema: " + sh("dumpsys alarm | grep -B1 -A3 naoesquece | head -40"));
        assertTrue("aviso 1 min antes", waitFor(() -> notif("aviso:t1"), 150_000));
        long pre = System.currentTimeMillis();
        sh("cmd statusbar expand-notifications");
        Thread.sleep(1500);
        shot("3-aviso-1-minuto-antes");
        sh("cmd statusbar collapse");
        assertTrue("alarme do lembrete", waitFor(AlarmService::isRinging, 90_000));
        long gap = System.currentTimeMillis() - pre;
        Log.i(TAG, "aviso → alarme: " + gap + " ms");
        assertTrue("1 minuto entre aviso e alarme (" + gap + ")", gap > 45_000 && gap < 75_000);
        assertEquals("t1", AlarmService.current().optString("id"));
        Thread.sleep(2500);
        shot("4-alarme-com-tela-ligada");
        Reminders.act(c, "done", AlarmService.current());
        assertTrue(waitFor(() -> !AlarmService.isRinging(), 8000));
        JSONArray ev = new JSONArray(Reminders.takeEvents(c));
        assertEquals(1, ev.length());
        assertEquals("t1", ev.getJSONObject(0).optString("id"));
        assertEquals("done", ev.getJSONObject(0).optString("act"));
        // a lista de novo não toca de novo o mesmo alarme
        assertEquals(0, Reminders.sync(c, l.toString()));
    }

    @Test
    public void d_reiniciar() throws Exception {
        long at = System.currentTimeMillis() + 3_600_000L;
        JSONArray l = new JSONArray();
        JSONObject a = new JSONObject();
        a.put("id", "t2");
        a.put("at", local(at));
        a.put("before", 0);
        a.put("title", "Depois de reiniciar");
        l.put(a);
        assertEquals(1, Reminders.sync(c, l.toString()));
        Reminders.rescheduleAll(c, true);
        String d = sh("dumpsys alarm");
        assertTrue("alarme marcado no Android", d.contains("naoesquece"));
        assertEquals(1, Reminders.pending(c));
        Reminders.sync(c, "[]");
        assertEquals(0, Reminders.pending(c));
    }

    private static JSONObject rep(String id, String at, String repeat) throws Exception {
        JSONObject a = new JSONObject();
        a.put("id", id);
        a.put("at", at);
        a.put("before", 0);
        a.put("repeat", repeat);
        a.put("title", "Repete " + repeat);
        return a;
    }

    @Test
    public void f_repeticao() throws Exception {
        // 06/11/2026 é sexta-feira
        assertEquals(local(Reminders.parseLocal("2026-11-07T08:00")), local(Reminders.occAfter(rep("r", "2026-11-06T08:00", "dia"), Reminders.parseLocal("2026-11-06T09:00"))));
        assertEquals("dias úteis pulam o fim de semana", local(Reminders.parseLocal("2026-11-09T08:00")), local(Reminders.occAfter(rep("r", "2026-11-06T08:00", "uteis"), Reminders.parseLocal("2026-11-06T09:00"))));
        assertEquals(local(Reminders.parseLocal("2026-11-09T08:00")), local(Reminders.occAfter(rep("r", "2026-11-02T08:00", "semana"), Reminders.parseLocal("2026-11-02T09:00"))));
        assertEquals("31 vira o último dia de fevereiro", local(Reminders.parseLocal("2026-02-28T08:00")), local(Reminders.occAfter(rep("r", "2026-01-31T08:00", "mes"), Reminders.parseLocal("2026-01-31T09:00"))));
        assertEquals(local(Reminders.parseLocal("2026-03-31T08:00")), local(Reminders.occAfter(rep("r", "2026-01-31T08:00", "mes"), Reminders.parseLocal("2026-02-28T09:00"))));
        // lembrete antigo que repete todo dia: o próximo alarme é nas próximas 24 h
        long now = System.currentTimeMillis();
        long r = Reminders.nextRing(rep("r", "2025-01-01T08:00", "dia"), now);
        assertTrue(r > now && r - now <= 86_400_000L);
    }

    @Test
    public void g_repeteTocaEMarcaOProximo() throws Exception {
        // começou ontem; a ocorrência de hoje toca em ~2 min
        long today = ((System.currentTimeMillis() + 150_000L) / 60_000L) * 60_000L;
        JSONArray l = new JSONArray().put(rep("r2", local(today - 86_400_000L), "dia"));
        assertEquals(1, Reminders.sync(c, l.toString()));
        List<JSONObject> hoje = Reminders.today(c);
        assertEquals("aparece no widget de hoje", 1, hoje.size());
        TodayWidget.refresh(c);
        assertTrue("alarme do dia", waitFor(AlarmService::isRinging, 200_000));
        Thread.sleep(2000);
        shot("5-alarme-que-repete");
        Reminders.act(c, "stop", AlarmService.current());
        assertTrue(waitFor(() -> !AlarmService.isRinging(), 8000));
        assertEquals("o de amanhã já ficou marcado", 1, Reminders.pending(c));
        Log.i(TAG, "depois de tocar: " + sh("dumpsys alarm | grep -A3 naoesquece | head -20"));
        Reminders.sync(c, "[]");
    }

    @Test
    public void h_widget() throws Exception {
        // as duas horas ficam sempre dentro de hoje, a qualquer hora que o teste rodar
        java.util.Calendar d0 = java.util.Calendar.getInstance();
        d0.set(java.util.Calendar.HOUR_OF_DAY, 0);
        d0.set(java.util.Calendar.MINUTE, 0);
        d0.set(java.util.Calendar.SECOND, 0);
        d0.set(java.util.Calendar.MILLISECOND, 0);
        long day = d0.getTimeInMillis(), end = day + 86_400_000L;
        if (System.currentTimeMillis() > end - 10 * 60_000L) { // perto da meia-noite: espera o dia virar
            Thread.sleep(end + 60_000L - System.currentTimeMillis());
            day = end;
            end += 86_400_000L;
        }
        long t = Math.min(System.currentTimeMillis() + 3_600_000L, end - 3 * 60_000L);
        JSONObject a = new JSONObject();
        a.put("id", "w1");
        a.put("at", local(t));
        a.put("before", 0);
        a.put("title", "Reunião com o gerente");
        JSONObject b = rep("w2", local(day + 60_000L - 86_400_000L), "dia");
        b.put("title", "Bater o ponto");
        Reminders.sync(c, new JSONArray().put(a).put(b).toString());
        File f = new File(c.getExternalFilesDir(null), "6-widget.png");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                RemoteViews rv = TodayWidget.build(c);
                View v = rv.apply(c, new FrameLayout(c));
                float d = c.getResources().getDisplayMetrics().density;
                int w = (int) (340 * d), h = (int) (190 * d);
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                v.layout(0, 0, w, h);
                Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bm);
                cv.drawColor(0xFF3A4A66);
                v.draw(cv);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    bm.compress(Bitmap.CompressFormat.PNG, 100, o);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(f.length() > 0);
        sh("cp " + f.getAbsolutePath() + " /sdcard/Download/ne/6-widget.png");
        assertEquals(2, Reminders.today(c).size());
        TodayWidget.refresh(c);
        Reminders.sync(c, "[]");
    }

    private static Bitmap textBitmap(String a, String b) {
        Bitmap bm = Bitmap.createBitmap(1400, 600, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        cv.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(90);
        cv.drawText(a, 60, 220, p);
        cv.drawText(b, 60, 400, p);
        return bm;
    }

    @Test
    public void i_lerTexto() throws Exception {
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        textBitmap("NOTA FISCAL 4521", "Entregar na sala 2").compress(Bitmap.CompressFormat.JPEG, 92, jpg);
        String t = Ocr.run(c, "image/jpeg", jpg.toByteArray());
        Log.i(TAG, "texto da foto: " + t);
        assertTrue("foto: " + t, t.toUpperCase(Locale.ROOT).contains("NOTA FISCAL") && t.contains("4521"));
        // PDF de 2 páginas
        PdfDocument doc = new PdfDocument();
        for (int i = 1; i <= 2; i++) {
            PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(595, 842, i).create());
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(36);
            pg.getCanvas().drawColor(Color.WHITE);
            pg.getCanvas().drawText("CONTRATO PAGINA " + i, 60, 140, p);
            doc.finishPage(pg);
        }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        doc.writeTo(pdf);
        doc.close();
        String tp = Ocr.run(c, "application/pdf", pdf.toByteArray());
        Log.i(TAG, "texto do PDF: " + tp);
        assertTrue("pdf: " + tp, tp.toUpperCase(Locale.ROOT).contains("CONTRATO") && tp.contains("2"));
    }

    @Test
    public void j_rapidos() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", "q1");
        o.put("type", "nota");
        o.put("text", "Comprar toner");
        Quick.add(c, o);
        assertTrue(Quick.list(c).contains("Comprar toner"));
        Quick.remove(c, "q1");
        assertEquals(0, Quick.count(c));
        // a janela rápida abre por cima de tudo
        c.startActivity(QuickActivity.intent(c, "nota").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(2500);
        shot("7-rapido-nota");
        sh("input text Comprar%stoner");
        Thread.sleep(700);
        // com a janela de nota ainda aberta, toca o botão de áudio: guarda a nota e troca para gravar
        c.startActivity(QuickActivity.intent(c, "audio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(3500);
        shot("7-rapido-audio");
        sh("input keyevent KEYCODE_BACK");
        Thread.sleep(1500);
        String fila = Quick.list(c);
        Log.i(TAG, "fila rápida: " + fila);
        assertTrue("nota rápida guardada", fila.contains("Comprar toner"));
        assertTrue("áudio rápido guardado", fila.contains("\"audio\""));
        for (int k = 0; k < 5 && Quick.count(c) > 0; k++) {
            JSONObject x = new org.json.JSONArray(Quick.list(c)).getJSONObject(0);
            if ("audio".equals(x.optString("type"))) assertTrue(Quick.data(c, x.optString("id")).length() > 100);
            Quick.remove(c, x.optString("id"));
        }
    }

    @Test
    public void k_mp3() throws Exception {
        assertTrue("biblioteca do MP3 carregada", Mp3.available());
        Log.i(TAG, "LAME " + Mp3.lameVersion());
        Context tc = InstrumentationRegistry.getInstrumentation().getContext();
        File dir = new File(c.getCacheDir(), "teste-mp3");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // gravação do Chrome/WebView (WebM/Opus, sem duração no cabeçalho), M4A/AAC (nota rápida) e WAV estéreo
        for (String name : new String[]{"teste.webm", "teste.m4a", "teste.wav"}) {
            File in = new File(dir, name), out = new File(dir, name + ".mp3");
            try (java.io.InputStream is = tc.getAssets().open(name); FileOutputStream os = new FileOutputStream(in)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = is.read(b)) > 0) os.write(b, 0, n);
            }
            long t0 = System.currentTimeMillis();
            double dur = Mp3.convert(in, out);
            Log.i(TAG, "mp3 " + name + ": " + out.length() + " bytes, " + dur + " s, " + (System.currentTimeMillis() - t0) + " ms");
            assertTrue(name + ": duração " + dur, dur > 2.4 && dur < 3.8);
            assertTrue(name + ": tamanho " + out.length(), out.length() > 12000 && out.length() < 60000);
            byte[] h = new byte[4];
            try (FileInputStream fi = new FileInputStream(out)) {
                assertEquals(4, fi.read(h));
            }
            assertTrue(name + ": começa com quadro MP3", (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0);
            // o próprio Android lê o MP3 gerado
            android.media.MediaExtractor ex = new android.media.MediaExtractor();
            ex.setDataSource(out.getPath());
            android.media.MediaFormat f = ex.getTrackFormat(0);
            assertEquals("audio/mpeg", f.getString(android.media.MediaFormat.KEY_MIME));
            double d2 = f.containsKey(android.media.MediaFormat.KEY_DURATION) ? f.getLong(android.media.MediaFormat.KEY_DURATION) / 1e6 : -1;
            Log.i(TAG, "mp3 " + name + " lido pelo Android: " + d2 + " s, " + f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) + " Hz");
            assertTrue(name + ": Android lê " + d2 + " s", d2 > 2.4 && d2 < 3.9);
            ex.release();
            if (name.equals("teste.webm")) { // guarda uma cópia para conferir fora do emulador
                File ext = c.getExternalFilesDir(null);
                if (ext != null) {
                    File keep = new File(ext, "gravacao-convertida.mp3");
                    try (FileInputStream fi = new FileInputStream(out); FileOutputStream fo = new FileOutputStream(keep)) {
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = fi.read(b)) > 0) fo.write(b, 0, n);
                    }
                    sh("cp " + keep.getPath() + " /sdcard/Download/ne/gravacao-convertida.mp3");
                }
            }
        }
    }

    private static android.os.Bundle res(String t) {
        android.os.Bundle b = new android.os.Bundle();
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        l.add(t);
        b.putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION, l);
        return b;
    }

    private static void ui(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    @Test
    public void l_ditado() throws Exception {
        Log.i(TAG, "reconhecedor de voz no emulador: " + Dictation.available(c));
        final List<String> ev = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final int[] n = {0, 0, 0}; // começou, parou, cancelou
        final android.speech.RecognitionListener[] L = {null};
        final Intent[] last = {null};
        Dictation.Engine fake = new Dictation.Engine() {
            @Override public void start(Intent i, android.speech.RecognitionListener l) { n[0]++; L[0] = l; last[0] = i; }
            @Override public void stop() { n[1]++; }
            @Override public void cancel() { n[2]++; }
            @Override public void destroy() { }
        };
        final Dictation[] d = {null};
        ui(() -> { d[0] = new Dictation(fake, (t, x) -> ev.add(t + ":" + x), "pt-BR", c.getPackageName()); d[0].start(); });
        assertEquals(1, n[0]);
        assertTrue(ev.contains("estado:ouvindo"));
        assertTrue("pede pontuação automática", android.os.Build.VERSION.SDK_INT < 33 || last[0].hasExtra(android.speech.RecognizerIntent.EXTRA_ENABLE_FORMATTING));
        // fala um trecho
        ui(() -> L[0].onPartialResults(res("comprar pão")));
        ui(() -> L[0].onResults(res("comprar pão e leite")));
        Thread.sleep(300);
        assertTrue(ev.contains("parcial:comprar pão"));
        assertTrue(ev.contains("final:comprar pão e leite"));
        assertEquals("continua ouvindo sozinho depois do trecho", 2, n[0]);
        // fica em silêncio pensando: o reconhecedor encerra, o app recomeça sem avisar erro
        ui(() -> L[0].onError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT));
        Thread.sleep(300);
        ui(() -> L[0].onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH));
        Thread.sleep(300);
        assertEquals(4, n[0]);
        assertFalse(ev.toString().contains("erro:"));
        assertEquals(Dictation.LISTENING, d[0].state());
        // pausa no meio de uma frase: guarda o que estava sendo dito e não volta a ouvir
        ui(() -> L[0].onPartialResults(res("ligar para")));
        ui(() -> d[0].pause());
        ui(() -> L[0].onResults(res("ligar para o cliente")));
        Thread.sleep(300);
        assertEquals(Dictation.PAUSED, d[0].state());
        assertEquals(4, n[0]);
        assertTrue(ev.contains("final:ligar para o cliente"));
        assertTrue(ev.contains("estado:pausado"));
        // continua de onde parou
        ui(() -> d[0].resume());
        assertEquals(5, n[0]);
        // sem internet: tenta o reconhecimento do próprio celular
        ui(() -> L[0].onError(android.speech.SpeechRecognizer.ERROR_NETWORK));
        Thread.sleep(500);
        assertEquals(6, n[0]);
        assertTrue(last[0].getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, false));
        assertTrue(ev.contains("aviso:offline"));
        // reconhecedor ocupado: recria e continua
        ui(() -> L[0].onError(android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY));
        Thread.sleep(900);
        assertEquals(7, n[0]);
        // resposta atrasada de um trecho antigo é ignorada
        ui(() -> d[0].listener(1).onResults(res("trecho velho")));
        assertFalse(ev.contains("final:trecho velho"));
        // concluir: espera o último trecho e termina
        ui(() -> L[0].onPartialResults(res("até amanhã")));
        ui(() -> d[0].finish());
        ui(() -> L[0].onResults(res("até amanhã cedo")));
        Thread.sleep(300);
        assertTrue(ev.contains("final:até amanhã cedo"));
        assertEquals("estado:fim", ev.get(ev.size() - 1));
        assertEquals(Dictation.DONE, d[0].state());
        assertEquals(7, n[0]);
        // concluir sem resposta do reconhecedor: guarda o parcial e termina mesmo assim
        ev.clear();
        ui(() -> { d[0] = new Dictation(fake, (t, x) -> ev.add(t + ":" + x), "pt-BR", c.getPackageName()); d[0].start(); });
        ui(() -> L[0].onPartialResults(res("texto solto")));
        ui(() -> d[0].finish());
        Thread.sleep(3000);
        assertTrue(ev.toString(), ev.contains("final:texto solto"));
        assertEquals("estado:fim", ev.get(ev.size() - 1));
        // cancelar não manda nada
        ev.clear();
        ui(() -> { d[0] = new Dictation(fake, (t, x) -> ev.add(t + ":" + x), "pt-BR", c.getPackageName()); d[0].start(); });
        ui(() -> d[0].cancel());
        ui(() -> L[0].onResults(res("não pode aparecer")));
        Thread.sleep(200);
        assertFalse(ev.toString().contains("não pode aparecer"));
        Log.i(TAG, "ditado: ok");
    }

    @Test
    public void m_ditadoReal() throws Exception {
        if (!Dictation.available(c)) { Log.i(TAG, "sem reconhecedor real no emulador"); return; }
        final List<String> ev = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final Dictation[] d = {null};
        ui(() -> { d[0] = new Dictation(Dictation.android(c), (t, x) -> ev.add(t + ":" + x), "pt-BR", c.getPackageName()); d[0].start(); });
        Thread.sleep(15000); // silêncio (o emulador não tem microfone): tem que continuar ouvindo, sem travar
        Log.i(TAG, "ditado real, 15 s de silêncio: " + ev);
        int ouvindo = 0;
        for (String e : ev) if (e.equals("estado:ouvindo")) ouvindo++;
        assertTrue("começou a ouvir", ouvindo >= 1);
        assertTrue("continua ativo depois do silêncio: " + d[0].state(), d[0].state() == Dictation.LISTENING || ev.contains("erro:falhou"));
        ui(() -> d[0].finish());
        assertTrue("concluir termina", waitFor(() -> ev.contains("estado:fim"), 6000));
        ui(() -> d[0].cancel());
    }

    private int events(ContentResolver cr, String id, String[] title) {
        int n = 0;
        try (Cursor q = cr.query(CalendarContract.Events.CONTENT_URI, new String[]{CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION},
                CalendarContract.Events.DESCRIPTION + " LIKE ? AND " + CalendarContract.Events.DELETED + "=0", new String[]{"%ne-ref:" + id + "%"}, null)) {
            while (q != null && q.moveToNext()) {
                n++;
                if (title != null) title[0] = q.getString(0);
            }
        }
        return n;
    }

    @Test
    public void e_calendario() throws Exception {
        ContentResolver cr = c.getContentResolver();
        long at = System.currentTimeMillis() + 86_400_000L;
        JSONObject in = new JSONObject();
        JSONArray up = new JSONArray();
        JSONObject n = new JSONObject();
        n.put("id", "t3");
        n.put("at", local(at));
        n.put("title", "Entregar o relatório");
        n.put("text", "Sala 2");
        up.put(n);
        in.put("up", up);
        in.put("del", new JSONArray());
        JSONObject r = DeviceCalendar.sync(c, "maria@example.com", in.toString());
        Log.i(TAG, "calendário: " + r);
        assertTrue(r.optBoolean("ok"));
        assertEquals(1, events(cr, "t3", null));
        n.put("title", "Entregar o relatório final");
        r = DeviceCalendar.sync(c, "maria@example.com", in.toString());
        String[] t = new String[1];
        assertEquals("sem duplicar", 1, events(cr, "t3", t));
        assertEquals("Entregar o relatório final", t[0]);
        JSONObject info = DeviceCalendar.info(c, "maria@example.com");
        assertTrue(info.optBoolean("found"));
        JSONObject del = new JSONObject();
        del.put("up", new JSONArray());
        del.put("del", new JSONArray().put("t3"));
        DeviceCalendar.sync(c, "maria@example.com", del.toString());
        assertEquals(0, events(cr, "t3", null));
        assertNotNull(r);
    }
}
