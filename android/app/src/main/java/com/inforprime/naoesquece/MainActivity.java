package com.inforprime.naoesquece;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.StatusBarManager;
import android.appwidget.AppWidgetManager;
import android.graphics.drawable.Icon;
import android.content.ComponentName;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Tela principal do Não Esquece. As telas rodam dentro do próprio app (WebView do Android),
 * sem abrir o Chrome. O que o celular faz melhor fica aqui no código nativo:
 * login do Google pela tela de contas do Android, digital/rosto, câmera, galeria,
 * microfone, ditado, salvar em Downloads e compartilhar, alarmes dos lembretes e calendário do celular.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "NaoEsquece";
    private static final int BG = 0xFF0A1122;
    private static final String BIO_KEY = "ne_bio";

    private WebView web;
    private View offline;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService media = Executors.newSingleThreadExecutor(); // MP3 (pode demorar)
    private volatile String pageUrl = "";

    private ValueCallback<Uri[]> fileCb;
    private Uri captureUri;
    private File captureFile;
    private PermissionRequest micRequest;
    private String authId, authScopes, authAccount;
    private String dictId;
    private String notifId, calId, scanId;

    private ActivityResultLauncher<Intent> fileLauncher, accountLauncher, dictLauncher;
    private ActivityResultLauncher<IntentSenderRequest> authLauncher, scanLauncher;
    private ActivityResultLauncher<String> micLauncher, notifLauncher;
    private ActivityResultLauncher<String[]> calLauncher;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("nao_esquece", MODE_PRIVATE);
        registerLaunchers();
        io.execute(() -> {
            cleanOld(new File(getCacheDir(), "share"));
            cleanOld(new File(getCacheDir(), "captures"));
            // "Forçar parada" e alguns otimizadores de bateria apagam os alarmes: ao abrir, marca tudo de novo
            Reminders.rescheduleAll(getApplicationContext(), false);
        });

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        offline = buildOffline();
        offline.setVisibility(View.GONE);
        root.addView(offline, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " NaoEsquecApp/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Bridge(), "NativeApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            Uri u = Uri.parse(url);
            String sc = u.getScheme() == null ? "" : u.getScheme();
            if (sc.equals("https") || sc.equals("http")) Links.openExternal(this, u);
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (offline.getVisibility() == View.VISIBLE) { finish(); return; }
                if (web.canGoBack()) web.goBack();
                else moveTaskToBack(true);
            }
        });

        boolean fromAlarm = getIntent() != null && getIntent().getData() != null && getIntent().getData().getQueryParameter("nota") != null;
        if (!fromAlarm && state != null && web.restoreState(state) != null) return;
        web.loadUrl(startUrl(getIntent()));
    }

    private static String startUrl(Intent i) {
        Uri d = i == null ? null : i.getData();
        if (d != null && Links.isApp(d)) return d.toString();
        return Links.BASE;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri d = intent == null ? null : intent.getData();
        if (d != null && Links.isApp(d) && "1".equals(d.getQueryParameter("nova")) && web != null) {
            web.evaluateJavascript("try{ if(window.__newFromNative) __newFromNative(); else if(appReady&&!locked&&document.querySelector('#sheet').hidden) newNote(); }catch(e){}", null);
        }
        if (d != null && Links.isApp(d) && "1".equals(d.getQueryParameter("hoje")) && web != null) {
            web.evaluateJavascript("try{ window.__todayFromNative&&__todayFromNative(); }catch(e){}", null);
        }
        String nota = d != null && Links.isApp(d) ? d.getQueryParameter("nota") : null;
        if (nota != null && web != null) {
            web.evaluateJavascript("try{ window.__openFromNative&&window.__openFromNative(" + JSONObject.quote(nota) + "); }catch(e){}", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.onResume();
    }

    @Override
    protected void onStop() {
        if (web != null) web.onPause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        io.shutdown();
        super.onDestroy();
    }

    /* ---------- tela sem internet (só na primeira abertura) ---------- */
    private View buildOffline() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(BG);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        box.setClickable(true);

        TextView h = new TextView(this);
        h.setText("Sem internet");
        h.setTextColor(0xFFE4EAF5);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setGravity(Gravity.CENTER);
        box.addView(h);

        TextView p = new TextView(this);
        p.setText("Na primeira vez, o Não Esquece precisa de internet para carregar. Depois disso, ele abre mesmo sem conexão.");
        p.setTextColor(0xFF93A0B8);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        p.setGravity(Gravity.CENTER);
        p.setPadding(0, dp(10), 0, dp(24));
        box.addView(p);

        Button b = new Button(this);
        b.setText("Tentar de novo");
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            offline.setVisibility(View.GONE);
            web.loadUrl(Links.BASE);
        });
        box.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /* ---------- resposta para a página ---------- */
    private void done(String id, boolean ok, String data) {
        final String js = "window.__nativeDone&&window.__nativeDone(" + JSONObject.quote(id == null ? "" : id) + ","
                + ok + "," + JSONObject.quote(data == null ? "" : data) + ")";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    /* ---------- resultados de outros apps ---------- */
    private void registerLaunchers() {
        fileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            ValueCallback<Uri[]> cb = fileCb;
            fileCb = null;
            Uri cap = captureUri;
            File capFile = captureFile;
            captureUri = null;
            captureFile = null;
            if (cb == null) return;
            Uri[] out = null;
            if (res.getResultCode() == RESULT_OK) {
                if (cap != null) {
                    if (capFile != null && capFile.length() > 0) out = new Uri[]{cap};
                } else {
                    Intent d = res.getData();
                    List<Uri> list = new ArrayList<>();
                    if (d != null && d.getClipData() != null) {
                        ClipData c = d.getClipData();
                        for (int k = 0; k < c.getItemCount(); k++) {
                            Uri x = c.getItemAt(k).getUri();
                            if (x != null) list.add(x);
                        }
                    } else if (d != null && d.getData() != null) {
                        list.add(d.getData());
                    }
                    if (!list.isEmpty()) out = list.toArray(new Uri[0]);
                }
            }
            cb.onReceiveValue(out);
        });

        micLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            PermissionRequest r = micRequest;
            micRequest = null;
            if (r == null) return;
            if (granted) r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else r.deny();
        });

        accountLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            String id = authId;
            authId = null;
            if (id == null) return;
            Intent d = res.getData();
            String name = d == null ? null : d.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (res.getResultCode() != RESULT_OK || TextUtils.isEmpty(name)) { done(id, false, "cancel"); return; }
            authorize(id, authScopes, name, true, false);
        });

        authLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), res -> {
            String id = authId;
            authId = null;
            if (id == null) return;
            if (res.getResultCode() != RESULT_OK) { done(id, false, "cancel"); return; }
            try {
                AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.getData());
                deliverAuth(id, r, authAccount);
            } catch (ApiException e) {
                done(id, false, authError(e));
            }
        });

        notifLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            String id = notifId;
            notifId = null;
            if (id != null) done(id, true, granted ? "ok" : "no");
        });

        calLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
            String id = calId;
            calId = null;
            if (id != null) done(id, true, DeviceCalendar.perm(this) ? "ok" : "no");
        });

        scanLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), res -> {
            String id = scanId;
            scanId = null;
            if (id == null) return;
            if (res.getResultCode() != RESULT_OK) { done(id, false, "cancel"); return; }
            GmsDocumentScanningResult r = GmsDocumentScanningResult.fromActivityResultIntent(res.getData());
            GmsDocumentScanningResult.Pdf pdf = r == null ? null : r.getPdf();
            if (pdf == null) { done(id, false, "erro"); return; }
            Uri u = pdf.getUri();
            int pages = pdf.getPageCount();
            io.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) throw new Exception("pdf");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    JSONObject o = new JSONObject();
                    o.put("b64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                    o.put("pages", pages);
                    done(id, true, o.toString());
                } catch (Exception e) {
                    Log.w(TAG, "scanner", e);
                    done(id, false, "erro");
                }
            });
        });

        dictLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            String id = dictId;
            dictId = null;
            if (id == null) return;
            Intent d = res.getData();
            ArrayList<String> r = d == null ? null : d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (res.getResultCode() != RESULT_OK || r == null || r.isEmpty()) { done(id, false, "cancel"); return; }
            done(id, true, r.get(0));
        });
    }

    /* ---------- login do Google (tela de contas do Android) ---------- */
    private void pickAccount(String id, String scopes) {
        authId = id;
        authScopes = scopes;
        try {
            Intent pick = AccountPicker.newChooseAccountIntent(new AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(Collections.singletonList("com.google"))
                    .setAlwaysShowAccountPicker(true)
                    .build());
            accountLauncher.launch(pick);
        } catch (Exception e) {
            authId = null;
            done(id, false, "sem_contas");
        }
    }

    private void authorize(String id, String scopes, String account, boolean ui, boolean canPick) {
        List<Scope> list = new ArrayList<>();
        for (String sc : scopes.trim().split("\\s+")) if (!sc.isEmpty()) list.add(new Scope(sc));
        AuthorizationRequest req = AuthorizationRequest.builder()
                .setRequestedScopes(list)
                .setAccount(new Account(account, "com.google"))
                .build();
        Identity.getAuthorizationClient(this).authorize(req)
                .addOnSuccessListener(this, r -> {
                    if (r.hasResolution()) {
                        if (!ui) { done(id, false, "needs_ui"); return; }
                        PendingIntent pi = r.getPendingIntent();
                        if (pi == null) { done(id, false, "ui"); return; }
                        authId = id;
                        authAccount = account;
                        authScopes = scopes;
                        try {
                            authLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
                        } catch (Exception e) {
                            authId = null;
                            done(id, false, "ui");
                        }
                    } else {
                        deliverAuth(id, r, account);
                    }
                })
                .addOnFailureListener(this, e -> {
                    Log.w(TAG, "authorize", e);
                    String err = authError(e);
                    if (ui && canPick && !err.equals("network") && !err.equals("cancel") && !err.equals("config")) {
                        pickAccount(id, scopes); // a conta guardada não está mais no celular: escolhe outra
                    } else {
                        done(id, false, err);
                    }
                });
    }

    private void deliverAuth(String id, AuthorizationResult r, String account) {
        String tk = r == null ? null : r.getAccessToken();
        if (TextUtils.isEmpty(tk)) { done(id, false, "sem_token"); return; }
        if (!TextUtils.isEmpty(account)) prefs.edit().putString("account", account).apply();
        try {
            JSONObject o = new JSONObject();
            o.put("token", tk);
            o.put("email", account == null ? "" : account);
            List<String> g = r.getGrantedScopes();
            o.put("scopes", g == null ? "" : TextUtils.join(" ", g));
            done(id, true, o.toString());
        } catch (Exception e) {
            done(id, false, "json");
        }
    }

    private static String authError(Exception e) {
        if (e instanceof ApiException) {
            int c = ((ApiException) e).getStatusCode();
            if (c == CommonStatusCodes.DEVELOPER_ERROR) return "config";
            if (c == CommonStatusCodes.NETWORK_ERROR) return "network";
            if (c == CommonStatusCodes.CANCELED || c == 12501) return "cancel";
            if (c == CommonStatusCodes.SIGN_IN_REQUIRED) return "needs_ui";
            return "erro_" + c;
        }
        return "erro";
    }

    /* ---------- digital ou rosto ---------- */
    private interface BioDone {
        void run(Cipher c, String err);
    }

    private String bioState() {
        int r = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if (r == BiometricManager.BIOMETRIC_SUCCESS) return "ok";
        if (r == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) return "none";
        return "unsupported";
    }

    private void bioPrompt(BiometricPrompt.CryptoObject co, String title, BioDone cb) {
        BiometricPrompt bp = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                BiometricPrompt.CryptoObject o = r.getCryptoObject();
                Cipher c = o == null ? null : o.getCipher();
                cb.run(c, c == null ? "cripto" : null);
            }

            @Override
            public void onAuthenticationError(int code, CharSequence msg) {
                String e;
                if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED) e = "cancel";
                else if (code == BiometricPrompt.ERROR_LOCKOUT || code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) e = "lockout";
                else e = "erro_" + code;
                cb.run(null, e);
            }
        });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use a digital ou o rosto")
                .setNegativeButtonText("Usar o PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        bp.authenticate(info, co);
    }

    private void bioClear() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(BIO_KEY)) ks.deleteEntry(BIO_KEY);
        } catch (Exception ignored) {
        }
        prefs.edit().remove("bio_iv").remove("bio_ct").apply();
    }

    private void bioEnable(String id, String secretB64) {
        try {
            bioClear();
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(BIO_KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true);
            if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            kg.init(b.build());
            SecretKey key = kg.generateKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            bioPrompt(new BiometricPrompt.CryptoObject(c), "Ativar digital ou rosto", (ci, err) -> {
                if (ci == null) { done(id, false, err); return; }
                try {
                    byte[] ct = ci.doFinal(Base64.decode(secretB64, Base64.NO_WRAP));
                    prefs.edit()
                            .putString("bio_iv", Base64.encodeToString(ci.getIV(), Base64.NO_WRAP))
                            .putString("bio_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                            .apply();
                    done(id, true, "");
                } catch (Exception e) {
                    done(id, false, "cripto");
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "bioEnable", e);
            done(id, false, "chave");
        }
    }

    private void bioUnlock(String id) {
        String iv = prefs.getString("bio_iv", null), ct = prefs.getString("bio_ct", null);
        if (iv == null || ct == null) { done(id, false, "off"); return; }
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(BIO_KEY, null);
            if (key == null) { bioClear(); done(id, false, "invalidated"); return; }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            bioPrompt(new BiometricPrompt.CryptoObject(c), "Desbloquear o Não Esquece", (ci, err) -> {
                if (ci == null) { done(id, false, err); return; }
                try {
                    done(id, true, Base64.encodeToString(ci.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Base64.NO_WRAP));
                } catch (Exception e) {
                    done(id, false, "cripto");
                }
            });
        } catch (KeyPermanentlyInvalidatedException e) {
            bioClear(); // digital nova cadastrada no celular: a chave antiga deixa de valer
            done(id, false, "invalidated");
        } catch (Exception e) {
            Log.w(TAG, "bioUnlock", e);
            done(id, false, "chave");
        }
    }

    /* ---------- arquivos ---------- */
    private static String safeName(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "").trim();
        if (s.length() > 90) s = s.substring(s.length() - 90);
        return s.isEmpty() ? "arquivo" : s;
    }

    private static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void cleanOld(File base) {
        File[] ds = base.listFiles();
        if (ds == null) return;
        long lim = System.currentTimeMillis() - 86400000L;
        for (File d : ds) if (d.lastModified() < lim) deleteTree(d);
    }

    private File writeShareFile(String name, byte[] data) throws Exception {
        File dir = new File(new File(getCacheDir(), "share"), String.valueOf(System.nanoTime()));
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("pasta");
        File f = new File(dir, safeName(name));
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
        return f;
    }

    private static String commonType(String a, String b) {
        if (a == null) return b;
        if (a.equals(b)) return a;
        int i = a.indexOf('/'), j = b.indexOf('/');
        if (i > 0 && j > 0 && a.substring(0, i).equals(b.substring(0, j))) return a.substring(0, i) + "/*";
        return "*/*";
    }

    /** WhatsApp instalado (o normal ou o Business). Com os dois, deixa a pessoa escolher. */
    private String whatsApp() {
        String found = null;
        for (String p : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try {
                getPackageManager().getPackageInfo(p, 0);
                if (found != null) return null;
                found = p;
            } catch (Exception ignored) {
            }
        }
        return found;
    }

    private void startShare(String title, String text, ArrayList<Uri> uris, String type, String pkg) {
        Intent i;
        if (uris.isEmpty()) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            if (!TextUtils.isEmpty(title)) i.putExtra(Intent.EXTRA_SUBJECT, title);
        } else if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType(type == null ? "*/*" : type);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            if (!TextUtils.isEmpty(text)) i.putExtra(Intent.EXTRA_TEXT, text);
            i.setClipData(ClipData.newRawUri("", uris.get(0)));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType(type == null ? "*/*" : type);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            if (!TextUtils.isEmpty(text)) i.putExtra(Intent.EXTRA_TEXT, text);
            ClipData c = ClipData.newRawUri("", uris.get(0));
            for (int k = 1; k < uris.size(); k++) c.addItem(new ClipData.Item(uris.get(k)));
            i.setClipData(c);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (pkg != null) {
            try {
                startActivity(new Intent(i).setPackage(pkg));
                return;
            } catch (Exception e) {
                Log.w(TAG, "whatsapp direto", e);
            }
        }
        startActivity(Intent.createChooser(i, null));
    }

    private Intent captureIntent(String action, String prefix, String ext) throws Exception {
        File dir = new File(getCacheDir(), "captures");
        if (!dir.mkdirs() && !dir.isDirectory()) throw new Exception("pasta");
        captureFile = new File(dir, prefix + "-" + System.currentTimeMillis() + ext);
        captureUri = FileProvider.getUriForFile(this, getPackageName() + ".files", captureFile);
        Intent i = new Intent(action);
        i.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri("", captureUri));
        return i;
    }

    private Intent pickIntent(String[] types, boolean multi) {
        List<String> mimes = new ArrayList<>();
        boolean any = false;
        if (types != null) for (String t : types) {
            if (t == null) continue;
            t = t.trim().toLowerCase();
            if (t.isEmpty()) continue;
            String m = t.startsWith(".") ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.substring(1)) : t;
            if (m == null || m.equals("application/json")) { any = true; continue; }
            if (!mimes.contains(m)) mimes.add(m);
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (any || mimes.isEmpty()) i.setType("*/*");
        else if (mimes.size() == 1) i.setType(mimes.get(0));
        else {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return i;
    }

    private void applyBars(boolean dark, String color) {
        int c;
        try {
            c = Color.parseColor(color);
        } catch (Exception e) {
            c = dark ? BG : 0xFFEEF2F8;
        }
        getWindow().setStatusBarColor(c);
        getWindow().setNavigationBarColor(c);
        if (web != null) web.setBackgroundColor(c);
        WindowInsetsControllerCompat w = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        w.setAppearanceLightStatusBars(!dark);
        w.setAppearanceLightNavigationBars(!dark);
    }

    /* ---------- permissões dos alarmes e do calendário ---------- */
    private Intent notifSettings() {
        if (Build.VERSION.SDK_INT >= 26) return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
    }

    private boolean openSettings(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private void ask(String id, String which) {
        Uri pkg = Uri.parse("package:" + getPackageName());
        try {
            switch (which == null ? "" : which) {
                case "notif":
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifId = id;
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                        return;
                    }
                    if (NotificationManagerCompat.from(this).areNotificationsEnabled()) { done(id, true, "ok"); return; }
                    openSettings(notifSettings());
                    done(id, true, "settings");
                    return;
                case "notifSettings":
                    openSettings(notifSettings());
                    done(id, true, "settings");
                    return;
                case "cal":
                    if (DeviceCalendar.perm(this)) { done(id, true, "ok"); return; }
                    calId = id;
                    calLauncher.launch(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR});
                    return;
                case "exact":
                    if (Build.VERSION.SDK_INT >= 31 && !Reminders.exactOk(this)) {
                        openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg));
                        done(id, true, "settings");
                    } else done(id, true, "ok");
                    return;
                case "full":
                    if (Build.VERSION.SDK_INT >= 34) {
                        openSettings(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg));
                        done(id, true, "settings");
                    } else done(id, true, "ok");
                    return;
                case "battery":
                    @SuppressLint("BatteryLife") Intent b = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
                    try {
                        startActivity(b);
                    } catch (Exception e) {
                        openSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    done(id, true, "settings");
                    return;
                case "sync":
                    openSettings(new Intent(Settings.ACTION_SYNC_SETTINGS));
                    done(id, true, "settings");
                    return;
                case "app":
                    openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
                    done(id, true, "settings");
                    return;
                default:
                    done(id, false, "desconhecido");
            }
        } catch (Exception e) {
            Log.w(TAG, "permissão", e);
            done(id, false, "erro");
        }
    }

    /* ---------- navegação ---------- */
    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            if (!r.isForMainFrame()) return false;
            Uri u = r.getUrl();
            if (Links.isApp(u)) return false;
            if (Links.isOwnPage(u)) { Links.openPage(MainActivity.this, u); return true; }
            String sc = u.getScheme() == null ? "" : u.getScheme();
            if (sc.equals("blob") || sc.equals("data") || sc.equals("about") || sc.equals("javascript")) return true;
            Links.openExternal(MainActivity.this, u);
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
            pageUrl = url == null ? "" : url;
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            pageUrl = url == null ? "" : url;
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (r.isForMainFrame()) offline.setVisibility(View.VISIBLE);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            captureUri = null;
            captureFile = null;
            String[] types = p.getAcceptTypes();
            String acc = types == null ? "" : TextUtils.join(",", types).toLowerCase();
            boolean capture = p.isCaptureEnabled();
            try {
                Intent i;
                if (capture && acc.contains("image")) i = captureIntent(MediaStore.ACTION_IMAGE_CAPTURE, "foto", ".jpg");
                else if (capture && acc.contains("video")) i = captureIntent(MediaStore.ACTION_VIDEO_CAPTURE, "video", ".mp4");
                else if (capture && acc.contains("audio")) i = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
                else i = pickIntent(types, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                fileLauncher.launch(i);
            } catch (Exception e) {
                Log.w(TAG, "fileChooser", e);
                captureUri = null;
                captureFile = null;
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = null;
                Toast.makeText(MainActivity.this, "Nenhum app do celular faz isso.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest req) {
            runOnUiThread(() -> {
                Uri o = req.getOrigin();
                boolean ours = o != null && Links.HOST.equals(o.getHost());
                boolean audio = false;
                for (String r : req.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                if (!ours || !audio) { req.deny(); return; }
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    micRequest = req;
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO);
                }
            });
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest req) {
            if (micRequest == req) micRequest = null;
        }
    }

    /* ---------- ponte com a página (window.NativeApp) ---------- */
    private class Bridge {
        private boolean trusted() {
            return pageUrl.startsWith(Links.BASE);
        }

        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("app", "android");
                o.put("version", BuildConfig.VERSION_NAME);
                o.put("code", BuildConfig.VERSION_CODE);
                o.put("sdk", Build.VERSION.SDK_INT);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void signIn(String id, String scopes, String hint, String interactive) {
            if (!trusted()) { done(id, false, "origem"); return; }
            runOnUiThread(() -> {
                boolean ui = "1".equals(interactive);
                String acc = !TextUtils.isEmpty(hint) ? hint : prefs.getString("account", "");
                if (TextUtils.isEmpty(acc)) {
                    if (!ui) { done(id, false, "needs_ui"); return; }
                    pickAccount(id, scopes);
                    return;
                }
                authorize(id, scopes, acc, ui, true);
            });
        }

        @JavascriptInterface
        public void clearToken(String token) {
            if (TextUtils.isEmpty(token)) return;
            io.execute(() -> {
                try {
                    GoogleAuthUtil.clearToken(getApplicationContext(), token);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void signOut(String token) {
            prefs.edit().remove("account").apply();
            clearToken(token);
        }

        @JavascriptInterface
        public String bioState() {
            return MainActivity.this.bioState();
        }

        @JavascriptInterface
        public void bioEnable(String id, String secretB64) {
            if (!trusted()) { done(id, false, "origem"); return; }
            runOnUiThread(() -> MainActivity.this.bioEnable(id, secretB64));
        }

        @JavascriptInterface
        public void bioUnlock(String id) {
            if (!trusted()) { done(id, false, "origem"); return; }
            runOnUiThread(() -> MainActivity.this.bioUnlock(id));
        }

        @JavascriptInterface
        public void bioDisable() {
            bioClear();
        }

        @JavascriptInterface
        public void saveFile(String id, String name, String mime, String b64) {
            if (!trusted()) { done(id, false, "origem"); return; }
            io.execute(() -> {
                try {
                    byte[] data = Base64.decode(b64, Base64.DEFAULT);
                    String safe = safeName(name);
                    String type = TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, type);
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Não Esquece");
                        Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (u == null) throw new Exception("insert");
                        try (OutputStream os = getContentResolver().openOutputStream(u)) {
                            if (os == null) throw new Exception("stream");
                            os.write(data);
                        }
                        done(id, true, "Download/Não Esquece/" + safe);
                    } else {
                        // Android 9 ou anterior: entrega pelo "Compartilhar" (salvar no Drive, enviar etc.)
                        File f = writeShareFile(safe, data);
                        ArrayList<Uri> uris = new ArrayList<>();
                        uris.add(FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f));
                        runOnUiThread(() -> startShare(safe, "", uris, type, null));
                        done(id, true, "share");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "saveFile", e);
                    done(id, false, "erro");
                }
            });
        }

        @JavascriptInterface
        public void share(String id, String json) {
            if (!trusted()) { done(id, false, "origem"); return; }
            io.execute(() -> {
                try {
                    JSONObject o = new JSONObject(json);
                    String text = o.optString("text", "");
                    String title = o.optString("title", "");
                    String app = o.optString("app", "");
                    JSONArray fs = o.optJSONArray("files");
                    ArrayList<Uri> uris = new ArrayList<>();
                    String type = null;
                    if (fs != null) for (int k = 0; k < fs.length(); k++) {
                        JSONObject f = fs.getJSONObject(k);
                        String mt = f.optString("type", "");
                        if (TextUtils.isEmpty(mt)) mt = "application/octet-stream";
                        File file = writeShareFile(f.optString("name", "arquivo"), Base64.decode(f.getString("b64"), Base64.DEFAULT));
                        uris.add(FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", file));
                        type = commonType(type, mt);
                    }
                    final String t = type;
                    runOnUiThread(() -> {
                        try {
                            startShare(title, text, uris, t, "whatsapp".equals(app) ? whatsApp() : null);
                            done(id, true, "");
                        } catch (Exception e) {
                            done(id, false, "erro");
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "share", e);
                    done(id, false, "erro");
                }
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> {
                Uri u = Uri.parse(url);
                if (Links.isApp(u)) return;
                if (Links.isOwnPage(u)) Links.openPage(MainActivity.this, u);
                else Links.openExternal(MainActivity.this, u);
            });
        }

        @JavascriptInterface
        public void dictate(String id) {
            if (!trusted()) { done(id, false, "origem"); return; }
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale agora");
                dictId = id;
                try {
                    dictLauncher.launch(i);
                } catch (Exception e) {
                    dictId = null;
                    done(id, false, "indisponivel");
                }
            });
        }

        /* alarmes dos lembretes */
        @JavascriptInterface
        public String alarmSet(String json) {
            if (!trusted()) return "-1";
            return String.valueOf(Reminders.sync(getApplicationContext(), json));
        }

        @JavascriptInterface
        public String alarmState() {
            return Reminders.state(getApplicationContext()).toString();
        }

        @JavascriptInterface
        public void alarmAsk(String id, String which) {
            if (!trusted()) { done(id, false, "origem"); return; }
            runOnUiThread(() -> ask(id, which));
        }

        @JavascriptInterface
        public void alarmTest(String mode) {
            if (!trusted()) return;
            Reminders.test(getApplicationContext(), mode);
        }

        @JavascriptInterface
        public String alarmEvents() {
            if (!trusted()) return "[]";
            return Reminders.takeEvents(getApplicationContext());
        }

        @JavascriptInterface
        public void alarmClear() {
            if (!trusted()) return;
            Reminders.clearAll(getApplicationContext());
        }

        /* escanear documento (scanner do Google) */
        @JavascriptInterface
        public void scanDoc(String id) {
            if (!trusted()) { done(id, false, "origem"); return; }
            runOnUiThread(() -> {
                try {
                    GmsDocumentScannerOptions o = new GmsDocumentScannerOptions.Builder()
                            .setGalleryImportAllowed(true)
                            .setPageLimit(30)
                            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                            .build();
                    GmsDocumentScanning.getClient(o).getStartScanIntent(MainActivity.this)
                            .addOnSuccessListener(MainActivity.this, sender -> {
                                scanId = id;
                                try {
                                    scanLauncher.launch(new IntentSenderRequest.Builder(sender).build());
                                } catch (Exception e) {
                                    scanId = null;
                                    done(id, false, "indisponivel");
                                }
                            })
                            .addOnFailureListener(MainActivity.this, e -> {
                                Log.w(TAG, "scanner", e);
                                done(id, false, "indisponivel");
                            });
                } catch (Exception e) {
                    done(id, false, "indisponivel");
                }
            });
        }

        /* abrir um anexo (PDF) no app certo do celular */
        @JavascriptInterface
        public void viewFile(String id, String name, String mime, String b64) {
            if (!trusted()) { done(id, false, "origem"); return; }
            io.execute(() -> {
                try {
                    File f = writeShareFile(name, Base64.decode(b64, Base64.DEFAULT));
                    Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, TextUtils.isEmpty(mime) ? "application/pdf" : mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setClipData(ClipData.newRawUri("", u));
                    runOnUiThread(() -> {
                        try {
                            startActivity(i);
                            done(id, true, "");
                        } catch (Exception e) {
                            done(id, false, "sem_app");
                        }
                    });
                } catch (Exception e) {
                    done(id, false, "erro");
                }
            });
        }

        /* áudio em MP3 (para tocar no PDF, no WhatsApp e no Word) */
        @JavascriptInterface
        public void toMp3(String id, String mime, String b64) {
            if (!trusted()) { done(id, false, "origem"); return; }
            media.execute(() -> {
                File dir = new File(getCacheDir(), "mp3");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                long k = System.nanoTime();
                File in = new File(dir, "in-" + k), out = new File(dir, "out-" + k + ".mp3");
                try {
                    try (FileOutputStream os = new FileOutputStream(in)) {
                        os.write(Base64.decode(b64, Base64.DEFAULT));
                    }
                    double dur = Mp3.convert(in, out);
                    byte[] data = new byte[(int) out.length()];
                    try (java.io.FileInputStream is = new java.io.FileInputStream(out)) {
                        int o = 0, n;
                        while (o < data.length && (n = is.read(data, o, data.length - o)) > 0) o += n;
                    }
                    JSONObject r = new JSONObject();
                    r.put("dur", dur);
                    r.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
                    done(id, true, r.toString());
                } catch (Throwable e) {
                    Log.w(TAG, "mp3 " + mime, e);
                    done(id, false, "erro");
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    in.delete();
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
            });
        }

        @JavascriptInterface
        public String mp3Info() {
            return Mp3.available() ? Mp3.lameVersion() : "";
        }

        /* ler o texto de fotos e PDFs (no celular, sem internet) */
        @JavascriptInterface
        public void ocr(String id, String mime, String b64) {
            if (!trusted()) { done(id, false, "origem"); return; }
            io.execute(() -> {
                try {
                    done(id, true, Ocr.run(getApplicationContext(), mime, Base64.decode(b64, Base64.DEFAULT)));
                } catch (Throwable e) {
                    Log.w(TAG, "ocr", e);
                    done(id, false, "erro");
                }
            });
        }

        /* nota rápida e áudio feitos pelo bloco das configurações rápidas ou pelo widget */
        @JavascriptInterface
        public String quickList() {
            if (!trusted()) return "[]";
            return Quick.list(getApplicationContext());
        }

        @JavascriptInterface
        public void quickData(String id, String qid) {
            if (!trusted()) { done(id, false, "origem"); return; }
            io.execute(() -> {
                try {
                    done(id, true, Quick.data(getApplicationContext(), qid));
                } catch (Exception e) {
                    done(id, false, "erro");
                }
            });
        }

        @JavascriptInterface
        public void quickDone(String qid) {
            if (!trusted()) return;
            Quick.remove(getApplicationContext(), qid);
        }

        @JavascriptInterface
        public void quickOpen(String mode) {
            if (!trusted()) return;
            runOnUiThread(() -> startActivity(QuickActivity.intent(MainActivity.this, mode)));
        }

        /* colocar os botões nas configurações rápidas (Android 13 ou mais novo pergunta direto) */
        @JavascriptInterface
        public void tileAdd(String id, String which) {
            if (!trusted()) { done(id, false, "origem"); return; }
            if (Build.VERSION.SDK_INT < 33) { done(id, true, "manual"); return; }
            runOnUiThread(() -> {
                try {
                    boolean audio = "audio".equals(which);
                    StatusBarManager sb = getSystemService(StatusBarManager.class);
                    sb.requestAddTileService(new ComponentName(MainActivity.this, audio ? QuickAudioTile.class : QuickNoteTile.class),
                            audio ? "Gravar áudio" : "Nota rápida",
                            Icon.createWithResource(MainActivity.this, audio ? R.drawable.ic_tile_mic : R.drawable.ic_tile_note),
                            getMainExecutor(), r -> done(id, true, String.valueOf(r)));
                } catch (Exception e) {
                    done(id, true, "manual");
                }
            });
        }

        /* widget da tela inicial */
        @JavascriptInterface
        public String widgetPin() {
            if (Build.VERSION.SDK_INT < 26) return "no";
            AppWidgetManager m = getSystemService(AppWidgetManager.class);
            if (m == null || !m.isRequestPinAppWidgetSupported()) return "no";
            runOnUiThread(() -> {
                try {
                    m.requestPinAppWidget(new ComponentName(MainActivity.this, TodayWidget.class), null, null);
                } catch (Exception ignored) {
                }
            });
            return "ok";
        }

        @JavascriptInterface
        public int widgetCount() {
            return TodayWidget.count(getApplicationContext());
        }

        /* calendário do celular */
        @JavascriptInterface
        public String calInfo(String email) {
            if (!trusted()) return "{}";
            return DeviceCalendar.info(getApplicationContext(), email).toString();
        }

        @JavascriptInterface
        public void calSync(String id, String email, String json) {
            if (!trusted()) { done(id, false, "origem"); return; }
            io.execute(() -> done(id, true, DeviceCalendar.sync(getApplicationContext(), email, json).toString()));
        }

        @JavascriptInterface
        public void calWipe(String id) {
            if (!trusted()) { done(id, false, "origem"); return; }
            io.execute(() -> done(id, true, String.valueOf(DeviceCalendar.wipe(getApplicationContext()))));
        }

        @JavascriptInterface
        public void setTheme(String dark, String color) {
            runOnUiThread(() -> applyBars("1".equals(dark), color));
        }
    }
}
