# AI Context - WiFi Thermal Test 项目记忆文件

## 项目目的
测试 mt7915 无线网卡在不同散热片方案下的散热效果。手机持续跑满WiFi带宽，同时采集网卡温度，实时在手机上绘制曲线，测试结束服务端生成 PNG+CSV 归档。

## 关键节点
| 角色 | IP | 备注 |
|------|----|------|
| 宿主机（server.py） | 192.168.63.178 | Debian 13，SSH端口40022，root/z052500Z |
| iStoreOS路由器 | 192.168.10.1 | mt7915网卡，SSH无密码，sensors读温度 |
| 手机 | DHCP | 连iStoreOS WiFi |

## 端口
| 端口 | 用途 |
|------|------|
| 5555 (TCP) | 控制连接：握手 + 服务端每秒推送STAT行 |
| 5556 (TCP) | 数据连接：8条并行流双向收发，跑满带宽 |

## 协议
1. 手机 → 服务端 控制连接(5555)：`START:<秒数>\n`
2. 服务端 → 手机：`OK\n`
3. 手机同时建立8条数据连接(5556)，双向发512KB块
4. 服务端每秒SSH读温度，统计速率，推送：`STAT:<elapsed>:<t0>:<t1>:<tx_mbps>:<rx_mbps>\n`
5. 手机实时绘制温度+速率曲线
6. 测试结束服务端发 `END\n`，生成PNG+CSV

## 单位
- 速率：**Mbps**（bits，不是bytes）= bytes × 8 / 1_000_000

## 图表（手机实时）
- X轴：时间（秒，按总时长自动缩放）
- 左侧标注：温度°C（红色）
- 右侧标注：速率 Mbps（蓝色）
- 曲线：phy0温度(红实线) / phy1温度(橙虚线) / 总速率(蓝实线)

## 文件位置
- 服务端：`/root/server.py`（宿主机）
- 日志：`/root/server.log`
- 结果：`/root/thermal_test_*.png` / `*.csv`
- APK：项目根目录 `WifiThermalTest.apk`

## 重启服务端
```bash
pkill -f server.py && nohup python3 /root/server.py > /root/server.log 2>&1 &
```

## Android App
- 包名：`com.wifithermal`
- MainActivity.java：控制流+数据流管理，UI更新
- ChartView.java：自定义View，实时绘制双Y轴曲线
- minSdk 24 (Android 7.0+)
