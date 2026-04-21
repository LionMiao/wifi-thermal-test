#!/usr/bin/env python3
"""
WiFi Thermal Test Server
- 启动 iperf3 -s 处理实际数据传输（原生速度）
- 控制端口 5555：接收 App 连接，每秒 push 温度数据
- 测试结束生成 PNG + CSV（速率从 iperf3 JSON 解析）
"""

import socket
import threading
import time
import csv
import os
import subprocess
import signal
import json
from datetime import datetime
from collections import deque

CONTROL_PORT  = 5555
IPERF_PORT    = 5201
ISTOREOS_IP   = "192.168.10.1"
SAMPLE_INTERVAL = 1
SMOOTH_WINDOW   = 3

running       = False
test_start    = 0
test_duration = 0
ctrl_sock     = None
iperf_proc    = None
log_data      = []
lock          = threading.Lock()


def get_temperature():
    try:
        r = subprocess.run(
            ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=2",
             "-o", "BatchMode=yes", f"root@{ISTOREOS_IP}", "sensors"],
            capture_output=True, text=True, timeout=5)
        temps = []
        for line in r.stdout.split('\n'):
            if 'temp1:' in line:
                try:
                    val = line.split('+')[1].split('°')[0].split('C')[0].strip()
                    temps.append(float(val))
                except:
                    pass
        return temps if temps else [0.0, 0.0]
    except Exception as e:
        print(f"[WARN] Temp: {e}")
        return [0.0, 0.0]


def start_iperf_server():
    """启动 iperf3 server，测试结束后收集 JSON 结果"""
    global iperf_proc
    try:
        iperf_proc = subprocess.Popen(
            ["iperf3", "-s", "-p", str(IPERF_PORT), "-J", "--forceflush", "-1"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        print(f"[INFO] iperf3 server started, pid={iperf_proc.pid}")
    except Exception as e:
        print(f"[ERROR] iperf3 start failed: {e}")
        iperf_proc = None


def monitor_thread():
    """每秒读温度，推送给 App，记录日志"""
    global running, ctrl_sock

    temp_window = deque(maxlen=SMOOTH_WINDOW)

    while running:
        time.sleep(SAMPLE_INTERVAL)
        if not running:
            break

        elapsed = time.time() - test_start
        temps   = get_temperature()
        t0      = temps[0]
        t1      = temps[1] if len(temps) > 1 else temps[0]
        temp_window.append((t0, t1))

        with lock:
            log_data.append({
                'elapsed':   round(elapsed, 1),
                'temp_phy0': t0,
                'temp_phy1': t1,
                'total_mbps': 0,  # filled later from iperf3 JSON
            })

        print(f"[{elapsed:6.1f}s] Temp: {t0}°C / {t1}°C")

        stat_line = f"STAT:{elapsed:.1f}:{t0}:{t1}:0:0\n"
        try:
            if ctrl_sock:
                ctrl_sock.sendall(stat_line.encode())
        except:
            pass

        if test_duration > 0 and elapsed >= test_duration + 5:
            print("[INFO] Monitor done")
            break


def collect_iperf_result():
    """等待 iperf3 进程退出，解析 JSON 结果填充速率"""
    global iperf_proc
    if not iperf_proc:
        return

    try:
        stdout, _ = iperf_proc.communicate(timeout=30)
        data = json.loads(stdout.decode())
        intervals = data.get("intervals", [])
        for i, iv in enumerate(intervals):
            mbps = iv["sum"]["bits_per_second"] / 1_000_000
            # Match to log_data by elapsed time
            if i < len(log_data):
                log_data[i]['total_mbps'] = round(mbps, 1)
        print(f"[INFO] iperf3 result parsed, {len(intervals)} intervals")
    except Exception as e:
        print(f"[WARN] iperf3 result parse: {e}")


def generate_output(output_dir):
    if not log_data:
        print("[WARN] No data")
        return

    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')

    csv_path = os.path.join(output_dir, f'thermal_test_{timestamp}.csv')
    with open(csv_path, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=log_data[0].keys())
        w.writeheader()
        w.writerows(log_data)
    print(f"[OK] CSV: {csv_path}")

    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt

        times = [d['elapsed'] / 60 for d in log_data]
        t0    = [d['temp_phy0'] for d in log_data]
        t1    = [d['temp_phy1'] for d in log_data]
        rates = [d['total_mbps'] for d in log_data]

        fig, ax1 = plt.subplots(figsize=(14, 6))
        ax1.set_xlabel('Time (min)')
        ax1.set_ylabel('Temperature (°C)', color='red')
        ax1.plot(times, t0, 'r-', label='phy0', linewidth=1.5)
        ax1.plot(times, t1, 'r--', label='phy1', linewidth=1.5, alpha=0.7)
        ax1.tick_params(axis='y', labelcolor='red')
        ax1.legend(loc='upper left')
        ax1.grid(True, alpha=0.3)

        ax2 = ax1.twinx()
        ax2.set_ylabel('Throughput (Mbps)', color='blue')
        ax2.plot(times, rates, 'b-', label='Total (Mbps)', linewidth=1.5)
        ax2.tick_params(axis='y', labelcolor='blue')
        ax2.legend(loc='upper right')

        plt.title('WiFi Thermal Test')
        plt.tight_layout()
        chart_path = os.path.join(output_dir, f'thermal_test_{timestamp}.png')
        plt.savefig(chart_path, dpi=150)
        plt.close()
        print(f"[OK] Chart: {chart_path}")
    except Exception as e:
        print(f"[WARN] Chart: {e}")


def main():
    global running, test_start, test_duration, ctrl_sock, log_data, iperf_proc

    output_dir = os.path.dirname(os.path.abspath(__file__))

    def sig_handler(sig, frame):
        print("\n[INFO] Stopping...")
        global running
        running = False
        if iperf_proc:
            iperf_proc.terminate()
        if ctrl_sock:
            try: ctrl_sock.close()
            except: pass

    signal.signal(signal.SIGINT, sig_handler)
    signal.signal(signal.SIGTERM, sig_handler)

    ctrl_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ctrl_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ctrl_server.bind(('0.0.0.0', CONTROL_PORT))
    ctrl_server.listen(1)

    print(f"[INFO] WiFi Thermal Test Server")
    print(f"[INFO] Control port: {CONTROL_PORT}")
    print(f"[INFO] iperf3 port:  {IPERF_PORT}")
    print(f"[INFO] Temp source:  {ISTOREOS_IP}")

    while True:
        running       = False
        log_data      = []
        iperf_proc    = None

        print(f"\n[INFO] Waiting for App connection on :{CONTROL_PORT} ...")
        try:
            ctrl_sock, addr = ctrl_server.accept()
        except:
            break

        print(f"[INFO] App connected: {addr}")
        ctrl_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        # Handshake
        try:
            cmd = b""
            while b"\n" not in cmd:
                cmd += ctrl_sock.recv(256)
            cmd = cmd.decode().strip()
            if cmd.startswith("START:"):
                test_duration = int(cmd.split(":")[1])
                print(f"[INFO] Duration: {test_duration}s")
                ctrl_sock.sendall(b"OK\n")
            else:
                ctrl_sock.close()
                continue
        except Exception as e:
            print(f"[ERROR] Handshake: {e}")
            ctrl_sock.close()
            continue

        # Start iperf3 server (accepts 1 test then exits)
        start_iperf_server()

        # Start test
        running    = True
        test_start = time.time()

        tm = threading.Thread(target=monitor_thread, daemon=True)
        tm.start()

        # Wait for iperf3 to finish
        if iperf_proc:
            iperf_proc.wait()
            print("[INFO] iperf3 done")
            collect_iperf_result()

        running = False
        tm.join(timeout=5)

        try: ctrl_sock.sendall(b"END\n")
        except: pass
        try: ctrl_sock.close()
        except: pass

        generate_output(output_dir)
        print("[INFO] Test complete.")

    ctrl_server.close()


if __name__ == '__main__':
    main()
