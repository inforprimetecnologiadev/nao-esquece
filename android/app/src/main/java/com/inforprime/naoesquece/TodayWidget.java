package com.inforprime.naoesquece;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Widget da tela inicial: os lembretes de hoje em ordem de horário e o botão Nova nota. */
public class TodayWidget extends AppWidgetProvider {
    static final String ACT_TICK = "com.inforprime.naoesquece.WIDGET";
    private static final int INK = 0xFFE4EAF5, MUTED = 0xFF7D8AA3, ACC = 0xFF4AB6FF;
    private static final int IMM = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    private static final int[] ROW = {R.id.w_r1, R.id.w_r2, R.id.w_r3, R.id.w_r4};
    private static final int[] TIME = {R.id.w_t1, R.id.w_t2, R.id.w_t3, R.id.w_t4};
    private static final int[] NAME = {R.id.w_n1, R.id.w_n2, R.id.w_n3, R.id.w_n4};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = build(c);
        for (int id : ids) m.updateAppWidget(id, v);
        tick(c);
    }

    @Override
    public void onEnabled(Context c) {
        tick(c);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (i != null && ACT_TICK.equals(i.getAction())) refresh(c);
    }

    static int count(Context c) {
        try {
            int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, TodayWidget.class));
            return ids == null ? 0 : ids.length;
        } catch (Exception e) {
            return 0;
        }
    }

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, TodayWidget.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews v = build(c);
            for (int id : ids) m.updateAppWidget(id, v);
            tick(c);
        } catch (Exception ignored) {
        }
    }

    /** Atualiza na virada do dia e a cada hora (o que já passou fica cinza). */
    private static void tick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar n = Calendar.getInstance();
        n.add(Calendar.HOUR_OF_DAY, 1);
        n.set(Calendar.MINUTE, 0);
        n.set(Calendar.SECOND, 5);
        Intent i = new Intent(c, TodayWidget.class).setAction(ACT_TICK);
        am.set(AlarmManager.RTC, n.getTimeInMillis(), PendingIntent.getBroadcast(c, 0, i, IMM));
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_hoje);
        Locale br = new Locale("pt", "BR");
        v.setTextViewText(R.id.w_date, cap(new SimpleDateFormat("EEEE, d 'de' MMMM", br).format(new Date())));
        List<JSONObject> items = Reminders.today(c);
        long now = System.currentTimeMillis();
        int next = -1;
        for (int k = 0; k < items.size(); k++) if (items.get(k).optLong("t") >= now) { next = k; break; }
        // mostra a partir do primeiro que ainda vai tocar (com um que já passou antes, se couber)
        int from = next < 0 ? Math.max(0, items.size() - ROW.length) : Math.max(0, Math.min(next - 1, items.size() - ROW.length));
        for (int k = 0; k < ROW.length; k++) {
            int idx = from + k;
            if (idx < items.size()) {
                JSONObject it = items.get(idx);
                long t = it.optLong("t");
                boolean past = t < now;
                v.setViewVisibility(ROW[k], View.VISIBLE);
                v.setTextViewText(TIME[k], Reminders.hhmm(t));
                v.setTextViewText(NAME[k], it.optString("title") + (it.optString("repeat").isEmpty() ? "" : "  ↻"));
                v.setTextColor(TIME[k], past ? MUTED : ACC);
                v.setTextColor(NAME[k], past ? MUTED : INK);
                v.setOnClickPendingIntent(ROW[k], PendingIntent.getActivity(c, 0, Reminders.openIntent(c, it.optString("id")), IMM));
            } else {
                v.setViewVisibility(ROW[k], View.GONE);
            }
        }
        v.setViewVisibility(R.id.w_empty, items.isEmpty() ? View.VISIBLE : View.GONE);
        int left = items.size() - from - ROW.length;
        v.setViewVisibility(R.id.w_more, left > 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_more, "+" + left + (left == 1 ? " lembrete" : " lembretes") + " hoje");
        Intent nova = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(Links.BASE + "?nova=1"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.w_new, PendingIntent.getActivity(c, 1, nova, IMM));
        Intent hoje = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(Links.BASE + "?hoje=1"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.w_head, PendingIntent.getActivity(c, 2, hoje, IMM));
        v.setOnClickPendingIntent(R.id.w_empty, PendingIntent.getActivity(c, 2, hoje, IMM));
        return v;
    }
}
