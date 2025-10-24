/**
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
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

package com.google.android.diskusage;

import android.system.OsConstants;
import android.util.Log;

import com.google.android.diskusage.DiskUsage.ProgressGenerator;
import com.google.android.diskusage.entity.FileSystemEntry;
import com.google.android.diskusage.entity.FileSystemFile;
import com.google.android.diskusage.jniutils.ScanUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Streaming native scanner.
 *
 * <p>The native side emits one binary record per filesystem entry. This class
 * parses those records as they arrive and builds the final
 * {@link FileSystemEntry} tree in a single pass. Directory sizes are summed
 * when the corresponding {@code LEAVE} record is received, and progress is
 * updated while scanning instead of after the whole tree has been built.</p>
 */
public class NativeScanner implements ProgressGenerator {

  private static final String TAG = "NativeScanner";

  // Must match app/src/main/cpp/scan.c.
  private static final int REC_DIR = 1;
  private static final int REC_FILE = 2;
  private static final int REC_LEAVE = 3;
  private static final int REC_ERROR = 4;

  private final int blockSize;

  private long pos;
  private FileSystemEntry lastCreatedFile;

  NativeScanner(int blockSize) {
    if (blockSize <= 0) {
      throw new IllegalArgumentException("Invalid block size: " + blockSize);
    }
    this.blockSize = blockSize;
  }

  @Override
  public FileSystemEntry lastCreatedFile() {
    return lastCreatedFile;
  }

  @Override
  public long pos() {
    return pos;
  }

  FileSystemEntry scan(MountPoint mountPoint) throws IOException, InterruptedException {
    Log.d(TAG, "scan start: " + mountPoint.getRoot());

    Parser parser = new Parser(blockSize);
    int result = ScanUtils.scanTree(mountPoint.getRoot(), blockSize, parser);
    if (result != 0) {
      throw new RuntimeException("Native scan failed: " + result);
    }

    FileSystemEntry root = parser.finish();
    Log.d(TAG, "scan done: " + root.name + ", entries=" + parser.entryCount);
    return root;
  }

  /**
   * Parses the native record stream and builds the final tree. A chunk always
   * ends at a record boundary, so no carry buffer is required.
   */
  private final class Parser implements ScanUtils.Listener {
    private static final int HEADER_SIZE = 1 + 8 + 8 + 2;
    private static final int ERROR_HEADER_SIZE = 1 + 1 + 1 + 8 + 8 + 2;

    private final int blockSize;
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private FileSystemEntry root;
    private int entryCount;

    Parser(int blockSize) {
      this.blockSize = blockSize;
    }

    @Override
    public void onData(byte[] data, int length) {
      int offset = 0;
      while (offset < length) {
        int type = data[offset++] & 0xff;

        if (type == REC_LEAVE) {
          leaveDirectory();
          continue;
        }

        if (type == REC_ERROR) {
          if (offset + ERROR_HEADER_SIZE - 1 > length) {
            throw new IllegalArgumentException("Truncated error record");
          }
          int isDir = data[offset++] & 0xff;
          int error = data[offset++] & 0xff;
          long blocks = readLong(data, offset);
          offset += 8;
          long bytes = readLong(data, offset);
          offset += 8;
          int nameLength = readUnsignedShort(data, offset);
          offset += 2;
          if (offset + nameLength > length) {
            throw new IllegalArgumentException("Truncated error name");
          }
          String name = new String(data, offset, nameLength, StandardCharsets.UTF_8);
          offset += nameLength;
          addError(name, isDir != 0, error, blocks, bytes);
          continue;
        }

        if (type != REC_DIR && type != REC_FILE) {
          throw new IllegalArgumentException("Unknown record type: " + type);
        }

        if (offset + HEADER_SIZE - 1 > length) {
          throw new IllegalArgumentException("Truncated entry record");
        }
        long blocks = readLong(data, offset);
        offset += 8;
        long bytes = readLong(data, offset);
        offset += 8;
        int nameLength = readUnsignedShort(data, offset);
        offset += 2;
        if (offset + nameLength > length) {
          throw new IllegalArgumentException("Truncated entry name");
        }
        String name = new String(data, offset, nameLength, StandardCharsets.UTF_8);
        offset += nameLength;

        if (type == REC_DIR) {
          enterDirectory(name, blocks, bytes);
        } else {
          addFile(name, blocks, bytes);
        }
      }
    }

    private void enterDirectory(String name, long blocks, long bytes) {
      Frame parent = stack.peek();
      FileSystemEntry entry = FileSystemFile.makeNode(
          parent == null ? null : parent.entry, name);
      entry.initSizeInBytesAndBlocks(bytes, blocks, blockSize);

      if (parent == null) {
        if (root != null) {
          throw new IllegalStateException("Multiple root directories");
        }
        root = entry;
      } else {
        parent.children.add(entry);
      }

      stack.push(new Frame(entry, blocks));
      lastCreatedFile = entry;
      entryCount++;
    }

    private void leaveDirectory() {
      if (stack.isEmpty()) {
        throw new IllegalStateException("Unbalanced LEAVE record");
      }
      Frame frame = stack.pop();
      long totalBlocks = frame.ownBlocks + frame.childBlocks;

      if (frame.children.isEmpty()) {
        frame.entry.children = null;
      } else {
        FileSystemEntry[] children = frame.children.toArray(
            new FileSystemEntry[frame.children.size()]);
        Arrays.sort(children, FileSystemEntry.COMPARE);
        frame.entry.children = children;
      }
      frame.entry.setSizeInBlocks(totalBlocks, blockSize);

      if (stack.isEmpty()) {
        root = frame.entry;
      } else {
        stack.peek().childBlocks += totalBlocks;
      }
    }

    private void addFile(String name, long blocks, long bytes) {
      Frame parent = stack.peek();
      if (parent == null) {
        throw new IllegalStateException("File outside a directory: " + name);
      }
      FileSystemEntry entry = FileSystemFile.makeNode(parent.entry, name);
      entry.initSizeInBytesAndBlocks(bytes, blocks, blockSize);
      parent.children.add(entry);
      parent.childBlocks += blocks;
      pos += blocks;
      lastCreatedFile = entry;
      entryCount++;
    }

    private void addError(String name, boolean isDir, int error,
                          long blocks, long bytes) {
      String displayName = errorName(name, error);
      Frame parent = stack.peek();
      // Error placeholders are informative only and must not be deletable.
      FileSystemEntry entry = FileSystemEntry.makeNode(
          parent == null ? null : parent.entry, displayName);
      entry.initSizeInBytesAndBlocks(bytes, blocks, blockSize);

      if (parent == null) {
        if (root == null) {
          root = entry;
        }
      } else {
        parent.children.add(entry);
        parent.childBlocks += blocks;
      }
      lastCreatedFile = entry;
      entryCount++;
    }

    FileSystemEntry finish() {
      if (root == null) {
        throw new IllegalStateException("Native scanner produced no root");
      }
      if (!stack.isEmpty()) {
        throw new IllegalStateException("Unbalanced directory stack");
      }
      return root;
    }
  }

  private static final class Frame {
    final FileSystemEntry entry;
    final ArrayList<FileSystemEntry> children = new ArrayList<>();
    final long ownBlocks;
    long childBlocks;

    Frame(FileSystemEntry entry, long ownBlocks) {
      this.entry = entry;
      this.ownBlocks = ownBlocks;
    }
  }

  private static int readUnsignedShort(byte[] data, int offset) {
    return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
  }

  private static long readLong(byte[] data, int offset) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value |= (long) (data[offset + i] & 0xff) << (8 * i);
    }
    return value;
  }

  private static String errorName(String name, int error) {
    if (error == OsConstants.EACCES) {
      return name + " <No access>";
    }
    if (error == OsConstants.ENOENT || error == OsConstants.ENOTDIR) {
      return name + " <deleted>";
    }
    return name + " <error>";
  }
}
