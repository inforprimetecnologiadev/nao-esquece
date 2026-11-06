/* Ponte entre o Java e o LAME: abre o codificador, manda o som (PCM 16 bits mono) e recebe o MP3. */
#include <jni.h>
#include <stdint.h>
#include "lame.h"

#define G(h) ((lame_global_flags *)(intptr_t)(h))

JNIEXPORT jlong JNICALL
Java_com_inforprime_naoesquece_Mp3_open(JNIEnv *env, jclass cls, jint inRate, jint outRate, jint kbps, jint quality) {
    lame_global_flags *g = lame_init();
    if (!g) return 0;
    lame_set_num_channels(g, 1);
    lame_set_mode(g, MONO);
    lame_set_in_samplerate(g, inRate);
    lame_set_out_samplerate(g, outRate);
    lame_set_VBR(g, vbr_off);
    lame_set_brate(g, kbps);
    lame_set_quality(g, quality);
    lame_set_bWriteVbrTag(g, 0);
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    return (jlong)(intptr_t)g;
}

JNIEXPORT jint JNICALL
Java_com_inforprime_naoesquece_Mp3_encode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint n, jbyteArray out) {
    if (!h) return -10;
    jshort *p = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int r = lame_encode_buffer(G(h), p, p, n, (unsigned char *)o, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, p, JNI_ABORT);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_inforprime_naoesquece_Mp3_flush(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    if (!h) return -10;
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int r = lame_encode_flush(G(h), (unsigned char *)o, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT void JNICALL
Java_com_inforprime_naoesquece_Mp3_close(JNIEnv *env, jclass cls, jlong h) {
    if (h) lame_close(G(h));
}

JNIEXPORT jstring JNICALL
Java_com_inforprime_naoesquece_Mp3_version(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, get_lame_version());
}
