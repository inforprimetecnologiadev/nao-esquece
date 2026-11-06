package com.inforprime.naoesquece;

import android.Manifest;
import android.accounts.Account;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Lembretes no calendário do próprio celular (o mesmo que o app de calendário do aparelho mostra).
 * Usa a agenda da conta Google que entrou no Não Esquece: o evento sincroniza com essa conta
 * e aparece em todos os aparelhos e apps de calendário ligados a ela.
 * Cada evento leva no fim da descrição a marca "ne-ref:<id da nota>", para o app achar e atualizar
 * o mesmo evento em qualquer celular, sem duplicar.
 */
final class DeviceCalendar {
    private static final String TAG = "NaoEsquece";
    static final String MARK = "ne-ref:";
    private static final String LOCAL = "Não Esquece";
    private static final long DURATION = 30 * 60_000L;

    private DeviceCalendar() {}

    static boolean perm(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private static final class Pick {
        long id = -1;
        String name = "", account = "", type = "";
        boolean google;
    }

    private static String str(Cursor q, int i) {
        try {
            String s = q.getString(i);
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static Cursor calendars(ContentResolver cr) {
        String[] full = {Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.OWNER_ACCOUNT,
                Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE, Calendars.IS_PRIMARY};
        try {
            return cr.query(Calendars.CONTENT_URI, full, null, null, null);
        } catch (Exception e) {
            // alguns aparelhos não têm a coluna "isPrimary"
            String[] basic = {Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.OWNER_ACCOUNT,
                    Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE};
            return cr.query(Calendars.CONTENT_URI, basic, null, null, null);
        }
    }

    /** Escolhe a agenda: a principal da conta Google do app; se ela não estiver no celular, a agenda "Não Esquece" do aparelho. */
    private static Pick pick(Context c, String email, boolean create) {
        Pick best = null;
        int bestScore = 0;
        try (Cursor q = calendars(c.getContentResolver())) {
            while (q != null && q.moveToNext()) {
                int access = q.getInt(5);
                if (access < Calendars.CAL_ACCESS_CONTRIBUTOR) continue;
                String acc = str(q, 1), type = str(q, 2), owner = str(q, 3);
                boolean google = "com.google".equals(type);
                boolean mine = !TextUtils.isEmpty(email) && email.equalsIgnoreCase(acc);
                boolean primary = (q.getColumnCount() > 7 && q.getInt(7) == 1) || (!owner.isEmpty() && owner.equalsIgnoreCase(acc));
                int s = 0;
                if (google && mine && primary) s = 100;
                else if (google && mine) s = 80;
                else if (LOCAL.equals(acc) && CalendarContract.ACCOUNT_TYPE_LOCAL.equals(type)) s = 50;
                if (s > bestScore) {
                    bestScore = s;
                    best = new Pick();
                    best.id = q.getLong(0);
                    best.account = acc;
                    best.type = type;
                    best.name = str(q, 4);
                    best.google = google;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "agendas", e);
        }
        if (best == null && create) {
            long id = createLocal(c);
            if (id > 0) {
                best = new Pick();
                best.id = id;
                best.account = LOCAL;
                best.type = CalendarContract.ACCOUNT_TYPE_LOCAL;
                best.name = LOCAL;
            }
        }
        return best;
    }

    private static Uri asSync(Uri u, String acc, String type) {
        return u.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, acc)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, type).build();
    }

    private static long createLocal(Context c) {
        try {
            ContentValues v = new ContentValues();
            v.put(Calendars.ACCOUNT_NAME, LOCAL);
            v.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
            v.put(Calendars.NAME, "nao_esquece");
            v.put(Calendars.CALENDAR_DISPLAY_NAME, LOCAL);
            v.put(Calendars.CALENDAR_COLOR, 0xFF0A54C8);
            v.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
            v.put(Calendars.OWNER_ACCOUNT, LOCAL);
            v.put(Calendars.VISIBLE, 1);
            v.put(Calendars.SYNC_EVENTS, 1);
            v.put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());
            Uri r = c.getContentResolver().insert(asSync(Calendars.CONTENT_URI, LOCAL, CalendarContract.ACCOUNT_TYPE_LOCAL), v);
            return r == null ? -1 : ContentUris.parseId(r);
        } catch (Exception e) {
            Log.w(TAG, "criar agenda", e);
            return -1;
        }
    }

    private static boolean syncOn(Pick p) {
        if (p == null || !p.google) return true;
        try {
            Account a = new Account(p.account, "com.google");
            return ContentResolver.getMasterSyncAutomatically() && ContentResolver.getSyncAutomatically(a, CalendarContract.AUTHORITY);
        } catch (Exception e) {
            return true;
        }
    }

    private static void requestSync(Pick p) {
        if (p == null || !p.google) return;
        try {
            Bundle b = new Bundle();
            b.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            b.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(new Account(p.account, "com.google"), CalendarContract.AUTHORITY, b);
        } catch (Exception e) {
            Log.w(TAG, "sincronizar agenda", e);
        }
    }

    private static boolean hasMark(String desc, String id) {
        if (desc == null) return false;
        String m = MARK + id;
        int i = desc.indexOf(m);
        while (i >= 0) {
            int e = i + m.length();
            if (e >= desc.length() || !Character.isLetterOrDigit(desc.charAt(e)) && desc.charAt(e) != '-' && desc.charAt(e) != '_') return true;
            i = desc.indexOf(m, i + 1);
        }
        return false;
    }

    /** Eventos do app para esta nota: [id do evento, id da agenda]. */
    private static List<long[]> find(ContentResolver cr, String id) {
        List<long[]> out = new ArrayList<>();
        String[] proj = {Events._ID, Events.CALENDAR_ID, Events.DESCRIPTION};
        String sel = Events.DESCRIPTION + " LIKE ? AND " + Events.DELETED + "=0";
        try (Cursor q = cr.query(Events.CONTENT_URI, proj, sel, new String[]{"%" + MARK + id + "%"}, null)) {
            while (q != null && q.moveToNext()) {
                if (hasMark(q.getString(2), id)) out.add(new long[]{q.getLong(0), q.getLong(1)});
            }
        } catch (Exception e) {
            Log.w(TAG, "procurar evento", e);
        }
        return out;
    }

    private static String desc(String text, String id) {
        String t = text == null ? "" : text.trim();
        if (t.length() > 900) t = t.substring(0, 899) + "…";
        return (t.isEmpty() ? "" : t + "\n\n") + "Lembrete do app Não Esquece · " + MARK + id;
    }

    private static boolean upsert(ContentResolver cr, long cal, JSONObject n) {
        String id = n.optString("id");
        long start = Reminders.parseLocal(n.optString("at"));
        if (!Reminders.validId(id) || start <= 0) return false;
        String title = n.optString("title", "").trim();
        ContentValues v = new ContentValues();
        v.put(Events.DTSTART, start);
        v.put(Events.DTEND, start + DURATION);
        v.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        v.put(Events.TITLE, title.isEmpty() ? "Lembrete" : title);
        v.put(Events.DESCRIPTION, desc(n.optString("text"), id));
        v.put(Events.HAS_ALARM, 0);
        v.put(Events.AVAILABILITY, Events.AVAILABILITY_FREE);
        List<long[]> ex = find(cr, id);
        long keep = -1;
        for (long[] e : ex) if (e[1] == cal && keep < 0) keep = e[0];
        for (long[] e : ex) if (e[0] != keep) cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, e[0]), null, null);
        if (keep > 0) return cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, keep), v, null, null) > 0;
        v.put(Events.CALENDAR_ID, cal);
        return cr.insert(Events.CONTENT_URI, v) != null;
    }

    private static JSONObject describe(Pick p, boolean perm) throws Exception {
        JSONObject o = new JSONObject();
        o.put("perm", perm);
        o.put("found", p != null);
        if (p != null) {
            o.put("cal", p.name);
            o.put("account", p.account);
            o.put("google", p.google);
            o.put("syncOn", syncOn(p));
        }
        return o;
    }

    static JSONObject info(Context c, String email) {
        try {
            boolean perm = perm(c);
            return describe(perm ? pick(c, email, false) : null, perm);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** {up:[{id,title,text,at}], del:[id]} → cria, atualiza ou apaga os eventos. */
    static JSONObject sync(Context c, String email, String json) {
        JSONObject out = new JSONObject();
        try {
            if (!perm(c)) {
                out.put("ok", false);
                out.put("err", "perm");
                return out;
            }
            Pick p = pick(c, email, true);
            if (p == null) {
                out.put("ok", false);
                out.put("err", "sem_agenda");
                return out;
            }
            ContentResolver cr = c.getContentResolver();
            JSONObject in = new JSONObject(json);
            JSONArray up = in.optJSONArray("up"), del = in.optJSONArray("del"), ok = new JSONArray();
            if (del != null) for (int k = 0; k < del.length(); k++) {
                String id = del.optString(k);
                if (!Reminders.validId(id)) continue;
                try {
                    for (long[] e : find(cr, id)) cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, e[0]), null, null);
                    ok.put(id);
                } catch (Exception e) {
                    Log.w(TAG, "apagar evento", e);
                }
            }
            if (up != null) for (int k = 0; k < up.length(); k++) {
                JSONObject n = up.optJSONObject(k);
                if (n == null) continue;
                try {
                    if (upsert(cr, p.id, n)) ok.put(n.optString("id"));
                } catch (Exception e) {
                    Log.w(TAG, "gravar evento", e);
                }
            }
            requestSync(p);
            out = describe(p, true);
            out.put("ok", true);
            out.put("done", ok);
        } catch (Exception e) {
            Log.w(TAG, "agenda", e);
            try {
                out.put("ok", false);
                out.put("err", "erro");
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Tira do calendário todos os lembretes do app (e a agenda "Não Esquece" do aparelho, se foi criada). */
    static int wipe(Context c) {
        if (!perm(c)) return 0;
        int n = 0;
        ContentResolver cr = c.getContentResolver();
        String[] proj = {Events._ID, Events.DESCRIPTION};
        List<Long> ids = new ArrayList<>();
        try (Cursor q = cr.query(Events.CONTENT_URI, proj, Events.DESCRIPTION + " LIKE ? AND " + Events.DELETED + "=0",
                new String[]{"%" + MARK + "%"}, null)) {
            while (q != null && q.moveToNext()) {
                String d = q.getString(1);
                if (d != null && d.contains("Não Esquece · " + MARK)) ids.add(q.getLong(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "limpar agenda", e);
        }
        for (long id : ids) {
            try {
                n += cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null);
            } catch (Exception ignored) {
            }
        }
        try {
            cr.delete(asSync(Calendars.CONTENT_URI, LOCAL, CalendarContract.ACCOUNT_TYPE_LOCAL),
                    Calendars.ACCOUNT_NAME + "=? AND " + Calendars.ACCOUNT_TYPE + "=?", new String[]{LOCAL, CalendarContract.ACCOUNT_TYPE_LOCAL});
        } catch (Exception ignored) {
        }
        return n;
    }
}
