# GAN 智能魔方重置协议

更新日期：2026-07-25

## 目的

GAN 智能魔方的“重置魔方”需要同时更新 App 与实体魔方。

目前的重置流程分成两部分：

1. App 清除本地的转动缓冲区、history 请求状态与 move counter。
2. 根据 GAN protocol generation，向实体魔方写入“完成状态”数据。

实现入口是 [`GanCubeProtocol.onLocalCubeReset()`](../app/src/main/java/com/dctimer/util/GanCubeProtocol.java)，由 [`CubeStateDialog`](../app/src/main/java/com/dctimer/dialog/CubeStateDialog.java) 的重置流程调用。

## 适用范围

当前实现涵盖：

- GAN v2
- GAN v3
- GAN v4

这里的 v2/v3/v4 是根据 BLE service、characteristic 与数据格式判断出的 GAN protocol generation，不一定等同于魔方产品名称或固件版本。

## 数据发送流程

重置数据不是直接以明文写入 BLE characteristic。`GanCubeProtocol` 会把数据放入现有的 request queue，再交由共用的 `GanCipher` 编码后写入对应 generation 的 write characteristic：

```text
reset payload
    -> requestQueue
    -> GanCipher.encode()
    -> GAN write characteristic
```

下面列出的 bytes 是协议 payload，而不是 BLE trace 中最终看到的加密 bytes。实际发送时仍会使用当前连接建立的 AES key 与 salt。

## 重置 payload

### GAN v2

v2 使用 20 bytes payload：

```text
0A 05 39 77 00 00 01 23 45 67 89 AB 00 00 00 00 00 00 00 00
```

### GAN v3

v3 使用 16 bytes payload：

```text
68 05 05 39 77 00 00 01 23 45 67 89 AB 00 00 00
```

### GAN v4

v4 使用 20 bytes payload：

```text
D2 0D 05 39 77 00 00 01 23 45 67 89 AB 00 00 00 00 00 00 00
```

## 字段解读

目前可以较可靠确认的部分如下：

| 字段 | 说明 |
| --- | --- |
| generation-specific prefix | v2、v3、v4 各自的包头与长度格式不同。 |
| `05` | 重置／写入逻辑魔方状态所使用的 command type。 |
| `39 77` | GAN 逻辑魔方数据中的固定识别／magic bytes。 |
| `00 00 01 23 45 67 89 AB` | 当前写入的完成魔方数据区段，对应 solved cube 的固定排列数据。 |
| trailing `00` | 根据 generation 补足数据长度的保留字段。 |

字段语义以当前实现和实际通信结果为准。

## 本地状态同步

发送数据前，App 会清除下列本地状态：

- `moveBuffer`
- `historyEstimateLocQueue`
- `prevMoveCnt`
- `currentMoveCnt`
- 设备 timestamp 与本地接收时间基准

这些状态会在本地重置后重新等待实体魔方的状态同步。

目前 `onLocalCubeReset(String cubeState)` 的 `cubeState` 参数只代表上层 UI 的状态通知；GAN reset payload 仍然固定写入 solved state，并不会把任意 facelet 字符串编码成 GAN reset packet。

## 资料说明

本文按公开协议资料、社区兼容性实现以及本项目的实际通信结果整理，属于本项目的兼容性实现说明。

## 验证记录

目前已在 GAN i4 和 GAN iC4 上完成 GAN v4 reset 协议验证。

验证步骤：

1. 将魔方调整为六面完成状态。
2. 故意再转动一步，使实体魔方偏离完成状态。
3. 在本 App 中执行“重置魔方”。
4. 结束本 App 后，再将实体魔方转回六面完成状态。
5. 打开官方 CubeStation，连接该魔方并查看状态。

验证结果：CubeStation 显示的状态正好与六面完成状态相差一步。这个结果符合 reset 时写入逻辑完成状态、而实体转动仍然保留在硬件记录中的情况，说明重置数据确实已经写入实体魔方，而不只是修改了本 App 内的虚拟状态。

目前尚未对 GAN v2、GAN v3 以及其他固件版本完成同等实机验证。

## 相关代码

- [`GanCubeProtocol.java`](../app/src/main/java/com/dctimer/util/GanCubeProtocol.java)
- [`SmartCubeProtocol.java`](../app/src/main/java/com/dctimer/util/SmartCubeProtocol.java)
- [`CubeStateDialog.java`](../app/src/main/java/com/dctimer/dialog/CubeStateDialog.java)
