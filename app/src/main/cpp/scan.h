//
// Created by zhe deng on 2025/10/23.
//

#ifndef DISKUSAGE_SCAN_H
#define DISKUSAGE_SCAN_H

#include <stddef.h>

/*
 * Streaming scanner.
 *
 * The native side never builds a directory tree. It walks the filesystem and
 * writes compact binary records into a small fixed-size buffer. Whenever the
 * buffer is full the callback receives a chunk. The Java side parses those
 * records immediately and builds the final FileSystemEntry tree.
 *
 * This keeps native memory O(depth + one directory) instead of O(number of
 * files), avoids one JNI call per entry, and lets the Java side report
 * progress and cancel naturally.
 *
 * The callback returns 0 on success and non-zero to abort the scan.
 */
typedef int (*scan_stream_callback)(void *ctx, const char *data, size_t len);

/*
 * Scan path and emit records through callback.
 *
 * blockSize is the filesystem block size from StatFs. st_blocks is measured
 * in 512-byte units, so it is converted to filesystem blocks before being
 * emitted.
 *
 * Returns 0 on success, non-zero if the callback aborted or an internal
 * allocation failed.
 */
int scan_tree_stream(const char *path, int blockSize,
                     scan_stream_callback callback, void *ctx);

#endif //DISKUSAGE_SCAN_H
