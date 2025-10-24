//
// Created by zhe deng on 2025/10/23.
//

#ifdef ANDROID_JNI_BUILD
#ifndef DISKUSAGE_SCAN_JNI_H
#define DISKUSAGE_SCAN_JNI_H

#include <jni.h>
#include "scan.h"

/*
 * Java_com_google_android_diskusage_jniutils_ScanUtils_scanTree
 *
 * The listener receives binary chunks produced by scan_tree_stream(). The
 * Java side parses them immediately, so at most one chunk plus one Java
 * byte[] is alive at a time.
 */
JNIEXPORT jint JNICALL
Java_com_google_android_diskusage_jniutils_ScanUtils_scanTree(
    JNIEnv *env, jclass clazz, jstring path, jint blockSize, jobject listener)
    __attribute__((visibility("default")));

#endif //DISKUSAGE_SCAN_JNI_H
#endif
