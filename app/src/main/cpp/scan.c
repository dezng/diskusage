#include "scan.h"

dev_t dev;
static const int sep = 0;
static int nfiles = 0;


void console_callback(const char *output) {
    printf("%s", output);
    fflush(stdout);
}

const char *getName(const char *path) {
  return strrchr(path, '/') + 1;
}

const char *get_error() {
  switch(errno) {
    case EACCES:
      return "%c%s <No access>";
    case ENOENT:
    case ENOTDIR:
      return "%c%s <deleted>";
    default:
      return "%c%s <error>";
  }
}

void dump_file(struct Entity *entity, scan_callback callback) {
    char buffer[1024];
    int len;

    if (entity->isdir) {
        len = snprintf(buffer, sizeof(buffer), "D%s%c%lld%c%lld%c", entity->name, sep,
                       entity->sizeInBlocks, sep, entity->sizeInBytes, sep);
    } else {
        len = snprintf(buffer, sizeof(buffer), "F%s%c%lld%c%lld%c", entity->name, sep,
                       entity->sizeInBlocks, sep, entity->sizeInBytes, sep);
    }

    if (len > 0 && len < sizeof(buffer)) {
        callback(buffer);
    }

    nfiles++;
    if (nfiles % 10 == 0) {

    }
}

void dump(char type, scan_callback callback) {
    char buffer[16];
    int len = snprintf(buffer, sizeof(buffer), "%c", type);
    if (len > 0 && len < sizeof(buffer)) {
        callback(buffer);
    }
}

void dump_error(char type, const char *path,
                long long sizeInBlocks, long long sizeInBytes, scan_callback callback) {
    char buffer[1024];
    int len;

    const char *error_msg = get_error();
    len = snprintf(buffer, sizeof(buffer), error_msg, type, getName(path), sep, sizeInBlocks, sep,
                   sizeInBytes, sep);

    if (len > 0 && len < sizeof(buffer)) {
        callback(buffer);
    }
}

struct Entity *make_entity_internal(
    const char *path,
    struct stat *stbuf) {
  struct Entity *e = malloc(sizeof(struct Entity));
  e->name = strdup(getName(path));
  e->sizeInBlocks = stbuf->st_blocks;
  e->sizeInBytes = stbuf->st_size;
  e->isdir = S_ISDIR(stbuf->st_mode);
  return e;
}

struct Entity *make_entity(const char *path) {
  struct stat stbuf;
  int res = lstat(path, &stbuf);
  if (res < 0) {
    return NULL;
  }
  if (stbuf.st_dev != dev) {
    return NULL;
  }
  return make_entity_internal(path, &stbuf);
}

char *makePath(const char *base, const char *name) {
  int baseLen = strlen(base);
  int nameLen = strlen(name);
  char *res = (char*) malloc(baseLen + nameLen + 2);
  strncpy(res, base, baseLen);
  res[baseLen] = '/';
  strncpy(res + baseLen + 1, name, nameLen);
  res[baseLen + 1 + nameLen] = 0;
  return res;
}

void scan_dir(const char *path, struct Entity *dirEntity, scan_callback callback) {
  DIR *dir = opendir(path);
  struct Entity *e;
  struct Entity *curr;
  struct Entity *prev;
  struct Entity *first;
  struct Entity **last = &first;

  if (dir == NULL) {
    dump_error('D', path, dirEntity->sizeInBlocks,
               dirEntity->sizeInBytes, callback);
      dump('Z', callback);
    return;
  }
    dump_file(dirEntity, callback);

  struct dirent *entity;
  while ((entity = readdir(dir)) != NULL) {
    if (entity->d_name[0] == 0 || (entity->d_name[0] == '.' && (
          entity->d_name[1] == 0 || (
            entity->d_name[1] == '.' && entity->d_name[2] == 0))
        )) continue;
    const char *entityPath = makePath(path, entity->d_name);
    struct Entity *e = make_entity(entityPath);
    free((void*)entityPath);
    if (e == NULL) continue;
    if (!e->isdir) {
        dump_file(e, callback);
      free((void*)e->name);
      free(e);
      continue;
    }
    *last = e;
    last = &(e->next);
  }
  *last = NULL;

  closedir(dir);

  curr = first;

  while (curr != NULL) {
    const char *entityPath = makePath(path, curr->name);
      scan_dir(entityPath, curr, callback);
    free((void*)entityPath);
    free((void*)curr->name);
    prev = curr;
    curr = curr->next;
    free(prev);
  }
    dump('Z', callback);
}

void scan_tree(const char *path, scan_callback callback) {
  struct stat stbuf;
  int res = lstat(path, &stbuf);
  if (res == -1) {
      dump_error('D', path, 1, 0, callback);
      dump('Z', callback);
    return;
  }
  dev = stbuf.st_dev;
    scan_dir(path, make_entity_internal(path, &stbuf), callback);
}


#ifndef ANDROID_JNI_BUILD
int main(int argc, char **argv) {
  if (argv[1] == 0) {
    printf("Need directory argument\n");
    exit(1);
  }
  if (strchr(argv[1], '/') == NULL) {
    printf("Need absolute path\n");
    exit(1);
  }
  void (*callback)(const char *) = console_callback;
  dump(0, console_callback);

  scan_tree(argv[1], callback);
  return 0;
}
#endif


