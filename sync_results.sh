#!/bin/bash
RESULTS_DIR="/opt/ehangnas/nas/mao/userdir/wifi-thermal-test/results"

# 同步新文件
sshpass -p 'z052500Z' ssh -p 40022 -o StrictHostKeyChecking=no root@192.168.62.167 \
    "find /root -maxdepth 1 \( -name 'thermal*.csv' -o -name 'thermal*.png' \) | sort" 2>/dev/null | \
while read f; do
    fname=$(basename "$f")
    dest="$RESULTS_DIR/$fname"
    if [ ! -f "$dest" ]; then
        sshpass -p 'z052500Z' scp -q -P 40022 -o StrictHostKeyChecking=no \
            "root@192.168.62.167:$f" "$dest"
    fi
done

# 重建 index.html
python3 - << 'PY'
import os
results_dir = "/opt/ehangnas/nas/mao/userdir/wifi-thermal-test/results"
pngs = sorted([f for f in os.listdir(results_dir) if f.endswith('.png')], key=lambda f: os.path.getmtime(os.path.join(results_dir, f)), reverse=True)
cards = ""
for f in pngs:
    csv = f.replace('.png', '.csv')
    ts = f.replace('thermal_test_','').replace('thermal_','').replace('.png','')
    y,mo,d,h,mi,s = ts[0:4],ts[4:6],ts[6:8],ts[9:11],ts[11:13],ts[13:15]
    label = f"{y}-{mo}-{d} {h}:{mi}:{s}"
    cards += f'\n  <div class="card"><img src="{f}" onclick="window.open(this.src)"><div class="title">🕐 {label}</div><div class="links"><a href="{f}" target="_blank">🔍 大图</a><a href="{csv}" download>⬇ CSV</a></div></div>'
html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>WiFi Thermal Test Results</title><style>body{{font-family:sans-serif;background:#0d0d1a;color:#ccc;padding:20px;margin:0}}h1{{color:#4fc3f7;margin-bottom:4px}}#count{{color:#888;margin-bottom:20px}}.grid{{display:flex;flex-wrap:wrap;gap:16px}}.card{{background:#1a1a2e;border-radius:8px;padding:12px;width:340px}}.card img{{width:100%;border-radius:4px;cursor:pointer}}.card img:hover{{opacity:.85}}.card .title{{margin-top:8px;font-size:13px;color:#aaa}}.card .links{{margin-top:6px;display:flex;gap:12px}}.card a{{color:#4fc3f7;font-size:12px;text-decoration:none}}.card a:hover{{text-decoration:underline}}</style></head><body><h1>📊 WiFi Thermal Test Results</h1><p id="count">共 {len(pngs)} 条记录</p><div class="grid">{cards}</div></body></html>"""
with open(os.path.join(results_dir, "index.html"), "w") as f:
    f.write(html)
PY
