package com.google.android.diskusage;

import android.annotation.TargetApi;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.Log;

import com.google.android.diskusage.entity.FileSystemEntry;
import com.google.android.diskusage.entity.FileSystemPackage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Android 8+ implementation. Kept in a separate class so that the legacy path
 * can be loaded on old Android versions without resolving API 26 classes.
 */
@TargetApi(Build.VERSION_CODES.O)
final class ModernAppsLoader {
  private static final String TAG = "ModernAppsLoader";

  private final DiskUsage diskUsage;
  private CharSequence lastAppName = "";
  private boolean switchToSecondary = true;
  private int numLoadedPackages = 0;

  ModernAppsLoader(DiskUsage diskUsage) {
    this.diskUsage = diskUsage;
  }

  FileSystemEntry[] load(final int blockSize) throws Throwable {
    final StorageStatsManager storageStatsManager =
        (StorageStatsManager) diskUsage.getSystemService(Context.STORAGE_STATS_SERVICE);
    if (storageStatsManager == null) {
      return null;
    }

    final PackageManager packageManager = diskUsage.getPackageManager();
    final List<PackageInfo> packages =
        packageManager.getInstalledPackages(PackageManager.GET_META_DATA);
    final ArrayList<FileSystemEntry> entries = new ArrayList<>();

    final Handler handler = diskUsage.handler;
    Runnable progressUpdater = new Runnable() {
      @Override
      public void run() {
        MyProgressDialog dialog = diskUsage.getPersistantState().loading;
        if (dialog != null) {
          if (switchToSecondary) {
            dialog.switchToSecondary();
            switchToSecondary = false;
          }
          dialog.setMax(packages.size());
          synchronized (ModernAppsLoader.this) {
            dialog.setProgress(numLoadedPackages, lastAppName);
          }
        }
        handler.postDelayed(this, 50);
      }
    };
    handler.post(progressUpdater);

    try {
      for (PackageInfo packageInfo : packages) {
        final ApplicationInfo metadata = packageInfo.applicationInfo;
        if (metadata == null) {
          numLoadedPackages++;
          continue;
        }

        final String pkg = packageInfo.packageName;
        final String appName = metadata.loadLabel(packageManager).toString();
        lastAppName = appName;

        try {
          StorageStats stats = storageStatsManager.queryStatsForPackage(
              StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
          FileSystemPackage entry = new FileSystemPackage(
              appName,
              pkg,
              stats.getAppBytes(),
              stats.getDataBytes(),
              stats.getCacheBytes(),
              metadata.flags,
              stats.getExternalCacheBytes());
          entry.applyFilter(blockSize);
          entries.add(entry);
        } catch (PackageManager.NameNotFoundException | IOException e) {
          Log.d(TAG, "No stats for " + pkg, e);
        } catch (SecurityException e) {
          Log.e(TAG, "Usage access is required for app statistics", e);
          break;
        }
        numLoadedPackages++;
      }
    } finally {
      handler.removeCallbacks(progressUpdater);
    }

    if (entries.isEmpty()) {
      return null;
    }
    FileSystemEntry[] result = entries.toArray(new FileSystemEntry[0]);
    Arrays.sort(result, FileSystemEntry.COMPARE);
    return result;
  }
}
