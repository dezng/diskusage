//
// Created by zhe deng on 2025/10/23.
//

#ifdef ANDROID_JNI_BUILD
#ifndef DISKUSAGE_SCAN_JNI_H
#define DISKUSAGE_SCAN_JNI_H

#include <jni.h>
#include "scan.h"


JNIEXPORT void JNICALL
Java_com_google_android_diskusage_jniutils_ScanUtils_scanDirectory(JNIEnv *env, jobject thiz, jstring path, jobject callback) __attribute__((visibility("default")));

#endif //DISKUSAGE_SCAN_JNI_H
#endif