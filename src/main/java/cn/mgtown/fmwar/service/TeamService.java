package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import cn.mgtown.fmwar.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队伍与玩家可见计分板的管理。
 *
 * <p>设计要点：本插件**不碰服务器主计分板**（{@code Bukkit.getScoreboardManager().getMainScoreboard()}），
 * 而是自建一块计分板并在需要时用 {@code player.setScoreboard} 单独挂到玩家身上。
 * 这样“仅队伍 fm / fmgz 可见”是天然成立的，也不会顶掉其他插件的侧栏或队伍配置。</p>
 *
 * <p><b>为什么还要额外维护两份并发镜像：</b>“这名玩家是不是本局相关玩家”这个判定
 * （{@link cn.mgtown.fmwar.game.GameEngine#isParticipant}）会被各玩家所属区域线程调用——
 * 例如骑乘拦截要在事件里<b>同步</b>决定是否取消事件，不能推迟到权威线程。而
 * {@code Scoreboard#getTeam(...).hasEntry(...)} 读的是普通 HashMap，在 Folia 上与权威线程的
 * 写入并发时会读到错值甚至死循环。因此队伍成员在这里被镜像到两个并发集合里，
 * 所有“查询”走镜像，所有“写”仍只发生在权威线程。</p>
 */
public final class TeamService {

    private final ConfigService config;
    private final Schedulers schedulers;
    private final Scoreboard scoreboard;
    /** 游戏队伍（fm）成员镜像，供跨线程查询。 */
    private final Set<UUID> playerMirror = ConcurrentHashMap.newKeySet();
    /** 观战队伍（fmgz）成员镜像，供跨线程查询。 */
    private final Set<UUID> spectatorMirror = ConcurrentHashMap.newKeySet();

    public TeamService(ConfigService config, Schedulers schedulers) {
        this.config = config;
        this.schedulers = schedulers;
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
        String entry = uuid.toString();
        Team spectatorTeam = spectatorTeam();
        if (spectatorTeam.hasEntry(entry)) {
            spectatorTeam.removeEntry(entry);
        }
        playerTeam().addEntry(entry);
        spectatorMirror.remove(uuid);
        playerMirror.add(uuid);
    }

    /** 把玩家加入观战队伍；会先从游戏队伍中移除。 */
    public void joinSpectatorTeam(UUID uuid) {
        String entry = uuid.toString();
        Team playerTeam = playerTeam();
        if (playerTeam.hasEntry(entry)) {
            playerTeam.removeEntry(entry);
        }
        spectatorTeam().addEntry(entry);
        playerMirror.remove(uuid);
        spectatorMirror.add(uuid);
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
        playerMirror.remove(uuid);
        spectatorMirror.remove(uuid);
    }

    public boolean inPlayerTeam(UUID uuid) {
        return playerMirror.contains(uuid);
    }

    public boolean inSpectatorTeam(UUID uuid) {
        return spectatorMirror.contains(uuid);
    }

    /** 把队伍所有成员（在线）取出来。 */
    public Set<UUID> playerTeamMembers() {
        return Set.copyOf(playerMirror);
    }

    public Set<UUID> spectatorTeamMembers() {
        return Set.copyOf(spectatorMirror);
    }

    /**
     * 把玩家的可见计分板切到本插件的（null 表示恢复服务器主计分板）。
     *
     * <p>{@code Player#setScoreboard} 改的是玩家自身状态，Folia 上必须在该玩家所属线程执行；
     * Paper 上（当前就在主线程）就地执行，与改造前完全一致。</p>
     */
    public void applyScoreboard(org.bukkit.entity.Player player, boolean useGameScoreboard) {
        schedulers.runOwned(player, () -> {
            if (useGameScoreboard) {
                player.setScoreboard(scoreboard);
            } else if (player.getScoreboard() == scoreboard) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }, null);
    }

    /** 读取当前配置下的队伍名与人数，用于 /fmwar status 展示。 */
    public String describe() {
        Settings.Teams teams = config.settings().teams();
        return teams.player() + "=" + playerMirror.size() + ", " + teams.spectator() + "=" + spectatorMirror.size();
    }
}
