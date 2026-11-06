package com.inforprime.naoesquece;

import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
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

import java.io.FileInputStream;
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
        assertFalse(fgs());
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
