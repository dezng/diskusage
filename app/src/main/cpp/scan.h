//
// Created by zhe deng on 2025/10/23.
//

#ifndef DISKUSAGE_SCAN_H
#define DISKUSAGE_SCAN_H

#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <dirent.h>
#include <stdlib.h>


struct Entity {
  long long sizeInBlocks;
  long long sizeInBytes;
  const char *name;
  struct Entity *next;
  char isdir;
};


typedef void (*scan_callback)(const char* output);


void scan_tree(const char* path, scan_callback callback);
void scan_dir(const char *path, struct Entity *entity, scan_callback callback);
const char *getName(const char *path);
void dump_file(struct Entity *entity, scan_callback callback);
void dump(char type, scan_callback callback);
const char *get_error();
void dump_error(char type, const char *path,
    long long sizeInBlocks, long long sizeInBytes, scan_callback callback);
struct Entity *make_entity_internal(
    const char *path,
    struct stat *stbuf);
struct Entity *make_entity(const char *path);
char *makePath(const char *base, const char *name);

#endif //DISKUSAGE_SCAN_H




