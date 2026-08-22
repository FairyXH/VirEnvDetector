# VirEnvDetector — 环境虚拟化检测器

独立 Android 应用（`io.github.fairyxh.VirEnvDetector`），用于从**普通 App 视角**验证
[ZhangVirtualEnv](../ZhangVirtualEnv/README.md) 模块的环境虚拟化是否真正生效。

> 它是 ZhangVirtualEnv 生态的一部分，但作为独立 Gradle 工程维护（复用主模块的 Gradle/AGP 版本，避免与模块混淆）。

> 该检测器需要和Lsposed模块搭配使用，用于为其检测虚拟环境是否生效：[ZhangVirtualEnv](https://github.com/FairyXH/ZhangVirtualEnv)

---

## 1. 为什么需要检测器

模块控制端不能 Hook 自身，因此使用独立测试组件从普通应用视角验证环境数据。
VirEnvDetector 作为项目配套测试组件，被加入模块的 LSPosed 作用域后：

1. 模块的 `FrameworkEnvHookAdapter` 会在检测器进程生效（虚拟位置/基站/BLE/WiFi/传感器/GNSS）；
2. 检测器同时直接调用模块本地 API（`127.0.0.1:18790`），拉取**期望配置**；
3. 将"实读环境"与"期望配置"逐项比对，输出 `PASS / FAIL / SYNCING / NOT_ENABLED / UNKNOWN`；
4. 报告上报到 `/api/test/report`，供自动化回归验证。

一句话：**检测器 = 第三方视角 + 期望配置对照 + 全链路回归工具**。

---

## 2. 检测项

| 检测项 | 实读来源 | 判定依据 |
|---|---|---|
| location | `LocationManager` GPS/Network/Passive 最新位置 | 与 `/api/location/status` 期望坐标距离 ≤ 容差（单点 300m / 路线 500m） |
| cell | `TelephonyManager.getAllCellInfo()` | 期望 `entries` 中存在 mcc/mnc/tac/ci 与实读匹配 |
| ble | `BluetoothLeScanner.startScan` 回调 | 期望 `devices` 中任一 address 出现在扫描结果 |
| wifi | `WifiManager.getScanResults()` | 期望 `networks` 中任一 ssid/bssid 出现在结果 |
| sensor | `SensorManager` 计步器回调 | 期望 `stepFrequency`/`events` 启用且收到步数事件 |
| gnss | `GnssStatus.Callback` | 期望卫星数/使用数 ≥ 80% 匹配虚拟状态 |
| sim | `TelephonyManager`（createForSubscriptionId 读取国家码/运营商/IMSI/ICCID/信号） | 期望 `slots` 中任一卡槽的 mcc/mnc/运营商/IMSI/ICCID 命中实读文本 |

每个检测项 UI 同时展示：
- 实读数据明细（坐标/小区字段/设备列表/步数/卫星数）
- 判定徽标（通过/未通过/同步中/未启用模拟/未知）

---

## 3. 判定语义

| 徽标 | 含义 |
|---|---|
| **通过（绿）** | 实读数据与虚拟期望一致 |
| **未通过（红）** | 期望已启用但实读与期望不一致（真实数据泄漏或 Hook 失效） |
| **同步中（橙）** | 期望配置刚切换（2s 宽限期），Hook 层缓存可能尚未追平，暂不判失败 |
| **未启用模拟（灰）** | 模块该类型未启用，实读为真实数据属正常 |
| **未知（灰）** | 无法读取或无数据 |

> 配置切换宽限期：`random-env` 或手动切换配置后，模块 `EnvStateCache`（500ms 轮询）
> 与 BLE/GNSS 的"配置就绪后接管"需要约 2s 完成；此期间 FAIL 自动降级为 SYNCING，
> 避免瞬时误报。

---

## 4. 使用方法

### 4.1 环境要求

- Android 10+（当前验证 Oplus Android 15）
- 已安装并启用 ZhangVirtualEnv 模块（LSPosed）
- 检测器已被加入模块 `scope.list`（默认已包含）

### 4.2 构建

```bash
cd VirEnvDetector
./gradlew assembleDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 4.3 安装与启动

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动
adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity
```

首次启动授予权限（定位、蓝牙、WiFi、电话）。模块更新后如需重新加载 Hook：
`adb install -r` 后 **`adb reboot`**。

### 4.4 操作

- **开始检测**：仅对当前已持续采集到的最新数据执行一次判定；不会启动采集。打开检测器后即自动注册监听器，并每秒刷新所有类型数据
- **持续采集**：位置、基站、蓝牙 BLE、WiFi、传感器、GNSS、SIM 和模块状态在检测器打开期间持续获取；每类标题显示最后刷新时间
- **清除判定**：只清除当前 PASS/FAIL 判定，不停止持续采集
- **随机模拟**：一键调用 `/api/debug/random-env` 生成全套随机虚拟环境并启用；持续采集会自动读取最新模拟数据，点击“开始检测”即可判定当前结果是否一致
- **远程环境判定接口**：控制端启用远程环境模拟后，会通过 `/api/remote/status` 发布当前 Consumer 远程模式、设备/服务端标识、更新时间以及模块当前生效的 location/env 快照。独立检测器读取该接口后，在远程模式下按模块当前实际生效快照判定；未启用远程模式时继续使用传统本地模拟期望配置。只有点击“开始远程测试”后才建立 Collector WebSocket。开始/停止远程测试按钮只控制采集端发包，不会启动、停止或改变本地持续采集与判定；服务端 ACK 与序号用于确认服务端接收状态
- **远程判定模式状态**：检测器顶部会显示当前使用“远程环境模拟判定”或“本地模拟判定”。远程模式只有在模块控制端已启用远程模式、选中设备且 Consumer WebSocket 已连接时才为真。
- 服务端 URL、Token、Device ID、最近测试状态和结果会保存在检测器私有设置中
- **刷新全部信息**：在服务端调试信息与位置信息之间，强制重新注册位置、基站、WiFi、BLE、GNSS、传感器监听并立即执行一次全量扫描/刷新
- **生命周期结束**：仅在 Activity 被销毁时停止刷新、注销监听器并清理资源；页面内的“清除判定”不会停止采集

---

## 5. 与模块 API 的交互

- 端口：`127.0.0.1:18790`
- 鉴权：`X-ZVE-Token` 请求头（从本 APK `assets/api_token.txt` 读取，须与模块 APK 内 token 一致）
- 网络：raw TCP Socket 直连（与模块 EnvStateCache 一致，绕过系统代理/Tun 与 cleartext 策略）
- 线程：所有网络调用在后台线程执行，UI 更新回主线程（避免 NetworkOnMainThread）

调用的接口：

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/env/status` | 期望环境配置（wifi/cell/ble/sensor/gnss） |
| GET | `/api/location/status` | 期望位置 |
| GET | `/api/route/status` | 期望路线/步频 |
| POST | `/api/debug/random-env` | 随机模拟（一键生成并启用全套环境） |
| POST | `/api/test/report` | 上报检测报告 |

### Token 说明

- Token 必须与模块 APK 中的 `assets/api_token.txt` **完全相同**。
- 未带 Token 或 Token 错误时，模块服务不返回任何字节直接断开（fail-closed）。
- 更换 Token 时需同步更新两个工程并重新打包。

---

## 6. Root 支持

检测器内置 Root 检测（`su -c id`），UI 顶部显示：

- `Root: 可用（可验证模块存在）`
- `Root: 不可用（检测器无法感知被 HMA 隐藏的模块，建议授予 Root）`

适用场景：

- 系统装有 **HideMyAppList / HMA** 等隐藏模块的应用；
- 需要直接读取模块在 `/data/system` 下的持久化配置以确认模块存在与配置内容。

**如果要用 Root 直读模块配置，请在 Magisk 中为 VirEnvDetector 授权 Root。**

---

## 7. 实时配置刷新机制

模块侧（保证 Hook 层数据及时）：

- `EnvStateCache` 500ms 轮询 `/api/env/status`
- BLE：`startScan` 未就绪时暂存回调，配置到达后自动补投递虚拟结果
- GNSS：300ms 周期投递虚拟卫星状态，快速覆盖真实回调
- 传感器：pending + refresh 补启动注入

检测器侧（避免瞬时误判）：

- 每秒拉取期望配置并计算指纹
- 配置变化后 2s 宽限期内 FAIL → SYNCING
- `random-env` 成功后等待 900ms 再注册监听，确保模块缓存已追平

---

## 8. 目录结构

```
VirEnvDetector/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml        # 定位/蓝牙/WiFi/电话权限
│       ├── assets/api_token.txt       # 与模块一致的 API Token
│       ├── java/io/github/fairyxh/VirEnvDetector/
│       │   └── MainActivity.kt        # 全部检测逻辑（单文件实现）
│       └── res/values/strings.xml
├── build.gradle.kts                   # 工程级（复用主模块 Gradle 版本）
├── settings.gradle.kts
└── gradle.properties / local.properties
```

> `MainActivity.kt` 为单文件实现：UI、实读、判定、API 客户端、Root 检测均在类内，
> 便于快速阅读与维护。

---

## 9. 常见问题排查

### 9.1 六项全部 NOT_ENABLED

- 模块 Backend 未运行（未在 LSPosed 启用/未重启）
- 检测器 Token 与模块不一致 → API 被拒（表现为无期望配置）
- 检查：`adb logcat -s VirEnvDetector:I` 是否有 `token=loaded len=48`

### 9.2 某项 FAIL 且读数为真实数据

- 模块该类型未启用 → 正常（应显示 NOT_ENABLED；若显示 FAIL 说明期望已启用）
- 配置刚切换 → 等待 2s 后应为 PASS 或 SYNCING
- Hook 未在检测器进程生效：
  - 确认检测器在 `scope.list` 中
  - 模块更新后需 `adb reboot`
  - 若系统装有 HMA，改用 Root 模式验证

### 9.3 GNSS 波动 / 读到真实卫星

- 已修复为拦截 `registerGnssStatusCallback` + 300ms 周期投递；若仍复现请更新模块到最新构建
- 参考模块 `docs/reverse/env-live-test-and-hook-fixes.md`

### 9.4 报告未上报

- `/api/test/report` 需要有效 Token
- 检测器日志出现 `api POST ... failed: null` 时检查 Token 与网络（raw TCP 应绕过代理）

---

## 10. 验证示例（全链路 PASS）

```
location: PASS | provider=gps
cell:     PASS | LTE mcc=460 mnc=11 tac=24236 ci=240160428 pci=428
ble:      PASS | ZVE-Device-0 AA:BB:CC:DB:29:C3
wifi:     PASS | ZVE-Rand-0 ...
sensor:   PASS | 计步器步数: 15801
gnss:     PASS | 卫星总数: 16 使用: 5
```

---

## 11. 关联工程

| 工程 | 说明 |
|---|---|
| `../ZhangVirtualEnv` | 环境虚拟化模块（控制端 + Backend + Hook） |
| `../ZhangVirtualEnv/docs/reverse/` | 逆向分析文档与真机验证脚本（新 Agent 先读这里） |

---

## 12. 许可证

与 ZhangVirtualEnv 主仓库相同（见 `../ZhangVirtualEnv/LICENSE`）。
