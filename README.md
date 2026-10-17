# Newcomer Protection

Minecraft Java 26.2 的纯服务端 Fabric Mod。按玩家在本服务器的累计游玩时长提供个人死亡不掉落、经验保留及可配置的原版状态效果。玩家使用原版客户端即可。

## 安装和配置

将构建产物 `build/libs/newcomer-protection-0.1.0-SNAPSHOT.jar` 和对应的 Fabric API 放入 Fabric 26.2 服务器的 `mods/` 目录。首次启动自动生成 `config/newcomer-protection.json`，修改后重启服务器生效。

默认配置：

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

- `enabled` 控制新人保护；`protectionSeconds: 0` 关闭保护。时长必须为非负整数且转换为 tick 后不能溢出。
- 保护条件为原版 `play_time` 严格小于 `protectionSeconds × 20`。它包含安装前已有的统计和挂机时间；低 TPS 时不等于真实在线秒数。
- `keepInventory` 和 `keepExperience` 可以独立开关。物品保留覆盖普通背包、装备、副手和消失诅咒物品。原版全局 `keep_inventory` 规则继续生效。
- 死亡瞬间固定本次保留判定，并随玩家数据保存；死亡界面等待跨过阈值、死亡后重连或正常重启不会改变本次判定。
- `effects` 可设为 `[]`。仅接受已注册的 `minecraft:` 常驻效果，等级范围为 1～256；瞬间治疗、瞬间伤害和未知效果会报错并阻止启动，避免静默忽略错误配置。缺省字段使用默认值。
- 登录、重生后立即补充效果，在线期间每 20 tick 刷新。效果持续 300 tick，保护结束后停止刷新，最多再持续约 15 秒（20 TPS）。不主动清除同类药水或信标效果；更强效果按原版规则共存。牛奶移除效果后，下次刷新会恢复保护增益。

与墓碑、背包或其他修改死亡掉落的模组组合时，需要在目标服务器进行兼容性验证。

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
- 两个局部 Mixin 处理死亡判定、掉落和保存；Fabric 事件处理重生复制与效果刷新。

## 验证

`build` 会编译模组并运行独立的服务端 GameTest。也可以单独执行：

```powershell
.\gradlew.bat runGameTest
```

测试覆盖背包装备、消失诅咒、经验保留、阈值边界、死亡判定保存恢复、独立开关、全局规则、效果与配置错误。GameTest 使用隔离的测试服务器数据，不要求启动正式服务器。测试模组不会打入发布 Jar。

可行性依据和工程准备阶段的历史验证见 [调研记录](docs/feasibility.md)。自动化测试不能代替原版客户端连接和目标模组组合的实际验收。

## Git

工程包含 `.gitignore`、`.gitattributes` 和 `.editorconfig`。初始化使用 `main` 分支，提交身份沿用现有 Git 配置。远端仓库、首次提交和发布许可留待后续确定。

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
