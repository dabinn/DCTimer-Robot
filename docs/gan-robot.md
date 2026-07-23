# GanRobot 实现与通讯协议说明

本文说明当前 GanRobot 接入方式、主要代码职责与已验证的 BLE 通讯行为；协议细节来自当前实现和现场 debug 记录，后续可随真机验证继续补充。

## 1. 代码结构与执行逻辑

当前 GanRobot 实现尽量把页面交互、BLE 连接、动作执行和协议细节分开，避免把机器人逻辑混进智能魔方或蓝牙计时器主链。

- `GanRobotActivity.java`：机器人页面入口，负责连接按钮、扫描弹窗、自动连接开关、动作输入、状态文字和机器人按钮动作设置。它只处理 UI 流程，实际 BLE 读写交给 `GanRobotBleClient`。
- `GanRobotBleClient.java`：GanRobot 专用 BLE 客户端，负责手动扫描、手动连接、断线、GATT callback、特征值 attach、`fff2/fff3/fff4` 的读写与通知分发。当前临时的 auto connect 逻辑也合并在这里，作为机器人 BLE 连接能力的一部分。
- `GanRobotProtocol.java`：GanRobot 协议层，集中保存 service/characteristic UUID、通知开启、设备识别规则，以及动作序列编码逻辑。
- `GanRobotExecutor.java`：动作执行层，负责把打乱、还原、smart cube 状态和机器人状态组合起来，生成机器人动作包并等待设备执行完成。
- `GanRobotController.java`：机器人实体按钮事件处理，负责根据用户设置触发还原、打乱或忽略。
- `GanRobotSessionState.java`：MainActivity 与 GanRobot 之间的轻量状态桥，保存主页面当前打乱、目标状态、智能魔方状态，以及机器人是否正在动作。


## 2. BluetoothTools 接入策略

项目原有蓝牙设备主要走 `MainActivity + BluetoothTools` 流程，`BluetoothTools` 同时承担扫描、连接、设备列表回调、智能魔方协议和蓝牙计时器协议分发。GanRobot 当前有独立页面，而现有 `BluetoothTools` 与 `MainActivity` 绑定较深，如果强行把 GanRobot 页面也接入完整流程，会让 `BluetoothTools` 出现大量机器人专用 UI 分支。

因此当前实现采用最小接入策略：

- 在 `BluetoothTools` 中只加入 GanRobot 设备类型识别，相当于把 `TYPE_GAN_ROBOT` 注册进项目现有 BLE 设备分类。
- 不改变 `BluetoothTools` 原有的 `MainActivity` context、扫描弹窗、智能魔方连接流程和蓝牙计时器连接流程。
- GanRobot 手动连接、自动连接、GATT callback 和读写暂时由 `GanRobotBleClient` 维护，减少对项目现有蓝牙主链的影响。

这种做法使 GanRobot 可以复用项目现有的 BLE 设备类型识别入口，同时保持原有智能魔方和蓝牙计时器连接流程不变。

后续如果项目新增统一的多设备连接窗口或连接管理机制，GanRobot 可以直接从 `GanRobotBleClient` 的扫描 / 连接部分迁移过去。届时 `GanRobotBleClient` 可以缩小为仅保留 GanRobot GATT attach、读写和按钮事件分发，甚至按新机制整体重写连接入口。

## 3. 协议概览

GanRobot 通过 BLE GATT 通讯，核心特征值如下：

- **Service UUID**：`0000fff0-0000-1000-8000-00805f9b34fb`
- **控制/状态特征值**：`0000fff1-0000-1000-8000-00805f9b34fb`
- **状态特征值**：`0000fff2-0000-1000-8000-00805f9b34fb`
- **动作特征值**：`0000fff3-0000-1000-8000-00805f9b34fb`
- **按钮/事件特征值**：`0000fff4-0000-1000-8000-00805f9b34fb`
- **其它辅助特征值**：`0000fff5` ~ `0000fff8`

其中：

- `fff1` 当前用途未完全确认，疑似控制/握手用途
- `fff3` 用于写入动作序列
- `fff2` 用于读取当前动作剩余量 / 设备忙闲状态
- `fff4` 会以 `write|notify` 形式出现，按下机器人实体按钮时会收到事件

## 4. 设备发现与连接

当前页面扫描时主要按以下规则识别 GanRobot：

- 设备名通常以 `GANBOT-` 开头
- 扫描记录中若能找到 `fff0` service，会进一步确认是 robot
- 若扫描记录包含 `GAN V2 / V3 / V4` 对应 service，则排除为智能魔方设备

连接流程：

1. `connectGatt(...)`
2. `discoverServices()`
3. 取得 `fff1` ~ `fff8`
4. 进入发送 / 轮询状态流程

## 5. 动作指令格式（fff3）

### 5.1 基本格式

动作不是直接按字符串发送，而是先编码成 **4-bit nibble**：

- 1 个 move = 1 个 nibble
- 1 个 packet 最多 18 bytes
- 18 bytes = 36 个 nibble = 36 个 move

当 nibble 数不足时，剩余字节填充 `0xFF`。
当 move 数为奇数时，最后低 nibble 用 `0xF` 补齐。

### 5.2 move 编码表

| Move | Nibble |
|---|---:|
| `R`  | 0 |
| `R2` | 1 |
| `R'` | 2 |
| `F`  | 3 |
| `F2` | 4 |
| `F'` | 5 |
| `D`  | 6 |
| `D2` | 7 |
| `D'` | 8 |
| `L`  | 9 |
| `L2` | 10 |
| `L'` | 11 |
| `B`  | 12 |
| `B2` | 13 |
| `B'` | 14 |

### 5.3 打包规则

第 `i` 个 move：

- 偶数 index：写入该 byte 的 high nibble
- 奇数 index：写入该 byte 的 low nibble

例：`R F D` 会先转成 `0, 3, 6`，再打成 nibble 流。

## 6. U 面处理

当前协议层 **没有原生 `U` 指令**，原因是 GAN Robot 只有五轴，没有 U 轴。

应用层用宏展开方式模拟 `U / U2 / U'`，再转成 robot 可执行的动作序列。

当前实现里，单个 `U` family 会展开成一段替代序列，因此会比普通面转长很多。

## 7. 状态查询（fff2）

`fff2` 用于读取当前动作剩余量。

当前代码只使用返回值的 **第 1 个 byte**：

- `snapshot[0] & 0xff` → `movesRemaining`

其余字节目前没有进一步解析。

### 7.1 约定语义

- `movesRemaining > 0`：机器人仍在执行
- `movesRemaining == 0`：可视为该包已完成 / 进入 idle

## 8. 按钮事件通道（fff4）

现场 debug 观察到，`fff4` 是实体按钮的事件通道：

- `fff4` 属性为 `write|notify`
- 连接后会先收到一笔初始值：`03 00 00 FF 00 00`
- 每按下一次实体按钮，会收到一次：`02 FF`

因此可直接将 `fff4` 视为**按钮事件**通道。

## 9. 执行时序

每个动作包的执行流程如下：

1. `writeCharacteristic(fff3)`
2. 等待 `onCharacteristicWrite`
3. 轮询读取 `fff2`
4. 直到 `remaining == 0`，认定该包完成
5. 继续发送下一包

## 10. 应用层编排（GanRobot 页）

GanRobot 页实际有三种编排方式：

### 10.1 直接执行

用户手动输入公式时：

- 先做字符串标准化
- 再转成 robot 可执行动作
- 最后直接写 `fff3`

### 10.2 位置 / 状态到状态

当能从 smart cube 获取当前状态时：

- 先根据当前状态与目标状态求解
- 再把解法发给 robot

### 10.3 方向探测（probe）

动作实际执行前会先发两个探测动作：

- `D`
- `F`

再根据 smart cube 状态变化推断 cube 当前朝向，最后把解法重映射到机器人实际朝向。



## 11. 待完善事项

- GanRobot 协议目前还没有完全拆解。当前实现主要覆盖设备发现、连接、动作写入、状态轮询和实体按钮单击事件。
- 对比官方 App 行为，仍能观察到部分动作编排与当前实现不完全一致，说明协议层或应用层调度仍有未确认细节。
- 当前动作速度还没有达到官方 App 的效果，后续需要继续分析动作打包方式、发送节奏和可能存在的设备控制参数。
- 机器人机体上的按钮双击、长按行为已由固件绑定，当前只能稳定接入单击事件，因此应用侧只实现单击触发动作。
- 后续如果对 GanRobot 协议、特征值含义或官方 App 行为有新的发现，会继续更新本文档。
