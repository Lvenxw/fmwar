package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 队伍与玩家可见计分板的管理。
 *
 * <p>设计要点：本插件**不碰服务器主计分板**（{@code Bukkit.getScoreboardManager().getMainScoreboard()}），
 * 而是自建一块计分板并在需要时用 {@code player.setScoreboard} 单独挂到玩家身上。
 * 这样“仅队伍 fm / fmgz 可见”是天然成立的，也不会顶掉其他插件的侧栏或队伍配置。</p>
 */
public final class TeamService {

    private final ConfigService config;
    private final Scoreboard scoreboard;

    public TeamService(ConfigService config) {
        this.config = config;
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
    }

    public Scoreboard scoreboard() {
        return scoreboard;
    }

    private Team team(String name) {
        Team team = scoreboard.getTeam(name);
        if (team == null) {
            team = scoreboard.registerNewTeam(name);
        }
        return team;
    }

    public Team playerTeam() {
        return team(config.settings().teams().player());
    }

    public Team spectatorTeam() {
        return team(config.settings().teams().spectator());
    }

    /** 把玩家加入游戏队伍；会先从观战队伍中移除（两队互斥）。 */
    public void joinPlayerTeam(UUID uuid) {
        Team spectators = spectatorTeam();
        if (spectators.hasEntry(uuid.toString())) {
            spectators.removeEntry(uuid.toString());
        }
        playerTeam().addEntry(uuid.toString());
    }

    /** 把玩家加入观战队伍；会先从游戏队伍中移除。 */
    public void joinSpectatorTeam(UUID uuid) {
        Team players = playerTeam();
        if (players.hasEntry(uuid.toString())) {
            players.removeEntry(uuid.toString());
        }
        spectatorTeam().addEntry(uuid.toString());
    }

    /** 从两个队伍中移除玩家。 */
    public void leaveAll(UUID uuid) {
        String entry = uuid.toString();
        if (playerTeam().hasEntry(entry)) {
            playerTeam().removeEntry(entry);
        }
        if (spectatorTeam().hasEntry(entry)) {
            spectatorTeam().removeEntry(entry);
        }
    }

    public boolean inPlayerTeam(UUID uuid) {
        return playerTeam().hasEntry(uuid.toString());
    }

    public boolean inSpectatorTeam(UUID uuid) {
        return spectatorTeam().hasEntry(uuid.toString());
    }

    /** 把队伍所有成员（在线）取出来。 */
    public Set<UUID> playerTeamMembers() {
        return members(playerTeam());
    }

    public Set<UUID> spectatorTeamMembers() {
        return members(spectatorTeam());
    }

    private Set<UUID> members(Team team) {
        Set<UUID> result = new HashSet<>();
        for (String entry : team.getEntries()) {
            try {
                result.add(UUID.fromString(entry));
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 条目（例如实体名）与队伍管理无关，跳过
            }
        }
        return result;
    }

    /** 把玩家的可见计分板切到本插件的（null 表示恢复服务器主计分板）。 */
    public void applyScoreboard(org.bukkit.entity.Player player, boolean useGameScoreboard) {
        if (useGameScoreboard) {
            player.setScoreboard(scoreboard);
        } else if (player.getScoreboard() == scoreboard) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** 读取当前配置下的队伍名，用于 /fmwar status 展示。 */
    public String describe() {
        Settings.Teams teams = config.settings().teams();
        return teams.player() + "=" + playerTeam().getSize() + ", " + teams.spectator() + "=" + spectatorTeam().getSize();
    }
}
