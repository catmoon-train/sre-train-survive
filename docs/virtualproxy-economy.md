# VirtualProxy / VPAC 金币兑换联动

## 兑换规则

- 默认 5 生存金币换 1 列车金币，10 列车金币换 1 生存金币。
- 个人每日两个方向分别限付出 50,000 对应金币；北京时间（Asia/Shanghai）每天 00:00 重置。
- 玩家仅可操作 QQ 绑定的本人账户。管理员可以通过 VPAC 修改兑换比例与每日限额。
- 兑换与限额由 VirtualProxy 中控负责；本模组只提供经过鉴权的生存账户查询与幂等扣款/入账接口。

## 生存服配置

首次启动新版模组后会生成 `config/habi_train_survive/virtualproxy_economy.json`，默认关闭：

```json
{
  "enabled": false,
  "bindAddress": "127.0.0.1",
  "port": 20091,
  "secret": ""
}
```

停服后将 `enabled` 改为 `true`，填写至少 32 位的随机密钥，再启动服务器。VirtualProxy 中控配置的生存服 URL 和密钥必须与这里一致。

若中控不在同一台机器，显式将 `bindAddress` 改为内网地址，并在防火墙中仅允许中控机器访问端口。此接口不应直接暴露公网；跨不可信网络请使用 HTTPS 反向代理或安全隧道。不要把密钥发送到 QQ 群或写入命令日志。
中控首次启动会在安全存储文件旁生成 `virtualproxy-coin-exchange.json`。停中控后填写 `trainNode`（明确指定的列车节点 ID）、`survivalUrl`（例如 `http://127.0.0.1:20091`）、`secret`（与生存服一致）；保留已有 `transactions`。该列车节点必须在中控金币资源许可列表中，且 SRE MySQL 同步已开启、经济数据表使用 InnoDB。中控、列车节点 VirtualProxy 和机器人均需更新；无需更新 manager 客户端或修改 SRE 源码。详细说明见 VirtualProxy 项目的 `docs/COIN_EXCHANGE.md`。

## VPAC 命令

在机器人授权群内使用，先完成 QQ 与游戏账户绑定：

```text
/vpac balance                         查本人两种余额、今日已用额度
/vpac exchange train 5                付出5生存币，获得1列车币
/vpac exchange survival 10            付出10列车币，获得1生存币
/vpac exchange status                 查看本人交易和待处理编号
/vpac exchange retry <编号>           使用原编号续办未完成交易
```

金额是付出的整数金币，必须为对应比例输入值的整倍数，不会自动取整损失零头。等待处理的交易会占用额度；余额不足明确拒绝的交易不占用额度。超时后不要重新发送一笔新兑换，请查看状态并续办原编号。

管理员（中控授权的 QQ 管理员）可使用：

```text
/vpac exchangeconfig                  查看当前配置（不显示密钥）
/vpac exchangeconfig survivalInput 5  生存→列车：付出生存币数量
/vpac exchangeconfig trainOutput 1    生存→列车：获得列车币数量
/vpac exchangeconfig trainInput 10    列车→生存：付出列车币数量
/vpac exchangeconfig survivalOutput 1 列车→生存：获得生存币数量
/vpac exchangeconfig survivalLimit 50000  每日付出生存币上限
/vpac exchangeconfig trainLimit 50000     每日付出列车币上限
```

比例输入/输出必须为正整数；限额为0表示暂停该方向，不是无限。比例与限额修改仅影响新交易，不重算旧交易、不清空已用额度。有未完成交易时禁止切换端点或密钥。

## 私有接口契约

所有接口必须携带请求头 `X-Economy-Secret`。金额使用整数“分”（100 分 = 1 生存金币）。

- `GET /health`：健康检查。
- `POST /balance`：JSON `{"playerUuid":"UUID","playerName":"Alice"}`，返回 `success`、`playerUuid`、`balanceCents`。
- `POST /adjust`：JSON `{"playerUuid":"UUID","playerName":"Alice","requestId":"交易编号-debit","deltaCents":-500}`，成功返回 `success`、`playerUuid`、`requestId`、`balanceCents`。

UUID 存在时优先使用已有 UUID 账户；否则使用唯一的、不区分大小写的已有玩家名。未知账户不会自动创建，请先进入生存服。

交易编号允许 1～160 位字母、数字和 `_ . : -`。`deltaCents` 非零且绝对值不超过 100,000,000,000；结果余额不得超过 100,000,000,000,000 分。同一交易编号与同一账户、金额重复提交只返回原回执，不会再次变更余额；更换账户或金额会拒绝。

状态码：401 密钥无效；400 参数无效；404 账户/接口不存在；409 余额不足、余额超限或编号冲突；413 请求体超过 8 KiB；503 暂不可用或结果未知。

**超时、断网或 503 不表示一定未扣款。只能使用原交易编号重试，不能生成新编号或盲目退款。**

## 持久化及维护

余额和 `exchangeReceipts` 回执保存在同一个 `economy.json` 中，每次兑换先完成原子落盘，再返回成功。不支持原子替换的文件系统会拒绝兑换。游戏内原有经济操作不变。

不要手动删除 `exchangeReceipts`，也不要只回滚生存服数据而保留中控/列车端的新交易记录；维护恢复时应停止兑换，并协调恢复两端账本。否则旧交易可能再次执行。

经济文件损坏时不再静默创建空账户数据，而是拒绝加载并保留原文件，以免丢失已提交回执。金币 HTTP 配置错误时仅关闭该接口，不影响正常游戏；修改接口配置后需要重启。

## 已执行验证

`./gradlew :test --tests 'io.wifi.habitrainsurvive.economy.VirtualProxyEconomyTest' build --console=plain`

测试覆盖实际 HTTP 鉴权、参数验证、幂等重试、交易冲突、余额不足、重启后的回执恢复、写盘失败不改余额、损坏数据保护。
