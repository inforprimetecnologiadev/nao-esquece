package com.inforprime.naoesquece;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Celular ligado, hora ou fuso alterados, app atualizado ou permissão de alarme liberada: marca os alarmes de novo. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String act = i == null || i.getAction() == null ? "" : i.getAction();
        boolean boot = act.equals(Intent.ACTION_BOOT_COMPLETED) || act.equals("android.intent.action.QUICKBOOT_POWERON");
        PendingResult r = goAsync();
        new Thread(() -> {
            try {
                Reminders.rescheduleAll(c.getApplicationContext(), boot);
            } finally {
                r.finish();
            }
        }).start();
    }
}
