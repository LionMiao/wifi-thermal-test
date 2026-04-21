package com.wifithermal;

import android.content.Context;
import android.util.Log;
import java.io.*;

/**
 * Manages the iperf3 ARM64 binary:
 * - copies from assets to app's files dir on first run
 * - executes iperf3 client and streams output line by line
 */
public class IperfRunner {
    private static final String TAG = "IperfRunner";
    private static final String BINARY_NAME = "iperf3";

    private final File binaryFile;
    private Process process;

    public interface LineCallback {
        /** Called for each line of iperf3 stdout */
        void onLine(String line);
        /** Called when iperf3 exits */
        void onDone(int exitCode);
    }

    public IperfRunner(Context ctx) {
        binaryFile = new File(ctx.getFilesDir(), BINARY_NAME);
    }

    /** Copy iperf3 from assets if not already present */
    public void install(Context ctx) throws IOException {
        if (binaryFile.exists() && binaryFile.canExecute()) return;
        try (InputStream in = ctx.getAssets().open(BINARY_NAME);
             FileOutputStream out = new FileOutputStream(binaryFile)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (!binaryFile.setExecutable(true, false)) {
            throw new IOException("Cannot set iperf3 executable");
        }
        Log.i(TAG, "iperf3 installed to " + binaryFile.getAbsolutePath());
    }

    /**
     * Run iperf3 client:
     * iperf3 -c <host> -p <port> -t <duration> -P <parallel> --forceflush -f m
     * Calls callback.onLine() for each output line.
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
                    "-f", "m",           // output in Mbits/s
                    "-i", "1",           // 1-second intervals
                };
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                process = pb.start();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    callback.onLine(line);
                }
                int exitCode = process.waitFor();
                callback.onDone(exitCode);
            } catch (Exception e) {
                Log.e(TAG, "iperf3 error", e);
                callback.onDone(-1);
            }
        }, "iperf3-runner").start();
    }

    public void stop() {
        if (process != null) {
            process.destroy();
        }
    }
}
