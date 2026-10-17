# Newcomer Protection

Minecraft Java 26.2 的纯服务端 Fabric Mod。按 UUID 为每个玩家配置新人保护，原版客户端即可加入。

## 功能与计时

所有功能分别配置 `minutes`，使用玩家在本服的原版累计 `play_time`：负数长期有效，0 禁用，正数表示累计游玩分钟阈值。1 分钟为 1200 tick，允许小数；判定为严格小于。包含挂机和安装前已有的统计，低 TPS 下不等于真实分钟。

| 功能名 | 行为 | 出厂期限 |
| --- | --- | --- |
| `inventory` | 保留全部物品或指定类别、物品 ID、标签、槽位 | 120 分钟 |
| `experience` | 按当前可用经验点数比例保留经验 | 120 分钟，比例 1 |
| `score` | 独立保留原版玩家分数，即死亡界面的分数；不修改计分板目标 | 120 分钟 |
| `revive` | 原地直接免死、恢复生命，支持次数、短暂无敌和效果 | 0，需手动启用 |
| `effects` | 定期刷新配置的原版持续状态效果 | 120 分钟，抗性 I、夜视 I |
| `pvp` | 阻止玩家伤害，支持主动攻击后永久解除本项保护 | 0，需手动启用 |
| `damageReduction` | 按比例减少传入伤害 | 0，需手动启用 |
| `mobRange` | 缩小怪物发现和维持玩家目标的范围 | 0，需手动启用 |

每项保护还可以单独配置自动取消条件。`minutes: -1` 只表示不因时长到期，不会绕过自动取消或管理员取消。

## 安装和配置

将 `build/libs/newcomer-protection-0.2.0-SNAPSHOT.jar` 与对应 Fabric API 放入 Fabric 26.2 服务端的 `mods/`。首次启动生成 `config/newcomer-protection.json`。编辑后使用 `/newcomer admin reload` 生效；新配置验证失败时继续使用旧配置，错误文件保留供修正。

`defaults` 是所有玩家的默认规则；`players` 按 UUID 覆盖。未填写的字段逐层继承，列表整体替换。以下为展示各项功能的配置示例，其中主动启用了出厂配置中关闭的功能；示例 UUID 需替换为目标玩家 UUID：

```json
{
  "enabled": true,
  "defaults": {
    "inventory": {
      "minutes": 120,
      "keep": {
        "all": false,
        "categories": ["weapons", "tools", "armor", "offhand"],
        "items": ["minecraft:diamond"],
        "tags": ["minecraft:logs"],
        "slots": [20]
      },
      "cancel": {
        "requireAll": false,
        "advancements": ["minecraft:story/enter_the_nether"],
        "items": ["minecraft:elytra"],
        "itemTags": [],
        "experienceLevel": 30,
        "playMinutes": 180
      }
    },
    "experience": { "minutes": 120, "ratio": 0.75 },
    "score": { "minutes": 120 },
    "revive": {
      "minutes": 60,
      "uses": 3,
      "invulnerabilitySeconds": 5,
      "effects": [
        { "id": "minecraft:fire_resistance", "level": 1, "durationSeconds": 10, "showParticles": false, "showIcon": true }
      ]
    },
    "effects": {
      "minutes": 120,
      "list": [
        { "id": "minecraft:resistance", "level": 1, "durationSeconds": 15, "showParticles": false, "showIcon": true },
        { "id": "minecraft:night_vision", "level": 1, "durationSeconds": 15, "showParticles": false, "showIcon": true }
      ]
    },
    "pvp": { "minutes": 120, "removeOnAttack": true },
    "damageReduction": { "minutes": 60, "reduction": 0.25 },
    "mobRange": { "minutes": 60, "multiplier": 0.5 }
  },
  "players": {
    "00000000-0000-0000-0000-000000000001": {
      "inventory": { "minutes": -1 },
      "pvp": { "minutes": 0 }
    }
  }
}
```

未知字段、UUID、物品、效果、标签、进度 ID，以及无效比例、次数或槽位会明确报错。已有 0.1 配置中的秒数、保包和经验开关、效果列表会被识别并在内存中转换；读取不会覆盖旧文件，后续管理员命令保存时写入新格式。

## 各功能的规则

### 物品、经验和分数

`inventory.keep.all: true` 表示全部保留，此时无需配置筛选项。设为 `false` 后，类别、ID、标签和槽位的匹配结果取并集。

| 类别 | 匹配规则 |
| --- | --- |
| `weapons` | 武器组件物品，以及弓、弩等投射武器；不限制所在槽位 |
| `tools` | 具有原版工具组件的物品；不限制所在槽位 |
| `wearable` | 具有原版可装备组件的物品，例如背包中的盔甲、鞘翅；不限制所在槽位 |
| `food` | 具有原版食物组件的物品；不限制所在槽位 |
| `blocks` | 方块物品；不限制所在槽位 |
| `hotbar` | 0～8，快捷栏 |
| `main` | 9～35，主背包 |
| `armor` | 36～39，脚、腿、胸、头 |
| `offhand` | 40，副手 |
| `body` | 41，26.2 身体装备位 |
| `saddle` | 42，26.2 鞍装备位 |

物品类别可以重叠，例如某件物品同时是工具和武器；组件按物品堆当前数据判断。`armor` 表示已穿戴的盔甲槽位，`wearable` 也能匹配主背包中的装备。需要精确范围时使用物品 ID 或标签，标签写作 `minecraft:logs`，不带 `#`。被选中的消失诅咒物品会保留，未选中的继续按原版销毁。

`experience.ratio` 为 0～1：1 完整保留，0 不保留。部分保留从等级及等级进度计算实际可用经验点数后向下取整，避免把附魔前的累计经验错误恢复；剩余部分按原版死亡经验奖励乘以 `1 - ratio` 生成经验球，并非把全部损失经验返还成经验球。分数由 `score` 独立决定。

死亡瞬间固定保留槽位、经验比例、经验值和分数。死亡界面期间到期、取消、修改配置，以及保存后重连，不会追溯改变这次死亡结果。打开容器时先返还光标和合成栏物品；背包放不下的额外物品遵循原版关闭容器的溢出掉落行为。原版全局 `keep_inventory` 仍然生效。

### 原地免死

`revive.uses` 为总可用次数：-1 不限，0 不提供免死，正数为次数上限。用过的次数按 UUID 随世界保存，不会因死亡、重连或正常重启刷新。

普通致命伤害先走原版不死图腾；没有图腾保住生命时才使用本模组次数。免死会恢复到最大生命、清除燃烧、冻结积累和摔落距离，保持原地，并给予配置效果。它不会发生死亡、掉落或真正的重生。

`invulnerabilitySeconds` 是本次免死授予的无敌窗口，按服务器 tick 计时并持久化剩余截止点；窗口内不重复消耗次数。原版绕过无敌的伤害标签继续生效，包括 `/kill` 和虚空，因此原地免死不用于虚空传送救援。已授予的无敌和药水有各自持续时间；修改资格只影响后续激活，重置 `revive` 或设置已用次数会清除现有无敌窗口。

### PvP、减伤与仇恨

`pvp.removeOnAttack: true` 时，对其他玩家发起进入伤害判定的攻击会永久解除攻击者的 PvP 保护，即使对方的保护使本次攻击最终无效；其他保护独立保留。设为 `false` 时，受保护玩家也不能伤害其他玩家，避免单向无敌。支持原版可归属的投射物和驯服者归因；放置岩浆、点火、机关等无法直接归属的环境伤害不保证识别为 PvP。

`damageReduction.reduction` 为 0～1，例如 0.25 表示减免 25%，1 表示完全阻止普通伤害；在护甲和药水减伤前生效。它也尊重原版绕过无敌的伤害标签。

`mobRange.multiplier` 为 0～1，乘以原本的发现/跟随范围；只影响怪物，不影响普通动物、玩家交互距离或世界其他玩家。同时约束常规目标搜索和已经存在的目标；主动攻击怪物后仍允许其反击。使用自定义 AI、绕过原版目标接口的模组生物需要单独验证。

### 状态效果

`effects.list` 是保护期间每 20 tick 刷新的效果；`revive.effects` 仅在实际免死时给予。等级 1 对应原版 amplifier 0，范围 1～256；`durationSeconds` 为 1～86400。仅支持原版持续效果，拒绝瞬间治疗、瞬间伤害等瞬时效果。

效果保护结束或被取消时停止刷新，让已给予的效果自然到期；不主动删除其他药水或信标的同类效果。已有更强效果沿用原版叠加规则。常驻效果建议使用 15 秒，以避免夜视在刷新间隔中闪烁；配置较长持续时间也会延长停止刷新后的残留时间。牛奶清除后，下次刷新会恢复仍有效的保护效果。

## 自动取消条件

每个功能都可以配置 `cancel`，字段与示例相同：完成任一指定进度、获得指定物品或标签物品、达到经验等级、累计游玩达到指定分钟。`experienceLevel: 0` 和 `playMinutes: 0` 表示不设置该条件。

- 默认 `requireAll: false`，任一已配置条件触发就永久取消这项保护。
- `requireAll: true` 时，每个列出的条件都需要达成；达成历史会保存，允许先拿到物品、之后达到等级。
- 物品被丢弃、经验下降、进度被撤销均不会重新获得保护。管理员可以显式重置，但当前仍满足的条件会再次触发。
- 从背包写入、原版库存进度触发点、等级/进度变化及每 tick 检查观测条件。配置生效时已持有的物品也算获得；无法追溯安装前已经获得又丢弃的物品，绕过这些接口且在同一 tick 内移除物品的第三方实现也可能无法观测。

运行状态保存在世界的 `data/newcomer-protection-state.json`，包含各 UUID 的取消原因、条件历史、免死次数和无敌截止点。不要在运行中手工修改该文件；使用管理命令。

## 命令

所有玩家可使用 `/newcomer status` 查看个人状态，`/newcomer help` 查看入口。

管理员命令要求原版 GAMEMASTERS 权限（通常为 OP 2 级），支持服务器控制台。UUID 参数既支持在线也支持从未加入的离线 UUID；可先通过 `lookup` 获取已知玩家的 UUID。所有配置修改均先校验、再保存并生效。

| 命令（均以 `/newcomer admin` 开头） | 含义 |
| --- | --- |
| `help` | 列出管理入口；命令与功能名支持 Tab 补全，UUID 提供在线及已配置候选 |
| `lookup <玩家名或选择器>` | 查询玩家名对应 UUID；离线模式未知名字的解析遵循原版规则，应优先使用已知玩家或准确 UUID |
| `list [页码]` | 每页列出 20 个有独立配置的 UUID |
| `enabled <true或false>` | 保存全局启用开关 |
| `reload` | 重新读取并校验配置，失败时保留正在使用的配置 |
| `defaults` | 查看默认规则 |
| `defaults set <功能> <分钟>` | 修改默认累计时间阈值 |
| `defaults rule <功能> <JSON对象>` | 合并修改默认规则的任意受支持字段 |
| `player <UUID>` | 查看该玩家各项期限、取消原因和免死次数；离线时不虚构剩余时间 |
| `player <UUID> show` | 查看该 UUID 的完整生效配置 |
| `player <UUID> set <功能> <分钟>` | 设置累计游玩阈值，不清除已有取消状态 |
| `player <UUID> grant <功能> <分钟>` | 在线玩家从当前游玩时间起再授予指定分钟，并重置该项状态；负数长期、0 禁用也支持离线 UUID |
| `player <UUID> rule <功能> <JSON对象>` | 合并修改该 UUID 的规则 |
| `player <UUID> clear <功能或all>` | 删除配置覆盖，恢复继承；不重置次数或取消状态 |
| `player <UUID> cancel <功能或all>` | 持久取消保护 |
| `player <UUID> reset <功能或all>` | 重置取消状态和对应条件历史；重置 revive 还会清零已用次数及无敌窗口 |
| `player <UUID> revives <已用次数>` | 设置已用免死次数，同时清除当前无敌窗口 |
| `copy <源UUID> <目标UUID>` | 复制生效规则，不复制取消历史或已用次数 |

功能参数为 `inventory`、`experience`、`score`、`revive`、`effects`、`pvp`、`damageReduction`、`mobRange`。

示例：

```text
/newcomer admin defaults set pvp 120
/newcomer admin player 00000000-0000-0000-0000-000000000001 set inventory -1
/newcomer admin player 00000000-0000-0000-0000-000000000001 rule experience {"minutes":60,"ratio":0.5}
/newcomer admin player 00000000-0000-0000-0000-000000000001 rule revive {"minutes":120,"uses":5,"invulnerabilitySeconds":3}
/newcomer admin player 00000000-0000-0000-0000-000000000001 rule inventory {"cancel":{"experienceLevel":30}}
```
## 开发环境

| 工具或依赖 | 固定版本 |
| --- | --- |
| Minecraft Java | 26.2 |
| Java toolchain | 25 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.161.0+26.2 |
| Fabric Loom | 1.17.21 |
| Gradle Wrapper | 9.5.1 |

安装 JDK 25，让 `java` 与 `javac` 可用，然后在项目根目录执行以下 PowerShell 命令。Gradle Wrapper 自动下载固定版本的 Gradle，无需全局安装。项目不记录机器专属的 JDK 位置。

```powershell
.\gradlew.bat --version
.\gradlew.bat build
.\gradlew.bat genSources
```

构建产物位于 `build/libs/`。使用 IntelliJ IDEA 打开项目根目录并导入 Gradle，选择 JDK 25；VS Code 可使用 Java 与 Gradle 扩展导入此工程。调试入口由 Loom 生成。

```powershell
.\gradlew.bat runServer
```

首次运行会生成开发服务器文件。请阅读 Minecraft EULA 后自行决定是否接受，项目不自动接受协议。`run/` 中的世界、玩家数据、配置和日志不会进入 Git。

## 工程约束

- `fabric.mod.json` 使用 `environment: server` 与专用服务器入口。
- Loom 使用 `serverOnlyMinecraftJar()`，开发范围限定为服务端。
- 26.2 使用 Mojang 未混淆名称和 `net.fabricmc.fabric-loom` 插件，无需 Yarn 或 remap 插件。
- 不注册新物品、状态效果类型或自定义网络通道。
- 局部 Mixin 与 Fabric 事件配合处理死亡、伤害、目标范围、进度和物品变化；不改变全局游戏规则。

## 验证

`build` 会编译模组并运行独立的服务端 GameTest。也可以单独执行：

```powershell
.\gradlew.bat runGameTest
```

测试覆盖 UUID 覆盖和独立期限、部分槽位与标签保留、经验比例与分数、死亡快照保存恢复、图腾与免死次数、无敌到期、PvP 与箭矢、减伤、仇恨范围、自动取消及历史、管理员权限、离线配置和错误重载。GameTest 使用隔离的测试服务器数据，不要求启动正式服务器。测试模组不会打入发布 Jar。

可行性依据和工程准备阶段的历史验证见 [调研记录](docs/feasibility.md)。自动化测试不能代替原版客户端连接和目标模组组合的实际验收。

## Git

工程包含 `.gitignore`、`.gitattributes` 和 `.editorconfig`，使用 `main` 分支。开发服务器、玩家配置、运行状态、日志和构建缓存均被忽略。

## 构建后的检查

每次构建成功后，在项目根目录扫描当前源码、脚本和可提交配置，包括新增未跟踪文件：

```powershell
$taskScanFiles = @(git ls-files --cached --others --exclude-standard)
betterleaks dir @taskScanFiles --redact
```

这里扫描当前文件内容，包括未跟踪文件，不扫描 Git 历史。`build/` 和 `run/` 等忽略目录包含自动生成的服务器配置与本机数据，不属于可提交源码。如 Windows 默认 RE2 引擎发生缓存跨磁盘移动错误，使用 `--regex-engine stdlib` 重跑。只有扫描完成且退出码为 0 才视为通过。

## Windows 临时目录问题

本次在 Windows 的 Codex 命令环境中遇到 Java NIO `Unable to establish loopback connection`，以及 Loom 在用户临时目录创建 IPC 文件失败。使用项目内被 Git 忽略的临时目录后，构建和 `genSources` 均通过。普通终端不一定需要这项设置；若重现同一错误，可仅在当前 PowerShell 进程中设置：

```powershell
$taskJavaTemp = Join-Path $PWD '.gradle/socket-temp'
New-Item -ItemType Directory -Path $taskJavaTemp -Force | Out-Null
$taskPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$taskPreviousJavaOptions -Djdk.net.unixdomain.tmpdir=`"$taskJavaTemp`" -Djava.io.tmpdir=`"$taskJavaTemp`""
    .\gradlew.bat build
    .\gradlew.bat genSources
} finally {
    $env:JAVA_TOOL_OPTIONS = $taskPreviousJavaOptions
}
```

这段命令在运行时计算目录，不保存本机绝对路径，也不改变全局 Java 设置。相关现象见 [Windows Java NIO 问题记录](https://github.com/openai/codex/issues/40902)。若当前环境需要 HTTP 代理，Java 还需在命令级别配置对应 `http(s).proxyHost` 和 `http(s).proxyPort`；地址及凭据不应写入可提交的工程配置。`build` 与 `genSources` 应分开调用，以避免当前 Loom 版本在合并任务图时的隐式依赖校验错误。
