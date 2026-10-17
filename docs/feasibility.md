# Gentle Start：Minecraft 26.2 Fabric 新玩家保护可行性

调研日期：2026-10-17。结论：能够实现纯服务端 Mod，让累计在本服务器游玩时长低于阈值的玩家获得死亡不掉落和可配置的原版状态效果，原版客户端可以参与。本文保留调研与工程准备阶段的依据；后续已实现玩法、配置与服务端 GameTest，当前使用方式见 [README](../README.md)。

## 版本证据

Mojang 官方版本清单中，26.2 为正式版，发布时间为 2026-06-16，所需 Java 主版本为 25。Fabric 官方提供 26.2 支持与对应构建模板。26.2 使用未混淆的 Mojang 名称，不需要 Yarn 映射或旧 remap 插件。

工程固定 Minecraft 26.2、Fabric Loader 0.19.5、Fabric API 0.161.0+26.2、稳定版 Loom 1.17.21 与 Gradle 9.5.1。已检查 Loom 发布元数据要求 Gradle API 9.5.0，Fabric API 包声明 Loader >=0.18.4、Java >=25、Minecraft ~26.2-；此组版本满足这些依赖声明，本次实际构建与服务端入口加载均通过，具体证据见文末。

来源：

- [Mojang 版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [26.2 版本元数据](https://piston-meta.mojang.com/v1/packages/91ef51b7d36ca092c5c02aba42f0f40c7cc1db84/26.2.json)
- [Fabric 对 26.2 的说明](https://fabricmc.net/2026/06/15/262.html)
- [Fabric 对未混淆构建与新 Loom 插件的说明](https://fabricmc.net/2026/03/14/261.html)
- [官方 26.2 工程模板](https://github.com/FabricMC/fabric-example-mod/tree/26.2)
- [Loom 1.17.21 发布元数据](https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.17.21/fabric-loom-1.17.21.module)

## 时间口径

最小方案复用原版 `Stats.PLAY_TIME`。当前 26.2 服务端的 `ServerPlayer.doTick()` 每个执行的玩家 tick 增加该统计；玩家统计由 `PlayerList.getPlayerStats()` 按 UUID 获取，`ServerStatsCounter` 负责保存与读取统计文件。因此能够使用服务器已有的累计数据，死亡、退出再进入和正常服务器重启不会主动清零累计时间。

判定采用严格小于：

```text
累计 play_time ticks < protectionSeconds × 20
```

例如 7200 秒对应 144000 tick，在正常 20 TPS 下为两小时。原版统计包含挂机，也会继续统计处于死亡界面的玩家；低 TPS 或修改 tick rate 时，tick 数与真实秒数不完全一致。若需求是精确的真实在线时间、排除挂机、或只计算安装 Mod 后的时间，需要单独设计计时和持久化，不能把原版统计当成等价实现。

玩家身份沿用服务器的 UUID。迁移服务器、改变在线认证模式、更换身份映射或删除统计文件，都可能影响历史时长识别。已有服务器的统计数据需要在功能实施前确认。

## 单独死亡不掉落

原版 `keep_inventory` 是世界规则，不提供按玩家设置的入口。不能通过临时修改这个全局规则来实现个别玩家保护，否则可能影响其他玩家及其他事件处理器。

本次读取了 Mojang 发布的 26.2 服务端 Jar，并校验其 SHA-1 为 `823e2250d24b3ddac457a60c92a6a941943fcd6a`，随后用 JDK `javap` 检查实际字节码。确认的调用关系如下：

1. `ServerPlayer.die()` 调用 `LivingEntity.dropAllDeathLoot()`，后者分别调用物品和经验掉落处理。
2. `Player.dropEquipment()` 在 `KEEP_INVENTORY` 为假时销毁消失诅咒物品，并调用 `Inventory.dropAll()`。
3. `Player.getBaseExperienceReward()` 依据 `KEEP_INVENTORY` 决定死亡经验奖励。
4. `ServerPlayer.restoreFrom()` 只在非死亡完整转移、开启 `KEEP_INVENTORY`、或旧玩家为旁观者时，调用 `transferInventoryXpAndScore()` 转移物品、经验和分数。
5. `Inventory.replaceWith()` 通过完整容器槽位接口进行转移。26.2 背包与装备存储结构有变化，后续实现应沿用这一接口，避免只复制普通背包而遗漏装备。

因此，建议在玩家死亡时固定本次保护判定，在局部 Mixin 中调整对应玩家的原版物品掉落判定，并在重生数据转移时使用同一判定。若提供经验保留选项，经验球生成与重生经验转移也要一致处理。玩家仍正常死亡、出现死亡界面并重生；不应通过禁止死亡代替死亡不掉落。

Fabric 的玩家复制/重生事件可用于补充数据和效果，但单独在死亡后事件里恢复背包不足以保证正确性，因为掉落可能已经发生。后续必须覆盖死亡界面等待超过阈值、死亡后断线重连以及消失诅咒物品，避免物品丢失或复制。

另已核对本工程实际使用的 Fabric API 0.161.0+26.2 所含实体事件模块 `fabric-entity-events-v1 5.0.5+06488ac19e` 的源码：`ServerPlayerEvents.COPY_FROM` 在 `ServerPlayer.restoreFrom()` 的 TAIL 触发，参数为旧玩家、新玩家与 `alive`；`AFTER_RESPAWN` 提供相同类型的参数。这证明可以在原版复制完成后转移自有状态，但死亡时的掉落判定仍需要前述局部处理。来源：[对应官方模块源码 Jar](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-entity-events-v1/5.0.5%2B06488ac19e/fabric-entity-events-v1-5.0.5%2B06488ac19e-sources.jar)。

来源：[已核验的 Mojang 官方 26.2 服务端 Jar](https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar)。该链接为二进制，不是源码页面；上述方法结论来自本次对其字节码的直接检查。

## 可配置状态效果

可按配置的原版效果 ID 构造 `MobEffectInstance` 并调用 `LivingEntity.addEffect()`。例如速度、抗性、生命恢复、夜视、抗火等，使用原版网络协议同步给客户端，无需安装客户端 Mod。

建议配置使用直观的等级值：`level: 1` 转换为游戏的 `amplifier: 0`。支持显示粒子与图标选项。登录、重生后补充效果，并以有限短持续时间定期刷新；保护结束后停止刷新，让本次效果自然过期。不要无条件清除同 ID 的全部效果，否则可能误删玩家饮用药水或信标给予的效果。若要求阈值到达时立刻撤销且完整保留其他来源效果，需要另外明确来源管理语义。

瞬间治疗、瞬间伤害等瞬时效果需要单独规定触发方式，不能默认作为常驻增益循环施加。自定义新的状态效果类型、物品、图标或界面超出了这里的原版客户端兼容范围。

来源：[Fabric 26.2 状态效果文档](https://docs.fabricmc.net/develop/entities/effects)。

## 配置

```json
{
  "enabled": true,
  "protectionSeconds": 7200,
  "keepInventory": true,
  "keepExperience": true,
  "effects": [
    {
      "id": "minecraft:resistance",
      "level": 1,
      "showParticles": false,
      "showIcon": true
    },
    {
      "id": "minecraft:night_vision",
      "level": 1,
      "showParticles": false,
      "showIcon": true
    }
  ]
}
```

以上字段已实现，首次启动会生成默认配置。读取时验证时长、等级和效果 ID，错误配置会明确报错并阻止启动。具体生效与效果过期规则见 README。

## 后续功能验收

- 阈值前、等于阈值、超过阈值的两类玩家同时在线，死亡行为只改变受保护者。
- 普通背包、护甲、副手、消失诅咒物品、经验球与重生经验一致；打开容器时死亡不产生物品复制。
- 死亡界面等待跨过阈值、死亡后退出再进入、跨维度和重启后数据一致。
- 牛奶、正常药水和信标效果并存；保护结束后按约定停止增益。
- 原版客户端能够连接；与目标服务器已有掉落/墓碑/背包 Mod 的 Mixin 兼容性需在真实模组组合下验证。

以上场景用于后续验收。工程准备阶段只验证了构建和加载；新增的自动化测试及其覆盖范围见 README，原版客户端及目标模组组合仍需实际验证。

## 工程准备阶段的历史验证结果

| 检查 | 结果及范围 |
| --- | --- |
| JDK | 已使用 Java / javac 25.0.4.1；工程 toolchain 和 release 固定为 25 |
| Gradle Wrapper | 9.5.1 官方 Wrapper Jar 和 Gradle 分发 ZIP 的 SHA-256 均核验成功 |
| `build` | BUILD SUCCESSFUL，生成 0.1.0-SNAPSHOT 工程骨架 Jar；当前没有功能测试，`test` 为 NO-SOURCE |
| `genSources` | 单独执行成功，生成服务端源码供 IDE 浏览 |
| `runServer` | Fabric 识别 Minecraft 26.2、Loader 0.19.5、API 0.161.0+26.2 和新模组；专用服务器初始化日志出现 |
| EULA / 游戏运行 | 保持 eula=false，在协议检查处结束；未启动世界或验证客户端连接、死亡和效果行为 |
| Jar 检查 | 版本变量已展开；environment 为 server；server entrypoint 正确；Java class major 为 69 |
| BetterLeaks | 使用 --redact --regex-engine stdlib 扫描源码、脚本和全部可提交配置，包括新增未跟踪文件；退出码 0，未发现泄漏 |
| Git | 已初始化 main 分支，设置仓库本地 LF 规则，沿用已有提交身份；尚无首次提交和远端，工程文件仍为未跟踪状态 |

Windows 当前命令环境的 Java NIO 与 Loom IPC 临时文件问题，已通过命令进程内的临时目录设置解决；HTTP 代理只在验证进程中使用。README 记录了可复现的目录设置，工程文本不包含本机绝对路径或代理地址。服务端加载时还出现 Windows 性能计数器的系统信息采集警告，但未阻止模组初始化或预期的 EULA 检查退出。
