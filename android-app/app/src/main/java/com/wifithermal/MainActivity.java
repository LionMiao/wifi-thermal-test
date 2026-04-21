package com.wifithermal;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.wifithermal.ChartView.DataPoint;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    static final int DATA_PORT = 5556;
    static final int NUM_STREAMS = 16;
    static final int STREAM_BUF = 1024 * 1024;
    static final int SMOOTH_WINDOW = 3; // sliding average seconds

    private EditText etIp, etPort, etDuration;
    private Button btnStart;
    private TextView tvStatus, tvTime, tvTx, tvRx, tvTotal, tvTemp0, tvTemp1;
    private ChartView chartView;
    private Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private Socket ctrlSocket;
    private final List<Socket> dataSockets = new ArrayList<>();

    // Use volatile longs for atomic-ish reads (good enough with sync below)
    private long totalSent = 0, totalRecv = 0;
    private long lastSent = 0, lastRecv = 0;
    private long startTime = 0;
    private int durationSec = 0;

    // Sliding window for smoothing
    private final ArrayDeque<Double> txWindow = new ArrayDeque<>();
    private final ArrayDeque<Double> rxWindow = new ArrayDeque<>();

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

        btnStart.setOnClickListener(v -> {
            if (!running) startTest();
            else stopTest();
        });
    }

    private double windowAvg(ArrayDeque<Double> dq) {
        double sum = 0;
        for (double v : dq) sum += v;
        return dq.isEmpty() ? 0 : sum / dq.size();
    }

    private void startTest() {
        String ip = etIp.getText().toString().trim();
        int ctrlPort = Integer.parseInt(etPort.getText().toString().trim());
        durationSec = Integer.parseInt(etDuration.getText().toString().trim()) * 60;

        running = true;
        totalSent = 0; totalRecv = 0;
        lastSent = 0; lastRecv = 0;
        txWindow.clear(); rxWindow.clear();
        chartView.clear();
        chartView.setDuration(durationSec);
        btnStart.setText("Stop");
        tvStatus.setText("Connecting...");

        new Thread(() -> {
            try {
                // Control connection
                ctrlSocket = new Socket(ip, ctrlPort);
                ctrlSocket.setTcpNoDelay(true);
                OutputStream ctrlOut = ctrlSocket.getOutputStream();
                BufferedReader ctrlIn = new BufferedReader(
                    new InputStreamReader(ctrlSocket.getInputStream()));

                // Handshake
                ctrlOut.write(("START:" + durationSec + "\n").getBytes());
                ctrlOut.flush();
                String resp = ctrlIn.readLine();
                if (!"OK".equals(resp)) { updateStatus("Handshake failed: " + resp); return; }

                updateStatus("Opening data streams...");

                synchronized (dataSockets) { dataSockets.clear(); }
                for (int i = 0; i < NUM_STREAMS; i++) {
                    Socket ds = new Socket(ip, DATA_PORT);
                    ds.setSendBufferSize(4 * 1024 * 1024);
                    ds.setReceiveBufferSize(4 * 1024 * 1024);
                    ds.setTcpNoDelay(true);
                    synchronized (dataSockets) { dataSockets.add(ds); }
                }

                startTime = System.currentTimeMillis();
                updateStatus("Running...");

                // Sender + receiver per stream
                for (Socket ds : new ArrayList<>(dataSockets)) {
                    final Socket fds = ds;
                    new Thread(() -> {
                        byte[] buf = new byte[STREAM_BUF];
                        while (running) {
                            try {
                                fds.getOutputStream().write(buf);
                                synchronized (MainActivity.this) { totalSent += buf.length; }
                            } catch (Exception e) { break; }
                        }
                    }, "sender").start();

                    new Thread(() -> {
                        byte[] buf = new byte[STREAM_BUF];
                        while (running) {
                            try {
                                int n = fds.getInputStream().read(buf);
                                if (n <= 0) break;
                                synchronized (MainActivity.this) { totalRecv += n; }
                            } catch (Exception e) { break; }
                        }
                    }, "receiver").start();
                }

                // Local UI updater: compute Mbps = bytes * 8 / 1_000_000, with sliding avg
                new Thread(() -> {
                    while (running) {
                        try { Thread.sleep(1000); } catch (Exception e) { break; }
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        long s, r;
                        synchronized (MainActivity.this) { s = totalSent; r = totalRecv; }

                        double txMbps = (s - lastSent) * 8.0 / 1_000_000.0;
                        double rxMbps = (r - lastRecv) * 8.0 / 1_000_000.0;
                        lastSent = s; lastRecv = r;

                        // Sliding average
                        txWindow.addLast(txMbps);
                        rxWindow.addLast(rxMbps);
                        if (txWindow.size() > SMOOTH_WINDOW) txWindow.removeFirst();
                        if (rxWindow.size() > SMOOTH_WINDOW) rxWindow.removeFirst();
                        double smoothTx = windowAvg(txWindow);
                        double smoothRx = windowAvg(rxWindow);
                        double smoothTotal = smoothTx + smoothRx;

                        long min = elapsed / 60, sec = elapsed % 60;
                        final String tStr   = String.format(Locale.US, "%02d:%02d", min, sec);
                        final String txStr  = String.format(Locale.US, "TX: %.0f Mbps", smoothTx);
                        final String rxStr  = String.format(Locale.US, "RX: %.0f Mbps", smoothRx);
                        final String totStr = String.format(Locale.US, "Total: %.0f Mbps", smoothTotal);
                        handler.post(() -> {
                            tvTime.setText(tStr);
                            tvTx.setText(txStr);
                            tvRx.setText(rxStr);
                            tvTotal.setText(totStr);
                        });
                        if (durationSec > 0 && elapsed >= durationSec) { running = false; break; }
                    }
                }, "ui-updater").start();

                // Read STAT lines from server for temp + chart
                String line;
                while (running && (line = ctrlIn.readLine()) != null) {
                    if (line.startsWith("END")) { running = false; break; }
                    if (!line.startsWith("STAT:")) continue;
                    try {
                        String[] p = line.split(":");
                        float elapsed = Float.parseFloat(p[1]);
                        float t0      = Float.parseFloat(p[2]);
                        float t1      = Float.parseFloat(p[3]);
                        float txM     = Float.parseFloat(p[4]);
                        float rxM     = Float.parseFloat(p[5]);
                        float totM    = txM + rxM;

                        final float ft0 = t0, ft1 = t1;
                        handler.post(() -> {
                            tvTemp0.setText(String.format(Locale.US, "phy0: %.1f°C", ft0));
                            tvTemp1.setText(String.format(Locale.US, "phy1: %.1f°C", ft1));
                        });
                        chartView.addPoint(new DataPoint(elapsed, t0, t1, totM));
                    } catch (Exception ignored) {}
                }

                updateStatus("Test complete!");

            } catch (Exception e) {
                updateStatus("Error: " + e.getMessage());
            } finally {
                running = false;
                cleanup();
                handler.post(() -> btnStart.setText("Start Test"));
            }
        }, "ctrl-thread").start();
    }

    private void stopTest() {
        running = false;
        cleanup();
        btnStart.setText("Start Test");
        tvStatus.setText("Stopped");
    }

    private void cleanup() {
        synchronized (dataSockets) {
            for (Socket s : dataSockets) { try { s.close(); } catch (Exception ignored) {} }
            dataSockets.clear();
        }
        try { if (ctrlSocket != null) ctrlSocket.close(); } catch (Exception ignored) {}
    }

    private void updateStatus(String s) {
        handler.post(() -> tvStatus.setText(s));
    }
}
