package com.inforprime.naoesquece;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/** Recebe a hora do aviso e do alarme marcados no relógio do Android (e os botões das notificações). */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String act = i == null ? null : i.getAction();
        JSONObject a = Reminders.parse(i == null ? null : i.getStringExtra("a"));
        if (act == null || a == null) return;
        switch (act) {
            case Reminders.ACT_PRE:
                if (Reminders.stillWanted(c, a)) Reminders.postPre(c, a);
                break;
            case Reminders.ACT_RING:
                ring(c, a);
                break;
            case Reminders.ACT_SKIP:
                Reminders.skip(c, a);
                Toast.makeText(c, "Certo, o alarme não vai tocar.", Toast.LENGTH_SHORT).show();
                break;
            case Reminders.ACT_OP:
                Reminders.act(c, i.getStringExtra("op"), a);
                break;
            default:
                break;
        }
    }

    private static void ring(Context c, JSONObject a) {
        if (a.optBoolean("snooze")) Reminders.dropSnooze(c, a.optString("id"));
        else if (!Reminders.stillWanted(c, a)) return;
        Reminders.markFired(c, a);
        Reminders.scheduleNext(c, a); // lembrete que repete: já marca o próximo
        NotificationManagerCompat.from(c).cancel("aviso:" + a.optString("id"), 1);
        Intent s = new Intent(c, AlarmService.class).setAction(AlarmService.ACT_START).putExtra("a", a.toString());
        try {
            ContextCompat.startForegroundService(c, s);
        } catch (Exception e) {
            Log.w("NaoEsquece", "alarme", e);
            Reminders.postLoud(c, a);
        }
    }
}
