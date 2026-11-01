# JNI entry points use fixed class/method names from scan_jni.c.
-keep class com.google.android.diskusage.jniutils.ScanUtils { *; }
-keep interface com.google.android.diskusage.jniutils.ScanUtils$Listener { *; }
-keepclassmembers class * implements com.google.android.diskusage.jniutils.ScanUtils$Listener {
    public void onData(byte[], int);
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# Instantiated by the framework from AndroidManifest.xml.
-keep class androidx.core.content.FileProvider { *; }

-dontwarn androidx.annotation.**
