#!/usr/bin/env python3
"""WiFi Thermal Test Server"""

import socket
import threading
import time
import csv
import os
import sys
import subprocess
import signal
from datetime import datetime

LISTEN_PORT = 5555
BUFFER_SIZE = 1024 * 1024
ISTOREOS_IP = "192.168.10.1"
SAMPLE_INTERVAL = 1

running = False
test_start_time = 0
total_bytes_sent = 0
total_bytes_recv = 0
bytes_sent_last = 0
bytes_recv_last = 0
lock = threading.Lock()
log_data = []
client_socket = None
test_duration = 0


def get_temperature():
    try:
        result = subprocess.run(
            ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=2",
             "-o", "BatchMode=yes", f"root@{ISTOREOS_IP}", "sensors"],
            capture_output=True, text=True, timeout=5)
        temps = []
        for line in result.stdout.split('\n'):
            if 'temp1:' in line:
                val = line.split('+')[1].split('\xc2')[0].split('°')[0].split('C')[0].strip()
                temps.append(float(val))
        return temps if temps else [0, 0]
    except Exception as e:
        print(f"[WARN] Temp read failed: {e}")
        return [0, 0]


def sender_thread(sock):
    global total_bytes_sent, running
    data = os.urandom(BUFFER_SIZE)
    while running:
        try:
            sent = sock.send(data)
            with lock:
                total_bytes_sent += sent
        except:
            break


def receiver_thread(sock):
    global total_bytes_recv, running
    while running:
        try:
            d = sock.recv(BUFFER_SIZE)
            if not d:
                break
            with lock:
                total_bytes_recv += len(d)
        except:
            break


def monitor_thread(output_dir):
    global running, bytes_sent_last, bytes_recv_last
    global total_bytes_sent, total_bytes_recv

    while running:
        time.sleep(SAMPLE_INTERVAL)
        if not running:
            break

        elapsed = time.time() - test_start_time
        with lock:
            s_now = total_bytes_sent
            r_now = total_bytes_recv

        sr = (s_now - bytes_sent_last) / 1024 / 1024
        rr = (r_now - bytes_recv_last) / 1024 / 1024
        bytes_sent_last = s_now
        bytes_recv_last = r_now

        temps = get_temperature()
        tr = sr + rr

        entry = {
            'elapsed': round(elapsed, 1),
            'temp_phy0': temps[0],
            'temp_phy1': temps[1] if len(temps) > 1 else temps[0],
            'send_mbps': round(sr, 2),
            'recv_mbps': round(rr, 2),
            'total_mbps': round(tr, 2),
        }
        log_data.append(entry)
        print(f"[{elapsed:6.1f}s] Temp: {temps[0]}°C / {temps[1] if len(temps)>1 else '-'}°C | "
              f"TX: {sr:.1f} MB/s RX: {rr:.1f} MB/s Total: {tr:.1f} MB/s")

        if test_duration > 0 and elapsed >= test_duration:
            print(f"\n[INFO] Duration {test_duration}s reached.")
            stop_test()
            break

    # Generate outputs
    generate_chart(output_dir)


def generate_chart(output_dir):
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
        t0 = [d['temp_phy0'] for d in log_data]
        t1 = [d['temp_phy1'] for d in log_data]
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
        ax2.set_ylabel('Throughput (MB/s)', color='blue')
        ax2.plot(times, rates, 'b-', label='Throughput', linewidth=1.5, alpha=0.8)
        ax2.tick_params(axis='y', labelcolor='blue')
        ax2.legend(loc='upper right')

        plt.title('WiFi Thermal Test')
        plt.tight_layout()
        chart_path = os.path.join(output_dir, f'thermal_test_{timestamp}.png')
        plt.savefig(chart_path, dpi=150)
        plt.close()
        print(f"[OK] Chart: {chart_path}")
    except ImportError:
        print("[WARN] matplotlib not available, CSV only")


def stop_test():
    global running, client_socket
    running = False
    try:
        client_socket.close()
    except:
        pass


def main():
    global running, test_start_time, client_socket, test_duration
    global total_bytes_sent, total_bytes_recv, bytes_sent_last, bytes_recv_last, log_data

    output_dir = os.path.dirname(os.path.abspath(__file__))

    def sig_handler(sig, frame):
        print("\n[INFO] Stopping...")
        stop_test()

    signal.signal(signal.SIGINT, sig_handler)
    signal.signal(signal.SIGTERM, sig_handler)

    print(f"[INFO] WiFi Thermal Test Server on port {LISTEN_PORT}")
    print(f"[INFO] Temp source: {ISTOREOS_IP}")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', LISTEN_PORT))
    server.listen(1)

    while True:
        running = False
        total_bytes_sent = 0
        total_bytes_recv = 0
        bytes_sent_last = 0
        bytes_recv_last = 0
        log_data = []

        print(f"[INFO] Waiting for client...\n")
        try:
            client_socket, addr = server.accept()
        except:
            break

        print(f"[INFO] Client: {addr}")
        client_socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4*1024*1024)
        client_socket.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4*1024*1024)
        client_socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        try:
            cmd = b""
            while b"\n" not in cmd:
                cmd += client_socket.recv(1024)
            cmd = cmd.decode().strip()
            if cmd.startswith("START:"):
                test_duration = int(cmd.split(":")[1])
                print(f"[INFO] Duration: {test_duration}s")
                client_socket.send(b"OK\n")
            else:
                client_socket.close()
                continue
        except Exception as e:
            print(f"[ERROR] Handshake: {e}")
            client_socket.close()
            continue

        running = True
        test_start_time = time.time()

        ts = threading.Thread(target=sender_thread, args=(client_socket,), daemon=True)
        tr = threading.Thread(target=receiver_thread, args=(client_socket,), daemon=True)
        tm = threading.Thread(target=monitor_thread, args=(output_dir,), daemon=True)
        ts.start()
        tr.start()
        tm.start()
        tm.join()
        running = False
        time.sleep(1)
        try:
            client_socket.close()
        except:
            pass
        print(f"[INFO] Test done.\n")

    server.close()

if __name__ == '__main__':
    main()
