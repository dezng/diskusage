package com.google.android.diskusage.render;

import android.graphics.Canvas;
import android.graphics.Rect;

import com.google.android.diskusage.Cursor;
import com.google.android.diskusage.entity.FileSystemEntry;
import com.google.android.diskusage.opengl.RenderingThread;

/**
 * Shared traversal for drawing the disk map.
 *
 * <p>The backend only knows how to draw a rectangle or a piece of text. All
 * geometry, clipping and recursion stays here, so the Canvas and OpenGL
 * renderers cannot drift apart.
 */
public final class EntryRenderer {
  private final EntryRenderBackend backend;

  private EntryRenderer(EntryRenderBackend backend) {
    this.backend = backend;
  }

  public static EntryRenderer forCanvas(Canvas canvas) {
    return new EntryRenderer(new CanvasEntryRenderBackend(canvas));
  }

  public static EntryRenderer forGpu(RenderingThread renderingThread) {
    return new EntryRenderer(new GpuEntryRenderBackend(renderingThread));
  }

  public void draw(FileSystemEntry root, Rect bounds, Cursor cursor,
      long viewTop, float viewDepth, float yscale, int screenHeight,
      int numSpecialEntries, FileSystemEntry deletingEntry) {
    // scale conversion:
    // window_y = yscale * world_y
    // world_y  = window_y / yscale

    // offset conversion:
    // window_y = yscale * (world_y  - rootOffset)
    // world_y  = window_y / yscale + rootOffset

    int viewLeft = (int) (viewDepth * EntryRenderStyle.elementWidth);

    // screen clip area to world conversion:
    long clipTop = (long) (bounds.top / yscale) + viewTop;
    long clipBottom = (long) (bounds.bottom / yscale) + viewTop;
    int clipLeft = bounds.left + viewLeft;
    int clipRight = bounds.right + viewLeft;
    float xoffset = -viewLeft;
    float yoffset = -viewTop * yscale;

    // X coords:
    // xoffset - screen position of current object on the screen
    // clip_x0, clip_x1 - clip area in coords of current object
    // screen_clip_x0 = xoffset + clip_x0
    // screen_clip_x1 = xoffset + clip_x1

    // Y coords:
    // yoffset - screen position of current object on the screen
    // clip_y0, clip_y1 - clip area in world coords relative to current object
    // screen_clip_y0 = yscale * (clip_y0 - elementOffset)
    // screen_clip_y1 = yscale * (clip_y1 - elementOffset)

    drawEntries(root.getSizeForRendering(), root.children,
        xoffset, yoffset, yscale, clipLeft, clipRight,
        clipTop, clipBottom, screenHeight, deletingEntry);

    drawSpecialEntries(root.getSizeForRendering(), root.children,
        xoffset, yoffset, yscale, clipLeft, clipRight,
        clipTop, clipBottom, screenHeight, numSpecialEntries);

    // paint position
    float cursorLeft = cursor.depth * EntryRenderStyle.elementWidth + xoffset;
    float cursorTop = (cursor.top - viewTop) * yscale;
    float cursorRight = cursorLeft + EntryRenderStyle.elementWidth;
    float cursorBottom = cursorTop + cursor.position.getSizeForRendering() * yscale;
    backend.drawCursor(cursorLeft, cursorTop, cursorRight, cursorBottom);
  }

  private void drawEntries(long parentSize, FileSystemEntry[] entries,
      float xoffset, float yoffset, float yscale,
      long clipLeft, long clipRight, long clipTop, long clipBottom,
      int screenHeight, FileSystemEntry deletingEntry) {

    if (entries == null || entries.length == 0) return;

    FileSystemEntry[] children = entries;
    int len = children.length;
    long childClipTop = clipTop;
    long childClipBottom = clipBottom;
    float childXOffset = xoffset + EntryRenderStyle.elementWidth;

    for (int i = 0; i < len; i++) {
      FileSystemEntry child = children[i];
      long childSize = child.getSizeForRendering();
      parentSize -= childSize;

      float top = yoffset;
      float bottom = top + childSize * yscale;

      if (childClipTop > childSize) {
        childClipTop -= childSize;
        childClipBottom -= childSize;
        yoffset = bottom;
        continue;
      }

      if (childClipBottom < 0) {
        return;
      }

      FileSystemEntry[] grandChildren = child.children;
      if (grandChildren != null) {
        drawEntries(child.getSizeForRendering(), grandChildren,
            childXOffset, yoffset, yscale,
            clipLeft - EntryRenderStyle.elementWidth,
            clipRight - EntryRenderStyle.elementWidth,
            childClipTop, childClipBottom, screenHeight, deletingEntry);
      }

      if (bottom - top < 4 && deletingEntry != child) {
        bottom += parentSize * yscale;
        backend.drawSmallEntry(child, xoffset, top, childXOffset, bottom);
        return;
      }

      if (clipLeft < EntryRenderStyle.elementWidth) {
        backend.drawEntry(child, xoffset, top, childXOffset, bottom);

        if (bottom - top > EntryRenderStyle.fontSize * 2) {
          float pos = textCenter(top, bottom, screenHeight);
          backend.drawName(child, xoffset + 2, pos - EntryRenderStyle.descent,
              EntryRenderStyle.elementWidth, false);
          backend.drawSize(child, xoffset + 2, pos - EntryRenderStyle.ascent,
              EntryRenderStyle.elementWidth, false);
        } else if (bottom - top > EntryRenderStyle.fontSize) {
          float baseline = (top + bottom
              - EntryRenderStyle.ascent - EntryRenderStyle.descent) / 2;
          backend.drawName(child, xoffset + 2, baseline,
              EntryRenderStyle.elementWidth, false);
        }
      }

      childClipTop -= childSize;
      childClipBottom -= childSize;
      yoffset = bottom;
    }
  }

  private void drawSpecialEntries(long parentSize, FileSystemEntry[] entries,
      float xoffset, float yoffset, float yscale,
      long clipLeft, long clipRight, long clipTop, long clipBottom,
      int screenHeight, int numSpecial) {

    if (numSpecial == 0 || entries == null || entries.length == 0) return;

    // Deep one level in hierarchy:
    entries = entries[0].children;
    if (entries == null || entries.length == 0) return;

    xoffset += EntryRenderStyle.elementWidth;
    clipLeft -= EntryRenderStyle.elementWidth;
    clipRight -= EntryRenderStyle.elementWidth;

    int len = entries.length;
    long childClipTop = clipTop;
    long childClipBottom = clipBottom;
    float childXOffset = xoffset + EntryRenderStyle.elementWidth;

    // Fast skip ordinary entries, FIXME: make root node special node with extra
    // field to get rid of this
    for (int i = 0; i < len - numSpecial; i++) {
      FileSystemEntry child = entries[i];
      long childSize = child.getSizeForRendering();
      parentSize -= childSize;

      float top = yoffset;
      float bottom = top + childSize * yscale;

      if (childClipBottom < 0) {
        return;
      }
      childClipTop -= childSize;
      childClipBottom -= childSize;
      yoffset = bottom;
    }

    for (int i = len - numSpecial; i < len; i++) {
      FileSystemEntry child = entries[i];
      long childSize = child.getSizeForRendering();
      parentSize -= childSize;

      float top = yoffset;
      float bottom = top + childSize * yscale;

      if (childClipTop > childSize) {
        childClipTop -= childSize;
        childClipBottom -= childSize;
        yoffset = bottom;
        continue;
      }

      if (childClipBottom < 0) {
        return;
      }

      if (clipLeft < EntryRenderStyle.elementWidth) {
        backend.drawSpecialEntry(child, xoffset, top, childXOffset, bottom);

        if (bottom - top > EntryRenderStyle.fontSize * 2) {
          float pos = textCenter(top, bottom, screenHeight);
          backend.drawName(child, xoffset + 2, pos - EntryRenderStyle.descent,
              EntryRenderStyle.elementWidth, true);
          backend.drawSize(child, xoffset + 2, pos - EntryRenderStyle.ascent,
              EntryRenderStyle.elementWidth, true);
        } else if (bottom - top > EntryRenderStyle.fontSize) {
          float baseline = (top + bottom
              - EntryRenderStyle.ascent - EntryRenderStyle.descent) / 2;
          backend.drawName(child, xoffset + 2, baseline,
              EntryRenderStyle.elementWidth, true);
        }
      }

      childClipTop -= childSize;
      childClipBottom -= childSize;
      yoffset = bottom;
    }
  }

  private static float textCenter(float top, float bottom, int screenHeight) {
    float fontSize = EntryRenderStyle.fontSize;
    float pos = (top + bottom) * 0.5f;
    if (pos < fontSize) {
      if (bottom > 2 * fontSize) {
        pos = fontSize;
      } else {
        pos = bottom - fontSize;
      }
    } else if (pos > screenHeight - fontSize) {
      if (top < screenHeight - 2 * fontSize) {
        pos = screenHeight - fontSize;
      } else {
        pos = top + fontSize;
      }
    }
    return pos;
  }
}
