package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;

/**
 * 记分板：
 * <ul>
 *   <li>{@code fm} —— 剩余时间 + 存活人数，挂在玩家自己可见的独立计分板上
 *       （因此“仅队伍 fm / fmgz 可见”是结构性成立的）；</li>
 *   <li>{@code fmjfb} —— 附魔战争积分榜，注册在**服务器主计分板**上，
 *       但只在游戏期间显示给游戏内玩家。</li>
 * </ul>
 *
 * <p>有意不复用 {@link TeamService} 的自建计分板来放积分榜：那块计分板只在游戏期间
 * 挂给玩家，非游戏期间不生效，而且这样不会在别人（包括本插件自己）读取主计分板队伍时
 * 用到被顶掉的侧栏目标。</p>
 */
public final class GameScoreboard {

    private static final String OBJECTIVE = "fm";
    private static final String TIME_ENTRY = "fmwar.time";
    private static final String ALIVE_ENTRY = "fmwar.alive";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final TeamService teams;

    /** 上一次渲染的积分榜条目名，用于增量增删（避免残留旧名次）。 */
    private final List<String> pointEntries = new ArrayList<>();

    public GameScoreboard(TeamService teams) {
        this.teams = teams;
    }

    /** 为一名玩家准备记分板并挂上。 */
    public void attach(Player player, Settings settings) {
        Scoreboard scoreboard = teams.scoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.registerNewObjective(OBJECTIVE, "dummy");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            String title = settings == null ? "附魔战争" : settings.scoreboard().title();
            objective.displayName(LEGACY.deserialize(nullToEmpty(title)));
        }
        teams.applyScoreboard(player, true);
    }

    /** 刷新剩余时间与存活人数两行。 */
    public void update(Settings settings, String time, String alive) {
        Scoreboard scoreboard = teams.scoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE);
        if (objective == null) {
            return;
        }
        objective.displayName(LEGACY.deserialize(nullToEmpty(settings.scoreboard().title())));
        setLine(objective, TIME_ENTRY,
                LEGACY.deserialize(nullToEmpty(settings.scoreboard().timeLine()).replace("{time}", time)));
        setLine(objective, ALIVE_ENTRY,
                LEGACY.deserialize(nullToEmpty(settings.scoreboard().aliveLine()).replace("{alive}", alive)));
    }

    /**
     * 刷新附魔战争积分榜（记分板 {@code scoreboard.points-main}，默认 {@code fmjfb}）。
     *
     * @param ranking 已按名次排好的条目；方法内部按 rows 截断
     */
    public void updatePoints(Settings settings, List<PointsService.Entry> ranking) {
        Settings.Scoreboard config = settings.scoreboard();
        if (!config.pointsEnabled()) {
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective objective = board.getObjective(config.pointsMain());
        if (objective == null) {
            objective = board.registerNewObjective(config.pointsMain(), "dummy");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        objective.displayName(LEGACY.deserialize(nullToEmpty(config.pointsTitle())));

        // 条目名固定为 rank-N：名次不变时只改显示文本，不会残留旧条目
        List<String> wanted = new ArrayList<>();
        int rows = Math.max(1, config.pointsRows());
        int shown = Math.min(rows, ranking.size());
        for (int index = 0; index < shown; index++) {
            PointsService.Entry entry = ranking.get(index);
            String name = "fmjfb.rank-" + index;
            wanted.add(name);
            String text = nullToEmpty(config.pointsLine())
                    .replace("{rank}", Integer.toString(index + 1))
                    .replace("{player}", entry.name())
                    .replace("{points}", Integer.toString(entry.points()));
            setLine(objective, name, LEGACY.deserialize(text));
        }
        // 移除本轮不再需要的条目（例如人数变少）
        for (String stale : new ArrayList<>(pointEntries)) {
            if (!wanted.contains(stale)) {
                board.resetScores(stale);
            }
        }
        pointEntries.clear();
        pointEntries.addAll(wanted);
    }

    /** 注销积分榜目标（插件停用时调用，避免在主计分板上留下空目标）。 */
    public void detachPoints(String objectiveName) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String entry : pointEntries) {
            board.resetScores(entry);
        }
        pointEntries.clear();
        Objective objective = board.getObjective(objectiveName);
        if (objective != null) {
            objective.unregister();
        }
    }

    private void setLine(Objective objective, String entry, Component text) {
        Score score = objective.getScore(entry);
        score.setScore(1);
        score.customName(text);
    }

    /** 把一名玩家的可见计分板还原为服务器主计分板。 */
    public void detach(Player player) {
        teams.applyScoreboard(player, false);
    }

    /** 把所有玩家的可见计分板还原，并注销本插件的目标（游戏结束/插件停用时调用）。 */
    public void detachAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            teams.applyScoreboard(player, false);
        }
        Scoreboard scoreboard = teams.scoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE);
        if (objective != null) {
            objective.unregister();
        }
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
