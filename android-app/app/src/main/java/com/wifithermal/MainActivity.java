package com.wifithermal;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.net.Socket;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private EditText etIp, etPort, etDuration;
    private Button btnStart;
    private TextView tvStatus, tvTime, tvSendRate, tvRecvRate, tvTotalRate;
    private Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private Socket socket;
    private long totalSent = 0, totalRecv = 0;
    private long lastSent = 0, lastRecv = 0;
    private long startTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etIp = findViewById(R.id.etIp);
        etPort = findViewById(R.id.etPort);
        etDuration = findViewById(R.id.etDuration);
        btnStart = findViewById(R.id.btnStart);
        tvStatus = findViewById(R.id.tvStatus);
        tvTime = findViewById(R.id.tvTime);
        tvSendRate = findViewById(R.id.tvSendRate);
        tvRecvRate = findViewById(R.id.tvRecvRate);
        tvTotalRate = findViewById(R.id.tvTotalRate);

        btnStart.setOnClickListener(v -> {
            if (!running) {
                startTest();
            } else {
                stopTest();
            }
        });
    }

    private void startTest() {
        String ip = etIp.getText().toString().trim();
        int port = Integer.parseInt(etPort.getText().toString().trim());
        int durationMin = Integer.parseInt(etDuration.getText().toString().trim());
        int durationSec = durationMin * 60;

        running = true;
        totalSent = 0;
        totalRecv = 0;
        lastSent = 0;
        lastRecv = 0;
        btnStart.setText("Stop");
        tvStatus.setText("Connecting...");

        new Thread(() -> {
            try {
                socket = new Socket(ip, port);
                socket.setSendBufferSize(4 * 1024 * 1024);
                socket.setReceiveBufferSize(4 * 1024 * 1024);
                socket.setTcpNoDelay(true);

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // Handshake
                out.write(("START:" + durationSec + "\n").getBytes());
                out.flush();
                byte[] buf = new byte[1024];
                int n = in.read(buf);
                String resp = new String(buf, 0, n).trim();
                if (!resp.equals("OK")) {
                    updateUI("Handshake failed: " + resp);
                    return;
                }

                startTime = System.currentTimeMillis();
                updateUI("Running...");

                // Sender thread
                Thread sender = new Thread(() -> {
                    byte[] data = new byte[1024 * 1024];
                    while (running) {
                        try {
                            out.write(data);
                            synchronized (this) { totalSent += data.length; }
                        } catch (Exception e) { break; }
                    }
                });
                sender.setDaemon(true);
                sender.start();

                // Receiver thread
                Thread receiver = new Thread(() -> {
                    byte[] data = new byte[1024 * 1024];
                    while (running) {
                        try {
                            int r = in.read(data);
                            if (r <= 0) break;
                            synchronized (this) { totalRecv += r; }
                        } catch (Exception e) { break; }
                    }
                });
                receiver.setDaemon(true);
                receiver.start();

                // UI updater
                while (running) {
                    Thread.sleep(1000);
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    long s, r;
                    synchronized (this) { s = totalSent; r = totalRecv; }
                    double sr = (s - lastSent) / 1024.0 / 1024.0;
                    double rr = (r - lastRecv) / 1024.0 / 1024.0;
                    lastSent = s;
                    lastRecv = r;

                    long min = elapsed / 60;
                    long sec = elapsed % 60;
                    final String timeStr = String.format(Locale.US, "%02d:%02d", min, sec);
                    final String sStr = String.format(Locale.US, "TX: %.1f MB/s", sr);
                    final String rStr = String.format(Locale.US, "RX: %.1f MB/s", rr);
                    final String tStr = String.format(Locale.US, "Total: %.1f MB/s", sr + rr);

                    handler.post(() -> {
                        tvTime.setText("Time: " + timeStr);
                        tvSendRate.setText(sStr);
                        tvRecvRate.setText(rStr);
                        tvTotalRate.setText(tStr);
                    });

                    if (durationSec > 0 && elapsed >= durationSec) {
                        running = false;
                    }
                }

                updateUI("Test complete!");

            } catch (Exception e) {
                updateUI("Error: " + e.getMessage());
            } finally {
                running = false;
                try { socket.close(); } catch (Exception ignored) {}
                handler.post(() -> btnStart.setText("Start Test"));
            }
        }).start();
    }

    private void stopTest() {
        running = false;
        try { socket.close(); } catch (Exception ignored) {}
        btnStart.setText("Start Test");
        tvStatus.setText("Stopped");
    }

    private void updateUI(String status) {
        handler.post(() -> tvStatus.setText(status));
    }
}
