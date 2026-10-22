# Gentle Start

> 为我自己所管理的服务器设计和编写，完全由 gpt-6-astra 生成。使用风险由使用者自行承担（Use at your own risk）。

[English](README.md) | 简体中文

**让新玩家有时间站稳脚跟，再迎接生存的挑战。**

Gentle Start 是一个 Minecraft Fabric 服务端模组，为刚加入服务器的玩家提供可配置的保护：死亡保留物品与经验、原地免死、重生无敌、PvP 保护，以及更多生存辅助。

保护按玩家在本服的累计游玩时间计算，也可以在获得物品、完成进度或达到指定等级后自动结束。你可以为整个服务器设置统一规则，也可以为某位玩家单独调整。

**仅需服务端安装 · 客户端无需安装本模组 · 支持原版客户端**

[功能](#功能) · [安装](#安装) · [配置](#配置) · [命令](#命令) · [常见问题](#常见问题) · [构建](#构建)

## 功能

默认配置会在玩家累计游玩不足 **120 分钟** 时保留死亡物品、全部经验和死亡界面分数，并提供抗性 I、夜视 I。其他保护默认关闭，可按需启用。

| 功能 | 能做什么 | 配置名称 |
| --- | --- | --- |
| 物品保留 | 死亡后保留全部物品，或按类别、物品、标签、槽位筛选 | `keepInventory` |
| 经验保留 | 保留全部或指定比例的可用经验 | `keepExperience` |
| 分数保留 | 保留死亡界面中的原版分数 | `keepScore` |
| 原地免死 | 抵挡致命伤害，恢复指定生命值，可限制次数并附加效果 | `preventDeath` |
| 重生无敌 | 真正死亡重生后，短暂免疫普通伤害；与免死无敌独立 | `respawnInvulnerability` |
| 掉落保护 | 延长死亡掉落的存在时间，防火、抵抗普通伤害并禁止合并 | `protectDeathDrops` |
| 死亡尸体 | 将死亡物品存入 54 格木桶或躺卧玩家模型，可选保存经验 | `deathCorpse` |
| 状态效果 | 在保护期间持续提供可配置的药水效果 | `protectionEffects` |
| PvP 保护 | 阻止玩家伤害，可在主动攻击后解除保护 | `pvpProtection` |
| 伤害减免 | 按比例降低受到的普通伤害 | `damageReduction` |
| 怪物感知范围 | 缩小怪物发现和持续追踪玩家的范围 | `mobDetectionRange` |

每项功能的期限和结束条件都可以独立设置。保护状态、免死次数与取消记录会随世界保存。

## 安装

| 环境 | 要求 |
| --- | --- |
| Minecraft Java Edition | 26.2 |
| 模组加载器 | Fabric Loader 0.19.5 或更高版本 |
| 依赖 | 对应 Minecraft 版本的 Fabric API；项目使用 0.161.0+26.2 构建 |
| Java | 25 或更高版本；源码构建使用 JDK 25 |
| 安装位置 | 服务端 |

1. 准备好上述版本的 Fabric 服务端。
2. 将 Gentle Start 的模组 Jar 与 Fabric API 放入服务器的 `mods/` 目录。不要使用文件名带 `-sources` 的源码包。
3. 启动服务器，自动生成 `config/gentle-start-mod.json`。
4. 按需修改配置，执行 `/gentlestart admin reload` 使其生效。

尚未提供预编译包时，可以按下方的[构建说明](#构建)生成 Jar。

## 配置

配置文件：`config/gentle-start-mod.json`。首次启动服务端且文件不存在时，会自动生成完整默认配置和中文 `//` 注释。仍使用原文件名，但内容支持注释（JSON with comments），外部严格 JSON 工具需要先去除注释。

每项功能、数值单位、范围和特殊值均有说明。旧版功能名称（如 `inventory`）和 `playersPerTick` 仍可读取；管理员修改并保存时统一写成新名称和标准注释。重载不会主动改写已有文件；保存不保留手工注释。新旧名称不能同时配置同一功能，以免覆盖含糊。功能内部字段及玩家状态记录保持兼容。

- `enabled`：全局开关。
- `timeConditionChecksPerTick`：每个服务端 tick 最多轮转检查多少名在线玩家的**累计时间取消条件及持续药水效果**，正整数，默认 `1`。在线人数稳定为 P 时，一轮约需 `ceil(P / timeConditionChecksPerTick)` tick；每 tick 不重复检查同一玩家。没有时间条件的功能跳过条件检查，不周期扫描物品、等级或进度；持续效果仅在缺失、等级不足或需要延长时补齐。
- `defaults`：所有玩家使用的默认规则。
- `players`：以 UUID 为索引，为指定玩家覆盖默认规则。

省略的字段会继承默认值；列表整体替换。修改文件后需要重载，管理命令则会自动保存并生效。无效配置会报错，重载失败时继续使用当前有效配置。

### 保护持续多久？

每项功能的 `minutes` 都表示**累计游玩时间的上限**：

| 值 | 含义 |
| --- | --- |
| 正数，例如 `120` | 累计游玩不足 120 分钟时有效，达到上限后结束 |
| `0` | 关闭该项功能 |
| 负数，例如 `-1` | 不按游玩时长到期，仍可被取消 |

计时包含挂机和安装模组前已有的本服游玩统计，不会因退出或重启重置。时间按服务器 tick 计算，低 TPS 时与现实时间不同。

### 配置示例

下面的示例在默认保护基础上，额外开启前两小时的 PvP 保护和 5 秒重生无敌。它不是模组的出厂配置。

```json
{
  "enabled": true,
  "timeConditionChecksPerTick": 1,
  "defaults": {
    "keepInventory": { "minutes": 120 },
    "keepExperience": { "minutes": 120, "ratio": 1.0 },
    "keepScore": { "minutes": 120 },
    "protectionEffects": { "minutes": 120 },
    "pvpProtection": { "minutes": 120, "removeOnAttack": true },
    "respawnInvulnerability": { "minutes": 120, "durationSeconds": 5 }
  },
  "players": {}
}
```

<details>
<summary>按需启用更多保护</summary>

将需要的规则放进 `defaults` 或某位玩家的配置中：

```json
{
  "preventDeath": { "minutes": 60, "uses": 3, "health": 10, "invulnerabilitySeconds": 5 },
  "protectDeathDrops": { "minutes": 120, "lifetimeSeconds": 600 },
  "damageReduction": { "minutes": 60, "reduction": 0.25 },
  "mobDetectionRange": { "minutes": 60, "multiplier": 0.5 }
}
```

- `preventDeath`：前 60 分钟最多免死 3 次，每次恢复到 10 点生命（5 颗心）。`uses: -1` 表示不限次数。`invulnerabilitySeconds` 设置每次免死后的独立无敌秒数，默认 `5`，支持 `0～86400` 的整数，`0` 不附加无敌。它与 `respawnInvulnerability.durationSeconds` 分别计时，互不授予或重置；取消/重置某项功能仅清除对应的无敌。
- `protectDeathDrops`：`lifetimeSeconds` 指定死亡掉落物寿命，默认 `600` 秒，支持 `1～86400` 的整数。按实体更新 tick 计时，区块停止实体更新时暂停。寿命在死亡时确定，已存在的掉落物不随配置修改而改变，保存和读档不会刷新剩余时间。旧版掉落物保留原有倒计时。普通主动丢弃、挖矿掉落和经验球不受影响，其他玩家仍可拾取。
- `damageReduction`：减免 25% 的传入伤害，在护甲和药水减伤前生效。
- `mobDetectionRange`：将怪物感知范围缩小到原来的 50%；主动攻击怪物后仍可能被反击。

</details>

<details>
<summary>使用尸体保存死亡物品</summary>

开启前先关闭物品保留。将以下规则放进 `defaults`，或一起放进某位玩家的覆盖配置：

```json
{
  "keepInventory": { "minutes": 0 },
  "deathCorpse": { "minutes": -1, "mode": "barrel", "saveExperience": true }
}
```

`deathCorpse` 默认关闭，使用与其他功能相同的累计游玩期限及 `cancel` 条件。默认配置及每位玩家合并继承后的配置中，它与 `keepInventory` 的 `minutes` 不能同时非零；冲突的修改或重载会报错，保留当前有效配置。原版 `keep_inventory` 游戏规则也优先：开启时不会生成尸体。

- `mode: "barrel"`：生成保存死亡物品的木桶，仅这些指定木桶使用 54 格，普通木桶容量不变。死亡位置被占用时，选择两格范围内最近的可替换位置，不覆盖方块实体；找不到位置时按正常死亡掉落处理。
- `mode: "player"`：在死亡位置生成与死者同皮肤、躺卧的原版假人，右键打开同样的 54 格容器。两种形式都支持原版客户端；假人的睡姿交互范围较小，右键时需对准生成位置中心附近。
- 前三行保持主背包排列，第四行放快捷栏；第五行第 1～4 格依次放头盔、胸甲、护腿、靴子，第 9 格放副手物品。
- `saveExperience: true`：按死亡时的等级和进度计算当前全部可用经验，保存为第六行第五格的附魔之瓶图标，点击一次领取全部存储经验；图标不是可取走投掷的普通经验瓶。尸体保存经验生效时优先于 `keepExperience`，不会在重生时再返还同一份经验。设为 `false` 时，仍按已有经验保留功能和原版经验掉落规则处理。

尸体容器只允许取出，不能放入物品，也没有所有者锁，其他玩家同样可以领取。漏斗可以抽取木桶尸体中的实际物品，但不能向其中放入物品，也不能提取经验。消失诅咒仍按原版在死亡时销毁相应物品，不存入尸体。

打破木桶会掉出剩余物品及经验；对玩家模型执行 `/kill` 也会释放剩余物品及经验并移除模型。关闭尸体功能只停止生成新尸体，已有尸体仍可交互取回内容。

使用命令开启时，先执行 `/gentlestart admin defaults set keepInventory 0`，再执行 `/gentlestart admin defaults rule deathCorpse {"minutes":-1,"mode":"barrel","saveExperience":true}`；将 `"barrel"` 改为 `"player"` 即使用玩家模型。也可以在 `/gentlestart admin ui defaults deathCorpse` 聊天配置页编辑这两个选项，或按 UUID 单独覆盖。

**卸载模组前，请先取回所有现存尸体中的物品和经验。** 扩展库存和经验领取依赖服务端模组，直接卸载不会把尸体转换成可以正常使用的原版 54 格木桶或带库存的假人。

</details>

<details>
<summary>只保留指定物品</summary>

将下面的规则放进 `defaults.keepInventory`：

```json
{
  "minutes": 120,
  "keep": {
    "all": false,
    "categories": ["weapons", "tools", "armor", "offhand"],
    "items": ["minecraft:diamond"],
    "tags": ["minecraft:logs"],
    "slots": []
  }
}
```

类别、物品 ID、标签和槽位满足任意一项即可保留；`all: true` 则保留全部物品。标签不带 `#`。

支持的类别：`weapons`、`tools`、`wearable`、`food`、`blocks`、`hotbar`、`main`、`armor`、`offhand`、`body`、`saddle`。其中 `armor` 仅匹配已穿戴的盔甲槽位，`wearable` 也匹配背包内可装备的物品。

槽位编号：快捷栏 0～8、主背包 9～35、盔甲 36～39、副手 40、身体装备 41、鞍 42。

</details>

<details>
<summary>在玩家成长后自动结束保护</summary>

给任意一项功能添加 `cancel`。下面的规则会在玩家进入下界或达到 30 级时，永久取消这一项保护：

```json
{
  "minutes": 120,
  "cancel": {
    "requireAll": false,
    "advancements": ["minecraft:story/enter_the_nether"],
    "experienceLevel": 30
  }
}
```

还支持 `items`（物品 ID 列表）、`itemTags`（物品标签列表）和 `playMinutes`（累计分钟数）。`requireAll: true` 表示所有指定条件都要达成，达成历史会保留。

保护取消后，丢弃物品、降低等级或撤销进度都不会重新获得保护。管理员可通过 `reset` 重置；若玩家仍满足取消条件，保护会再次取消。

物品/标签、装备、经验等级、进度的取消条件由各自变化事件触发。日常伤害、效果刷新和状态查询不再扫描这些状态；累计时间条件仍按 `timeConditionChecksPerTick` 分批检查，实际保护判定时也会及时核对时间。登录、重生、配置修改/重载、成功的数据包重载和管理员重置会重新核对当时状态，避免遗漏已持有物品或已完成进度。其他模组若绕过原版方法直接修改内部字段，不保证触发即时检查。

</details>

<details>
<summary>为某位玩家单独设置</summary>

先用 `/gentlestart admin lookup 玩家名` 查询 UUID，再填写到 `players` 中。示例 UUID 需要替换：

```json
{
  "players": {
    "00000000-0000-0000-0000-000000000001": {
      "keepInventory": { "minutes": -1 },
      "pvpProtection": { "minutes": 0 }
    }
  }
}
```

这位玩家的物品保留不会按时间到期，PvP 保护关闭，其他功能继承服务器默认规则。

</details>

## 命令

### 交互式管理员配置

管理员在游戏中执行 `/gentlestart admin` 或 `/gentlestart admin ui`，在**聊天栏**显示可点击的管理菜单，无需客户端安装模组：

- **全局设置**：点击切换总开关；编辑 `timeConditionChecksPerTick` 时预填命令，修改末尾人数后回车保存。
- **默认保护规则**：选择功能，直接编辑期限、比例、次数、生命、无敌秒数、掉落物寿命和取消条件数值。
- **选择玩家**：分页显示在线玩家与已有 UUID 覆盖，也可按名字或 UUID 查找；配置页显示该玩家合并后的当前值。
- **编辑**：数值和列表的 `[编辑]` 按钮将完整命令及当前值放进聊天输入栏，修改末尾值后回车；布尔规则点击 `[切换]` 立即保存。悬停查看含义和完整值，长列表缩略显示，字段分页展示。
- 每次只保存选中的字段，其他字段继续继承默认配置。物品筛选、取消条件和药水效果列表也可预填编辑，列表使用 JSON 数组并整体替换。校验失败不写入配置，成功后刷新对应配置页。
- 玩家页提供查看保护状态、恢复本功能默认配置和重置取消状态的入口。恢复默认、重置状态只预填命令，需回车执行。普通配置调整不会清除已取消状态或已用免死次数。

可直接查看 `/gentlestart admin ui defaults protectDeathDrops` 或 `/gentlestart admin ui player <UUID> protectDeathDrops`，末尾可加页码。控制台保留原有文本管理命令；所有聊天按钮仍通过命令检查管理员权限。

玩家使用 `/gentlestart status` 查看自己的保护状态，使用 `/gentlestart help` 查看帮助。状态统一使用中文功能名和配置名称，并区分生效、过期、取消、禁用、旁观模式不适用及免死次数用尽；离线查询不推测剩余时间。

管理员命令需要 OP 2 级权限，也可以从服务器控制台执行。命令支持 Tab 补全。

| 常用命令 | 用途 |
| --- | --- |
| `/gentlestart admin reload` | 重载配置 |
| `/gentlestart admin defaults set pvpProtection 120` | 为累计游玩不足两小时的玩家开启 PvP 保护 |
| `/gentlestart admin lookup 玩家名` | 查询玩家 UUID |
| `/gentlestart admin player <UUID> grant keepInventory 30` | 从在线玩家当前游玩时长起，再授予 30 分钟物品保留 |
| `/gentlestart admin player <UUID> cancel all` | 取消该玩家的全部保护 |
| `/gentlestart admin player <UUID> reset all` | 重置取消记录、条件历史、免死次数和无敌窗口 |

`set` 设置累计时长上限；`grant` 从当前游玩时长起追加保护并重置对应状态。`reset` 不会延长时长上限。

<details>
<summary>完整管理命令</summary>

| 命令（均以 `/gentlestart admin` 开头） | 含义 |
| --- | --- |
| `help` | 列出管理入口；命令与功能名支持 Tab 补全，UUID 提供在线及已配置候选 |
| `lookup <玩家名或选择器>` | 查询玩家名对应 UUID；离线模式未知名字的解析遵循原版规则，应优先使用已知玩家或准确 UUID |
| `list [页码]` | 每页列出 20 个有独立配置的 UUID |
| `enabled <true或false>` | 保存全局启用开关 |
| `reload` | 重新读取并校验配置，失败时保留正在使用的配置 |
| `defaults` | 查看默认规则 |
| `ui` | 显示聊天栏交互管理菜单 |
| `ui set <defaults或UUID> <功能> <字段路径> <JSON值>` | 只修改一个字段，例如 `protectDeathDrops lifetimeSeconds 600` 或 `keepInventory cancel.experienceLevel 30` |
| `settings <true或false> <每tick人数>` | 一次保存全局开关和周期检查人数 |
| `checksPerTick <每tick人数>` | 仅修改轮询人数，不改变全局开关；聊天菜单的两个入口分别只修改对应字段 |
| `defaults set <功能> <分钟>` | 修改默认累计时间阈值 |
| `defaults rule <功能> <JSON对象>` | 合并修改默认规则的任意受支持字段 |
| `player <UUID>` | 查看该玩家各项期限、取消原因和免死次数；离线时不虚构剩余时间 |
| `player <UUID> show` | 查看该 UUID 的完整生效配置 |
| `player <UUID> set <功能> <分钟>` | 设置累计游玩阈值，不清除已有取消状态 |
| `player <UUID> grant <功能> <分钟>` | 在线玩家从当前游玩时间起再授予指定分钟，并重置该项状态；负数长期、0 禁用也支持离线 UUID |
| `player <UUID> rule <功能> <JSON对象>` | 合并修改该 UUID 的规则 |
| `player <UUID> clear <功能或all>` | 删除配置覆盖，恢复继承；不重置次数或取消状态 |
| `player <UUID> cancel <功能或all>` | 持久取消保护 |
| `player <UUID> reset <功能或all>` | 重置取消状态和对应条件历史；preventDeath 清零已用次数及免死无敌，respawnInvulnerability 仅清除重生无敌 |
| `player <UUID> revives <已用次数>` | 设置已用免死次数，保留独立无敌窗口 |
| `copy <源UUID> <目标UUID>` | 复制生效规则，不复制取消历史或已用次数 |


</details>

## 常见问题

**客户端需要安装吗？**

不需要。Gentle Start 仅安装在 Fabric 服务端，原版客户端即可加入。它不是 Paper 或 Spigot 插件。

**老玩家会重新获得两小时保护吗？**

不会。模组读取玩家已有的本服累计游玩时间。需要额外授予保护时，使用 `grant` 命令。

**退出、重连或重启会刷新次数吗？**

正常退出、重启不会。取消记录、免死次数和无敌截止点按玩家保存到世界的 `data/gentle-start-mod-state.json.d/<UUID前两位>/<UUID>.json`。每 100 tick（正常 TPS 下约 5 秒），只把发生变化的玩家交给一个后台写入任务；不复制、不重写未变化的历史数据。完全重置会删除对应玩家文件。记录按需加载，最多缓存 4,096 条已读取记录；尚未保存的变化会保留在内存中，直到成功落盘。

正常停服和 `/save-all flush` 会等待保存。失败的记录保留待保存状态并重试，已经成功的玩家不会被重复写入；新变化优先于失败的旧版本。写入失败会记录错误，不会从伤害回调抛出。玩家文件损坏或无法读取时，访问会明确报错，不会当作新玩家重置保护。突然断电或强杀进程可能丢失最近的变化；磁盘持续不可写时，待保存时间会更长。请使用管理命令调整，不要在服务器运行时手工编辑这些文件。

从旧版单文件格式升级后，首次启动会将 `data/gentle-start-mod-state.json` 迁移到临时目录，全部记录成功后才启用新目录。大型存档的这次迁移可能需要较长时间。原文件原样保留，作为升级前备份；新目录一旦存在，就以新目录为准，不会重新合并旧文件。备份世界时应包含新目录；降级到只读取旧文件的版本会恢复过时状态。

**免死可以防止掉进虚空吗？**

不可以。虚空和 `/kill` 等绕过无敌的特殊伤害仍然有效，模组不提供虚空传送救援。原版不死图腾优先于模组免死次数触发。免死在伤害、护甲及吸收生命等结算后确认致死，并在进入死亡流程前恢复生命，因此不会关闭当前容器、生成死亡快照或替换玩家。装备耐久等伤害结算副作用仍按原版执行。直接绕过伤害流程调用死亡方法的其他模组不保证被拦截；真正死亡会清除先前的免死无敌。

**开启 PvP 保护后还能攻击别人吗？**

`removeOnAttack: true` 时，主动攻击其他玩家会永久解除自己的 PvP 保护；设为 `false` 时，受保护玩家也无法伤害别人。归属判定检查伤害来源的致因实体、直接实体及原版可追踪实体/驯服实体的主人，例如箭、宠物攻击和有归属的爆炸。参与这种玩家攻击时，攻击者当前的免死无敌和重生无敌也会结束（即使对方挡下伤害），但不取消之后的免死/重生保护资格；受害者自己的保护不受影响。无法归属玩家的岩浆、点火和机关伤害仍不保证被识别为 PvP。

**保护结束后，药水效果会立即消失吗？**

持续药水直接按 `protectionEffects.minutes` 的剩余保护时间授予，长期保护使用原版无限效果。轮询只在需要时补齐，不重置正常倒计时，因此生命恢复等周期效果沿用原版节奏。登录、重生、原地免死和配置重载会检查补齐；喝奶等清除效果后，在该玩家下一次轮询时补齐。持续效果列表里的旧 `durationSeconds` 字段仍可读取，但不决定时长；`preventDeath.effects` 中的该字段仍按秒生效。

按时到期的有限效果自然结束。提前取消、缩短期限或关闭功能仅停止补发，不撤销已授予的效果，也不移除其他药水或信标提供的同类效果；已经授予的无限效果会保留，直到喝奶、死亡或其他原版机制清除。

**能和其他模组一起用吗？**

原版全局 `keep_inventory` 仍然生效。按槽位保留时，模组暂存选中物品，让原版处理其他物品、消失诅咒和装备掉落，并通过 `try/finally` 恢复选中槽位；不重写原版掉落循环，也不临时切换全局规则。已测试另一 Mixin 取消或抛出异常时的槽位恢复。使用自定义 AI、死亡或伤害流程的模组仍需在实际组合中测试，不能保证所有组合都兼容。

## 构建

使用 JDK 25，在项目根目录执行：

```powershell
.\gradlew.bat build
```

Linux / macOS：

```sh
./gradlew build
```

产物位于 `build/libs/`，安装不带 `-sources` 后缀的 Jar。`build` 会一并运行服务端 GameTest；单独运行测试可使用 `runGameTest` 任务。

GitHub Actions 会在推送到 `main` 和提交 Pull Request 时运行构建、服务端 GameTest 及脱敏的 BetterLeaks 扫描，也支持手动触发。成功运行后可下载模组 Jar 产物；构建和测试日志会保留用于排查。查看 [CI 运行记录](https://github.com/XiaoQing235/gentle-start-mod/actions/workflows/ci.yml)。

## 反馈

遇到问题或有功能建议，请提交 [Issue](https://github.com/XiaoQing235/gentle-start-mod/issues)，附上 Minecraft、Fabric Loader、Fabric API 和模组版本，以及复现步骤。提交日志或配置前请移除个人信息。

## 许可证

[MIT License](LICENSE) · Copyright (c) 2026 XiaoQing235
