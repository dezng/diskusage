package com.google.android.diskusage.render;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import com.google.android.diskusage.R;

/**
 * Shared metrics, canvas paints and texture drawables used while drawing the
 * disk map.
 *
 * <p>This is view state, not part of the filesystem model. The rendering
 * backends read the metrics from here so both the Canvas and the OpenGL
 * implementations use exactly the same layout values and background assets.
 */
public final class EntryRenderStyle {
  public static final int padding = 4;

  static float ascent;
  static float descent;
  public static float fontSize;
  public static int elementWidth;

  static final Paint cursorFg = new Paint();
  static final Paint textPaintFolder = new Paint();
  static final Paint textPaintFile = new Paint();

  static final Paint dirPaint = new Paint();
  static final Paint filePaint = new Paint();
  static final Paint specialPaint = new Paint();
  static final Paint smallPaint = new Paint();

  static Bitmap dirBitmap;
  static Bitmap fileBitmap;
  static Bitmap specialBitmap;
  static Bitmap smallBitmap;

  static {
    cursorFg.setColor(Color.YELLOW);
    cursorFg.setStyle(Paint.Style.STROKE);

    textPaintFolder.setColor(Color.WHITE);
    textPaintFolder.setStyle(Paint.Style.FILL_AND_STROKE);
    textPaintFolder.setFlags(textPaintFolder.getFlags() | Paint.ANTI_ALIAS_FLAG);

    textPaintFile.setColor(Color.parseColor("#18C5E7"));
    textPaintFile.setStyle(Paint.Style.FILL_AND_STROKE);
    textPaintFile.setFlags(textPaintFile.getFlags() | Paint.ANTI_ALIAS_FLAG);
  }

  private EntryRenderStyle() {
  }

  /**
   * Loads the same 16x16 gradient drawables that the OpenGL renderer uses as
   * textures. They are stretched per entry with bilinear filtering, matching
   * the texture sampling of {@code RenderingThread}.
   */
  public static void loadDrawables(Context context) {
    if (dirBitmap != null) return;

    Resources resources = context.getResources();
    dirBitmap = loadDrawable(resources, R.drawable.dirbg_new);
    fileBitmap = loadDrawable(resources, R.drawable.filebg_new);
    specialBitmap = loadDrawable(resources, R.drawable.special);
    smallBitmap = loadDrawable(resources, R.drawable.small);

    dirPaint.setShader(new BitmapShader(
        dirBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    filePaint.setShader(new BitmapShader(
        fileBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    specialPaint.setShader(new BitmapShader(
        specialBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    smallPaint.setShader(new BitmapShader(
        smallBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));

    dirPaint.setFilterBitmap(true);
    filePaint.setFilterBitmap(true);
    specialPaint.setFilterBitmap(true);
    smallPaint.setFilterBitmap(true);
  }

  private static Bitmap loadDrawable(Resources resources, int drawableId) {
    Drawable drawable = resources.getDrawable(drawableId);
    Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
    // Match the OpenGL path: textures are sampled as raw 16x16 pixels and are
    // not rescaled again by the bitmap density.
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    Canvas canvas = new Canvas(bitmap);
    drawable.setBounds(0, 0, 16, 16);
    drawable.draw(canvas);
    return bitmap;
  }

  public static void updateFontsLegacy(Context context) {
    float textSize = context.getResources().getDisplayMetrics().scaledDensity
        * 12 + 0.5f;
    if (textSize < 10) textSize = 10;
    updateFonts(textSize);
  }

  public static void updateFonts(float textSize) {
    textPaintFile.setTextSize(textSize);
    textPaintFolder.setTextSize(textSize);
    ascent = textPaintFolder.ascent();
    descent = textPaintFolder.descent();
    fontSize = descent - ascent;
  }

  static Paint textPaint(boolean special, boolean isFile) {
    if (special) return textPaintFolder;
    return isFile ? textPaintFile : textPaintFolder;
  }
}
