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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.diskusage.datasource.DataSource;
import com.google.android.diskusage.datasource.DebugDataSourceBridge;
import com.google.android.diskusage.datasource.fast.DefaultDataSource;
import com.google.android.diskusage.entity.FileSystemEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

public class SelectActivity extends Activity {

  private static final String TAG = "SelectActivity";

  private AlertDialog dialog;
  Map<String,Bundle> bundles = new TreeMap<String,Bundle>();
  ArrayList<Runnable> actionList = new ArrayList<Runnable>();
//  private static boolean debugUnhidden = true;
  private static boolean debugLoadedDump = false;
  private DataSource debugDataSource;
  private static DebugDataSourceBridge debugDataSourceBridge;

  static {
    try {
      Class<?> clazz = Class.forName(
          "com.google.android.diskusage.datasource.debug.DebugDataSourceBridgeImpl");
      debugDataSourceBridge = (DebugDataSourceBridge) clazz.newInstance();
    } catch (Throwable t) {
      Log.d(TAG, "Debug is disabled");
    }
  }


  private abstract class AbstractUsageAction implements Runnable {
    public void runAction(String key, Class<?> viewer) {
      Intent i = new Intent(SelectActivity.this, viewer);
      i.putExtra(DiskUsage.KEY_KEY, key);
      Bundle bundle = bundles.get(key);
      if (bundle != null) {
        i.putExtra(DiskUsage.STATE_KEY, bundle);
      }
      startActivityForResult(i, 0);
    }
    public void runAction2(String key, Class<?> viewer, boolean scanner) {
      Intent i = new Intent(SelectActivity.this, viewer);
      i.putExtra(DiskUsage.KEY_KEY, key);
      i.putExtra(DiskUsage.SCANNER_KEY, !scanner);
      Bundle bundle = bundles.get(key);
      if (bundle != null) {
        i.putExtra(DiskUsage.STATE_KEY, bundle);
      }
      startActivityForResult(i, 0);
    }
  };

  private class DiskUsageAction extends AbstractUsageAction {
    private final MountPoint mountPoint;
    private final boolean useNativeScanner;

    DiskUsageAction(MountPoint mountPoint) {
      this.mountPoint = mountPoint;
      useNativeScanner = true;
    }
    DiskUsageAction(MountPoint mountPoint, boolean scanner) {
      this.mountPoint = mountPoint;
      useNativeScanner = !scanner;
    }

    public void run() {
      runAction2(mountPoint.getKey(), PermissionRequestActivity.class, !useNativeScanner);
    }
  };

  private class EnableDebugAction implements Runnable {
    public void run() {
      try {
        debugLoadedDump = false;
        debugDataSource = debugDataSourceBridge.initNewDump(SelectActivity.this);
        DataSource.override(debugDataSource);
        dialog.hide();
        MountPoint.reset();
        makeDialog();
      } catch (IOException e) {
        Log.d(TAG, "Failed to enable debug", e);
        Toast.makeText(
            SelectActivity.this,
            "Failed to enable debug " + e.getMessage(),
            Toast.LENGTH_LONG).show();
      }
    }
  }

  private class DisableDebug implements Runnable {
    @Override
    public void run() {
        DataSource.override(new DefaultDataSource());
        debugDataSource = null;
//        debugUnhidden = false;
        dialog.hide();
        MountPoint.reset();
        makeDialog();
    }

  }

  private class LoadDumpAction implements Runnable {
    public void run() {
      try {
        debugDataSource = debugDataSourceBridge.loadDefaultDump();
        debugLoadedDump = true;
        DataSource.override(debugDataSource);
        dialog.hide();
        MountPoint.reset();
        makeDialog();
      } catch (IOException e) {
        Log.d(TAG, "Failed to enable debug", e);
        Toast.makeText(
            SelectActivity.this,
            "Failed to enable debug " + e.getMessage(),
            Toast.LENGTH_LONG).show();
      }
    }
  }

  private class SendBugReportAction implements Runnable {
    @Override
    public void run() {
      try {
        debugDataSourceBridge.saveDumpAndSendReport(
            debugDataSource, SelectActivity.this);
      } catch (IOException e) {
        Log.d(TAG, "Failed to send bugreport", e);
        Toast.makeText(
            SelectActivity.this,
            "Failed to send bugreport: " + e.getMessage(),
            Toast.LENGTH_LONG).show();
      }
    }
  }

  public void makeDialog() {
    ArrayList<String> options = new ArrayList<String>();
    actionList.clear();

//    PortableFile[] fileDirs = DataSource.get().getExternalFilesDirs(this);
    for (MountPoint mountPoint : MountPoint.getMountPoints(this)) {
      options.add(mountPoint.getTitle());
      actionList.add(new DiskUsageAction(mountPoint));
    }

    if (BuildConfig.DEBUG) {
      for (MountPoint mountPoint : MountPoint.getMountPoints(this)) {
        options.add(mountPoint.getTitle() + " force Scanner");
        actionList.add(new DiskUsageAction(mountPoint, true));
      }
    }

    if (BuildConfig.DEBUG && debugDataSourceBridge != null) {
      if (debugDataSource != null && !debugLoadedDump) {
        options.add("* Send bug report");
        actionList.add(new SendBugReportAction());
      }

      if (debugDataSource == null) {
        options.add("* Enable debug dump");
        actionList.add(new EnableDebugAction());

        if (debugDataSourceBridge.dumpExist()) {
          options.add("* Load dump");
          actionList.add(new LoadDumpAction());
        }
      } else {
        options.add("* Disable debug");
        actionList.add(new DisableDebug());
      }
    }

    final String[] optionsArray = options.toArray(new String[options.size()]);

    dialog = new AlertDialog.Builder(this)
    .setItems(optionsArray,
        new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        actionList.get(which).run();
      }
    })
    .setTitle(R.string.ask_view)
    .setOnCancelListener(new OnCancelListener() {
      @Override
      public void onCancel(DialogInterface dialog) {
        finish();
      }
    }).create();
    /*try {
      if (debugDataSourceBridge != null) {
        dialog.getListView().setOnItemLongClickListener(
            new OnItemLongClickListener() {
          @Override
          public boolean onItemLongClick(
              AdapterView<?> arg0, View arg1, int arg2, long arg3) {
            debugUnhidden = true;
            dialog.hide();
            makeDialog();
            return true;
          }
        });
      }
    } catch (Throwable t) {
      // api 3
    }*/
    dialog.show();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    FileSystemEntry.setupStrings(this);
    setContentView(new TextView(this));
//    ActionBar bar = getActionBar();
//    bar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_USE_LOGO);
  }

  @Override
  protected void onResume() {
    super.onResume();
//    ActionBar actionBar = getActionBar();
//    actionBar.setDisplayHomeAsUpEnabled(true);
    makeDialog();
  }

  @Override
  protected void onPause() {
    if (dialog.isShowing()) dialog.dismiss();
    super.onPause();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (data == null) return;
    Bundle state = data.getBundleExtra(DiskUsage.STATE_KEY);
    String key = data.getStringExtra(DiskUsage.KEY_KEY);
    bundles.put(key, state);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    for (Entry<String, Bundle> entry : bundles.entrySet()) {
      outState.putBundle(entry.getKey(), entry.getValue());
    }
    String[] keys = bundles.keySet().toArray(new String[0]);
    outState.putStringArray(BUNDLE_KEYS, keys);
  }

  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {
    super.onRestoreInstanceState(savedInstanceState);
    for (String key : savedInstanceState.getStringArray(BUNDLE_KEYS)) {
      bundles.put(key, savedInstanceState.getBundle(key));
    }
  }

  private static final String BUNDLE_KEYS = "keys";
}