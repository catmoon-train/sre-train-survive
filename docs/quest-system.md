# 服务端任务系统（每日 / 每周 / 章节）

纯服务端实现，不依赖客户端模组；界面使用箱子菜单。

## 玩法

- **每日任务**：从 25 个每日模板中按玩家随机抽 5 个，北京时间每天 0 点刷新。
- **每周任务**：从 25 个每周模板中随机抽 5 个，每周一 0 点刷新。
- **章节任务**：类似 FTB Quests 的任务树，完成前置后永久解锁，进度与领取状态永久保存。
- 奖励支持金币、物品、经验，数量全部可在编辑器中修改。
- 玩家手动领取奖励，避免离线补发与重复领取。

## 命令

```text
/quest                     打开任务界面（别名 /quests、/mission）
/quest submit <任务ID>     提交物资类任务所需物品
/quest reload              管理员：重载 quests.json（校验失败时保留旧定义）
/quest editor              管理员：生成带当前任务与注册表的 quest_editor.html
/quest web                 管理员：启动在线任务编辑器（默认端口 11088）
/quest web <端口>          管理员：用指定端口启动在线编辑器
/quest web start           管理员：同 /quest web
/quest web status          管理员：查看在线编辑器地址与端口
/quest web stop            管理员：关闭在线编辑器
/quest reset <玩家>        管理员：清空该玩家的每日/每周/章节进度
/quest status              管理员：查看模板数量与错误
```

## 文件

| 文件 | 用途 |
| --- | --- |
| `config/habi_train_survive/quests.json` | 任务定义，编辑器导出后覆盖它 |
| `config/habi_train_survive/quest_progress.json` | 玩家进度、抽取结果、领取记录 |
| `config/habi_train_survive/quest_editor.html` | `/quest editor` 生成的离线编辑器 |
| `config/habi_train_survive/quest_web_editor.json` | 在线编辑器配置（`bindAddress`、`port`），缺失或损坏时回退默认值 |
| `quests_legacy_backup.json` | 首次升级时旧版任务进度自动备份 |

首次启动若没有 `quests.json`，会从模组内置 `defaults/quests.json` 写入 25 每日 + 25 每周 + 2 个示例章节。编辑过的文件不会被内置模板覆盖。

## quests.json 结构

```json
{
  "settings": { "dailyCount": 5, "weeklyCount": 5, "timeZone": "Asia/Shanghai" },
  "daily": [
    {
      "id": "daily_mine_stone",
      "title": "采石工",
      "description": "挖掘 128 个石头",
      "icon": "minecraft:stone_pickaxe",
      "tasks": [
        { "type": "mine_block", "target": "minecraft:stone", "amount": 128, "display": "石头" }
      ],
      "rewards": [
        { "type": "coin", "amount": 250 },
        { "type": "xp", "amount": 20 },
        { "type": "item", "item": "minecraft:bread", "count": 4 }
      ]
    }
  ],
  "weekly": [],
  "chapters": [
    {
      "id": "chapter_survival",
      "title": "生存入门",
      "description": "……",
      "icon": "minecraft:grass_block",
      "quests": [
        {
          "id": "ch_wood",
          "title": "第一块木头",
          "icon": "minecraft:oak_log",
          "x": 0, "y": 0,
          "requires": [],
          "hidden": false,
          "tasks": [{ "type": "mine_block", "target": "#minecraft:logs", "amount": 16 }],
          "rewards": [{ "type": "coin", "amount": 200 }, { "type": "xp", "amount": 20 }]
        }
      ]
    }
  ]
}
```

- `id` 全局唯一，允许字母、数字与 `_ . -`。重复、缺标题、无目标、无奖励、未知触发器、前置循环都会导致整份文件被拒绝，服务器继续使用上一次的有效定义。
- 每日/每周不使用 `requires`、`x`、`y`、`hidden`，写了也会被忽略。
- `target` 支持具体注册表 id、`#标签`（例如 `#minecraft:logs`）、`*`（任意）。
- `icon` 是物品 id，无效时界面回退为纸。

## 触发器

| type | 目标 | 说明 |
| --- | --- | --- |
| `kill_entity` | 实体 / 标签 / `*` | 玩家击杀生物 |
| `mine_block` | 方块 / 标签 / `*` | 玩家破坏方块（非创造破坏也算） |
| `place_block` | 方块 / 标签 / `*` | 仅统计原版成功放置 |
| `obtain_item` | 物品 / 标签 / `*` | 拾取掉落物与合成产物 |
| `craft_item` | 物品 / 标签 / `*` | 从合成结果槽取走物品 |
| `use_item` | 物品 / 标签 / `*` | 主手使用物品 |
| `submit_item` | 物品 / 标签 / `*` | `/quest submit` 时从背包扣除并计数 |
| `online_time` | 无 | 每秒累计，数量单位为秒 |

扩展新触发器：调用 `QuestTrigger.register(new QuestTrigger.Type("dungeon_clear", "通关副本", TargetKind.NONE, "无需目标"))`，它就会出现在编辑器下拉框中；然后在 `QuestManager` 中把对应事件转发到 `event(player, "dungeon_clear", "", 1)`。未知类型会被校验拒绝。

已知边界：

- 拾取只在物品实体整堆被收走时计数；背包已满导致的失败拾取不计数。
- `/give`、商店直接写入背包不触发 `obtain_item`。
- 提交类任务必须输入 `/quest submit`，界面会提示命令；未提交不会自动完成。

## 编辑流程

1. 游戏内执行 `/quest editor`，控制台会输出绝对路径。
2. 用浏览器打开 `config/habi_train_survive/quest_editor.html`。它已内嵌当前任务、物品/方块/实体注册表与触发器列表，完全离线。
3. 编辑每日、每周与章节任务：章节可新建/复制/删除，章节内任务可设置图标、目标、奖励、网格 X/Y、隐藏和前置依赖；问题面板会提示重复 id、无效注册表引用、前置循环、刷新数量超过模板数量等。
4. 点击导出下载 `quests.json`，覆盖服务器同目录文件（编辑期间会自动保存草稿到浏览器本地）。
5. 回到游戏执行 `/quest reload`；如果校验失败会给出具体原因，旧定义继续生效。

编辑器是单文件，不含外部 CDN、字体或网络请求，可直接拷贝到别的电脑使用；再次执行 `/quest editor` 会用当前服务器数据重新生成。

## 在线编辑（临时网页编辑器）

离线编辑器需要“下载 → 覆盖文件 → `/quest reload`”三步；在线编辑把这三步合成一次保存，适合服主在远端随时改任务。

1. 游戏内执行 `/quest web`（或 `/quest web 11090` 指定端口）。控制台与聊天栏会输出形如
   `http://<服务器IP>:11088/?token=xxxxxxxx` 的地址。
2. 浏览器打开该地址。页面与离线编辑器完全一致（含每日、每周与章节任务编辑），只是多出“保存到服务器”按钮，并跳过 localStorage 草稿恢复。
3. 章节编辑：左侧“章节”可直接新建/复制/删除章节；进入章节后可新建章节任务，设置图标、目标、奖励、网格 X/Y、隐藏与前置依赖。保存时 `chapters` 会整份写回。
4. 点击“保存到服务器”：服务端先整份校验，通过后原子写入 `quests.json` 并立即切换运行中的任务定义；校验失败只返回错误原因，磁盘与内存都不变，无需再执行 `/quest reload`。
5. `/quest web status` 查看当前地址与端口，`/quest web stop` 关闭监听。

安全与实现要点：

- 每次启动生成 16 字节随机令牌，页面与全部接口（`/`, `/api/quests`, `/api/bundle`, `/api/close`）都校验令牌，可用 `X-Quest-Token` 头或 `?token=` 传入。
- 默认绑定 `0.0.0.0`、端口 `11088`；绑定地址与端口写入 `quest_web_editor.json`，改动后下次 `/quest web` 生效。
- 请求体上限 4 MiB；所有 Minecraft 侧操作都投递回主线程执行，不在 HTTP 线程碰世界与注册表。
- 服务器关闭（`SERVER_STOPPING`）时自动停止监听，不会残留端口占用。
