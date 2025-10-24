package com.google.android.diskusage.jniutils;

/**
 * JNI entry points for the streaming filesystem scanner.
 *
 * <p>The native scanner never returns a tree. It sends compact binary chunks
 * through {@link Listener#onData(byte[], int)}. The Java implementation parses
 * those chunks immediately and builds the final tree, so only one chunk plus
 * one Java byte array exists at a time.</p>
 */
public final class ScanUtils {

    static {
        System.loadLibrary("scan");
    }

    private ScanUtils() {
    }

    /**
     * Receives binary scanner output. A chunk always ends at a record
     * boundary; implementations may parse it without keeping a carry buffer.
     */
    public interface Listener {
        void onData(byte[] data, int length);
    }

    /**
     * Scan {@code path} using {@code blockSize}-byte filesystem blocks.
     *
     * @return 0 on success, non-zero on failure
     */
    public static native int scanTree(String path, int blockSize,
                                      Listener listener);
}
