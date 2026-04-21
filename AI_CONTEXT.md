# AI Context - WiFi Thermal Test 项目记忆文件

> 此文件供 AI 快速理解项目背景，人类也可阅读。

## 项目目的

测试 **mt7915 无线网卡**在不同散热片方案下的散热效果。
通过让手机持续跑满WiFi带宽，同时采集网卡温度，生成温度+速率随时间变化的双曲线图，量化散热差异。

## 关键节点 IP

| 角色 | IP | 备注 |
|------|----|------|
| 宿主机（server.py所在） | 192.168.63.178 | Debian 13，SSH端口40022，root/z052500Z |
| iStoreOS路由器 | 192.168.10.1 | mt7915网卡在此，SSH无密码 |
| 手机 | DHCP | 连iStoreOS WiFi |

## 数据流

```
手机 --[TCP 5555]--> 宿主机server.py --[SSH每秒]--> iStoreOS sensors
       双向1MB块持续收发               采集temp并统计速率
                                       测试结束生成PNG+CSV
```

## 协议握手

1. 手机发：`START:<秒数>\n`
2. 服务端回：`OK\n`
3. 双方开始持续双向发送 1MB 随机数据块
4. 时间到后服务端停止，生成图表

## 图表内容

- X轴：时间（分钟）
- 左Y轴（红）：mt7915 phy0 / phy1 温度（°C）
- 右Y轴（蓝）：总吞吐速率（MB/s）
- 文件名：`thermal_test_YYYYMMDD_HHMMSS.png`

## 文件位置

- 服务端代码：`/root/server.py`（宿主机）
- 日志：`/root/server.log`
- 结果图表：`/root/thermal_test_*.png`
- APK：项目根目录 `WifiThermalTest.apk`

## Android App

- 包名：`com.wifithermal`
- 主Activity：`MainActivity.java`
- 单Activity，无第三方依赖
- 两个后台线程：sender / receiver，主线程每秒更新UI

## 已知问题 / 注意事项

- 宿主机需能免密SSH到 192.168.10.1，否则温度读取失败（返回0）
- `nohup python3 /root/server.py > /root/server.log 2>&1 &` 后台启动
- 重启：`pkill -f server.py && nohup python3 /root/server.py > /root/server.log 2>&1 &`
- 服务端口 5555，未做systemd服务，手动管理
