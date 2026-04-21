package com.wifithermal;

import android.content.Context;
import android.util.Log;
import java.io.*;

public class IperfRunner {
    private static final String TAG = "IperfRunner";
    private final File binaryFile;
    private volatile Process process;

    public interface LineCallback {
        void onLine(String line);
        void onDone(int exitCode);
    }

    public IperfRunner(Context ctx) {
        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        binaryFile = new File(nativeDir, "libiperf3.so");
        Log.i(TAG, "iperf3 path=" + binaryFile.getAbsolutePath()
                + " exists=" + binaryFile.exists()
                + " canExec=" + binaryFile.canExecute());
    }

    public boolean isAvailable() {
        return binaryFile.exists() && binaryFile.canExecute();
    }

    public String getBinaryPath() { return binaryFile.getAbsolutePath(); }

    /** 先跑 --version 做诊断，结果通过 callback 回传 */
    public void checkVersion(LineCallback callback) {
        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(binaryFile.getAbsolutePath(), "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) callback.onLine("VER: " + line);
                int code = p.waitFor();
                callback.onDone(code);
            } catch (Exception e) {
                callback.onLine("VER_ERR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                callback.onDone(-1);
            }
        }, "iperf3-version-check").start();
    }

    /** 正式跑 iperf3 client */
    public void runClient(String host, int port, int duration, int parallel,
                          LineCallback callback) {
        new Thread(() -> {
            try {
                String[] cmd = {
                    binaryFile.getAbsolutePath(),
                    "-c", host,
                    "-p", String.valueOf(port),
                    "-t", String.valueOf(duration),
                    "-P", String.valueOf(parallel),
                    "--forceflush",
                    "-f", "m",
                    "-i", "1",
                };
                Log.i(TAG, "cmd: " + String.join(" ", cmd));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                process = pb.start();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, line);
                    callback.onLine(line);
                }
                int code = process.waitFor();
                Log.i(TAG, "iperf3 exit code=" + code);
                callback.onDone(code);
            } catch (Exception e) {
                Log.e(TAG, "iperf3 run error", e);
                // 把完整异常信息透传到UI
                callback.onLine("EXEC_ERR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                callback.onDone(-1);
            }
        }, "iperf3-runner").start();
    }

    public void stop() {
        if (process != null) process.destroy();
    }
}
