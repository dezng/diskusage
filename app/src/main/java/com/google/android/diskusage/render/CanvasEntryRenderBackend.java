package com.google.android.diskusage.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import com.google.android.diskusage.entity.FileSystemEntry;

/** Draws entries directly with an Android {@link Canvas}. */
final class CanvasEntryRenderBackend extends EntryRenderBackend {
  private final Canvas canvas;
  private final Matrix textureMatrix = new Matrix();

  CanvasEntryRenderBackend(Canvas canvas) {
    this.canvas = canvas;
  }

  @Override
  void drawEntry(FileSystemEntry entry, float x0, float y0, float x1, float y1) {
    boolean isFile = entry.children == null;
    drawTexture(isFile ? EntryRenderStyle.filePaint : EntryRenderStyle.dirPaint,
        isFile ? EntryRenderStyle.fileBitmap : EntryRenderStyle.dirBitmap,
        x0, y0, x1, y1);
  }

  @Override
  void drawSpecialEntry(
      FileSystemEntry entry, float x0, float y0, float x1, float y1) {
    drawTexture(EntryRenderStyle.specialPaint, EntryRenderStyle.specialBitmap,
        x0, y0, x1, y1);
  }

  @Override
  void drawSmallEntry(
      FileSystemEntry entry, float x0, float y0, float x1, float y1) {
    float width = x1 - x0;
    float height = y1 - y0;
    if (width <= 0 || height <= 0 || EntryRenderStyle.smallBitmap == null) return;

    // SmallSquare in the OpenGL path repeats the texture every 4 vertical
    // pixels instead of stretching it once across the entry.
    textureMatrix.setScale(
        width / EntryRenderStyle.smallBitmap.getWidth(), 0.25f);
    textureMatrix.postTranslate(x0, y0);
    EntryRenderStyle.smallPaint.getShader().setLocalMatrix(textureMatrix);
    canvas.drawRect(x0, y0, x1, y1, EntryRenderStyle.smallPaint);
  }

  private void drawTexture(Paint paint, Bitmap bitmap,
      float x0, float y0, float x1, float y1) {
    float width = x1 - x0;
    float height = y1 - y0;
    if (width <= 0 || height <= 0 || bitmap == null) return;

    textureMatrix.setScale(width / bitmap.getWidth(), height / bitmap.getHeight());
    textureMatrix.postTranslate(x0, y0);
    paint.getShader().setLocalMatrix(textureMatrix);
    canvas.drawRect(x0, y0, x1, y1, paint);
  }

  @Override
  void drawName(FileSystemEntry entry, float x, float baseline,
      int elementWidth, boolean special) {
    Paint paint = EntryRenderStyle.textPaint(special, entry.children == null);
    int maxWidth = elementWidth - 4;
    int length = paint.breakText(entry.name, true, maxWidth, null);
    canvas.drawText(entry.name, 0, length, x, baseline, paint);
  }

  @Override
  void drawSize(FileSystemEntry entry, float x, float baseline,
      int elementWidth, boolean special) {
    Paint paint = EntryRenderStyle.textPaint(special, entry.children == null);
    canvas.drawText(getDrawingCache(entry).getSizeString(), x, baseline, paint);
  }

  @Override
  void drawCursor(float x0, float y0, float x1, float y1) {
    canvas.drawRect(x0, y0, x1, y1, EntryRenderStyle.cursorFg);
  }
}
