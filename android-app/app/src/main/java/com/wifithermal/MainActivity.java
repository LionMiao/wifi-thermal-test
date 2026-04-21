package com.wifithermal;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.wifithermal.ChartView.DataPoint;
import java.io.*;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // iperf3 server listens on 5201 by default; we keep 5555 for our control (temp push)
    static final int IPERF_PORT    = 5201;
    static final int CONTROL_PORT  = 5555;
    static final int PARALLEL      = 8;
    static final int SMOOTH_WINDOW = 3;

    private EditText etIp, etPort, etDuration;
    private Button btnStart;
    private TextView tvStatus, tvTime, tvTx, tvRx, tvTotal, tvTemp0, tvTemp1;
    private ChartView chartView;
    private Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private IperfRunner iperfRunner;
    private Socket ctrlSocket;
    private long startTime;
    private int durationSec;

    private final ArrayDeque<Double> rateWindow = new ArrayDeque<>();

    // iperf3 interval line pattern:
    // [SUM]   0.00-1.00   sec  xxx MBytes  xxx Mbits/sec
    private static final Pattern INTERVAL_PATTERN = Pattern.compile(
        "\\[SUM\\].*?(\\d+\\.\\d+)-(\\d+\\.\\d+)\\s+sec.*?(\\d+\\.\\d+)\\s+Mbits/sec"
    );
    // single stream (no SUM) when parallel=1
    private static final Pattern SINGLE_PATTERN = Pattern.compile(
        "\\[\\s*\\d+\\].*?(\\d+\\.\\d+)-(\\d+\\.\\d+)\\s+sec.*?(\\d+\\.\\d+)\\s+Mbits/sec.*?sender"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etIp       = findViewById(R.id.etIp);
        etPort     = findViewById(R.id.etPort);
        etDuration = findViewById(R.id.etDuration);
        btnStart   = findViewById(R.id.btnStart);
        tvStatus   = findViewById(R.id.tvStatus);
        tvTime     = findViewById(R.id.tvTime);
        tvTx       = findViewById(R.id.tvTx);
        tvRx       = findViewById(R.id.tvRx);
        tvTotal    = findViewById(R.id.tvTotal);
        tvTemp0    = findViewById(R.id.tvTemp0);
        tvTemp1    = findViewById(R.id.tvTemp1);
        chartView  = findViewById(R.id.chartView);

        iperfRunner = new IperfRunner(this);
        try {
            
            if (iperfRunner.isAvailable()) tvStatus.setText("Ready"); else tvStatus.setText("iperf3 not found: " + iperfRunner.getBinaryPath());
        } catch (Exception e) {
        }

        btnStart.setOnClickListener(v -> {
            if (!running) startTest();
            else stopTest();
        });
    }

    private void startTest() {
        String ip = etIp.getText().toString().trim();
        durationSec = Integer.parseInt(etDuration.getText().toString().trim()) * 60;

        running = true;
        rateWindow.clear();
        chartView.clear();
        chartView.setDuration(durationSec);
        startTime = System.currentTimeMillis();
        btnStart.setText("Stop");
        tvStatus.setText("Connecting...");

        // Control connection for temperature push
        new Thread(() -> {
            try {
                ctrlSocket = new Socket(ip, CONTROL_PORT);
                ctrlSocket.setTcpNoDelay(true);
                OutputStream ctrlOut = ctrlSocket.getOutputStream();
                BufferedReader ctrlIn = new BufferedReader(
                    new InputStreamReader(ctrlSocket.getInputStream()));

                // Tell server test duration (for logging/chart on server side)
                ctrlOut.write(("START:" + durationSec + "\n").getBytes());
                ctrlOut.flush();
                String resp = ctrlIn.readLine();
                Log.i(TAG, "Control resp: " + resp);

                // Read STAT lines: STAT:elapsed:t0:t1:tx:rx
                String line;
                while (running && (line = ctrlIn.readLine()) != null) {
                    if (line.startsWith("END")) break;
                    if (!line.startsWith("STAT:")) continue;
                    try {
                        String[] p = line.split(":");
                        float t0 = Float.parseFloat(p[2]);
                        float t1 = Float.parseFloat(p[3]);
                        final float ft0 = t0, ft1 = t1;
                        handler.post(() -> {
                            tvTemp0.setText(String.format(Locale.US, "phy0: %.1f°C", ft0));
                            tvTemp1.setText(String.format(Locale.US, "phy1: %.1f°C", ft1));
                        });
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "Control socket: " + e.getMessage());
            }
        }, "ctrl-thread").start();

        // Time updater
        new Thread(() -> {
            while (running) {
                try { Thread.sleep(1000); } catch (Exception e) { break; }
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                long min = elapsed / 60, sec = elapsed % 60;
                final String t = String.format(Locale.US, "%02d:%02d", min, sec);
                handler.post(() -> tvTime.setText(t));
                if (durationSec > 0 && elapsed >= durationSec + 5) running = false;
            }
        }, "time-updater").start();

        // Run iperf3
        iperfRunner.runClient(ip, IPERF_PORT, durationSec, PARALLEL,
            new IperfRunner.LineCallback() {
                @Override
                public void onLine(String line) {
                    Log.d(TAG, "iperf3: " + line);
                    parseAndDisplay(line);
                }
                @Override
                public void onDone(int exitCode) {
                    running = false;
                    handler.post(() -> {
                        tvStatus.setText(exitCode == 0 ? "Test complete!" : "Done (code=" + exitCode + ")");
                        btnStart.setText("Start Test");
                    });
                    try { if (ctrlSocket != null) ctrlSocket.close(); } catch (Exception ignored) {}
                }
            });

        updateStatus("Running...");
    }

    private void parseAndDisplay(String line) {
        // Match interval summary line (SUM for multi-stream)
        Matcher m = INTERVAL_PATTERN.matcher(line);
        if (!m.find()) return;

        try {
            float t1 = Float.parseFloat(m.group(1));
            float t2 = Float.parseFloat(m.group(2));
            // Only process 1-second intervals, skip the final summary
            if (t2 - t1 > 1.5f) return;

            double mbps = Double.parseDouble(m.group(3));

            // Sliding average
            rateWindow.addLast(mbps);
            if (rateWindow.size() > SMOOTH_WINDOW) rateWindow.removeFirst();
            double smooth = rateWindow.stream().mapToDouble(d -> d).average().orElse(0);

            float elapsed = (System.currentTimeMillis() - startTime) / 1000f;
            final double fSmooth = smooth;
            final float fElapsed = elapsed;

            handler.post(() -> {
                tvTx.setText(String.format(Locale.US, "TX: %.0f Mbps", fSmooth));
                tvRx.setText("RX: --");
                tvTotal.setText(String.format(Locale.US, "Total: %.0f Mbps", fSmooth));
            });

            // Add to chart using server-side temp (chart updates when STAT arrives)
            // We update rate here, temp is updated separately from control socket
            chartView.updateLastRate(fElapsed, (float) fSmooth);
        } catch (Exception e) {
            Log.w(TAG, "Parse error: " + e.getMessage() + " line=" + line);
        }
    }

    private void stopTest() {
        running = false;
        iperfRunner.stop();
        try { if (ctrlSocket != null) ctrlSocket.close(); } catch (Exception ignored) {}
        btnStart.setText("Start Test");
        tvStatus.setText("Stopped");
    }

    private void updateStatus(String s) {
        handler.post(() -> tvStatus.setText(s));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTest();
    }
}
