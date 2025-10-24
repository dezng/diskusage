package com.google.android.diskusage.jniutils;

import android.util.Log;

/**
 * Created by zhe deng on 2025/10/23.
 */
public class ScanUtils {
    private static final String TAG = "ScanUtils";

    static {
        System.loadLibrary("scan");
    }

    public interface ScanCallback {
        void onScanResult(String name);
    }


    public static String scanDirectory(String path) {
        StringBuilder sb = new StringBuilder();
        scanDirectory(path, str -> {
            Log.v(TAG, "scanDirectory: path: " + path + ", call: " + str);
            sb.append(str);
        });
        Log.i(TAG, "scanDirectory: " + sb);
        return sb.toString();
    }

    private static native void scanDirectory(String path, ScanCallback callback);
}

