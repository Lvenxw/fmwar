# 0002. 记分板与队伍挂到插件自建的计分板，不动服务器主计分板

- 状态：已接受
- 日期：2026-02-14（会话日期）

## 背景

需求要求「开启右侧记分板 fm（仅队伍为 fm 和 fmgz 可见）」，并显式提到队伍 `fm` 与 `fmgz`。
Bukkit 的队伍（`Team`）只存在于某一块 `Scoreboard` 上：

- 若用 `Bukkit.getScoreboardManager().getMainScoreboard()`，队伍成员关系是**全服可见**的，
  任何玩家（哪怕不在游戏中）都能看到这些队伍及其前缀，无法表达「仅 fm 与 fmgz 可见」；
- 主计分板通常已被其它插件占用（等级榜、经济榜、称号前缀等），在上面 `registerNewTeam("fm")`
  或 `setDisplaySlot(SIDEBAR)` 会顶掉别人的东西。

## 决策

插件启动时创建**自己的一块** `Scoreboard`（`ScoreboardManager#getNewScoreboard()`），
队伍 `fm` / `fmgz` 与目标 `fm` 都注册在它上面；只在需要时用
`Player#setScoreboard(我们的Scoreboard)` 挂给参战者与观战者，结束或离场时用
`setScoreboard(getMainScoreboard())` 还原。

## 后果

- 「仅 fm / fmgz 可见」变成结构性事实：没有这块记分板就看不到它，不需要额外权限判断。
- 不与非本插件的侧栏/队伍冲突；同一名玩家在观战结束后回到服务器默认侧栏。
- 代价：本插件的队伍成员关系不能用于其它插件读取（它们读主计分板）。
  目前没有任何需求依赖这一点。
- 还原动作必须显式执行（`GameScoreboard#detachAll`），否则插件停用后玩家会停留在
  一块没有目标的空计分板上。插件停用时已在 `onDisable` 里调用。
