# 部署与前置条件

FMWar 的玩法代码本身自包含，但有几项条件**只有在真实服务器上才能确认**。
这份清单把这些条件集中列出，装完照做一遍即可。

## 1. 安装

1. `./gradlew build`（Windows：`gradle build`，见下方「构建说明」）产出 `build/libs/fmwar-1.0-SNAPSHOT.jar`。
2. 把 jar 放进服务端 `plugins/`。
3. 启动服务器，`plugins/fmwar/config.yml` 会被自动释放，按需要修改后执行 `/fmwar reload`。

## 2. 构建说明

- Gradle wrapper 固定 9.4.0，但 `xyz.jpenilla.run-paper 3.1.0` 要求 Gradle ≥ 9.7。
  因此默认**不加载** run-paper，`build` / `compileJava` / `jar` 都正常可用。
  需要 `runServer` 时先升级 wrapper 到 9.7+，再用 `gradle -PwithRunPaper=true runServer`。
- `libs/ExtraShopApi-1.0.1.jar` 只作编译期依赖（`compileOnly`），**不要**丢进 `plugins/`，
  也不要 shade 进本插件：运行期该 API 由 ExtraShop 插件提供，自带一份会导致类型不匹配。

## 3. 前置条件检查表

| # | 条件 | 怎么确认 | 不满足时的表现 |
| --- | --- | --- | --- |
| 1 | 世界名与 `config.yml` 的 `world` 一致 | 服务器 `level-name` | 控制台报世界未加载；`/fmwar doctor` 列出 |
| 2 | 六个 eshop 商店已定义并摆放在场地内 | `/fmwar doctor` 看 `defined=true`；或 `/eshop` 检查 | 商店不出现，其余玩法正常 |
| 3 | 奖励箱与钓竿的附魔键在本服注册表里存在 | **`/fmwar doctor` 会直接列出未注册的键** | 控制台 WARNING + 该条被跳过，箱子少物品 |
| 4 | Residence 的 FM 领地对普通玩家关闭 `tp` | `/res set FM tp false` | 玩家可自行传进场地 |
| 5 | 没有其它插件的侧栏与之冲突 | 看是否有等级榜/经济榜侧栏 | 本插件不改主计分板，冲突风险低 |
| 6 | 坐姿类插件（如 GSit）的躺/坐/趴需要额外拦截 | 观察玩家能否坐下 | 本插件只拦骑乘（潜行拦截可用 `start.block-sneak` 打开）；坐姿需另接该插件 API |
| 7 | 场地所在区块已生成 | 开一次箱子/传送 | `getHighestBlockYAt` 在未生成区块上的结果不可靠；开局分散会异步加载中心区块后重试 |
| 8 | 奖励箱坐标处是空气或可替换方块 | 看控制台是否出现“被 X 占用且不可替换” | 该箱子被跳过并记 WARNING，其余箱子正常 |

## 4. 指令

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/fmwar reload` | `fmwar.admin` | 重新加载 `config.yml`。**校验不通过时保留上一份可用配置**并报错，不会让进行中的对局切到半残配置；若有关键项缺失（文件被清空/截断/被其它插件整份覆盖），会拒绝加载并提示你核对文件 |
| `/fmwar start` | `fmwar.admin` | 跳过准备按钮，把准备房间内已入队玩家直接拉入对局 |
| `/fmwar stop` | `fmwar.admin` | 强制中止并清场（清背包、还原队伍与计分板、移除奖励箱、despawn 商店） |
| `/fmwar status` | `fmwar.admin` | 阶段、队列人数、名单人数、场地内存活、剩余时间、自己的队伍 |
| `/fmwar doctor` | `fmwar.admin` | 配置校验结果 + 每个 shopId 的 `defined/spawned` + **奖励箱/钓竿里每个附魔键在本服注册表里是否存在**（未注册的键会逐个列出） |

## 5. 外部行为边界

- **本插件不创建商店**：ExtraShop API 只有 `isShopDefined / isSpawned / spawn / despawn`，
  商品、坐标、价格都必须在服务器上用 `/eshop` 预先配好。
- **本插件不改别人的权限数据**：Residence 的 `tp` flag 属于领地配置，本插件只保证自己的传送
  不被拦（内部标记放行），并拒绝其它来源把玩家送进场地/准备房间/大厅/决斗圈。
- **运行期状态不落盘**：服务器重启即当前对局作废，插件停用时会强制结算清场
  （还原队伍与计分板、移除奖励箱、despawn 商店、把玩家送回大厅）。
