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

    static final int IPERF_PORT   = 5201;
    static final int CONTROL_PORT = 5555;
    static final int PARALLEL     = 8;
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

    // 匹配多流 SUM 行: [SUM]  0.00-1.00 sec  xxx MBytes  xxx Mbits/sec
    private static final Pattern SUM_PATTERN = Pattern.compile(
        "\\[SUM\\]\\s+(\\d+\\.\\d+)-(\\d+\\.\\d+)\\s+sec[\\s\\S]+?(\\d+\\.\\d+)\\s+Mbits/sec"
    );
    // 单流行（PARALLEL=1时）
    private static final Pattern SINGLE_PATTERN = Pattern.compile(
        "\\[\\s*\\d+\\]\\s+(\\d+\\.\\d+)-(\\d+\\.\\d+)\\s+sec[\\s\\S]+?(\\d+\\.\\d+)\\s+Mbits/sec"
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

        // 启动时先做版本检测，诊断能不能执行
        if (iperfRunner.isAvailable()) {
            tvStatus.setText("Checking iperf3...");
            iperfRunner.checkVersion(new IperfRunner.LineCallback() {
                @Override public void onLine(String line) {
                    Log.i(TAG, line);
                    handler.post(() -> tvStatus.setText(line));
                }
                @Override public void onDone(int code) {
                    handler.post(() -> {
                        if (code == 0) tvStatus.setText("Ready");
                        else tvStatus.setText("iperf3 check failed code=" + code);
                    });
                }
            });
        } else {
            tvStatus.setText("iperf3 not found: " + iperfRunner.getBinaryPath());
        }

        btnStart.setOnClickListener(v -> { if (!running) startTest(); else stopTest(); });
    }

    private void startTest() {
        String ip    = etIp.getText().toString().trim();
        durationSec  = Integer.parseInt(etDuration.getText().toString().trim()) * 60;

        running = true;
        rateWindow.clear();
        chartView.clear();
        chartView.setDuration(durationSec);
        startTime = System.currentTimeMillis();
        btnStart.setText("Stop");
        tvStatus.setText("Connecting...");

        // ── 控制连接：握手 + 接收温度 STAT ──
        new Thread(() -> {
            try {
                ctrlSocket = new Socket(ip, CONTROL_PORT);
                ctrlSocket.setTcpNoDelay(true);
                OutputStream ctrlOut = ctrlSocket.getOutputStream();
                BufferedReader ctrlIn = new BufferedReader(
                    new InputStreamReader(ctrlSocket.getInputStream()));

                ctrlOut.write(("START:" + durationSec + "\n").getBytes());
                ctrlOut.flush();
                String resp = ctrlIn.readLine();
                Log.i(TAG, "ctrl resp: " + resp);

                String line;
                while (running && (line = ctrlIn.readLine()) != null) {
                    if (line.startsWith("END")) break;
                    if (!line.startsWith("STAT:")) continue;
                    try {
                        String[] p = line.split(":");
                        float elapsed = Float.parseFloat(p[1]);
                        float t0      = Float.parseFloat(p[2]);
                        float t1      = Float.parseFloat(p[3]);
                        final float ft0 = t0, ft1 = t1, fe = elapsed;
                        handler.post(() -> {
                            tvTemp0.setText(String.format(Locale.US, "phy0: %.1f°C", ft0));
                            tvTemp1.setText(String.format(Locale.US, "phy1: %.1f°C", ft1));
                        });
                        chartView.updateLastTemp(fe, t0, t1);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "ctrl: " + e.getMessage());
            }
        }, "ctrl-thread").start();

        // ── 时间更新 ──
        new Thread(() -> {
            while (running) {
                try { Thread.sleep(1000); } catch (Exception e) { break; }
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                final String t = String.format(Locale.US, "%02d:%02d", elapsed/60, elapsed%60);
                handler.post(() -> tvTime.setText(t));
            }
        }, "time-updater").start();

        // ── 跑 iperf3 ──
        iperfRunner.runClient(ip, IPERF_PORT, durationSec, PARALLEL,
            new IperfRunner.LineCallback() {
                @Override
                public void onLine(String line) {
                    Log.d(TAG, "iperf3> " + line);
                    // 把所有输出行显示到 status，方便诊断
                    handler.post(() -> tvStatus.setText(line));
                    parseLine(line);
                }
                @Override
                public void onDone(int exitCode) {
                    running = false;
                    handler.post(() -> {
                        if (exitCode == 0) tvStatus.setText("Test complete!");
                        else tvStatus.setText("Done code=" + exitCode);
                        btnStart.setText("Start Test");
                    });
                    try { if (ctrlSocket != null) ctrlSocket.close(); } catch (Exception ignored) {}
                }
            });
    }

    private void parseLine(String line) {
        // 优先匹配 [SUM] 行（多流时每秒汇总）
        Matcher m = SUM_PATTERN.matcher(line);
        if (!m.find()) {
            m = SINGLE_PATTERN.matcher(line);
            if (!m.find()) return;
        }
        try {
            float t1   = Float.parseFloat(m.group(1));
            float t2   = Float.parseFloat(m.group(2));
            if (t2 - t1 > 1.5f) return; // 跳过末尾汇总行

            double mbps = Double.parseDouble(m.group(3));
            rateWindow.addLast(mbps);
            if (rateWindow.size() > SMOOTH_WINDOW) rateWindow.removeFirst();
            double smooth = 0;
            for (double v : rateWindow) smooth += v;
            smooth /= rateWindow.size();

            float elapsed = (System.currentTimeMillis() - startTime) / 1000f;
            final double fs = smooth;
            handler.post(() -> {
                tvTx.setText(String.format(Locale.US, "TX: %.0f Mbps", fs));
                tvRx.setText("RX: --");
                tvTotal.setText(String.format(Locale.US, "Total: %.0f Mbps", fs));
            });
            chartView.updateLastRate(elapsed, (float) smooth);
        } catch (Exception ignored) {}
    }

    private void stopTest() {
        running = false;
        iperfRunner.stop();
        try { if (ctrlSocket != null) ctrlSocket.close(); } catch (Exception ignored) {}
        btnStart.setText("Start Test");
        tvStatus.setText("Stopped");
    }

    private void updateStatus(String s) { handler.post(() -> tvStatus.setText(s)); }

    @Override
    protected void onDestroy() { super.onDestroy(); stopTest(); }
}
