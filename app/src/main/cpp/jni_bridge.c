#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#define TAG "AjmatVoiceFlow"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static jstring make_error(JNIEnv *env, const char *message) {
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "ERROR:%s", message);
    return (*env)->NewStringUTF(env, buffer);
}

JNIEXPORT jlong JNICALL
Java_com_ajmat_voiceflow_speech_WhisperJNI_loadModel(
        JNIEnv *env, jobject thiz, jstring model_path) {
    (void) thiz;
    if (model_path == NULL) return 0;

    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) return 0;

    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *context =
        whisper_init_from_file_with_params(path, params);

    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (context == NULL) {
        LOGE("Could not load Whisper model");
        return 0;
    }
    return (jlong) (intptr_t) context;
}

JNIEXPORT void JNICALL
Java_com_ajmat_voiceflow_speech_WhisperJNI_freeModel(
        JNIEnv *env, jobject thiz, jlong context_pointer) {
    (void) env;
    (void) thiz;
    if (context_pointer != 0) {
        whisper_free((struct whisper_context *) (intptr_t) context_pointer);
    }
}

JNIEXPORT jstring JNICALL
Java_com_ajmat_voiceflow_speech_WhisperJNI_transcribeAudio(
        JNIEnv *env, jobject thiz, jlong context_pointer,
        jstring wav_path, jint num_threads, jstring language) {
    (void) thiz;
    if (context_pointer == 0) return make_error(env, "Whisper model is not loaded.");
    if (wav_path == NULL || language == NULL) return make_error(env, "Missing audio path or language.");

    const char *path = (*env)->GetStringUTFChars(env, wav_path, NULL);
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    if (path == NULL || lang == NULL) {
        if (path != NULL) (*env)->ReleaseStringUTFChars(env, wav_path, path);
        if (lang != NULL) (*env)->ReleaseStringUTFChars(env, language, lang);
        return make_error(env, "Could not read input parameters.");
    }

    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        (*env)->ReleaseStringUTFChars(env, wav_path, path);
        (*env)->ReleaseStringUTFChars(env, language, lang);
        return make_error(env, "Could not open temporary WAV audio.");
    }

    unsigned char header[44];
    if (fread(header, 1, sizeof(header), file) != sizeof(header) ||
        memcmp(header, "RIFF", 4) != 0 ||
        memcmp(header + 8, "WAVE", 4) != 0) {
        fclose(file);
        (*env)->ReleaseStringUTFChars(env, wav_path, path);
        (*env)->ReleaseStringUTFChars(env, language, lang);
        return make_error(env, "Invalid WAV header.");
    }

    uint32_t data_size = (uint32_t) header[40]
        | ((uint32_t) header[41] << 8)
        | ((uint32_t) header[42] << 16)
        | ((uint32_t) header[43] << 24);

    if (data_size < 3200U || data_size > 2000000000U || (data_size % 2U) != 0U) {
        fclose(file);
        (*env)->ReleaseStringUTFChars(env, wav_path, path);
        (*env)->ReleaseStringUTFChars(env, language, lang);
        return make_error(env, "Audio data is empty or too large.");
    }

    int16_t *pcm = (int16_t *) malloc(data_size);
    if (pcm == NULL) {
        fclose(file);
        (*env)->ReleaseStringUTFChars(env, wav_path, path);
        (*env)->ReleaseStringUTFChars(env, language, lang);
        return make_error(env, "Not enough memory for audio buffer.");
    }
    size_t bytes_read = fread(pcm, 1, data_size, file);
    fclose(file);
    (*env)->ReleaseStringUTFChars(env, wav_path, path);

    if (bytes_read < 3200U) {
        free(pcm);
        (*env)->ReleaseStringUTFChars(env, language, lang);
        return make_error(env, "Could not read audio samples.");
    }

    const int sample_count = (int) (bytes_read / sizeof(int16_t));
    float *samples = (float *) malloc((size_t) sample_count * sizeof(float));
    if (samples == NULL) {
        free(pcm);
        (*env)->ReleaseStringUTFChars(env, language, lang);
        return make_error(env, "Not enough memory for Whisper input.");
    }
    for (int i = 0; i < sample_count; ++i) {
        samples[i] = (float) pcm[i] / 32768.0f;
    }
    free(pcm);

    struct whisper_context *context =
        (struct whisper_context *) (intptr_t) context_pointer;
    struct whisper_full_params run_params =
        whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    run_params.n_threads = (int) (num_threads > 0 ? num_threads : 2);
    run_params.language = (strcmp(lang, "auto") == 0) ? NULL : lang;
    run_params.translate = false;
    run_params.print_special = false;
    run_params.print_progress = false;
    run_params.print_realtime = false;
    run_params.print_timestamps = false;
    run_params.no_timestamps = true;
    run_params.no_context = true;
    run_params.single_segment = false;
    run_params.temperature = 0.0f;

    const int result = whisper_full(context, run_params, samples, sample_count);
    free(samples);
    (*env)->ReleaseStringUTFChars(env, language, lang);
    if (result != 0) {
        LOGE("whisper_full returned %d", result);
        return make_error(env, "Whisper transcription failed.");
    }

    const int segments = whisper_full_n_segments(context);
    size_t output_size = 1;
    for (int i = 0; i < segments; ++i) {
        const char *segment = whisper_full_get_segment_text(context, i);
        if (segment != NULL) output_size += strlen(segment);
    }

    char *output = (char *) calloc(output_size, 1);
    if (output == NULL) return make_error(env, "Not enough memory for transcript.");
    for (int i = 0; i < segments; ++i) {
        const char *segment = whisper_full_get_segment_text(context, i);
        if (segment != NULL) strncat(output, segment, output_size - strlen(output) - 1);
    }

    jstring answer = (*env)->NewStringUTF(env, output);
    free(output);
    return answer;
}
