package cn.mgtown.fmwar.game;

import cn.mgtown.FMWar;
import cn.mgtown.fmwar.config.Settings;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ConfigService;
import cn.mgtown.fmwar.service.GameScoreboard;
import cn.mgtown.fmwar.service.ShopService;
import cn.mgtown.fmwar.service.TeamService;
import cn.mgtown.fmwar.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import cn.mgtown.fmwar.config.Position;
import cn.mgtown.fmwar.config.Region;
import cn.mgtown.fmwar.service.PointsService;
import cn.mgtown.fmwar.service.ResidenceService;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 对局引擎：唯一的状态机 + 唯一的 20tick 主循环。
 *
 * <p>整份规格的时序都挂在同一个时钟上（准备倒计时、900s 对局倒计时、剩 60s 传决斗圈、
 * 每 30s 发绿宝石、归零后每秒扣血），因此这里只允许存在一个循环，按阶段分派，
 * 避免多个定时器互相竞态。</p>
 */
public final class GameEngine {

    /** 准备的游戏世界。 */
    private World world;

    /**
     * 自定义铁砧（customanvil 插件）的记分板标签。
     *
     * <p>清场时必须排除这些实体：它们是场地里的常驻设施，不属于“对局残留物”。</p>
     */
    private static final String ANVIL_TAG = "customanvil";

    private final FMWar plugin;
    private final ConfigService config;
    private final AlertService alerts;
    private final TeamService teams;
    private final GameScoreboard scoreboard;
    private final ShopService shops;
    private final PointsService points;
    private final ResidenceService residence;

    /** 受害者 -> 最后一名对其造成伤害的玩家：死亡时据此记击杀分。 */
    private final Map<UUID, UUID> lastDamager = new HashMap<>();

    /** 当前阶段。 */
    private GamePhase phase = GamePhase.IDLE;
    /** 当前阶段的计时器，IDLE 时为 null。 */
    private Timer timer;
    /** 已加入队列的玩家（准备房间阶段）。 */
    private final Set<UUID> queue = new LinkedHashSet<>();
    /** 正在对局中的玩家（队伍 fm）。 */
    private final Set<UUID> members = new LinkedHashSet<>();
    /** 准备房间上一 tick 的玩家集合，用于“有玩家进入则重置”。 */
    private Set<UUID> prepRoster = Set.of();
    /** 连续右键准备按钮的次数。 */
    private int prepareClicks;
    /** 上次展示倒计时秒数的缓存，避免重复刷动作栏。 */
    private long lastCountdownSecond = -1L;
    /** 上次扣血的秒数。 */
    private long lastOvertimeSecond = -1L;
    /** 上次发放绿宝石时的对局已进行秒数。 */
    private long lastEmeraldSecond = 0L;
    /** 上一 tick 的剩余时间，用于卡顿时的决斗圈传送闸门。 */
    private long lastRemainingTicks = Long.MAX_VALUE;
    /** 是否已经执行过决斗圈传送。 */
    private boolean duelTeleported;
    /** 本局生成的奖励箱方块坐标（结束时清理）。 */
    private final List<Location> chests = new ArrayList<>();
    /** 把无关玩家送出场地后的冷却（tick），防止传送被取消时每 tick 反复重传。 */
    private final Map<UUID, Long> outsiderCooldown = new HashMap<>();
    private static final long OUTSIDER_COOLDOWN_TICKS = 100L;
    /** 无关玩家上一次收到“不能进入场地”提示的 tick；避免重传时反复刷屏。 */
    private final Map<UUID, Long> outsiderNotice = new HashMap<>();
    private static final long OUTSIDER_NOTICE_INTERVAL_TICKS = 200L;
    /**
     * 开局后跳过场地判定的 tick 数。
     *
     * <p>开局传送是异步的（{@code teleportAsync}），刚落地的若干 tick 内玩家读到的仍是
     * 准备房间（在场地外）的旧坐标。若不跳过，一局会在开始的那一 tick 就把所有参战者
     * 判成“离开游戏”，直接以“无人生还”收场。2 秒足够任何一次本地传送落地。</p>
     */
    private static final long SETTLE_TICKS = 40L;

    /** 被本插件淘汰、等待主动重生的玩家：只有这些人的重生点会被改写为大厅。 */
    private final Set<UUID> pendingRespawn = new HashSet<>();
    /** 刚被淘汰的玩家 → 豁免截止 tick：避免其被“无关玩家清场”逻辑二次传送与误提示。 */
    private final Map<UUID, Long> recentEliminations = new HashMap<>();
    private static final long ELIMINATION_GRACE_TICKS = 100L;
    /** 对局中掉线的观战者：重新上线时仍为观战（需求 104），对局结束后回大厅。 */
    private final Set<UUID> disconnectedSpectators = new HashSet<>();
    /**
     * 下次上线必须送回大厅的玩家（掉线的参战者、掉线的准备房间入队者、局外掉线的观战者）。
     *
     * <p><b>这张名单刻意不随对局结算清空</b>：登记发生在对局结束之前，而玩家可能在对局
     * 结束之后才上线。此前的实现把标记放在会被 {@link #resetRuntimeState()} 清空的名单里，
     * 于是“对局中掉线、结束后才上线”的玩家所有分支都落空，直接以登出坐标留在场地内。</p>
     */
    private final Set<UUID> pendingHall = new HashSet<>();

    /** 本局是否已判定结束（结束后不再接受任何淘汰/胜利判定，避免同一局重复结算）。 */
    private boolean ended;

    /** 最近一次分散的结果摘要，写进开局日志（便于确认落点而不是“又传到场地传送点”）。 */
    private String lastDisperseReport = "未执行";
    /**
     * 调试日志开关（{@code /fmwar debug}）。
     *
     * <p>用 INFO 级别而不是 fine：这样不需要改服务端日志配置就能看到，
     * 排查“按钮没反应/倒计时不启动”这类问题时可以直接开关。</p>
     */
    private boolean debug;

    /** 决斗圈的随机选择器（本局选定后不再变化，保证所有人进同一个圈）。 */
    private final java.util.Random duelRandom = new java.util.Random();

    private BukkitTask tickTask;

    public GameEngine(FMWar plugin, ConfigService config, AlertService alerts,
                      TeamService teams, GameScoreboard scoreboard, ShopService shops,
                      PointsService points, ResidenceService residence) {
        this.plugin = plugin;
        this.config = config;
        this.alerts = alerts;
        this.teams = teams;
        this.scoreboard = scoreboard;
        this.shops = shops;
        this.points = points;
        this.residence = residence;
    }

    /**
     * 修改当前对局的剩余时间（测试用）。
     *
     * <p>把阶段计时器的起点往后挪，等价于“把剩余时间改成 seconds 秒”。
     * 只在 RUNNING 阶段有效；改完会立刻刷新记分板。</p>
     *
     * @return 是否修改成功
     */
    public boolean setRemainingSeconds(long seconds) {
        if (phase != GamePhase.RUNNING || timer == null) {
            return false;
        }
        long durationTicks = Math.max(1L, seconds) * 20L;
        timer = new Timer(GamePhase.RUNNING, Bukkit.getCurrentTick(), durationTicks);
        // 重置与时间相关的游标，避免 60 秒闸门/加时扣血/绿宝石节奏被旧值干扰
        lastRemainingTicks = Long.MAX_VALUE;
        lastEmeraldSecond = 0L;
        lastOvertimeSecond = -1L;
        duelTeleported = false;
        if (config.settings().scoreboard().enabled()) {
            scoreboard.update(config.settings(), (int) seconds, aliveCount());
        }
        plugin.getLogger().info("剩余时间已被指令修改为 " + seconds + " 秒");
        return true;
    }

    /** 供伤害监听上报“谁打了谁”，死亡时据此记击杀分。 */
    public void recordDamager(UUID victim, UUID damager) {
        if (victim == null || damager == null || victim.equals(damager)) {
            return;
        }
        // 只记录参战者之间的伤害：旁观者/局外玩家不该被计分
        if (!members.contains(victim) || !members.contains(damager)) {
            return;
        }
        lastDamager.put(victim, damager);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public void onEnable() {
        World resolved = resolveWorld();
        this.world = resolved;
        if (resolved == null) {
            plugin.getLogger().severe("配置中的世界不存在，附魔战争玩法无法启动；请修正 config.yml 的 world / regions.*.world");
            return;
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        preloadChunks();
    }

    /**
     * 预加载与玩法强相关的区块。
     *
     * <p>奖励箱坐标与开局分散中心若落在未加载区块，`getBlockAt` / `setType` 会在服务端主线程
     * 同步生成区块，开局瞬间造成明显卡顿。这里在启用时就异步预加载一次，把这份开销挪到开机阶段。</p>
     */
    private void preloadChunks() {
        if (world == null) {
            return;
        }
        Set<Long> chunks = new HashSet<>();
        for (Position position : config.settings().loot().chestLocations()) {
            chunks.add(chunkKey((int) Math.floor(position.x()) >> 4, (int) Math.floor(position.z()) >> 4));
        }
        Settings.Disperse disperse = config.settings().disperse();
        chunks.add(chunkKey((int) Math.floor(disperse.centerX()) >> 4, (int) Math.floor(disperse.centerZ()) >> 4));
        Settings.Duel duel = config.settings().duel();
        for (Settings.DuelArena arena : duel.arenas()) {
            chunks.add(chunkKey((int) Math.floor(arena.centerX()) >> 4, (int) Math.floor(arena.centerZ()) >> 4));
        }

        int loaded = 0;
        for (long key : chunks) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk -> { });
            loaded++;
        }
        plugin.getLogger().info("已请求异步预加载 " + loaded + " 个玩法相关区块");
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** 插件停用：强制结算并清场。 */
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        forceCleanup();
        // 强制复位领地权限：绝不能把“临时打开”的状态留在服务器上
        residence.reset();
        points.save();
    }

    /** /fmwar reload 之后重新解析世界引用并重跑区块预加载。 */
    public void onReload() {
        World resolved = resolveWorld();
        if (resolved == null) {
            plugin.getLogger().warning("reload 后世界仍不可用，玩法的传送与奖励箱不会生效");
            return;
        }
        this.world = resolved;
        preloadChunks();
    }

    /**
     * 解析游戏世界。
     *
     * <p>刻意**不**回退到 `getWorlds().get(0)`：需求里的所有坐标都绑定在 game 世界，
     * 静默回退会让奖励箱与分散传送落到错误的世界，比直接报错更难排查。</p>
     */
    private World resolveWorld() {
        String name = config.settings().world();
        World resolved = Bukkit.getWorld(name);
        if (resolved == null) {
            plugin.getLogger().severe("配置世界 " + name + " 未加载：请修正 config.yml 的 world / regions.*.world 后 /fmwar reload");
        }
        return resolved;
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    private void tick() {
        long now = Bukkit.getCurrentTick();
        switch (phase) {
            case IDLE -> {
                // 空闲阶段要保证“离开准备房间即退出队列”始终生效。
                // 注意：这里**绝不能**无条件清零 prepareClicks——右键事件回调先于本 tick 执行，
                // 无条件清零会把玩家刚刚点出的进度当 tick 抹掉，表现为“按多少次都只显示 1/7”。
                // 进度只在“准备房间一个人都没有”时才归零。
                syncPrepRoster();
                if (prepRoster.isEmpty() && prepareClicks != 0) {
                    prepareClicks = 0;
                }
            }
            case PREPARING -> tickPreparing(now);
            case RUNNING -> tickRunning(now);
            case ENDING -> tickEnding(now);
        }
    }

    private void tickPreparing(long now) {
        syncPrepRoster();
        if (timer != null && timer.expired(now)) {
            debug("倒计时归零，开始对局（已入队 " + queuedInRoom() + " 人）");
            beginGame();
            return;
        }
        if (timer != null) {
            // 倒计时期间准备房间内已入队人数不足两人：取消倒计时并重置进度。
            // 这是**唯一**的取消判定点（此前 syncPrepRoster 里也有一份，且被误写成无条件取消）。
            int queued = queuedInRoom();
            if (PrepRoom.shouldCancelCountdown(queued, 2)) {
                debug("倒计时被取消：准备房间内已入队人数降到 " + queued + " 人");
                cancelCountdown("prepare-cancel-not-enough");
                return;
            }
            long remainingTicks = timer.remainingTicks(now);
            long remaining = TimeUtil.ceilSeconds(remainingTicks);
            // 只发给队列内的玩家：不在游戏里的玩家不该看到这条
            List<Player> viewers = queuePlayers();
            String text = alerts.render("prepare-countdown-bar", Map.of("seconds", Long.toString(remaining)));
            double progress = timer.durationTicks() <= 0
                    ? 0.0
                    : Math.max(0.0, Math.min(1.0, remainingTicks / (double) timer.durationTicks()));
            scoreboard.showCountdown(text, progress, viewers);
            if (remaining != lastCountdownSecond) {
                lastCountdownSecond = remaining;
                debug("准备倒计时：剩余 " + remaining + " 秒（房间内已入队 " + queued + " 人）");
                for (Player player : viewers) {
                    alerts.sendActionBarTo(player, "prepare-countdown",
                            Map.of("seconds", Long.toString(remaining)));
                }
            }
        }
    }

    /** 队列内仍在线的玩家。 */
    private List<Player> queuePlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : queue) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * 取消准备倒计时并把进度清零，向队列内的玩家提示原因。
     *
     * <p>必须把 {@code phase} 退回 {@link GamePhase#IDLE}：否则“游戏已经开始”的状态会残留，
     * 玩家在倒计时被取消后点“加入游戏”仍会被拒绝。</p>
     *
     * @param messageKey 提示文案键
     */
    private void cancelCountdown(String messageKey) {
        if (timer == null && phase != GamePhase.PREPARING) {
            return;
        }
        debug("准备倒计时被取消：" + messageKey + "（房间内已入队 " + queuedInRoom() + " 人）");
        timer = null;
        phase = GamePhase.IDLE;
        prepareClicks = 0;
        lastCountdownSecond = -1L;
        scoreboard.hideCountdown();
        alerts.broadcastTo(queuePlayers(), messageKey, Map.of());
    }

    private void tickRunning(long now) {
        if (timer == null) {
            return;
        }
        long remainingTicks = timer.remainingTicks(now);
        long elapsedTicks = timer.elapsedTicks(now);
        Settings settings = config.settings();

        // 0) 场地范围判定（离场/观战离场/无关玩家清场）
        //    刚开局的若干 tick 内不做判定：玩家的传送是异步的，此刻他们读到的仍是
        //    准备房间（在场地外）的旧坐标，会被立刻误判为“离开游戏”，一局直接作废。
        if (elapsedTicks >= SETTLE_TICKS) {
            checkArenaPresence();
        }
        // 存活人数只算一次：本 tick 的记分板、周期复核都复用它，
        // 否则同一 tick 会把名单遍历三遍
        int alive = aliveCount();

        // 1) 记分板：剩余时间与存活人数都写在“记分值”上（条目名是标签）
        if (settings.scoreboard().enabled()) {
            long seconds = Math.max(0L, remainingTicks) / 20L;
            scoreboard.update(settings, (int) seconds, alive);
        }

        // 2) 剩 N 秒时把场内玩家集中到决斗圈
        //    用“上一 tick 的剩余时间”做闸门：服务器卡顿导致一次跳过多个 tick 时，
        //    只要跨过阈值就触发，但不会因为载入旧存档（剩余时间本来就很小）而立刻传送
        long duelThresholdTicks = settings.timing().duelTeleportAtSeconds() * 20L;
        boolean crossedThreshold = lastRemainingTicks > duelThresholdTicks && remainingTicks <= duelThresholdTicks;
        boolean alreadyBelow = remainingTicks > 0 && remainingTicks <= duelThresholdTicks && lastRemainingTicks == Long.MAX_VALUE;
        if (!duelTeleported && (crossedThreshold || alreadyBelow)) {
            duelTeleported = true;
            teleportToDuel();
        }
        lastRemainingTicks = remainingTicks;

        // 3) 每 interval 秒发一颗绿宝石（用“距上次发放已经过多少秒”判定，丢 tick 也不会漏发）
        long elapsedSeconds = elapsedTicks / 20L;
        long interval = Math.max(1L, settings.timing().emeraldIntervalSeconds());
        if (elapsedSeconds >= lastEmeraldSecond + interval) {
            lastEmeraldSecond = elapsedSeconds;
            giveEmeralds();
        }
        // 4) 倒计时归零：每秒扣血
        if (remainingTicks <= 0) {
            long overtime = (-remainingTicks) / 20L;
            if (overtime > lastOvertimeSecond) {
                lastOvertimeSecond = overtime;
                applyOvertimeDamage();
            }
        }

        // 5) 周期性复核兜底：存活人数跌破两人时结束本局。
        //    正常路径由 eliminate → checkVictory 提前收尾；这里只处理“扣血致死那一步
        //    就发生在本次 tick、checkVictory 的任务又排在引擎之后”的时序，因此必须
        //    自己完成播报，否则玩家只会看到一句“游戏结束”而不知道结果。
        if (elapsedTicks % 80L == 0L && alive < 2) {
            settleFromRoster();
        }
    }

    /** 按当前名单直接判定结果并结束对局（兜底路径，保证与 checkVictory 播报一致）。 */
    private void settleFromRoster() {
        if (ended || phase != GamePhase.RUNNING) {
            return;
        }
        ended = true;
        List<Player> alive = onlineMembers();
        if (alive.size() == 1) {
            Player winner = alive.get(0);
            alerts.broadcast("win", Map.of("player", winner.getName()));
            eliminate(winner, null, false);
            resetWinnerState(winner);
        } else {
            alerts.broadcast("no-survivor", Map.of());
        }
        endGame();
    }

    private void tickEnding(long now) {
        if (timer == null || timer.expired(now)) {
            finishGame();
        }
    }

    // ------------------------------------------------------------------
    // 准备房间
    // ------------------------------------------------------------------

    /** 调试日志：仅在 /fmwar debug 打开时输出，级别用 INFO 以便无需改服务端配置。 */
    private void debug(String message) {
        if (debug) {
            plugin.getLogger().info("[调试] " + message);
        }
    }

    /** 切换调试日志；返回切换后的状态。 */
    public boolean toggleDebug() {
        debug = !debug;
        return debug;
    }

    public boolean isDebug() {
        return debug;
    }

    /** 计算“新进入准备房间”的玩家（规则见 {@link PrepRoom}）。 */
    public static Set<UUID> entrants(Set<UUID> previous, Set<UUID> current) {
        return PrepRoom.entrants(previous, current);
    }

    /**
     * 同步准备房间名单，并处理“离开准备房间即退出队列”（需求 39）。
     *
     * <p>这段逻辑每 tick 都跑（含 IDLE 阶段），因此队列成员集合始终等于
     * “已入队且仍在准备房间内”的玩家，准备倒计时的人数判定也用它，而不是队列总数。</p>
     *
     * <p><b>准备进度只在“有玩家进入”时清零</b>（需求 37：“期间准备房间范围有玩家进入则重置”）。
     * 早先的实现在**任何**名单变化时都清零，于是“有人离开”“倒计时结束后名单重新采样”
     * 这类非进入事件也会把进度打回 0，表现为进度永远停在 1/7。</p>
     */
    private void syncPrepRoster() {
        Set<UUID> current = playersInRegion(config.settings().region("prep-room"));
        Set<UUID> previous = prepRoster;
        boolean roomChanged = !current.equals(previous);
        prepRoster = current;

        // 只认“新进入”的玩家；离开不算进入，因此不会误清零进度
        Set<UUID> entered = PrepRoom.entrants(previous, current);
        if (roomChanged) {
            // 已入队但已不在准备房间内的玩家：退出队列（需求 39）
            for (UUID uuid : new ArrayList<>(queue)) {
                if (current.contains(uuid)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(uuid);
                queue.remove(uuid);
                debug("玩家 " + (player == null ? uuid : player.getName())
                        + " 已不在准备房间范围内，退出队列（队列剩余 " + queue.size() + "）");
                if (player != null) {
                    alerts.sendTo(player, "queue-left-self", Map.of());
                    alerts.broadcast("queue-leave", Map.of("player", player.getName()));
                }
            }
            // 排查用：名单变化时写下“谁新进来”，便于确认进度是被真实进入事件还是
            // 异常抖动重置的
            if (!entered.isEmpty() && prepareClicks > 0) {
                debug("准备房间新增 " + entered.size() + " 名玩家，准备进度由 "
                        + prepareClicks + " 重置为 0");
            }
        }

        // 需求 37：只在“有玩家新进入”时清零准备进度。
        // 判定本身放在 PrepRoom 里并被断言覆盖——离开与名单不变都不该清零进度。
        // 注意：这段必须在倒计时的提前 return **之前**执行，否则“倒计时中有人新进房间”
        // 时进度不会被清空（曾经的缺陷）。
        if (PrepRoom.shouldResetProgress(previous, current, prepareClicks)) {
            debug("准备进度由 " + prepareClicks + " 清零：新进入 " + entered.size() + " 人");
            prepareClicks = 0;
            alerts.broadcastTo(onlinePlayers(current), "prepare-reset", Map.of());
        } else if (!entered.isEmpty()) {
            debug("有 " + entered.size() + " 人新进入准备房间，但当前进度为 " + prepareClicks + "，无需清零");
        }

        if (timer != null) {
            // 倒计时在跑：本轮只做名单同步与进度清零，是否取消交给 tickPreparing 统一判定
            //（取消需要“人数不足”或“有人新进入”这些条件，绝不能无条件取消——
            //  那会让倒计时刚起步就被重置）
            return;
        }
    }

    /** 准备房间内**已入队**的玩家数量（需求里的“准备房间范围满足至少两名玩家”）。 */
    private int queuedInRoom() {
        int count = 0;
        for (UUID uuid : playersInRegion(config.settings().region("prep-room"))) {
            if (queue.contains(uuid)) {
                count++;
            }
        }
        return count;
    }

    /** 队列是否够人（准备房间内至少两名已入队玩家）。 */
    public boolean canStart() {
        return queuedInRoom() >= 2;
    }

    /** 右键准备按钮。 */
    public void prepareClick(Player player) {
        Settings settings = config.settings();
        long required = settings.timing().prepareClicks();
        debug("准备按钮被点击：玩家=" + player.getName() + " 阶段=" + phase
                + " 进度=" + prepareClicks + "/" + required
                + " 倒计时中=" + (timer != null)
                + " 房间内已入队=" + queuedInRoom()
                + " 队列=" + queue.size());
        // 倒计时已经开始后不再接受点击：否则每点一下都会把倒计时重建一次
        //（表现为“进度涨到 35/7 却永远不开始”）
        if (timer != null) {
            alerts.sendActionBarTo(player, "prepare-countdown",
                    Map.of("seconds", Long.toString(TimeUtil.ceilSeconds(timer.remainingTicks(Bukkit.getCurrentTick())))));
            return;
        }
        if (!canStart()) {
            debug("准备按钮被拒绝：准备房间内已入队玩家不足 2 人（当前 " + queuedInRoom() + "）");
            alerts.sendActionBarTo(player, "prepare-need-two", Map.of());
            return;
        }
        // 计数上限就是目标次数，绝不越过（避免出现 35/7 这类读数）
        prepareClicks = (int) Math.min(required, prepareClicks + 1L);
        alerts.sendActionBarTo(player, "prepare-progress", Map.of(
                "clicks", Integer.toString(prepareClicks),
                "required", Long.toString(required)));
        // 需求：准备进度要常驻显示（此前只在点按钮时闪过动作栏），且只发给队列内的玩家
        String progressText = alerts.render("prepare-progress-bar", Map.of(
                "clicks", Integer.toString(prepareClicks),
                "required", Long.toString(required)));
        scoreboard.showProgress(progressText, prepareClicks / (double) Math.max(1L, required), queuePlayers());
        if (prepareClicks >= required) {
            long countdownTicks = Math.max(1L, settings.timing().prepareCountdownSeconds()) * 20L;
            timer = new Timer(GamePhase.PREPARING, Bukkit.getCurrentTick(), countdownTicks);
            phase = GamePhase.PREPARING;
            lastCountdownSecond = -1L;
            // 进入倒计时瞬间立刻取一次名单基准值：这一 tick 的事件回调先于引擎 tick 执行，
            // 若不刷新，引擎 tick 里的 syncPrepRoster 会把“原本就在房间里的人”当成新进入者，
            // 于是刚点满的进度立刻被清零、倒计时当 tick 就被取消
            prepRoster = playersInRegion(settings.region("prep-room"));
            alerts.broadcast("prepare-announce",
                    Map.of("seconds", Long.toString(settings.timing().prepareCountdownSeconds())));
            plugin.getLogger().info("准备完成（" + prepareClicks + "/" + required + "），"
                    + settings.timing().prepareCountdownSeconds() + " 秒后开始对局");
            debug("已进入 PREPARING：倒计时 " + countdownTicks + " tick，"
                    + "名单基准 " + prepRoster.size() + " 人");
        }
    }

    /** 右键返回大厅按钮：退出队列并回大厅。 */
    public void leaveToHall(Player player) {
        queue.remove(player.getUniqueId());
        // 传送本身由 teleport() 临时放行后立即恢复，这里不需要额外处理领地权限
        teleport(player, config.settings().location("hall-spawn"));
        teams.leaveAll(player.getUniqueId());
        scoreboard.detach(player);
        alerts.sendTo(player, "queue-left-self", Map.of());
        alerts.broadcast("queue-leave", Map.of("player", player.getName()));
    }

    // ------------------------------------------------------------------
    // 队列
    // ------------------------------------------------------------------

    /** 对局是否已经开打（含正在结算的那一 tick）。 */
    public boolean isRunning() {
        return phase == GamePhase.RUNNING || phase == GamePhase.ENDING;
    }

    /** 当前是否处于“已经开始、尚未结算完成”的活跃对局（防止对局外玩家被淘汰逻辑带走）。 */
    public boolean isActive() {
        return phase == GamePhase.RUNNING;
    }

    /** 是否正在收尾结算（此时不应再放人进场）。 */
    public boolean isSettling() {
        return phase == GamePhase.ENDING;
    }

    public boolean tryJoinQueue(Player player) {
        // 先判阶段：对局已经开打（含正在结算的那一 tick）时直接拒绝，
        // 避免下面为“准备倒计时期间的加入”取消倒计时这件事被误触发。
        if (phase != GamePhase.IDLE && phase != GamePhase.PREPARING) {
            alerts.sendTo(player, "game-already-running", Map.of());
            return false;
        }

        // 需求：入队前必须清空背包（含光标上的物品）。
        // 检查刻意放在 cancelCountdown 之前：一次无效点击不该把正在跑的倒计时取消掉，
        // 也不该让玩家带着上一局的装备进入准备房间。
        if (hasAnyItem(player)) {
            debug("玩家 " + player.getName() + " 入队被拒：背包未清空");
            alerts.sendTo(player, "queue-inventory-not-empty", Map.of());
            return false;
        }

        // 需求：准备倒计时期间也允许加入队列——新玩家进入意味着“有人进入准备房间”，
        // 此时应当取消倒计时并重置准备进度，而不是把大厅里的人挡在门外。
        if (phase == GamePhase.PREPARING) {
            cancelCountdown("prepare-cancel-joined");
        }
        UUID uuid = player.getUniqueId();
        if (queue.contains(uuid)) {
            alerts.sendTo(player, "queue-already-joined", Map.of());
            return false;
        }
        queue.add(uuid);
        // 需求：准备房间的领地常态关闭传送权限。传送本身走 teleport()，
        // 它会在传送期间临时放行、传送完成立刻恢复，因此这里不需要常驻打开权限。
        teleport(player, config.settings().location("prep-spawn"));
        // 入队即挂上本插件的记分板：准备倒计时与进度的常驻显示依赖它
        scoreboard.attach(player, config.settings());
        alerts.sendTo(player, "queue-joined-self", Map.of());
        alerts.broadcast("queue-join", Map.of("player", player.getName()));
        debug("玩家 " + player.getName() + " 加入队列，当前队列 " + queue.size() + " 人");

        // 传送是异步的：领地插件若拦下这次传送，future 会以 false 完成。
        // 届时必须把刚刚入队的玩家摘掉，否则会出现“人在大厅、队列里却有人”的幽灵状态，
        // 准备房间人数判定会被它拉到 2 人从而启动倒计时。
        teleportAsyncWithResult(player, config.settings().location("prep-spawn").toLocation())
                .whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error == null && Boolean.TRUE.equals(ok)) {
                        return;
                    }
                    // 玩家可能在等待期间自己又退了队/下线，只有仍在队列中才回滚
                    if (!queue.remove(uuid)) {
                        return;
                    }
                    scoreboard.detach(player);
                    Player online = Bukkit.getPlayer(uuid);
                    String name = online == null ? player.getName() : online.getName();
                    debug("玩家 " + name + " 入队传送失败（被领地/插件拦截或取消），已从队列移除");
                    if (online != null) {
                        alerts.sendTo(online, "queue-teleport-failed", Map.of());
                    }
                    alerts.broadcast("queue-leave", Map.of("player", name));
                }));
        return true;
    }

    /**
     * 玩家身上是否有任何物品。
     *
     * <p>用 {@link PlayerInventory#getContents()} 整体判定：它同时覆盖主背包、快捷栏、
     * 护甲与副手，比只看 {@code getStorageContents()} 更贴近“背包已经清空”的语义，
     * 也正好对应 {@code applyStartState} / {@code clearPlayerState} 里
     * {@code getInventory().clear()} 的清理范围。光标上的物品不属于容器内容，
     * 需要单独判一次。</p>
     */
    private boolean hasAnyItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                return true;
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        return cursor != null && !cursor.getType().isAir();
    }

    /**
     * 队列成员是否仍在准备房间内。
     *
     * <p>队列成员的清理由 {@code syncPrepRoster} 每 tick 统一负责（需求 39：离开准备房间即退出队列），
     * 因此这里只做查询，避免出现两处各自增删队列导致口径不一致。</p>
     */
    public boolean isQueued(UUID uuid) {
        return queue.contains(uuid);
    }

    public Set<UUID> queue() {
        return Set.copyOf(queue);
    }

    /** 玩家是否在本次对局名单里（队伍 fm 的成员集合）。 */
    public boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    /** 对局名单人数（包含暂时离线的成员）。 */
    public int memberCount() {
        return members.size();
    }

    /**
     * 从队列里把还在准备房间的玩家直接拉进对局并立即开局（/fmwar start）。
     *
     * @return 实际参战人数
     */
    public int prepareFromQueue() {
        if (phase != GamePhase.IDLE) {
            return 0;
        }
        int queued = 0;
        for (UUID uuid : queue) {
            if (Bukkit.getPlayer(uuid) != null) {
                queued++;
            }
        }
        if (queued < 2) {
            return queued;
        }
        beginGame();
        return members.size();
    }

    public GamePhase phase() {
        return phase;
    }

    public long remainingTicks() {
        if (timer == null) {
            return 0L;
        }
        return timer.remainingTicks(Bukkit.getCurrentTick());
    }

    // ------------------------------------------------------------------
    // 开局
    // ------------------------------------------------------------------

    private void beginGame() {
        Settings settings = config.settings();
        scoreboard.hideCountdown();
        // 领地权限不在这里常驻打开：开局分散、传决斗圈、观战进场各自走 teleport()，
        // 由它在每次传送期间临时放行、传送完成立刻恢复
        phase = GamePhase.RUNNING;
        timer = new Timer(GamePhase.RUNNING, Bukkit.getCurrentTick(), settings.timing().gameDurationSeconds() * 20L);
        members.clear();
        duelTeleported = false;
        ended = false;
        lastOvertimeSecond = -1L;
        lastEmeraldSecond = 0L;
        lastRemainingTicks = Long.MAX_VALUE;
        lastCountdownSecond = -1L;
        prepareClicks = 0;

        List<Player> participants = new ArrayList<>();
        for (UUID uuid : new ArrayList<>(queue)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            members.add(uuid);
            teams.joinPlayerTeam(uuid);
            scoreboard.attach(player, config.settings());
            participants.add(player);
        }
        queue.clear();

        if (participants.isEmpty()) {
            alerts.broadcast("game-over", Map.of());
            ended = true;
            endGame();
            return;
        }

        // 先广播“游戏开始”再做传送与发放：这样玩家一定能看到开局提示，
        // 即使后续某一步（分散/放箱/生成商店）出错也不会静默
        alerts.broadcast("game-start", Map.of());
        disperse(participants);
        for (Player player : participants) {
            applyStartState(player);
        }
        spawnChests();
        if (settings.extraShopsEnabled()) {
            int spawned = shops.spawnAll();
            if (spawned > 0) {
                plugin.getLogger().info("已生成 " + spawned + " 个附魔战争商店 NPC");
            }
        }
        plugin.getLogger().info("对局开始：参战 " + participants.size() + " 人，"
                + "分散落点 " + lastDisperseReport);
    }

    /**
     * 开局前：切回生存模式、清空背包与药水效果（需求：游戏内物品不能带出场地）。
     *
     * <p>刻意**不做背包备份**：备份只存在于内存里，崩服会连同备份一起丢失，
     * 反而让玩家物品更不安全。需要保护玩家物品请用专门的背包备份插件。</p>
     */
    private void applyStartState(Player player) {
        Settings settings = config.settings();
        // 需求：开局把场内玩家（队伍 fm）改成生存模式
        player.setGameMode(GameMode.SURVIVAL);
        if (settings.start().clearInventory()) {
            player.getInventory().clear();
            player.setItemOnCursor(null);
        }
        if (settings.start().clearEffects()) {
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
        }
        if (settings.start().resistanceEnabled()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                    (int) settings.start().resistanceDurationTicks(),
                    settings.start().resistanceAmplifier(), true, false));
        }
        if (settings.start().heal()) {
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH) == null
                    ? 20.0
                    : player.getAttribute(Attribute.MAX_HEALTH).getValue());
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
        }
        if (settings.start().rodEnabled()) {
            ItemStack rod = buildRod(settings);
            if (rod != null) {
                player.getInventory().addItem(rod);
            }
        }
        player.updateInventory();
    }

    private ItemStack buildRod(Settings settings) {
        Material material = Material.matchMaterial(
                String.valueOf(settings.start().rodMaterial()).toUpperCase(Locale.ROOT));
        if (material == null) {
            plugin.getLogger().warning("start.fishing-rod.material 无法识别: " + settings.start().rodMaterial());
            return null;
        }
        ItemStack rod = new ItemStack(material);
        for (Map.Entry<String, Integer> entry : settings.start().rodEnchantments().entrySet()) {
            String keyText = entry.getKey();
            String normalized = keyText.contains(":") ? keyText : "minecraft:" + keyText;
            NamespacedKey key;
            try {
                key = NamespacedKey.fromString(normalized.toLowerCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                key = null;
            }
            Enchantment enchantment = key == null ? null : Registry.ENCHANTMENT.get(key);
            if (enchantment == null) {
                plugin.getLogger().warning("钓竿附魔不存在，已跳过: " + keyText);
                continue;
            }
            rod.addUnsafeEnchantment(enchantment, entry.getValue());
        }
        return rod;
    }

    // ------------------------------------------------------------------
    // 开局分散与决斗圈
    // ------------------------------------------------------------------

    /**
     * 开局分散。
     *
     * <p>落点必须**落在场地内**：分散圆是按半径采样的，而半径可能超出场地矩形，
     * 越界的玩家会在开局后立刻被判“离开游戏”——表现为刚开局就“无人生还”。
     * 因此采样时把场地作为硬约束传进去，越界候选点直接丢弃。</p>
     */
    private void disperse(List<Player> participants) {
        Settings settings = config.settings();
        Settings.Disperse disperse = settings.disperse();
        Region arena = settings.optionalRegion("arena");
        if (!disperse.enabled() || world == null) {
            lastDisperseReport = "已禁用或世界未加载，全部使用场地传送点";
            plugin.getLogger().warning("分散未执行（" + lastDisperseReport + "）");
            for (Player player : participants) {
                teleport(player, settings.location("arena-spawn"));
            }
            return;
        }
        // center 的 y 作为“最高可落脚点”的搜索参考高度；配置里省略 y 时传 0，
        // 此时 SafeLocation 会自行从世界最高点向下找，行为与之前一致
        double referenceY = disperse.centerY();
        List<Location> taken = new ArrayList<>();
        int fallback = 0;
        for (int index = 0; index < participants.size(); index++) {
            final Player player = participants.get(index);
            final int slot = index;
            Location sample = SafeLocation.sampleSquare(world, disperse.centerX(), disperse.centerZ(),
                    disperse.radius(), disperse.minSpacing(), disperse.maxAttempts(),
                    taken, arena, referenceY);
            if (sample != null) {
                taken.add(sample);
                teleport(player, sample);
                continue;
            }
            // 找不到合格点：异步加载该区块后再试一次，仍失败则退回场地传送点
            fallback++;
            int chunkX = (int) Math.floor(disperse.centerX()) >> 4;
            int chunkZ = (int) Math.floor(disperse.centerZ()) >> 4;
            world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
                Location retry = SafeLocation.sampleSquare(world, disperse.centerX(), disperse.centerZ(),
                        disperse.radius(), disperse.minSpacing(), disperse.maxAttempts(),
                        taken, arena, referenceY);
                if (retry != null) {
                    taken.add(retry);
                    teleport(player, retry);
                } else {
                    plugin.getLogger().warning("玩家 " + player.getName() + " 未能找到分散落点（槽位 " + slot
                            + "），已退回场地传送点。请检查 regions.arena 是否覆盖分散范围、"
                            + "以及该范围内是否有可站立地面");
                    teleport(player, settings.location("arena-spawn"));
                }
            }));
        }
        lastDisperseReport = taken.size() + "/" + participants.size() + " 人成功分散"
                + "（中心 " + (long) disperse.centerX() + "," + (long) disperse.centerZ()
                + " 正方形半边长 " + (long) disperse.radius()
                + "，最小间距 " + (long) disperse.minSpacing()
                + (fallback > 0 ? "，" + fallback + " 人走异步重试" : "") + "）";
        // 逐人记录落点：确认落的是“该位置最高可落脚点”而不是房子内部
        for (Player player : participants) {
            Location at = player.getLocation();
            debug("分散落点 " + player.getName() + " -> "
                    + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ()
                    + "（最高方块 y=" + world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) + "）");
        }
    }

    /**
     * 把存活玩家送进决斗圈。
     *
     * <p>需求：到点时**随机选一个**决斗圈（duel-1 / duel-2），选定后本局所有存活玩家
     * 都进**同一个**圈；落点必须在该圈区域内、且不高于配置的高度上限
     * （室内场地的封顶玻璃会挡住“最高可落脚面”，必须靠上限把人压回场地内部）。</p>
     */
    private void teleportToDuel() {
        Settings settings = config.settings();
        World target = world;
        if (target == null) {
            return;
        }
        Settings.DuelArena arena = settings.duel().pick(duelRandom);
        if (arena == null) {
            plugin.getLogger().warning("未配置任何决斗圈（duel.arenas），决斗圈传送已跳过");
            return;
        }
        Region duelRegion = settings.optionalRegion(arena.region());
        if (duelRegion == null) {
            plugin.getLogger().warning("regions." + arena.region()
                    + " 未配置，决斗圈传送已跳过（玩家保持在原地）");
            return;
        }
        // 落点模式由“区域 + 配置”共同决定：区域 y 范围窄时一律锁定高度（以区域为准），
        // 避免从别的圈抄下来的 max-y 把落点顶到区域之外
        boolean exactY = Settings.resolveExactY(duelRegion, arena.exactY());
        double effectiveMaxY = Settings.resolveMaxY(duelRegion, exactY, arena.maxY());
        debug("本局决斗圈：" + arena.region() + "（落点区域 x [" + (long) duelRegion.minX()
                + "," + (long) duelRegion.maxX() + "] y [" + (long) duelRegion.minY()
                + "," + (long) duelRegion.maxY() + "] z [" + (long) duelRegion.minZ()
                + "," + (long) duelRegion.maxZ() + "]，"
                + (exactY
                        ? "高度锁定 y=" + (long) arena.centerY() + "（不搜索地形）"
                        : "搜索最高可落脚面，上限 " + (long) effectiveMaxY
                                + "，允许水面 " + arena.allowWater())
                + "）");

        // 兜底落点：两种模式各自取自己的“中心点安全位”
        Location fallback = exactY
                ? SafeLocation.exact(target, arena.centerX(), arena.centerY(), arena.centerZ())
                : SafeLocation.find(target, arena.centerX(), arena.centerZ(),
                        arena.centerY(), effectiveMaxY, arena.allowWater());
        if (fallback == null) {
            fallback = new Location(target, arena.centerX() + 0.5,
                    Math.max(duelRegion.minY() + 1, arena.centerY()), arena.centerZ() + 0.5);
        }

        List<Location> taken = new ArrayList<>();
        int fallbackCount = 0;
        for (Player player : onlineMembers()) {
            // 在**该区域的整个范围**内取点，而不是“中心 ± 半径”的正方形：
            // 决斗圈区域多为长方形，正方形采样会有死角且容易越界。
            // 锁定高度模式下完全不看地形，直接把高度钉在配置值上。
            Location sample = exactY
                    ? SafeLocation.sampleRegionExactY(target, duelRegion, arena.centerY(),
                            arena.minSpacing(), arena.maxAttempts(), taken)
                    : SafeLocation.sampleRegion(target, duelRegion,
                            arena.minSpacing(), arena.maxAttempts(), taken,
                            arena.centerY(), effectiveMaxY, arena.allowWater());
            if (sample == null) {
                fallbackCount++;
                teleport(player, fallback);
                alerts.sendActionBarTo(player, "duel-teleport", Map.of());
                continue;
            }
            taken.add(sample);
            teleport(player, sample);
            alerts.sendActionBarTo(player, "duel-teleport", Map.of());
        }

        // 观战者一并送进本局选定的那个决斗圈，直接落在圈中心的安全位（fallback）：
        // 不参与落点采样——旁观模式没有碰撞体积，多人重叠没有影响，
        // 集中在中心反而能看清对决。决斗圈区域本就落在 arena 之内，
        // 因此这里不会触发 checkArenaPresence 里的“观战者离场”。
        int spectators = 0;
        for (Player spectator : onlineSpectators()) {
            teleport(spectator, fallback);
            alerts.sendActionBarTo(spectator, "duel-teleport", Map.of());
            spectators++;
        }
        if (spectators > 0) {
            debug("决斗圈：已把 " + spectators + " 名观战者传送到圈中心点（"
                    + fallback.getBlockX() + "," + fallback.getBlockY() + ","
                    + fallback.getBlockZ() + "）");
        }

        if (fallbackCount > 0) {
            plugin.getLogger().warning("决斗圈：" + fallbackCount + " 名玩家未能采样到落点，"
                    + "已使用兜底点（" + fallback.getBlockX() + "," + fallback.getBlockY() + ","
                    + fallback.getBlockZ() + "）。请检查 regions." + arena.region()
                    + (exactY ? " 与 center 的 y" : " 与 max-y"));
        }
    }

    // ------------------------------------------------------------------
    // 奖励箱
    // ------------------------------------------------------------------

    /**
     * 在配置坐标生成奖励箱，每个箱子随机取一行内容放进去。
     *
     * <p>带完整诊断日志：箱子是否放上、用了哪一行、解析出几件物品、失败在哪一步，
     * 都写进控制台。此前“箱子是空的”这类问题只能靠猜，这行日志能直接定位。</p>
     */
    private void spawnChests() {
        Settings.Loot loot = config.settings().loot();
        if (loot.lootGroups().isEmpty()) {
            plugin.getLogger().warning("奖励箱内容行为空（chests.loot-groups），不会生成任何奖励箱");
            return;
        }
        boolean booksOnly = config.settings().start().lootBooksOnly();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int placed = 0;
        int totalItems = 0;
        for (Position position : loot.chestLocations()) {
            Location location = position.toLocation();
            if (location == null || location.getWorld() == null) {
                plugin.getLogger().warning("奖励箱坐标世界未加载: " + position.world());
                continue;
            }
            World target = location.getWorld();
            // 先确保区块已加载：未加载时 getState() 拿不到真正的方块实体，
            // 置物会“看起来成功”但内容丢失（官方示例同样是先 loadChunk 再取箱子）
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            if (!target.isChunkLoaded(chunkX, chunkZ)) {
                target.loadChunk(chunkX, chunkZ);
            }

            Block block = location.getBlock();
            // 只覆盖空气或可替换方块：避免把别人放的建筑直接抹掉，同时保留已有的箱子（只填内容）
            if (block.getType() != Material.CHEST && !block.isReplaceable()) {
                plugin.getLogger().warning("奖励箱坐标 " + position.x() + "," + position.y() + "," + position.z()
                        + " 被 " + block.getType() + " 占用且不可替换，已跳过");
                continue;
            }
            if (block.getType() != Material.CHEST) {
                block.setType(Material.CHEST, false);
                // setType 之后重新取一次方块引用，确保拿到新建的方块实体
                block = location.getBlock();
            }
            // 用**活的方块实体**（useSnapshot=false）而不是快照：
            // 快照的改动必须靠 update() 写回，中间任何一步出错内容就丢了；
            // 活体直接操作 chunk 里的方块实体，且能正确处理双箱（getInventory 返回整个双箱）
            BlockState state = block.getState(false);
            if (!(state instanceof Chest chest)) {
                plugin.getLogger().warning("奖励箱坐标 " + position.x() + "," + position.y() + "," + position.z()
                        + " 无法取得箱子方块实体（区块是否已加载？），已跳过");
                continue;
            }
            Inventory inventory = chest.getBlockInventory();

            List<String> group = loot.lootGroups().get(random.nextInt(loot.lootGroups().size()));
            List<ItemStack> items = plugin.lootParser().parseGroup(group, booksOnly);
            if (items.isEmpty()) {
                plugin.getLogger().warning("奖励箱内容行解析后为空，该箱未放入任何物品：" + group
                        + " —— 多半是附魔键在本服未注册，可用 /fmwar doctor 查看");
            }
            for (int slot = 0; slot < items.size() && slot < inventory.getSize(); slot++) {
                ItemStack item = items.get(slot);
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                if (booksOnly && item.getType() == Material.ENCHANTED_BOOK) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.displayName(
                                Component.translatable("item.minecraft.enchanted_book")
                                        .decoration(TextDecoration.ITALIC, false)  // 自定义名默认斜体，这里去掉
                        );
                        item.setItemMeta(meta);
                    }
                }
                inventory.setItem(slot, item);
            }
            // 活体写入后仍需 update() 通知客户端刷新方块实体视图
            boolean updated = chest.update(true);
            // 读回校验：直接看同一份活体 inventory，确认内容确实进去了
            int readBack = countItems(inventory.getContents());
            if (readBack != items.size()) {
                plugin.getLogger().warning("奖励箱写入校验失败：期望 " + items.size()
                        + " 件，读回 " + readBack + " 件（坐标 "
                        + position.x() + "," + position.y() + "," + position.z()
                        + "，update 返回 " + updated + "。请把这条日志发给开发者）");
            }

            chests.add(block.getLocation());
            placed++;
            totalItems += items.size();
        }
        plugin.getLogger().info("奖励箱：已生成 " + placed + "/" + loot.chestLocations().size()
                + " 个，共放入 " + totalItems + " 件"
                + (booksOnly ? "附魔书" : "物品"));
        debug("奖励箱明细：" + describeChests());
    }

    /** 统计非空物品数量。 */
    private int countItems(ItemStack[] contents) {
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                count++;
            }
        }
        return count;
    }

    /** 逐个箱子列出当前实际内容（读回，不是写入时的记录）。 */
    public String describeChests() {
        if (chests.isEmpty()) {
            return "本次对局没有生成任何奖励箱";
        }
        StringBuilder builder = new StringBuilder();
        for (Location location : chests) {
            builder.append("\n  ").append(location.getBlockX()).append(',')
                    .append(location.getBlockY()).append(',').append(location.getBlockZ()).append(" -> ");
            if (!(location.getBlock().getState(false) instanceof Chest chest)) {
                builder.append("不是箱子方块");
                continue;
            }
            ItemStack[] contents = chest.getBlockInventory().getContents();
            int count = countItems(contents);
            if (count == 0) {
                builder.append("空");
                continue;
            }
            builder.append(count).append(" 件：");
            for (ItemStack item : contents) {
                if (item != null && !item.getType().isAir()) {
                    builder.append(item.getType().name()).append('x').append(item.getAmount()).append(' ');
                }
            }
        }
        return builder.toString();
    }

    /** 奖励箱坐标列表（供指令读回校验）。 */
    public List<Location> chestLocations() {
        return List.copyOf(chests);
    }

    private void clearChests() {
        for (Location location : chests) {
            Block block = location.getBlock();
            if (block.getType() == Material.CHEST) {
                if (block.getState() instanceof Chest chest) {
                    chest.getInventory().clear();
                }
                block.setType(Material.AIR, false);
            }
        }
        chests.clear();
    }

    // ------------------------------------------------------------------
    // 对局中的周期事件
    // ------------------------------------------------------------------

    private void giveEmeralds() {
        int amount = Math.max(1, config.settings().emerald().amount());
        ItemStack emerald = new ItemStack(Material.EMERALD, amount);
        for (Player player : onlineMembers()) {
            player.getInventory().addItem(emerald.clone());
            player.updateInventory();
        }
    }

    private void applyOvertimeDamage() {
        double damage = config.settings().timing().overtimeDamage();
        for (Player player : onlineMembers()) {
            player.damage(damage);
        }
    }

    // ------------------------------------------------------------------
    // 离场、掉线与死亡
    // ------------------------------------------------------------------

    /**
     * 每 tick 检查场地进出：参战者离开→淘汰，观战者离开→送回大厅，
     * 与本局无关的玩家进入→送回大厅（否则可以进来开箱子、干扰对局）。
     */
    private void checkArenaPresence() {
        if (phase != GamePhase.RUNNING) {
            return;
        }
        Region arena = config.settings().region("arena");
        Settings settings = config.settings();
        for (UUID uuid : new ArrayList<>(members)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            if (!arena.contains(player.getLocation())) {
                eliminate(player, "player-arena-exit", true);
            }
        }
        for (Player player : onlineSpectators()) {
            if (!arena.contains(player.getLocation())) {
                exitSpectator(player);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (members.contains(uuid) || teams.inSpectatorTeam(uuid)) {
                continue;
            }
            if (!arena.contains(player.getLocation())) {
                continue;
            }
            if (settings.region("hall").contains(player.getLocation())
                    || settings.region("prep-room").contains(player.getLocation())) {
                continue;
            }
            // 刚被淘汰/刚重生完的玩家不算“无关玩家”：否则会额外收到 arena-forbidden
            // 并被重复传送一次，提示与需求 106 的“离开了游戏”相冲突
            Long grace = recentEliminations.get(uuid);
            if (grace != null) {
                if (Bukkit.getCurrentTick() < grace) {
                    continue;
                }
                recentEliminations.remove(uuid);
            }
            // 冷却 + 目标在受保护区域内：即使某次传送被领地守卫取消，也不会形成每 tick 重传的循环
            long now = Bukkit.getCurrentTick();
            Long until = outsiderCooldown.get(uuid);
            if (until != null && now < until) {
                continue;
            }
            // 提示与传送冷却解耦：冷却只限制传送频率，不该让玩家在场地里滞留却收不到说明
            Long notifiedAt = outsiderNotice.get(uuid);
            if (notifiedAt == null || now - notifiedAt >= OUTSIDER_NOTICE_INTERVAL_TICKS) {
                outsiderNotice.put(uuid, now);
                alerts.sendTo(player, "arena-forbidden", Map.of());
            }
            outsiderCooldown.put(uuid, now + OUTSIDER_COOLDOWN_TICKS);
            teleport(player, settings.location("hall-spawn"));
        }
    }

    /**
     * 胜者回大厅后的“状态复原”：满血、满饥饿、清火、清药水效果、清空中的空气。
     *
     * <p>刻意不碰背包：背包的取舍交给 {@link #clearPlayerState(Player)} 按配置决定，
     * 这里只负责把数值状态恢复成“干净”的样子，避免赢的人顶着 1 滴血、身上着火、
     * 中毒状态回大厅。</p>
     */
    private void resetWinnerState(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double full = maxHealth == null ? 20.0 : maxHealth.getValue();
        // 血量至少要留 1：某些属性插件把 maxHealth 临时压到 0 时 setHealth(0) 会直接弄死玩家
        player.setHealth(Math.max(1.0, full));
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setExhaustion(0F);
        player.setFireTicks(0);
        player.setFallDistance(0F);
        player.setRemainingAir(player.getMaximumAir());
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.updateInventory();
    }

    /** 淘汰一名游戏内玩家：清背包、清效果、移出队伍、送回大厅并广播。 */
    public void eliminate(Player player, String messageKey, boolean broadcast) {
        eliminate(player, messageKey, broadcast, false);
    }

    /**
     * 淘汰一名游戏内玩家。
     *
     * @param deferredRespawn true 表示这次淘汰由死亡触发，需要在下一 tick 主动把玩家
     *                        从死亡界面拉回来（并在此期间把它登记进“待重生名单”，
     *                        使重生点监听只影响本插件淘汰的玩家）
     */
    public void eliminate(Player player, String messageKey, boolean broadcast, boolean deferredRespawn) {
        UUID uuid = player.getUniqueId();
        if (!members.remove(uuid)) {
            return;
        }
        clearPlayerState(player);
        teams.leaveAll(uuid);
        scoreboard.detach(player);
        recentEliminations.put(uuid, Bukkit.getCurrentTick() + ELIMINATION_GRACE_TICKS);
        if (deferredRespawn) {
            // 先登记再传送：PlayerRespawnEvent 触发时才知道“这是被本插件淘汰的人”
            pendingRespawn.add(uuid);
        }
        // 传送豁免：淘汰后的传送可能落到领地内，必须保证成功
        teleport(player, config.settings().location("hall-spawn"));
        if (deferredRespawn) {
            scheduleRespawn(player);
        }
        if (messageKey != null && !messageKey.isEmpty()) {
            if (broadcast) {
                alerts.broadcast(messageKey, Map.of("player", player.getName()));
            } else {
                alerts.sendTo(player, messageKey, Map.of("player", player.getName()));
            }
        }
        if (!ended && phase == GamePhase.RUNNING) {
            checkVictory();
        }
    }

    /**
     * 该玩家是否正处于“被本插件淘汰、等待主动重生”的窗口内。
     *
     * <p>只有窗口内的玩家才会被 {@code PlayerStateListener} 改写重生点——
     * 这样 FMWar 绝不会影响服务器上其他玩家的死亡重生位置。</p>
     */
    public boolean consumePendingRespawn(UUID uuid) {
        return pendingRespawn.remove(uuid);
    }

    /**
     * 死亡淘汰后把玩家从死亡界面拉回来，并确保落点是大厅。
     *
     * <p>只有死亡路径会调用它。离场/掉线等路径没有死亡界面，若也调用 respawn，
     * 最终落点会被服务器重生点覆盖掉刚刚的大厅传送。</p>
     */
    private void scheduleRespawn(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                pendingRespawn.remove(player.getUniqueId());
                return;
            }
            if (player.isDead()) {
                // 必须在 respawn() **之前**保留待重生标记：PlayerRespawnEvent 正是在
                // respawn() 内部同步触发的，标记若先被清掉，监听就不会把落点改成大厅
                player.spigot().respawn();
            }
            pendingRespawn.remove(player.getUniqueId());
            teleport(player, config.settings().location("hall-spawn"));
        });
    }

    /**
     * 离场/淘汰时清空玩家状态。
     *
     * <p>不做任何背包还原：本插件不保留入场备份（内存备份在崩服时会丢失，
     * 反而让玩家物品更不安全）。需要保护玩家原有物品请用专门的背包备份插件。</p>
     */
    private void clearPlayerState(Player player) {
        Settings settings = config.settings();
        if (settings.start().clearInventory()) {
            player.getInventory().clear();
            player.setItemOnCursor(null);
        }
        if (settings.start().clearEffects()) {
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
        }
        player.updateInventory();
    }

    /** 观战玩家离场：回大厅 + 生存模式 + 移出队伍。 */
    public void exitSpectator(Player player) {
        teams.leaveAll(player.getUniqueId());
        scoreboard.detach(player);
        player.setGameMode(GameMode.SURVIVAL);
        teleport(player, config.settings().location("hall-spawn"));
        alerts.sendTo(player, "spectator-arena-exit", Map.of());
    }

    /** 右键观战按钮。 */
    public boolean trySpectate(Player player) {
        if (!isActive()) {
            // 结算中的那一 tick 也不放人进场，否则刚进来就会被清场
            alerts.sendTo(player, "spectator-unavailable", Map.of());
            return false;
        }
        if (members.contains(player.getUniqueId())) {
            alerts.sendTo(player, "spectator-only-in-game", Map.of());
            return false;
        }
        if (teams.inSpectatorTeam(player.getUniqueId())) {
            alerts.sendTo(player, "spectator-already", Map.of());
            return false;
        }
        teams.joinSpectatorTeam(player.getUniqueId());
        player.setGameMode(GameMode.SPECTATOR);
        scoreboard.attach(player, config.settings());
        teleport(player, config.settings().location("arena-spawn"));
        alerts.sendTo(player, "spectator-enter", Map.of());
        return true;
    }

    /**
     * 玩家掉线。
     *
     * <p>需求 108：参战者掉线即视为离开游戏——当场清空背包与药水效果、移出队伍，
     * 并登记“下次上线送回大厅”，而不是靠队伍状态去猜。</p>
     */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        boolean retiredFromGame = false;

        if (members.remove(uuid)) {
            // 只要不是空闲阶段就清理：ENDING 的那一 tick 也算本局，否则背包会留到下一局，
            // 而入场备份会随 resetRuntimeState 一起丢掉，等于永久损失玩家物品
            if (phase != GamePhase.IDLE) {
                clearPlayerState(player);
            }
            teams.leaveAll(uuid);
            scoreboard.detach(player);
            alerts.broadcast("quit", Map.of("player", player.getName()));
            if (phase == GamePhase.RUNNING && !ended) {
                checkVictory();
            }
            retiredFromGame = true;
        } else if (teams.inSpectatorTeam(uuid)) {
            if (phase == GamePhase.RUNNING) {
                // 需求 104：对局中掉线的观战者，上线后仍为观战
                disconnectedSpectators.add(uuid);
            } else {
                teams.leaveAll(uuid);
                retiredFromGame = true;
            }
        }

        // 需求 39 后半：准备房间内掉线同样退出队列，上线时传送至大厅
        if (queue.remove(uuid)) {
            retiredFromGame = true;
        }

        if (retiredFromGame) {
            // 这张名单不随对局结算清空：否则“对局中掉线、对局结束后才上线”的玩家
            // 会三条分支全落空，直接以登出坐标留在场地里
            pendingHall.add(uuid);
        }

        // 掉线者下一 tick 起不在准备房间内，同步名单避免被当成“仍在房间内”
        Set<UUID> current = new HashSet<>(prepRoster);
        current.remove(uuid);
        prepRoster = current;
    }

    /** 玩家上线。 */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();

        // 需求 104：对局中掉线的观战者重新上线，仍为观战模式、仍在队伍 fmgz。
        // 这一支必须先于 pendingHall 判定——观战者掉线时也会进 pendingHall，
        // 但对局仍在进行时应当恢复观战，而不是被送回大厅
        if (disconnectedSpectators.remove(uuid)) {
            pendingHall.remove(uuid);
            if (phase == GamePhase.RUNNING) {
                player.setGameMode(GameMode.SPECTATOR);
                scoreboard.attach(player, config.settings());
                teleport(player, config.settings().location("arena-spawn"));
            } else {
                player.setGameMode(GameMode.SURVIVAL);
                teams.leaveAll(uuid);
                scoreboard.detach(player);
                teleport(player, config.settings().location("hall-spawn"));
            }
            return;
        }

        // 需求 108 / 39：对局中掉线的参战者、准备房间内掉线的入队玩家——
        // 无论对局是否已结束，上线一律移出队伍、恢复生存模式并传送至大厅
        if (pendingHall.remove(uuid)) {
            player.setGameMode(GameMode.SURVIVAL);
            teams.leaveAll(uuid);
            scoreboard.detach(player);
            teleport(player, config.settings().location("hall-spawn"));
            alerts.sendTo(player, "player-arena-exit", Map.of("player", player.getName()));
            return;
        }

        if (phase == GamePhase.RUNNING) {
            if (members.contains(uuid)) {
                // 名单里仍有此人（例如跨 tick 的边界情况）：按观战处理，不再回到对局
                teams.joinSpectatorTeam(uuid);
                player.setGameMode(GameMode.SPECTATOR);
                scoreboard.attach(player, config.settings());
                teleport(player, config.settings().location("arena-spawn"));
                return;
            }
            if (teams.inSpectatorTeam(uuid)) {
                player.setGameMode(GameMode.SPECTATOR);
                scoreboard.attach(player, config.settings());
                teleport(player, config.settings().location("arena-spawn"));
                return;
            }
        }
        if (teams.inSpectatorTeam(uuid) || teams.inPlayerTeam(uuid)) {
            // 对局已结束或已离开：清干净并送回大厅
            teams.leaveAll(uuid);
            scoreboard.detach(player);
            player.setGameMode(GameMode.SURVIVAL);
            teleport(player, config.settings().location("hall-spawn"));
        }
    }

    // ------------------------------------------------------------------
    // 胜负判定
    // ------------------------------------------------------------------

    /** 场地内（队伍 fm、在线、在场地范围内）的存活人数。 */
    public int aliveCount() {
        return onlineMembers().size();
    }

    private List<Player> onlineMembers() {
        Region arena = config.settings().region("arena");
        List<Player> result = new ArrayList<>();
        for (UUID uuid : members) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (arena.contains(player.getLocation())) {
                result.add(player);
            }
        }
        return result;
    }

    private List<Player> onlineSpectators() {
        List<Player> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (teams.inSpectatorTeam(player.getUniqueId())) {
                result.add(player);
            }
        }
        return result;
    }

    /** 只剩一名玩家则胜利；一名不剩则无人生还。挂起 1 tick，避开死亡结算中的中间态。 */
    private void checkVictory() {        if (phase != GamePhase.RUNNING || ended) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            // ended 标志保证同一局只结算一次：否则“宣布胜利 → 淘汰胜利者”会再触发一次
            // “无人生还”，同一局出现两条矛盾提示
            if (phase != GamePhase.RUNNING || ended) {
                return;
            }
            List<Player> alive = onlineMembers();
            if (alive.size() == 1) {
                Player winner = alive.get(0);
                ended = true;
                // 需求：获得最终胜利 +3 分
                points.remember(winner.getUniqueId(), winner.getName());
                points.addWin(winner.getUniqueId());
                points.save();
                alerts.broadcast("win", Map.of("player", winner.getName()));
                eliminate(winner, null, false);
                // 淘汰流程负责传送与清背包；胜者的血量/饥饿/药水状态在这里单独复原
                resetWinnerState(winner);
                endGame();
            } else if (alive.isEmpty()) {
                ended = true;
                alerts.broadcast("no-survivor", Map.of());
                endGame();
            }
        });
    }

    // ------------------------------------------------------------------
    // 结束与清场
    // ------------------------------------------------------------------

    /**
     * 参战者死亡：按最后伤害来源记击杀分（+1），并完成淘汰流程。
     *
     * <p>由 {@code PlayerStateListener} 在装备掉落已清空之后调用。</p>
     *
     * @return 是否确实处理了这次死亡（不在名单里的玩家返回 false）
     */
    public boolean onPlayerDeath(Player player) {
        if (phase != GamePhase.RUNNING || ended || !members.contains(player.getUniqueId())) {
            return false;
        }
        UUID killer = lastDamager.remove(player.getUniqueId());
        eliminate(player, "death", true, true);
        if (killer != null && !killer.equals(player.getUniqueId())) {
            Player killerPlayer = Bukkit.getPlayer(killer);
            if (killerPlayer != null) {
                points.remember(killer, killerPlayer.getName());
            }
            points.addKill(killer);
            points.save();
        }
        return true;
    }

    /**
     * 请求中止对局：走正常结算路径（ENDED → 下一 tick 清场），因此会广播游戏结束，
     * 而不是像 {@link #stop()} 那样静默强清（后者只用于插件停用的兜底）。
     */
    public void requestEnd() {
        if (phase == GamePhase.IDLE || phase == GamePhase.ENDING) {
            return;
        }
        ended = true;
        endGame();
    }

    private void endGame() {
        phase = GamePhase.ENDING;
        timer = new Timer(GamePhase.ENDING, Bukkit.getCurrentTick(), 1L);
    }

    private void finishGame() {
        Settings settings = config.settings();
        for (Player player : new ArrayList<>(onlineMembers())) {
            eliminate(player, null, false);
        }
        for (Player player : onlineSpectators()) {
            teams.leaveAll(player.getUniqueId());
            scoreboard.detach(player);
            player.setGameMode(GameMode.SURVIVAL);
            teleport(player, settings.location("hall-spawn"));
        }
        clearChests();
        clearArenaEntities();
        shops.despawnAll();
        scoreboard.detachAll();
        sendPendingHallToLobby();
        resetRuntimeState();
        // 对局结束：积分写盘（一局里分数变动频繁，不必每次加分都落盘）
        points.save();
        alerts.broadcast("game-over", Map.of());
    }

    /** 插件停用/异常时的兜底清理：不广播，只保证不留下悬空状态。 */
    private void forceCleanup() {
        for (UUID uuid : new ArrayList<>(members)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                eliminate(player, null, false);
            }
        }
        for (Player player : onlineSpectators()) {
            teams.leaveAll(player.getUniqueId());
            scoreboard.detach(player);
            player.setGameMode(GameMode.SURVIVAL);
            teleport(player, config.settings().location("hall-spawn"));
        }
        clearChests();
        clearArenaEntities();
        shops.despawnAll();
        scoreboard.detachAll();
        sendPendingHallToLobby();
        resetRuntimeState();
    }

    /**
     * 兜底：若 pendingHall 里还有**在线**玩家，就地把他们送回大厅。
     *
     * <p>正常情况下这些人在掉线时已处理，上线时由 {@code onJoin} 处理；这里只覆盖
     * “标记写下后玩家又上线、但走的不是 onJoin 路径”之类的边角情形，避免清场后
     * 还有人留在场地里。名单本身刻意保留（跨对局生效）。</p>
     */
    private void sendPendingHallToLobby() {
        for (UUID uuid : new ArrayList<>(pendingHall)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            pendingHall.remove(uuid);
            player.setGameMode(GameMode.SURVIVAL);
            teams.leaveAll(uuid);
            scoreboard.detach(player);
            teleport(player, config.settings().locationOrNull("hall-spawn"));
        }
    }

    /**
     * 清空全部运行期状态。
     *
     * <p>两条清理路径（正常结算 {@code finishGame} 与停用兜底 {@code forceCleanup}）
     * 共用一份实现，避免漏清某个集合——此前的“漏清冷却表”正是这么来的。</p>
     */
    private void resetRuntimeState() {
        members.clear();
        queue.clear();
        prepRoster = Set.of();
        outsiderCooldown.clear();
        outsiderNotice.clear();
        recentEliminations.clear();
        pendingRespawn.clear();
        disconnectedSpectators.clear();
        lastDamager.clear();
        chests.clear();
        prepareClicks = 0;
        duelTeleported = false;
        lastOvertimeSecond = -1L;
        lastEmeraldSecond = 0L;
        lastRemainingTicks = Long.MAX_VALUE;
        lastCountdownSecond = -1L;
        timer = null;
        ended = false;
        phase = GamePhase.IDLE;
        // 对局结束：把两处领地权限都恢复为服务器设定的常态（准备房间关闭、场地按服主配置）
        residence.reset();
        // 刻意不清 pendingHall：它描述的是“离线玩家下次上线怎么处理”，
        // 生命周期跨越对局边界。若在这里清空，对局中掉线、结束后才上线的玩家会失去标记，
        // 直接以登出坐标留在场地里（需求 108 / 39 落空）。
    }

    /** 大厅传送点；未配置或世界未加载时返回 null。供重生点设置使用。 */
    public Location hallLocation() {
        Position hall = config.settings().locationOrNull("hall-spawn");
        return hall == null ? null : hall.toLocation();
    }

    /**
     * 清空场地范围内所有实体（不含玩家）。
     *
     * <p>需求 118：“清空游戏场地范围内所有实体（不包括玩家，此时玩家应全部离开场地范围）”。
     * 只删自己放的奖励箱是不够的——掉落物、射出的箭、投掷物、载具都要一并清理。</p>
     */
    /**
     * 清空场地范围内所有实体（不含玩家，也不含自定义铁砧）。
     *
     * <p>需求 118：“清空游戏场地范围内所有实体（不包括玩家，此时玩家应全部离开场地范围）”。
     * 只有奖励箱、掉落物、箭矢、载具等都要一并清理。</p>
     *
     * <p><b>自定义铁砧（插件 customanvil）必须排除</b>：它是场地里的常驻设施，
     * 属于玩家自己摆放的功能性实体，清掉会破坏场地布置。这里同时检查记分板标签与
     * PersistentDataContainer 两种标记方式，覆盖它的 Interaction 与 BlockDisplay 实体。</p>
     */
    private void clearArenaEntities() {
        Region arena = config.settings().optionalRegion("arena");
        if (arena == null) {
            return;
        }
        World target = arena.bukkitWorld();
        if (target == null) {
            return;
        }
        // 先取快照再删除：直接在 getEntities() 的返回集合上移除会有并发修改问题
        for (org.bukkit.entity.Entity entity : new ArrayList<>(target.getEntities())) {
            if (entity instanceof Player) {
                continue;
            }
            if (isCustomAnvilEntity(entity)) {
                continue;
            }
            if (arena.contains(entity.getLocation())) {
                entity.remove();
            }
        }
    }

    /** 判定实体是否属于 customanvil 插件的自定义铁砧（标签或 PDC 任一命中即算）。 */
    private boolean isCustomAnvilEntity(org.bukkit.entity.Entity entity) {
        if (entity.getScoreboardTags().contains(ANVIL_TAG)) {
            return true;
        }
        PersistentDataContainer container = entity.getPersistentDataContainer();
        return container.has(new NamespacedKey("customanvil", "anvil_entity"), PersistentDataType.BYTE)
                || container.has(new NamespacedKey("customanvil", "anvil_partner"), PersistentDataType.STRING);
    }

    /** 强制中止（/fmwar stop）。 */
    public void stop() {
        forceCleanup();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 传送到配置里的坐标点。
     *
     * <p>插件不做任何传送权限判断：进入各区域的限制**完全交给领地插件（Residence）
     * 自己的传送权限旗帜**。此前的实现自己拦截了进入保护区的传送，结果连管理员的
     * {@code /tp} 与 {@code /res tp} 都被拦掉，属于越权——已移除。</p>
     */
    public void teleport(Player player, Position target) {
        if (target == null) {
            plugin.getLogger().warning("传送目标未配置，玩家 " + player.getName() + " 未被传送");
            return;
        }
        teleport(player, target.toLocation());
    }

    /**
     * 与 {@link #teleport(Player, Location)} 相同，但把传送成败回传给调用方。
     *
     * <p>领地插件通常在 PlayerTeleportEvent 里取消事件来拦人，这种情况下
     * {@link Player#teleportAsync(Location)} 的 future 会完成为 {@code false}；
     * 调用方据此决定是否需要回滚副作用（例如把刚入队的玩家踢出队列）。</p>
     */
    public CompletableFuture<Boolean> teleportAsyncWithResult(Player player, Location location) {
        if (location == null) {
            plugin.getLogger().warning("传送目标世界未加载，玩家 " + player.getName() + " 未被传送");
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        residence.runWithAccess(() -> {
            try {
                player.teleportAsync(location).whenComplete((ok, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(Boolean.TRUE.equals(ok));
                    }
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /**
     * 传送（目标世界未加载时记录日志并保持原地）。
     *
     * <p><b>领地权限按次放行</b>：传送前临时打开相关领地的权限，传送完成后立刻恢复常态。
     * 这样准备房间（FM.zb）与场地（FM）的传送权限在绝大多数时间都是关闭的，
     * 玩家无法自行传进去；只有本插件把人送进去的那一瞬间是开的。</p>
     */
    public void teleport(Player player, Location location) {
        if (location == null) {
            plugin.getLogger().warning("传送目标世界未加载，玩家 " + player.getName() + " 未被传送");
            return;
        }
        // 权限只在“这一次传送真正完成之前”保持打开；teleportAsync 的完成通知
        // 才是关闭时机，提前关闭会让传送被领地插件拦掉
        residence.runWithAccess(() -> player.teleportAsync(location));
    }

    private Set<UUID> playersInRegion(Region region) {
        Set<UUID> result = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (region.contains(player.getLocation())) {
                result.add(player.getUniqueId());
            }
        }
        return result;
    }

    private List<Player> onlinePlayers(Set<UUID> uuids) {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : uuids) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                result.add(player);
            }
        }
        return result;
    }
}
