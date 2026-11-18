# 传送移除玩家时的 Int2ObjectMap NPE 修复

## 原因

用户提供的 `crash-2026-11-17_22.21.28-server.txt` 显示主线程在传送流程里崩溃：

```text
java.lang.NullPointerException: Cannot invoke "it.unimi.dsi.fastutil.ints.IntArrayList.getInt(int)"
because "this.wrapped" is null
    at it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap$MapIterator.nextEntry
    at net.minecraft.server.level.ChunkMap.removeEntity
    at net.minecraft.server.level.ServerChunkCache.removeEntity
    at net.minecraft.server.level.ServerLevel$EntityCallbacks.onTrackingEnd
    ...
    at net.minecraft.server.level.ServerPlayer.changeDimension
    at io.wifi.habitrainsurvive.world.SafeTeleportService.position
```

`ChunkMap.removeEntity` 在 `entity instanceof ServerPlayer` 分支里遍历 `entityMap.values()`，对每个已追踪实体调用 `TrackedEntity.removePlayer(player)`。这条回调链会重新进入实体移除流程（同一次 `teleportTo` 引起的载具/乘客级联移除、Architectury 的实体回调等），从而在迭代过程中修改 `entityMap`。

fastutil 的 `values()` 迭代器不是 fail-fast 的：表被外部 `remove`/`put` 改动（可能触发 rehash）后，迭代器内部的 `wrapped` 会被置为 `null`，但迭代器的 `pos` 仍在有效范围内，于是下一次 `next()` 直接抛 NPE。已用最小复现（`Int2ObjectOpenHashMap` 迭代中外部 remove/put）确认异常文本与崩溃报告完全一致。

## 改动

新增 `src/main/java/io/wifi/habitrainsurvive/mixin/ChunkMapRemoveEntityMixin.java`：

- 只对 `ChunkMap.removeEntity` 中那一次 `Int2ObjectMap.values()` 调用做 `@Redirect`，返回 `new ObjectArrayList<>(tracked.values())` 快照，`remap = false`。
- 不改移除顺序、不吞异常、不改变 `TrackedEntity.removePlayer` 的调用次数；`addEntity`、`tick` 等其它 `values()` 调用完全不动。
- 已注册到 `habi_train_survive.mixins.json`，沿用 `defaultRequire: 1`，注入点不存在会在启动时直接报错，而不是静默失效。

同一文件内另有一个既有先例 `DistanceManagerMixin`（对 `DistanceManager.removePlayer` 缺失 bucket 做防御性 Redirect），本次沿用相同写法。

## 验证

命令：`./gradlew test --offline --console=plain`。

- 296 项测试全部通过（8 项既有跳过为 Windows 符号链接权限限制），含任务在线编辑的新增用例。
- 隔离 Fabric 1.21.1 开发映射服实际启动：Mixin 子系统加载成功，无 `defaultRequire` 注入失败报错；模组 16 大子系统数据全部加载完成，`Done (4.820s)!`。
- 同一隔离服实测：`/habi reloadall` 输出全量重载成功信息；`/quest web` 启动监听并输出带令牌地址；`/quest web status` 返回运行中地址；未带令牌请求 `/` 与 `/api/quests` 均返回 401；`/quest web stop` 正常关闭。
- 反编译开发映射 `ChunkMap` 确认 `removeEntity` 中目标调用真实存在（`Int2ObjectMap.values()` 仅一处，位于 `ServerPlayer` 分支）。

未验证：生产存档 + C2ME alpha 等完整模组组合下的长期表现；本次隔离服未安装 C2ME。修复只消除“迭代中被外部修改导致 NPE”这一条路径，其它传送相关问题需单独定位。

## 更新

产物：`build/libs/habi-train-survive-1.0.0.jar`。停服后备份原 JAR，替换同名新包并完整重启；无需修改存档或配置。
