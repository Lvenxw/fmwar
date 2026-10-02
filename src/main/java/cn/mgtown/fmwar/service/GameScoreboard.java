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
 * <p>积分榜**不显示在侧栏**：它只在 {@code /fmwar points list} 里查看，因此本类
 * 不注册 fmjfb 目标，也不会占用玩家的侧栏。</p>
 */
public final class GameScoreboard {

    private static final String OBJECTIVE = "fm";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final TeamService teams;

    /**
     * 准备阶段的常驻显示条（BossBar）。
     *
     * <p>两种情况共用这一条：准备进度（{@code 3/7}）与开局倒计时（{@code 10 秒}）。
     * 用 BossBar 而不是记分板多一行，这样对局中的两行（剩余时间/存活人数）不受影响，
     * 且对参战者与观战者都常驻可见。</p>
     *
     * <p>Adventure 的 {@link BossBar} 本身没有“显示/隐藏”开关：可见性由
     * {@code Audience#showBossBar} / {@code Audience#hideBossBar} 决定，
     * 因此这里自行维护“谁正在看到它”的集合。</p>
     */
    private final BossBar bossBar = BossBar.bossBar(
            Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    /** 当前正在显示准备阶段显示条的玩家。 */
    private final Set<UUID> countdownViewers = new HashSet<>();
    /** 显示条是否处于“应当显示”的状态。 */
    private boolean countdownActive;

    /**
     * 准备阶段的**侧栏**兜底显示。
     *
     * <p>BossBar 在某些客户端/资源包环境下可能不显示，而“倒计时看不到”是致命的——
     * 玩家会以为按钮没生效。这里同时在队伍侧栏上挂一个纯文本倒计时，
     * 侧栏是百分百会渲染的。</p>
     */
    private static final String COUNTDOWN_OBJECTIVE = "fmcd";
    private static final String COUNTDOWN_ENTRY = "fmcd.time";
    private Objective countdownObjective;

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

    /** 显示/刷新准备倒计时：BossBar + 侧栏双通道。 */
    public void showCountdown(String text, String sidebarTitle, String sidebarLine, double progress) {
        countdownActive = true;

        // 通道 1：BossBar（progress < 0 表示这条只用于准备进度，不显示进度条比例）
        bossBar.name(LEGACY.deserialize(nullToEmpty(text)));
        bossBar.progress(progress < 0 ? 1.0f : (float) Math.max(0.0, Math.min(1.0, progress)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (countdownViewers.add(player.getUniqueId())) {
                player.showBossBar(bossBar);
            }
        }

        // 通道 2：侧栏（同一块自建计分板，用独立目标，接管 SIDEBAR 显示位）
        Scoreboard board = teams.scoreboard();
        if (countdownObjective == null || countdownObjective.getScoreboard() != board) {
            countdownObjective = board.getObjective(COUNTDOWN_OBJECTIVE);
            if (countdownObjective == null) {
                countdownObjective = board.registerNewObjective(COUNTDOWN_OBJECTIVE, "dummy");
            }
        }
        countdownObjective.displayName(LEGACY.deserialize(nullToEmpty(sidebarTitle)));
        countdownObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        Score score = countdownObjective.getScore(COUNTDOWN_ENTRY);
        score.setScore(1);
        score.customName(LEGACY.deserialize(nullToEmpty(sidebarLine)));
    }

    /**
     * 只显示准备进度（不动侧栏）。
     *
     * <p>准备阶段大多数时间只有进度可看，此时不该让侧栏显示一条无关的倒计时行。</p>
     */
    public void showProgress(String text, double progress) {
        countdownActive = true;
        bossBar.name(LEGACY.deserialize(nullToEmpty(text)));
        bossBar.progress((float) Math.max(0.0, Math.min(1.0, progress)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (countdownViewers.add(player.getUniqueId())) {
                player.showBossBar(bossBar);
            }
        }
    }

    /** 隐藏准备倒计时（BossBar 与侧栏都收起，并恢复游戏侧栏）。 */
    public void hideCountdown() {
        // BossBar
        if (countdownActive || !countdownViewers.isEmpty()) {
            countdownActive = false;
            for (UUID uuid : new HashSet<>(countdownViewers)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(bossBar);
                }
            }
            countdownViewers.clear();
        }
        // 侧栏：把显示位还给游戏侧栏目标
        if (countdownObjective != null) {
            Objective game = teams.scoreboard().getObjective(OBJECTIVE);
            if (game != null) {
                game.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            countdownObjective.unregister();
            countdownObjective = null;
        }
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
        // 条目名是纯文本，不会自动转换 & 颜色码，必须显式渲染成 Component，
        // 否则侧栏会直接显示 “&e剩余时间” 这种原始代码
        setScoreName(objective, label(config.timeLine(), "剩余时间"), seconds);
        setScoreName(objective, label(config.aliveLine(), "存活人数"), alive);
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

    /** 设置一行：条目名是标签（带颜色渲染），数值是记分值。 */
    private void setScoreName(Objective objective, String label, int value) {
        Score score = objective.getScore(label);
        score.setScore(value);
        score.customName(LEGACY.deserialize(label));
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
