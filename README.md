# CommandGrants

[![构建](https://github.com/EastTnT/commandgrants/actions/workflows/build.yml/badge.svg)](https://github.com/EastTnT/commandgrants/actions/workflows/build.yml)
[![许可: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-62b47a.svg)](https://www.minecraft.net/)
[![加载器: Fabric](https://img.shields.io/badge/loader-Fabric-dbb69c.svg)](https://fabricmc.net/)

在**关闭作弊**的情况下，让主机把一部分指令权限开放给指定玩家。

原版只有「允许作弊」这一个总开关：要么所有人（含自己）拿到全部指令，要么谁都没有。这个模组把它拆成**按玩家、按指令**的细粒度授权，房主照样可以不开作弊。

- 目标版本：**Minecraft 26.2**
- 加载器：Fabric Loader 0.19.5+
- Java：25
- 环境：单人/局域网房主、专用服务器管理员

## 安装

要**两端都装**：这个模组同时改了服务端（裁剪解析用的调度器）和客户端（收命令包时过滤补全），只装一边不会生效。

1. 装 [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5 或更高，以及 [Fabric API](https://modrinth.com/mod/fabric-api) `0.161.0+26.2`；
2. 把 `commandgrants-1.0.0.jar` 和 Fabric API 一起放进 `mods/`；
3. 客户端用 Java 25 启动（26.2 的要求），进世界后在「对局域网开放」里就能看到按钮。

专用服务器：同样放进 `mods/`，`server.properties` 里把 `allow-cheats` **保持 `false`**，用下面那组 `/commandgrants` 指令维护授权（需要 op）。


## 它做了什么

授权分三层落地，缺一不可：

| 层 | 实现位置 | 作用 |
| --- | --- | --- |
| 权限本身 | `GrantedPermissionSet` | 包装玩家原有的 `PermissionSet`，额外答「是」于被开放的 `Permission.HasCommandLevel` |
| 服务端执行 | `mixin/ServerGamePacketListenerImplMixin` | 执行前把调度器换成裁剪过的版本，未开放指令解析即失败 |
| 客户端补全 | `mixin/ClientboundCommandsPacketMixin` | 构造 `ClientboundCommandsPacket` 时就地过滤命令树，未开放的指令根本不会发给客户端 |

第三层才是真正的安全边界。26.2 的指令执行路径上**没有**运行时权限检查，唯一的闸门是构造命令包时的 `child.canUse(commandFilter)`；因此过滤必须发生在**打包那一刻**，而不是等玩家敲下去再拒绝。这样未开放的指令连补全都不会出现。

反过来，`GrantAccess#reachable` 只做**细化**、绝不放宽：原版本来就免费给所有人的指令（`/help`、`/list` 等，需求等级为 `ALL`）、玩家本来就有权限的指令、以及**其他模组自己注册的**检查（不是本游戏的 `Commands.LEVEL_*`）一律保留。一个只被开放了 `/time` 的玩家，其余原有能力一点不少。

唯一会同时动到两个名字的地方是**重定向别名**：`/tp` 是重定向到 `/teleport` 的第二个根字面量，它们是同一条指令的两个名字，所以开一个就是开两个（见下文维护须知）。

## 怎么用

### 单人 / 局域网

1. 世界内按 `Esc` → **对局域网开放**（`MultiplayerOptionsScreen`）。
2. 标题栏左侧会多出一个 **CommandGrants** 按钮（紧挨标题，不与底部 Apply/Cancel 行重叠）。
3. 打开后按玩家、按指令勾选要开放的项；预设可以一键套用（`None` / `Moderator` / `Gamemaster`）。
4. 原版的全局「允许作弊」开关保持关闭即可。

一行就是一条指令，但**一条指令可能有两个名字**：勾「teleport / tp」这一行会同时开放这两个写法，勾哪一行都一样。

按钮只对**世界所有者**点亮。关闭作弊的单人世界里房主的权限集是普通 `ALL`，所以按钮的可用性直接问集成服务器（`IntegratedServer#isSingleplayerOwner`），而不是猜权限等级——否则这个模组最该出现的场景里按钮反而是灰的。

### 专用服务器

在 `server.properties` 保留 `allow-cheats=false`，由 op 用指令维护。根指令要求 `OWNERS` 级权限，也就是 op：

```
/commandgrants list                  打印当前授权表
/commandgrants catalog               打印所有可开放的指令（授权时要拼对名字）
/commandgrants open <玩家> <指令>    开放一条
/commandgrants close <玩家> <指令>   收回一条
/commandgrants preset <玩家> <预设>  套用预设：none / moderator / gamemaster
/commandgrants clear                 清空整张表
/commandgrants enable <true|false>   全局停用本模组
```

`open`/`close` 要求显式写出玩家名：Brigadier 无法把 `/commandgrants open tp` 和 `/commandgrants open Alex tp` 区分开。玩家参数是 `EntityArgument.player()`，所以对方得在线；离线玩家的授权在表里仍然保留，重新上线即生效。

授权表存于 `<世界目录>/commandgrants.json`，按玩家 UUID 记录，服务器重启后仍然有效。

### 谁能改

服务端 `CommandGrantsServer#mayEdit` 是唯一判据，三个入口（局域网菜单、指令、网络包）共用：集成服务器上只有房主，专用服务器上只有 op。客户端按钮的亮灭只是方便，就算绕过它也改不动——每个网络包的接收端都会重新校验。

## 构建

```powershell
$env:JAVA_HOME = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon"   # JDK 25
$env:GRADLE_USER_HOME = "$PWD\.cache\gradle-home"
.\gradlew.bat build --no-daemon --console=plain
```

产物：`build/libs/commandgrants-1.0.0.jar`。

26.2 是**未混淆**的，所以整个工程没有 Yarn 映射、没有 refmap，直接对 `net.minecraft.*` 编程；Loom 也必须用 `net.fabricmc.fabric-loom` 这个非混淆管线插件（`fabric-loom` 遗留 id 会报 `Configuration 'mappings' has no dependencies`）。

### 首次全新构建要跑两次

这是 Loom 1.18.2 + 未混淆管线的已知坑，不是本工程代码的问题，但会让人以为构建坏了：

- Loom 生成合并后的 Minecraft jar 时，并发写同一个 zip 文件系统会抛 `CREATE_NEW` 冲突，而 `close()` 把它吞了，结果落盘一个 **22 字节的空 jar**；
- `ensureMergedMinecraftJar` 任务用 `tools/MergeJars.java` 的产物把它换掉；
- 但**全新状态下这个 22 字节 stub 没有 `.backup` 兄弟文件**，Loom 的 `AbstractMappedMinecraftProvider#shouldRefreshOutputs` 会因为「备份缺失」判定需要重新处理，走 `AccessWidenerJarProcessor` 去写一个只读的 zip 文件系统，于是配置期直接抛 `java.nio.file.ReadOnlyFileSystemException`。

所以：**全新克隆后第一次 `build` 大概率失败，再跑一次就好**（第二次备份已就位，进入稳态增量构建）。成功与否只看 `BUILD SUCCESSFUL`，沙箱下 Gradle 每次都会额外打一行 `Couldn't open current thread, error = 5` 并以 `exit code 1` 结束，那是噪音。

一旦 `ensureMergedMinecraftJar` 修好过 jar，它的 `.backup` 必须**与 jar 内容保持一致**（任务里就是把它写成同样的字节）。删掉 `.backup` 会让下一次构建重新掉进上面那个只读文件系统的坑——这正是本工程早期「构建成功/失败交替出现」的根因。

## 测试

`src/gametest/` 下是 Fabric Client GameTest，会在真实客户端里建一个 **关闭作弊的平坦世界** 并断言：

1. 局域网菜单上出现且**启用**了编辑器按钮（关闭作弊的单人世界里房主也必须能用，这是本模组的主场景）；
2. 编辑器能打开，并且**真的画出了指令行**——行数等于满页、每行都取自客户端刚收到的目录（见上节「只测服务端会漏掉整类 bug」）；每行还必须在**布局算出来的位置**上（单列、y 递增、不越出屏幕，见下节「编辑器为什么会叠在左上角」）；
3. 命令目录与真实树一致：`/time`、`/gamemode` 可授权，`/help`、`/list` 不可；
4. `GrantAccess` 裁剪后的命令树：喂给「只有 `/time` 授权的客人」时 `/time` 在、`/gamemode` 与另一条受限指令不在，而原版免费指令 `/help`、`/list` **仍在**；喂给合成出来的 GAMEMASTERS 权限集时本来就该有的指令都在，高于该等级的指令仍被裁掉；
5. 裁剪后的调度器**执行**时，`/time set day` 能过、`/gamemode creative` 和那条受限指令被拒（`Unknown or incomplete command`）；
6. 重定向别名成组：`/tp` 确实是根字面量、确实带 `redirect`，且与 `/teleport` 同组；勾长名后 `/tp` 能跑、勾短名后 `/teleport` 能跑，编辑器那一行的文案等于 `teleport / tp`（见下节「一条指令可能有两个根名字」）；
7. 窗口缩放后重新分页：`600x480 → 600x240 → 854x480` 三档下，画出来的行数必须等于 `GrantEditorScreen#rowsPerPage()` 当场算出的值。

第 7 条用的是 API 自己的 `WindowHooks#fabric_resize`，**不能**直接调 `Screen#resize`：后者绕开窗口，会让布局拿到一个现实中不存在的坐标系（曾得到 `y=-2647` 这种坐标，那是测试的错，不是界面的错）。另外尺寸是**窗口像素**，要按 GUI 缩放折算——测试环境默认窗口是 `854x480`（GUI scale 2，即 `427x240` 逻辑像素），写 `300x200` 已经占掉逻辑屏幕的一半以上。

第 5 条**刻意用 `execute` 而不是 `parse` 判定**：Brigadier 的 `parseNodes` 会把每个候选节点的失败都吞进 `ParseResults` 里，所以在完全没有 `/gamemode` 的调度器上 `parse("gamemode creative")` 既不抛异常、也不消费输入，读 `getReader().canRead()` 根本分不出「解析成功」和「立刻放弃」。抛 `Unknown or incomplete command` 的是 `execute`，而 `execute` 正是 `Commands.performCommand` 对裁剪后调度器做的事。

「另一条受限指令」是**搜出来的**而不是写死的：`/op`、`/deop`、`/stop`、`/publish` 只在 `Commands` 的 `includeDedicated` 分支注册，而集成服务器是 `false`，所以这些指令在单人世界里根本不存在，写死会得到一个恒假的断言。

`/op` 之类不在单人世界里，这也说明测试不能假设「指令一定存在」；同理，**断言树里少了某条指令时不能用「解析结果」当证据**——第 5 条因此用 `execute` 判「能不能跑」，而不是用 `parse` 判「有没有解析出来」。

```powershell
.\gradlew.bat runClientGameTest --no-daemon --console=plain
```

一轮约 70~90 秒（含世界创建）。game test 源集由 `build.gradle` 里的 `fabricApi.configureTests { ... }` 生成，源集根是 `src/gametest/java`。

专用服务器那一侧用 `.\gradlew.bat runServer --no-daemon --console=plain` 起（配置在 `build.gradle` 的 `loom { runs { server { ... } } }`，运行目录 `run/server`）。已验证启动日志里 `Env=SERVER` 下 `[CommandGrants] initialized`、无 mixin 报错——`commandgrants.mixins.json` 里只有 `MultiplayerOptionsScreenMixin` 挂在 `client` 列表下，所以它不会被加载到专用服务器上。

`commandgrants.mixins.json` 的 `compatibilityLevel` 必须跟着 `build.gradle` 的 `options.release` 走，两者现在都是 **25**。此前它写着 `JAVA_21`，于是 6 个 mixin 每次启动都在 `debug.log` 里各刷一条 `Class version 69 required is higher than the class version supported by the current version of Mixin (JAVA_21 supports class version 65)`——不影响功能，但是纯噪音，而且会让人误以为 mixin 没挂上（同一条日志里 `Mixing ... into ...` 才是挂上了）。

## 已知限制

- 授权变更后**不需要重连**：`CommandGrantsServer#pulse` 会重建派生表、重算权限并给每个在线玩家重发一次命令包（`server.getCommands().sendCommands(player)`），所以补全会当场更新。原版只在 profile 等级变化时才重发命令包，这也是这里必须显式重发的原因。
- 授权按**根指令**发放（`/time` 这一级），所以给 `/time` 就是给它整棵子树。反过来，某条根指令的等级取的是它**子树里的最高要求**，和原版 `fillUsableCommands` 逐节点判定的口径一致。
- 该模组把「允许作弊」的原版语义保留为总闸；打开作弊时一切照旧，本模组不介入。

## 维护须知：命令等级不是直接挂在节点上的

想知道某条指令要几级，不能拿 `node.getRequirement()` 去和 `Commands.LEVEL_GAMEMASTERS` 这些常量做恒等比较——**永远不会相等**。`Commands.hasPermission(PermissionCheck)` 会把它包一层再挂上去：

```java
// Commands.hasPermission 的字节码就是：new PermissionProviderCheck + 挂到节点上
public final class PermissionProviderCheck<T extends PermissionSetSupplier> implements Predicate<T> {
    private final PermissionCheck test;
    public boolean test(T source) { return this.test.check(source.permissions()); }
}
```

所以节点上躺的是 `PermissionProviderCheck`，真正的 `PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER)` 在它的 `test()` 里。本工程用 `VanillaLevel.of(Predicate)` 先剥这层壳、再解出 `PermissionLevel`；`CommandRequirements`（发现等级）和 `GrantAccess`（判定可达）共用它。

**这个坑的可怕之处在于它的失败方向**：恒等比较恒假 ⇒ 「读不出等级」⇒ 代码把「无闸门」当成默认结论 ⇒ 过滤静默退化成 no-op，不报任何错。本工程曾经就是这样——`mirror`/`trim` 每次都返回新对象（引用不等，看起来「过滤过了」），内容却一字未删。所以 `VanillaLevel` 对读不出的情形返回 `null`，由调用方显式决定，而不是直接猜 `ALL`。

## 维护须知：房主给自己授权时不能跳过权限包装

`CommandGrantsServer#permissionsFor` 必须**一视同仁地**把 `GrantedPermissionSet` 套在每个人身上，包括单人世界的房主。这里曾经有一条看起来无害的短路——「房主在自己的世界上本来就什么都持有，包装是多余的」——它正是用户报的「勾了指令还是不能用」的直接原因：

- 「关闭作弊」的单人世界里，房主 `getProfilePermissions(...)` **一个等级权限都没有**（测试里 `gametest: host holds no level permissions` 就是这条），所以「房主本来就什么都持有」这个前提是假的。
- 于是房主给自己勾的授权被整个丢掉：裁剪树里进不来 `/gamemode`（他自己看不到补全），解析也过不去（服务端 `parseCommand` 用同一份权限判 `requires`），表现就是原版的「未知或不完整的命令」。
- `GrantedPermissionSet#hasPermission` 是 `base || grants`，**只放宽不缩小**，所以把包装套回房主身上不会动他原有的任何能力；`table.isEmpty()` 那条短路已经把「没有授权 = 完全原版」保住了。

**必须走生产路径才测得到**：授权的读取（`manager.permissionsFor`）、裁剪（`manager.filteredRoot`）、闸门（`manager.narrowedDispatcher`）三者要由**同一个管理器、同一个玩家**串起来断言。用测试自己拼的 `GrantAccess` + 手搓权限集，这条短路永远不会被触发——这也是它此前一路绿着过去的原因。

## 维护须知：编辑器为什么需要一份玩家名单

`GrantEditorScreen` 只有在**名单非空**时才会画出指令网格：`players.isEmpty()` 会让 `selectedPlayer` 变成 `null`，于是走 `commandgrants.editor.noPlayers` 分支，一条指令都不画。而 `playerId == null` 还会让每个复选框的 `grantable()` 判断和 `toggle` 一起失效。

名单来自服务端的快照，服务端原本只用 `server.getPlayerList().getPlayers()` 拼它——**这在加入事件里是空的**：Fabric 的 `ServerPlayConnectionEvents.JOIN` 触发时玩家还在入场流程中，尚未登记进玩家列表。于是「进世界后收到的第一份快照」名单为空，界面一片空白，直到有别的玩家上线触发第二次同步才恢复。

修法是 `CommandGrantsServer#onlineGrants(ServerPlayer extra)` + `CommandGrantsNetwork.snapshot(manager, extra)`：把 `ServerPlayConnectionEvents.JOIN` 手里的 `handler.getPlayer()` 显式塞进名单（`LinkedHashMap` 取并集，存量记录在前、在线玩家 `putIfAbsent` 在后）。单看服务端日志一切正常——**同步确实发出了 78 条指令，只是没人可勾**——所以这类「服务端自洽、界面空白」的故障必须靠**客户端侧**的断言兜住。

## 维护须知：只测服务端会漏掉整类 bug

测试里最容易犯的错，是拿服务端的目录去断言 `GrantEditorScreen` 的行为。上面那个空白界面就是这样漏过去的：快照、目录、权限、裁剪树全都对，只有「画不画得出来」没人问。所以 `assertEditorShowsCommands` 现在直接检查**屏幕上真的画出来的复选框**：行数必须等于 `GrantEditorScreen#rowsPerPage()`（该常量与该方法因此是 `public`），且每行都得在客户端自己的目录里。

同时断言**不能只看第一页**：一页只放 8 行，`time` 这种字母序靠后的指令根本不在首页，按名字断言等于在考字母表。真正该问的是「这一页是不是满的、行是不是来自目录」。

## 维护须知：编辑器为什么会叠在左上角

屏幕**建好了控件不等于摆好了控件**。`GrantEditorScreen#build()` 少了 `layout.arrangeElements()`，所有控件就停在构造原点——玩家看到的就是「完成」「None」和几个按钮挤在窗口最上面一行、按下去还响音效（按钮确实在那儿，只是全叠在一起）。这部分抄原版最保险：`OutOfMemoryScreen#init()` 的末尾是 `visitWidgets(...)` 紧跟 `repositionElements()`，而 `Screen#repositionElements()` 的默认实现体只有一条 `layout.arrangeElements()`（两者都经 `javap` 确认过）。

**缩放不只是摆放问题**。页大小跟着窗口高度走，所以窗口变矮之后光重新 arrange 是不够的：`GrantEditorScreen#repositionElements()` 因此先比较 `rowsPerPage()`，变了就 `rebuildWidgets()` 重新分页，没变才只 arrange。只 arrange 会让一页行数超过新窗口能放下的行数——正是这个界面早先「行画到页脚上」的那种故障。

## 维护须知：一条指令可能有两个根名字（重定向别名）

`/tp` 不是 `/teleport` 的别名字符串，而是**另一个根字面量**：`TeleportCommand.register` 先注册 `/teleport`，再注册 `/tp` 并让它 `redirect(...)` 到同一个节点。于是活树里 `dispatcher.getRoot().getChildren()` 同时有 `teleport` 和 `tp`——目录里两条、编辑器里两行、授权表里两个键。Brigadier 的重定向语义是「解析到这里就跳到那个节点的子节点去」，所以两个名字跑的是同一段指令体，但在**权限和目录的粒度上它们毫无关联**：没有任何 `Permission` 能区分它们（两者都是 `COMMANDS_GAMEMASTER`）。

这就是「勾了传送还是不能用」的成因：房主勾的是长名 `teleport`，写下去的是 `/tp`，而 `reachable` 只看字面量名字，于是 `/tp` 被裁掉——玩家收到「未知或不完整的命令」。

修法是给 `CommandIndex` 记一份**别名簇**（`aliasGroups`）：`CommandRequirements.discover` 顺手把 `node.getRedirect().getName()` 也读出来，把「重定向到彼此」且**需求等级相同**的根字面量合成一组，`Entry#group` 随快照发到客户端，`GrantAccess#reachable` 改成问「这一组里有没有一个被开放」，编辑器则把这一组画成**一行**、标签写成 `teleport / tp (gamemasters)`，勾选时对组里每个名字各发一次 toggle。

几处必须守住的边界：

- **等级不同的不合并**。重定向如果重新挂了更强的 `requires`，两个名字就是两个不同的决定，合并会把其中一个从房主眼前藏掉。
- **`addChild` 不会自己带进别名**。实测（`RootCommandNode` 只放 `teleport`）：`parse("tp targets 1")` 游标停在 `0/12`，`tp` 根本不可见；只放 `tp` 时重定向照常工作——**目标节点不必在根上**。所以两个名字必须各自可达，簇是「一起开放」而不是「互相替代」。
- **标签必须写全两个名字**。只写 `teleport` 的那一行，正是房主以为自己开了 `/tp` 的原因；测试因此断言这一行的文案等于 `teleport / tp`。

## 维护须知：`RowHelper.addChild` 的第二个参数是格子数，不是像素

`GridLayout.RowHelper#addChild(T)` 只占一个格子，而 `addChild(T, int)` 里的 `int` 是**这个控件要跨几列/几行格子**。把像素宽度（230）传进去，等于告诉网格「这一行有 230 格高」，于是每行高度被按 `Divisor` 分成 230 份，网格报出的高度直接变成几千像素（实测 `grid=916x2832`，一行高 704），`FrameLayout` 只好把它居中到一个荒唐的位置，整页被推到屏幕外——`y=-2647`。

行宽的正确写法是在控件自己身上给：`Checkbox.builder(...).maxWidth(OPTION_WIDTH)`，然后 `rows.addChild(checkbox)` 一个参数。

## 许可

MIT，见 `LICENSE`。
