#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include "whisper.h"

#define TAG "AjmatVoiceFlow"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static atomic_bool g_cancel_requested = ATOMIC_VAR_INIT(false);
static atomic_bool g_inference_running = ATOMIC_VAR_INIT(false);
static atomic_int g_progress = ATOMIC_VAR_INIT(-1);
static atomic_int g_last_logged_progress = ATOMIC_VAR_INIT(-10);
static atomic_int g_segment_count = ATOMIC_VAR_INIT(0);

static jstring make_error(JNIEnv *env, const char *message) {
    char buffer[384];
    snprintf(buffer, sizeof(buffer), "ERROR:%s", message);
    return (*env)->NewStringUTF(env, buffer);
}

static bool whisper_abort_requested(void * user_data) {
    (void) user_data;
    return atomic_load(&g_cancel_requested);
}

static void whisper_progress(
        struct whisper_context * context,
        struct whisper_state * state,
        int progress,
        void * user_data) {
    (void) context;
    (void) state;
    (void) user_data;
    int safe_progress = progress < 0 ? 0 : (progress > 100 ? 100 : progress);
    atomic_store(&g_progress, safe_progress);

    int previous = atomic_load(&g_last_logged_progress);
    if (safe_progress >= previous + 10 || safe_progress == 100) {
        atomic_store(&g_last_logged_progress, safe_progress);
        LOGI("Whisper native progress: %d%%", safe_progress);
    }
}

static void whisper_new_segment(
        struct whisper_context * context,
        struct whisper_state * state,
        int n_new,
        void * user_data) {
    (void) context;
    (void) state;
    (void) user_data;
    int count = atomic_fetch_add(&g_segment_count, n_new) + n_new;
    LOGI("Whisper emitted %d segment(s); transcript text withheld from logs.", count);
}

JNIEXPORT jlong JNICALL
Java_com_ajmat_voiceflow_speech_WhisperJNI_loadModel(
        JNIEnv *env, jobject thiz, jstring model_path) {
    (void) thiz;
    if (model_path == NULL) return 0;

    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) return 0;

    LOGI("Loading multilingual Whisper model from app-private storage.");
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    struct whisper_context *context =
        whisper_init_from_file_with_params(path, params);

    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (context == NULL) {
        LOGE("Could not load Whisper model.");
        return 0;
    }

    LOGI("Whisper model initialized. System: %s", whisper_print_system_info());
    return (jlong) (intptr_t) context;
}

JNIEXPORT void JNICALL
Java_com_ajmat_voiceflow_speech_WhisperJNI_freeModel(
        JNIEnv *env, jobject thiz, jlong context_pointer) {
    (void) env;
    (void) thiz;
    if (context_pointer != 0) {
        whisper_free((struct whisper_context *) (intptr_t) context_pointer);
        LOGI("Whisper model released.");
    }
}

JNIEXPORT void JNICALL
Java_com_ajmat_voiceflow_speech_WhisperJNI_cancelTranscription(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (atomic_load(&g_inference_running)) {
        atomic_store(&g_cancel_requested, true);
        LOGI("Cooperative native cancellation requested.");
    }
}

JNIEXPORT jint JNICALL
Java_com_ajmat_voiceflow_speech_WhisperJNI_getProgress(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (!atomic_load(&g_inference_running)) return -1;
    return (jint) atomic_load(&g_progress);
}

JNIEXPORT jstring JNICALL
Java_com_ajmat_voiceflow_speech_WhisperJNI_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    (void) thiz;
    const char *system_info = whisper_print_system_info();
    return (*env)->NewStringUTF(env, system_info != NULL ? system_info : "Whisper system information unavailable");
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
        return make_error(env, "Not enough memory for audio samples.");
    }

    size_t bytes_read = fread(pcm, 1, data_size, file);
    fclose(file);
    (*env)->ReleaseStringUTFChars(env, wav_path, path);

    if (bytes_read < 3200U || (bytes_read % 2U) != 0U) {
        free(pcm);
        (*env)->ReleaseStringUTFChars(env, language, lang);
        return make_error(env, "WAV audio data is incomplete.");
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

    atomic_store(&g_cancel_requested, false);
    atomic_store(&g_inference_running, true);
    atomic_store(&g_progress, 0);
    atomic_store(&g_last_logged_progress, -10);
    atomic_store(&g_segment_count, 0);

    struct whisper_full_params run_params =
        whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    run_params.n_threads = (int) (num_threads > 0 ? num_threads : 2);
    run_params.language = (strcmp(lang, "auto") == 0) ? NULL : lang;
    run_params.detect_language = (strcmp(lang, "auto") == 0);
    run_params.translate = false;
    run_params.print_special = false;
    run_params.print_progress = false;
    run_params.print_realtime = false;
    run_params.print_timestamps = false;
    run_params.no_timestamps = true;
    run_params.no_context = true;
    run_params.single_segment = false;
    run_params.temperature = 0.0f;
    run_params.temperature_inc = 0.0f;
    run_params.greedy.best_of = 1;
    run_params.suppress_blank = true;
    run_params.progress_callback = whisper_progress;
    run_params.abort_callback = whisper_abort_requested;
    run_params.new_segment_callback = whisper_new_segment;

    LOGI("whisper_full begin: samples=%d, seconds=%.2f, threads=%d, language=%s",
         sample_count, (double) sample_count / WHISPER_SAMPLE_RATE,
         run_params.n_threads, lang);

    const int result = whisper_full(context, run_params, samples, sample_count);
    free(samples);
    atomic_store(&g_inference_running, false);
    (*env)->ReleaseStringUTFChars(env, language, lang);

    if (atomic_load(&g_cancel_requested)) {
        LOGE("whisper_full stopped by cooperative cancellation; native result=%d", result);
        return make_error(env, "Whisper inference was cancelled.");
    }
    if (result != 0) {
        char message[128];
        snprintf(message, sizeof(message), "Whisper inference failed (native code %d).", result);
        LOGE("%s", message);
        return make_error(env, message);
    }

    const int segments = whisper_full_n_segments(context);
    if (segments <= 0) {
        atomic_store(&g_progress, 100);
        LOGI("whisper_full completed but returned no speech segments.");
        return make_error(env, "No speech was recognized. Speak clearly for 2–3 seconds and try again.");
    }

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

    atomic_store(&g_progress, 100);
    LOGI("whisper_full completed; segments=%d. Transcript content withheld from logs.", segments);
    jstring answer = (*env)->NewStringUTF(env, output);
    free(output);
    return answer;
}
