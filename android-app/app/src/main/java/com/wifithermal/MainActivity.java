package com.wifithermal;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

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

    private static final Pattern INTERVAL_PAT = Pattern.compile(
        "\\[.*?\\]\\s+(\\d+\\.\\d+)-(\\d+\\.\\d+)\\s+sec.*?(\\d+(?:\\.\\d+)?)\\s+Mbits/sec"
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
        if (iperfRunner.isAvailable()) {
            tvStatus.setText("Ready");
        } else {
            tvStatus.setText("iperf3 missing: " + iperfRunner.getBinaryPath());
        }

        btnStart.setOnClickListener(v -> { if (!running) startTest(); else stopTest(); });
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void startTest() {
        if (!iperfRunner.isAvailable()) {
            toast("iperf3 二进制不存在，请重装 App");
            return;
        }

        String ip   = etIp.getText().toString().trim();
        durationSec = Integer.parseInt(etDuration.getText().toString().trim()) * 60;

        running = true;
        rateWindow.clear();
        chartView.clear();
        chartView.setDuration(durationSec);
        startTime = System.currentTimeMillis();
        btnStart.setText("Stop");
        updateStatus("连接控制端口...");

        new Thread(() -> {
            // ── Step 1: 连控制端口 5555 ──
            try {
                ctrlSocket = new Socket();
                ctrlSocket.connect(new java.net.InetSocketAddress(ip, CONTROL_PORT), 5000);
                ctrlSocket.setTcpNoDelay(true);
            } catch (ConnectException e) {
                toast("❌ 无法连接控制端口 " + ip + ":" + CONTROL_PORT + "（服务未运行？）");
                finish(false); return;
            } catch (SocketTimeoutException e) {
                toast("❌ 连接控制端口超时（" + ip + ":" + CONTROL_PORT + "）");
                finish(false); return;
            } catch (Exception e) {
                toast("❌ 控制连接异常: " + e.getMessage());
                finish(false); return;
            }

            // ── Step 2: 握手 ──
            try {
                updateStatus("握手中...");
                OutputStream out = ctrlSocket.getOutputStream();
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(ctrlSocket.getInputStream()));

                out.write(("START:" + durationSec + "\n").getBytes());
                out.flush();

                ctrlSocket.setSoTimeout(10000); // 等服务端检查iperf3最多10s
                String resp = in.readLine();
                ctrlSocket.setSoTimeout(0);

                if (resp == null) {
                    toast("❌ 握手无响应（服务端可能崩溃）");
                    finish(false); return;
                }
                if (resp.startsWith("ERROR:")) {
                    String reason = resp.substring(6);
                    if (reason.startsWith("iperf3_not_ready")) {
                        toast("❌ 服务端 iperf3 未就绪: " + reason.replace("iperf3_not_ready:", ""));
                    } else {
                        toast("❌ 服务端错误: " + reason);
                    }
                    finish(false); return;
                }
                if (!"OK".equals(resp)) {
                    toast("❌ 握手失败，服务端回复: " + resp);
                    finish(false); return;
                }

                // ── Step 3: 握手成功，启动 iperf3 ──
                updateStatus("握手成功，启动测速...");
                toast("✅ 连接成功，开始测速");

                // 时间更新线程
                new Thread(() -> {
                    while (running) {
                        try { Thread.sleep(1000); } catch (Exception e) { break; }
                        long el = (System.currentTimeMillis() - startTime) / 1000;
                        final String t = String.format(Locale.US, "%02d:%02d", el/60, el%60);
                        handler.post(() -> tvTime.setText(t));
                    }
                }, "time-updater").start();

                // 启动 iperf3
                iperfRunner.runClient(ip, IPERF_PORT, durationSec, PARALLEL,
                    new IperfRunner.LineCallback() {
                        @Override
                        public void onLine(String line) {
                            Log.d(TAG, "iperf3> " + line);
                            // 出错行直接 toast 提示
                            if (line.startsWith("EXEC_ERR:") || line.toLowerCase().contains("error")) {
                                toast("iperf3: " + line);
                            }
                            parseLine(line);
                        }
                        @Override
                        public void onDone(int exitCode) {
                            running = false;
                            if (exitCode == 0) {
                                toast("✅ 测试完成");
                                updateStatus("测试完成");
                            } else {
                                toast("❌ iperf3 退出 code=" + exitCode
                                    + "（检查 " + ip + ":" + IPERF_PORT + " 是否可达）");
                                updateStatus("iperf3 失败 code=" + exitCode);
                            }
                            try { ctrlSocket.close(); } catch (Exception ignored) {}
                            handler.post(() -> btnStart.setText("Start Test"));
                        }
                    });

                // ── Step 4: 持续读温度 STAT ──
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.startsWith("END")) break;
                    if (!line.startsWith("STAT:")) continue;
                    try {
                        String[] p = line.split(":");
                        float t0 = Float.parseFloat(p[2]);
                        float t1 = Float.parseFloat(p[3]);
                        float el = Float.parseFloat(p[1]);
                        handler.post(() -> {
                            tvTemp0.setText(String.format(Locale.US, "phy0: %.1f°C", t0));
                            tvTemp1.setText(String.format(Locale.US, "phy1: %.1f°C", t1));
                        });
                        chartView.updateLastTemp(el, t0, t1);
                    } catch (Exception ignored) {}
                }

            } catch (Exception e) {
                toast("❌ 通信异常: " + e.getMessage());
                finish(false);
            }
        }, "ctrl-thread").start();
    }

    private void parseLine(String line) {
        if (PARALLEL > 1 && !line.contains("[SUM]")) return;
        if (PARALLEL == 1 && !line.matches("\\[\\s*\\d+\\].*")) return;

        Matcher m = INTERVAL_PAT.matcher(line);
        if (!m.find()) return;
        try {
            float t2 = Float.parseFloat(m.group(2));
            float t1 = Float.parseFloat(m.group(1));
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
                tvStatus.setText("Running...");
                tvTx.setText(String.format(Locale.US, "TX: %.0f Mbps", fs));
                tvTotal.setText(String.format(Locale.US, "Total: %.0f Mbps", fs));
            });
            chartView.updateLastRate(elapsed, (float) smooth);
        } catch (Exception ignored) {}
    }

    private void finish(boolean success) {
        running = false;
        try { if (ctrlSocket != null) ctrlSocket.close(); } catch (Exception ignored) {}
        iperfRunner.stop();
        handler.post(() -> {
            btnStart.setText("Start Test");
            updateStatus(success ? "测试完成" : "已停止");
        });
    }

    private void stopTest() {
        toast("已手动停止测试");
        finish(false);
        tvStatus.setText("Stopped");
    }

    private void updateStatus(String s) { handler.post(() -> tvStatus.setText(s)); }

    @Override
    protected void onDestroy() { super.onDestroy(); finish(false); }
}
