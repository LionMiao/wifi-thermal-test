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
    private OutputStream ctrlOut;
    private long startTime;
    private int durationSec;

    private final ArrayDeque<Double> txWindow = new ArrayDeque<>();
    private final ArrayDeque<Double> rxWindow = new ArrayDeque<>();

    // --bidir 模式：[TX-C] 是上传，[RX-C] 是下载
    private static final Pattern BIDIR_TX = Pattern.compile(
        "\\[TX-C\\].*?(\\d+\\.\\d+)-(\\d+\\.\\d+)\\s+sec.*?(\\d+(?:\\.\\d+)?)\\s+Mbits/sec"
    );
    private static final Pattern BIDIR_RX = Pattern.compile(
        "\\[RX-C\\].*?(\\d+\\.\\d+)-(\\d+\\.\\d+)\\s+sec.*?(\\d+(?:\\.\\d+)?)\\s+Mbits/sec"
    );

    private double lastTx = 0, lastRx = 0;

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
        tvStatus.setText(iperfRunner.isAvailable() ? "Ready" : "iperf3 missing: " + iperfRunner.getBinaryPath());
        btnStart.setOnClickListener(v -> { if (!running) startTest(); else stopTest(); });
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void startTest() {
        if (!iperfRunner.isAvailable()) { toast("iperf3 二进制不存在，请重装 App"); return; }

        String ip   = etIp.getText().toString().trim();
        durationSec = Integer.parseInt(etDuration.getText().toString().trim()) * 60;

        running = true;
        txWindow.clear(); rxWindow.clear();
        lastTx = 0; lastRx = 0;
        chartView.clear();
        chartView.setDuration(durationSec);
        startTime = System.currentTimeMillis();
        btnStart.setText("Stop");
        updateStatus("连接控制端口...");

        new Thread(() -> {
            // Step 1: 连控制端口
            try {
                ctrlSocket = new Socket();
                ctrlSocket.connect(new java.net.InetSocketAddress(ip, CONTROL_PORT), 5000);
                ctrlSocket.setTcpNoDelay(true);
                ctrlOut = ctrlSocket.getOutputStream();
            } catch (ConnectException e) {
                toast("❌ 无法连接控制端口 " + ip + ":" + CONTROL_PORT); finish(false); return;
            } catch (SocketTimeoutException e) {
                toast("❌ 控制端口连接超时"); finish(false); return;
            } catch (Exception e) {
                toast("❌ 控制连接异常: " + e.getMessage()); finish(false); return;
            }

            // Step 2: 握手
            try {
                updateStatus("握手中...");
                BufferedReader in = new BufferedReader(new InputStreamReader(ctrlSocket.getInputStream()));
                ctrlOut.write(("START:" + durationSec + "\n").getBytes());
                ctrlOut.flush();

                ctrlSocket.setSoTimeout(10000);
                String resp = in.readLine();
                ctrlSocket.setSoTimeout(0);

                if (resp == null) { toast("❌ 握手无响应"); finish(false); return; }
                if (resp.startsWith("ERROR:")) {
                    String r = resp.substring(6);
                    toast("❌ " + (r.startsWith("iperf3_not_ready") ? "服务端 iperf3 未就绪: " + r.replace("iperf3_not_ready:","") : "服务端错误: " + r));
                    finish(false); return;
                }
                if (!"OK".equals(resp)) { toast("❌ 握手失败: " + resp); finish(false); return; }

                updateStatus("握手成功，启动测速...");
                toast("✅ 连接成功，开始测速");

                // 时间更新
                new Thread(() -> {
                    while (running) {
                        try { Thread.sleep(1000); } catch (Exception e) { break; }
                        long el = (System.currentTimeMillis() - startTime) / 1000;
                        handler.post(() -> tvTime.setText(String.format(Locale.US, "%02d:%02d", el/60, el%60)));
                    }
                }, "time-updater").start();

                // Step 3: 启动 iperf3
                iperfRunner.runClient(ip, IPERF_PORT, durationSec, PARALLEL,
                    new IperfRunner.LineCallback() {
                        @Override public void onLine(String line) {
                            Log.d(TAG, "iperf3> " + line);
                            if (line.startsWith("EXEC_ERR:") || (line.toLowerCase().contains("error") && !line.contains("sec"))) {
                                toast("iperf3: " + line);
                            }
                            parseLine(line);
                        }
                        @Override public void onDone(int exitCode) {
                            running = false;
                            if (exitCode == 0) { toast("✅ 测试完成"); updateStatus("测试完成"); }
                            else { toast("❌ iperf3 退出 code=" + exitCode); updateStatus("iperf3 失败 code=" + exitCode); }
                            try { ctrlSocket.close(); } catch (Exception ignored) {}
                            handler.post(() -> btnStart.setText("Start Test"));
                        }
                    });

                // Step 4: 持续读温度 STAT
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

    private static final Pattern INTERVAL_PAT = Pattern.compile(
        "\\[.*?\\]\\s+(\\d+\\.\\d+)-(\\d+\\.\\d+)\\s+sec.*?(\\d+(?:\\.\\d+)?)\\s+Mbits/sec"
    );

    private void parseLine(String line) {
        // -R 纯下载模式：-P>1 只取 [SUM] 行，速率显示为 RX（手机是接收方）
        if (PARALLEL > 1 && !line.contains("[SUM]")) return;
        if (PARALLEL == 1 && !line.matches("\\[\\s*\\d+\\].*")) return;

        Matcher m = INTERVAL_PAT.matcher(line);
        if (!m.find()) return;
        try {
            float t1 = Float.parseFloat(m.group(1));
            float t2 = Float.parseFloat(m.group(2));
            if (t2 - t1 > 1.5f) return; // 跳过末尾汇总行

            double mbps = Double.parseDouble(m.group(3));
            rxWindow.addLast(mbps);
            if (rxWindow.size() > SMOOTH_WINDOW) rxWindow.removeFirst();
            double s = 0; for (double v : rxWindow) s += v;
            lastRx = s / rxWindow.size();

            float elapsed = (System.currentTimeMillis() - startTime) / 1000f;
            final double fRx = lastRx;
            handler.post(() -> {
                updateStatus("Running...");
                tvTx.setText("TX: -- Mbps");
                tvRx.setText(String.format(Locale.US, "RX: %.0f Mbps", fRx));
                tvTotal.setText(String.format(Locale.US, "Total: %.0f Mbps", fRx));
            });
            chartView.updateLastRate(elapsed, (float) lastRx);

            // 上报速率给服务端（纯下载，TX=0）
            sendRate(0, lastRx);

        } catch (Exception ignored) {}
    }

    private void sendRate(double tx, double rx) {
        if (ctrlOut == null) return;
        new Thread(() -> {
            try {
                ctrlOut.write(String.format(Locale.US, "RATE:%.1f:%.1f\n", tx, rx).getBytes());
                ctrlOut.flush();
            } catch (Exception ignored) {}
        }).start();
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
