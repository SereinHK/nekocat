# 喵聊 NekoChat

[![Build](https://github.com/SereinHK/nekocat/actions/workflows/build.yml/badge.svg)](https://github.com/SereinHK/nekocat/actions/workflows/build.yml)

完全离线的 Android 局域网聊天工具。不依赖 Wi-Fi、流量或任何服务器，多台设备通过蓝牙或局域网自组网互发消息 —— 没有账号、没有后端、没有云端。

- **三种传输方式**：蓝牙经典 (RFCOMM)、低功耗蓝牙 (BLE GATT)、WiFi 局域网 (TCP)，共用同一套帧协议与组网逻辑
- **多点组网**：3 台及以上同时群聊，消息自动中继扩散到全网并幂等去重
- **一对一私聊**：带锁图标与独立配色，与群发明确区分
- **扫码连接**：WiFi 模式下扫二维码即可连上，不用手抄 IP
- **完全离线**：无账号、无后端、无遥测；整个应用没有任何一行代码会发起外部请求

## 快速开始

要求 **JDK 17~21** 与 **Android SDK Platform 37**（Miuix 0.9.3 要求 `compileSdk >= 37`）。
`local.properties` 指向本机 SDK 路径，换机器请自行修改。

```powershell
.\gradlew.bat :app:assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

装到两台以上设备后，**两端必须选同一种传输方式**，然后：

**蓝牙经典 (RFCOMM)** —— 最稳定
1. 先在系统「设置 → 蓝牙」里互相配对
2. 两端都进入 App，点右上角 **启动**
3. 在其中一台的「设备」页 → 已配对设备 → 点对方 **连接**

**低功耗蓝牙 (BLE)** —— 免配对
1. 两端都打开蓝牙（不用配对），传输方式选 BLE
2. 两端都点 **启动**，会自动互相发现并连接

**WiFi 局域网 (TCP)** —— 带宽高、距离远
1. 两端连到同一个路由器/热点，传输方式选 WiFi
2. 两端都点 **启动**
3. 主机在「设备」页点 **显示二维码**，对端点 **扫对方的二维码**（也可手动填 IP）

三台及以上：全部启动即可，RFCOMM 下由一台作服务端、其余连它，BLE/WiFi 可任意两两直连。

连不上时先看 App 内「设置」页最下方的**当前状态**，它会实时显示关键信息。
BLE 的每一步也都有日志（tag `NekoChatBle`），配合仓库里的 `watch-ble-log.bat` 双击即可抓取。

## 发布签名（可选）

release 包默认未签名，密钥不进仓库。要出可分发的 release 包，先生成密钥并在仓库根目录建 `keystore.properties`（已在 `.gitignore` 里）：

```powershell
keytool -genkeypair -v -keystore nekochat.jks -alias nekochat -keyalg RSA -keysize 2048 -validity 10000
```

```properties
storeFile=nekochat.jks
storePassword=你的密码
keyAlias=nekochat
keyPassword=你的密码
```

之后 `.\gradlew.bat :app:assembleRelease` 产出已签名的 `app-release.apk`；没有这个文件时构建照常成功，产物为 `app-release-unsigned.apk`。

许可见 [`LICENSE`](LICENSE)（MIT）。
