package com.google.android.diskusage.render;

import com.google.android.diskusage.entity.FileSystemEntry;
import com.google.android.diskusage.opengl.DrawingCache;

/**
 * Low level drawing operations needed by {@link EntryRenderer}.
 *
 * <p>The traversal and clipping logic lives in EntryRenderer. Subclasses only
 * translate the operations to their drawing API (Canvas or OpenGL).
 */
abstract class EntryRenderBackend {
  abstract void drawEntry(
      FileSystemEntry entry, float x0, float y0, float x1, float y1);

  abstract void drawSpecialEntry(
      FileSystemEntry entry, float x0, float y0, float x1, float y1);

  abstract void drawSmallEntry(
      FileSystemEntry entry, float x0, float y0, float x1, float y1);

  abstract void drawName(
      FileSystemEntry entry, float x, float baseline, int elementWidth,
      boolean special);

  abstract void drawSize(
      FileSystemEntry entry, float x, float baseline, int elementWidth,
      boolean special);

  abstract void drawCursor(float x0, float y0, float x1, float y1);

  protected final DrawingCache getDrawingCache(FileSystemEntry entry) {
    if (entry.drawingCache == null) {
      entry.drawingCache = new DrawingCache(entry);
    }
    return entry.drawingCache;
  }
}
