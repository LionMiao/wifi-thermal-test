# WiFi Thermal Test

用于测试无线网卡（mt7915）在持续高负载下的散热效果。通过比较不同散热片方案下的温度曲线与吞吐速率曲线，量化散热效果。

## 项目架构

```
手机(Android App) <--TCP 5555--> 宿主机(server.py) <--SSH--> iStoreOS路由器(sensors读温度)
     WiFi连接                      有线连接                    192.168.10.1
```

- **手机**：连接 iStoreOS 的 WiFi，与宿主机持续双向收发数据，跑满带宽
- **宿主机(server.py)**：TCP服务端，统计吞吐速率，每秒SSH读取路由器温度，测试结束生成图表
- **iStoreOS**：mt7915 无线网卡所在路由器，`sensors` 命令读温度

## 目录结构

```
wifi-thermal-test/
├── server.py              # 电脑端服务（Python）
├── android-app/           # Android App 项目（Kotlin/Java）
│   └── app/src/main/java/com/wifithermal/MainActivity.java
├── WifiThermalTest.apk    # 编译好的 APK（可直接安装）
└── README.md
```

## 服务端部署（宿主机 Debian）

```bash
# 安装依赖
pip3 install matplotlib

# 确保能免密SSH到iStoreOS读温度
ssh-keygen -t ed25519
ssh-copy-id root@192.168.10.1

# 后台启动服务（监听 5555 端口）
nohup python3 /root/server.py > /root/server.log 2>&1 &

# 查看日志
tail -f /root/server.log

# 重启服务
pkill -f server.py && nohup python3 /root/server.py > /root/server.log 2>&1 &
```

## Android App 使用

1. 安装 `WifiThermalTest.apk` 到手机
2. 手机连接 iStoreOS 的 WiFi
3. 打开 App，填写：
   - **Server IP**：宿主机在路由器网段的 IP
   - **Port**：`5555`
   - **Duration**：测试时长（分钟）
4. 点击 **Start Test**，开始持续双向收发数据
5. 测试结束后服务端自动生成图表

## 测试结果

测试完成后，在 `server.py` 所在目录生成：
- `thermal_test_时间戳.png` — 双Y轴图表（左：温度°C，右：吞吐MB/s，X轴：时间分钟）
- `thermal_test_时间戳.csv` — 原始采样数据

## 温度数据来源

iStoreOS 上执行 `sensors`，读取 mt7915 无线网卡温度：
```
mt7915_phy0-pci-0680 → temp1
mt7915_phy1-pci-0680 → temp1
```

## 配置说明（server.py）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `LISTEN_PORT` | `5555` | TCP监听端口 |
| `BUFFER_SIZE` | `1MB` | 每次收发的数据块大小 |
| `ISTOREOS_IP` | `192.168.10.1` | iStoreOS SSH地址 |
| `SAMPLE_INTERVAL` | `1s` | 温度+速率采样间隔 |

## 编译 Android App

```bash
export ANDROID_HOME=/opt/android-sdk
gradle assembleDebug
# APK输出：app/build/outputs/apk/debug/app-debug.apk
```

## 环境依赖

- 宿主机：Python 3.x、matplotlib、paramiko（可选）
- Android：minSdk 24（Android 7.0+）
- 路由器：iStoreOS，mt7915网卡，`sensors` 命令可用
