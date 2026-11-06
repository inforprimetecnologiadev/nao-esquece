package com.inforprime.naoesquece;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Alarmes dos lembretes. A lista fica guardada no celular e cada alarme é marcado no relógio do Android
 * (AlarmManager.setAlarmClock): toca com o app fechado, com a tela bloqueada e sem internet.
 * Um minuto antes chega um aviso com som, vibração ou os dois.
 */
final class Reminders {
    private static final String TAG = "NaoEsquece";
    private static final String PREFS = "nao_esquece_alarmes";
    private static final String K_LIST = "lista", K_SNOOZE = "adiados", K_EVENTS = "eventos", K_FIRED = "tocados";

    static final String ACT_PRE = "com.inforprime.naoesquece.AVISO";
    static final String ACT_RING = "com.inforprime.naoesquece.ALARME";
    static final String ACT_SKIP = "com.inforprime.naoesquece.NAO_TOCAR";
    static final String ACT_OP = "com.inforprime.naoesquece.ACAO";

    static final String CH_BOTH = "aviso_som_vibra", CH_SOUND = "aviso_som", CH_VIBRA = "aviso_vibra";
    static final String CH_ALARM = "alarme", CH_ALARM_LOUD = "alarme_reserva", CH_MISSED = "perdidos";

    static final long[] VIB = {0, 350, 200, 350};
    static final long[] VIB_ALARM = {0, 900, 700};
    static final int COLOR = 0xFF0A54C8;
    static final long PRE_MS = 60_000L;
    static final int SNOOZE_MIN = 5;

    private static final int IMM = PendingIntent.FLAG_IMMUTABLE;
    private static final Locale BR = new Locale("pt", "BR");

    private Reminders() {}

    /* ---------- guardar ---------- */
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray load(Context c, String k) {
        try {
            return new JSONArray(sp(c).getString(k, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void save(Context c, String k, JSONArray a) {
        sp(c).edit().putString(k, a.toString()).commit();
    }

    static JSONObject parse(String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static String clip(String s, int n) {
        s = s == null ? "" : s.trim();
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    static String mode(String m) {
        return "som".equals(m) || "vibra".equals(m) ? m : "ambos";
    }

    /* ---------- horários ---------- */
    static long parseLocal(String at) {
        if (TextUtils.isEmpty(at)) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat(at.length() > 16 ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd'T'HH:mm", Locale.US);
            f.setLenient(false);
            Date d = f.parse(at);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    static String repeat(JSONObject a) {
        String r = a.optString("repeat", "");
        return r.equals("dia") || r.equals("uteis") || r.equals("semana") || r.equals("mes") ? r : "";
    }

    static boolean isRepeat(JSONObject a) {
        return !repeat(a).isEmpty() && !a.optBoolean("snooze") && !a.optBoolean("test");
    }

    private static long beforeMs(JSONObject a) {
        return Math.max(0, Math.min(40320, a.optInt("before", 0))) * 60_000L;
    }

    /** k-ésima ocorrência de um lembrete que repete (null = dia que não conta, como sábado em "dias úteis"). */
    private static Calendar occ(Calendar base, String rep, int k) {
        Calendar o = (Calendar) base.clone();
        switch (rep) {
            case "dia":
                o.add(Calendar.DAY_OF_MONTH, k);
                return o;
            case "uteis": {
                o.add(Calendar.DAY_OF_MONTH, k);
                int w = o.get(Calendar.DAY_OF_WEEK);
                return w == Calendar.SATURDAY || w == Calendar.SUNDAY ? null : o;
            }
            case "semana":
                o.add(Calendar.DAY_OF_MONTH, 7 * k);
                return o;
            case "mes": {
                int day = base.get(Calendar.DAY_OF_MONTH);
                o.set(Calendar.DAY_OF_MONTH, 1);
                o.add(Calendar.MONTH, k);
                o.set(Calendar.DAY_OF_MONTH, Math.min(day, o.getActualMaximum(Calendar.DAY_OF_MONTH)));
                return o;
            }
            default:
                return k == 0 ? o : null;
        }
    }

    /** Primeira ocorrência (dia e hora da atividade) depois de t. */
    static long occAfter(JSONObject a, long t) {
        long st = parseLocal(a.optString("at", ""));
        if (st <= 0) return 0;
        String rep = repeat(a);
        if (rep.isEmpty()) return st > t ? st : 0;
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(st);
        int k0 = 0;
        if (t > st) {
            long days = (t - st) / 86_400_000L;
            k0 = (int) Math.max(0, (rep.equals("semana") ? days / 7 : rep.equals("mes") ? days / 31 : days) - 2);
        }
        for (int k = k0; k < k0 + 800; k++) {
            Calendar o = occ(base, rep, k);
            if (o != null && o.getTimeInMillis() > t) return o.getTimeInMillis();
        }
        return 0;
    }

    /** Hora do próximo alarme depois de "after" (um lembrete que não repete tem uma hora só, mesmo que já tenha passado). */
    static long nextRing(JSONObject a, long after) {
        long r = a.optLong("ring", 0);
        if (r > 0) return r;
        long b = beforeMs(a);
        if (repeat(a).isEmpty()) {
            long st = parseLocal(a.optString("at", ""));
            return st <= 0 ? 0 : st - b;
        }
        long o = occAfter(a, after + b);
        return o <= 0 ? 0 : o - b;
    }

    /** Hora em que o alarme toca: dia e hora da atividade menos o "tocar antes". */
    static long ringOf(JSONObject a) {
        return nextRing(a, System.currentTimeMillis());
    }

    private static String key(JSONObject a) {
        return a.optString("id") + "@" + ringOf(a);
    }

    /** Próximo alarme que ainda não tocou (pula os já tocados ou dispensados). */
    private static long upcoming(JSONObject a, Set<String> fired) {
        long now = System.currentTimeMillis();
        String id = a.optString("id");
        long ring = nextRing(a, now);
        for (int g = 0; g < 400 && ring > 0 && fired.contains(id + "@" + ring) && isRepeat(a); g++) ring = nextRing(a, ring);
        return ring > now && !fired.contains(id + "@" + ring) ? ring : 0;
    }

    static String hhmm(long t) {
        return new SimpleDateFormat("HH:mm", BR).format(new Date(t));
    }

    private static String dayHour(long t) {
        Date d = new Date(t);
        String day = new SimpleDateFormat("dd/MM", BR).format(d);
        String today = new SimpleDateFormat("dd/MM", BR).format(new Date());
        return day.equals(today) ? hhmm(t) : day + " às " + hhmm(t);
    }

    static String titleOf(JSONObject a) {
        String t = a.optString("title", "").trim();
        return t.isEmpty() ? "Lembrete" : t;
    }

    /** "Alarme das 13:50 · atividade às 14:00" */
    static String alarmLine(JSONObject a) {
        long ring = ringOf(a), st = a.optLong("occ", 0);
        if (st <= 0) st = parseLocal(a.optString("at", ""));
        String s = "Alarme das " + hhmm(ring);
        if (st > 0 && st - ring >= 60_000L) s += " · atividade " + (st - ring >= 86_400_000L ? "em " : "às ") + dayHour(st);
        return s;
    }

    /* ---------- relógio do Android ---------- */
    static boolean exactOk(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    static Intent openIntent(Context c, String id) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.setData(Uri.parse(Links.BASE + "?nota=" + Uri.encode(id == null ? "" : id)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    private static Intent alarmIntent(Context c, String action, String kind, JSONObject a) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(action);
        i.setData(Uri.parse("naoesquece://alarme/" + kind + "/" + a.optString("id")));
        i.putExtra("a", a.toString());
        return i;
    }

    private static PendingIntent pi(Context c, String action, String kind, JSONObject a, boolean create) {
        int fl = IMM | (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
        return PendingIntent.getBroadcast(c, 0, alarmIntent(c, action, kind, a), fl);
    }

    private static void schedule(Context c, JSONObject a0, String kind, boolean withPre, Set<String> fired) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long ring = upcoming(a0, fired), now = System.currentTimeMillis();
        if (ring <= now) return;
        boolean exact = exactOk(c);
        try {
            // o alarme leva a hora exata desta ocorrência (num lembrete que repete, cada dia é uma)
            JSONObject a = new JSONObject(a0.toString());
            a.put("ring", ring);
            if (!a.has("occ")) a.put("occ", ring + beforeMs(a0));
            PendingIntent r = pi(c, ACT_RING, kind, a, true);
            if (exact) {
                PendingIntent show = PendingIntent.getActivity(c, 0, openIntent(c, a.optString("id")), IMM | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(ring, show), r);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ring, r);
            }
            long pre = ring - PRE_MS;
            if (withPre && pre > now) {
                PendingIntent p = pi(c, ACT_PRE, "aviso", a, true);
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, pre, p);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, pre, p);
            }
        } catch (Exception e) {
            Log.w(TAG, "agendar", e);
        }
    }

    private static void cancel(Context c, JSONObject a, String... kinds) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (String k : kinds) {
            PendingIntent p = pi(c, k.equals("aviso") ? ACT_PRE : ACT_RING, k, a, false);
            if (p != null) {
                if (am != null) am.cancel(p);
                p.cancel();
            }
        }
    }

    /** Lista que vem do app (todas as notas com lembrete). Substitui a anterior e devolve quantos alarmes ficaram marcados. */
    static synchronized int sync(Context c, String json) {
        JSONArray next = new JSONArray();
        try {
            JSONArray in = new JSONArray(json);
            for (int k = 0; k < in.length() && next.length() < 500; k++) {
                JSONObject a = in.optJSONObject(k);
                if (a == null || !validId(a.optString("id")) || parseLocal(a.optString("at")) <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("id", a.optString("id"));
                o.put("at", a.optString("at"));
                o.put("before", Math.max(0, Math.min(40320, a.optInt("before", 0))));
                o.put("title", clip(a.optString("title", ""), 200));
                o.put("text", clip(a.optString("text", ""), 300));
                o.put("mode", mode(a.optString("mode")));
                if (!repeat(a).isEmpty()) o.put("repeat", repeat(a));
                next.put(o);
            }
        } catch (Exception e) {
            return -1;
        }
        ensureChannels(c);
        JSONArray prev = load(c, K_LIST);
        for (int k = 0; k < prev.length(); k++) {
            JSONObject a = prev.optJSONObject(k);
            if (a != null) cancel(c, a, "tocar", "aviso");
        }
        save(c, K_LIST, next);
        Set<String> fired = firedSet(c);
        long now = System.currentTimeMillis();
        int n = 0;
        for (int k = 0; k < next.length(); k++) {
            JSONObject a = next.optJSONObject(k);
            schedule(c, a, "tocar", true, fired);
            if (upcoming(a, fired) > now) n++;
        }
        TodayWidget.refresh(c);
        return n;
    }

    /** Depois de ligar o celular, mudar a hora ou atualizar o app, o Android esquece os alarmes: marca tudo de novo. */
    static synchronized void rescheduleAll(Context c, boolean boot) {
        ensureChannels(c);
        long now = System.currentTimeMillis();
        Set<String> fired = firedSet(c);
        JSONArray list = load(c, K_LIST);
        for (int k = 0; k < list.length(); k++) {
            JSONObject a = list.optJSONObject(k);
            if (a == null) continue;
            long r = nextRing(a, now - 3_600_000L);
            if (boot && r > 0 && r <= now && r > now - 3_600_000L && !fired.contains(a.optString("id") + "@" + r)) {
                try {
                    JSONObject m = new JSONObject(a.toString());
                    m.put("ring", r);
                    m.put("occ", r + beforeMs(a));
                    postMissed(c, m);
                    markFired(c, m);
                    fired.add(a.optString("id") + "@" + r);
                } catch (Exception ignored) {
                }
            }
            cancel(c, a, "tocar", "aviso");
            schedule(c, a, "tocar", true, fired);
        }
        JSONArray sn = load(c, K_SNOOZE), keep = new JSONArray();
        for (int k = 0; k < sn.length(); k++) {
            JSONObject s = sn.optJSONObject(k);
            if (s == null) continue;
            if (ringOf(s) > now) {
                keep.put(s);
                cancel(c, s, "adiado");
                schedule(c, s, "adiado", false, fired);
            } else if (boot && ringOf(s) > now - 3_600_000L && !fired.contains(key(s))) {
                postMissed(c, s);
                markFired(c, s);
            }
        }
        save(c, K_SNOOZE, keep);
        TodayWidget.refresh(c);
    }

    /** O alarme ainda vale? (a nota pode ter mudado de hora ou o lembrete pode ter sido removido) */
    static boolean stillWanted(Context c, JSONObject a) {
        if (a.optBoolean("test") || a.optBoolean("snooze")) return true;
        return item(c, a) != null;
    }

    /** O lembrete da lista que gerou este alarme (mesma nota, mesmo dia e hora, mesma repetição). */
    static JSONObject item(Context c, JSONObject a) {
        JSONArray list = load(c, K_LIST);
        String id = a.optString("id");
        for (int k = 0; k < list.length(); k++) {
            JSONObject x = list.optJSONObject(k);
            if (x != null && id.equals(x.optString("id")) && x.optString("at").equals(a.optString("at"))
                    && x.optInt("before") == a.optInt("before") && repeat(x).equals(repeat(a))) return x;
        }
        return null;
    }

    /** Depois de tocar (ou de dispensar) uma ocorrência de um lembrete que repete, marca a próxima. */
    static synchronized void scheduleNext(Context c, JSONObject a) {
        if (!isRepeat(a)) return;
        JSONObject x = item(c, a);
        if (x != null) schedule(c, x, "tocar", true, firedSet(c));
        TodayWidget.refresh(c);
    }

    private static Set<String> firedSet(Context c) {
        JSONArray a = load(c, K_FIRED);
        Set<String> s = new HashSet<>();
        for (int k = 0; k < a.length(); k++) s.add(a.optString(k));
        return s;
    }

    static synchronized void markFired(Context c, JSONObject a) {
        JSONArray f = load(c, K_FIRED), out = new JSONArray();
        for (int k = Math.max(0, f.length() - 199); k < f.length(); k++) out.put(f.optString(k));
        out.put(key(a));
        save(c, K_FIRED, out);
    }

    static synchronized void snooze(Context c, JSONObject a, int min) {
        try {
            JSONObject s = new JSONObject(a.toString());
            s.remove("test");
            if (!s.has("occ")) s.put("occ", ringOf(a) + beforeMs(a));
            s.put("ring", System.currentTimeMillis() + min * 60_000L);
            s.put("snooze", true);
            JSONArray list = load(c, K_SNOOZE), out = new JSONArray();
            long now = System.currentTimeMillis();
            for (int k = 0; k < list.length(); k++) {
                JSONObject x = list.optJSONObject(k);
                if (x != null && !x.optString("id").equals(s.optString("id")) && ringOf(x) > now) out.put(x);
            }
            out.put(s);
            save(c, K_SNOOZE, out);
            cancel(c, s, "adiado");
            schedule(c, s, "adiado", false, new HashSet<>());
        } catch (Exception e) {
            Log.w(TAG, "adiar", e);
        }
    }

    static synchronized void dropSnooze(Context c, String id) {
        JSONArray list = load(c, K_SNOOZE), out = new JSONArray();
        for (int k = 0; k < list.length(); k++) {
            JSONObject x = list.optJSONObject(k);
            if (x != null && !id.equals(x.optString("id"))) out.put(x);
        }
        save(c, K_SNOOZE, out);
    }

    /** "Não tocar o alarme", no aviso de 1 minuto antes. */
    static void skip(Context c, JSONObject a) {
        markFired(c, a);
        cancel(c, a, a.optBoolean("snooze") ? "adiado" : "tocar");
        NotificationManagerCompat.from(c).cancel("aviso:" + a.optString("id"), 1);
        scheduleNext(c, a);
    }

    /** Parar, adiar ou concluir a partir da tela do alarme ou do alarme de reserva. */
    static void act(Context c, String op, JSONObject a) {
        if (a == null) return;
        String id = a.optString("id");
        if ("snooze".equals(op)) snooze(c, a, SNOOZE_MIN);
        else if ("done".equals(op) && !a.optBoolean("test")) addEvent(c, id, "done");
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        nm.cancel("alarme:" + id, 1);
        nm.cancel("aviso:" + id, 1);
        if (AlarmService.isRinging()) {
            try {
                c.startService(new Intent(c, AlarmService.class).setAction(AlarmService.ACT_STOP));
            } catch (Exception ignored) {
            }
        }
    }

    /** Teste: o aviso chega agora e o alarme toca em 20 segundos. */
    static void test(Context c, String mode) {
        try {
            ensureChannels(c);
            JSONObject a = new JSONObject();
            a.put("id", "teste");
            a.put("title", "Teste do Não Esquece");
            a.put("text", "Se você está vendo isto, o alarme do app funciona neste celular.");
            a.put("mode", mode(mode));
            a.put("test", true);
            a.put("ring", System.currentTimeMillis() + 20_000L);
            a.put("pre", "Teste: o alarme toca em 20 segundos");
            postPre(c, a);
            cancel(c, a, "teste");
            schedule(c, a, "teste", false, new HashSet<>());
        } catch (Exception e) {
            Log.w(TAG, "teste", e);
        }
    }

    static synchronized void clearAll(Context c) {
        JSONArray l = load(c, K_LIST);
        for (int k = 0; k < l.length(); k++) {
            JSONObject a = l.optJSONObject(k);
            if (a != null) cancel(c, a, "tocar", "aviso");
        }
        JSONArray s = load(c, K_SNOOZE);
        for (int k = 0; k < s.length(); k++) {
            JSONObject a = s.optJSONObject(k);
            if (a != null) cancel(c, a, "adiado");
        }
        sp(c).edit().clear().commit();
        NotificationManagerCompat.from(c).cancelAll();
        if (AlarmService.isRinging()) {
            try {
                c.startService(new Intent(c, AlarmService.class).setAction(AlarmService.ACT_STOP));
            } catch (Exception ignored) {
            }
        }
    }

    static int pending(Context c) {
        long now = System.currentTimeMillis();
        Set<String> fired = firedSet(c);
        int n = 0;
        JSONArray l = load(c, K_LIST);
        for (int k = 0; k < l.length(); k++) {
            JSONObject a = l.optJSONObject(k);
            if (a != null && upcoming(a, fired) > now) n++;
        }
        return n + load(c, K_SNOOZE).length();
    }

    /** Lembretes de hoje, em ordem de horário (para o widget). */
    static synchronized List<JSONObject> today(Context c) {
        Calendar s = Calendar.getInstance();
        s.set(Calendar.HOUR_OF_DAY, 0);
        s.set(Calendar.MINUTE, 0);
        s.set(Calendar.SECOND, 0);
        s.set(Calendar.MILLISECOND, 0);
        long start = s.getTimeInMillis();
        s.add(Calendar.DAY_OF_MONTH, 1);
        long end = s.getTimeInMillis();
        List<JSONObject> out = new ArrayList<>();
        JSONArray l = load(c, K_LIST);
        for (int k = 0; k < l.length(); k++) {
            JSONObject a = l.optJSONObject(k);
            if (a == null) continue;
            long t = occAfter(a, start - 1);
            if (t <= 0 || t >= end) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", a.optString("id"));
                o.put("title", titleOf(a));
                o.put("t", t);
                o.put("repeat", repeat(a));
                out.add(o);
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (x, y) -> Long.compare(x.optLong("t"), y.optLong("t")));
        return out;
    }

    /* ---------- recados para o app (concluir pela tela do alarme) ---------- */
    static synchronized void addEvent(Context c, String id, String act) {
        JSONArray e = load(c, K_EVENTS);
        if (e.length() > 100) e = new JSONArray();
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("act", act);
            o.put("at", System.currentTimeMillis());
            e.put(o);
        } catch (Exception ignored) {
        }
        save(c, K_EVENTS, e);
    }

    static synchronized String takeEvents(Context c) {
        String s = sp(c).getString(K_EVENTS, "[]");
        sp(c).edit().remove(K_EVENTS).commit();
        return s;
    }

    /* ---------- situação das permissões (para a tela de Ajustes) ---------- */
    static JSONObject state(Context c) {
        JSONObject o = new JSONObject();
        try {
            ensureChannels(c);
            NotificationManagerCompat nmc = NotificationManagerCompat.from(c);
            boolean notif = nmc.areNotificationsEnabled();
            boolean chOk = true;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                for (String ch : new String[]{CH_BOTH, CH_SOUND, CH_VIBRA, CH_ALARM}) {
                    NotificationChannel x = nm == null ? null : nm.getNotificationChannel(ch);
                    if (x != null && x.getImportance() == NotificationManager.IMPORTANCE_NONE) chOk = false;
                }
            }
            o.put("notif", notif);
            o.put("channels", chOk);
            o.put("exact", exactOk(c));
            boolean full = true;
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                full = nm != null && nm.canUseFullScreenIntent();
            }
            o.put("full", full);
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            o.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
            AudioManager au = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            o.put("vol", au == null ? -1 : au.getStreamVolume(AudioManager.STREAM_ALARM));
            o.put("volMax", au == null ? -1 : au.getStreamMaxVolume(AudioManager.STREAM_ALARM));
            o.put("cal", DeviceCalendar.perm(c));
            o.put("pending", pending(c));
            o.put("widgets", TodayWidget.count(c));
            o.put("scanner", true);
            o.put("ocr", true);
            o.put("tilesAuto", Build.VERSION.SDK_INT >= 33);
            o.put("quick", Quick.count(c));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("maker", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.US));
        } catch (Exception e) {
            Log.w(TAG, "estado", e);
        }
        return o;
    }

    /* ---------- notificações ---------- */
    static Uri alarmUri(Context c) {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return u;
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        AudioAttributes evt = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        AudioAttributes alarm = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        List<NotificationChannel> l = new ArrayList<>();
        NotificationChannel x;

        x = new NotificationChannel(CH_BOTH, "Aviso 1 minuto antes · som e vibração", NotificationManager.IMPORTANCE_HIGH);
        x.setSound(snd, evt);
        x.enableVibration(true);
        x.setVibrationPattern(VIB);
        l.add(x);

        x = new NotificationChannel(CH_SOUND, "Aviso 1 minuto antes · só som", NotificationManager.IMPORTANCE_HIGH);
        x.setSound(snd, evt);
        x.enableVibration(false);
        l.add(x);

        x = new NotificationChannel(CH_VIBRA, "Aviso 1 minuto antes · só vibração", NotificationManager.IMPORTANCE_HIGH);
        x.setSound(null, null);
        x.enableVibration(true);
        x.setVibrationPattern(VIB);
        l.add(x);

        x = new NotificationChannel(CH_ALARM, "Alarme do lembrete", NotificationManager.IMPORTANCE_HIGH);
        x.setDescription("Tela do alarme. O som e a vibração são do próprio alarme.");
        x.setSound(null, null);
        x.enableVibration(false);
        x.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        l.add(x);

        x = new NotificationChannel(CH_ALARM_LOUD, "Alarme (reserva)", NotificationManager.IMPORTANCE_HIGH);
        x.setDescription("Usado quando o celular não deixa o alarme principal abrir.");
        x.setSound(alarmUri(c), alarm);
        x.enableVibration(true);
        x.setVibrationPattern(VIB_ALARM);
        x.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        l.add(x);

        x = new NotificationChannel(CH_MISSED, "Lembretes perdidos", NotificationManager.IMPORTANCE_DEFAULT);
        x.setSound(null, null);
        x.enableVibration(false);
        l.add(x);

        nm.createNotificationChannels(l);
    }

    private static NotificationCompat.Builder base(Context c, String ch) {
        Notification pub = new NotificationCompat.Builder(c, ch)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setColor(COLOR)
                .setContentTitle("Não Esquece")
                .setContentText("Você tem um lembrete")
                .build();
        return new NotificationCompat.Builder(c, ch)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setColor(COLOR)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(pub);
    }

    private static void show(Context c, String tag, Notification n) {
        try {
            NotificationManagerCompat.from(c).notify(tag, 1, n);
        } catch (SecurityException e) {
            Log.w(TAG, "sem permissão de notificação", e);
        }
    }

    private static PendingIntent opPI(Context c, String op, JSONObject a) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACT_OP);
        i.setData(Uri.parse("naoesquece://alarme/" + op + "/" + a.optString("id")));
        i.putExtra("op", op);
        i.putExtra("a", a.toString());
        return PendingIntent.getBroadcast(c, 0, i, IMM | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Aviso de 1 minuto antes, com som, vibração ou os dois. */
    static void postPre(Context c, JSONObject a) {
        ensureChannels(c);
        String m = mode(a.optString("mode"));
        String ch = m.equals("som") ? CH_SOUND : m.equals("vibra") ? CH_VIBRA : CH_BOTH;
        String id = a.optString("id"), title = titleOf(a), text = a.optString("text", "").trim();
        long ring = ringOf(a);
        String head = a.optString("pre", "");
        if (head.isEmpty()) head = "Em 1 minuto: " + title;
        String line = a.optBoolean("test") ? text : alarmLine(a);
        Intent skip = new Intent(c, AlarmReceiver.class).setAction(ACT_SKIP)
                .setData(Uri.parse("naoesquece://alarme/pular/" + id)).putExtra("a", a.toString());
        NotificationCompat.Builder b = base(c, ch)
                .setContentTitle(head)
                .setContentText(line)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(a.optBoolean("test") || text.isEmpty() ? line : line + "\n" + text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setWhen(ring)
                .setShowWhen(true)
                .setTimeoutAfter(10 * 60_000L)
                .setContentIntent(PendingIntent.getActivity(c, 0, openIntent(c, a.optBoolean("test") ? "" : id), IMM | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(0, "Não tocar o alarme", PendingIntent.getBroadcast(c, 0, skip, IMM | PendingIntent.FLAG_UPDATE_CURRENT));
        if (Build.VERSION.SDK_INT < 26) {
            if (m.equals("som")) b.setDefaults(Notification.DEFAULT_SOUND);
            else if (m.equals("vibra")) b.setVibrate(VIB);
            else {
                b.setDefaults(Notification.DEFAULT_SOUND);
                b.setVibrate(VIB);
            }
        }
        show(c, "aviso:" + id, b.build());
    }

    static void postMissed(Context c, JSONObject a) {
        ensureChannels(c);
        String id = a.optString("id");
        NotificationCompat.Builder b = base(c, CH_MISSED)
                .setContentTitle("Lembrete perdido: " + titleOf(a))
                .setContentText("O alarme era às " + hhmm(ringOf(a)) + ".")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setWhen(ringOf(a))
                .setShowWhen(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, openIntent(c, a.optBoolean("test") ? "" : id), IMM | PendingIntent.FLAG_UPDATE_CURRENT));
        show(c, "perdido:" + id, b.build());
    }

    /** Alarme de reserva: notificação que repete o som de alarme até a pessoa tocar nela. */
    static void postLoud(Context c, JSONObject a) {
        ensureChannels(c);
        String id = a.optString("id");
        Intent screen = new Intent(c, AlarmActivity.class)
                .setData(Uri.parse("naoesquece://alarme/tela/" + id))
                .putExtra("a", a.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent full = PendingIntent.getActivity(c, 0, screen, IMM | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_ALARM_LOUD)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setColor(COLOR)
                .setContentTitle(titleOf(a))
                .setContentText(alarmLine(a))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setTimeoutAfter(AlarmService.MAX_MS)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setDeleteIntent(opPI(c, "stop", a))
                .addAction(0, "Parar", opPI(c, "stop", a))
                .addAction(0, "Adiar " + SNOOZE_MIN + " min", opPI(c, "snooze", a));
        if (!isRepeat(a)) b.addAction(0, "Concluída", opPI(c, "done", a));
        if (Build.VERSION.SDK_INT < 26) {
            b.setSound(alarmUri(c), AudioManager.STREAM_ALARM);
            b.setVibrate(VIB_ALARM);
        }
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;
        show(c, "alarme:" + id, n);
    }
}
