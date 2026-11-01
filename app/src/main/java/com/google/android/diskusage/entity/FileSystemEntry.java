/**
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.google.android.diskusage.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;

import android.content.Context;

import com.google.android.diskusage.R;
import com.google.android.diskusage.opengl.DrawingCache;

public class FileSystemEntry {

  private static String n_bytes;
  private static String n_kilobytes;
  private static String n_megabytes;
  private static String n_megabytes10;
  private static String n_megabytes100;
  private static String n_gigabytes;
  private static String n_gigabytes10;
  private static String n_gigabytes100;
  private static String dir_name_size_num_dirs;
  private static String dir_empty;
  private static String dir_name_size;

  public boolean hasChildren() {
    return children != null && children.length != 0;
  }

  // Object Fields:


  // The size suitable for painting without any operations (and sorting)
  // Bit layout:
  // 40 bits      | 24 bits
  // sizeInBlocks | reminder
  // reminder is encoded file size information suitable for formating sizeString.
  // reminder:
  // 3 bits         | 21 bits  (2**18 = 44,040,192)
  // sizeMultiplier | size in multiplier of bytes
  // sizeMultiplier:
  // 000 = multiplier=1,              format=(n_bytes "%d bytes", size)
  // 001 = multiplier=1024,           format=(n_kilobytes "%d KiB", size)
  // 010 = multiplier=1024,           format=(n_megabytes "%5.2f MiB", size / 1024.f)
  // 011 = multiplier=1024,           format=(n_megabytes10 "%5.1f MiB", size / 1024.f)
  // 100 = multiplier=1024*1024,      format=(n_megabytes100 "%d MiB", size)
  // 101 = multiplier=1024*1024,      format=(n_gigabytes "%5.2f GiB", size/ 1024.f)
  // 110 = multiplier=1024*1024,      format=(n_gigabytes10 "%5.1f GiB", size/ 1024.f)
  // 111 = multiplier=1024*1024*1024, format=(n_gigabytes100 "%d GiB", size)

  // Ranges for sizeMultipliers:
  // 0: sz < 1024:               "%4.0f bytes", sz
  // 1: sz < 1024 * 1024:        "%4.0f KiB", sz * (1f / 1024)
  // 2: sz < 1024 * 1024 * 10:   "%5.2f MiB", sz * (1f / 1024 / 1024)
  // 3: sz < 1024 * 1024 * 200:  "%5.1f MiB", sz * (1f / 1024 / 1024)
  // 4: sz >= 1024 * 1024 * 200: "%4.0f MiB", sz * (1f / 1024 / 1024)
  //
  // FIXME: remove outdate info:
  // reminder can be 0..blockSize (inclusive)
  // size in bytes = (size in blocks * blockSize) + reminder - blockSize;
  // FIXME: make private and update code which uses it
  public long encodedSize;
  public FileSystemEntry parent;
  public FileSystemEntry[] children;
  public String name;
//  public String sizeString;
  public DrawingCache drawingCache;

  private static final int MULTIPLIER_SHIFT=18;

  private static final int MULTIPLIER_MASK = 7 << MULTIPLIER_SHIFT;
  private static final int MULTIPLIER_BYTES = 0 << MULTIPLIER_SHIFT;
  private static final int MULTIPLIER_KBYTES = 1 << MULTIPLIER_SHIFT;
  private static final int MULTIPLIER_MBYTES = 2 << MULTIPLIER_SHIFT;
  private static final int MULTIPLIER_MBYTES10 = 3 << MULTIPLIER_SHIFT;
  private static final int MULTIPLIER_MBYTES100 = 4 << MULTIPLIER_SHIFT;
  private static final int MULTIPLIER_GBYTES = 5 << MULTIPLIER_SHIFT;
  private static final int MULTIPLIER_GBYTES10 = 6 << MULTIPLIER_SHIFT;
  private static final int MULTIPLIER_GBYTES100 = 7 << MULTIPLIER_SHIFT;
  private static final int SIZE_MASK = (1 << MULTIPLIER_SHIFT) - 1;

//  static int blockSize;
  // will take for a while to make this break
  // 16Mb block size on mobile device... probably in year 2020.
  // probably 32 bits for maximum number of block will break before ~2016
  public static final int blockOffset = 24;
  static final long blockMask = (1l << blockOffset) - 1;

  public long getSizeInBlocks() {
    return encodedSize >> blockOffset;
  }

  public long getSizeForRendering() {
    return encodedSize & ~blockMask;
  }

  public static String calcSizeStringFromEncoded(long encodedSize) {
    int size = SIZE_MASK & (int)encodedSize;
    switch (MULTIPLIER_MASK & (int)encodedSize) {
    case MULTIPLIER_BYTES: return String.format(n_bytes, size);
    case MULTIPLIER_KBYTES: return String.format(n_kilobytes, size);
    case MULTIPLIER_MBYTES: return String.format(n_megabytes, size * (1f / 1024));
    case MULTIPLIER_MBYTES10: return String.format(n_megabytes10, size * (1f / 1024));
    case MULTIPLIER_MBYTES100: return String.format(n_megabytes100, size);
    case MULTIPLIER_GBYTES: return String.format(n_gigabytes, size * (1f / 1024));
    case MULTIPLIER_GBYTES10: return String.format(n_gigabytes10, size * (1f / 1024));
    case MULTIPLIER_GBYTES100: return String.format(n_gigabytes100, size);
    }
    return "";
  }

  public void clearDrawingCache() {
    if (drawingCache != null) {
      drawingCache.resetSizeString();
    }
  }

  private long makeBytesPart(long size) {
    if (size < 1024) return size;
    if (size < 1024 * 1024) return MULTIPLIER_KBYTES | (size >> 10);
    if (size < 1024 * 1024 * 10 ) return MULTIPLIER_MBYTES | (size >> 10);
    if (size < 1024 * 1024 * 200) return MULTIPLIER_MBYTES10 | (size >> 10);
    if (size < (long) 1024 * 1024 * 1024) return MULTIPLIER_MBYTES100 | (size >> 20);
    if (size < (long) 1024 * 1024 * 1024 * 10) return MULTIPLIER_GBYTES | (size >> 20);
    if (size < (long) 1024 * 1024 * 1024 * 200) return MULTIPLIER_GBYTES10 | (size >> 20);
    return MULTIPLIER_GBYTES100 | (size >> 30);
  }

  public boolean isDeletable() {
    return false;
  }

  public void setSizeInBlocks(long blocks, int blockSize) {
    long bytes = blocks * blockSize;
    encodedSize = (blocks << blockOffset) | makeBytesPart(bytes);
  }

  public FileSystemEntry initSizeInBytes(long bytes, int blockSize) {
    long blocks = (bytes + blockSize - 1) / blockSize;
    encodedSize = (blocks << blockOffset) | makeBytesPart(bytes);
    return this;
  }

  public FileSystemEntry initSizeInBytesAndBlocks(long bytes, long blocks, int blockSize) {
    encodedSize = (blocks << blockOffset) | makeBytesPart(bytes);
    return this;
  }

  public FileSystemEntry setChildren(FileSystemEntry[] children, int blockSize) {
    this.children = children;
    long blocks = 0;
    if (children == null) return this;
    for (int i = 0; i < children.length; i++) {
      blocks += children[i].getSizeInBlocks();
      children[i].parent = this;
    }
    setSizeInBlocks(blocks, blockSize);
    return this;
  }


  public static class ExcludeFilter {
    public final Map<String, ExcludeFilter> childFilter;

    private static void addEntry(
        TreeMap<String, ArrayList<String>> filter, String name, String value) {
      ArrayList<String> entry = filter.get(name);
      if (entry == null) {
        entry = new ArrayList<String>();
        filter.put(name, entry);
      }
      entry.add(value);
    }

    public ExcludeFilter(ArrayList<String> exclude_paths) {
      if (exclude_paths == null) {
        this.childFilter = null;
        return;
      }
      TreeMap<String, ArrayList<String>> filter =
        new TreeMap<String, ArrayList<String>>();
      for(String path : exclude_paths) {
        String[] parts = path.split("/", 2);
        if (parts.length < 2) {
          addEntry(filter, path, null);
        } else {
          addEntry(filter, parts[0], parts[1]);
        }
      }
      TreeMap<String, ExcludeFilter> excludeFilter = new TreeMap<String, ExcludeFilter>();
      for (Entry<String, ArrayList<String>> entry : filter.entrySet()) {
        boolean has_null = false;
        for (String part : entry.getValue()) {
          if (part == null) {
            has_null = true;
            break;
          }
        }
        if (has_null) {
          excludeFilter.put(entry.getKey(), new ExcludeFilter(null));
        } else {
          excludeFilter.put(entry.getKey(), new ExcludeFilter(entry.getValue()));
        }
      }
      this.childFilter = excludeFilter;
    }
  }

  protected FileSystemEntry(FileSystemEntry parent, String name) {
    this.name = name;
    this.parent = parent;
  }

  public static FileSystemEntry makeNode(
      FileSystemEntry parent, String name) {
    return new FileSystemEntry(parent, name);
  }

  public static class Compare implements Comparator<FileSystemEntry> {
    @Override
    public final int compare(FileSystemEntry aa, FileSystemEntry bb) {
      if (aa.encodedSize == bb.encodedSize) {
        return 0;
      }
      return aa.encodedSize < bb.encodedSize ? 1 : -1;
    }
  }

  /**
   * For sorting according to size.
   */
  public static Compare COMPARE = new Compare();

  public FileSystemEntry create() {
    return new FileSystemEntry(null, this.name);
  }

  public class SearchInterruptedException extends RuntimeException {
    private static final long serialVersionUID = -3986013022885904101L;
  };

  public FileSystemEntry copy() {
    if (Thread.interrupted()) throw new SearchInterruptedException();
    FileSystemEntry copy = create();
    if (this.children != null) {
      FileSystemEntry[] children = new FileSystemEntry[this.children.length];
      for (int i = 0; i < this.children.length; i++) {
        FileSystemEntry childCopy = children[i] = this.children[i].copy();
        childCopy.parent = copy;
      }
      copy.children = children;
    }
    copy.encodedSize = this.encodedSize;
    return copy;
  }

  public FileSystemEntry filterChildren(CharSequence pattern, int blockSize) {
//    res = Pattern.compile(Pattern.quote(pattern.toString()), Pattern.CASE_INSENSITIVE).matcher(name).find();

    if (children == null) return null;
    ArrayList<FileSystemEntry> filtered_children = new ArrayList<FileSystemEntry>();

    for (FileSystemEntry child : this.children) {
      FileSystemEntry childCopy = child.filter(pattern, blockSize);
      if (childCopy != null) {
        filtered_children.add(childCopy);
      }
    }
    if (filtered_children.size() == 0) return null;
    FileSystemEntry[] children = new FileSystemEntry[filtered_children.size()];
    filtered_children.toArray(children);
    Arrays.sort(children, COMPARE);
    FileSystemEntry copy = create();
    copy.children = children;
    long size = 0;


    for (FileSystemEntry child : children) {
      size += child.getSizeInBlocks();
      child.parent = copy;
    }
    copy.setSizeInBlocks(size, blockSize);
    return copy;
  }

  public FileSystemEntry filter(CharSequence pattern, int blockSize) {
    if (name.toLowerCase().contains(pattern)) {
      return copy();
    }
    return filterChildren(pattern, blockSize);
  }

  /**
   * Find index of directChild in 'children' field of this entry.
   * @param directChild
   * @return index of the directChild in 'children' field.
   */
  public final int getIndexOf(FileSystemEntry directChild) {
    FileSystemEntry[] children0 = children;
    int len = children0.length;
    int i;

    for (i = 0; i < len; i++) {
      if (children0[i] == directChild) return i;
    }

    throw new RuntimeException("something broken");
  }

  /**
   * Find entry which follows this entry in its the parent.
   * @return next entry in the same parent or this entry if there is no more entries
   */
  public final FileSystemEntry getNext() {
    int index = parent.getIndexOf(this);
    if (index + 1 == parent.children.length) return this;
    return parent.children[index + 1];
  }

  /**
   * Find entry which precedes this entry in its the parent.
   * @return previous entry in the same parent or this entry if the entry is first
   */
  public final FileSystemEntry getPrev() {
    int index = parent.getIndexOf(this);
    if (index == 0) return this;
    return parent.children[index - 1];
  }

  /**
   * Calculate size string for specified file length in bytes.
   *
   * Currently used by delete activity preview file list loader.
   *
   * @param sz file size in bytes
   * @return formated size string
   */
  public static String calcSizeString(float sz) {
    if (sz < 1024 * 1024 * 10) {
      if (sz < 1024 * 1024) {
        if (sz < 1024) {
          if (sz < 0) sz = 0;
          return String.format(n_bytes, (int)sz);
        }
        return String.format(n_kilobytes, (int)(sz * (1f / 1024)));
      }
      return String.format(n_megabytes, sz * (1f / 1024 / 1024));
    }
    if (sz < 1024 * 1024 * 200) {
      return String.format(n_megabytes10, sz * (1f / 1024 / 1024));
    }
    return String.format(n_megabytes100, (int)(sz * (1f / 1024 / 1024)));
  }

  public final String sizeString() {
    return calcSizeStringFromEncoded(encodedSize);
  }

//  public final String pathFromRoot(FileSystemEntry root) {
//    String res = toTitleString();
//    if (parent == root) return res;
//    else return parent.relativePath(root) + "/" + res;
//  }

  public final String toTitleString() {
    String sizeString0 = sizeString();
    if (children != null && children.length != 0)
      return String.format(dir_name_size_num_dirs, name, sizeString0, children.length);
    else if (getSizeInBlocks() == 0) {
      return String.format(dir_empty, name);
    } else {
      return String.format(dir_name_size, name, sizeString0);
    }
  }

  public final String path2() {
    ArrayList<String> pathElements = new ArrayList<String>();
    FileSystemEntry current = this;
    while (current != null) {
      pathElements.add(current.name);
      current = current.parent;
    }
    pathElements.remove(pathElements.size() - 1);
    pathElements.remove(pathElements.size() - 1);
    StringBuilder path = new StringBuilder();
    String sep = "";
    for (int i = pathElements.size() - 1; i >= 0; i--) {
      path.append(sep);
      path.append(pathElements.get(i));
      sep = "/";
    }
    return path.toString();
  }

  public final String absolutePath() {
    if (parent == null) {
      // FIXME: to prevent crash appeared on some users.
      return name;
    }
    if (this instanceof FileSystemRoot) {
      return ((FileSystemRoot)this).rootPath;
    }
    return parent.absolutePath() + "/" + name;
  }

  /**
   * Find depth of 'entry' in current element.
   * @param entry
   * @return 1 for depth equal 1 and so on
   */
  public final int depth(FileSystemEntry entry) {
    int d = 0;
    FileSystemEntry root = this;

    while(entry != root) {
      entry = entry.parent;
      d++;
    }
    return d;
  }

  /**
   * Find and return entry on specified depth and offset in this entry used as root.
   * @param maxDepth
   * @param offset
   * @return nearest entry to the specified conditions
   */
  public final FileSystemEntry findEntry(int maxDepth, long offset) {
    long currOffset = 0;
    FileSystemEntry entry = this;
    FileSystemEntry[] children0 = children;
    // Log.d(TAG, "Starting entry search at " + entry.name);

    for (int depth = 0; depth < maxDepth; depth++) {
      int nchildren = children0.length;
      // Log.d(TAG, "  Entry = " + entry.name);
      for (int c = 0; c < nchildren; c++) {
        FileSystemEntry e = children0[c];
        long size = e.getSizeForRendering();
        if (currOffset + size < offset) {
          currOffset += size;
          continue;
        }

        // found entry
        entry = e;
        children0 = e.children;
        if (children0 == null) return entry;
        break;
      }

    }
    return entry;
  }

  /**
   * Returns offset in bytes (world coordinates) from start of this
   * object to the start of 'cursor' object.
   * @param cursor
   * @return offset in bytes
   */
  public final long getOffset(FileSystemEntry cursor) {
    long offset = 0;
    FileSystemEntry dir;
    FileSystemEntry root = this;

//    Log.d(TAG, "getOffset()");

    while (cursor != root) {
//      Log.d(TAG, "cursor = " + (cursor != null) + " root = " + (root != null));
//      Log.d(TAG, "cursor = " + cursor.name + " root = " + root.name);
      dir = cursor.parent;
      FileSystemEntry[] children = dir.children;
      int len = children.length;

      for (int i = 0; i < len; i++) {
        FileSystemEntry e = children[i];
        if (e == cursor) break;
        offset += e.getSizeForRendering();
      }
      cursor = dir;
    }
    return offset;
  }

  // FIXME: no resort needed
  public final void remove(int blockSize) {
    FileSystemEntry[] children0 = parent.children;
    int len = children0.length;
    for (int i = 0; i < len; i++) {
      if (children0[i] != this) continue;

      // executed only once:
      parent.children = new FileSystemEntry[len - 1];
      System.arraycopy(children0, 0, parent.children, 0, i);
      System.arraycopy(children0, i + 1, parent.children, i, len - i - 1);
      //java.util.Arrays.sort(parent.children, this);

      FileSystemEntry parent0 = parent;

      long blocks = getSizeInBlocks();

      while (parent0 != null) {
        parent0.setSizeInBlocks(parent0.getSizeInBlocks() - blocks, blockSize);
        parent0.clearDrawingCache();
        java.util.Arrays.sort(parent0.children, FileSystemEntry.COMPARE);
        parent0 = parent0.parent;
      }
      return;
    }
    // FIXME: the exception was thrown somehow
    // throw new RuntimeException("child is not found: " + this);
  }

  public final void insert(FileSystemEntry newEntry, int blockSize) {
    FileSystemEntry[] children0 = new FileSystemEntry[children.length + 1];
    System.arraycopy(children, 0, children0, 0, children.length);
    children0[children.length] = newEntry;
    children = children0;
    newEntry.parent = this;
    FileSystemEntry parent0 = this;
    long blocks = newEntry.getSizeInBlocks();

    while (parent0 != null) {
      java.util.Arrays.sort(children, COMPARE);
      parent0.setSizeInBlocks(parent0.getSizeInBlocks() + blocks, blockSize);
      parent0.clearDrawingCache();
      parent0 = parent0.parent;
    }
  }

  /**
   * Walks through the path and finds the specified entry, null otherwise.
   * @param exactMatch TODO
   */
  public FileSystemEntry getEntryByName(String path, boolean exactMatch) {
    String[] pathElements = path.split("/");
    FileSystemEntry entry = this;

    outer:
      for (int i = 0; i < pathElements.length; i++) {
        String name = pathElements[i];
        FileSystemEntry[] children = entry.children;
        if (children == null) {
          return null;
        }
        for (int j = 0; j < children.length; j++) {
          entry = children[j];
          if (name.equals(entry.name)) {
            continue outer;
          }
        }
        return null;
      }
    return entry;
  }

  public static void setupStrings(Context context) {
    if (n_bytes != null) return;
    n_bytes = context.getString(R.string.n_bytes);
    n_kilobytes = context.getString(R.string.n_kilobytes);
    n_megabytes = context.getString(R.string.n_megabytes);
    n_megabytes10 = context.getString(R.string.n_megabytes10);
    n_megabytes100 = context.getString(R.string.n_megabytes100);
    n_gigabytes = context.getString(R.string.n_gigabytes);
    n_gigabytes10 = context.getString(R.string.n_gigabytes10);
    n_gigabytes100 = context.getString(R.string.n_gigabytes100);
    dir_name_size_num_dirs = context.getString(R.string.dir_name_size_num_dirs);
    dir_empty = context.getString(R.string.dir_empty);
    dir_name_size = context.getString(R.string.dir_name_size);
  }

//  public final void getAllChildren(List<String> out, FileSystemEntry deleteRoot) {
//    FileSystemEntry[] sortedChildren = new FileSystemEntry[children.length];
//    System.arraycopy(children, 0, sortedChildren, 0, children.length);
//    Arrays.sort(sortedChildren, alphaComparator);
//    for (int i = 0; i < children.length; i++) {
//      FileSystemEntry child = children[i];
//      if (child.children != null) child.getAllChildren(out, deleteRoot);
//      else out.add(child.pathFromRoot(deleteRoot));
//    }
//  }

  private void validate0() {
    if (parent != null) {
      parent.getIndexOf(this);
      validateRecursive();
      parent.validate0();
      return;
    }
    validateRecursive();
  }

  private void validateRecursive() {
    if (children == null) return;
    for (int i = 0; i < children.length; i++) {
      if (children[i].parent != this) throw new RuntimeException("corrupted: " + this.path2() + " <> " + children[i].name);
      children[i].validateRecursive();
    }
  }

  /**
   * Just files, no directories.
   * @return count
   */
  public int getNumFiles() {
    if (this instanceof FileSystemEntrySmall) {
      return ((FileSystemEntrySmall)this).numFiles;
    }

    if (children == null) return 1;

    int numFiles = 0;
    boolean hasFile = false;
    for (FileSystemEntry entry : children) {
      if (entry.children == null) hasFile = true;
      numFiles += entry.getNumFiles();
    }
    if (hasFile) numFiles++;
    return numFiles;
  }


  @Override
  public String toString() {
    return "{" +
            "\"name\":\"" + name +
            "\",\"encodedSize\":" + encodedSize +
            ",\"children\":" + Arrays.toString(children) +
            '}';
  }
}
