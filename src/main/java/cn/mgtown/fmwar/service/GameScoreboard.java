package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 右侧记分板 fm：剩余时间 + 存活人数。
 *
 * <p>只挂在游戏内与观战玩家的身上（{@link TeamService#applyScoreboard}），因此
 * “仅队伍为 fm 和 fmgz 可见”不需要额外判断。</p>
 */
public final class GameScoreboard {

    private static final String OBJECTIVE = "fm";
    private static final String TIME_ENTRY = "fmwar.time";
    private static final String ALIVE_ENTRY = "fmwar.alive";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final TeamService teams;

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

    /** 刷新两行内容。 */
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
