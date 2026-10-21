#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "libretro.h"

#define LOG_TAG "WatchBoy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define FRAME_W 240
#define FRAME_H 160
#define AUDIO_CAPACITY 32768

static uint16_t frameBuffer[FRAME_W * FRAME_H];
static pthread_mutex_t frameMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t audioMutex = PTHREAD_MUTEX_INITIALIZER;
static int16_t audioBuffer[AUDIO_CAPACITY];
static uint32_t audioRead;
static uint32_t audioWrite;
static volatile uint16_t joypadBits;
static int sampleRate = 32768;
static int coreReady;
static int gameLoaded;
static char saveDirectory[1024];
static char statePath[1200];

static void logMessage(enum retro_log_level level, const char *format, ...) {
    int priority = ANDROID_LOG_DEBUG;
    va_list args;
    if (level == RETRO_LOG_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == RETRO_LOG_WARN) priority = ANDROID_LOG_WARN;
    else if (level == RETRO_LOG_INFO) priority = ANDROID_LOG_INFO;
    va_start(args, format);
    __android_log_vprint(priority, LOG_TAG, format, args);
    va_end(args);
}

static bool environmentCallback(unsigned command, void *data) {
    switch (command) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return data && *(enum retro_pixel_format *)data == RETRO_PIXEL_FORMAT_RGB565;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        if (data) ((struct retro_log_callback *)data)->log = logMessage;
        return data != NULL;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
        if (data) *(const char **)data = saveDirectory;
        return data != NULL;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        if (data) *(bool *)data = true;
        return data != NULL;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        if (data) *(bool *)data = true;
        return data != NULL;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        if (data) *(unsigned *)data = 0;
        return data != NULL;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        if (command == RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO && data) {
            struct retro_system_av_info *info = data;
            if (info->timing.sample_rate >= 8000 && info->timing.sample_rate <= 96000)
                sampleRate = (int)info->timing.sample_rate;
        }
        return true;
    default:
        return false;
    }
}

static void videoRefresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    unsigned y, x;
    if (!data || !width || !height) return;
    pthread_mutex_lock(&frameMutex);
    memset(frameBuffer, 0, sizeof(frameBuffer));
    if (width > FRAME_W) width = FRAME_W;
    if (height > FRAME_H) height = FRAME_H;
    for (y = 0; y < height; y++) {
        const uint16_t *source = (const uint16_t *)((const uint8_t *)data + y * pitch);
        memcpy(frameBuffer + y * FRAME_W, source, width * sizeof(uint16_t));
    }
    pthread_mutex_unlock(&frameMutex);
}

static size_t audioSampleBatch(const int16_t *data, size_t frames) {
    size_t i;
    pthread_mutex_lock(&audioMutex);
    for (i = 0; i < frames * 2; i++) {
        uint32_t next = (audioWrite + 1) % AUDIO_CAPACITY;
        if (next == audioRead) audioRead = (audioRead + 2) % AUDIO_CAPACITY;
        audioBuffer[audioWrite] = data[i];
        audioWrite = next;
    }
    pthread_mutex_unlock(&audioMutex);
    return frames;
}

static void inputPoll(void) { }

static int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void) index;
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)joypadBits;
    if (id >= 16) return 0;
    return (joypadBits & (1u << id)) ? 1 : 0;
}

static void initializeCore(void) {
    if (coreReady) return;
    retro_set_environment(environmentCallback);
    retro_set_video_refresh(videoRefresh);
    retro_set_audio_sample(NULL);
    retro_set_audio_sample_batch(audioSampleBatch);
    retro_set_input_poll(inputPoll);
    retro_set_input_state(inputState);
    retro_init();
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    coreReady = 1;
    LOGI("mGBA libretro core initialized");
}

static void jstringToUtf8(JNIEnv *env, jstring value, char *target, size_t capacity) {
    const char *text = (*env)->GetStringUTFChars(env, value, NULL);
    if (!text) { target[0] = '\0'; return; }
    snprintf(target, capacity, "%s", text);
    (*env)->ReleaseStringUTFChars(env, value, text);
}

JNIEXPORT void JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeSetSaveDirectory(JNIEnv *env, jobject self, jstring path) {
    (void) self;
    jstringToUtf8(env, path, saveDirectory, sizeof(saveDirectory));
}

JNIEXPORT void JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeSetStatePath(JNIEnv *env, jobject self, jstring path) {
    (void) self;
    jstringToUtf8(env, path, statePath, sizeof(statePath));
}

JNIEXPORT jboolean JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeStart(JNIEnv *env, jobject self, jstring path) {
    struct retro_game_info gameInfo;
    char romPath[1200];
    (void) self;
    jstringToUtf8(env, path, romPath, sizeof(romPath));
    if (!romPath[0]) return JNI_FALSE;
    initializeCore();
    if (gameLoaded) retro_unload_game();
    memset(&gameInfo, 0, sizeof(gameInfo));
    gameInfo.path = romPath;
    if (!retro_load_game(&gameInfo)) {
        gameLoaded = 0;
        LOGW("mGBA could not load selected ROM");
        return JNI_FALSE;
    }
    gameLoaded = 1;
    joypadBits = 0;
    pthread_mutex_lock(&audioMutex);
    audioRead = audioWrite = 0;
    pthread_mutex_unlock(&audioMutex);
    LOGI("Loaded selected GBA ROM");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeRunFrame(JNIEnv *env, jobject self) {
    (void) env; (void) self;
    if (gameLoaded) retro_run();
}

JNIEXPORT void JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeSetButtons(JNIEnv *env, jobject self, jint bits) {
    (void) env; (void) self;
    joypadBits = (uint16_t)bits;
}

JNIEXPORT void JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeCopyFrame(JNIEnv *env, jobject self, jintArray target) {
    jint *out;
    int i;
    (void) self;
    if (!target || (*env)->GetArrayLength(env, target) < FRAME_W * FRAME_H) return;
    out = (*env)->GetIntArrayElements(env, target, NULL);
    if (!out) return;
    pthread_mutex_lock(&frameMutex);
    for (i = 0; i < FRAME_W * FRAME_H; i++) {
        uint16_t p = frameBuffer[i];
        int r5 = (p >> 11) & 31;
        int g6 = (p >> 5) & 63;
        int b5 = p & 31;
        int r = (r5 << 3) | (r5 >> 2);
        int g = (g6 << 2) | (g6 >> 4);
        int b = (b5 << 3) | (b5 >> 2);
        out[i] = (jint)(0xFF000000u | ((unsigned)r << 16) | ((unsigned)g << 8) | (unsigned)b);
    }
    pthread_mutex_unlock(&frameMutex);
    (*env)->ReleaseIntArrayElements(env, target, out, 0);
}

JNIEXPORT jint JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeReadAudio(JNIEnv *env, jobject self, jshortArray target) {
    jshort *out;
    jsize length;
    jint count = 0;
    (void) self;
    if (!target) return 0;
    length = (*env)->GetArrayLength(env, target);
    out = (*env)->GetShortArrayElements(env, target, NULL);
    if (!out) return 0;
    pthread_mutex_lock(&audioMutex);
    while (count < length && audioRead != audioWrite) {
        out[count++] = audioBuffer[audioRead];
        audioRead = (audioRead + 1) % AUDIO_CAPACITY;
    }
    pthread_mutex_unlock(&audioMutex);
    (*env)->ReleaseShortArrayElements(env, target, out, 0);
    return count;
}

JNIEXPORT jint JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeSampleRate(JNIEnv *env, jobject self) {
    (void) env; (void) self;
    return sampleRate;
}

JNIEXPORT jboolean JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeSaveState(JNIEnv *env, jobject self) {
    size_t size;
    void *state;
    char tempPath[1240];
    FILE *file;
    int ok;
    (void) env; (void) self;
    if (!gameLoaded || !statePath[0]) return JNI_FALSE;
    size = retro_serialize_size();
    if (!size || size > 64u * 1024u * 1024u) return JNI_FALSE;
    state = malloc(size);
    if (!state || !retro_serialize(state, size)) { free(state); return JNI_FALSE; }
    snprintf(tempPath, sizeof(tempPath), "%s.tmp", statePath);
    file = fopen(tempPath, "wb");
    if (!file) { free(state); return JNI_FALSE; }
    ok = fwrite(state, 1, size, file) == size && fflush(file) == 0;
    ok = fclose(file) == 0 && ok;
    free(state);
    if (!ok || rename(tempPath, statePath) != 0) { remove(tempPath); return JNI_FALSE; }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeLoadState(JNIEnv *env, jobject self) {
    size_t size;
    void *state;
    FILE *file;
    long fileSize;
    int ok;
    (void) env; (void) self;
    if (!gameLoaded || !statePath[0] || !(file = fopen(statePath, "rb"))) return JNI_FALSE;
    if (fseek(file, 0, SEEK_END) != 0 || (fileSize = ftell(file)) <= 0 ||
        fseek(file, 0, SEEK_SET) != 0) { fclose(file); return JNI_FALSE; }
    size = retro_serialize_size();
    if (!size || (size_t)fileSize != size) { fclose(file); return JNI_FALSE; }
    state = malloc(size);
    if (!state) { fclose(file); return JNI_FALSE; }
    ok = fread(state, 1, size, file) == size;
    fclose(file);
    if (ok) ok = retro_unserialize(state, size);
    free(state);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeStop(JNIEnv *env, jobject self) {
    (void) env; (void) self;
    if (gameLoaded) {
        retro_unload_game();
        gameLoaded = 0;
    }
    joypadBits = 0;
}

JNIEXPORT void JNICALL
Java_com_whitetea_watchboy_MainActivity_nativeShutdown(JNIEnv *env, jobject self) {
    (void) env; (void) self;
    if (gameLoaded) retro_unload_game();
    gameLoaded = 0;
    if (coreReady) retro_deinit();
    coreReady = 0;
    joypadBits = 0;
}
