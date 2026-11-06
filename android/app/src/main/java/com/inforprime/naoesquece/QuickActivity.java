package com.inforprime.naoesquece;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;

/**
 * Nota rápida e gravar áudio sem abrir o app: uma janela pequena por cima do que estiver na tela.
 * O que for salvo aqui vira uma nota nova quando o Não Esquece abrir (e desbloquear).
 */
public class QuickActivity extends AppCompatActivity {
    static final String EXTRA_MODE = "modo";
    private static final long MAX_REC = 30 * 60_000L;

    private String mode = "nota";
    private boolean dark, saved, asking;
    private int bg, ink, muted, acc, accInk, soft, danger;
    private EditText input;
    private TextView timer, status;
    private View dot;
    private Button mainBtn;
    private MediaRecorder rec;
    private File recFile;
    private long recStart;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (rec == null || timer == null) return;
            long s = (System.currentTimeMillis() - recStart) / 1000;
            timer.setText(String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
            h.postDelayed(this, 500);
        }
    };
    private ActivityResultLauncher<String> micPerm;

    static Intent intent(Context c, String mode) {
        Intent i = new Intent(c, QuickActivity.class);
        i.setData(Uri.parse("naoesquece://rapido/" + mode));
        i.putExtra(EXTRA_MODE, mode);
        return i;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String m = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MODE);
        if (m == null && getIntent() != null && getIntent().getData() != null) m = getIntent().getData().getLastPathSegment();
        mode = "audio".equals(m) ? "audio" : "nota";
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF111A2E : 0xFFFFFFFF;
        ink = dark ? 0xFFE4EAF5 : 0xFF121A2B;
        muted = dark ? 0xFF93A0B8 : 0xFF5A6579;
        acc = dark ? 0xFF4AB6FF : 0xFF0A54C8;
        accInk = dark ? 0xFF041330 : 0xFFFFFFFF;
        soft = dark ? 0xFF1C2A45 : 0xFFE2E8F2;
        danger = dark ? 0xFFFF5A6E : 0xFFD4213D;
        micPerm = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            asking = false;
            if (ok) startRec();
            else noMic();
        });
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | (mode.equals("nota") ? WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE : 0));
        setContentView(build());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mode.equals("nota")) saveNote(true);
                else stopAndSave();
            }
        });
        if (mode.equals("audio")) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec();
            else {
                asking = true;
                micPerm.launch(Manifest.permission.RECORD_AUDIO);
            }
        }
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(r));
        return d;
    }

    private Button button(String t, int b, int f) {
        Button x = new Button(this);
        x.setText(t);
        x.setAllCaps(false);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        x.setTypeface(Typeface.DEFAULT_BOLD);
        x.setTextColor(f);
        x.setStateListAnimator(null);
        x.setBackground(round(b, 16));
        x.setMinHeight(dp(52));
        return x;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> {
            if (mode.equals("nota")) saveNote(true);
        });
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(bg, 24));
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setClickable(true);
        card.setElevation(dp(12));

        TextView head = new TextView(this);
        head.setText(mode.equals("nota") ? "Nota rápida" : "Gravar áudio");
        head.setTextColor(ink);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(head);

        TextView sub = new TextView(this);
        sub.setText(mode.equals("nota") ? "Vira uma nota nova quando você abrir o Não Esquece." : "O áudio vira uma nota nova quando você abrir o Não Esquece.");
        sub.setTextColor(muted);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setPadding(0, dp(2), 0, dp(14));
        card.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button no = button(mode.equals("nota") ? "Cancelar" : "Descartar", soft, ink);
        mainBtn = button(mode.equals("nota") ? "Salvar" : "Parar e salvar", acc, accInk);

        if (mode.equals("nota")) {
            input = new EditText(this);
            input.setHint("Escreva aqui…");
            input.setHintTextColor(muted);
            input.setTextColor(ink);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            input.setMinLines(4);
            input.setMaxLines(10);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setBackground(round(soft, 14));
            input.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            no.setOnClickListener(v -> {
                saved = true;
                finish();
            });
            mainBtn.setOnClickListener(v -> saveNote(false));
            input.requestFocus();
        } else {
            LinearLayout rec = new LinearLayout(this);
            rec.setOrientation(LinearLayout.HORIZONTAL);
            rec.setGravity(Gravity.CENTER);
            rec.setPadding(0, dp(6), 0, dp(10));
            dot = new View(this);
            dot.setBackground(round(danger, 9));
            rec.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));
            timer = new TextView(this);
            timer.setText("0:00");
            timer.setTextColor(ink);
            timer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
            timer.setTypeface(Typeface.DEFAULT_BOLD);
            timer.setPadding(dp(14), 0, 0, 0);
            rec.addView(timer);
            card.addView(rec, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            status = new TextView(this);
            status.setText("Gravando…");
            status.setTextColor(muted);
            status.setGravity(Gravity.CENTER);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            card.addView(status);
            no.setOnClickListener(v -> discard());
            mainBtn.setOnClickListener(v -> stopAndSave());
        }

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.setPadding(0, dp(16), 0, 0);
        row.addView(no, half);
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        row.addView(mainBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                mode.equals("nota") ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        lp.setMargins(dp(16), mode.equals("nota") ? dp(72) : 0, dp(16), 0);
        root.addView(card, lp);
        return root;
    }

    /* ---------- nota ---------- */
    private void saveNote(boolean quiet) {
        if (saved) return;
        String t = input == null ? "" : input.getText().toString().trim();
        saved = true;
        if (t.isEmpty()) { finish(); return; }
        try {
            JSONObject o = new JSONObject();
            o.put("id", "q" + System.currentTimeMillis());
            o.put("type", "nota");
            o.put("text", t.length() > 20000 ? t.substring(0, 20000) : t);
            o.put("at", System.currentTimeMillis());
            Quick.add(this, o);
            Toast.makeText(this, "Nota salva. Ela aparece quando você abrir o Não Esquece.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Não consegui salvar a nota.", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /* ---------- áudio ---------- */
    private void noMic() {
        if (status != null) status.setText("O app precisa do microfone para gravar.");
        if (timer != null) timer.setText("—");
        if (mainBtn != null) {
            mainBtn.setText("Permitir");
            mainBtn.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
                saved = true;
                finish();
            });
        }
    }

    @SuppressWarnings("deprecation")
    private void startRec() {
        try {
            recFile = new File(Quick.dir(this), "audio-" + System.currentTimeMillis() + ".m4a");
            rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioEncodingBitRate(64000);
            rec.setAudioSamplingRate(44100);
            rec.setAudioChannels(1);
            rec.setMaxDuration((int) MAX_REC);
            rec.setOnInfoListener((r, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopAndSave();
            });
            rec.setOutputFile(recFile.getAbsolutePath());
            rec.prepare();
            rec.start();
            recStart = System.currentTimeMillis();
            h.post(tick);
            ObjectAnimator a = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f);
            a.setDuration(700);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.start();
        } catch (Exception e) {
            releaseRec();
            if (status != null) status.setText("Não consegui ligar o microfone.");
        }
    }

    private void releaseRec() {
        h.removeCallbacks(tick);
        if (rec == null) return;
        try {
            rec.release();
        } catch (Exception ignored) {
        }
        rec = null;
    }

    private void stopAndSave() {
        if (saved) return;
        saved = true;
        if (rec == null) { finish(); return; }
        long dur = Math.max(1, (System.currentTimeMillis() - recStart) / 1000);
        boolean ok = true;
        try {
            rec.stop();
        } catch (Exception e) {
            ok = false; // gravação curta demais
        }
        releaseRec();
        if (ok && recFile != null && recFile.length() > 0) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", "q" + System.currentTimeMillis());
                o.put("type", "audio");
                o.put("file", recFile.getName());
                o.put("mime", "audio/mp4");
                o.put("dur", dur);
                o.put("at", recStart);
                Quick.add(this, o);
                Toast.makeText(this, "Áudio salvo. Ele aparece quando você abrir o Não Esquece.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Não consegui salvar o áudio.", Toast.LENGTH_SHORT).show();
            }
        } else {
            if (recFile != null) //noinspection ResultOfMethodCallIgnored
                recFile.delete();
            Toast.makeText(this, "A gravação ficou curta demais.", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void discard() {
        saved = true;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
        }
        releaseRec();
        if (recFile != null) //noinspection ResultOfMethodCallIgnored
            recFile.delete();
        finish();
    }

    @Override
    protected void onStop() {
        // saiu da janela (botão Início, ligação etc.): guarda o que já foi feito
        if (!isChangingConfigurations() && !saved && !asking) {
            if (mode.equals("audio")) stopAndSave();
            else saveNote(true);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releaseRec();
        super.onDestroy();
    }
}
