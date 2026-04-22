# AI Context - WiFi Thermal Test 项目记忆文件

> 最后更新：2026-04-22

## 项目目的
测试 mt7915 无线网卡在不同散热方案下的散热效果。手机通过 iperf3 持续跑满 WiFi 下行带宽，同时服务端采集网卡温度，实时推送给手机绘制曲线，测试结束服务端生成 PNG+CSV 归档。

## 架构

```
手机 App (Android)
  │
  ├─ 控制连接 TCP 5555 ──→ server.py (宿主机 192.168.10.220)
  │    握手/温度STAT/速率上报
  │
  └─ iperf3 client ────→ iperf3 -s TCP 5201 (宿主机)
       -R -P 8 纯下载，手机收，服务端发
```

## 关键节点
| 角色 | IP | 备注 |
|------|----|------|
| 宿主机（server.py + iperf3） | 192.168.10.220 / SSH 192.168.62.167:40022 | root/z052500Z |
| iStoreOS路由器 | 192.168.10.1 | mt7915网卡，SSH免密，sensors读温度 |
| 手机 | DHCP | 连iStoreOS WiFi，App填写宿主机IP |
| NAS（本机）| 192.168.60.200 | HTTP服务9090，提供APK下载和结果展示 |

## 端口
| 端口 | 用途 |
|------|------|
| 5555 | 控制连接：握手 + 服务端每秒推 STAT + 接收手机上报 RATE |
| 5201 | iperf3 server，手机直连，纯下载 `-R -P 8` |
| 9090 (NAS) | HTTP服务，APK下载 + 测试结果展示 |

## 控制协议
1. 手机 → 服务端：`START:<秒数>\n`
2. 服务端检查 iperf3 是否在 5201 监听，不在则自动启动
3. 服务端 → 手机：`OK\n`（失败回 `ERROR:原因\n`）
4. 手机启动 iperf3 client：`-R -P 8 --forceflush -f m -i 1`，TMPDIR 设为 filesDir
5. 服务端每秒推温度：`STAT:<elapsed>:<t0>:<t1>\n`
6. 手机每秒上报速率：`RATE:<tx>:<rx>\n`（纯下载模式 tx=0）
7. 测试结束服务端发 `END\n`，生成 PNG+CSV

## iperf3 关键问题及解决方案
- **Permission Denied**：iperf3 在 Android 上用 mkstemp() 创建临时文件，硬编码路径 `/data/local/tmp`，App 无权限写。
  **解决**：ProcessBuilder 设置环境变量 `TMPDIR` = `ctx.getFilesDir()`
- **工作目录**：同时设 `pb.directory(ctx.getFilesDir())`

## 温度采集
```python
ssh root@192.168.10.1 'sensors'
# 解析 mt7915_phy0/phy1 的 temp1 字段
```

## 文件位置
- 宿主机服务端：`/root/server.py`，日志：`/root/server.log`
- 测试结果：宿主机 `/root/thermal_YYYYMMDD_HHMMSS.png/.csv`
- NAS结果展示：`/opt/ehangnas/nas/mao/userdir/wifi-thermal-test/results/`
  每分钟自动从宿主机同步（cron任务），访问 http://192.168.60.200:9090/results/
- APK：http://192.168.60.200:9090/WifiThermalTest.apk

## 服务管理
```bash
# 宿主机 server.py（已配置 systemd 开机自启）
systemctl status wifi-thermal-server
systemctl restart wifi-thermal-server

# NAS HTTP服务（已配置 systemd 开机自启）
systemctl status wifi-thermal-http
systemctl restart wifi-thermal-http
```

## Android App
- 包名：`com.wifithermal`
- 关键文件：
  - `MainActivity.java`：控制流管理、iperf3 启动、UI 更新、速率解析
  - `IperfRunner.java`：封装 iperf3 二进制执行，设置 TMPDIR/workDir
  - `ChartView.java`：自定义View，双Y轴实时曲线（温度左轴红色，速率右轴蓝色）
  - `jniLibs/arm64-v8a/libiperf3.so`：iperf3 ARM64 二进制（改名为.so绕过安装限制）
- minSdk 24 (Android 7.0+)，targetSdk 34

## 图表说明（PNG）
- 双Y轴：左温度°C（红色），右速率Mbps（蓝色）
- 曲线：phy0温度(红实线) / phy1温度(红虚线) / TX Mbps(蓝虚线) / RX Mbps(青虚线) / Total(蓝实线)

## 编译方式
```bash
cd /opt/ehangnas/nas/mao/userdir/wifi-thermal-test/android-app
export ANDROID_HOME=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
/root/.gradle-8.7/bin/gradle assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk ../WifiThermalTest.apk
```

## GitHub
- Repo: https://github.com/LionMiao/wifi-thermal-test
- Token: YOUR_GITHUB_TOKEN
