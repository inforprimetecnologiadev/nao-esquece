package com.inforprime.naoesquece;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Áudio em MP3 dentro do app (o Android não tem codificador de MP3).
 * Lê o áudio com o decodificador do próprio celular (WebM/Opus das gravações do app, M4A/AAC, OGG, WAV, AMR, 3GP…)
 * e grava em MP3 mono, 64 kbps, com o LAME (biblioteca nemp3).
 * O MP3 toca no PDF (Adobe Acrobat), no WhatsApp, no Word e em qualquer player.
 */
final class Mp3 {
    private static final String TAG = "NaoEsqueceMp3";
    private static final int KBPS = 64;
    private static final int[] RATES = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};
    private static boolean loaded;
    private static Throwable loadErr;

    static {
        try {
            System.loadLibrary("nemp3");
            loaded = true;
        } catch (Throwable e) {
            loadErr = e;
            Log.w(TAG, "biblioteca do MP3", e);
        }
    }

    static native long open(int inRate, int outRate, int kbps, int quality);

    static native int encode(long h, short[] pcm, int n, byte[] out);

    static native int flush(long h, byte[] out);

    static native void close(long h);

    static native String version();

    static boolean available() {
        return loaded;
    }

    static String lameVersion() {
        try {
            return loaded ? version() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private static int outRate(int in) {
        for (int r : RATES) if (r == in) return in;
        return 44100;
    }

    /** Converte {@code in} em MP3 no arquivo {@code out}. Devolve a duração em segundos. */
    static double convert(File in, File out) throws Exception {
        if (!loaded) throw new Exception("sem MP3: " + loadErr);
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        long lame = 0;
        try {
            ex.setDataSource(in.getPath());
            int track = -1;
            MediaFormat f = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat t = ex.getTrackFormat(i);
                String m = t.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    f = t;
                    break;
                }
            }
            if (track < 0) throw new Exception("sem trilha de áudio");
            String mime = f.getString(MediaFormat.KEY_MIME);
            if ("audio/mpeg".equals(mime)) { // já é MP3
                copy(in, out);
                return f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) / 1e6 : 0;
            }
            ex.selectTrack(track);
            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(f, null, null, 0);
            dec.start();

            int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = Math.max(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            int pcmEnc = 2; // AudioFormat.ENCODING_PCM_16BIT
            long samples = 0;
            boolean inDone = false, outDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] mono = new short[0];
            byte[] mp3 = new byte[0];
            long t0 = System.currentTimeMillis();
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
                while (!outDone) {
                    if (System.currentTimeMillis() - t0 > 15 * 60_000L) throw new Exception("demorou demais");
                    if (!inDone) {
                        int ii = dec.dequeueInputBuffer(10_000);
                        if (ii >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(ii);
                            int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                            if (n < 0) {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inDone = true;
                            } else {
                                dec.queueInputBuffer(ii, 0, n, Math.max(0, ex.getSampleTime()), 0);
                                ex.advance();
                            }
                        }
                    }
                    int oi = dec.dequeueOutputBuffer(info, 10_000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        int r0 = rate, c0 = ch;
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEnc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                        if (lame != 0 && rate != r0) throw new Exception("taxa mudou no meio");
                        if (lame != 0 && ch != c0) Log.i(TAG, "canais: " + c0 + " -> " + ch);
                        continue;
                    }
                    if (oi < 0) continue;
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        if (lame == 0) {
                            lame = open(rate, outRate(rate), KBPS, 5);
                            if (lame == 0) throw new Exception("LAME não abriu (" + rate + " Hz)");
                        }
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer bb = ob.slice().order(ByteOrder.nativeOrder());
                        int frames;
                        if (pcmEnc == 4) { // ENCODING_PCM_FLOAT
                            FloatBuffer fb = bb.asFloatBuffer();
                            frames = fb.remaining() / ch;
                            if (mono.length < frames) mono = new short[frames];
                            for (int i = 0; i < frames; i++) {
                                float s = 0;
                                for (int c = 0; c < ch; c++) s += fb.get(i * ch + c);
                                s /= ch;
                                mono[i] = (short) Math.max(-32768, Math.min(32767, Math.round(s * 32767f)));
                            }
                        } else {
                            ShortBuffer sb = bb.asShortBuffer();
                            frames = sb.remaining() / ch;
                            if (mono.length < frames) mono = new short[frames];
                            if (ch == 1) sb.get(mono, 0, frames);
                            else for (int i = 0; i < frames; i++) {
                                int s = 0;
                                for (int c = 0; c < ch; c++) s += sb.get(i * ch + c);
                                mono[i] = (short) (s / ch);
                            }
                        }
                        if (frames > 0) {
                            int need = (int) (1.25 * frames) + 7200;
                            if (mp3.length < need) mp3 = new byte[need];
                            int w = encode(lame, mono, frames, mp3);
                            if (w < 0) throw new Exception("LAME erro " + w);
                            os.write(mp3, 0, w);
                            samples += frames;
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
                if (lame == 0) throw new Exception("áudio vazio");
                if (mp3.length < 7200) mp3 = new byte[7200];
                int w = flush(lame, mp3);
                if (w > 0) os.write(mp3, 0, w);
            }
            return rate > 0 ? samples / (double) rate : 0;
        } finally {
            if (lame != 0) close(lame);
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                dec.release();
            }
            ex.release();
        }
    }

    private static void copy(File a, File b) throws Exception {
        try (FileInputStream in = new FileInputStream(a); FileOutputStream os = new FileOutputStream(b)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
