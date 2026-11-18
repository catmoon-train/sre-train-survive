# 服务端运营套件手册

适用：Minecraft **1.21.1 / Fabric Loader 0.19.3 / Fabric API 0.116.13+1.21.1 / Java 21**。本手册以当前 `world`、`hub`、`menu`、`onboarding`、`presentation` 源码及内置默认配置为准；原有业务系统说明仍见 [README](../README.md)。菜单使用原版容器与数据包，**玩家不必安装本模组或客户端模组**。

> **当前精简规则：** 本核心默认只有永久主世界、下界、末地和主城四个维度定义。资源维度从默认配置、旧 `worlds.json` 条目和生成的数据包中直接移除，不再保留禁用世界条目；旧资源坐标禁止使用，旧地图区块不自动删除。SRE 已移除八个训练维度，本核心不再维护训练维度过滤。主城 `/rtp` 前往永久主世界；已有中断的重置事务仅保留安全恢复处理，不恢复维度注册。替换 JAR 后须完整重启；本次仅编译打包，未运行测试服。

> 本次交付没有向生产服务器部署。请先在隔离测试服验证自己的存档、权限和模组组合；构建通过不等于真实客户端完整验收。当前验证边界见第 9 节。

## 1. 安装、升级与备份

1. 停服，确认 Java 21，并准备上述版本的 Fabric 服务端及 Fabric API；将发行用模组 JAR 放入 `mods/`，升级时移走旧版同模组 JAR，避免重复加载。
2. **首次安装到旧服或每次升级前做停服一致性快照**：整个实际存档目录（`server.properties` 的 `level-name`）、整个 `config/habi_train_survive/`、`mods/`、服务端启动参数及 `server.properties`。不能只备份 `region/`；玩家数据、所有维度、`data/`、经济/礼物/领地等业务状态必须配套保存。备份另存，先在副本验证可恢复。
3. 首次启动只生成原版三世界与独立主城的默认配置，升级时自动删除旧资源维度配置条目。其它已有配置不覆盖；按需人工合并菜单/公告文字，不要删除玩家状态文件。
4. 用 `/habi status`、`/worldadmin list` 和日志检查加载、开放、代次、主城完成状态，再测试普通玩家权限。隔离原版已有存档升级及旧业务 JSON 保留已实测通过；自己的生产存档和模组组合仍须先在副本验证。

本文路径均相对于服务端运行目录；`<存档>/` 表示实际存档根目录，不固定为 `world/`。文件编辑使用 UTF-8 JSON；状态文件必须停服后才可进行有依据的修复。

## 2. 默认四世界

| 世界 ID | 默认用途/生成预设 | 运营要点 |
| --- | --- | --- |
| `minecraft:overworld` | 生存世界 / `normal` | 永久资产、领地、城镇、商店 |
| `minecraft:the_nether` | 生存下界 / `nether` | 原版永久下界 |
| `minecraft:the_end` | 生存末地 / `end` | 原版永久末地 |
| `habi_train_survive:hub` | 主城大厅 / `void`，`kind=hub` | 独立大厅；不可关闭、停用或作为资源世界重置 |

当前仅原版三世界与主城默认启用和开放，进入费0、不限人数、无额外权限；资源世界不再创建、进入或周期重置，SRE 的本地训练场在其独立单机存档的主世界运行，不由本核心接管。下文资源重置/导入事务说明保留作历史恢复参考，不代表当前允许重新开启资源世界。

**重要限制：原版三世界保留原版共享 gamerules、天气、时间、难度及边界行为，本套件不把它们改成三个独立规则域。** `mobs`、`mobGrief`、`weather`、`fixedTime`、`difficulty`、`borderSize` 的独立配置只适用于 `habi_train_survive:...` 托管维度。现在用 `/worldadmin rule` 给原版世界设置这六项会被拒绝；JSON 中给原版项填非默认独立值也会校验失败。原版项中的默认字段是兼容占位，不会把原版真实边界强制设成 20000 或把难度强制改成 normal；请用原版命令维护其原版规则。

`pvp`、建造/交互/伤害、饥饿、爆炸、火蔓延、流体、活塞、领地/商店准入等套件保护策略可作用于两类世界，**饥饿开关也不局限于大厅**。世界级限制先于领地/城镇授权，领地授权不能突破世界禁令；建造/交互维护绕过权限为 `habi.hub.build`（OP 2 级也具备权限绕过），并非所有规则的万能绕过。

## 3. 玩家入口与默认完整菜单路由

| 指令 | 用途/权限 |
| --- | --- |
| `/menu [id]`、`/cd [id]` | 打开默认 `main` 或指定页面；根命令公开，仍校验页面/按钮/动作权限 |
| `/world`、`/worlds` | 玩家打开 `worlds` 页面；控制台则列出世界 |
| `/world <世界ID>`、`/worlds <世界ID>` | 前往世界出生点，重新检查进入条件；建议始终使用完整 ID |
| `/hub`、`/lobby`、`/spawn` | 返回服务器出生点；保留旧 `/setspawn` 优先语义，见第 5 节。`hub/lobby` 使用 `habi.cmi.spawn`、注册回退等级 0，默认公开 |
| `/rtp [世界ID]` | 安全随机传送；省略 ID 时为当前世界，大厅中则选资源主世界；大厅/void 不可作为 RTP 目的地。根命令公开，默认菜单 RTP 按钮另要求 `habi.cmi.rtp` |
| `/guide`、`/rules` | 新手指南与规则，所有玩家可查看 |
| `/settings` | 所有玩家可打开个人设置 GUI；`/settings sidebar`、`/settings announcements`、`/settings sounds` 分别切换对应偏好 |

默认也可**潜行 + 交换副手（通常 Shift+F）**打开菜单，`menus.json` 的 `sneakSwapEnabled` 可关闭。界面图标不是可取走物品；列表支持翻页、返回、关闭。以下是随包默认路由，旧服自己的 `menus.json` 不会被覆盖：

| `main` 按钮 | 目标 |
| --- | --- |
| 世界导航 / 返回主城 | `worlds` 动态列表 / 玩家执行 `hub` |
| 生存随机传送 / 资源随机传送 | RTP 至 `minecraft:overworld` / `habi_train_survive:resource`（按钮要求 `habi.cmi.rtp`） |
| 我的家 / 公共地标 | `homes` → `home <名称>` / `warps` → `warp <名称>`（页面和按钮分别要求 `habi.cmi.home` / `habi.cmi.warp`） |
| 领地管理 / 城镇导航 | `claim` / `towns` → `town spawn <名称>` |
| 我的余额 / 系统商城 / 拍卖行 | `bal` / `shop` / `ah` |
| 安全交易 | `players` → 对当前仍在线、可见玩家执行 `trade <真实账户名或UUID>`；不使用昵称 |
| 私人礼盒 / 礼包中心 | `gift` / `kits` |
| 每日签到 / 任务中心 | `daily`（按钮要求 `habi.cmi.daily`）/ `quest` |
| 幸运抽奖 / 称号中心 / 墓碑找回 | `lottery` / `title` / `grave` |
| 新手指南 / 服务器规则 / 个人设置 | `guide` / `rules` / `settings` |
| 管理中心 | `admin`（要求 `habi.admin`）：`habi status`、`worldadmin list`、浏览 `worlds`、`menu reload`、`habi reload`、返回/关闭 |
| 欢迎信息 / 关闭 | 发送菜单使用提示 / 关闭容器 |

`worlds` 显示加载/开放、人数、费用、权限；`homes/warps/towns/players` 来自原有业务数据。**导航不提权**：进入 `admin` 不代表获得 `worldadmin` 权限，点击 `menu reload` 仍需原版 OP 2 级。现有商店、签到、礼包、交易等继续使用各自业务规则，不是菜单重做了一套经济或奖励系统。

### 传送与费用

> 最新安全搜索与定点传送修复：RTP、回家/返回/地标等读条定点传送及内部纠正均先异步准备区块，再由主线程复查并提交；安全检查拒绝未加载区块。每次最多 4 个加载请求、每个准备 3×3 区块，总超时为 1200 实际 tick 或 60 秒。成功提示和费用结算只在实际成功后执行。详见 [两次 Watchdog 与异步传送修复](ASYNC_TELEPORT_FIX.md)。

传送复用读条、战斗、冻结、监禁等限制；移动、受伤、取消或离线会中止相关读条/搜索。安全搜索分批加载区块，有次数和超时上限，拒绝无支撑、碰撞、危险方块、液体及越界落点，失败可重新尝试或请管理员设置安全出生点。

进入时重新检查世界已加载/启用/开放、进入权限、人数上限、余额和资源代次。RTP **成功后才记冷却、扣 RTP 费用**；跨世界进入费也只在成功跨界后扣，同世界移动不收进入费。传送失败不消耗本次费用/冷却。`habi.world.admin` 可绕过常规进入权限、开放/人数/进入费限制，但不能进入未加载或待重置世界，也不绕过战斗/监禁/冻结限制；RTP 冷却的 OP 豁免仍以原版 OP 2 级判定。

## 4. 管理命令、配置生效时机

权限节点由现有权限系统管理，原版 OP 2 级可满足下表带节点的管理命令；节点授权不等于授予原版 OP。控制台可执行不依赖玩家位置/界面的命令。

| 完整语法 | 权限 | 生效/注意事项 |
| --- | --- | --- |
| `/habi` | `habi.admin` | 玩家打开管理菜单；控制台列出世界 |
| `/habi status` | `habi.admin` | 世界加载/开放/代次、待重置/待重启登记变更、主城建造与重载错误；修复并成功重载后清除对应错误 |
| `/habi reload` | `habi.admin` | 重载五个运营配置；不重载全部旧业务数据，也不代替重启 |
| `/habi preview` | `habi.admin` **且** `habi.presentation.preview` | 玩家本人 15 秒展示预览，无奖励/引导进度/偏好变更 |
| `/menu reload`、`/cd reload` | **原版 OP 2 级** | 仅重载菜单；不是 `habi.admin` 节点替代 |
| `/worldadmin`、`/worldadmin list` | `habi.world.admin` | 列表 |
| `/worldadmin info <世界ID>` | 同上 | 单世界状态、费用、资源重置时间/周期 |
| `/worldadmin create <世界ID> <preset> <seed> <kind> <显示名>` | 同上 | 登记后**重启**创建，显示名为行尾文本 |
| `/worldadmin import <世界ID> <imports内相对目录>` | 同上 | 仅未加载的托管维度；登记后**重启**离线导入 |
| `/worldadmin setspawn <世界ID>` | 同上 | 玩家站在该维度安全落点执行；立即保存世界出生点 |
| `/worldadmin rule <世界ID> <规则> <值>` | 同上 | 验证通过后保存配置，运行时更新规则；原版六项限制见第 2 节 |
| `/worldadmin open <世界ID>`、`/worldadmin close <世界ID>` | 同上 | 立即改变准入；关闭会取消相关传送并疏散在线玩家到独立大厅，不卸载区块 |
| `/worldadmin enable <世界ID>`、`/worldadmin disable <世界ID>` | 同上 | 启停登记**重启生效**；disable 同时立即关闭准入、取消传送并疏散。原版三界不能 disable，大厅不能 close/disable |
| `/worldadmin reset <世界ID>` | 同上 | 只签发 30 秒确认令牌，不立刻重置 |
| `/worldadmin reset <世界ID> confirm <token>` | 同上 | 确认后关闭资源准入、取消待传送/TPA并疏散，**下次启动**事务备份重置 |
| `/worldadmin reset <世界ID> cancel` | 同上 | 仅在尚无文件事务（`resetBackup` 为空）时取消排队；不是回滚命令 |
| `/hubadmin setoverworldspawn` | `habi.hub.admin` | 站在主世界安全落点执行，保存精确坐标及朝向，立即生效；大厅钻石导航与 `/world minecraft:overworld` 共用此点，不改变 `/spawn` |
| `/hubadmin rebuild` → `/hubadmin rebuild confirm <token>` | `habi.hub.admin` | 同一命令来源 30 秒内确认；重新分批放置大厅初始建筑，先做停服备份 |
| `/setspawn` | **原版 OP 2 级** | 玩家更新旧 CMI 全服出生点；沿用原有自动保存/正常停服落盘，不是 `/worldadmin setspawn` |

`create` 的 `preset` 支持 `normal`、`nether`、`end`、`flat`、`void`；普通运营使用 `kind=survival` 或 `resource`，不要把新世界伪装成默认大厅。必须用唯一的 `habi_train_survive:...` ID，拒绝原版覆盖、目录重叠/路径穿越和 Windows 设备别名，已有目标目录不覆盖。

可设置的 `rule` 键：

- 布尔值（只能 `true` / `false`）：`pvp build interact damage hunger mobs mobGrief explosions fireSpread fluids pistons allowClaims allowShops weather portals`。
- 数值：`borderSize`（64..59999968）、`entryFee`（0..1e12）、`playerLimit`（0 表示不限，最大 100000）、`rtpMin`、`rtpMax`（`0 ≤ min < max ≤ 29000000`）、`resetIntervalHours`（0 关闭周期，最大 87600，仅资源世界可非零）、`fixedTime`（-1 正常走时，或 0..24000）。
- 枚举/节点：`difficulty peaceful|easy|normal|hard`；`permission <节点>`，用 `permission none` 清空。`seed`、`preset`、`importSource` 等不是 `rule` 键；`rtpAttempts`（1..100，默认 24）、传送门目标等需编辑 JSON。

**运行时 / 重启分界：** 名称、开放、准入/费用、出生点和支持的保护规则可在线更新；新建、导入、启用/停用，以及修改 `preset` / `seed` / `importSource` 必须完整重启，`/habi reload` 不会热加载或卸载维度。启用一个曾停用的世界后还需检查 `open`，必要时显式 `open`；enable 不自动重新开放。不要通过修改旧世界种子/预设来“转换”已有区块，续生成可能不连续；应创建新 ID 后按导入流程处理。没有通用的在线世界删除、格式转换或备份恢复指令。

重载按模块分别验证，**出错的模块保留上一份有效运行配置，不覆盖错误文件**，其他有效模块可以成功更新，因此不是五模块整体回滚。菜单重载后旧页面点击会刷新再要求重新选择。查看报错路径/字段，修复后再重载；不要用原版 `/reload` 代替套件配置重载。首次启动不能假定有历史有效配置可回退：世界/菜单配置或状态损坏可能拒绝启动；展示/引导会报告错误并保留当时有效配置，必须检查日志。

## 5. 独立 97×97 大厅与旧出生点迁移

大厅在 `habi_train_survive:hub` 独立 void 维度，初始广场 x/z = -48..48，默认出生点 `(0.5, 81, 0.5)`、边界宽 192、固定正午。默认禁止普通玩家建造、交互、PVP、伤害、饥饿、刷怪、怪物破坏、爆炸、火蔓延、流体/活塞、圈地/商店和传送门。

`hub.json` 默认：`autoBuild=true`、`blocksPerTick=512`（32..4096）、`firstJoinToHub=true`、`everyJoinToHub=false`、`fallbackRespawnToHub=true`、`voidRescue=true`。建造分 tick、记录进度；完成标记或导入标记存在后**重启不再覆盖玩家装修**，识别到已有大厅 region 但无状态时也会保守跳过自动建设。中断未完成的建设会续做，不应在完成前装修；显式 rebuild 会再次放置计划中的初始建筑方块，可能覆盖装修，并不等于清空整个维度。`autoBuild=false` 时不会继续分批建设。

右键初始导航方块（y=82）：钻石 `(0,-28)` → 永久生存主世界；紫晶 `(0,28)` → 世界菜单（不是直接随机传送）；绿宝石 `(-28,0)` → `shop`；金块 `(28,0)` → 主菜单。默认首次加入/无有效重生点回退由大厅机制处理，已有玩家识别来自原有 CMI 与存档玩家 UUID，不会把所有老玩家当新人。大厅 y<65 的虚空救援、重置/关闭疏散、失效代次登录救援使用**独立大厅安全点**。

**兼容旧 `/spawn`：** `/spawn`、`/hub`、`/lobby` 优先使用仍有效的旧 CMI 全服出生点，只有没有有效旧点时才回退到独立大厅；首次加入的普通大厅传送也沿用该目标。安装不会偷偷改写老服 `/setspawn`。

如需迁移到独立大厅：先等建造完成 → `/world habi_train_survive:hub`（明确绕过“旧出生点优先”的目的地选择，但仍受通行限制）→ 站在安全位置执行 `/worldadmin setspawn habi_train_survive:hub` → 用 OP 执行 **`/setspawn`** 更新全服旧出生点 → 检查 `/spawn`、`/hub`、新玩家与重生行为。只设置 worldadmin 出生点不会清除旧 CMI 优先点。

## 6. 新建与 Anvil 地图导入

### 新建

例如先执行 `/worldadmin create habi_train_survive:build normal 12345 survival 建筑生存`，或将 kind 改为 `resource`（自动禁止永久领地/商店，周期默认 720 小时）。检查 `worlds.json` 后重启；首次进入由安全落点搜索选点，建议随后站在安全地点执行 `/worldadmin setspawn <ID>`。void 不提供天然地面，需要管理员先准备安全平台，不支持 RTP。

### 导入到新 ID（不要先重启把目标生成出来）

1. 停止地图源服务器并复制**相应单维度**的标准 Anvil 数据到本服 `imports/<目录>/`：普通主世界选择含 `region/` 的根；下界/末地应把源 `DIM-1/`、`DIM1/` 中的数据作为该导入目录内容，不能把整个三维度存档当一个维度。保留源副本，导入器不移动源地图。
2. 在导入目录写清元数据，例如 `imports/build-map/manifest.json`：

   ```json
   {"format": 1, "preset": "normal", "version": 3955, "seed": 12345}
   ```

   `format` 必须为 **1**；正常主世界/下界/末地分别用 `normal` / `nether` / `end`，必须与新世界定义相符，**seed 必须显式写明并与 `/worldadmin create` / `worlds.json` 完全一致**。`version` 是真实地图 `DataVersion`，1.21.1 为 **3955**，不是随意填的套件版本。支持范围 **3463..3955（1.20 至 1.21.1）**，不接受更旧或更高版本；不要只改数字冒充转换。flat/void 须与对应生成器匹配，不支持任意自定义维度类型/noise settings/生成器。
3. 先登记 `/worldadmin create habi_train_survive:build normal 12345 survival 导入生存`，**重启前**执行 `/worldadmin import habi_train_survive:build build-map`。这里填的是 `imports/` 内相对目录，不是 `imports/build-map`、盘符或绝对路径。若用 JSON 登记，同样必须在启动生成前配置显式 seed 与 `importSource`。
4. 确认 `<存档>/dimensions/habi_train_survive/build/` **尚不存在**；重启时在任何维度区块打开之前校验、复制、原子发布。启动后检查 `/worldadmin info ...`，进入验证并设置安全出生点。不要为了重试删除一个已经存在的目标世界。

源目录必须有含实际区块的 `region/`；可选 `entities/`、`poi/`、`data/`。**只复制这四类目录**，排除 `playerdata`、`players`、`advancements`、`stats`、`session.lock`、`level.dat`、`level.dat_old` 等玩家/锁/整服元数据，不迁移玩家背包、权限或插件账本。`level.dat` 若存在只用于元数据检查；没有它时必须提供 manifest，二者都有时版本/种子/生成器必须一致。导入器校验标准 region 文件结构、外部 `.mcc` 及摘要，但不是离线 DataFixer 或任意地图转换工具。

拒绝符号链接、特殊文件、路径穿越、目标覆盖和不相容元数据。先复制至 `<存档>/habi-import-staging/<token>/`，再用 `<存档>/habi-import-journals/<世界ID哈希>.json` 与目标内 `.habi-anvil-import.json` 校验恢复。事务日志/标记会保留，**不要手动删除或改写来强行重新导入**；`imported=true` 是一次性标记，即使以后资源重置也不会重新复制原源地图。失败时保护性停启，不在不明确状态下继续打开区块。

## 7. 资源重置、代次与安全恢复

### 排队与事务

只有本核心 `kind=resource` 世界可重置，原版三界、大厅和普通永久世界不可重置。先 `/worldadmin reset <ID>`，在同一命令来源下 30 秒内用返回 token 确认；确认后立即阻止进入、取消相关传送/TPA并疏散，下次启动才更换地图。周期到期走同样队列。尚未开始文件事务时可 `reset <ID> cancel`，**不能取消已经开始的文件事务**。

启动重置使用 **严格原子事务 + WAL（预写日志）**：先持久化 `pendingReset/resetBackup/resetGeneration/resetHadDirectory`，再把旧维度目录原子移动到：

```text
<存档>/habi-backups/habi_train_survive_resource-<毫秒时间戳>/
<存档>/habi-backups/habi_train_survive_resource_nether-<毫秒时间戳>/
<存档>/habi-backups/habi_train_survive_resource_end-<毫秒时间戳>/
```

其他资源 ID 同样把 `:`、`/` 替换成 `_`。备份不覆盖；源与目标必须同文件系统、支持 `ATOMIC_MOVE`，**不降级为复制后删除**。日志/目录不符、原目录曾存在却源与备份同时存在或同时缺失、链接/权限/IO/原子移动失败会拒绝启动，不猜测或覆盖。已移动而最终状态保存前崩溃，可凭 WAL 在下次启动完成提交。

提交后 generation 加 1、更新重置时间、清除自动出生点与昼夜运行状态，再生成新地图；上一代显式出生点同样失效，导航会重新搜索安全点，管理员可重新设置。种子按基础种子与代次派生，旧 `importSource` 不会重放。

### 重置后的旧坐标

home、warp、城镇传送点、back、死亡点等保存 `generation`；旧代次传送失效，不能落到新地图的同一组数字坐标。登录记录属于旧代次时救援到大厅，旧传送请求也不能跨代复用。**坐标失效不等于删除墓碑物品**：仍可通过 `/grave` 提取旧遗物；不再能去旧死亡点，按原有远程提取规则收费（留足背包空间，旧业务背包放不下会掉落）。

### 安全恢复（没有 `/restore` 命令）

1. **立刻停服并隔离访问**，先另存当前完整存档、整个套件配置/业务状态、事务日志、报错日志；不要在运行中把备份目录盖回去，不删除 WAL 或把 `pendingReset` 改 false 来“解锁”。
2. 若只是事务中断，保留原目录/WAL/配置；排除权限、磁盘空间、链接或原子移动环境问题后，在隔离副本让启动流程完成恢复。若报目录歧义，不再试探性删除任一边，先核对事务与备份。
3. 真正回滚以**重置前同一时间点的整套停服快照**为准，恢复整个存档与 `config/habi_train_survive/`，连同对应模组版本/配置；其中必须包括 `<存档>/data/habi_world_state.json`、所有维度/玩家数据及 CMI、城镇、墓碑、礼物、经济等相关业务状态。`generation`、`lastVisits`、WAL、`imported/hubBuilt` 和各坐标引用必须一致；不要拿旧地图搭配新代次状态，也不要只把 generation 减 1，否则旧/新引用可能误有效或奖励/经济不一致。
4. 自动 `habi-backups/` 是**单维度目录备份，不是整服一致性快照**。只有单维度备份、没有匹配状态快照时，不能按通用流程安全回滚；保留两边数据，由维护者离线核对代次、事务、坐标引用与资产后制定恢复，不凭空填状态值。不要用 import 覆盖已有 ID 来替代恢复。
5. 先在副本启动，检查七维度、代次/重置队列、出生点、玩家登录/背包、各类坐标、墓碑和账本，确认后才安排自己的上线。整套回滚会丢失快照之后的进度，需提前告知玩家。

## 8. 配置/状态归属、菜单扩展、新手与运营展示

### 文件归属

| 路径 | 谁维护/内容 |
| --- | --- |
| `config/habi_train_survive/worlds.json` | 运营者配置世界定义、准入、保护、生成/导入意图；管理命令也写此文件 |
| 同目录 `hub.json` | 运营者配置自动建造、加入/重生/虚空策略、每 tick 预算 |
| 同目录 `menus.json` | 运营者配置菜单；默认源 `src/main/resources/defaults/menus.json` |
| 同目录 `onboarding.json`、`presentation.json` | 运营者配置欢迎/指南/规则/奖励礼包名，以及公告/侧栏/Tab；默认源在 `src/main/resources/defaults/` |
| 同目录 `generated-world-pack/` | 系统生成维度定义，不是人工配置入口，不应自行改写 |
| `<存档>/data/habi_world_state.json` | 系统运行状态：generation、重置时间/WAL、导入标记、主城完成/进度、自动出生点、昼夜及 lastVisits；随存档保存 |
| `config/habi_train_survive/onboarding_state.json` | 系统管理的已有玩家标记、欢迎/阅读/奖励状态、持久待投递礼物，不当作配置重置 |
| 同目录 `presentation_state.json` | 玩家侧栏/公告/本模块声音偏好，按 UUID 保存 |
| 同目录 `cmi.json`、`gifts.json` 及原有各业务 JSON | 继续由旧业务管理器拥有；TPA/收款开关、家/地标/礼包、礼物等不迁入新配置，需一并备份 |

### 自定义菜单的约束

`menus.json` 顶层 `version=1`、`sneakSwapEnabled`、`defaultMenu`、`menus`。页面字段为 `title/rows/permission/back/dynamic/buttons`；静态页 1..6 行、槽位从 **0** 开始，按钮字段 `slot/icon/name/lore/permission/actions`。动态值仅支持 `worlds/homes/warps/towns/players/admin`（空串为静态页）；前五种必须 6 行且 `buttons=[]`，45 个内容槽加翻页页脚。`admin` 使用配置按钮并额外检查 `habi.admin`。

动作 `type` 区分大小写：

| 类型 | `value` / 行为 |
| --- | --- |
| `OPEN` | 已定义菜单 ID |
| `BACK` / `CLOSE` | 不填 value；返回页面（未设 back 则关闭）/关闭 |
| `PLAYER_COMMAND` | 无前导 `/` 的单行固定命令，以玩家自己的命令源执行，不提权 |
| `CONSOLE_COMMAND` | 同样是管理员配置的固定单行命令，以控制台源执行，需严格限制页面/按钮/动作授权 |
| `TELEPORT` / `RTP` | 完整世界 ID；经过统一世界准入/安全传送流程 |
| `MESSAGE` | 私人提示文本 |
| `SOUND` | 已注册音效 ID；可设置 `volume` 0..4、`pitch` 0.5..2（默认均为 1） |

动作可有 `permission`，点击前会先检查整个动作序列权限；`MESSAGE/SOUND` 可在前，跳转/关闭/命令/传送只能是最后一个动作。校验重复字段/槽位、未知字段/类型、图标/音效注册、引用、OPEN 与 back 各自的环等，失败保留有效菜单。

标题/按钮/说明/消息的文本占位符：`{player}`、`{uuid}`、`{balance}`、`{world}`、`{online}`、`{group}`、`{homes}`、`{rtp_cooldown}`、`{worlds}`、`{loaded_worlds}`。**命令动作仅允许身份占位符 `{player}` / `{uuid}`**，来自服务器玩家实体的真实账户名/UUID；账户名校验为 `[A-Za-z0-9_]{1,16}`，不接入昵称、聊天输入或其它文本占位符。不要设计“输入任意命令给控制台执行”的入口；这不是受支持的菜单能力。静态控制台命令本身有高权限，身份过滤并不代替管理员审查及权限配置。动态列表命令参数也做安全单词校验，不可安全拼接的旧名称需用原业务入口处理。

### 新手与奖励幂等

引导包含欢迎标题/文字、指南/规则与阅读进度；指南跳转以玩家命令源复用旧业务。默认奖励从现有 CMI **`newbie` 礼包物品**生成单件礼物包，进入 `/gift box`，背包满可延后领取；不是直接执行任意控制台奖励命令。

首次升级用 CMI 已知 UUID 和存档 `playerdata` UUID 为老玩家登记已处理状态，避免给老玩家重发。新奖励使用固定礼物 ID、持久 outbox、礼物落盘确认再提交已发放标记，崩溃后重试不会因重复登录/重载/预览再造一份；缺失/空/无效礼包或保存失败会保留可重试状态并报错。修改礼包名、重开指南、`/habi preview` 不触发重新领奖。**不要删除 onboarding_state 或只回滚 gifts 文件来补发**，应成套恢复。`rewardEnabled=false` 会把尚未创建待投递礼物的新玩家标记为奖励已处理，并非“暂停后自动补发”；已有待投递礼物仍会恢复投递。阅读步骤无额外物品/金币奖励；每日签到、任务、礼包冷却仍由旧系统管理。

### 运营展示与个人设置

默认轮播公告间隔 180 秒，侧栏/Tab 每 5 秒刷新；可配置全局开关、内容、欢迎/离开消息与偏好默认值。展示文本支持 `{player}/{balance}/{world}/{dimension}/{group}/{online}/{max}`，欢迎标题/文字使用 `{player}`；不要和菜单命令占位符混用。

侧栏仅发送自己的私有目标，检测到服务器/队伍其它侧栏时让位，不清除其它模组计分板。`/settings` 的侧栏、公告（含本模块进退服消息）、提示音偏好持久保存；声音开关**只管本展示/欢迎模块**，不静音菜单 SOUND、旧业务提示或整个游戏。Tab 没有玩家个人开关，由 `presentation.json` 全局控制。设置 GUI 的接收 TPA、接收转账复用 CMI 的 `tpaToggle/payToggle`，保存于 `cmi.json`。预览仅本人可见、15 秒后恢复正常展示，不改偏好、不改变指南进度或发奖。

### 快捷维度旅行与 Shift+F

`/nether`、`/end` 或主菜单“前往地狱/末地”可快速进入当前世界配对的维度（资源世界仍前往资源维度）。检查玩家身边 XYZ 各3格内的对应传送门方块：有门则优先尝试当前对应坐标，地狱按维度坐标比例（通常8:1），末地保留X/Z；同列安全高度仍不可用时转为安全RTP。没有门直接走安全RTP，沿用原有费用、冷却和通行限制；不会强行落入方块或岩浆。世界导航中的下界/末地入口也采用同一策略。

Shift+F（潜行+交换副手）打开配置的默认菜单，普通F不拦截，仍受 `sneakSwapEnabled` 配置控制。旧菜单配置不覆盖，只在默认菜单空槽补齐这两个快捷入口；没有空槽时使用命令。菜单在原版点击包处理结束后重新同步，避免预测槽位导致图标消失；AIR图标以纸张兜底。

**本次快捷维度旅行、菜单同步和快捷键修改按要求仅编译打包，不运行测试或游戏内验收；以下测试数字属于此前基础套件版本。**

## 9. 验证状态与上线前待办

以下为本轮实际执行结果。测试服仅监听本机随机端口，使用会话临时目录；没有操作正式服，临时模拟玩家模组未进入交付 JAR。**服务端模拟与真实客户端验收严格区分。**

| 层次 | 当前状态 | 不能据此声称 |
| --- | --- | --- |
| 自动化单元/文件事务测试 | 共 **83** 项：**75 通过、8 跳过、0 失败/错误**；跳过项受 Windows 创建符号链接权限限制 | 跳过的链接用例通过、完整游戏交互通过 |
| 构建 | `./gradlew test build --console=plain` 成功，产物 `build/libs/habi-train-survive-1.0.0.jar` | 在所有服务端环境/模组组合均可运行 |
| 实际 Fabric 服务端首次启动 | 默认七维度加载、97×97 大厅生成、正常保存成功，无注册/Mixin 注入错误 | 所有客户端输入及视觉均已验收 |
| 服务端模拟玩家交互 | **34 项通过**：菜单防取物/分页/权限、世界关闭/权限/战斗/监禁/冻结、一次扣费、碰撞/旧代拒绝、取消读条、新手奖励幂等、错误配置保留 | 模拟玩家等同真实客户端网络输入链路 |
| 重启、重置、导入与偏好 | 设置命令 **4 项通过**；重启/导入 **17 项通过**：主城装修与永久地图保留、资源备份/代次、旧坐标拒绝、偏好/奖励持久化、flat/void 生成、独立末地战斗状态、标准 Anvil 导入且排除玩家目录 | 已验证所有崩溃时序、整服灾难恢复或任何第三方地图 |
| 原版已有存档升级 | **11 项通过**：从未安装核心的原版夹具开始，保留建筑、配置难度/gamerules、旧经济/权限配额/出生点/家，老玩家不重复领奖，新增世界正常加载 | 已检查用户真实生产存档中的每条业务数据 |
| RTP 与资源传送门专项 | **12 项通过**：异步安全点、RTP/进入费一次结算、成功冷却、移动取消不扣费，资源末地/下界双向路由及关闭拦截 | 已完成真实客户端站门计时/动画或高并发性能测试 |
| 管理状态回归 | **6 项通过**：停用变更显示待重启、状态命令、错误菜单重载安全失败、错误持续显示、成功重载清除错误、撤销变更清除待重启提示 | 所有第三方配置错误和客户端管理入口均已遍历 |
| 真实原版客户端 | **完整验收尚未完成** | 菜单/快捷键、侧栏/Tab/声音、传送/保护与异常恢复已完成真实客户端端到端验证 |

上线前仍需在自己的隔离副本用普通玩家和管理员完成真实客户端核验：默认所有菜单及 Shift/数字键/拖拽/丢弃等输入，潜行副手快捷键，侧栏/Tab/声音效果，床/重生锚与登录策略，主城各类保护，以及墓碑旧遗物提取与停服快照恢复。已通过的服务端项目不替代这些人工验收。
上述隔离服务端场景合计 **84 项断言通过**。测试过程中遇到的模拟偏好误用、原版难度夹具配置和并行 JVM 内存不足均已定位修正，按实际命令及单服顺序重测通过；不是尚存的代码阻塞。
