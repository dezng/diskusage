package com.google.android.diskusage.render;

import com.google.android.diskusage.entity.FileSystemEntry;
import com.google.android.diskusage.opengl.DrawingCache;
import com.google.android.diskusage.opengl.RenderingThread;

/** Draws entries with the legacy OpenGL renderer. */
final class GpuEntryRenderBackend extends EntryRenderBackend {
  private final RenderingThread renderingThread;

  GpuEntryRenderBackend(RenderingThread renderingThread) {
    this.renderingThread = renderingThread;
  }

  @Override
  void drawEntry(FileSystemEntry entry, float x0, float y0, float x1, float y1) {
    RenderingThread.Square square = entry.children == null
        ? renderingThread.fileSquare : renderingThread.dirSquare;
    square.draw(x0, y0, x1, y1);
  }

  @Override
  void drawSpecialEntry(
      FileSystemEntry entry, float x0, float y0, float x1, float y1) {
    renderingThread.specialSquare.draw(x0, y0, x1, y1);
  }

  @Override
  void drawSmallEntry(
      FileSystemEntry entry, float x0, float y0, float x1, float y1) {
    renderingThread.smallSquare.draw(x0, y0, x1, y1);
  }

  @Override
  void drawName(FileSystemEntry entry, float x, float baseline,
      int elementWidth, boolean special) {
    DrawingCache cache = getDrawingCache(entry);
    cache.drawText(renderingThread, x, baseline, elementWidth - 5);
  }

  @Override
  void drawSize(FileSystemEntry entry, float x, float baseline,
      int elementWidth, boolean special) {
    DrawingCache cache = getDrawingCache(entry);
    cache.drawSize(renderingThread, x, baseline, elementWidth - 5);
  }

  @Override
  void drawCursor(float x0, float y0, float x1, float y1) {
    renderingThread.cursorSquare.drawFrame(x0, y0, x1, y1);
  }
}
