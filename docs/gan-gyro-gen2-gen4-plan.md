# GAN Gen2 / Gen4 陀螺仪接入实施方案

日期：`2026-07-15`

状态：代码和协议单元测试已完成；协议字段已根据 `ref/smartcube-web-bluetooth` 核对，Gen2、Gen4 真机验收待执行。

## 目标

在现有 GAN 智能魔方连接链中接入 Gen2 和 Gen4 的陀螺仪姿态数据，使计时页和魔方状态弹窗能够复用当前 `SmartCube3DView` 的四元数姿态跟随能力。

本次接入保持以下现有边界：

- BLE 扫描、连接、解密和协议分发继续由 `BluetoothTools` 与 `GanCubeProtocol` 负责。
- 协议层只解析、校验、转换并上报归一化四元数，不直接操作 3D 视图。
- 姿态继续统一进入 `MainActivity.onSmartCubeGyroChanged(x, y, z, w)`。
- `SmartCube3DView` 和 `CubeStateDialog` 继续消费统一四元数，不感知 GAN 协议版本。
- 陀螺仪只影响视角跟随，不改变魔方状态、转动事件、计时状态机、成绩保存或解法重建。

## 范围

### 接入范围

- GAN Gen2 陀螺仪通知解析。
- GAN Gen4 陀螺仪通知解析。
- GAN 原始坐标系到当前 3D 渲染坐标系的转换。
- 按当前连接实际收到的有效姿态包识别陀螺仪能力。
- 新连接和断开时清理旧姿态与校准状态。
- 协议解析单元测试和 Gen2、Gen4 真机验收。

### 不在本次范围

- GAN Gen1 陀螺仪接入。
- GAN Gen3 陀螺仪适配；当前参考实现没有 Gen3 姿态事件分支。
- 角速度数据的业务使用。
- 修改现有姿态平滑、重置姿态或手动拖动逻辑。
- 新增设置项、资源文案、数据库字段或持久化迁移。
- 调整 GAN MOVE、FACELETS、MOVE_HISTORY、BATTERY 现有行为。

## 协议依据

参考实现：`ref/smartcube-web-bluetooth/src/gan-cube-protocol.ts`。

### Gen2

- 解密后消息的首 `4 bit` 为事件类型。
- 事件类型 `0x1` 表示陀螺仪事件。
- 四元数从 bit `4` 开始，依次为 `qw / qx / qy / qz`，每项 `16 bit`。
- 角速度从 bit `68` 开始，依次为 `vx / vy / vz`，每项 `4 bit`；本次不使用。

### Gen4

- 解密后消息的首字节为事件类型。
- 事件类型 `0xEC` 表示陀螺仪事件。
- 四元数从 bit `16` 开始，依次为 `qw / qx / qy / qz`，每项 `16 bit`。
- 角速度从 bit `80` 开始，依次为 `vx / vy / vz`，每项 `4 bit`；本次不使用。
- 参考抓包 `fixture_GANi4_A26E_gan-gen4_2026-04-14T11-38-02.json` 已包含连续 `GYRO` 事件。

### 分量解码

Gen2 和 Gen4 使用相同的 `16 bit` 分量格式：最高位为符号位，其余 `15 bit` 为幅值。解码规则为：

```text
value = sign * magnitude / 0x7FFF
sign = 最高位为 1 时取 -1，否则取 1
magnitude = raw & 0x7FFF
```

解码完成后必须对四元数重新归一化，避免量化误差进入渲染层。

## 坐标系约定

参考实现将 GAN 姿态坐标定义为右手坐标系：

- `+X`：红面 `R`
- `+Y`：蓝面 `B`
- `+Z`：白面 `U`

当前 `SmartCube3DView` 模型坐标为：

- `+X`：红面 `R`
- `+Y`：白面 `U`
- `+Z`：绿面 `F`

因此协议层向统一入口上报前按以下方式转换：

```text
GAN (x, y, z, w) -> Renderer (x, z, -y, w)
```

该变换是右手坐标系之间的轴变换，与当前 MoYu32 上报到统一姿态入口时使用的映射一致。最终方向仍需 Gen2、Gen4 真机分别验证；若单轴方向不一致，只调整 `GanCubeProtocol` 中的坐标映射，不在 UI 层增加型号补偿。

## 数据流

```text
GAN BLE notification
    -> GanCubeCipher.decode()
    -> GanCubeProtocol 识别 Gen2 0x1 / Gen4 0xEC
    -> 解码、合法性校验、坐标转换、归一化
    -> MainActivity.onSmartCubeGyroChanged()
    -> SmartCube3DView / CubeStateDialog
```

## 实施步骤

### 步骤一：增加共用四元数解析

修改 `app/src/main/java/com/dctimer/util/GanCubeProtocol.java`。

1. 增加 GAN `16 bit` 符号幅值分量的解码方法。
2. 增加共用四元数解析方法，由调用方传入四元数起始 bit。
3. 按 `qw / qx / qy / qz` 顺序读取原始分量。
4. 将原始 `(x, y, z, w)` 转换为 `(x, z, -y, w)`。
5. 计算模长并归一化后返回。
6. 对下列情况返回解析失败，不触发 UI 回调：
   - 解密结果为空或 bit 长度不足。
   - 四个原始分量全部为零。
   - 模长小于安全阈值。
   - 任一结果为 `NaN` 或无穷值。

解析方法保持包内可测试，不增加对外公开 API，也不把协议细节放入 `SmartCubeProtocol` 接口。

### 步骤二：接入 Gen2 通知分发

修改 `GanCubeProtocol.parseV2Data()`。

1. 保持现有 AES 解密和 bit string 转换顺序。
2. 在现有 MOVE、FACELETS、BATTERY 分支之外增加 `mode == 1` 分支。
3. 使用 bit `4` 作为四元数起点调用共用解析方法。
4. 解析成功后调用 `context.onSmartCubeGyroChanged(x, y, z, w)`。
5. 姿态包不修改 `prevMoveCnt`、`currentMoveCnt`、电量或 `SmartCube.cubeState`。

### 步骤三：接入 Gen4 通知分发

修改 `GanCubeProtocol.parseV4Data()`。

1. 保持现有 MOVE、FACELETS、MOVE_HISTORY、BATTERY 分支不变。
2. 增加 `mode == 0xEC` 分支。
3. 使用 bit `16` 作为四元数起点调用共用解析方法。
4. 解析成功后调用统一姿态入口。
5. 不将 `0xEC` 当作 `72-bit MOVE chunk` 处理，也不进入 move buffer。

GAN Gen2、Gen4 的参考抓包表明姿态通知会在订阅通知后主动上报，因此本次不增加陀螺仪启用命令，也不改变初始化请求队列。

### 步骤四：改为当前连接的运行时能力识别

修改 `app/src/main/java/com/dctimer/activity/MainActivity.java`。

1. 不把全部 `TYPE_GANI_CUBE` 直接加入固定陀螺仪白名单，因为该类型同时覆盖 Gen2、Gen3 和 Gen4。
2. 首次收到合法四元数后，将当前连接视为支持陀螺仪。
3. `shouldFollowSmartCubeGyro()` 同时检查用户设置和当前连接是否已有有效姿态能力。
4. 保持“陀螺仪跟随视角”关闭时仍可接收和缓存姿态，但不驱动 3D 视图。
5. 首次有效姿态继续作为初始校准值，复用现有重置姿态逻辑。

该方式保证 Gen2、Gen4 收到姿态后自动启用跟随，同时不会将没有姿态通知的 Gen3 误判为支持。

### 步骤五：清理连接级姿态状态

修改 `MainActivity` 的智能魔方连接和断开流程。

1. 新的计时 BLE 设备完成服务识别、协议启动前，清除上一连接的：
   - 最新四元数。
   - 初始校准四元数。
   - 当前连接陀螺仪能力状态。
   - 待提交的姿态 UI 更新标记。
2. 断开连接或回退普通计时器时执行同样清理。
3. 同步关闭计时页和状态弹窗的陀螺仪视图，恢复默认斜三面视角。
4. 不清除用户保存的 `scgyro` 设置；下次连接支持陀螺仪的设备后继续按该设置工作。

此步骤用于避免从 MoYu32、GAN Gen2 或 Gen4 切换到 Gen3 时复用上一台设备的姿态。

### 步骤六：增加协议单元测试

新增 `app/src/test/java/com/dctimer/util/GanCubeProtocolTest.java`。

测试使用构造的解密后 payload，直接覆盖协议 bit 位和坐标转换，不依赖 Android BLE 或真机。

至少覆盖：

1. Gen2 `mode == 1` 的四元数起始位置为 bit `4`。
2. Gen4 `mode == 0xEC` 的四元数起始位置为 bit `16`。
3. 正负分量按符号位和 `0x7FFF` 正确解码。
4. `(x, y, z, w)` 正确映射为 `(x, z, -y, w)`。
5. 输出四元数模长在浮点误差范围内等于 `1`。
6. 全零四元数、短包和非法数值不会产生有效结果。
7. Gen2 MOVE、Gen4 MOVE 和 Gen3 分支没有被姿态类型误匹配。

自动验证命令：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.dctimer.util.GanCubeProtocolTest"
```

本次预计为小范围协议与页面状态改动，默认不执行 `assembleDebug`。如果实施时改动扩展到 5 个以上生产文件、修改协议接口或构建配置，再补充执行：

```powershell
.\gradlew.bat assembleDebug
```

### 步骤七：真机验收

Gen2 和 Gen4 必须分别验证，不能用其中一个型号的结果代替另一个。

#### 基础姿态

- 连接后无需额外操作即可收到姿态并进入跟随视角。
- 魔方静置时画面稳定，没有持续跳变、翻转或明显漂移。
- 分别绕红、白、绿三个物理轴缓慢旋转，虚拟魔方的轴和方向一致。
- 四元数跨正负等价表示时，画面没有突然反向旋转；现有 `slerp` 最短路径逻辑正常生效。

#### 页面交互

- 计时页和魔方状态弹窗显示一致。
- 点击“重置姿态”后恢复白顶绿前略俯视基准。
- 手动拖动视角后仍能与姿态跟随叠加。
- 关闭“陀螺仪跟随视角”后视图恢复固定视角；重新打开后使用当前姿态恢复跟随。

#### 计时回归

- 打乱过程中 MOVE 事件、打乱推进和纠错正常。
- 打乱完成后首转起表正常。
- 复原停表、成绩保存和解法步序没有变化。
- 快速双层转动不影响 Gen4 多 MOVE chunk 解析。
- 姿态高频通知下页面无明显卡顿，BLE 请求队列不被阻塞。

#### 连接切换

- Gen2 或 Gen4 断开后，视图不保留旧姿态。
- 从支持陀螺仪的设备切换到 GAN Gen3 后保持默认固定视角。
- 从 GAN Gen3 切回 Gen2 或 Gen4 后，收到首个有效姿态才重新启用跟随。

## 涉及文件

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/dctimer/util/GanCubeProtocol.java` | Gen2、Gen4 姿态分发，共用解码、校验、坐标转换和归一化 |
| `app/src/main/java/com/dctimer/activity/MainActivity.java` | 当前连接能力识别，连接切换时清理姿态状态 |
| `app/src/test/java/com/dctimer/util/GanCubeProtocolTest.java` | 协议字段、坐标映射和异常输入测试 |
| `docs/project.md` | 实施完成后更新当前能力和真机验证状态 |
| `docs/roadmap.md` | 实施完成后收口 GAN Gen2 / Gen4 陀螺仪决策与待验证项 |

`docs/architecture.md` 不需要修改，现有“协议层解析归一化四元数，经 `MainActivity` 进入 UI”的边界已经覆盖本次接入。`README.md` 也无需修改，当前已声明支持 GAN v2 / v3 / v4，本次没有新增设备类别或使用入口。

## 完成标准

满足以下条件后，代码接入可标记为完成：

- Gen2 和 Gen4 姿态包均能通过独立单元测试解析。
- 所有新增协议测试通过，既有相关单元测试无回归。
- Gen3 不会因 GAN 通用设备类型被误判为支持陀螺仪。
- 连接切换不会复用上一台设备的姿态或校准值。
- `docs/project.md` 和 `docs/roadmap.md` 已同步代码完成度。
- 未完成真机验证时，文档和交付说明明确标记“待验证”。

只有 Gen2、Gen4 分别完成基础姿态、页面交互和计时回归验收后，才能将对应型号标记为“真机已验证”。

## 风险与回退

### 主要风险

- 不同 GAN 型号或固件的物理轴方向可能与参考实现标注存在差异。
- 高频姿态通知可能暴露 UI 更新节流或连接切换时序问题。
- 若未清理连接级姿态，Gen3 可能错误复用上一台设备的姿态。

### 回退方式

本次不修改持久化数据、数据库或公共协议接口，回退只需撤销 `GanCubeProtocol` 的姿态分支和 `MainActivity` 的运行时能力状态，不影响 GAN 现有转动、状态、电量和计时链路。

若解析正确但真机轴方向错误，优先只调整 `GanCubeProtocol` 的坐标映射；不要修改 `SmartCube3DView`，避免影响已经验证的 MoYu32 姿态行为。
