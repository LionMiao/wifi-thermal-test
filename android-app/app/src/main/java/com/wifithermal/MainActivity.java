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

    static final int DATA_PORT     = 5556;
    static final int CONTROL_PORT  = 5555;
    static final int NUM_STREAMS   = 8;
    static final int BUF_SIZE      = 1024 * 1024; // 1MB
    static final int SMOOTH_WINDOW = 3;

    private EditText etIp, etPort, etDuration;
    private Button btnStart;
    private TextView tvStatus, tvTime, tvTx, tvRx, tvTotal, tvTemp0, tvTemp1;
    private ChartView chartView;
    private Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private Socket ctrlSocket;
    private final List<Socket> dataSockets = new ArrayList<>();

    private volatile long totalSent = 0, totalRecv = 0;
    private long lastSent = 0, lastRecv = 0;
    private long startTime = 0;
    private int durationSec = 0;

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
        tvStatus.setText("Ready");
        btnStart.setOnClickListener(v -> { if (!running) startTest(); else stopTest(); });
    }

    private void startTest() {
        String ip     = etIp.getText().toString().trim();
        int ctrlPort  = Integer.parseInt(etPort.getText().toString().trim());
        durationSec   = Integer.parseInt(etDuration.getText().toString().trim()) * 60;

        running = true;
        totalSent = 0; totalRecv = 0; lastSent = 0; lastRecv = 0;
        txWindow.clear(); rxWindow.clear();
        chartView.clear(); chartView.setDuration(durationSec);
        startTime = System.currentTimeMillis();
        btnStart.setText("Stop");
        tvStatus.setText("Connecting...");

        new Thread(() -> {
            try {
                // ── 控制连接 ──
                ctrlSocket = new Socket(ip, ctrlPort);
                ctrlSocket.setTcpNoDelay(true);
                OutputStream ctrlOut = ctrlSocket.getOutputStream();
                BufferedReader ctrlIn = new BufferedReader(
                    new InputStreamReader(ctrlSocket.getInputStream()));

                ctrlOut.write(("START:" + durationSec + "\n").getBytes());
                ctrlOut.flush();
                String resp = ctrlIn.readLine();
                if (!"OK".equals(resp)) { updateStatus("Handshake fail: " + resp); running=false; return; }

                updateStatus("Opening streams...");

                // ── 数据流连接 ──
                synchronized (dataSockets) { dataSockets.clear(); }
                for (int i = 0; i < NUM_STREAMS; i++) {
                    Socket ds = new Socket(ip, DATA_PORT);
                    ds.setSendBufferSize(4 * 1024 * 1024);
                    ds.setReceiveBufferSize(4 * 1024 * 1024);
                    ds.setTcpNoDelay(true);
                    synchronized (dataSockets) { dataSockets.add(ds); }
                }

                updateStatus("Running...");

                // ── 每条流独立 sender + receiver 线程 ──
                for (Socket ds : new ArrayList<>(dataSockets)) {
                    final Socket fds = ds;
                    // sender
                    new Thread(() -> {
                        byte[] buf = new byte[BUF_SIZE];
                        try {
                            OutputStream out = fds.getOutputStream();
                            while (running) {
                                out.write(buf);
                                totalSent += BUF_SIZE;
                            }
                        } catch (Exception ignored) {}
                    }, "sender").start();
                    // receiver
                    new Thread(() -> {
                        byte[] buf = new byte[BUF_SIZE];
                        try {
                            InputStream in = fds.getInputStream();
                            while (running) {
                                int n = in.read(buf);
                                if (n <= 0) break;
                                totalRecv += n;
                            }
                        } catch (Exception ignored) {}
                    }, "receiver").start();
                }

                // ── 本地 UI 速率更新（每秒，滑动平均）──
                new Thread(() -> {
                    while (running) {
                        try { Thread.sleep(1000); } catch (Exception e) { break; }
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        long s = totalSent, r = totalRecv;
                        double txMbps = (s - lastSent) * 8.0 / 1_000_000.0;
                        double rxMbps = (r - lastRecv) * 8.0 / 1_000_000.0;
                        lastSent = s; lastRecv = r;
                        txWindow.addLast(txMbps); if (txWindow.size()>SMOOTH_WINDOW) txWindow.removeFirst();
                        rxWindow.addLast(rxMbps); if (rxWindow.size()>SMOOTH_WINDOW) rxWindow.removeFirst();
                        double sTx=0, sRx=0;
                        for (double v:txWindow) sTx+=v; sTx/=txWindow.size();
                        for (double v:rxWindow) sRx+=v; sRx/=rxWindow.size();
                        final double fTx=sTx, fRx=sRx, fTot=sTx+sRx;
                        final String tStr=String.format(Locale.US,"%02d:%02d",elapsed/60,elapsed%60);
                        handler.post(()->{
                            tvTime.setText(tStr);
                            tvTx.setText(String.format(Locale.US,"TX: %.0f Mbps",fTx));
                            tvRx.setText(String.format(Locale.US,"RX: %.0f Mbps",fRx));
                            tvTotal.setText(String.format(Locale.US,"Total: %.0f Mbps",fTot));
                        });
                        chartView.updateLastRate((System.currentTimeMillis()-startTime)/1000f, (float)fTot);
                        if (durationSec>0 && elapsed>=durationSec) { running=false; break; }
                    }
                }, "ui-updater").start();

                // ── 读服务端温度 STAT 行 ──
                String line;
                while (running && (line = ctrlIn.readLine()) != null) {
                    if (line.startsWith("END")) { running=false; break; }
                    if (!line.startsWith("STAT:")) continue;
                    try {
                        String[] p = line.split(":");
                        float t0 = Float.parseFloat(p[2]);
                        float t1 = Float.parseFloat(p[3]);
                        float elapsed = Float.parseFloat(p[1]);
                        final float ft0=t0, ft1=t1, fe=elapsed;
                        handler.post(()->{
                            tvTemp0.setText(String.format(Locale.US,"phy0: %.1f°C",ft0));
                            tvTemp1.setText(String.format(Locale.US,"phy1: %.1f°C",ft1));
                        });
                        chartView.updateLastTemp(fe, t0, t1);
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
        try { if (ctrlSocket!=null) ctrlSocket.close(); } catch (Exception ignored) {}
    }

    private void updateStatus(String s) { handler.post(()->tvStatus.setText(s)); }

    @Override
    protected void onDestroy() { super.onDestroy(); stopTest(); }
}
