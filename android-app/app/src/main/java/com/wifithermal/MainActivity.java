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
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    static final int CONTROL_PORT_DEFAULT = 5555;
    static final int DATA_PORT = 5556;
    static final int NUM_STREAMS = 8;
    static final int STREAM_BUF = 512 * 1024;

    private EditText etIp, etPort, etDuration;
    private Button btnStart;
    private TextView tvStatus, tvTime, tvTx, tvRx, tvTotal, tvTemp0, tvTemp1;
    private ChartView chartView;
    private Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private Socket ctrlSocket;
    private final List<Socket> dataSockets = new ArrayList<>();
    private long totalSent = 0, totalRecv = 0;
    private long lastSent = 0, lastRecv = 0;
    private long startTime = 0;
    private int durationSec = 0;

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

    private void startTest() {
        String ip = etIp.getText().toString().trim();
        int ctrlPort = Integer.parseInt(etPort.getText().toString().trim());
        durationSec = Integer.parseInt(etDuration.getText().toString().trim()) * 60;

        running = true;
        totalSent = 0; totalRecv = 0;
        lastSent = 0; lastRecv = 0;
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

                updateStatus("Connecting data streams...");

                // Open NUM_STREAMS data connections
                synchronized (dataSockets) { dataSockets.clear(); }
                for (int i = 0; i < NUM_STREAMS; i++) {
                    Socket ds = new Socket(ip, DATA_PORT);
                    ds.setSendBufferSize(2 * 1024 * 1024);
                    ds.setReceiveBufferSize(2 * 1024 * 1024);
                    ds.setTcpNoDelay(true);
                    synchronized (dataSockets) { dataSockets.add(ds); }
                }

                startTime = System.currentTimeMillis();
                updateStatus("Running...");

                // Launch sender+receiver for each data socket
                for (Socket ds : dataSockets) {
                    final Socket fds = ds;
                    Thread t = new Thread(() -> {
                        byte[] buf = new byte[STREAM_BUF];
                        while (running) {
                            try {
                                fds.getOutputStream().write(buf);
                                synchronized (MainActivity.this) { totalSent += buf.length; }
                            } catch (Exception e) { break; }
                        }
                    });
                    t.setDaemon(true); t.start();

                    Thread r = new Thread(() -> {
                        byte[] buf = new byte[STREAM_BUF];
                        while (running) {
                            try {
                                int n = fds.getInputStream().read(buf);
                                if (n <= 0) break;
                                synchronized (MainActivity.this) { totalRecv += n; }
                            } catch (Exception e) { break; }
                        }
                    });
                    r.setDaemon(true); r.start();
                }

                // UI updater from local stats
                Thread uiThread = new Thread(() -> {
                    while (running) {
                        try { Thread.sleep(1000); } catch (Exception e) { break; }
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        long s, r;
                        synchronized (MainActivity.this) { s = totalSent; r = totalRecv; }
                        double txMbps = (s - lastSent) * 8.0 / 1_000_000;
                        double rxMbps = (r - lastRecv) * 8.0 / 1_000_000;
                        lastSent = s; lastRecv = r;
                        long min = elapsed / 60, sec = elapsed % 60;
                        final String tStr = String.format(Locale.US, "%02d:%02d", min, sec);
                        final String txStr = String.format(Locale.US, "TX: %.0f Mbps", txMbps);
                        final String rxStr = String.format(Locale.US, "RX: %.0f Mbps", rxMbps);
                        final String totStr = String.format(Locale.US, "Total: %.0f Mbps", txMbps + rxMbps);
                        handler.post(() -> {
                            tvTime.setText(tStr);
                            tvTx.setText(txStr);
                            tvRx.setText(rxStr);
                            tvTotal.setText(totStr);
                        });
                        if (durationSec > 0 && elapsed >= durationSec) { running = false; break; }
                    }
                });
                uiThread.setDaemon(true); uiThread.start();

                // Read stats from server (STAT:elapsed:t0:t1:tx:rx)
                String line;
                while (running && (line = ctrlIn.readLine()) != null) {
                    if (line.startsWith("END")) { running = false; break; }
                    if (!line.startsWith("STAT:")) continue;
                    try {
                        String[] p = line.split(":");
                        float elapsed = Float.parseFloat(p[1]);
                        float t0      = Float.parseFloat(p[2]);
                        float t1      = Float.parseFloat(p[3]);
                        float txMbps  = Float.parseFloat(p[4]);
                        float rxMbps  = Float.parseFloat(p[5]);
                        float totMbps = txMbps + rxMbps;

                        final float ft0 = t0, ft1 = t1, ftot = totMbps;
                        handler.post(() -> {
                            tvTemp0.setText(String.format(Locale.US, "phy0: %.1f°C", ft0));
                            tvTemp1.setText(String.format(Locale.US, "phy1: %.1f°C", ft1));
                        });
                        chartView.addPoint(new DataPoint(elapsed, t0, t1, totMbps));
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
        }).start();
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
