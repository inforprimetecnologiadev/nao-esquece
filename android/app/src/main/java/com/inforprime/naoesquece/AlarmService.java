package com.inforprime.naoesquece;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONObject;

/** Toca o alarme: som de alarme do celular em repetição e vibração, com a tela do alarme por cima da tela bloqueada. */
public class AlarmService extends Service {
    private static final String TAG = "NaoEsquece";
    static final String ACT_START = "com.inforprime.naoesquece.TOCAR";
    static final String ACT_STOP = "com.inforprime.naoesquece.PARAR";
    static final String ACT_SNOOZE = "com.inforprime.naoesquece.ADIAR";
    static final String ACT_DONE = "com.inforprime.naoesquece.CONCLUIR";
    static final String BC_CHANGE = "com.inforprime.naoesquece.ALARME_MUDOU";
    static final long MAX_MS = 150_000L; // toca por até 2 min e 30 s; depois vira "lembrete perdido"
    private static final int NID = 4242;

    private static volatile JSONObject current;

    static JSONObject current() {
        return current;
    }

    static boolean isRinging() {
        return current != null;
    }

    private MediaPlayer player;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> end(true);

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String act = i == null ? null : i.getAction();
        if (ACT_START.equals(act)) {
            JSONObject a = Reminders.parse(i.getStringExtra("a"));
            if (a == null) {
                a = new JSONObject();
                try {
                    a.put("id", "x");
                    a.put("title", "Lembrete");
                } catch (Exception ignored) {
                }
            }
            start(a);
            return START_NOT_STICKY;
        }
        JSONObject a = current;
        if (a != null) {
            if (ACT_SNOOZE.equals(act)) Reminders.snooze(this, a, Reminders.SNOOZE_MIN);
            else if (ACT_DONE.equals(act) && !a.optBoolean("test")) Reminders.addEvent(this, a.optString("id"), "done");
        }
        end(false);
        return START_NOT_STICKY;
    }

    private void start(JSONObject a) {
        JSONObject prev = current;
        if (prev != null && !prev.optString("id").equals(a.optString("id"))) Reminders.postMissed(this, prev);
        stopSound();
        current = a;
        Notification n = notification(a);
        if (!goForeground(n)) {
            current = null;
            Reminders.postLoud(this, a);
            stopSelf();
            return;
        }
        acquireWake();
        String m = Reminders.mode(a.optString("mode"));
        if (!m.equals("vibra")) playSound();
        if (!m.equals("som")) vibrate();
        h.removeCallbacks(timeout);
        h.postDelayed(timeout, MAX_MS);
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            // sem permissão de notificação a tela do alarme não aparece sozinha: tenta abrir direto
            try {
                startActivity(new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
        sendBroadcast(new Intent(BC_CHANGE).setPackage(getPackageName()));
    }

    private boolean goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
                } catch (Exception e) {
                    Log.w(TAG, "systemExempted", e);
                    startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
                }
            } else {
                startForeground(NID, n);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startForeground", e);
            return false;
        }
    }

    @Override
    public void onTimeout(int startId) {
        end(true); // Android 14+: tempo do serviço curto acabou
    }

    private PendingIntent svc(String act, int code) {
        return PendingIntent.getService(this, code, new Intent(this, AlarmService.class).setAction(act),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification(JSONObject a) {
        Reminders.ensureChannels(this);
        Intent screen = new Intent(this, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent full = PendingIntent.getActivity(this, 7, screen, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, Reminders.CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setColor(Reminders.COLOR)
                .setContentTitle(Reminders.titleOf(a))
                .setContentText(Reminders.alarmLine(a))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setDeleteIntent(svc(ACT_STOP, 11))
                .addAction(0, "Parar", svc(ACT_STOP, 12))
                .addAction(0, "Adiar " + Reminders.SNOOZE_MIN + " min", svc(ACT_SNOOZE, 13))
                .addAction(0, "Concluída", svc(ACT_DONE, 14))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private static AudioAttributes alarmAttrs() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    }

    private void playSound() {
        AudioAttributes attrs = alarmAttrs();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            if (audio != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build();
                    audio.requestAudioFocus(focus);
                } else {
                    //noinspection deprecation
                    audio.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
            }
        } catch (Exception ignored) {
        }
        Uri u = Reminders.alarmUri(this);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs);
            player.setDataSource(this, u);
            player.setLooping(true);
            player.prepare();
            player.start();
            return;
        } catch (Exception e) {
            Log.w(TAG, "som do alarme", e);
            releasePlayer();
        }
        // reserva: toque do celular pelo Ringtone (alguns aparelhos não abrem o som de alarme pelo MediaPlayer)
        for (int type : new int[]{RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION}) {
            try {
                Ringtone r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(type));
                if (r == null) continue;
                r.setAudioAttributes(attrs);
                if (Build.VERSION.SDK_INT >= 28) r.setLooping(true);
                r.play();
                ringtone = r;
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private void vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                vibrator = vm == null ? null : vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(VibrationEffect.createWaveform(Reminders.VIB_ALARM, 0),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            } else if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(Reminders.VIB_ALARM, 0), alarmAttrs());
            } else {
                //noinspection deprecation
                vibrator.vibrate(Reminders.VIB_ALARM, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrar", e);
        }
    }

    private void releasePlayer() {
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    private void stopSound() {
        releasePlayer();
        if (ringtone != null) {
            try {
                ringtone.stop();
            } catch (Exception ignored) {
            }
            ringtone = null;
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
            vibrator = null;
        }
        if (audio != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26 && focus != null) audio.abandonAudioFocusRequest(focus);
                else //noinspection deprecation
                    audio.abandonAudioFocus(null);
            } catch (Exception ignored) {
            }
            focus = null;
        }
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wake == null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "naoesquece:alarme");
                wake.setReferenceCounted(false);
            }
            wake.acquire(MAX_MS + 15_000L);
        } catch (Exception ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
    }

    private void end(boolean missed) {
        h.removeCallbacks(timeout);
        JSONObject a = current;
        current = null;
        stopSound();
        if (missed && a != null) Reminders.postMissed(this, a);
        releaseWake();
        sendBroadcast(new Intent(BC_CHANGE).setPackage(getPackageName()));
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(timeout);
        stopSound();
        releaseWake();
        if (current != null) {
            current = null;
            sendBroadcast(new Intent(BC_CHANGE).setPackage(getPackageName()));
        }
        super.onDestroy();
    }
}
