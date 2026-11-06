package com.inforprime.naoesquece;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

/** Lê o texto de fotos e PDFs no próprio celular (ML Kit, sem internet e sem custo). */
final class Ocr {
    private static final int MAX_PAGES = 30;

    private Ocr() {}

    static String run(Context c, String mime, byte[] data) throws Exception {
        TextRecognizer r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            if ("application/pdf".equals(mime)) return pdf(c, r, data);
            return image(r, data);
        } finally {
            r.close();
        }
    }

    private static String read(TextRecognizer r, InputImage im) throws Exception {
        Text t = Tasks.await(r.process(im), 60, TimeUnit.SECONDS);
        return t == null ? "" : t.getText().trim();
    }

    private static String image(TextRecognizer r, byte[] data) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > 2600) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) throw new Exception("imagem");
        int rot = 0;
        try {
            int or = new ExifInterface(new ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {
        }
        try {
            return read(r, InputImage.fromBitmap(b, rot));
        } finally {
            b.recycle();
        }
    }

    private static String pdf(Context c, TextRecognizer r, byte[] data) throws Exception {
        File f = File.createTempFile("ocr", ".pdf", c.getCacheDir());
        StringBuilder out = new StringBuilder();
        try {
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(data);
            }
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer pr = new PdfRenderer(pfd)) {
                int n = Math.min(pr.getPageCount(), MAX_PAGES);
                for (int i = 0; i < n; i++) {
                    try (PdfRenderer.Page p = pr.openPage(i)) {
                        float k = 1800f / Math.max(1, Math.max(p.getWidth(), p.getHeight()));
                        int w = Math.max(1, Math.round(p.getWidth() * k)), h = Math.max(1, Math.round(p.getHeight() * k));
                        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        b.eraseColor(Color.WHITE);
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        String t;
                        try {
                            t = read(r, InputImage.fromBitmap(b, 0));
                        } finally {
                            b.recycle();
                        }
                        if (t.isEmpty()) continue;
                        if (out.length() > 0) out.append("\n\n");
                        if (n > 1) out.append("— Página ").append(i + 1).append(" —\n");
                        out.append(t);
                    }
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return out.toString().trim();
    }
}
