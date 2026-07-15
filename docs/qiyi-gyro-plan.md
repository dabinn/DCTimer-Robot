# QiYi 智能魔方陀螺仪接入实施方案

日期：`2026-07-15`

状态：方案已完成，代码未实施；协议字段已根据 `ref/smartcube-web-bluetooth` 与参考抓包核对，真机能力边界待验证。

## 目标

在当前 QiYi 智能魔方协议链中接入陀螺仪姿态，使支持姿态通知的 QiYi 设备能够复用现有计时页和状态弹窗的 3D 四元数跟随能力。

本次接入保持以下既有边界：

- `BluetoothTools` 继续负责扫描、连接、设备类型识别和协议分发。
- `QiyiCubeProtocol` 只负责 AES 解密、帧识别、校验、四元数解析、坐标转换和上报。
- 姿态统一进入 `MainActivity.onSmartCubeGyroChanged(x, y, z, w)`。
- `SmartCube3DView` 与 `CubeStateDialog` 继续消费统一四元数，不感知 QiYi 协议。
- 陀螺仪只影响 3D 视角，不修改魔方状态、转动事件、计时状态机、成绩保存或解法重建。

## 现状结论

当前项目已经具备完整的通用姿态链路：

```text
协议层归一化四元数
    -> MainActivity.onSmartCubeGyroChanged()
    -> 当前连接运行时能力识别与首帧校准
    -> SmartCube3DView / CubeStateDialog
```

`MainActivity` 已在首次收到合法姿态时把当前连接标记为支持陀螺仪，并在重新扫描、连接成功、断开和回退普通计时时清理旧姿态与校准。因此本次不需要修改 UI、渲染层、设置项或连接状态管理。

缺口集中在 `QiyiCubeProtocol.parseMessage()`：当前所有解密数据都先按普通 `0xFE` 帧处理，而参考实现中的 QiYi 姿态是独立的 `0xCC 0x10` 帧。现有代码会把这种帧交给 `handleNonProtocolMessage()`，连接初期连续收到三帧时还会设置 `protocolMismatchDetected`，停止后续 hello 和重试。仅增加四元数解码函数不足以完成接入，必须先改变解密后的帧分流顺序。

## 参考依据

主要参考文件：

- `ref/smartcube-web-bluetooth/src/smartcube/protocols/qiyi.ts`
- `ref/smartcube-web-bluetooth/captures/fixture_XMD-TornadoV4-i-034C__qiyi_2026-04-14T11-46-25.json`
- `ref/smartcube-web-bluetooth/captures/fixture_QY-QYSC-S-A0E6________qiyi_2026-04-14T11-37-22.json`

### 姿态帧格式

AES 解密后的姿态帧固定为 `16` 字节：

| 偏移 | 长度 | 含义 | 编码 |
| --- | --- | --- | --- |
| `0` | 1 | 帧头 | `0xCC` |
| `1` | 1 | 帧长度 | `0x10` |
| `2..5` | 4 | 序号或保留字段 | 本次不使用 |
| `6..7` | 2 | `ax` | 大端有符号 `int16` |
| `8..9` | 2 | `ay` | 大端有符号 `int16` |
| `10..11` | 2 | `az` | 大端有符号 `int16` |
| `12..13` | 2 | `aw` | 大端有符号 `int16` |
| `14..15` | 2 | CRC16-Modbus | 小端保存，覆盖前 `14` 字节 |

各姿态分量按 `raw / 1000f` 解码。

参考抓包中的首个姿态通知：

```text
加密：2CD322E4C813863EAAC4182B687EC590
解密：CC100004F663FDBCFE59FEA0FDACDEA1
原始：(ax, ay, az, aw) = (-0.580, -0.423, -0.352, -0.596)
参考输出：(x, y, z, w) = (-0.580, 0.352, -0.423, -0.596)
```

### 坐标映射

参考实现使用以下变换：

```text
QiYi (ax, ay, az, aw) -> Renderer (ax, ay, az, aw)
```

协议层在坐标转换后还应重新归一化四元数。参考 Web 实现直接上报千分位值，但当前项目架构明确要求协议层输出归一化四元数，且 MoYu32、GAN 已遵循这一规则。归一化只消除量化误差，不改变姿态方向。

### 设备能力边界

两份参考抓包呈现不同能力：

| 设备 | 事件数 | 姿态事件数 | 当前结论 |
| --- | ---: | ---: | --- |
| `XMD-TornadoV4-i-034C` | 279 | 214 | 明确持续上报姿态 |
| `QY-QYSC-S-A0E6` | 65 | 0 | 不能据此声明支持姿态 |

因此不按 `TYPE_QIYI_CUBE` 或设备名前缀建立固定白名单。继续沿用当前项目的运行时能力识别：只有当前连接收到并成功解析首个合法四元数后，才启用姿态跟随。

## 范围

### 本次接入

- 识别 AES 解密后的 QiYi `0xCC 0x10` 姿态帧。
- 校验姿态帧 CRC、长度和四元数合法性。
- 解析大端有符号 `int16` 分量并按 `1/1000` 缩放。
- 完成 QiYi 坐标到当前 3D 渲染坐标的转换与归一化。
- 把合法姿态上报到现有统一入口。
- 避免合法 `0xCC` 姿态帧触发协议不匹配保护。
- 增加协议单元测试和分型号真机验收。

### 不在本次范围

- 修改 BLE 扫描、服务识别、MTU、AES 密钥、hello、ACK 或写队列。
- 修改普通 `0xFE` 状态帧、history、facelet、电量和本地重置逻辑。
- 为 QiYi 新增独立设置项、文案、资源或设备能力白名单。
- 修改 `MainActivity`、`SmartCube3DView` 或 `CubeStateDialog` 的通用姿态行为。
- 使用角速度、序号或姿态帧中的保留字段。
- 声明所有 `QY-QYSC` 型号都支持陀螺仪。

## 推荐方案

采用“解密后优先识别独立姿态帧，普通帧保持原路径”的方案。

```text
FFF6 BLE notification
    -> QiyiCubeProtocol AES-ECB 解密
    -> 0xCC 0x10 ?
       -> 是：CRC 校验 -> 四元数解析/转换/归一化 -> 统一姿态入口
       -> 否：继续现有 0xFE 长度/CRC/opcode 分发
```

该方案改动集中、可独立回退，不改变公共接口，也不会把 QiYi 差异扩散到 UI 层。

不采用“在 `MainActivity` 中按 QiYi 设备类型直接声明支持陀螺仪”的替代方案。它虽然改动更少，但会把没有姿态事件证据的 `QY-QYSC` 一并误判为支持，并使视图能力与实际通知脱节。

本方案最关键的协议假设是目标 QiYi 固件沿用参考实现的 `0xCC 0x10` 帧格式。真机反馈已确认参考实现中的轴交换不适用于当前渲染链，QiYi 四元数应按 `(ax, ay, az, aw)` 顺序传入；如果其他型号仍存在物理轴差异，只调整 `QiyiCubeProtocol` 的坐标映射，不修改通用渲染层。

## 实施步骤

### 步骤一：提取可测试的姿态帧解析

修改 `app/src/main/java/com/dctimer/util/QiyiCubeProtocol.java`。

1. 增加姿态帧头、固定长度、分量缩放值和最小合法模长常量。
2. 增加包内可测试的静态解析方法，输入 AES 解密后的字节数组，输出归一化后的 `float[4]`；非法帧返回 `null`。
3. 只接受以下条件全部成立的帧：
   - 长度至少为 `16` 字节。
   - `msg[0] == 0xCC` 且 `msg[1] == 0x10`。
   - 前 `14` 字节计算得到的 CRC 与 `msg[14] | msg[15] << 8` 相等。
4. 使用大端顺序读取偏移 `6 / 8 / 10 / 12` 的四个有符号 `int16`。
5. 分别除以 `1000f`，得到 `(ax, ay, az, aw)`。
6. 按 `(ax, ay, az, aw)` 顺序计算模长并归一化。
7. 全零、模长低于安全阈值、`NaN` 或无穷值返回 `null`，不得触发 UI 回调。

CRC 计算复用当前 `crc16Modbus` 算法；可将其调整为包内静态辅助方法，以便解析器和单元测试复用，不增加公共 API。

### 步骤二：在普通 `0xFE` 分流前处理姿态

继续修改 `QiyiCubeProtocol.parseMessage()`。

1. 保持通知长度必须为 AES 块大小倍数的现有前置检查。
2. 完成 AES 解密后，先检查 `0xCC 0x10`，再读取普通帧的 `msgLength`。
3. 命中姿态帧时调用步骤一的解析方法：
   - 成功时调用 `context.onSmartCubeGyroChanged(q[0], q[1], q[2], q[3])`。
   - 成功或校验失败后都直接结束本帧处理，不进入普通 `0xFE` opcode 分支。
4. 合法姿态帧不调用 `handleNonProtocolMessage()`，避免累计 `nonProtocolMessageCount` 和误设 `protocolMismatchDetected`。
5. 姿态帧不设置 `helloReceived`，因为它不能替代 hello 状态同步；连接初期即使先收到姿态，也应允许现有 hello 和重试继续运行。
6. 姿态帧不发送 ACK，不修改 `lastTimestamp`、电量、facelet、move history、请求队列或本地重置状态。
7. 非 `0xCC 0x10` 数据继续完整走现有普通帧处理，保留当前未知 `0xCC` 帧的协议保护行为。

### 步骤三：补充协议单元测试

修改 `app/src/test/java/com/dctimer/util/QiyiCubeProtocolTest.java`，不新增 Android BLE 或 Robolectric 依赖。

至少覆盖：

1. 使用真实解密帧 `CC100004F663FDBCFE59FEA0FDACDEA1`，验证大端有符号分量、坐标映射和归一化结果。
2. 验证输出模长在浮点误差范围内等于 `1`。
3. 验证正负轴组合，防止误用 MoYu/GAN 的 `(x, z, -y, w)` 轴转换。
4. 修改真实帧中的一个数据字节，验证 CRC 失败时返回 `null`。
5. 验证短包、错误帧头、错误长度标记返回 `null`。
6. 构造带正确 CRC 的全零四元数，验证不会产生姿态。
7. 验证普通 `0xFE` 帧不被姿态识别器匹配，现有 history 测试继续通过。

自动验证命令：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.dctimer.util.QiyiCubeProtocolTest"
```

本次预计只改一个生产文件和一个测试文件，默认不执行 `assembleDebug`。如果实施时改动扩展到 `MainActivity`、`BluetoothTools`、协议接口或构建配置，再补充执行：

```powershell
.\gradlew.bat assembleDebug
```

### 步骤四：Tornado V4 真机验收

使用明确存在参考姿态数据的 `XMD-TornadoV4-i` 型号完成主验收。

#### 连接与姿态

- 连接后无需新增启用命令即可持续收到姿态。
- 姿态先于 hello 到达时，hello、状态同步、facelet 和电量仍能完成。
- 静置时画面稳定，无持续跳变、翻转或明显漂移。
- 分别绕红、白、绿三个物理轴缓慢旋转，虚拟魔方轴向和方向一致。
- 四元数正负等价表示切换时没有突然反向旋转。

#### 页面交互

- 计时页与魔方状态弹窗姿态一致。
- “重置姿态”恢复白顶绿前略俯视基准。
- 手动拖动视角后仍能与姿态跟随正常叠加。
- 关闭“陀螺仪跟随视角”后恢复固定视角；重新打开后按当前姿态恢复跟随。

#### 计时回归

- MOVE、facelet、history 补步、电量和本地手动重置行为不变。
- 打乱推进、偏离纠错、首转起表、复原停表和成绩保存正常。
- 高频姿态通知下页面无明显卡顿，hello/ACK 写队列没有被阻塞。
- 断开和重连后不复用上一连接的姿态或校准值。

### 步骤五：QY-QYSC 能力回归

使用 `QY-QYSC` 型号单独验证安全退化，不能用 Tornado V4 的结果代替。

- 若设备不发送 `0xCC 0x10`，保持默认固定视角，不误显示为支持陀螺仪。
- hello、状态同步、转动、history、电量和计时主链不受新增分流影响。
- 若真机实际发送合法姿态，则按同一验收清单确认轴向；确认前文档仍标记该型号姿态“待验证”。
- 若收到其它 `0xCC` 帧，确认其仍进入现有未知协议保护，不被误当成姿态。

### 步骤六：实施完成后收口文档状态

代码和单元测试完成后更新：

- `docs/project.md`：记录 QiYi / Tornado V4 姿态协议已接入，并准确区分“代码完成”“单元测试完成”“真机待验证”。
- `docs/roadmap.md`：补充 `0xCC 0x10`、大端 `int16 / 1000`、坐标映射和运行时能力识别决策。

`docs/architecture.md` 无需修改，现有“协议类输出归一化四元数，经 `MainActivity` 进入 UI”的边界已经覆盖本次接入。`README.md` 也无需修改，本次没有新增设备类别、入口或用户操作方式。

## 涉及文件

| 文件 | 计划改动 |
| --- | --- |
| `app/src/main/java/com/dctimer/util/QiyiCubeProtocol.java` | 姿态帧优先分流、CRC 校验、四元数解码、坐标转换、归一化和统一上报 |
| `app/src/test/java/com/dctimer/util/QiyiCubeProtocolTest.java` | 真实帧、轴向、归一化、CRC 与异常输入测试 |
| `docs/project.md` | 实施后更新完成度和真机状态 |
| `docs/roadmap.md` | 实施后收口协议与能力识别决策 |

预计只涉及 2 个生产/测试文件；另外 2 个长期文档仅在代码实施后更新。无需修改资源、多语言文案、数据库、Manifest、Gradle 配置或公共接口。

## 完成标准

代码接入完成需要同时满足：

- 真实 `0xCC 0x10` 样本可解析为方向正确、模长为 `1` 的四元数。
- 非法长度、错误 CRC、全零四元数不会触发统一姿态入口。
- 合法姿态不再触发 `protocolMismatchDetected`，也不阻断 hello 和状态同步。
- 现有 QiYi history 单元测试和新增姿态测试全部通过。
- 没有姿态通知的 QiYi 连接保持固定视角，不复用上一设备能力。
- `docs/project.md` 与 `docs/roadmap.md` 已按实际验证程度更新。

只有相应型号完成真机的三轴方向、页面交互和计时回归后，才能把该型号标记为“陀螺仪真机已验证”。

## 风险与回退

### 主要风险

- 不同 QiYi 型号或固件可能不发送 `0xCC 0x10`，或字段/轴向存在差异。
- 姿态可能在 hello 前高频到达，若错误修改 `helloReceived` 会破坏初始状态同步。
- 若只识别 `0xCC` 而不同时检查 `0x10`、CRC 和模长，可能吞掉未知协议帧或把损坏数据送入渲染层。
- 参考 Web 实现未归一化；实现时必须遵守当前 Android 架构约束，避免不同品牌向渲染层输出不一致的数据契约。

### 回退方式

本次不修改持久化数据、公共接口或用户设置。若接入出现回归，只需撤销 `QiyiCubeProtocol` 的姿态特判与解析方法，并移除对应测试，即可恢复当前 QiYi 转动和状态链路。

若解析和 CRC 正确但其他 QiYi 型号真机轴向仍有差异，仅调整 `QiyiCubeProtocol` 中的 `(ax, ay, az, aw)` 映射；不要修改 `SmartCube3DView` 或 `MainActivity`，避免影响已验证的 MoYu32 与 GAN 姿态。
