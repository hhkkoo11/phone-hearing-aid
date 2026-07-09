#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "rnnoise/rnnoise.h"

static int clamp_to_short(float value) {
    if (value > 32767.0f) {
        return 32767;
    }
    if (value < -32768.0f) {
        return -32768;
    }
    return (int) value;
}

JNIEXPORT jlong JNICALL
Java_com_daicg_hearingaid_AiNoiseSuppressor_nativeCreate(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    DenoiseState *state = rnnoise_create(NULL);
    return (jlong) (intptr_t) state;
}

JNIEXPORT void JNICALL
Java_com_daicg_hearingaid_AiNoiseSuppressor_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    DenoiseState *state = (DenoiseState *) (intptr_t) handle;
    if (state != NULL) {
        rnnoise_destroy(state);
    }
}

JNIEXPORT jint JNICALL
Java_com_daicg_hearingaid_AiNoiseSuppressor_nativeFrameSize(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return rnnoise_get_frame_size();
}

JNIEXPORT jint JNICALL
Java_com_daicg_hearingaid_AiNoiseSuppressor_nativeProcessInPlace(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jshortArray samples,
        jint length) {
    (void) clazz;
    DenoiseState *state = (DenoiseState *) (intptr_t) handle;
    if (state == NULL || samples == NULL || length <= 0) {
        return 0;
    }

    int frame_size = rnnoise_get_frame_size();
    int frames = length / frame_size;
    if (frames <= 0) {
        return 0;
    }

    jshort *pcm = (*env)->GetShortArrayElements(env, samples, NULL);
    if (pcm == NULL) {
        return 0;
    }

    float *input = (float *) malloc(sizeof(float) * frame_size);
    float *output = (float *) malloc(sizeof(float) * frame_size);
    if (input == NULL || output == NULL) {
        free(input);
        free(output);
        (*env)->ReleaseShortArrayElements(env, samples, pcm, JNI_ABORT);
        return 0;
    }

    for (int frame = 0; frame < frames; frame++) {
        int offset = frame * frame_size;
        for (int i = 0; i < frame_size; i++) {
            input[i] = (float) pcm[offset + i];
        }
        rnnoise_process_frame(state, output, input);
        for (int i = 0; i < frame_size; i++) {
            pcm[offset + i] = (jshort) clamp_to_short(output[i]);
        }
    }

    free(input);
    free(output);
    (*env)->ReleaseShortArrayElements(env, samples, pcm, 0);
    return frames;
}
