#!/usr/bin/env python3
"""
WiFi Thermal Test Server
- 控制端口 5555: 握手 + 每秒 push 温度
- 数据端口 5556: 多进程（绕过GIL）双向收发，跑满带宽
"""
import socket, threading, time, csv, os, subprocess, signal, json, sys
from datetime import datetime
from collections import deque
from multiprocessing import Process, Value, Array
import ctypes

CONTROL_PORT   = 5555
DATA_PORT      = 5556
NUM_STREAMS    = 8
BUF_SIZE       = 1024 * 1024   # 1MB per stream
ISTOREOS_IP    = "192.168.10.1"
SAMPLE_INTERVAL = 1
SMOOTH_WINDOW  = 3

# 共享内存计数（跨进程）
shared_tx = None
shared_rx = None

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
        temps=[]
        for line in r.stdout.split('\n'):
            if 'temp1:' in line:
                try: temps.append(float(line.split('+')[1].split('°')[0].split('C')[0].strip()))
                except: pass
        return temps if temps else [0.0,0.0]
    except: return [0.0,0.0]

# ── 子进程：单条 stream 收发 ──────────────────────────
def stream_worker(conn_fd, tx_val, rx_val):
    """在独立进程里跑，彻底绕开 Python GIL"""
    import socket, os
    sock = socket.fromfd(conn_fd, socket.AF_INET, socket.SOCK_STREAM)
    os.close(conn_fd)
    data = os.urandom(BUF_SIZE)
    buf  = bytearray(BUF_SIZE)
    sock.setblocking(False)
    import select
    while True:
        try:
            # 写
            r, w, _ = select.select([sock],[sock],[],0.1)
            if w:
                n = sock.send(data)
                if n > 0: tx_val.value += n
            if r:
                n = sock.recv_into(buf)
                if n == 0: break
                rx_val.value += n
        except: break
    try: sock.close()
    except: pass

# ── 主进程：monitor ────────────────────────────────────
def monitor(output_dir):
    global running, ctrl_sock, log_data
    tx_win = deque(maxlen=SMOOTH_WINDOW)
    rx_win = deque(maxlen=SMOOTH_WINDOW)
    tx_last = rx_last = 0

    while running:
        time.sleep(SAMPLE_INTERVAL)
        if not running: break

        elapsed = time.time() - test_start
        tx_now  = shared_tx.value if shared_tx else 0
        rx_now  = shared_rx.value if shared_rx else 0

        dtx = tx_now - tx_last; tx_last = tx_now
        drx = rx_now - rx_last; rx_last = rx_now

        tx_win.append(dtx * 8 / 1e6)
        rx_win.append(drx * 8 / 1e6)
        tx_mbps = sum(tx_win)/len(tx_win)
        rx_mbps = sum(rx_win)/len(rx_win)
        tot     = tx_mbps + rx_mbps

        temps = get_temp()
        t0 = temps[0]; t1 = temps[1] if len(temps)>1 else temps[0]

        log_data.append({'elapsed':round(elapsed,1),'temp_phy0':t0,'temp_phy1':t1,
                         'tx_mbps':round(tx_mbps,1),'rx_mbps':round(rx_mbps,1),
                         'total_mbps':round(tot,1)})

        print(f"[{elapsed:6.1f}s] {t0}°/{t1}° | TX:{tx_mbps:.0f} RX:{rx_mbps:.0f} Total:{tot:.0f} Mbps")

        try:
            if ctrl_sock:
                ctrl_sock.sendall(f"STAT:{elapsed:.1f}:{t0}:{t1}:{tx_mbps:.1f}:{rx_mbps:.1f}\n".encode())
        except: pass

        if test_dur > 0 and elapsed >= test_dur:
            print("[INFO] Duration reached")
            running = False; break

    save_output(output_dir)

def save_output(output_dir):
    if not log_data: return
    ts = datetime.now().strftime('%Y%m%d_%H%M%S')
    csv_path = os.path.join(output_dir, f'thermal_{ts}.csv')
    with open(csv_path,'w',newline='') as f:
        w = csv.DictWriter(f, fieldnames=log_data[0].keys())
        w.writeheader(); w.writerows(log_data)
    print(f"[OK] CSV: {csv_path}")
    try:
        import matplotlib; matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        times=[d['elapsed']/60 for d in log_data]
        fig,ax1=plt.subplots(figsize=(14,6))
        ax1.set_xlabel('Time (min)'); ax1.set_ylabel('Temp (°C)',color='red')
        ax1.plot(times,[d['temp_phy0'] for d in log_data],'r-',label='phy0',linewidth=1.5)
        ax1.plot(times,[d['temp_phy1'] for d in log_data],'r--',label='phy1',linewidth=1.5,alpha=.7)
        ax1.tick_params(axis='y',labelcolor='red'); ax1.legend(loc='upper left'); ax1.grid(alpha=.3)
        ax2=ax1.twinx(); ax2.set_ylabel('Mbps',color='blue')
        ax2.plot(times,[d['total_mbps'] for d in log_data],'b-',label='Total Mbps',linewidth=1.5)
        ax2.tick_params(axis='y',labelcolor='blue'); ax2.legend(loc='upper right')
        plt.title('WiFi Thermal Test'); plt.tight_layout()
        chart=os.path.join(output_dir,f'thermal_{ts}.png')
        plt.savefig(chart,dpi=150); plt.close()
        print(f"[OK] Chart: {chart}")
    except Exception as e: print(f"[WARN] Chart: {e}")

def main():
    global running, ctrl_sock, log_data, test_start, test_dur, shared_tx, shared_rx

    output_dir = os.path.dirname(os.path.abspath(__file__))

    ctrl_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ctrl_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ctrl_server.bind(('0.0.0.0', CONTROL_PORT))
    ctrl_server.listen(1)

    data_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    data_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    data_server.bind(('0.0.0.0', DATA_PORT))
    data_server.listen(NUM_STREAMS + 4)

    print(f"[INFO] Server ready  ctrl:{CONTROL_PORT}  data:{DATA_PORT}  streams:{NUM_STREAMS}")
    print(f"[INFO] Temp: {ISTOREOS_IP}")

    def sig(s,f):
        global running
        running = False
        try: ctrl_sock.close()
        except: pass
    signal.signal(signal.SIGINT, sig)
    signal.signal(signal.SIGTERM, sig)

    while True:
        running=False; log_data=[]; workers=[]
        shared_tx = Value(ctypes.c_longlong, 0)
        shared_rx = Value(ctypes.c_longlong, 0)

        print("\n[INFO] Waiting for App...")
        try: ctrl_sock, addr = ctrl_server.accept()
        except: break
        print(f"[INFO] App: {addr}")
        ctrl_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        # 握手
        try:
            cmd=b""
            while b"\n" not in cmd: cmd += ctrl_sock.recv(256)
            cmd=cmd.decode().strip()
            if not cmd.startswith("START:"): ctrl_sock.close(); continue
            test_dur = int(cmd.split(":")[1])
            print(f"[INFO] Duration: {test_dur}s, waiting {NUM_STREAMS} data streams...")
            ctrl_sock.sendall(b"OK\n")
        except Exception as e:
            print(f"[ERROR] Handshake: {e}"); ctrl_sock.close(); continue

        # 接受数据连接
        data_server.settimeout(15)
        stream_socks = []
        while len(stream_socks) < NUM_STREAMS:
            try:
                ds, da = data_server.accept()
                ds.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4*1024*1024)
                ds.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4*1024*1024)
                ds.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                stream_socks.append(ds)
                print(f"[INFO] stream {len(stream_socks)}/{NUM_STREAMS}: {da}")
            except socket.timeout:
                print(f"[WARN] only {len(stream_socks)} streams"); break

        if not stream_socks:
            print("[ERROR] No streams"); ctrl_sock.close(); continue

        # 每条 stream 启动独立子进程
        running=True; test_start=time.time()
        for ds in stream_socks:
            fd = ds.fileno()
            p = Process(target=stream_worker,
                        args=(os.dup(fd), shared_tx, shared_rx), daemon=True)
            p.start(); workers.append(p)
            ds.close()   # 父进程关闭，子进程持有dup的fd

        # 启动 monitor
        tm = threading.Thread(target=monitor, args=(output_dir,), daemon=True)
        tm.start()

        # 等待 monitor 结束（duration 到了或 App 断开）
        tm.join()
        running = False

        # 结束子进程
        for p in workers:
            p.terminate(); p.join(timeout=2)

        try: ctrl_sock.sendall(b"END\n")
        except: pass
        try: ctrl_sock.close()
        except: pass
        print("[INFO] Test complete.")

    ctrl_server.close(); data_server.close()

if __name__ == '__main__':
    main()
