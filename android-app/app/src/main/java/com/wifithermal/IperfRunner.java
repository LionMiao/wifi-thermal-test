package com.wifithermal;

import android.content.Context;
import android.util.Log;
import java.io.*;

/**
 * Runs iperf3 binary (packaged as libiperf3.so in jniLibs/arm64-v8a).
 * Android allows executing from nativeLibraryDir.
 */
public class IperfRunner {
    private static final String TAG = "IperfRunner";

    private final File binaryFile;
    private volatile Process process;

    public interface LineCallback {
        void onLine(String line);
        void onDone(int exitCode);
    }

    public IperfRunner(Context ctx) {
        // nativeLibraryDir is the only place Android allows exec from
        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        binaryFile = new File(nativeDir, "libiperf3.so");
        Log.i(TAG, "iperf3 path: " + binaryFile.getAbsolutePath()
                + " exists=" + binaryFile.exists()
                + " exec=" + binaryFile.canExecute());
    }

    public boolean isAvailable() {
        return binaryFile.exists() && binaryFile.canExecute();
    }

    public String getBinaryPath() {
        return binaryFile.getAbsolutePath();
    }

    /**
     * iperf3 -c host -p port -t duration -P parallel --forceflush -f m -i 1
     */
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
                Log.i(TAG, "Running: " + String.join(" ", cmd));
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
                callback.onDone(code);
            } catch (Exception e) {
                Log.e(TAG, "iperf3 run error", e);
                callback.onLine("ERROR: " + e.getMessage());
                callback.onDone(-1);
            }
        }, "iperf3-runner").start();
    }

    public void stop() {
        if (process != null) process.destroy();
    }
}
