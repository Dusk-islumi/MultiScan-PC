# MultiScan-PC — 多码扫码枪（Android + Windows 接收器）

把 Android 手机当作**多码扫码器**：扫码页连续扫多个条码 → 确认勾选 → 通过 Wi-Fi 发送到 Windows 电脑 → 电脑上的托盘接收器把条码**逐条模拟键盘输入并回车**。

打开 App 就**自动搜索并连接同一局域网内的电脑**，无需任何设置；首页只有一行连接状态，搜索不到时才弹出窗口让你填地址。局域网内不再使用口令。

本项目是从原版 [MultiScan](https://github.com/Dusk-islumi/MultiScan) 独立拆分出的变种仓库：
保留扫描与本地记录能力，移除了云端同步入口，改为把条码直接发送到 Windows。
原版的服务器同步代码仍保留在工程中，但变种默认关闭自动同步，设置页也不提供云端同步入口。

```
MultiScan-PC/
├─ android/              Android 客户端（applicationId: com.example.multiscan.pc）
├─ pc-receiver/          Windows 托盘接收器（C# 源码 + build.bat）
├─ releases/             打包产物（APK / EXE）
├─ LICENSE
├─ THIRD-PARTY-NOTICES.md
└─ README.md
```

## 支持的码制

当前 App **只启用 Code 128**。解码底座与原版相同，是 [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp)
（Android 依赖 `io.github.zxing-cpp:android:3.1.1`，package `zxingcpp`，不是 Java 版 `com.google.zxing`）。
zxing-cpp 本身支持 Code 39/93、EAN、UPC、ITF、Codabar、QR、Data Matrix、Aztec、PDF417 等，
但本项目在 `android/src/main/java/com/example/multiscan/scan/decode/BarcodeDecoder.kt`
的 pass1 / pass2 两处 `Options(formats = ...)` 中限定为 `Format.CODE_128`。

需要其它码制时，可自行修改两处 `formats`（例如
`setOf(Format.CODE_128, Format.CODE_39, Format.EAN_13, Format.QR_CODE)`），
把 `Detection.format` 从固定 `FORMAT_CODE128` 改为 `result.format.name`
（枚举名形如 `CODE_128`，与现有 `Code128` 不同），并同步记录页/发送逻辑里的格式文案，
然后重新执行 `cd android && ./gradlew assembleDebug`。Windows 接收器不解析码制，只接收字符串并逐条输入，
因此只要手机端能解出，接收器端无需改动。

## 系统要求

| 端 | 要求 |
|---|---|
| 手机 | Android 8.0（API 26）及以上；相机可正常对焦 |
| 电脑 | Windows 10 x64；.NET Framework 4.x（Win10 自带，无需安装） |
| 网络 | 手机与电脑在**同一局域网**（同一路由器 / 同一 Wi-Fi），且路由器未开启 AP 隔离；打开 App 自动连接 |

接收器为单文件 `PcReceiver.exe`，无需安装、无后台服务；关闭托盘图标即完全退出。

## 快速开始

### 1. 启动电脑接收器

双击 `releases/PcReceiver.exe`。

- 首次运行会在 EXE 同目录生成 `PcReceiver.ini` 和 `PcReceiver.log`。
- 托盘出现条码图标，并弹出气泡显示本机地址，例如 `http://192.168.1.10:8765`。
- **首次启动如弹出 Windows 防火墙提示，请勾选「专用网络」并允许访问。**
  若当时点了取消：Windows 安全中心 → 防火墙和网络保护 → 允许应用通过防火墙 → 更改设置 → 勾选 `PcReceiver` 的「专用」→ 确定。
  接收器同时使用 TCP 和 UDP 的 `8765` 端口（发现固定用 UDP 8765），允许该程序即可。
- 托盘菜单可随时查看：地址（点击复制）、状态、重新加载配置、打开配置文件、查看日志、退出。
- 双击托盘图标可再次弹出地址气泡。

> 电脑有多个网卡（VPN / 虚拟机）时，托盘显示的地址可能不是手机能访问的那个。自动搜索会使用实际收到广播的网卡地址；若连接失败，可在 `cmd` 里执行 `ipconfig`，选与手机同网段的 IPv4 地址手动填写。

### 2. 手机连接电脑

安装 `releases/MultiScan-PC-v1.2.0-debug.apk`（可与原版 [MultiScan](https://github.com/Dusk-islumi/MultiScan) 共存）。

打开 App 即可，无需进入设置：

1. 首页顶部会显示「正在搜索电脑…」，连接成功后变为「已连接 · 电脑名」，地址会自动保存。
2. 若一直连不上，会自动弹出「连接电脑」窗口：
   - 确认电脑上的接收器已启动后，点「重新搜索」；
   - 或直接输入托盘菜单显示的地址，例如 `192.168.1.10:8765`，点「连接」。
3. 之后想换电脑，点首页的连接状态即可重新搜索或输入地址；设置页也保留同一个入口。

### 3. 扫码并发送

1. 首页点「开始扫描」，对准条码连续扫描。
   - 同一批次内重复扫到同一个码：显示灰框，不会重复入库。
   - **同一批结束后重新开一批，可以再次扫描同一个码**。
2. 点完成，进入「确认发送」页，勾选要发送的条码（默认全选）。
3. 在电脑上**先打开要输入的窗口并放好光标**（Excel、记事本、业务系统输入框等）。
4. 手机点「发送 N 条到电脑」。接收器受理后，手机显示「已发送，电脑端正在逐条输入」。
5. 接收器等待 `start_delay_ms`（默认 600ms）后开始输入：每条条码逐字符输入，末尾自动回车，再输入下一条。

> 发送成功只代表接收器**已受理**，不代表已经输入完成。接收器同一时间只处理一个批次（输入中再发会返回 409）。

### 4. 记录页

- 按批次查看历史条码，可展开、复制单条或整批。
- 每个批次可「再次发送」到电脑（相当于重发该批次全部条码）。
- 右上角「清空记录」删除本机全部批次与条码（不影响电脑端已输入的内容）。

### 5. 连接状态与省电

- 只在 App **启动 / 回到前台**时检查一次，两次检查至少间隔 60 秒，**没有后台轮询，也没有常驻 socket**。
- 已保存的地址能用时只发一个 `/health` 请求；失效后才做一次约 1.6 秒的 UDP 搜索，搜索完立即关闭 socket。
- 发送成功会立即标记为已连接；只有网络层失败（超时 / 连接被拒）才标记为未连接，业务错误（如电脑正在输入）不会误报。
- 自动搜索失败最多自动弹一次输入窗口；之后点首页状态栏可主动重试，不会反复打扰。

## 接收器配置（PcReceiver.ini）

修改后请在托盘菜单选择「重新加载配置」，无需重启 EXE。

| 键 | 默认值 | 范围 | 说明 |
|---|---|---|---|
| `port` | `8765` | 1–65535 | HTTP 监听端口（`0.0.0.0`），修改后手机会通过发现响应拿到新端口，手动输入时需同步修改 |
| `char_delay_ms` | `6` | 0–200 | 每个字符之间的间隔（毫秒），0 表示不等待 |
| `enter_delay_ms` | `25` | 0–500 | 每条回车后的等待（毫秒） |
| `start_delay_ms` | `600` | 0–10000 | 收到请求到开始输入前的等待，留时间给目标窗口获得焦点 |
| `max_items` | `100` | 1–500 | 单次最多接受的条码数 |

自动发现固定监听 **UDP 8765**，与 HTTP 端口无关；即使把 `port` 改成 `8766`，手机仍能搜到，并自动得到 `http://电脑IP:8766`。

旧版配置文件里的 `token=...` 已不再使用，可删除；保留也不影响运行。

输入太快导致目标程序丢字符时，可适当调大 `char_delay_ms`（如 10–20）；目标窗口切换慢时调大 `start_delay_ms`。

## 网络协议

### 自动发现（UDP 8765）

手机向全局广播地址和各网卡子网广播地址发送 UTF-8 文本：

```
MULTISCAN_DISCOVER_V1
```

接收器回复 JSON（发回请求来源）：

```json
{
  "ok": true,
  "service": "multiscan-pc",
  "version": "1.1",
  "port": 8765,
  "name": "DESKTOP-ABC"
}
```

手机用回复包的来源 IP 拼出 `http://来源IP:port`，并按 `IP:port` 去重。

### `GET /health`

```json
{"ok":true,"version":"1.1"}
```

### `POST /v1/type`

无需鉴权。请求头：`Content-Type: application/json`

```json
{
  "batch_id": "8b6c0f6e-2f4a-4f2b-9d5a-1c2d3e4f5a6b",
  "mode": "enter",
  "items": ["6901234567890", "6901234567891"]
}
```

成功响应（受理，异步输入）：

```json
{"ok":true,"accepted":2}
```

`mode` 仅支持 `enter`（或留空）；每条条码输入完成后发送一次回车。

| 状态码 | 含义 |
|---|---|
| 200 | 已受理并排队输入 |
| 400 | JSON 无效、items 为空、条码为空/超长（>512）/含控制字符、mode 不支持 |
| 404 | 路径不存在 |
| 405 | 方法错误 |
| 408 | 读取请求超时 |
| 409 | 上一批仍在输入 |
| 413 | 条码数超过 `max_items` 或请求体过大 |
| 431 | 请求头过大 |
| 503 | 接收器正在退出 |

限制：请求体 ≤ 64KB，请求头 ≤ 8KB，单条条码 ≤ 512 字符，最多 `max_items` 条。

命令行自测（会把条码输入到当前焦点窗口，请先打开记事本）：

```bat
curl -H "Content-Type: application/json" ^
  -d "{\"batch_id\":\"test\",\"mode\":\"enter\",\"items\":[\"1234567890\"]}" ^
  http://127.0.0.1:8765/v1/type
```

## 安全说明

**本版本按需求取消了发送口令：同一局域网内任何设备都能搜索到接收器并触发键盘输入。** 请务必注意：

- 仅在可信的家庭 / 办公局域网使用；不要把端口映射到公网。
- Windows 防火墙只允许「专用网络」，不要为公司/公共 Wi-Fi 开放。
- 路由器若开启「AP 隔离」，手机搜不到电脑，但也阻止了其他设备互访，这是更安全的选择。
- 日志记录来源 IP、条码数量、错误原因，**不记录条码内容**；日志超过 1MB 自动轮转为 `PcReceiver.log.old`。
- 通信为明文 HTTP，无加密。若局域网不可信，建议使用独立 Wi-Fi / VLAN / VPN，或自行恢复口令鉴权。
- 接收器会向当前前台窗口输入内容；发送前请确认焦点窗口正确，避免误输入到聊天、命令行等窗口。

## 常见问题

**搜索不到电脑**
1. 确认接收器正在运行（托盘图标存在，状态为「运行中」）。
2. 确认手机和电脑在同一 Wi-Fi / 同一网段；手机浏览器访问 `http://电脑IP:8765/health` 应返回 JSON。
3. 检查 Windows 防火墙是否允许 `PcReceiver.exe`（专用网络），允许后仍不行可暂时关闭防火墙测试。
4. 路由器可能开启了 AP 隔离；关闭「AP 隔离 / 客户端隔离」后再试。
5. 电脑有 VPN / 虚拟机 / 多个网卡时，广播可能走错网卡。直接在设置页手动输入 `ipconfig` 中与手机同网段的 IPv4 地址。
6. 仍未找到时，可手动输入地址，不依赖自动发现。

**自动连接失败 / 连接不到电脑**
- 自动发现的地址可能属于虚拟网卡。手动填写电脑 Wi-Fi 网卡的 IPv4 地址。
- 确认 `port` 没有被其他程序占用；托盘菜单「状态」显示运行中即 HTTP 已监听。
- 手机与电脑不在同一子网时，广播可能可用但 TCP 被路由/防火墙拦截。
- 首次自动失败后会弹出输入窗口；若已关掉，点首页的连接状态即可重新打开。

**手机显示发送成功，但电脑没有输入**
- 目标窗口必须在前台获得焦点；不要在其他窗口或桌面状态下发送。
- 若目标程序**以管理员身份运行**，Windows UIPI 会阻止普通权限的接收器注入按键：请右键 `PcReceiver.exe` →「以管理员身份运行」。
- UAC 安全桌面、锁屏界面无法被模拟输入。
- 以管理员身份启动接收器后，普通程序仍可正常接收输入；反之不行。
- 查看 `PcReceiver.log` 中是否有 `typing finished: n/n` 与 `SendInput failed`。

**提示「电脑端正在输入，请稍后重试」**
上一批还没输入完。等待托盘气泡/日志出现 `typing finished` 后再发。

**个别程序只收到部分字符 / 丢回车**
调大 `char_delay_ms` 与 `enter_delay_ms` 后重新加载配置；部分游戏、远程桌面、带键盘钩子的安全软件会忽略 `SendInput`，这类程序无法支持。

**端口被占用 / 启动失败**
修改 `PcReceiver.ini` 的 `port`（如 `8766`），托盘菜单「重新加载配置」。自动发现仍使用 UDP 8765，手机会拿到新端口；手动输入时地址要同步改为新端口。

**Windows SmartScreen 提示未知发布者**
EXE 未做代码签名，点「更多信息」→「仍要运行」。若被杀毒软件拦截，请将该 EXE 加入信任（请自行确认来源可信）。

**升级安装后历史记录消失**
数据库从 v1 升级到 v2（条码唯一约束由全局改为「批次内唯一」），早期调试安装升级时会重建空库；正式使用后不再变动。

## 从源码构建

### Android APK

要求：JDK 17、Android SDK（platform 36 / build-tools 已安装），`android/local.properties` 指向本机 SDK。

```bash
cd android
./gradlew assembleDebug
# 产物：build/outputs/apk/debug/app-debug.apk（在 android/ 目录内）
```

当前变种标识：`com.example.multiscan.pc`，`versionCode 4`，`versionName 1.2.0-pc`，应用名「多码扫码枪」，可与原版 App 同时安装。

单元测试：

```bash
cd android
./gradlew testDebugUnitTest
```

### Windows 接收器

要求：Windows 10 x64，自带 .NET Framework 4.x 编译器（`%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe`）。

```bat
cd pc-receiver
build.bat
:: 产物：pc-receiver\PcReceiver.exe
```

接收器只依赖 `System`、`System.Windows.Forms`、`System.Drawing`、`System.Web.Extensions`，不使用第三方库，单 EXE 约 30KB。

## 已知限制

- **仅启用 Code 128**：其它码制需修改 App 源码后重新构建；Windows 接收器本身与码制无关。
- **无鉴权**：同一局域网内任何设备都能触发输入，仅适合可信网络。
- 仅支持向**当前前台窗口**输入，无法指定目标进程。
- 同一时刻只处理一个批次（409 忙），不支持多手机并发输入。
- 明文 HTTP，无加密。
- 自动发现依赖 UDP 广播；部分企业 Wi-Fi / AP 隔离 / VPN 环境会失败，需要手动输入 IP。
- 单批最多 100 条（可配置到 500），不满足请分批发送。
