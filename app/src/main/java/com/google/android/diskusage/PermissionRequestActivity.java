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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

/**
 * Runtime permission gate.
 *
 * <p>Storage browsing needs "all files access" (Android 11+) or the legacy
 * READ/WRITE_EXTERNAL_STORAGE permission. The storage graph also displays
 * application statistics, which additionally need usage access.</p>
 */
public class PermissionRequestActivity extends Activity {
  private static final int DISKUSAGE_REQUEST_CODE = 10;
  private static final int STORAGE_SETTINGS_REQUEST_CODE = 11;
  private static final int LEGACY_PERMISSION_REQUEST_CODE = 12;
  private static final int USAGE_ACCESS_REQUEST_CODE = 13;

  private String key;
  private MountPoint mountPoint;
  private boolean needsUsageAccess;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Intent input = getIntent();
    key = input.getStringExtra(DiskUsage.KEY_KEY);
    mountPoint = MountPoint.getForKey(this, key);
    if (key == null || mountPoint == null) {
      finish();
      return;
    }

    needsUsageAccess = mountPoint.hasApps()
        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;

    if (!isStorageAccessGranted()) {
      requestStorageAccess();
      return;
    }

    continueAfterStorage();
  }

  private void continueAfterStorage() {
    if (needsUsageAccess && !isUsageAccessGranted()) {
      requestUsageAccess();
    } else {
      forwardToViewer();
    }
  }

  private void requestStorageAccess() {
    new AlertDialog.Builder(this)
        .setTitle("Storage access permission needed")
        .setMessage("Allow DiskUsage to read and manage files on this device?")
        .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int which) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              openStorageSettings();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              requestPermissions(new String[] {
                  Manifest.permission.READ_EXTERNAL_STORAGE,
                  Manifest.permission.WRITE_EXTERNAL_STORAGE
              }, LEGACY_PERMISSION_REQUEST_CODE);
            } else {
              continueAfterStorage();
            }
          }
        })
        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int which) {
            finish();
          }
        })
        .setOnCancelListener(new DialogInterface.OnCancelListener() {
          @Override
          public void onCancel(DialogInterface dialogInterface) {
            finish();
          }
        })
        .create()
        .show();
  }

  private void requestUsageAccess() {
    new AlertDialog.Builder(this)
        .setTitle("Usage access permission needed")
        .setMessage("Allow DiskUsage to read application storage statistics?")
        .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int which) {
            startActivityForResult(
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                USAGE_ACCESS_REQUEST_CODE);
          }
        })
        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int which) {
            // App statistics are optional; the storage graph can still load.
            forwardToViewer();
          }
        })
        .setOnCancelListener(new DialogInterface.OnCancelListener() {
          @Override
          public void onCancel(DialogInterface dialogInterface) {
            forwardToViewer();
          }
        })
        .create()
        .show();
  }

  private void openStorageSettings() {
    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
    intent.setData(Uri.parse("package:" + getPackageName()));
    startActivityForResult(intent, STORAGE_SETTINGS_REQUEST_CODE);
  }

  private boolean isStorageAccessGranted() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      return Environment.isExternalStorageManager();
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return checkPermission(
          Manifest.permission.WRITE_EXTERNAL_STORAGE,
          android.os.Process.myPid(),
          android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }
    return true;
  }

  private boolean isUsageAccessGranted() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return false;
    }
    try {
      AppOpsManager appOps =
          (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
      if (appOps == null) {
        return false;
      }
      ApplicationInfo info = getPackageManager().getApplicationInfo(getPackageName(), 0);
      int mode = appOps.checkOpNoThrow(
          AppOpsManager.OPSTR_GET_USAGE_STATS, info.uid, info.packageName);
      return mode == AppOpsManager.MODE_ALLOWED;
    } catch (Exception e) {
      return false;
    }
  }

  private void forwardToViewer() {
    Intent input = getIntent();
    Intent target = new Intent(this, DiskUsage.class);
    target.putExtra(DiskUsage.KEY_KEY, input.getStringExtra(DiskUsage.KEY_KEY));
    target.putExtra(DiskUsage.STATE_KEY, input.getBundleExtra(DiskUsage.STATE_KEY));
    target.putExtra(DiskUsage.SCANNER_KEY,
        input.getBooleanExtra(DiskUsage.SCANNER_KEY, true));
    startActivityForResult(target, DISKUSAGE_REQUEST_CODE);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == DISKUSAGE_REQUEST_CODE) {
      setResult(0, data);
      finish();
    } else if (requestCode == STORAGE_SETTINGS_REQUEST_CODE) {
      if (isStorageAccessGranted()) {
        continueAfterStorage();
      } else {
        finish();
      }
    } else if (requestCode == USAGE_ACCESS_REQUEST_CODE) {
      // App statistics are optional; the storage graph can still be shown.
      forwardToViewer();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                         int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == LEGACY_PERMISSION_REQUEST_CODE) {
      if (isStorageAccessGranted()) {
        continueAfterStorage();
      } else {
        finish();
      }
    }
  }
}
