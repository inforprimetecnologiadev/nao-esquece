package com.inforprime.naoesquece;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** Decide onde cada endereço abre: na tela do app, numa página interna ou num app do celular. */
final class Links {
    static final String HOST = "inforprimetecnologiadev.github.io";
    static final String BASE = "https://" + HOST + "/nao-esquece/";

    private Links() {}

    private static String path(Uri u) {
        return u.getPath() == null ? "" : u.getPath();
    }

    private static boolean ours(Uri u) {
        return u != null && "https".equals(u.getScheme()) && HOST.equals(u.getHost());
    }

    /** A tela principal do app. */
    static boolean isApp(Uri u) {
        if (!ours(u)) return false;
        String p = path(u);
        return p.equals("/nao-esquece") || p.equals("/nao-esquece/") || p.equals("/nao-esquece/index.html");
    }

    /** Outra página do Não Esquece (central de ajuda, política, termos). */
    static boolean isOwnPage(Uri u) {
        if (!ours(u)) return false;
        String p = path(u);
        return p.startsWith("/nao-esquece/") && !isApp(u) && !p.endsWith(".apk");
    }

    static void openPage(Context c, Uri u) {
        Intent i = new Intent(c, PageActivity.class);
        i.setData(u);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Abre fora do app: e-mail, Google Drive, WhatsApp, Agenda. */
    static void openExternal(Context c, Uri u) {
        if (u == null) return;
        try {
            String s = u.getScheme() == null ? "" : u.getScheme();
            Intent i;
            if (s.equals("intent")) {
                i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                try {
                    c.startActivity(i);
                    return;
                } catch (ActivityNotFoundException e) {
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb == null) throw e;
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(fb));
                }
            } else if (s.equals("mailto")) {
                i = new Intent(Intent.ACTION_SENDTO, u);
            } else {
                i = new Intent(Intent.ACTION_VIEW, u);
            }
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "Nenhum app do celular abre este link.", Toast.LENGTH_SHORT).show();
        }
    }
}
