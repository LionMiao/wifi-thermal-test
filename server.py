#!/usr/bin/env python3
"""WiFi Thermal Test Server
Control port 5555: handshake + stats push
Data port    5556: N parallel bidirectional streams
"""

import socket
import threading
import time
import csv
import os
import subprocess
import signal
from datetime import datetime

CONTROL_PORT = 5555
DATA_PORT = 5556
NUM_STREAMS = 8          # expected parallel data connections
BUFFER_SIZE = 512 * 1024 # 512KB per stream
ISTOREOS_IP = "192.168.10.1"
SAMPLE_INTERVAL = 1

# shared state
lock = threading.Lock()
total_bytes_sent = 0
total_bytes_recv = 0
bytes_sent_last = 0
bytes_recv_last = 0
log_data = []
running = False
test_start_time = 0
test_duration = 0
data_sockets = []
ctrl_sock = None


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


def to_mbps(bytes_count):
    return bytes_count * 8 / 1_000_000


def stream_sender(sock):
    global total_bytes_sent, running
    data = os.urandom(BUFFER_SIZE)
    while running:
        try:
            n = sock.send(data)
            with lock:
                total_bytes_sent += n
        except:
            break


def stream_receiver(sock):
    global total_bytes_recv, running
    buf = bytearray(BUFFER_SIZE)
    while running:
        try:
            n = sock.recv_into(buf)
            if not n:
                break
            with lock:
                total_bytes_recv += n
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

        delta_s = s_now - bytes_sent_last
        delta_r = r_now - bytes_recv_last
        bytes_sent_last = s_now
        bytes_recv_last = r_now

        tx_mbps = to_mbps(delta_s)
        rx_mbps = to_mbps(delta_r)
        total_mbps = tx_mbps + rx_mbps

        temps = get_temperature()
        t0 = temps[0]
        t1 = temps[1] if len(temps) > 1 else temps[0]

        entry = {
            'elapsed': round(elapsed, 1),
            'temp_phy0': t0,
            'temp_phy1': t1,
            'tx_mbps': round(tx_mbps, 1),
            'rx_mbps': round(rx_mbps, 1),
            'total_mbps': round(total_mbps, 1),
        }
        log_data.append(entry)
        print(f"[{elapsed:6.1f}s] Temp: {t0}°C/{t1}°C | "
              f"TX: {tx_mbps:.0f} Mbps RX: {rx_mbps:.0f} Mbps Total: {total_mbps:.0f} Mbps")

        # Push stats to client on control socket
        stat_line = f"STAT:{elapsed:.1f}:{t0}:{t1}:{tx_mbps:.1f}:{rx_mbps:.1f}\n"
        try:
            if ctrl_sock:
                ctrl_sock.sendall(stat_line.encode())
        except:
            pass

        if test_duration > 0 and elapsed >= test_duration:
            print(f"\n[INFO] Duration {test_duration}s reached.")
            stop_test()
            break

    generate_output(output_dir)


def generate_output(output_dir):
    if not log_data:
        print("[WARN] No data to save")
        return

    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')

    # CSV
    csv_path = os.path.join(output_dir, f'thermal_test_{timestamp}.csv')
    with open(csv_path, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=log_data[0].keys())
        w.writeheader()
        w.writerows(log_data)
    print(f"[OK] CSV: {csv_path}")

    # Chart
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
        ax2.set_ylabel('Throughput (Mbps)', color='blue')
        ax2.plot(times, rates, 'b-', label='Total (Mbps)', linewidth=1.5, alpha=0.8)
        ax2.tick_params(axis='y', labelcolor='blue')
        ax2.legend(loc='upper right')

        plt.title('WiFi Thermal Test')
        plt.tight_layout()
        chart_path = os.path.join(output_dir, f'thermal_test_{timestamp}.png')
        plt.savefig(chart_path, dpi=150)
        plt.close()
        print(f"[OK] Chart: {chart_path}")
    except Exception as e:
        print(f"[WARN] Chart failed: {e}")


def stop_test():
    global running, data_sockets, ctrl_sock
    running = False
    for s in data_sockets:
        try: s.close()
        except: pass
    try:
        if ctrl_sock: ctrl_sock.send(b"END\n")
    except: pass


def main():
    global running, test_start_time, test_duration, ctrl_sock
    global total_bytes_sent, total_bytes_recv, bytes_sent_last, bytes_recv_last
    global log_data, data_sockets

    output_dir = os.path.dirname(os.path.abspath(__file__))

    def sig_handler(sig, frame):
        print("\n[INFO] Stopping...")
        stop_test()
    signal.signal(signal.SIGINT, sig_handler)
    signal.signal(signal.SIGTERM, sig_handler)

    # Control server
    ctrl_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ctrl_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ctrl_server.bind(('0.0.0.0', CONTROL_PORT))
    ctrl_server.listen(1)

    # Data server
    data_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    data_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    data_server.bind(('0.0.0.0', DATA_PORT))
    data_server.listen(NUM_STREAMS + 2)

    print(f"[INFO] WiFi Thermal Test Server")
    print(f"[INFO] Control: :{CONTROL_PORT}  Data: :{DATA_PORT}  Streams: {NUM_STREAMS}")
    print(f"[INFO] Temp source: {ISTOREOS_IP}")

    while True:
        # Reset state
        running = False
        total_bytes_sent = 0
        total_bytes_recv = 0
        bytes_sent_last = 0
        bytes_recv_last = 0
        log_data = []
        data_sockets = []

        print(f"\n[INFO] Waiting for client on control port...")
        try:
            ctrl_sock, addr = ctrl_server.accept()
        except:
            break

        print(f"[INFO] Client: {addr}")
        ctrl_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        # Handshake
        try:
            cmd = b""
            while b"\n" not in cmd:
                cmd += ctrl_sock.recv(256)
            cmd = cmd.decode().strip()
            if not cmd.startswith("START:"):
                ctrl_sock.close()
                continue
            test_duration = int(cmd.split(":")[1])
            print(f"[INFO] Duration: {test_duration}s, waiting for {NUM_STREAMS} data streams...")
            ctrl_sock.sendall(b"OK\n")
        except Exception as e:
            print(f"[ERROR] Handshake: {e}")
            ctrl_sock.close()
            continue

        # Accept N data connections
        data_server.settimeout(10)
        accepted = 0
        while accepted < NUM_STREAMS:
            try:
                ds, da = data_server.accept()
                ds.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2*1024*1024)
                ds.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 2*1024*1024)
                ds.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                data_sockets.append(ds)
                accepted += 1
                print(f"[INFO] Data stream {accepted}/{NUM_STREAMS}: {da}")
            except socket.timeout:
                print(f"[WARN] Only {accepted} streams connected, proceeding anyway")
                break

        if not data_sockets:
            print("[ERROR] No data streams, aborting")
            ctrl_sock.close()
            continue

        # Start test
        running = True
        test_start_time = time.time()
        threads = []

        for ds in data_sockets:
            t1 = threading.Thread(target=stream_sender, args=(ds,), daemon=True)
            t2 = threading.Thread(target=stream_receiver, args=(ds,), daemon=True)
            t1.start()
            t2.start()
            threads.extend([t1, t2])

        tm = threading.Thread(target=monitor_thread, args=(output_dir,), daemon=True)
        tm.start()
        tm.join()

        running = False
        for s in data_sockets:
            try: s.close()
            except: pass
        try: ctrl_sock.close()
        except: pass
        print("[INFO] Test done.")

    ctrl_server.close()
    data_server.close()


if __name__ == '__main__':
    main()
