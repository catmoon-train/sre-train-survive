# 任务编辑指南（每日 / 每周 / 章节）

面向服主与维护者：用 `/quest web` 临时网页编辑器或 `/quest editor` 离线 HTML 修改任务定义，由服务器校验后写回 `config/habi_train_survive/quests.json`。纯服务端，不涉及客户端模组。

## 何时使用

- 新增或修改每日/每周任务模板、随机刷新数量。
- 新建章节、章节内任务，设置网格 X/Y、隐藏、前置依赖与奖励。
- 在线保存被拒（重复 ID、前置循环、刷新数超过模板数、未知触发器）需要定位。
- 迁移服务器后恢复内置 25 每日 + 25 每周模板。

不用于：修玩家进度（用 `quest_progress.json` / `/quest reset`），也不用于 VirtualProxy 金币兑换。

## 操作步骤

1. 确认任务系统已加载（需要 OP 2 或 `habi.admin`）：

   ```text
   /quest status
   ```

   看模板数量与 `错误:`；有错误先修文件，别继续编辑。

2. 启动临时网页编辑器（推荐，保存即校验生效）：

   ```text
   /quest web
   /quest web 11088
   ```

   输出形如 `http://<IP>:11088/?token=<一次性令牌>`。端口/监听地址在 `config/habi_train_survive/quest_web_editor.json`（默认 `0.0.0.0:11088`）。令牌每次启动更换，旧链接立即 401。

3. 浏览器打开链接。左侧“章节”可新建/复制/删除章节；进入章节后“新建任务”，可编辑图标、触发器、目标、数量、奖励、网格 X/Y、隐藏与前置依赖；保存写回 `chapters[].quests[]`。

4. 保存前看右侧“问题”面板：错误阻止保存/导出，警告允许。常见错误：ID 重复、标题为空、无目标或无奖励、未知触发器/奖励类型、前置成环、刷新数超过模板数或超过 45、坐标超出 0..64、物品数超过 2304、经验超过 1000000、金币超过 1e12。

5. 在线保存点“保存到服务器”：服务端整份校验 → 原子写 `quests.json` → 切换运行中定义；失败只返回原因，磁盘与内存保持旧版。

   离线方式（不开端口）：

   ```text
   /quest editor
   ```

   打开输出的 `config/habi_train_survive/quest_editor.html`，编辑后“导出 quests.json”，覆盖同目录文件，再执行：

   ```text
   /quest reload
   ```

6. 关闭在线编辑器（服务器停止会自动关闭）：

   ```text
   /quest web stop
   /quest web status
   ```

## 常见坑

- 旧 JAR 的页面可能没有章节编辑。先确认 jar 内 `habi_train_survive/quest_editor_template.html` 与源码一致，再怀疑页面本身。
- `quest_web_editor.json` 默认监听 `0.0.0.0`，记得防火墙限制；带 token 的链接不要发群。
- 关闭/重启编辑器后旧 token 失效，必须用新链接。
- 在线页面不恢复 localStorage 草稿；离线页面会恢复，若数据与服务器不一致，先点“放弃本地草稿，重新载入服务器数据”。
- 新建章节暂无任务只是警告；每个任务必须至少一个目标与一个奖励。
- 目标写具体 ID、`#标签`（如 `#minecraft:logs`）或 `*`；离线无法验证标签是否存在。
- 每日/每周忽略 `requires`/`x`/`y`/`hidden`；只有章节任务使用这些字段。
- 金币奖励服务器允许 0/double，编辑器只接受正整数，极端数据会被判错。
- 保存成功会立刻替换运行中定义；先备份 `quests.json`，别把 `quest_progress.json` 当定义覆盖。

## 验证

```bash
./gradlew :test --tests 'io.wifi.habitrainsurvive.quest.*' build --console=plain
```

通过标准：任务测试全绿、构建成功；游戏内 `/quest status` 为预期数量且无错误；`/quest` 章节页能看到新章节与任务；`/quest web` 保存后 `quests.json` 的 `chapters` 与编辑一致。
