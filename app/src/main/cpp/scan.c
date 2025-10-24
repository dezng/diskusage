#include "scan.h"

#include <dirent.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/*
 * Binary streaming protocol. All integers are little-endian.
 *
 *   DIR   : u8 type=1, u64 blocks, u64 bytes, u16 nameLen, name[UTF-8]
 *   FILE  : u8 type=2, u64 blocks, u64 bytes, u16 nameLen, name[UTF-8]
 *   LEAVE : u8 type=3
 *   ERROR : u8 type=4, u8 isDir, u8 errno, u64 blocks, u64 bytes,
 *           u16 nameLen, name[UTF-8]
 *
 * A directory is emitted as DIR ... children ... LEAVE. Sizes in DIR/FILE are
 * the entry's own allocated size, not the recursive size. The Java parser
 * accumulates child sizes when it sees LEAVE. That keeps the native side free
 * of any tree allocation.
 */
#define REC_DIR   1
#define REC_FILE  2
#define REC_LEAVE 3
#define REC_ERROR 4

#define WRITE_BUFFER_SIZE (64 * 1024)

typedef struct {
  char *buf;
  size_t used;
  size_t cap;
  int blockSize;
  int aborted;
  scan_stream_callback callback;
  void *ctx;
} Writer;

struct PendingDir {
  char *name;
  struct stat st;
  struct PendingDir *next;
};

static void put_u16(char *p, uint16_t v) {
  p[0] = (char) (v & 0xff);
  p[1] = (char) ((v >> 8) & 0xff);
}

static void put_u64(char *p, uint64_t v) {
  for (int i = 0; i < 8; i++) {
    p[i] = (char) ((v >> (8 * i)) & 0xff);
  }
}

static int flush_writer(Writer *w) {
  if (w->used == 0) {
    return 0;
  }
  if (w->callback(w->ctx, w->buf, w->used) != 0) {
    w->aborted = 1;
    return -1;
  }
  w->used = 0;
  return 0;
}

static int reserve(Writer *w, size_t need) {
  if (need > w->cap) {
    w->aborted = 1;
    return -1;
  }
  if (w->used + need > w->cap) {
    if (flush_writer(w) != 0) {
      return -1;
    }
  }
  return 0;
}

static long long blocks_for(const struct stat *st, int blockSize) {
  int ratio = blockSize / 512;
  if (ratio < 1) {
    ratio = 1;
  }
  return (long long) (st->st_blocks / ratio);
}

static const char *base_name(const char *path) {
  const char *slash = strrchr(path, '/');
  return slash == NULL ? path : slash + 1;
}

static char *make_path(const char *dir, const char *name) {
  size_t dirLen = strlen(dir);
  size_t nameLen = strlen(name);
  char *result = (char *) malloc(dirLen + nameLen + 2);
  if (result == NULL) {
    return NULL;
  }
  memcpy(result, dir, dirLen);
  result[dirLen] = '/';
  memcpy(result + dirLen + 1, name, nameLen);
  result[dirLen + 1 + nameLen] = 0;
  return result;
}

static int write_entry(Writer *w, uint8_t type, const struct stat *st,
                       const char *name) {
  size_t nameLen = strlen(name);
  if (nameLen > 0xffff) {
    nameLen = 0xffff;
  }
  if (reserve(w, 1 + 8 + 8 + 2 + nameLen) != 0) {
    return -1;
  }
  char *p = w->buf + w->used;
  p[0] = (char) type;
  put_u64(p + 1, (uint64_t) blocks_for(st, w->blockSize));
  put_u64(p + 9, (uint64_t) st->st_size);
  put_u16(p + 17, (uint16_t) nameLen);
  memcpy(p + 19, name, nameLen);
  w->used += 1 + 8 + 8 + 2 + nameLen;
  return 0;
}

static int write_leave(Writer *w) {
  if (reserve(w, 1) != 0) {
    return -1;
  }
  w->buf[w->used++] = (char) REC_LEAVE;
  return 0;
}

static int write_error(Writer *w, int isDir, int error,
                       const struct stat *st, const char *name) {
  size_t nameLen = strlen(name);
  if (nameLen > 0xffff) {
    nameLen = 0xffff;
  }
  if (reserve(w, 1 + 1 + 1 + 8 + 8 + 2 + nameLen) != 0) {
    return -1;
  }
  char *p = w->buf + w->used;
  p[0] = (char) REC_ERROR;
  p[1] = (char) (isDir ? 1 : 0);
  p[2] = (char) (error & 0xff);
  put_u64(p + 3, (uint64_t) blocks_for(st, w->blockSize));
  put_u64(p + 11, (uint64_t) st->st_size);
  put_u16(p + 19, (uint16_t) nameLen);
  memcpy(p + 21, name, nameLen);
  w->used += 1 + 1 + 1 + 8 + 8 + 2 + nameLen;
  return 0;
}

static void free_pending(struct PendingDir *pending) {
  while (pending != NULL) {
    struct PendingDir *next = pending->next;
    free(pending->name);
    free(pending);
    pending = next;
  }
}

static void scan_dir(Writer *w, const char *path, const struct stat *st,
                     dev_t dev) {
  if (w->aborted) {
    return;
  }

  DIR *dir = opendir(path);
  if (dir == NULL) {
    write_error(w, 1, errno, st, base_name(path));
    return;
  }

  if (write_entry(w, REC_DIR, st, base_name(path)) != 0) {
    closedir(dir);
    return;
  }

  struct PendingDir *pending = NULL;
  struct PendingDir *pendingTail = NULL;
  struct dirent *entry;

  while (!w->aborted && (entry = readdir(dir)) != NULL) {
    if (entry->d_name[0] == 0
        || (entry->d_name[0] == '.' && (
            entry->d_name[1] == 0
            || (entry->d_name[1] == '.' && entry->d_name[2] == 0)))) {
      continue;
    }

    char *childPath = make_path(path, entry->d_name);
    if (childPath == NULL) {
      w->aborted = 1;
      break;
    }

    struct stat childStat;
    if (lstat(childPath, &childStat) != 0) {
      free(childPath);
      continue;
    }
    if (childStat.st_dev != dev) {
      free(childPath);
      continue;
    }

    if (S_ISDIR(childStat.st_mode)) {
      struct PendingDir *node =
          (struct PendingDir *) malloc(sizeof(struct PendingDir));
      if (node == NULL) {
        free(childPath);
        w->aborted = 1;
        break;
      }
      node->name = strdup(entry->d_name);
      if (node->name == NULL) {
        free(node);
        free(childPath);
        w->aborted = 1;
        break;
      }
      node->st = childStat;
      node->next = NULL;
      if (pendingTail == NULL) {
        pending = node;
      } else {
        pendingTail->next = node;
      }
      pendingTail = node;
      free(childPath);
    } else {
      long long blocks = blocks_for(&childStat, w->blockSize);
      /* Match the old scanner: zero-block files carry no useful space. */
      if (blocks != 0) {
        write_entry(w, REC_FILE, &childStat, entry->d_name);
      }
      free(childPath);
    }
  }

  closedir(dir);

  struct PendingDir *node = pending;
  while (!w->aborted && node != NULL) {
    char *childPath = make_path(path, node->name);
    if (childPath == NULL) {
      w->aborted = 1;
      break;
    }
    scan_dir(w, childPath, &node->st, dev);
    free(childPath);
    node = node->next;
  }

  free_pending(pending);

  if (!w->aborted) {
    write_leave(w);
  }
}

int scan_tree_stream(const char *path, int blockSize,
                     scan_stream_callback callback, void *ctx) {
  if (path == NULL || callback == NULL || blockSize <= 0) {
    return -1;
  }

  Writer *w = (Writer *) calloc(1, sizeof(Writer));
  if (w == NULL) {
    return -1;
  }
  w->buf = (char *) malloc(WRITE_BUFFER_SIZE);
  if (w->buf == NULL) {
    free(w);
    return -1;
  }
  w->cap = WRITE_BUFFER_SIZE;
  w->blockSize = blockSize;
  w->callback = callback;
  w->ctx = ctx;

  struct stat st;
  if (lstat(path, &st) != 0) {
    struct stat empty;
    memset(&empty, 0, sizeof(empty));
    write_error(w, 1, errno, &empty, base_name(path));
  } else if (!S_ISDIR(st.st_mode)) {
    write_error(w, 0, ENOTDIR, &st, base_name(path));
  } else {
    scan_dir(w, path, &st, st.st_dev);
  }

  int result = w->aborted ? -1 : flush_writer(w);
  free(w->buf);
  free(w);
  return result;
}

#ifndef ANDROID_JNI_BUILD
static int stdout_callback(void *ctx, const char *data, size_t len) {
  (void) ctx;
  return fwrite(data, 1, len, stdout) == len ? 0 : -1;
}

int main(int argc, char **argv) {
  if (argc < 2 || strchr(argv[1], '/') == NULL) {
    fprintf(stderr, "usage: scan <absolute-path>\n");
    return 1;
  }
  return scan_tree_stream(argv[1], 512, stdout_callback, NULL) == 0 ? 0 : 1;
}
#endif
