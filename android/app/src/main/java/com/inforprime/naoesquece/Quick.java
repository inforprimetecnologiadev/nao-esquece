package com.inforprime.naoesquece;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/** Notas e áudios feitos pelo botão rápido ou pelo widget: ficam guardados até o app abrir e virar notas. */
final class Quick {
    private static final String PREFS = "nao_esquece_rapidos", K = "itens";

    private Quick() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "rapidos");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray load(Context c) {
        try {
            return new JSONArray(sp(c).getString(K, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void add(Context c, JSONObject o) {
        JSONArray a = load(c);
        a.put(o);
        sp(c).edit().putString(K, a.toString()).commit();
    }

    static synchronized String list(Context c) {
        return load(c).toString();
    }

    static synchronized int count(Context c) {
        return load(c).length();
    }

    static synchronized void remove(Context c, String id) {
        JSONArray a = load(c), out = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) {
                String f = o.optString("file", "");
                //noinspection ResultOfMethodCallIgnored
                if (!f.isEmpty()) new File(dir(c), f).delete();
            } else out.put(o);
        }
        sp(c).edit().putString(K, out.toString()).commit();
    }

    static String data(Context c, String id) throws Exception {
        JSONArray a = load(c);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !id.equals(o.optString("id"))) continue;
            File f = new File(dir(c), o.optString("file", "x"));
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
        }
        throw new Exception("nao_achei");
    }
}
