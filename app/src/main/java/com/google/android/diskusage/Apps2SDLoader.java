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

import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.google.android.diskusage.datasource.AppInfo;
import com.google.android.diskusage.datasource.AppStats;
import com.google.android.diskusage.datasource.AppStatsCallback;
import com.google.android.diskusage.datasource.DataSource;
import com.google.android.diskusage.datasource.PkgInfo;
import com.google.android.diskusage.entity.FileSystemEntry;
import com.google.android.diskusage.entity.FileSystemPackage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Loads per-application storage statistics.
 *
 * <p>Android 8+ delegates to {@link ModernAppsLoader}. Android 7.1 and older
 * use the legacy PackageManager.getPackageSizeInfo() API, which reports
 * internal and external (SD card) code/data/cache/media/obb sizes.</p>
 */
public class Apps2SDLoader {
  private static final String TAG = "Apps2SDLoader";
  private static final long LEGACY_CALLBACK_TIMEOUT_SECONDS = 3;

  private final DiskUsage diskUsage;
  private CharSequence lastAppName = "";
  private boolean switchToSecondary = true;
  private int numLoadedPackages = 0;

  public Apps2SDLoader(DiskUsage activity) {
    this.diskUsage = activity;
  }

  public FileSystemEntry[] load(final int blockSize) throws Throwable {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      return new ModernAppsLoader(diskUsage).load(blockSize);
    }
    return loadLegacy(blockSize);
  }

  private FileSystemEntry[] loadLegacy(final int blockSize) throws Throwable {
    final PackageManager packageManager = diskUsage.getPackageManager();
    final Method getPackageSizeInfo;
    try {
      getPackageSizeInfo = packageManager.getClass().getMethod(
          "getPackageSizeInfo", String.class, IPackageStatsObserver.class);
    } catch (NoSuchMethodException e) {
      Log.e(TAG, "Legacy package size API is unavailable", e);
      return null;
    }

    final List<PkgInfo> packages = DataSource.get().getInstalledPackages(packageManager);
    final ArrayList<FileSystemEntry> entries = new ArrayList<>();

    final Handler handler = diskUsage.handler;
    Runnable progressUpdater = makeProgressUpdater(packages.size());
    handler.post(progressUpdater);

    try {
      for (PkgInfo info : packages) {
        final AppInfo appInfo = info.getApplicationInfo();
        if (appInfo == null) {
          numLoadedPackages++;
          continue;
        }

        final String pkg = info.getPackageName();
        final String appName = appInfo.getApplicationLabel();
        final int flags = appInfo.getFlags();
        lastAppName = appName;
        final CountDownLatch done = new CountDownLatch(1);
        final FileSystemPackage[] result = new FileSystemPackage[1];

        try {
          DataSource.get().getPackageSizeInfo(
              info, getPackageSizeInfo, packageManager, new AppStatsCallback() {
                @Override
                public void onGetStatsCompleted(AppStats stats, boolean succeeded) {
                  if (succeeded && stats != null) {
                    result[0] = new FileSystemPackage(appName, pkg, stats, flags);
                  }
                  done.countDown();
                }
              });
          done.await(LEGACY_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
          Log.d(TAG, "Legacy stats failed for " + pkg, e);
        }

        if (result[0] != null) {
          result[0].applyFilter(blockSize);
          entries.add(result[0]);
        }
        numLoadedPackages++;
      }
    } finally {
      handler.removeCallbacks(progressUpdater);
    }

    return finish(entries);
  }

  private Runnable makeProgressUpdater(final int max) {
    final Handler handler = diskUsage.handler;
    return new Runnable() {
      @Override
      public void run() {
        MyProgressDialog dialog = diskUsage.getPersistantState().loading;
        if (dialog != null) {
          if (switchToSecondary) {
            dialog.switchToSecondary();
            switchToSecondary = false;
          }
          dialog.setMax(max);
          synchronized (Apps2SDLoader.this) {
            dialog.setProgress(numLoadedPackages, lastAppName);
          }
        }
        handler.postDelayed(this, 50);
      }
    };
  }

  private FileSystemEntry[] finish(ArrayList<FileSystemEntry> entries) {
    if (entries.isEmpty()) {
      return null;
    }
    FileSystemEntry[] result = entries.toArray(new FileSystemEntry[0]);
    Arrays.sort(result, FileSystemEntry.COMPARE);
    return result;
  }
}
