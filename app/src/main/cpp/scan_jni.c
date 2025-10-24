//
// Created by zhe deng on 2025/10/23.
//

#ifdef ANDROID_JNI_BUILD
#include "scan_jni.h"
#include <android/log.h>


#define LOG_TAG "ScanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)


static JavaVM *g_jvm = NULL;
static jobject g_callback_obj = NULL;
static jmethodID g_callback_method = NULL;

static void scan_callback_jni(const char* path) {
    if (g_callback_obj == NULL || g_callback_method == NULL) return;

    JNIEnv* env;
    int getEnvStat = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
    }

    jstring jpath = (*env)->NewStringUTF(env, path);
    (*env)->CallVoidMethod(env, g_callback_obj, g_callback_method, jpath);
    (*env)->DeleteLocalRef(env, jpath);

    if (getEnvStat == JNI_EDETACHED) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

JNIEXPORT void JNICALL
Java_com_google_android_diskusage_jniutils_ScanUtils_scanDirectory(JNIEnv *env, jobject thiz, jstring path, jobject callback) {
    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);


    if (g_jvm == NULL) {
        (*env)->GetJavaVM(env, &g_jvm);
    }


    g_callback_obj = (*env)->NewGlobalRef(env, callback);
    jclass callback_class = (*env)->GetObjectClass(env, callback);
    g_callback_method = (*env)->GetMethodID(env, callback_class, "onScanResult", "(Ljava/lang/String;)V");


    scan_tree(path_str, scan_callback_jni);


    (*env)->ReleaseStringUTFChars(env, path, path_str);
    (*env)->DeleteGlobalRef(env, g_callback_obj);
    g_callback_obj = NULL;
    g_callback_method = NULL;
}
#endif