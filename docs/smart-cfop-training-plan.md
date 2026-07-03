# 智能魔方 3阶 CFOP 专项训练落地计划

日期：`2026-07-03`

分支：`codex/smart-cfop-training`

状态：已按计划完成代码落地；本地单元测试和 `assembleDebug` 已通过，真机待验证。

## 背景

旧分支 `codex/smart-training-followups` 已验证过一条可行方向：为智能魔方专项训练新增独立分类，并适配 `WCA / OLL / PLL / 顶层 / F2L` 等训练模式。但旧实现混入了较多兼容和兜底逻辑，本轮基于当前 `master` 重新实现，优先把分类语义、状态机边界、朝向规则和验收点提前收口。

本轮不再使用 `智能3阶` 作为一级展示文案。一级展示分组采用方法语义，首个分组命名为 `3阶 CFOP`，展示在普通 `3阶` 后面；后续可按同一结构继续增加 `3阶 Roux` 等方法分组。`3阶 CFOP` 只承载真正的专项训练，不再包含 `WCA` 子项；完整三阶智能魔方计时继续走现有普通三阶/WCA 主流程。

## 目标

- 新增 `3阶 CFOP` 专项训练分组，首批支持 `OLL训练`、`PLL训练`、`顶层训练`、`F2L训练`。
- 底层分类 ID 独立稳定，真实索引追加在末尾，避免移动现有分类索引。
- 打乱选择 UI 中通过展示顺序映射，把 `3阶 CFOP` 显示在普通 `3阶` 后面。
- `3阶 CFOP` 全部分项都使用独立训练朝向，默认 `黄顶绿前`。
- 专项训练在智能魔方链路下支持阶段完成停表，其中 `OLL / F2L` 不要求全魔方复原。
- 普通三阶、普通三阶子集、智能魔方完整复原主流程、蓝牙计时器链路不受影响。

## 非目标

- 本轮不实现 `3阶 Roux`，只保留可扩展的分组和代码边界。
- 不迁移历史成绩、历史分组或数据库结构。
- 不把普通 `3阶` 或 `3阶子集` 的现有 OLL/PLL/F2L 分类改成智能训练分类。
- 不重构 BLE 协议层，不改变设备识别、扫描、连接和协议分发。
- 不从旧分支直接 cherry-pick 代码；旧分支只作为需求和验收参考。

## 核心方案

采用“真实分类 ID 稳定追加，选择 UI 单独映射展示顺序”的方案。

真实分类顺序继续追加在末尾，例如当前新增分组真实 idx 为 `21`。业务层、偏好保存、分组 puzzle type、成绩记录、打乱生成都只使用真实 `scrambleIdx = idx << 5 | subIdx`。

展示层单独维护一级分组顺序，把真实 idx `21` 的 `3阶 CFOP` 展示在真实 idx `1` 的普通 `3阶` 后面。展示位置不能写入业务层，点击后必须转换回真实 idx。

```text
真实顺序：WCA, 2阶, 3阶, 4阶, ..., 连拧, 3阶 CFOP
展示顺序：WCA, 2阶, 3阶, 3阶 CFOP, 4阶, ..., 连拧
```

## 关键决策

1. `3阶 CFOP` 是独立一级分组，不复用普通 `3阶` 或 `3阶子集`。
   - 原因：普通专项打乱和智能魔方专项训练的起停、朝向、完成判定、保存语义不同，混用会让后续逻辑继续膨胀。

2. 真实分类 ID 追加到末尾，展示顺序通过 UI 映射调整。
   - 原因：避免影响旧索引、偏好、分组和历史成绩；同时让用户在打乱选择中更容易发现。

3. 训练朝向独立于解法重建朝向。
   - 原因：解法重建朝向服务成绩分析，训练朝向服务当前专项的展示、打乱和阶段判定，两者不应互相覆盖。

4. `3阶 CFOP` 的完成判定由训练模式决定。
   - `OLL训练`：OLL 完成即可停表，PLL 未完成也算完成。
   - `F2L训练`：F2L 完成即可停表，顶层未完成也算完成。
   - `PLL训练 / 顶层训练`：仍要求完整复原。

5. 专项训练完成后保留当前物理状态。
   - 原因：`OLL / F2L` 完成时魔方可能不是全复原状态，强行标记 solved 会破坏下一次专项训练的连续体验和内部状态。

## 代码落地点

### 分类与资源

- `app/src/main/res/values/arrays.xml`
- `app/src/main/res/values-zh/arrays.xml`
- `app/src/main/res/values-zh-rTW/arrays.xml`
  - `item_scr` 末尾追加 `3x3 CFOP` / `3阶 CFOP` / `3階 CFOP`。
  - 新增 `item_333_cfop` 子数组：`OLL训练`、`PLL训练`、`顶层训练`、`F2L训练`。
  - 三套语言资源必须保持数组数量和顺序一致。

- `app/src/main/java/com/dctimer/APP.java`
  - 读取偏好时允许新增真实 idx。
  - 新增 `smartCubeTrainingOrientation` 偏好，默认 `黄顶绿前` 对应的朝向索引。

- `app/src/main/java/com/dctimer/activity/MainActivity.java`
  - `subid` 末尾追加 `item_333_cfop`。
  - 智能设置区增加 `训练朝向` 设置项。

### 展示顺序映射

- `app/src/main/java/com/dctimer/activity/MainActivity.java`
  - 当前打乱选择弹窗直接用 `selectIdx + 1` 映射一级列表位置，需要替换为真实 idx 和展示位置互转。
  - 打开弹窗时：真实 `scrambleIdx >> 5` 转为展示位置，一级列表高亮展示位置。
  - 点击一级列表时：展示位置转真实 idx，再读取真实 idx 对应的二级数组。
  - 点击二级列表时：使用真实 idx 和真实 subIdx 组合 `scrambleIdx`。
  - `selectSession` 自动切换分组仍然比较真实 `scrambleIdx`。

- `app/src/main/java/com/dctimer/util/StringUtils.java`
  - `getScrambleName(idx, sub)` 继续按真实 idx 取名称。
  - 若映射 helper 放在工具层，必须只服务选择 UI，不改变业务层真实 idx。

### 训练模式模型

- 新增或更新 `app/src/main/java/com/dctimer/model/SmartCubeTraining.java`
  - 定义 `3阶 CFOP` 真实 group 和四个 sub mode。
  - 提供判断：是否为 `3阶 CFOP`、是否为阶段完成模式。
  - 提供完成判定：`OLL / F2L / solved` 三类判定。
  - 完成判定只消费 facelet 状态和训练模式，不接触 BLE 协议。

### 打乱生成

- `app/src/main/java/scrambler/Scrambler.java`
  - `defaultLength` 末尾增加 `3阶 CFOP` 行。
  - 新增 `3阶 CFOP` 打乱生成分支：
    - `OLL训练`：顶层朝向训练状态。
    - `PLL训练`：PLL 状态。
    - `顶层训练`：顶层训练状态。
    - `F2L训练`：Cross 已完成、F2L 待训练状态。
  - 所有 `3阶 CFOP` 子项都按训练朝向生成或转换打乱，使用户看到的步骤和虚拟魔方朝向一致。
  - `getCubeState()` 和打乱详情图逻辑需要把 `3阶 CFOP` 视作可生成 3x3 状态图的分类。

### 朝向与显示

- `app/src/main/java/com/dctimer/util/Utils.java`
  - 复用并补齐 facelet 朝向转换、反向转换、move 朝向转换、反向 move 转换。
  - 朝向转换必须可 round-trip，不能只靠面映射处理 move，因为不同朝向下顺逆时针可能变化。

- `app/src/main/java/com/dctimer/activity/MainActivity.java`
  - 计时页虚拟魔方在 `3阶 CFOP` 训练中显示训练朝向后的状态。
  - 打乱步骤、下一步高亮、纠错步骤显示训练朝向后的 move 文案。
  - 内部 `SmartCube` 仍保存物理状态和物理 move，不保存展示态。

- `app/src/main/java/com/dctimer/dialog/CubeStateDialog.java`
  - 状态弹窗展示和动画也使用同一显示转换入口。

### 智能魔方状态机

- `app/src/main/java/com/dctimer/model/SmartCube.java`
  - 保持设备模型不理解具体训练模式。
  - 如需扩展完成判定，使用外部 completion checker 或等价的业务层回调，避免在 `SmartCube` 内硬编码 `OLL / F2L`。
  - 增加“清空本次解法追踪但保留当前 cubeState”的能力，用于阶段训练完成后进入下一轮。

- `app/src/main/java/com/dctimer/activity/MainActivity.java`
  - 普通智能魔方流程仍按目标打乱状态触发 `onScrambled`。
  - `3阶 CFOP` 训练从当前物理状态执行专项打乱，打乱序列完成后进入绿色 `READY`。
  - 打乱完成那一步不能直接起表，需要跳过当前 move 的起表触发。
  - 首个真实解法转动起表。
  - `OLL / F2L` 阶段完成时停表并保存成绩，然后只重置本次 solve tracking，不强制 `markSolved()`。

### 成绩保存与统计

- 继续复用现有智能魔方成绩保存链路，保存 `moves` 和可空 `solve_meta`。
- 专项训练的步序统计必须从首个真实解法转动开始，不混入专项打乱步骤。
- `OLL / F2L` 的重建展示可先按现有保存字段落库；若重建分段对阶段训练无意义，本轮不额外扩展数据库字段。

## 实施步骤

1. 已完成：增加 `3阶 CFOP` 资源、真实分类行和偏好边界。
2. 已完成：增加打乱选择 UI 的真实 idx 和展示位置映射，只改选择弹窗入口。
3. 已完成：新增 `SmartCubeTraining` 模型和单元测试，锁定模式识别、默认训练朝向、完成判定。
4. 已完成：补齐朝向工具和单元测试，验证 facelet 和 move 双向转换。
5. 已完成：增加 `3阶 CFOP` 打乱生成，并验证四个子模式均可生成非空打乱和 3x3 状态图。
6. 已完成：接入智能魔方打乱进度和 READY 逻辑，专项打乱完成后跳过当前 move 的起表触发。
7. 已完成：接入训练完成判定和阶段完成后的 solve tracking 清理。
8. 已完成：接入训练朝向在计时页、状态弹窗、打乱文本和动画中的显示转换。
9. 已完成：补齐文档状态说明和待真机验证标记。

## 测试计划

### 单元测试

- `SmartCubeTrainingTest`
  - `3阶 CFOP` group/sub mode 识别。
  - 默认训练朝向为 `黄顶绿前`。
  - `OLL` 在 OLL 完成、PLL 未完成时判定完成。
  - `F2L` 在 F2L 完成、顶层未完成时判定完成。
  - `PLL / 顶层` 不接受未完整复原状态。

- `SmartCubeOrientationTest`
  - 默认朝向不改变 facelet 和 move。
  - 所有训练朝向下 facelet 转换可 round-trip。
  - 所有训练朝向下 move 转换可 round-trip。
  - 转换后的 move 能匹配转换后的 facelet 状态变化。

- `Smart333CfopScramblerTest`
  - 四个子模式都能生成非空打乱。
  - 分类被识别为 3x3 状态图可展示分类。

- `ScrambleGroupDisplayOrderTest` 或等价测试
  - `3阶 CFOP` 真实 idx 保持末尾。
  - 展示顺序位于普通 `3阶` 后、`4阶` 前。
  - 展示位置和真实 idx 双向映射正确。

- `SmartCubeResultTest`
  - 阶段训练完成后清理 solve tracking 但保留当前 cubeState。
  - 计时结果只统计解法阶段 move。

### 命令验证

代码实现完成后执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

本任务属于跨资源、模型、打乱和智能魔方状态机的大改动，完成实现后需要执行编译验证。

当前验证记录：

- `.\gradlew.bat testDebugUnitTest`：已通过。
- `.\gradlew.bat assembleDebug`：已通过。

### 手动验收

- 打乱选择弹窗中 `3阶 CFOP` 显示在 `3阶` 后、`4阶` 前。
- 选择 `3阶 CFOP - OLL训练` 后，按钮标题显示该真实分类名称；关闭重开弹窗仍正确高亮。
- 切换 session 或自动选择分组时，仍按真实 `scrambleIdx` 匹配。
- 普通 `3阶`、`3阶子集` 原有分类顺序和行为不变。
- 智能魔方连接后，`3阶 CFOP` 训练按训练朝向展示打乱、虚拟魔方和状态弹窗。
- 完成专项打乱后进入绿色 `READY`，完成打乱的最后一步不启动计时。
- `OLL训练` 完成 OLL 即停表，PLL 未完成也保存成绩。
- `F2L训练` 完成 F2L 即停表，顶层未完成也保存成绩。
- `PLL训练 / 顶层训练` 必须完整复原才停表。
- 完成一次 `OLL / F2L` 后继续生成下一条专项训练，不要求手动重置为 solved。
- 蓝牙计时器模式不显示智能魔方专项状态，也不触发训练完成判定。

## 风险与防偏

- 风险：展示顺序映射泄漏到业务层，导致保存和历史分组错乱。
  - 防护：映射 helper 只在打乱选择 UI 使用；所有持久化仍写真实 `scrambleIdx`；增加映射单元测试。

- 风险：训练朝向下 move 顺逆方向错误。
  - 防护：使用 facelet 状态变化验证 move 映射，而不是只做面名替换；增加 round-trip 测试。

- 风险：专项训练从非 solved 状态继续时，打乱进度判断错误。
  - 防护：`3阶 CFOP` 训练以当前物理状态作为本次专项打乱起点，按实际 move 序列推进，不假设起点是 solved。

- 风险：阶段完成后误调用 `markSolved()`，破坏当前物理状态。
  - 防护：阶段训练完成后只清理 solve tracking；单元测试覆盖保留 cubeState。

- 风险：普通三阶和智能三阶语义混淆。
  - 防护：不复用普通 `3阶` 子模式；`3阶 CFOP` 作为独立真实分类存在。

## 回滚

本计划不包含数据库迁移。若实现后需要回滚，直接回退相关代码和资源改动即可。若用户在新版本选择过 `3阶 CFOP` 后降级，旧版本读取到超出范围的 `sel` 会按现有偏好边界回退到默认三阶分类。

## 待真机验证

- MoYu32、QiYi / Tornado V4、GAN v2/v3/v4 在 `OLL / F2L` 阶段完成停表上的一致性。
- 训练朝向下状态弹窗、计时页 3D 预览和实际物理转动方向一致性。
- 连续多条专项训练从非 solved 状态接续时，打乱进度、READY 和首转起表是否稳定。
