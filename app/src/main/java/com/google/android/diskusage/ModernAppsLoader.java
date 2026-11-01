package com.google.android.diskusage;

import android.annotation.TargetApi;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Android 8+ implementation. Kept in a separate class so that the legacy path
 * can be loaded on old Android versions without resolving API 26 classes.
 */
@TargetApi(Build.VERSION_CODES.O)
final class ModernAppsLoader {
  private static final String TAG = "ModernAppsLoader";

  private final DiskUsage diskUsage;
  private final Apps2SDLoader owner;
  private CharSequence lastAppName = "";
  private boolean switchToSecondary = true;
  private int numLoadedPackages = 0;

  ModernAppsLoader(DiskUsage diskUsage, Apps2SDLoader owner) {
    this.diskUsage = diskUsage;
    this.owner = owner;
  }

  FileSystemEntry[] load(final int blockSize) throws Throwable {
    final StorageStatsManager storageStatsManager =
        (StorageStatsManager) diskUsage.getSystemService(Context.STORAGE_STATS_SERVICE);
    if (storageStatsManager == null) {
      return null;
    }

    final PackageManager packageManager = diskUsage.getPackageManager();
    final List<ApplicationInfo> packages = packageManager.getInstalledApplications(0);
    final ArrayList<FileSystemEntry> entries = new ArrayList<>();

    final Handler handler = diskUsage.handler;
    Runnable progressUpdater = new Runnable() {
      @Override
      public void run() {
        if (!owner.isProgressEnabled()) {
          handler.postDelayed(this, 50);
          return;
        }
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

    /*
     * queryStatsForPackage() is a binder call, so doing it serially for every
     * installed package leaves most of the wall-clock time waiting. Keep the
     * result order stable and cap the pool to avoid flooding the system
     * service on low-end devices.
     */
    final int threadCount = Math.max(1,
        Math.min(4, Runtime.getRuntime().availableProcessors()));
    final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    final List<Future<FileSystemPackage>> futures = new ArrayList<>(packages.size());
    try {
      for (final ApplicationInfo metadata : packages) {
        futures.add(executor.submit(new Callable<FileSystemPackage>() {
          @Override
          public FileSystemPackage call() {
            final String pkg = metadata.packageName;
            final String appName = metadata.loadLabel(packageManager).toString();
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
              return entry;
            } catch (PackageManager.NameNotFoundException | IOException e) {
              Log.d(TAG, "No stats for " + pkg, e);
              return null;
            }
          }
        }));
      }

      for (int i = 0; i < futures.size(); i++) {
        try {
          FileSystemPackage entry = futures.get(i).get();
          if (entry != null) {
            entries.add(entry);
            lastAppName = entry.name;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (ExecutionException e) {
          if (e.getCause() instanceof SecurityException) {
            Log.e(TAG, "Usage access is required for app statistics",
                e.getCause());
            break;
          }
          Log.d(TAG, "No stats for package", e.getCause());
        }
        numLoadedPackages++;
      }
    } finally {
      executor.shutdownNow();
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
