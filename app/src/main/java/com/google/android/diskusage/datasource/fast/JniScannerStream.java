package com.google.android.diskusage.datasource.fast;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION_CODES;

import com.google.android.diskusage.datasource.DataSource;
import com.google.android.diskusage.jniutils.ScanUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class JniScannerStream extends InputStream {

  private final InputStream is;
  private final Process process;

  public JniScannerStream(InputStream is, Process process) {
    this.is = is;
    this.process = process;
  }

  @Override
  public int read() throws IOException {
    return is.read();
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    return is.read(buffer);
  }

  @Override
  public int read(byte[] buffer, int byteOffset, int byteCount)
      throws IOException {
    return is.read(buffer, byteOffset, byteCount);
  }

  @Override
  public void close() throws IOException {
    is.close();
    try {
      process.waitFor();
    } catch (InterruptedException e) {
      throw new IOException(e.getMessage());
    }
  }

  static class Factory {
    private final Context context;
    private static boolean remove = true;

    Factory(Context context) {
      this.context = context;
    }

    public JniScannerStream create(String path, boolean rootRequired)
        throws IOException, InterruptedException {
      return runScanner(path, rootRequired);
    }

    public InputStream create(String path)
        throws IOException, InterruptedException {
        final byte[] result = ScanUtils.scanDirectory(path).getBytes(StandardCharsets.UTF_8);


        return new InputStream() {
            private int position = 0;

            @Override
            public int read() throws IOException {
                if (position >= result.length) {
                    return -1;
                }
                return result[position++] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int byteOffset, int byteCount) throws IOException {
                if (position >= result.length) {
                    return -1;
                }

                int available = result.length - position;
                int toRead = Math.min(byteCount, available);

                System.arraycopy(result, position, buffer, byteOffset, toRead);
                position += toRead;

                return toRead;
            }
        };
    }

    private JniScannerStream runScanner(String root,
                                        boolean rootRequired) throws IOException, InterruptedException {
      String binaryName = "scan";
      final int sdkVersion = DataSource.get().getAndroidVersion();
      if (sdkVersion >= 21 /* Lollipop */) {
        binaryName = "scan5";
      }
      setupBinary(binaryName);
      boolean deviceIsRooted = DataSource.get().isDeviceRooted();
      Process process = null;


      if (!(rootRequired && deviceIsRooted)) {
        process = Runtime.getRuntime().exec(new String[] {
            getScanBinaryPath(binaryName), root});
      } else {
        IOException e = null;
        for (String su : new String[] { "su", "/system/bin/su", "/system/xbin/su" }) {
          try {
            process = Runtime.getRuntime().exec(new String[] { su });
            break;
          } catch(IOException newe) {
            e = newe;
          }
        }

        if (process == null) {
          throw e;
        }

        OutputStream os = process.getOutputStream();
        os.write((getScanBinaryPath(binaryName) + " " + root).getBytes("UTF-8"));
        os.flush();
        os.close();
      }
      InputStream is = process.getInputStream();
      return new JniScannerStream(is, process);
    }

    public void setupBinary(String binaryName)
        throws IOException, InterruptedException {
      // Remove 'scan' binary every run. TODO: do clean update on package update
//      if (remove) {
        new File(getScanBinaryPath(binaryName)).delete();
//        remove = false;
//      }

      File binary = new File(getScanBinaryPath(binaryName));
      if (binary.isFile()) return;
      unpackScanBinary(binaryName);
      runChmod(binaryName);
    }

    private String getScanBinaryPath(String binaryName) {
      return context.getDir("binary", Context.MODE_PRIVATE).getAbsolutePath()
          + "/" + binaryName;
    }

    private void runChmod(String binaryName)
        throws IOException, InterruptedException {
      if (Integer.parseInt(Build.VERSION.SDK) >= VERSION_CODES.GINGERBREAD) {
        try {
          setExecutable(binaryName);
          return;
        } catch (Exception e) {
          // fall back to legacy way
        }
      }
      Process process;
      try {
        process = Runtime.getRuntime().exec(
            "chmod 0555 " + getScanBinaryPath(binaryName));
      } catch (IOException e) {
        try {
          process = Runtime.getRuntime().exec(
              "/system/bin/chmod 0555 " + getScanBinaryPath(binaryName));
        } catch (IOException ee ) {
          throw new RuntimeException("Failed to chmod", ee);
        }
      }
      process.waitFor();
    }

    @TargetApi(VERSION_CODES.GINGERBREAD)
    private void setExecutable(String binaryName) {
      if (new File(binaryName).setExecutable(true, true) == false) {
        throw new RuntimeException("Failed to setExecutable");
      }
    }

    private void unpackScanBinary(String binaryName) throws IOException {
      InputStream is = context.getAssets().open(binaryName);
      FileOutputStream os = new FileOutputStream(getScanBinaryPath(binaryName));
      StreamCopy.copyStream(is, os);
    }
  }
}
