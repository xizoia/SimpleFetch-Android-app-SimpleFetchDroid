# SimpleFetchDroid

将 [SimpleFetch](https://github.com/B4QAQ/SimpleFetch-AstroBoxV2-Plugins)（AstroBox V2 插件）移植为**独立安卓 APK**：不依赖 AstroBox 宿主，独立实现小米手环/手表的 BLE 连接、V5 协议栈认证、快应用互联与 SF HTTP 代理，让不支持 `@system.fetch` 的快应用通过手机网络联网。

- 当前版本：**v1.5.10**（versionCode 17）
- 应用开源仓库：<https://github.com/xizoia/SimpleFetch-Android-app-SimpleFetchDroid>
- 协议文档：<https://docs.b4qaq.cn/docs/simplefetch/>

---

## 一、使用的开源项目

本项目**运行时零第三方依赖**——所有协议（protobuf 编解码、V2 帧、V5 认证、AES-CCM/CTR、HMAC/kdf 等）均为**纯 Java 手写实现**，仅依赖 Android 平台框架（AOSP）。下列开源项目是**设计思路与协议实现的参考来源**（移植 / 对拍验证），并非编译期或运行期依赖：

| 开源项目 | 在本项目中的用途 | 许可证 | 地址 |
|---|---|---|---|
| **SimpleFetch**（B4QAQ） | SF 协议（握手/心跳/请求响应/SSE/下载）的设计来源，也是本应用桥接逻辑的直接移植对象（原 Rust 插件） | — | <https://github.com/B4QAQ/SimpleFetch-AstroBoxV2-Plugins> |
| **AstroBox V2 / AstroBox-NG** | V5 协议核心参考：`auth.rs`（AuthAppVerify / AuthDeviceVerify / kdf_miwear）、`thirdparty_app.rs`（快应用上下线 `basic_info` / `SyncPhoneAppStatus`） | — | <https://github.com/AstroBox-NG> |
| **Gadgetbridge** | BLE 协议栈与认证实现参考：`XiaomiBleProtocolV2`（Service `0xFE95` / 特征 `0x5e`·`0x5f`）、`XiaomiSppPacketV2`（A5A5 帧 + CRC16/ARC）、`XiaomiAuthService`、`computeAuthStep3Hmac` 等 | AGPLv3 | <https://github.com/Freeyourgadget/Gadgetbridge> |
| **Android Open Source Project (AOSP)** | Android 平台基础：蓝牙/BLE（`BluetoothDevice`/`BluetoothGatt`）、通知与前台服务、UI 组件、JSON（`org.json`） | Apache-2.0 | <https://source.android.com> |

> **合规提示**：Gadgetbridge 为 AGPLv3。本项目协议部分参考了其实现并通过交叉验证，但若你**分发（含修改后分发）**本应用，请留意 AGPLv3 的「衍生作品须以相同许可证开源」要求。

构建阶段使用的工具来自 **Android SDK `build-tools`**（`aapt2`、`d8`、`zipalign`、`apksigner`），属于编译工具链、非运行时依赖。

---

## 二、如何继续开发

### 1. 环境准备

| 工具 | 版本要求 | 说明 |
|---|---|---|
| JDK | 11+ | 仅用于 `javac` 编译（源码按 `-source 8 -target 8` 编译） |
| Android SDK | `build-tools/34.0.0` + `platforms/android-35` | 构建脚本写死这两个路径（`build.sh` 顶部 `SDK`/`BT`/`PLATFORM` 变量可改） |
| `adb` | 任意 | 安装 / 抓日志（可选） |

设置环境变量：

```bash
export ANDROID_HOME=/opt/android-sdk   # 指向你的 Android SDK 根目录
```

### 2. 获取代码

```bash
git clone https://github.com/xizoia/SimpleFetch-Android-app-SimpleFetchDroid.git
# 或解压 SimpleFetchDroid-src.zip
```

### 3. 构建

不使用 Gradle，直接用 Android 官方工具链手动构建：

```bash
cd SimpleFetchDroid
ANDROID_HOME=/opt/android-sdk ./build.sh
```

构建流程（`build.sh` 内部 7 步）：

```
aapt2 compile 资源
  → aapt2 link（生成 resources.apk + R.java，注入 versionCode/versionName）
    → 生成 BuildConfig.java
      → javac 编译（源码 8 + R.java + BuildConfig）
        → d8 打包 dex → zipalign 对齐 → apksigner 签名（debug 自签）
```

产物：`SimpleFetchDroid.apk`（debug 签名，可直接 `adb install`）。

> **版本号三处必须同步**（否则 `AndroidManifest` 的 `versionCode` 优先生效，导致覆盖安装失败）：
> 1. `app/src/main/AndroidManifest.xml` 的 `android:versionCode` / `android:versionName`
> 2. `build.sh` 里 `--version-code` / `--version-name`
> 3. `build.sh` 内联生成的 `BuildConfig.VERSION_CODE` / `VERSION_NAME`

### 4. 工程结构

```
app/src/main/
├── AndroidManifest.xml
├── java/cn/b4qaq/simplefetchdroid/
│   ├── ble/BleClient.java        # BLE 连接 / GATT / 特征读写 / 分块发送队列 / 设备信息(电量)读取
│   ├── frame/V2Frame.java        # SPP V2 帧编解码（A5A5 + CRC-16/ARC）
│   ├── proto/ProtoWriter.java    # protobuf wire 编码器（手写，零依赖）
│   ├── proto/ProtoReader.java    # protobuf wire 解析器
│   ├── proto/Wear.java           # WearPacket / Auth / ThirdpartyApp 等业务消息
│   ├── crypto/CryptoUtil.java    # HMAC / kdf_miwear(HKDF) / AES-CCM / AES-CTR
│   ├── device/DeviceSession.java # 会话状态机：认证 → 业务分发 → 应用上下线
│   ├── sf/SfBridge.java          # SF 协议状态机（握手/心跳/请求/SSE/下载/关闭）
│   ├── sf/HttpAgent.java         # HTTP 代理执行器（含 SSE 流式解析）
│   ├── store/Prefs.java          # AuthKey / 上次设备 / 最近应用 / 统计持久化
│   ├── service/KeepAliveService.java # 前台保活服务（通知 + WakeLock + 自检）
│   └── ui/
│       ├── MainActivity.java     # 主界面（连接流程 / 桥接管理 / 应用上线重连）
│       └── AboutActivity.java    # 关于大页面（开源项目列表 + 跳转源码）
└── res/
    ├── layout/  activity_main.xml · activity_about.xml
    ├── values/  strings.xml · colors.xml · styles.xml   （浅色色板）
    ├── values-night/  colors.xml · styles.xml            （深色色板，跟随系统）
    ├── drawable/  btn_*/card_bg*/chip_bg/log_bg（HyperOS 风格 shape）
    └── mipmap-*  ic_launcher（自适应图标，蓝渐变 + 白链环）
```

### 5. 常见二次开发位置

| 想改什么 | 改哪里 |
|---|---|
| UI 文案 / 颜色 / 深浅主题 | `res/values/strings.xml`、`res/values*/colors.xml`、`res/values*/styles.xml` |
| 界面布局 | `res/layout/activity_main.xml`、`activity_about.xml` |
| 按钮样式（圆角/毛玻璃） | `res/drawable/btn_*`、`card_bg*` |
| 连接 / 认证流程 | `device/DeviceSession.java` + `crypto/CryptoUtil.java` |
| BLE 收发 / MTU / 设备信息 | `ble/BleClient.java` |
| 快应用桥接逻辑（握手/SSE/代理） | `sf/SfBridge.java`、`sf/HttpAgent.java` |
| 应用「再次上线」自动断开重连 | `MainActivity.onAppOnline()`（含 8 秒冷却窗口） |
| 后台保活 / 通知栏统计 | `service/KeepAliveService.java` |
| 持久化数据 | `store/Prefs.java` |
| 新增协议消息类型 | `proto/Wear.java`（消息体）→ `device/DeviceSession.java`（分发） |
| 新增支持设备 | `ble/BleClient.java` 的 Service/特征 UUID 与分帧逻辑 |

**调试技巧**
- 编译报错会写入 `build/javac.log`（`build.sh` 第 4 步）；`find build/obj -name '*.class'` 确认是否产出 `.class`。
- 验证产物：`aapt2 dump badging SimpleFetchDroid.apk` 看版本号与入口；`dexdump classes.dex | grep 方法名` 确认关键方法已打进 dex；`apksigner verify --print-certs SimpleFetchDroid.apk` 验签。
- App 运行日志显示在界面「日志区」，关键事件（连接/认证/握手/上线重连）都会打印，比 `logcat` 更聚焦。
- **设备信息读取走独立 GATT 通道**（Battery `0x180F`），与桥接通道（`0xFE95`）严格串行、互不干扰——新增设备相关功能时务必保持这条隔离，避免影响桥接稳定性。

### 6. 进一步可做的方向

- 补齐更多设备信息（序列号 / 系统版本走 Device Information Service `0x180A`，已在早期版本验证可用，可按需恢复）。
- 接入更多快应用协议字段（参照 `proto/Wear.java` 的 `WearPacket` 类型枚举）。
- 把手动 `build.sh` 迁移到 Gradle（如需 IDE 调试更顺手）。
- 替换 debug 签名为正式签名，发布到应用商店（注意 AGPLv3 合规，见上文）。

---

## 三、功能

- **BLE 连接**：连接小米 V2/V5 协议设备（Service `0xFE95`，特征 `0x5e`/`0x5f`），支持已配对设备直连、自动重连、MTU 协商
- **V5 认证**：完整实现 `AuthVerify → kdf_miwear → AuthAppConfirm` 流程（AuthKey 认证）
- **快应用管理**：获取安装列表、启动指定快应用、上下线状态同步
- **SF 协议桥接**：完整移植 SimpleFetch 插件状态机
  - 普通请求代理（GET/POST/PUT/DELETE...）
  - SSE 流式响应（`SF_SSE_EVENT` / `SF_SSE_END`）
  - 大文件下载分片（base64 8KB 分片）、大响应分片（base64 12KB 分片）
  - 心跳保活（PING/PONG，30 秒超时自动断开）
  - 握手超时、主动/被动断开（`SF_CLOSE_BRIDGE`）
  - **应用再次上线自动重连**：已桥接快应用再次上线即「断开 → 等 3 秒 → 连接」，含 8 秒冷却防自触发死循环
- **后台保活**：前台服务 + 唤醒锁，退出界面/熄屏仍处理请求；通知栏显示设备名与总请求/成功/失败统计
- **设备信息**：连接后读取设备名称 / 电量（标准 GATT，独立于桥接通道）
- **最近应用**：记录最近 4 个成功桥接的应用，点击可重连
- **关于页**：列出使用的开源项目，并跳转本项目源码仓库

## 四、安装与使用

```
adb install SimpleFetchDroid.apk
```

要求 **Android 8.0+**，设备支持小米 V2 SPP 协议（小米手环 9 / Watch S 系列 / Redmi Watch 4 等新一代机型）。

使用步骤：
1. 手机安装「小米运动健康」并绑定手表 → 设备详情 → 开发者选项 → 获取 **AuthKey**（32 位十六进制）。
2. 打开本 App，从「已配对设备」选择手表，粘贴 AuthKey 连接。
3. 认证成功自动拉取快应用列表，点击某应用的「连接」即启动该快应用并握手；之后其网络请求经本 App 代理。

## 五、协议实现对照

| 层 | 实现 | 参考来源 |
|---|---|---|
| BLE GATT | Service `0xFE95`，RX `0x005e`（notify）、TX `0x005f`（write），MTU 512 | Gadgetbridge `XiaomiBleProtocolV2` |
| SPP V2 帧 | `A5 A5 + type + seq + len(LE16) + CRC16/ARC(LE16)` | Gadgetbridge `XiaomiSppPacketV2` |
| V5 认证 | `AuthAppVerify → AuthDeviceVerify → kdf_miwear(HKDF) → AuthAppConfirm(CCM)` | AstroBox-NG-Module-Core `auth.rs` |
| 数据加密 | AES-128-CTR，密钥即 IV；认证消息走明文信道 | Gadgetbridge `XiaomiAuthService` |
| 快应用互联 | `WearPacket(type=20) → MessageContent{basic_info, content}` | AstroBox-NG-Module-Core `thirdparty_app.rs` |
| SF 协议 | 15 种 `SF_` 消息，握手/心跳/请求响应/SSE/下载/关闭 | SimpleFetch 插件 `event_handler.rs` |

核心算法已交叉验证：CRC-16/ARC（`"123456789" → 0xBB3D`）、kdf_miwear（与 `computeAuthStep3Hmac` 对拍）、AES-CCM（RFC 3610 Vector #1）、protobuf wire（构建/解析 roundtrip）。

## 六、已知限制

- 仅支持 V2 SPP 帧协议设备（老款 V1 协议未实现）。
- AuthKey 需从小米运动健康获取，每台设备一个。
- 连接稳定性依赖手表未被「小米运动健康」后台抢连；建议把本 App 加入电池优化白名单。



## 八、致谢

- [SimpleFetch](https://github.com/B4QAQ/SimpleFetch-AstroBoxV2-Plugins)（B4QAQ）— SF 协议设计与原插件实现
- [AstroBox](https://github.com/AstroBox-NG) — V5 协议核心参考
- [Gadgetbridge](https://github.com/Freeyourgadget/Gadgetbridge) — BLE 协议与认证实现参考（AGPLv3）

本项目的协议实现参考上述开源项目，**仅供学习研究使用**。
