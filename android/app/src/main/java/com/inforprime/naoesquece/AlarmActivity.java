package com.inforprime.naoesquece;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/** Tela do alarme: aparece por cima da tela bloqueada, com Parar, Adiar e Concluída. */
public class AlarmActivity extends AppCompatActivity {
    private static final int BG = 0xFF0A1122, INK = 0xFFE4EAF5, MUTED = 0xFF93A0B8, ACC = 0xFF4AB6FF, ACC_INK = 0xFF041330, SOFT = 0xFF14305C;

    private JSONObject a;
    private boolean loud; // alarme de reserva (sem o serviço tocando)
    private TextView clock, title, line, text;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (clock != null) clock.setText(Reminders.hhmm(System.currentTimeMillis()));
            h.postDelayed(this, 10_000L);
        }
    };
    private final BroadcastReceiver change = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (loud) return;
            JSONObject cur = AlarmService.current();
            if (cur == null) finish();
            else {
                a = cur;
                fill();
            }
        }
    };
    private boolean registered;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            //noinspection deprecation
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        if (!pickAlarm(getIntent())) {
            finish();
            return;
        }
        setContentView(build());
        fill();
        ContextCompat.registerReceiver(this, change, new IntentFilter(AlarmService.BC_CHANGE), ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // o alarme só sai pelos botões, para não ficar tocando sem tela
            }
        });
    }

    private boolean pickAlarm(Intent i) {
        JSONObject cur = AlarmService.current();
        if (cur != null) {
            a = cur;
            loud = false;
            return true;
        }
        JSONObject x = Reminders.parse(i == null ? null : i.getStringExtra("a"));
        if (x != null) {
            a = x;
            loud = true;
            return true;
        }
        return false;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (pickAlarm(i)) fill();
    }

    @Override
    protected void onStart() {
        super.onStart();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onStop() {
        h.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(tick);
        if (registered) {
            try {
                unregisterReceiver(change);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int bg, int fg, int h) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(fg);
        b.setStateListAnimator(null);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(18));
        b.setBackground(d);
        b.setMinHeight(dp(h));
        return b;
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(28), dp(56), dp(28), dp(36));

        root.addView(label("Não Esquece · alarme", 15, MUTED, true));
        clock = label("", 76, INK, true);
        clock.setPadding(0, dp(18), 0, dp(6));
        root.addView(clock);

        title = label("", 26, INK, true);
        title.setMaxLines(4);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(10), 0, dp(8));
        root.addView(title);

        line = label("", 16, ACC, true);
        root.addView(line);

        text = label("", 16, MUTED, false);
        text.setMaxLines(4);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, dp(10), 0, 0);
        root.addView(text);

        View space = new View(this);
        root.addView(space, new LinearLayout.LayoutParams(1, 0, 1f));

        Button stop = button("Parar", ACC, ACC_INK, 64);
        stop.setOnClickListener(v -> op("stop", "Alarme parado"));
        root.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        Button snooze = button("Adiar " + Reminders.SNOOZE_MIN + " min", SOFT, INK, 56);
        snooze.setOnClickListener(v -> op("snooze", "Toca de novo às " + Reminders.hhmm(System.currentTimeMillis() + Reminders.SNOOZE_MIN * 60_000L)));
        Button done = button("Concluída", SOFT, INK, 56);
        done.setOnClickListener(v -> op("done", "Pronto. A nota fica como concluída quando você abrir o app."));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(snooze, half);
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        row.addView(done, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button open = button("Abrir a nota", BG, ACC, 52);
        open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        open.setOnClickListener(v -> openNote());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(open, lp);
        return root;
    }

    private void fill() {
        if (a == null || title == null) return;
        clock.setText(Reminders.hhmm(System.currentTimeMillis()));
        title.setText(Reminders.titleOf(a));
        line.setText(a.optBoolean("test") ? "Teste do alarme" : Reminders.alarmLine(a));
        String t = a.optString("text", "").trim();
        text.setText(t);
        text.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void op(String o, String msg) {
        Reminders.act(this, o, a);
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        finish();
    }

    private void openNote() {
        String id = a == null || a.optBoolean("test") ? "" : a.optString("id");
        Reminders.act(this, "stop", a);
        Intent open = Reminders.openIntent(this, id);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    startActivity(open);
                    finish();
                }

                @Override
                public void onDismissCancelled() {
                    finish();
                }

                @Override
                public void onDismissError() {
                    finish();
                }
            });
        } else {
            startActivity(open);
            finish();
        }
    }
}
