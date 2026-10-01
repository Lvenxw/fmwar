package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /**
     * 准备倒计时用的常驻显示条。
     *
     * <p>需求：倒计时提示要“在队伍里能常驻显示”，而不是按一下按钮才闪一下。
     * 用 BossBar 而不是记分板多一行——这样现有两行（剩余时间/存活人数）不受影响，
     * 且对参战者与观战者都常驻可见。</p>
     *
     * <p>Adventure 的 {@link BossBar} 本身没有“显示/隐藏”开关：可见性由
     * {@code Audience#showBossBar} / {@code Audience#hideBossBar} 决定，
     * 因此这里自行维护“谁正在看到它”的集合。</p>
     */
    private final BossBar bossBar = BossBar.bossBar(
            Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    /** 当前正在显示倒计时条的玩家。 */
    private final Set<UUID> countdownViewers = new HashSet<>();
    /** 倒计时条是否处于“应当显示”的状态。 */
    private boolean countdownActive;

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
        // 清掉两种旧写法留下的条目：早期版本把数值放在显示名上（名里带 fmwar. 前缀）
        if (settings != null) {
            for (String legacy : new String[]{"fmwar.time", "fmwar.alive"}) {
                scoreboard.resetScores(legacy);
            }
        }
        // 准备倒计时用 BossBar 常驻显示（记分板不占用额外行）
        if (countdownActive) {
            player.showBossBar(bossBar);
            countdownViewers.add(player.getUniqueId());
        }
        teams.applyScoreboard(player, true);
    }

    /** 把一名玩家的 BossBar 收起来（离场/观战结束时调用）。 */
    public void detachBossBar(Player player) {
        if (countdownViewers.remove(player.getUniqueId())) {
            player.hideBossBar(bossBar);
        }
    }

    /** 显示/刷新准备倒计时 BossBar（对所有在线的游戏参与者常驻显示）。 */
    public void showCountdown(String text, double progress) {
        countdownActive = true;
        bossBar.name(LEGACY.deserialize(nullToEmpty(text)));
        bossBar.progress((float) Math.max(0.0, Math.min(1.0, progress)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!countdownViewers.add(player.getUniqueId())) {
                continue;
            }
            player.showBossBar(bossBar);
        }
    }

    /** 隐藏准备倒计时 BossBar。 */
    public void hideCountdown() {
        if (!countdownActive && countdownViewers.isEmpty()) {
            return;
        }
        countdownActive = false;
        for (UUID uuid : new HashSet<>(countdownViewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.hideBossBar(bossBar);
            }
        }
        countdownViewers.clear();
    }

    /**
     * 刷新剩余时间与存活人数两行。
     *
     * <p><b>数值写在“记分值”上而不是显示名里</b>：记分板渲染出来是
     * {@code 条目名 .......... 记分值}。条目名用固定的 {@code scoreboard.time-line} /
     * {@code alive-line} 文案（其中 {time}/{alive} 占位符会被忽略），
     * 记分值才是真正的数字——这样右侧只会出现一个数，不会多出一个无意义的 1。</p>
     */
    public void update(Settings settings, int seconds, int alive) {
        Scoreboard scoreboard = teams.scoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE);
        if (objective == null) {
            return;
        }
        objective.displayName(LEGACY.deserialize(nullToEmpty(settings.scoreboard().title())));
        Settings.Scoreboard config = settings.scoreboard();
        setScore(objective, label(config.timeLine(), "剩余时间"), seconds);
        setScore(objective, label(config.aliveLine(), "存活人数"), alive);
    }

    /**
     * 从配置文案里取出“条目名”。
     *
     * <p>配置模板同时写着占位符（{@code &e剩余时间 &f{time}}），但取值方式改为记分值后
     * 数字不该再出现在名字里，因此这里把 {@code {time}} / {@code {alive}} 及其前面的
     * 颜色代码一起裁掉，只留标签本身。</p>
     */
    private String label(String template, String fallback) {
        if (template == null || template.isBlank()) {
            return fallback;
        }
        String text = template;
        int placeholder = text.indexOf('{');
        if (placeholder > 0) {
            text = text.substring(0, placeholder);
        }
        // 去掉标签末尾悬空的颜色代码（如 "&e剩余时间 &f" 里的 "&f"）
        text = text.replaceAll("(?i)&[0-9a-fk-or]\\s*$", "");
        text = text.trim();
        return text.isEmpty() ? fallback : text;
    }

    /** 设置一行：条目名是标签，数值是记分值。 */
    private void setScore(Objective objective, String entry, int value) {
        objective.getScore(entry).setScore(value);
    }

    /**
     * 刷新附魔战争积分榜（记分板 {@code scoreboard.points-main}，默认 {@code fmjfb}）。
     *
     * @param ranking 已按名次排好的条目
     */
    public void updatePoints(Settings settings, List<PointsService.Entry> ranking) {
        updatePoints(settings, ranking, 1);
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

    /**
     * 用分页渲染积分榜。
     *
     * @param page 1 起算的页码
     */
    public void updatePoints(Settings settings, List<PointsService.Entry> ranking, int page) {
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

        int rows = Math.max(1, config.pointsRows());
        int totalPages = Math.max(1, (ranking.size() + rows - 1) / rows);
        int current = Math.max(1, Math.min(page, totalPages));
        int from = (current - 1) * rows;
        int to = Math.min(ranking.size(), from + rows);

        List<String> wanted = new ArrayList<>();
        for (int index = from; index < to; index++) {
            PointsService.Entry entry = ranking.get(index);
            String name = "fmjfb.rank-" + (index - from);
            wanted.add(name);
            String text = nullToEmpty(config.pointsLine())
                    .replace("{rank}", Integer.toString(index + 1))
                    .replace("{player}", entry.name())
                    .replace("{points}", Integer.toString(entry.points()));
            setLine(objective, name, LEGACY.deserialize(text));
        }
        // 页脚：页码提示（多于一页时才显示）
        if (totalPages > 1) {
            String footer = "fmjfb.page";
            wanted.add(footer);
            setLine(objective, footer, LEGACY.deserialize(
                    "&7第 &f" + current + "&7/&f" + totalPages + " &7页"));
        }
        for (String stale : new ArrayList<>(pointEntries)) {
            if (!wanted.contains(stale)) {
                board.resetScores(stale);
            }
        }
        pointEntries.clear();
        pointEntries.addAll(wanted);
    }

    /** 把一名玩家的可见计分板还原为服务器主计分板，并收起倒计时条。 */
    public void detach(Player player) {
        detachBossBar(player);
        teams.applyScoreboard(player, false);
    }

    /** 把所有玩家的可见计分板还原，并注销本插件的目标（游戏结束/插件停用时调用）。 */
    public void detachAll() {
        hideCountdown();
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
