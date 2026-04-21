#!/usr/bin/env python3
"""
WiFi Thermal Test Server
- 控制端口 5555: 握手 + 检查iperf3 + 每秒push温度
- iperf3 -s 在 5201 独立运行，App 直接连 iperf3
"""
import socket, threading, time, csv, os, subprocess, signal
from datetime import datetime
from collections import deque

CONTROL_PORT    = 5555
IPERF_PORT      = 5201
ISTOREOS_IP     = "192.168.10.1"
SAMPLE_INTERVAL = 1

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

def is_iperf3_listening():
    """检查 iperf3 是否真正在 5201 端口监听"""
    try:
        with socket.create_connection(("127.0.0.1", IPERF_PORT), timeout=2):
            return True
    except:
        return False

def ensure_iperf3_ready():
    """
    确保 iperf3 在 5201 监听。
    返回 (True, "") 或 (False, "错误原因")
    """
    if is_iperf3_listening():
        print("[INFO] iperf3 already listening on 5201")
        return True, ""

    print("[INFO] iperf3 not listening, starting...")
    try:
        subprocess.Popen(
            ["iperf3", "-s", "-p", str(IPERF_PORT), "--forceflush"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except FileNotFoundError:
        msg = "iperf3 binary not found on server"
        print(f"[ERROR] {msg}")
        return False, msg
    except Exception as e:
        msg = f"iperf3 start exception: {e}"
        print(f"[ERROR] {msg}")
        return False, msg

    # 等待最多 5 秒确认监听
    for i in range(10):
        time.sleep(0.5)
        if is_iperf3_listening():
            print("[INFO] iperf3 started and listening on 5201")
            return True, ""

    msg = "iperf3 started but not listening on 5201 after 5s"
    print(f"[ERROR] {msg}")
    return False, msg

def monitor(output_dir):
    global running, ctrl_sock
    while running:
        time.sleep(SAMPLE_INTERVAL)
        if not running: break

        elapsed = time.time() - test_start
        temps   = get_temp()
        t0 = temps[0]
        t1 = temps[1] if len(temps) > 1 else temps[0]

        log_data.append({'elapsed': round(elapsed,1), 'temp_phy0': t0, 'temp_phy1': t1})
        print(f"[{elapsed:6.1f}s] {t0}°C / {t1}°C")

        try:
            ctrl_sock.sendall(f"STAT:{elapsed:.1f}:{t0}:{t1}\n".encode())
        except:
            print("[INFO] App disconnected")
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
        t0v   = [d['temp_phy0'] for d in log_data]
        t1v   = [d['temp_phy1'] for d in log_data]
        fig, ax = plt.subplots(figsize=(14,6))
        ax.set_xlabel('Time (min)'); ax.set_ylabel('Temp (°C)')
        ax.plot(times, t0v, 'r-', label='phy0', linewidth=1.5)
        ax.plot(times, t1v, 'r--', label='phy1', linewidth=1.5, alpha=.7)
        ax.legend(); ax.grid(alpha=.3)
        plt.title('WiFi Thermal Test'); plt.tight_layout()
        chart = os.path.join(output_dir, f'thermal_{ts}.png')
        plt.savefig(chart, dpi=150); plt.close()
        print(f"[OK] Chart: {chart}")
    except Exception as e: print(f"[WARN] Chart: {e}")

def main():
    global running, ctrl_sock, log_data, test_start, test_dur

    output_dir = os.path.dirname(os.path.abspath(__file__))

    # 启动时先确保 iperf3 跑起来
    ensure_iperf3_ready()

    ctrl_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ctrl_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ctrl_server.bind(('0.0.0.0', CONTROL_PORT))
    ctrl_server.listen(1)
    print(f"[INFO] Control :{CONTROL_PORT}  iperf3 :{IPERF_PORT}  Temp:{ISTOREOS_IP}")

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
        print(f"[INFO] App connected: {addr}")
        ctrl_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        try:
            # 读握手命令
            cmd = b""
            while b"\n" not in cmd: cmd += ctrl_sock.recv(256)
            cmd = cmd.decode().strip()
            if not cmd.startswith("START:"):
                ctrl_sock.sendall(b"ERROR:invalid_command\n")
                ctrl_sock.close(); continue

            test_dur = int(cmd.split(":")[1])
            print(f"[INFO] Duration: {test_dur}s")

            # 检查/启动 iperf3
            ok, err = ensure_iperf3_ready()
            if not ok:
                ctrl_sock.sendall(f"ERROR:iperf3_not_ready:{err}\n".encode())
                ctrl_sock.close(); continue

            # 一切正常，回复 OK
            test_start = time.time()
            ctrl_sock.sendall(b"OK\n")

        except Exception as e:
            print(f"[ERROR] Handshake: {e}")
            try: ctrl_sock.sendall(f"ERROR:handshake_exception:{e}\n".encode())
            except: pass
            ctrl_sock.close(); continue

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
