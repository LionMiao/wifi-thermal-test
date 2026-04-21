#!/usr/bin/env python3
"""
WiFi Thermal Test Server
- 控制端口 5555: 握手 + 每秒 push 温度给 App
- iperf3 -s 在 5201 独立运行，App 直接连 iperf3（原生速度）
- 测试结束生成 PNG + CSV
"""
import socket, threading, time, csv, os, subprocess, signal
from datetime import datetime
from collections import deque

CONTROL_PORT   = 5555
IPERF_PORT     = 5201
ISTOREOS_IP    = "192.168.10.1"
SAMPLE_INTERVAL = 1
SMOOTH_WINDOW  = 3

ctrl_sock  = None
running    = False
log_data   = []
test_start = 0
test_dur   = 0

def get_temp():
    try:
        r = subprocess.run(
            ["ssh","-o","StrictHostKeyChecking=no","-o","ConnectTimeout=2",
             "-o","BatchMode=yes",f"root@{ISTOREOS_IP}","sensors"],
            capture_output=True, text=True, timeout=5)
        temps = []
        for line in r.stdout.split('\n'):
            if 'temp1:' in line:
                try: temps.append(float(line.split('+')[1].split('°')[0].split('C')[0].strip()))
                except: pass
        return temps if temps else [0.0, 0.0]
    except: return [0.0, 0.0]

def ensure_iperf3_running():
    """确保 iperf3 -s 在后台持续运行"""
    try:
        r = subprocess.run(["pgrep","-x","iperf3"], capture_output=True)
        if r.returncode == 0:
            print("[INFO] iperf3 already running")
            return
        subprocess.Popen(["iperf3","-s","-p",str(IPERF_PORT),"--forceflush"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(0.5)
        print(f"[INFO] iperf3 -s started on :{IPERF_PORT}")
    except Exception as e:
        print(f"[WARN] iperf3 start: {e}")

def monitor(output_dir):
    global running, ctrl_sock
    win = deque(maxlen=SMOOTH_WINDOW)

    while running:
        time.sleep(SAMPLE_INTERVAL)
        if not running: break

        elapsed = time.time() - test_start
        temps   = get_temp()
        t0 = temps[0]
        t1 = temps[1] if len(temps) > 1 else temps[0]

        log_data.append({
            'elapsed':   round(elapsed, 1),
            'temp_phy0': t0,
            'temp_phy1': t1,
        })
        print(f"[{elapsed:6.1f}s] {t0}°C / {t1}°C")

        try:
            if ctrl_sock:
                ctrl_sock.sendall(f"STAT:{elapsed:.1f}:{t0}:{t1}:0:0\n".encode())
        except:
            running = False; break

        if test_dur > 0 and elapsed >= test_dur + 5:
            break

    save_output(output_dir)

def save_output(output_dir):
    if not log_data: return
    ts = datetime.now().strftime('%Y%m%d_%H%M%S')
    csv_path = os.path.join(output_dir, f'thermal_{ts}.csv')
    with open(csv_path, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=log_data[0].keys())
        w.writeheader(); w.writerows(log_data)
    print(f"[OK] CSV: {csv_path}")
    try:
        import matplotlib; matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        times = [d['elapsed']/60 for d in log_data]
        t0    = [d['temp_phy0'] for d in log_data]
        t1    = [d['temp_phy1'] for d in log_data]
        fig, ax = plt.subplots(figsize=(14,6))
        ax.set_xlabel('Time (min)'); ax.set_ylabel('Temp (°C)')
        ax.plot(times, t0, 'r-', label='phy0', linewidth=1.5)
        ax.plot(times, t1, 'r--', label='phy1', linewidth=1.5, alpha=.7)
        ax.legend(); ax.grid(alpha=.3)
        plt.title('WiFi Thermal Test'); plt.tight_layout()
        chart = os.path.join(output_dir, f'thermal_{ts}.png')
        plt.savefig(chart, dpi=150); plt.close()
        print(f"[OK] Chart: {chart}")
    except Exception as e: print(f"[WARN] Chart: {e}")

def main():
    global running, ctrl_sock, log_data, test_start, test_dur

    output_dir = os.path.dirname(os.path.abspath(__file__))
    ensure_iperf3_running()

    ctrl_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ctrl_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ctrl_server.bind(('0.0.0.0', CONTROL_PORT))
    ctrl_server.listen(1)
    print(f"[INFO] Control port :{CONTROL_PORT}  iperf3 port :{IPERF_PORT}")
    print(f"[INFO] Temp: {ISTOREOS_IP}")

    def sig(s,f):
        global running; running = False
        try: ctrl_sock.close()
        except: pass
    signal.signal(signal.SIGINT, sig)
    signal.signal(signal.SIGTERM, sig)

    while True:
        running = False; log_data = []
        print("\n[INFO] Waiting for App...")
        try: ctrl_sock, addr = ctrl_server.accept()
        except: break
        print(f"[INFO] App: {addr}")
        ctrl_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        try:
            cmd = b""
            while b"\n" not in cmd: cmd += ctrl_sock.recv(256)
            cmd = cmd.decode().strip()
            if not cmd.startswith("START:"):
                ctrl_sock.close(); continue
            test_dur = int(cmd.split(":")[1])
            test_start = time.time()
            print(f"[INFO] Duration: {test_dur}s")
            ctrl_sock.sendall(b"OK\n")
        except Exception as e:
            print(f"[ERROR] Handshake: {e}"); ctrl_sock.close(); continue

        # 确保 iperf3 还在跑
        ensure_iperf3_running()
        running = True

        tm = threading.Thread(target=monitor, args=(output_dir,), daemon=True)
        tm.start(); tm.join()

        running = False
        try: ctrl_sock.sendall(b"END\n")
        except: pass
        try: ctrl_sock.close()
        except: pass
        print("[INFO] Done.")

    ctrl_server.close()

if __name__ == '__main__':
    main()
