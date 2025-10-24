#ifdef ANDROID_JNI_BUILD

#include "scan_jni.h"

#include <stdlib.h>

#define JNI_WRITE_BUFFER_SIZE (64 * 1024)

/*
 * The method id is cached after the first lookup. The class is loaded by the
 * same class loader as the listener passed to scanTree(), so a single lookup
 * is enough for the lifetime of the process.
 */
static jmethodID g_on_data = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void) reserved;
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass listenerClass = (*env)->FindClass(
      env, "com/google/android/diskusage/jniutils/ScanUtils$Listener");
  if (listenerClass != NULL) {
    g_on_data = (*env)->GetMethodID(env, listenerClass, "onData", "([BI)V");
    if (g_on_data == NULL) {
      /* Lazy lookup in scanTree() will retry with the actual listener. */
      (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, listenerClass);
  } else {
    /* Lazy lookup in scanTree() will retry with the actual listener object. */
    (*env)->ExceptionClear(env);
  }
  return JNI_VERSION_1_6;
}

typedef struct {
  JNIEnv *env;
  jobject listener;
  jbyteArray buffer;
  jint capacity;
  int failed;
} JniSink;

static int jni_write(void *ctx, const char *data, size_t len) {
  JniSink *sink = (JniSink *) ctx;
  if (sink->failed) {
    return -1;
  }
  if (len > (size_t) sink->capacity) {
    sink->failed = 1;
    return -1;
  }

  (*sink->env)->SetByteArrayRegion(
      sink->env, sink->buffer, 0, (jsize) len, (const jbyte *) data);
  if ((*sink->env)->ExceptionCheck(sink->env)) {
    sink->failed = 1;
    return -1;
  }

  (*sink->env)->CallVoidMethod(
      sink->env, sink->listener, g_on_data, sink->buffer, (jint) len);
  if ((*sink->env)->ExceptionCheck(sink->env)) {
    sink->failed = 1;
    return -1;
  }
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_google_android_diskusage_jniutils_ScanUtils_scanTree(
    JNIEnv *env, jclass clazz, jstring path, jint blockSize, jobject listener) {
  (void) clazz;

  if (path == NULL || listener == NULL || blockSize <= 0) {
    return -1;
  }

  if (g_on_data == NULL) {
    jclass listenerClass = (*env)->GetObjectClass(env, listener);
    if (listenerClass == NULL) {
      return -1;
    }
    g_on_data = (*env)->GetMethodID(env, listenerClass, "onData", "([BI)V");
    (*env)->DeleteLocalRef(env, listenerClass);
    if (g_on_data == NULL) {
      return -1;
    }
  }

  const char *pathChars = (*env)->GetStringUTFChars(env, path, NULL);
  if (pathChars == NULL) {
    return -1;
  }

  jbyteArray buffer = (*env)->NewByteArray(env, JNI_WRITE_BUFFER_SIZE);
  if (buffer == NULL) {
    (*env)->ReleaseStringUTFChars(env, path, pathChars);
    return -1;
  }

  JniSink sink;
  sink.env = env;
  sink.listener = listener;
  sink.buffer = buffer;
  sink.capacity = JNI_WRITE_BUFFER_SIZE;
  sink.failed = 0;

  int result = scan_tree_stream(pathChars, blockSize, jni_write, &sink);

  (*env)->ReleaseStringUTFChars(env, path, pathChars);
  (*env)->DeleteLocalRef(env, buffer);

  if ((*env)->ExceptionCheck(env)) {
    return -1;
  }
  if (result != 0 || sink.failed) {
    (*env)->ThrowNew(env, "java/lang/RuntimeException",
                     "Native filesystem scan failed");
    return -1;
  }
  return 0;
}

#endif
