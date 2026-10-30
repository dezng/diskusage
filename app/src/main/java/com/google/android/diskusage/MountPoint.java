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

import android.content.Context;
import android.util.Log;

import com.google.android.diskusage.datasource.DataSource;
import com.google.android.diskusage.datasource.PortableFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MountPoint {
  private static final String TAG = MountPoint.class.getSimpleName();

  private final String title;
  private final String root;
  private final boolean internal;

  private static boolean init = false;
  private static List<MountPoint> mountPoints = new ArrayList<>();
  private static Map<String, MountPoint> mountPointForKey = new HashMap<>();

  MountPoint(String title, String root, boolean internal) {
    this.title = title;
    this.root = root;
    this.internal = internal;
  }

  public String getRoot() {
    return root;
  }

  public String getTitle() {
    return title;
  }

  public boolean isDeleteSupported() {
    return internal;
  }

  public String getKey() {
    return "storage:" + root;
  }

  public boolean hasApps() {
    return internal;
  }

  public static MountPoint getForKey(Context context, String key) {
    initMountPoints(context);
    return mountPointForKey.get(key);
  }

  public static List<MountPoint> getMountPoints(Context context) {
    initMountPoints(context);
    return mountPoints;
  }

  private static void initMountPoints(Context context) {
    if (init) return;
    init = true;

    PortableFile[] dirs = DataSource.get().getExternalFilesDirs(context);
    for (PortableFile dir : dirs) {
      if (dir == null) {
        continue;
      }
      String path = dir.getAbsolutePath().replaceFirst(
          "/Android/data/" + BuildConfig.APPLICATION_ID + "/files", "");
      boolean internal = !dir.isExternalStorageRemovable();
      String title = internal ? context.getString(R.string.storage_card) : path;
      MountPoint mountPoint = new MountPoint(title, path, internal);
      mountPoints.add(mountPoint);
      mountPointForKey.put(mountPoint.getKey(), mountPoint);
      Log.d(TAG, "mountpoint " + path + " internal=" + internal);
    }
  }

  public static void reset() {
    mountPoints = new ArrayList<>();
    mountPointForKey = new HashMap<>();
    init = false;
  }
}
