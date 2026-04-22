package com.wifithermal;

import android.content.Context;
import android.util.Log;
import java.io.*;

public class IperfRunner {
    private static final String TAG = "IperfRunner";
    private final File binaryFile;
    private final File workDir;   // 可写工作目录，解决 "unable to create stream: Permission denied"
    private volatile Process process;

    public interface LineCallback {
        void onLine(String line);
        void onDone(int exitCode);
    }

    public IperfRunner(Context ctx) {
        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        binaryFile = new File(nativeDir, "libiperf3.so");
        workDir    = ctx.getFilesDir();   // App私有可写目录
        Log.i(TAG, "iperf3=" + binaryFile.getAbsolutePath()
                + " exists=" + binaryFile.exists()
                + " canExec=" + binaryFile.canExecute()
                + " workDir=" + workDir.getAbsolutePath());
    }

    public boolean isAvailable() {
        return binaryFile.exists() && binaryFile.canExecute();
    }

    public String getBinaryPath() { return binaryFile.getAbsolutePath(); }

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
                    "-R",
                    "-f", "m",
                    "-i", "1",
                };
                Log.i(TAG, "cmd: " + String.join(" ", cmd));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.directory(workDir);
                pb.environment().put("TMPDIR", workDir.getAbsolutePath()); // iperf3 mkstemp临时文件目录
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
                callback.onLine("EXEC_ERR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                callback.onDone(-1);
            }
        }, "iperf3-runner").start();
    }

    public void stop() {
        if (process != null) process.destroy();
    }
}
