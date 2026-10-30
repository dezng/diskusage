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

package com.google.android.diskusage;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUriExposedException;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.FileProvider;

import android.view.Menu;
import android.view.MenuItem;
import android.webkit.MimeTypeMap;

import android.widget.TextView;
import android.widget.Toast;

import com.google.android.diskusage.datasource.DataSource;
import com.google.android.diskusage.datasource.StatFsSource;
import com.google.android.diskusage.entity.FileSystemEntry;
import com.google.android.diskusage.entity.FileSystemEntrySmall;
import com.google.android.diskusage.entity.FileSystemFreeSpace;
import com.google.android.diskusage.entity.FileSystemPackage;
import com.google.android.diskusage.entity.FileSystemRoot;
import com.google.android.diskusage.entity.FileSystemSuperRoot;
import com.google.android.diskusage.entity.FileSystemSystemSpace;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiskUsage extends LoadableActivity {

  private final String TAG = getClass().getSimpleName();

  // FIXME: wrap to direct requests to rendering thread
  protected FileSystemState fileSystemState;
  public static final int RESULT_DELETE_CONFIRMED = 10;
  public static final int RESULT_DELETE_CANCELED = 11;

  public static final String SCANNER_KEY = "scanner";
  public static final String STATE_KEY = "state";
  public static final String KEY_KEY = "key";

  public static final String DELETE_PATH_KEY = "path";
  public static final String DELETE_ABSOLUTE_PATH_KEY = "absolute_path";
  String key;
  boolean useNativeScanner = true;

  private String pathToDelete;

  public DiskUsageMenu menu = DiskUsageMenu.getInstance(this);
  RendererManager rendererManager = new RendererManager(this);

  @Override
  protected void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    Log.d(TAG, "onCreate()");
    setContentView(new TextView(this));
    menu.onCreate();
    Intent i = getIntent();


    useNativeScanner = i.getBooleanExtra(SCANNER_KEY, useNativeScanner);
    key = i.getStringExtra(KEY_KEY);
    if (key == null) {
      // Just close instead of crashing later
      finish();
      return;
    }
    Bundle receivedState = i.getBundleExtra(STATE_KEY);

    MountPoint mountPoint = MountPoint.getForKey(this, key);
    if (mountPoint == null) {
      finish();
      return;
    }
    Log.d(TAG, "onCreate, rootPath = " + mountPoint.getRoot() + " receivedState = " + receivedState);
    if (receivedState != null) onRestoreInstanceState(receivedState);
  }

  ArrayList<Runnable> afterLoadAction = new ArrayList<Runnable>();

  public void applyPatternNewRoot(FileSystemSuperRoot newRoot, String searchQuery) {
    fileSystemState.replaceRootKeepCursor(newRoot, searchQuery);
  }

  @Override
  protected void onResume() {
    super.onResume();
    rendererManager.onResume();
    if (pkg_removed != null) {
      // Check if package removed
      String pkg_name = pkg_removed.pkg;
      PackageManager pm = getPackageManager();
      try {
        pm.getPackageInfo(pkg_name, 0);
      } catch (NameNotFoundException e) {
        if (fileSystemState != null)
          fileSystemState.removeInRenderThread(pkg_removed);
      }
      pkg_removed = null;
    }
    LoadFiles(this, new AfterLoad() {
      @Override
      public void run(final FileSystemSuperRoot root, boolean isCached) {
        fileSystemState = new FileSystemState(DiskUsage.this, root);
        rendererManager.makeView(fileSystemState, root);
        fileSystemState.startZoomAnimationInRenderThread(null, !isCached, false);

        for (Runnable r : afterLoadAction) {
          r.run();
        }
        afterLoadAction.clear();
        if (pathToDelete != null) {
          String path = pathToDelete;
          pathToDelete = null;
          continueDelete(path);
        }
      }
    }, false);
  }

  @Override
  protected void onPause() {
    rendererManager.onPause();
    super.onPause();
    if (fileSystemState != null) {
      fileSystemState.killRenderThread();
      final Bundle savedState = new Bundle();
      fileSystemState.saveState(savedState);
      afterLoadAction.add(new Runnable() {
        @Override
        public void run() {
          fileSystemState.restoreStateInRenderThread(savedState);
        }
      });
    }
  }

  @Override
  public void onActivityResult(int a, int result, Intent i) {
    if (result != RESULT_DELETE_CONFIRMED) return;
    pathToDelete = i.getStringExtra("path");
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    return super.onCreateOptionsMenu(menu);
  }

  private static abstract class VersionedPackageViewer {
    abstract void viewPackage(String pkg);

    public static VersionedPackageViewer newInstance(DiskUsage context) {
      final int sdkVersion = DataSource.get().getAndroidVersion();
      VersionedPackageViewer viewer = null;
      if (sdkVersion < Build.VERSION_CODES.GINGERBREAD) {
        viewer = context.new EclairPackageViewer();
      } else {
        viewer = context.new GingerbreadPackageViewer();
      }
      return viewer;
    }
  }

  private final class EclairPackageViewer extends VersionedPackageViewer {
    @Override
    void viewPackage(String pkg) {
      try {
        final String APP_PKG_PREFIX = "com.android.settings.";
        final String APP_PKG_NAME = APP_PKG_PREFIX+"ApplicationPkgName";
        Log.d(TAG, "show package = " + pkg);
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.InstalledAppDetails"));
        viewIntent.putExtra(APP_PKG_NAME, pkg);
        viewIntent.putExtra("pkg", pkg);
        startActivity(viewIntent);
      } catch (RuntimeException e) {
        Toast.makeText(DiskUsage.this,
            "Sorry, failed to view the installed app. " +
            "Please contact app developer.", Toast.LENGTH_SHORT).show();
      }
    }
  }

  private final class GingerbreadPackageViewer extends VersionedPackageViewer {
    @Override
    void viewPackage(String pkg) {
      Log.d(TAG, "show package = " + pkg);
      Intent viewIntent = new Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:" + pkg));
      startActivity(viewIntent);
    }
  }

  private final VersionedPackageViewer packageViewer =
    VersionedPackageViewer.newInstance(this);

  protected void viewPackage(FileSystemPackage pkg) {
    packageViewer.viewPackage(pkg.pkg);
    // FIXME: reload package data instead of just removing it
    pkg_removed = pkg;
  }

  void continueDelete(String path) {
    FileSystemEntry entry = fileSystemState.masterRoot.getEntryByName(path, true);
    if (entry != null) {
      BackgroundDelete.startDelete(this, entry);
    } else {
      Toast.makeText(this,
          "Oops. Can't find directory to be deleted.", Toast.LENGTH_SHORT);
    }
  }

  public void askForDeletion(final FileSystemEntry entry) {
    final String path = entry.path2();
    final String fullPath = entry.absolutePath();
    Log.d(TAG, "Deletion requested for " + path);

    if (entry instanceof FileSystemEntrySmall) {
      Toast.makeText(this,
          "Delete directory instead", Toast.LENGTH_SHORT).show();

      return;
    }
    if (entry.children == null || entry.children.length == 0) {
      if (entry instanceof FileSystemPackage) {
        this.pkg_removed = (FileSystemPackage) entry;
        BackgroundDelete.startDelete(this, entry);
        return;
      }

      // Delete single file or directory
      new AlertDialog.Builder(this)
      .setTitle(new File(fullPath).isDirectory()
          ? format(R.string.ask_to_delete_directory, path)
          : format(R.string.ask_to_delete_file, path))
      .setPositiveButton(str(R.string.button_delete),
          new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          BackgroundDelete.startDelete(DiskUsage.this, entry);
        }
      })
      .setNegativeButton(str(R.string.button_cancel),
          new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int whichButton) {
        }
      }).create().show();
    } else {
      Intent i = new Intent(this, DeleteActivity.class);
      i.putExtra(DELETE_PATH_KEY, path);
      i.putExtra(DELETE_ABSOLUTE_PATH_KEY, fullPath);
      i.putExtra(DeleteActivity.NUM_FILES_KEY, entry.getNumFiles());

      i.putExtra(DiskUsage.KEY_KEY, this.key);
      i.putExtra(DeleteActivity.SIZE_KEY, entry.sizeString());
      this.startActivityForResult(i, 0);
    }
  }
  private String format(int id, Object... args) {
    return getString(id, args);
  }

  private String str(int id) {
    return getString(id);
  }

  public boolean isIntentAvailable(Intent intent) {
    final PackageManager packageManager = getPackageManager();
    List<ResolveInfo> res = packageManager.queryIntentActivities(
        intent, PackageManager.MATCH_DEFAULT_ONLY);
    return res.size() > 0;
  }

  public void view(FileSystemEntry entry) {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.addCategory(Intent.CATEGORY_DEFAULT);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (entry instanceof FileSystemEntrySmall) {
      entry = entry.parent;
    }
    if (entry instanceof FileSystemPackage) {
      viewPackage((FileSystemPackage) entry);
      return;
    }
    if (entry.parent != null && entry.parent instanceof FileSystemPackage) {
      viewPackage((FileSystemPackage) entry.parent);
      return;
    }

    String path = entry.absolutePath();
    File file = new File(path);
    Uri uri = Uri.fromFile(file);

    if (file.isDirectory()) {
      // Go on with default file manager
      // Shoud this be optional?
      intent = new Intent(Intent.ACTION_VIEW);
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      intent.setDataAndType(uri, "inode/directory");

      try {
        startActivity(intent);
        return;
      } catch(ActivityNotFoundException e) {
      }

      intent = new Intent("org.openintents.action.VIEW_DIRECTORY");
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      intent.setData(uri);

      try {
        startActivity(intent);
        return;
      } catch(ActivityNotFoundException|FileUriExposedException e) {
      }

      intent = new Intent("org.openintents.action.PICK_DIRECTORY");
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      intent.setData(uri);
      intent.putExtra("org.openintents.extra.TITLE",
          str(R.string.title_in_oi_file_manager));
      intent.putExtra("org.openintents.extra.BUTTON_TEXT",
          str(R.string.button_text_in_oi_file_manager));

      try {
        startActivity(intent);
        return;
      } catch(ActivityNotFoundException|FileUriExposedException e) {
      }

      // old Astro
      intent = new Intent(Intent.ACTION_VIEW);
      intent.addCategory(Intent.CATEGORY_DEFAULT);
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      intent.setDataAndType(uri, "vnd.android.cursor.item/com.metago.filemanager.dir");

      try {
        startActivity(intent);
        return;
      } catch(ActivityNotFoundException|FileUriExposedException e) {
      }

      final Intent installSolidExplorer = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=pl.solidexplorer"));

      if (isIntentAvailable(installSolidExplorer)) {
        new AlertDialog.Builder(this)
        .setCancelable(true)
        .setTitle("Missing compatible file manager")
        .setMessage("No compatible filemanager found.\n\nAsk you favorite file manager developer " +
            "for integration with DiskUsage or install:" +
            "\n * Solid Explorer" +
            "\n * OI File Manager")
            .setPositiveButton("Install Solid Explorer", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface arg0, int arg1) {
                startActivity(installSolidExplorer);
              }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface arg0, int arg1) {
                // pass
              }
            })
            .create().show();
      } else {
      Toast.makeText(this, str(R.string.install_oi_file_manager),
          Toast.LENGTH_SHORT).show();
      }
      return;
    }

    String fileName = entry.name;
    int dot = fileName.lastIndexOf(".");
    Log.d(TAG, "name: " + fileName + " path: " + path + " dot: " + dot);
    if (dot != -1) {
      String extension = fileName.substring(dot + 1).toLowerCase();

      MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
      String mime = mimeTypeMap.getMimeTypeFromExtension(extension);
      Log.d(TAG, "extension: " + extension + " mime: " + mime);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file);
      }
      try {
        intent = new Intent(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        if (mime != null) {
          intent.setDataAndType(uri, mime);
        } else {
          intent.setDataAndType(uri, "binary/octet-stream");
        }
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
        return;
      } catch (ActivityNotFoundException|FileUriExposedException ignored) {
        Log.e("diskusage", "Can't open viewer and crash", ignored);
      }
    }
    Toast.makeText(this, str(R.string.no_viewer_found),
        Toast.LENGTH_SHORT).show();
  }

  public void rescan() {
    LoadFiles(DiskUsage.this, new AfterLoad() {
      @Override
      public void run(FileSystemSuperRoot newRoot, boolean isCached) {
        fileSystemState.startZoomAnimationInRenderThread(newRoot, !isCached, false);
      }
    }, true);
  }

  public void finishOnBack() {
    if (!menu.readyToFinish()) {
      return;
    }
    Bundle outState = new Bundle();
    onSaveInstanceState(outState);
    Intent result = new Intent();
    result.putExtra(DiskUsage.STATE_KEY, outState);
    result.putExtra(DiskUsage.KEY_KEY, key);
    result.putExtra(DiskUsage.SCANNER_KEY, useNativeScanner);
    setResult(0, result);
    finish();
  }

  public void setSelectedEntity(FileSystemEntry position) {
    menu.update(position);
    setTitle(format(R.string.title_for_path, position.toTitleString()));
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
      switch (item.getItemId()) {
          case android.R.id.home:
            finishOnBack();
            break;
      }
      return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    if (fileSystemState == null) return;

    fileSystemState.killRenderThread();
    fileSystemState.saveState(outState);
    menu.onSaveInstanceState(outState);
  }

  @Override
  protected void onRestoreInstanceState(final Bundle inState) {
    Log.d(TAG, "onRestoreInstanceState, rootPath = " + inState.getString(KEY_KEY));

    if (fileSystemState != null)
      fileSystemState.restoreStateInRenderThread(inState);
    else {
      afterLoadAction.add(new Runnable() {
        @Override
        public void run() {
          fileSystemState.restoreStateInRenderThread(inState);
        }
      });
    }

    menu.onRestoreInstanceState(inState);
  }

  public interface AfterLoad {
    public void run(FileSystemSuperRoot root, boolean isCached);
  }

  Handler handler = new Handler();

  private Runnable progressUpdater;

  static abstract class MemoryClass {
    abstract int maxHeap();

    static class MemoryClassDefault extends MemoryClass {
      @Override
      int maxHeap() {
        return 16 * 1024 * 1024;
      }
    };

    static MemoryClass getInstance(DiskUsage diskUsage) {
      final int sdkVersion = DataSource.get().getAndroidVersion();
      if (sdkVersion < Build.VERSION_CODES.ECLAIR) {
        return new MemoryClassDefault();
      } else {
        return diskUsage.new MemoryClassDetected();
      }
    }
  };

  class MemoryClassDetected extends MemoryClass {
    @Override
    int maxHeap() {
      ActivityManager manager = (ActivityManager) DiskUsage.this.getSystemService(Context.ACTIVITY_SERVICE);
      return manager.getMemoryClass() * 1024 * 1024;
    }
  }
  MemoryClass memoryClass = MemoryClass.getInstance(this);

  private int getMemoryQuota() {
    int totalMem = memoryClass.maxHeap();
    int numMountPoints = MountPoint.getMountPoints(this).size();
    return totalMem / (numMountPoints + 1);
  }

  static class FileSystemStats {
    final int blockSize;
    final long freeBlocks;
    final long busyBlocks;
    final long totalBlocks;

    public FileSystemStats(MountPoint mountPoint) {
      StatFsSource stats = null;
      try {
        stats = DataSource.get().statFs(mountPoint.getRoot());
      } catch (IllegalArgumentException e) {
        Log.e("diskusage",
            "Failed to get filesystem stats for " + mountPoint.getRoot(), e);
      }
      if (stats != null) {
        blockSize = stats.getBlockSize();
        freeBlocks = stats.getAvailableBlocks();
        totalBlocks = stats.getBlockCount();
        busyBlocks = totalBlocks - freeBlocks;
      } else {
        freeBlocks = totalBlocks = busyBlocks = 0;
        blockSize = 512;
      }
    }
    public String formatUsageInfo() {
      if (totalBlocks == 0) return "Used <no information>";
      return String.format("Used %s of %s",
          FileSystemEntry.calcSizeString(busyBlocks * blockSize),
          FileSystemEntry.calcSizeString(totalBlocks * blockSize));
    }
  };

  public interface ProgressGenerator {
    FileSystemEntry lastCreatedFile();
    long pos();
  };

  Runnable makeProgressUpdater(final ProgressGenerator scanner,
      final FileSystemStats stats) {
    return new Runnable() {
      private FileSystemEntry file;
      @Override
      public void run() {
        MyProgressDialog dialog = getPersistantState().loading;
        if (dialog != null) {
          dialog.setMax(stats.busyBlocks);
          FileSystemEntry lastFile = scanner.lastCreatedFile();

          if (lastFile != file) {
            dialog.setProgress(scanner.pos(), lastFile);
          }
          file = lastFile;
        }
        handler.postDelayed(this, 50);
      }
    };
  }

  @Override
  FileSystemSuperRoot scan() throws IOException, InterruptedException {
    final MountPoint mountPoint = MountPoint.getForKey(this, key);
    final MountPoint realMountPoint = mountPoint;
    final FileSystemStats stats = new FileSystemStats(mountPoint);
    int heap = getMemoryQuota();

    FileSystemEntry rootElement;
    if (useNativeScanner) {
      try {
        rootElement = nativeFirstScanner(stats, mountPoint);
      } catch (RuntimeException e) {
        Log.e("NativeScanner", "scan: err.", e);
        rootElement = normalScanner(stats, heap, mountPoint);
      }
    } else {
      rootElement = normalScanner(stats, heap, mountPoint);
    }

    ArrayList<FileSystemEntry> entries = new ArrayList<FileSystemEntry>();

    if (rootElement.children != null) {
      for (FileSystemEntry e : rootElement.children) {
        entries.add(e);
      }
    }

    if (mountPoint.hasApps()) {
      FileSystemRoot media = (FileSystemRoot) FileSystemRoot.makeNode(
          "media", realMountPoint.getRoot(), false).setChildren(entries.toArray(new FileSystemEntry[0]),
              stats.blockSize);
      entries = new ArrayList<FileSystemEntry>();
      entries.add(media);

      FileSystemEntry[] appList = loadApps2SD(stats.blockSize);
      if (appList != null) {
        moveAppData(appList, media, stats.blockSize);
        FileSystemEntry apps = FileSystemEntry.makeNode(null, "Apps").setChildren(appList, stats.blockSize);
        entries.add(apps);
      }
    }

    long visibleBlocks = 0;
    for (FileSystemEntry e : entries) {
      visibleBlocks += e.getSizeInBlocks();
    }

    long systemBlocks = stats.totalBlocks - stats.freeBlocks - visibleBlocks;
    Collections.sort(entries, FileSystemEntry.COMPARE);
    if (systemBlocks > 0) {
      entries.add(new FileSystemSystemSpace(getString(R.string.graph_system_data), systemBlocks * stats.blockSize, stats.blockSize));
      entries.add(new FileSystemFreeSpace(getString(R.string.graph_free_space), stats.freeBlocks * stats.blockSize, stats.blockSize));
    } else {
      long freeBlocks = stats.freeBlocks + systemBlocks;
      if (freeBlocks > 0) {
        entries.add(new FileSystemFreeSpace(getString(R.string.graph_free_space), freeBlocks * stats.blockSize, stats.blockSize));
      }
    }

    rootElement = FileSystemRoot.makeNode(mountPoint.getTitle(), mountPoint.getRoot(), false)
        .setChildren(entries.toArray(new FileSystemEntry[0]), stats.blockSize);
    FileSystemSuperRoot newRoot = new FileSystemSuperRoot(stats.blockSize);
    newRoot.setChildren(new FileSystemEntry[] { rootElement }, stats.blockSize);
    return newRoot;
  }

  private FileSystemEntry normalScanner(FileSystemStats stats, int heap, MountPoint mountPoint) throws IOException {
    FileSystemEntry rootElement;
    final Scanner scanner = new Scanner(20, stats.blockSize, stats.busyBlocks, heap);
    progressUpdater = makeProgressUpdater(scanner, stats);
    handler.post(progressUpdater);
    rootElement = scanner.scan(DataSource.get().createLegacyScanFile(mountPoint.getRoot()));
    Log.d(TAG, "normalScanner: " + rootElement);
    handler.removeCallbacks(progressUpdater);
    return rootElement;
  }

  private FileSystemEntry nativeFirstScanner(FileSystemStats stats, MountPoint mountPoint) throws IOException, InterruptedException {
    FileSystemEntry rootElement;
    final NativeScanner scanner = new NativeScanner(stats.blockSize);
    progressUpdater = makeProgressUpdater(scanner, stats);
    handler.post(progressUpdater);
    rootElement = scanner.scan(mountPoint);
    handler.removeCallbacks(progressUpdater);
    return rootElement;
  }

  protected FileSystemEntry[] loadApps2SD(int blockSize) {
    try {
      return new Apps2SDLoader(this).load(blockSize);
    } catch (Throwable t) {
      Log.e(TAG, "problem loading app storage info", t);
      return null;
    }
  }

  void moveIntoPackage(FileSystemPackage pkg, FileSystemEntry entry,
                       String newName, FileSystemPackage.ChildType type, int blockSize) {
    if (entry == null) {
      return;
    }
    String path = entry.absolutePath();
    entry.remove(blockSize);
    FileSystemRoot newRoot = FileSystemRoot.makeNode(newName, path, true);
    newRoot.setChildren(entry.children, blockSize);
    pkg.addPublicChild(newRoot, type, blockSize);
  }

  private static FileSystemEntry childNamed(FileSystemEntry parent, String name) {
    if (parent == null || parent.children == null) {
      return null;
    }
    for (FileSystemEntry child : parent.children) {
      if (name.equals(child.name)) {
        return child;
      }
    }
    return null;
  }

  private static Map<String, FileSystemEntry> indexByName(FileSystemEntry parent) {
    if (parent == null || parent.children == null || parent.children.length == 0) {
      return Collections.emptyMap();
    }
    Map<String, FileSystemEntry> index =
        new HashMap<>(parent.children.length * 2);
    for (FileSystemEntry child : parent.children) {
      index.put(child.name, child);
    }
    return index;
  }

  /**
   * Moves the external app directories discovered by the media scan into the
   * corresponding app entries. Only the external tree needs to be searched:
   * the internal /data tree is not part of this view.
   *
   * <p>The previous implementation rebuilt and re-resolved the full path for
   * every app and every directory type, which was O(apps^2) and produced a
   * large amount of debug logging. Index the three Android subdirectories once
   * and do a single pass over the installed apps instead.</p>
   */
  void moveAppData(FileSystemEntry[] apps, FileSystemRoot media, int blockSize) {
    FileSystemEntry androidDir = childNamed(media, "Android");
    if (androidDir == null) {
      for (FileSystemEntry app : apps) {
        ((FileSystemPackage) app).applyFilter(blockSize);
      }
      Arrays.sort(apps, FileSystemEntry.COMPARE);
      return;
    }

    Map<String, FileSystemEntry> dataPackages =
        indexByName(childNamed(androidDir, "data"));
    Map<String, FileSystemEntry> obbPackages =
        indexByName(childNamed(androidDir, "obb"));
    Map<String, FileSystemEntry> mediaPackages =
        indexByName(childNamed(androidDir, "media"));

    for (FileSystemEntry entry : apps) {
      FileSystemPackage app = (FileSystemPackage) entry;
      FileSystemEntry data = dataPackages.get(app.pkg);
      if (data != null) {
        moveIntoPackage(app, childNamed(data, "cache"), "ExternalCache",
            FileSystemPackage.ChildType.CACHE, blockSize);
        moveIntoPackage(app, childNamed(data, "files"), "Files",
            FileSystemPackage.ChildType.DATA, blockSize);
      }
      moveIntoPackage(app, obbPackages.get(app.pkg), "Obb",
          FileSystemPackage.ChildType.CODE, blockSize);
      moveIntoPackage(app, mediaPackages.get(app.pkg), "MediaFiles",
          FileSystemPackage.ChildType.DATA, blockSize);
      app.applyFilter(blockSize);
    }
    Arrays.sort(apps, FileSystemEntry.COMPARE);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);
    this.menu.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public String getKey() {
    return key;
  }

  public void searchRequest() {
    menu.searchRequest();
  }
}
