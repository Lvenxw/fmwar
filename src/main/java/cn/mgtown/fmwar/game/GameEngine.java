package cn.mgtown.fmwar.game;

import cn.mgtown.FMWar;
import cn.mgtown.fmwar.config.Settings;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ArenaCleaner;
import cn.mgtown.fmwar.service.ConfigService;
import cn.mgtown.fmwar.service.GameScoreboard;
import cn.mgtown.fmwar.service.ShopService;
import cn.mgtown.fmwar.service.TeamService;
import cn.mgtown.fmwar.util.Schedulers;
import cn.mgtown.fmwar.util.TimeUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import cn.mgtown.fmwar.config.Position;
import cn.mgtown.fmwar.config.Region;
import cn.mgtown.fmwar.service.PointsService;
import cn.mgtown.fmwar.service.ResidenceService;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;

/**
 * 对局引擎：唯一的状态机 + 唯一的 20tick 主循环。
 *
 * <p>整份规格的时序都挂在同一个时钟上（准备倒计时、game-duration 对局倒计时、
 * 归零后传送决赛圈并把计时重置为 duel-duration、再归零后每秒扣血），
 * 因此这里只允许存在一个循环，按阶段分派，避免多个定时器互相竞态。</p>
 *
 * <p><b>线程模型（本类最重要的一条约定）</b>：主循环由 {@link Schedulers#mainTimer} 驱动，
 * 跑在插件的 <b>权威线程</b>上——Paper 上是主线程，Folia 上是全局区域线程，两者都是单线程。
 * 对局状态（本类全部字段）<b>只允许权威线程改动</b>。为此：</p>
 *
 * <ul>
 *   <li><b>外部入口先过守卫</b>：事件回调与指令在 Folia 上跑在“玩家所属区域线程”，与权威线程
 *       并发。凡是会改状态的公开方法（右键按钮、上线/掉线/死亡、指令）入口第一句都是
 *       {@link Schedulers#guardAuthoritative}——不在权威线程就整段重排过去。Paper 上判定恒为真，
 *       一行都不多走，因此行为与改造前完全一致。</li>
 *   <li><b>会被跨线程读的集合用并发实现</b>：{@code members} / {@code queue} / {@code lastDamager} /
 *       {@code chests}。它们会被区域线程上的只读判定（{@code isMember} / {@code isParticipant}）
 *       或区块线程上的收尾动作读到。其余集合只在权威线程访问，保持裸实现。</li>
 *   <li><b>玩家自身状态派发到该玩家所属线程</b>：清背包、改游戏模式、改血量、重生……
 *       统一走 {@link Schedulers#runOwned}；Paper 上就地执行，Folia 上才真正派发。</li>
 *   <li><b>方块与区块派发到该区块所属区域线程</b>：奖励箱读写、落点地形采样同理。</li>
 * </ul>
 *
 * <p>所有调度一律走 {@link Schedulers}，绝不使用在 Folia 上会直接抛异常的
 * {@code Bukkit.getScheduler()}。</p>
 */
public final class GameEngine {

    /** 准备的游戏世界。 */
    private World world;

    /**
     * 场地清场。
     *
     * <p>刻意做成独立服务而不是留在引擎里：它有自己的生命周期（分批任务可能跨若干 tick），
     * 也有自己关心的事（区块枚举、加载/卸载还原）。塞进 {@code GameEngine} 只会让
     * 那个已经很大的类再多一块与玩法状态机无关的状态。</p>
     */
    private final ArenaCleaner cleaner;

    /**
     * MiniMessage 解析器。
     *
     * <p>它是 Adventure 自带的实现，{@code MiniMessage.miniMessage()} 返回的单例内部
     * 无状态、线程安全，静态持有即可，不必每次解析都新建。</p>
     */
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final FMWar plugin;
    private final ConfigService config;
    private final AlertService alerts;
    private final TeamService teams;
    private final GameScoreboard scoreboard;
    private final ShopService shops;
    private final PointsService points;
    private final ResidenceService residence;
    /** 唯一的调度入口：四类 Paper 调度器，跨 Paper / Folia 可用。 */
    private final Schedulers schedulers;

    /**
     * 受害者 -> 最后一名对其造成伤害的玩家：死亡时据此记击杀分。
     *
     * <p>用并发表：写入发生在“造成伤害的那一 tick 的受害者所在区域线程”，而读取与移除
     * 发生在权威线程（{@link #onPlayerDeath}）。这里<b>刻意不做入口重排</b>——
     * 记伤害必须原地、按事件顺序发生，否则“先记伤害、后判死亡”的先后关系会被打乱。
     * 相邻两次伤害事件的顺序在同一个受害者线程上是确定的，因此并发表 + 先写后读即可。</p>
     */
    private final Map<UUID, UUID> lastDamager = new ConcurrentHashMap<>();

    /**
     * 当前阶段。只由权威线程写；指令线程会读它做状态展示，故 volatile。
     */
    private volatile GamePhase phase = GamePhase.IDLE;
    /** 当前阶段的计时器，IDLE 时为 null。只由权威线程写，指令线程读，故 volatile。 */
    private volatile Timer timer;
    /**
     * 已加入队列的玩家（准备房间阶段）。
     *
     * <p>用并发表：只有权威线程会增删它，但它会被区域线程上的
     * {@link #isParticipant} 读到（骑乘拦截要在事件里同步判定）。
     * 代价是失去插入顺序——本集合的用途全是“计数 / 全员相同的提示”，顺序无关。</p>
     */
    private final Set<UUID> queue = ConcurrentHashMap.newKeySet();
    /**
     * 正在对局中的玩家（队伍 fm）。
     *
     * <p>同样用并发表：权威线程增删，区域线程读（{@link #isMember} / {@link #isParticipant}）。
     * 现存的顺序依赖只有“取第一个存活者作为胜者”，而那一步只在存活人数恰好为 1 时才发生。
     * 改动前它是 {@code LinkedHashSet}，因此仍有插入顺序；换成并发集合后顺序不再保证，
     * 若日志顺序对你的排查有影响，请以玩家名字而不是出场顺序为准。</p>
     */
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();
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
    /** 上次展示“下一颗绿宝石剩余秒数”的缓存，避免每 tick 刷动作栏。 */
    private long lastEmeraldCountdownShown = -1L;
    /** 是否已经执行过决斗圈传送。 */
    private boolean duelTeleported;
    /**
     * 本局生成的奖励箱方块坐标（结束时清理）。
     *
     * <p>用并发列表：实际放箱子/清箱子的方块读写会派发到各区块所属的区域线程，
     * 登记与清理不再只发生在权威线程上。</p>
     */
    private final List<Location> chests = new CopyOnWriteArrayList<>();
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

    /**
     * 观战者传送后的落地宽限（tick）。
     *
     * <p>观战者进入时，队伍标记是同步写好的，但传送是异步的。上一 tick 观战者还站在
     * 大厅（在场地外），下一 tick 就会被 {@link #checkArenaPresence()} 判成“观战者离场”，
     * 表现为“概率传送成功，失败时显示超出范围”。这里在传送前登记一个宽限窗口，
     * 窗口内跳过离场判定，落地后自动放行。</p>
     */
    private final Map<UUID, Long> spectatorGrace = new HashMap<>();
    private static final long SPECTATOR_GRACE_TICKS = 60L;

    /**
     * 被本插件淘汰、等待主动重生的玩家：只有这些人的重生点会被改写为大厅。
     *
     * <p>用并发集合：写入发生在权威线程（{@code eliminate}），而读取与移除发生在
     * 玩家所属线程（{@code scheduleRespawn} 的 respawn 流程与 {@code PlayerRespawnEvent}），
     * Folia 上两者不是同一个线程。</p>
     */
    private final Set<UUID> pendingRespawn = ConcurrentHashMap.newKeySet();
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

    /**
     * 上次记录玩家活动区块的 tick。
     *
     * <p>用绝对 tick 而非 elapsedTicks：后者会在决斗圈传送时被重置，
     * 那样两次采样的间隔就不再稳定。</p>
     */
    private long lastTrackTick;

    /** 最近一次分散的结果摘要，随“分散完成”一并写进日志（便于确认落点而不是“又传到场地传送点”）。 */
    private String lastDisperseReport = "未执行";
    /**
     * 调试日志开关（{@code /fmwar debug}）。
     *
     * <p>用 INFO 级别而不是 fine：这样不需要改服务端日志配置就能看到，
     * 排查“按钮没反应/倒计时不启动”这类问题可以直接开关。</p>
     *
     * <p>它只是一个开关，切换动作本身不需要重排到权威线程；用 volatile 保证
     * 指令线程与主循环之间的可见性即可，也不必牺牲 {@code toggleDebug()} 的返回值。</p>
     */
    private volatile boolean debug;

    /** 决斗圈的随机选择器（本局选定后不再变化，保证所有人进同一个圈）。 */
    private final java.util.Random duelRandom = new java.util.Random();

    private ScheduledTask tickTask;

    public GameEngine(FMWar plugin, ConfigService config, AlertService alerts,
                      TeamService teams, GameScoreboard scoreboard, ShopService shops,
                      PointsService points, ResidenceService residence, Schedulers schedulers) {
        this.plugin = plugin;
        this.config = config;
        this.alerts = alerts;
        this.teams = teams;
        this.scoreboard = scoreboard;
        this.shops = shops;
        this.points = points;
        this.residence = residence;
        this.schedulers = schedulers;
        this.cleaner = new ArenaCleaner(plugin, config, schedulers);
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
        // 指令线程入口：状态机只允许权威线程改，先重排过去
        if (!schedulers.guardAuthoritative(() -> setRemainingSeconds(seconds))) {
            return false;
        }
        if (phase != GamePhase.RUNNING || timer == null) {
            return false;
        }
        long durationTicks = Math.max(1L, seconds) * 20L;
        timer = new Timer(GamePhase.RUNNING, Bukkit.getCurrentTick(), durationTicks);
        // 重置与时间相关的游标，避免决斗圈传送闸门/加时扣血/绿宝石节奏被旧值干扰
        lastEmeraldSecond = 0L;
        lastEmeraldCountdownShown = -1L;
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
        // 主循环挂在权威线程上：Paper 主线程 / Folia 全局区域线程，都是单线程、每 tick 推进
        tickTask = schedulers.mainTimer(this::tick, 1L, 1L);
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
        // 分批清场任务必须先撤掉：停用过程中调度任务会抛异常，
        // 回调里再访问区块同样不安全
        cleaner.cancel();
        forceCleanup();
        // 强制复位领地权限：绝不能把“临时打开”的状态留在服务器上
        residence.reset();
        points.save();
    }

    /** /fmwar reload 之后重新解析世界引用并重跑区块预加载。 */
    public void onReload() {
        if (!schedulers.guardAuthoritative(this::onReload)) {
            return;
        }
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
                // 准备房右侧常驻显示“准备人数”（需求：准备房显示右侧准备人数）
                updatePrepRoomDisplay();
            }
            case PREPARING -> tickPreparing(now);
            case RUNNING -> tickRunning(now);
            case ENDING -> tickEnding(now);
        }
    }

    private void tickPreparing(long now) {
        syncPrepRoster();
        // 倒计时期间也要刷新准备人数（有人进/出准备房时右侧数字跟着变）
        updatePrepRoomDisplay();
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
        int alive = aliveCount();

        // 1) 记分板：剩余时间与存活人数都写在“记分值”上（条目名是标签）
        if (settings.scoreboard().enabled()) {
            long seconds = Math.max(0L, remainingTicks) / 20L;
            scoreboard.update(settings, (int) seconds, alive);
        }

        // 2) 剩余时间归零 → 把所有人传送到决赛圈，并把计时器重置为 duel-duration。
        //    这里就是“剩余时间等效传送倒计时”的落点：对局倒计时不再有“剩 N 秒预告”，
        //    直接走到 0 才触发传送，重置后新的一段决赛圈倒计时再归零才进入加时扣血。
        //    时长取自 timing.duel-duration，与 config.yml 保持一致。
        if (!duelTeleported && remainingTicks <= 0) {
            duelTeleported = true;
            teleportToDuel();
            long duelDurationSeconds = Math.max(1L, settings.timing().duelDurationSeconds());
            timer = new Timer(GamePhase.RUNNING, now, duelDurationSeconds * 20L);
            lastOvertimeSecond = -1L;
            lastEmeraldSecond = 0L;
            lastEmeraldCountdownShown = -1L;
            // 刷新本 tick 的游标：后面的兜底复核都基于新计时器
            remainingTicks = timer.remainingTicks(now);
            elapsedTicks = timer.elapsedTicks(now);
            alive = aliveCount();
            debug("对局倒计时归零，已传送至决斗圈；计时器重置为 " + duelDurationSeconds + " 秒");
        }

        // 3) 每 interval 秒发一颗绿宝石（用“距上次发放已经过多少秒”判定，丢 tick 也不会漏发）。
        //    决赛圈阶段不再发放绿宝石：duelTeleported 为 true 时整段跳过。
        long elapsedSeconds = elapsedTicks / 20L;
        long interval = Math.max(1L, settings.timing().emeraldIntervalSeconds());
        if (!duelTeleported && elapsedSeconds >= lastEmeraldSecond + interval) {
            lastEmeraldSecond = elapsedSeconds;
            giveEmeralds();
        }

        // 3.1) 常驻显示“下一颗绿宝石剩余秒数”：只在秒数变化时刷，避免每 tick 刷屏。
        //      决赛圈阶段不再显示：进入决赛圈那一刻已经由步骤 2 把游标重置为 -1，
        //      这里再跳过写入，屏幕上会保留 teleportToDuel() 发出的“已传送至决赛圈”动作栏。
        if (!duelTeleported) {
            long nextEmeraldIn = Math.max(0L, lastEmeraldSecond + interval - elapsedSeconds);
            if (nextEmeraldIn != lastEmeraldCountdownShown) {
                lastEmeraldCountdownShown = nextEmeraldIn;
                String seconds = Long.toString(nextEmeraldIn);
                for (Player player : onlineMembers()) {
                    alerts.sendActionBarTo(player, "emerald-countdown", Map.of("seconds", seconds));
                }
            }
        }

        // 4) 决赛圈倒计时归零：每秒扣血
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

        // 6) 每秒记录一次玩家活动过的区块，供结算时精准清场。
        //    场地矩形约 1260 个区块，而一局里玩家真正去过的只有几十个；
        //    不记录就只能扫全量，既慢又会成批加载/卸载区块，把客户端卡出观感。
        //    每秒一次足够：两次采样之间玩家挪不出一个区块。
        if (now - lastTrackTick >= 20L) {
            lastTrackTick = now;
            cleaner.track();
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

    /**
     * 切换玩家的游戏模式。
     *
     * <p>游戏模式是玩家自身状态，Folia 上必须在该玩家所属线程改。这里统一收口，
     * 免得每个调用点各自展开一次派发；Paper 上就地执行，与改造前完全一致。</p>
     */
    private void setGameMode(Player player, GameMode mode) {
        schedulers.runOwned(player, () -> player.setGameMode(mode), null);
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
            //（取消需要“人数不足”或“有人新进入”这些条件，绝不能无条件取消——\
            //  那会让倒计时刚起步就被重置）
            return;
        }
    }

    /**
     * 准备房右侧常驻显示“准备人数”。
     *
     * <p>挂在同一块 {@code fm} 侧栏里（标题仍是 {@code scoreboard.title()}，
     * 默认“附魔战争”），与对局中的“剩余时间/存活人数”共享同一块记分板，不另开新栏。</p>
     *
     * <p>房间空了（队列为空）就隐藏这一行，避免上一局的数字残留。</p>
     */
    private void updatePrepRoomDisplay() {
        if (queue.isEmpty()) {
            scoreboard.hidePrepRoom();
            return;
        }
        scoreboard.showPrepRoom(config.settings(), queue.size());
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
        // 事件回调在 Folia 上跑在该玩家所属区域线程，而 prepareClicks/timer/phase 只许权威线程改
        if (!schedulers.guardAuthoritative(() -> prepareClick(player))) {
            return;
        }
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
        if (!schedulers.guardAuthoritative(() -> leaveToHall(player))) {
            return;
        }
        UUID uuid = player.getUniqueId();
        // 只对“本局相关玩家”生效。这个按钮位于准备房间内，但“在准备房间里”不等于
        // “已入队”——若不加这一关，任何路人在准备房间点到它都会被 FMWar 传送走、
        // 记分板被摘掉，还会触发一次全局的“退出游戏队列”播报（此前就是这样）。
        if (!isParticipant(uuid)) {
            debug("玩家 " + player.getName() + " 点击了返回大厅，但不是本局相关玩家，已忽略");
            return;
        }
        queue.remove(uuid);
        // 传送本身由 teleport() 临时放行后立即恢复，这里不需要额外处理领地权限
        teleport(player, config.settings().location("hall-spawn"));
        teams.leaveAll(uuid);
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
        // 入队要改 queue / timer / phase，全是权威线程的状态；事件回调可能不在那个线程上。
        // 重排后本次返回 false——调用方（右键监听）不使用返回值，只是“没触发动作”。
        if (!schedulers.guardAuthoritative(() -> tryJoinQueue(player))) {
            return false;
        }
        // 先判阶段：对局已经开打（含正在结算的那一 tick）时直接拒绝，
        // 避免下面为“准备倒计时期间的加入”取消倒计时这件事被误触发。
        if (!canJoinNow(player)) {
            return false;
        }

        // 需求：入队前必须清空背包（含光标上的物品）。
        // 检查刻意放在 cancelCountdown 之前：一次无效点击不该把正在跑的倒计时取消掉，
        // 也不该让玩家带着上一局的装备进入准备房间。
        //
        // 而“读玩家背包”改的是玩家自身状态，在 Folia 上必须落在该玩家所属的区域线程。
        // 已拥有（Paper 上恒成立）就地读，与改造前逐字一致；否则先派发过去读，
        // 读完再回权威线程继续——因为下面的 finishJoin 要改 queue / timer / phase。
        if (schedulers.owns(player)) {
            if (hasAnyItem(player)) {
                rejectJoinDirtyInventory(player);
                return false;
            }
            finishJoin(player);
            return true;
        }
        schedulers.runOwned(player, () -> {
            boolean dirty = hasAnyItem(player);
            schedulers.onMain(() -> {
                // 派发期间玩家可能已离线，阶段也可能已经变化，都要重新判一次
                if (!player.isOnline() || !canJoinNow(player)) {
                    return;
                }
                if (dirty) {
                    rejectJoinDirtyInventory(player);
                    return;
                }
                finishJoin(player);
            });
        }, null);
        return true;
    }

    /** 入队前置：阶段必须允许加入（IDLE / PREPARING）。返回 false 时已给出提示。 */
    private boolean canJoinNow(Player player) {
        if (phase != GamePhase.IDLE && phase != GamePhase.PREPARING) {
            alerts.sendTo(player, "game-already-running", Map.of());
            return false;
        }
        return true;
    }

    /** 背包非空时的拒绝路径：只打日志与提示，不改任何对局状态。 */
    private void rejectJoinDirtyInventory(Player player) {
        debug("玩家 " + player.getName() + " 入队被拒：背包未清空");
        alerts.sendTo(player, "queue-inventory-not-empty", Map.of());
    }

    /**
     * 真正入队：取消在跑的准备倒计时、登记队列、传送并挂记分板。
     *
     * <p><b>必须在权威线程上调用</b>——它会改 {@code queue} / {@code timer} / {@code phase}。
     * Paper 上它仍是在点击事件里同步跑完的，与改造前一致。</p>
     */
    private void finishJoin(Player player) {
        // 需求：准备倒计时期间也允许加入队列——新玩家进入意味着“有人进入准备房间”，
        // 此时应当取消倒计时并重置准备进度，而不是把大厅里的人挡在门外。
        if (phase == GamePhase.PREPARING) {
            cancelCountdown("prepare-cancel-joined");
        }
        UUID uuid = player.getUniqueId();
        if (queue.contains(uuid)) {
            alerts.sendTo(player, "queue-already-joined", Map.of());
            return;
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
                .whenComplete((ok, error) -> {
                    if (error == null && Boolean.TRUE.equals(ok)) {
                        return;
                    }
                    // 这个回调在 Folia 上跑在“玩家所属区域线程”，而 queue 的状态机
                    // 只允许权威线程改，因此先回权威线程再做共享状态的改动
                    schedulers.onMain(() -> {
                        // 玩家可能在等待期间自己又退了队/下线，只有仍在队列中才回滚
                        if (!queue.remove(uuid)) {
                            return;
                        }
                        Player online = Bukkit.getPlayer(uuid);
                        String name = online == null ? player.getName() : online.getName();
                        debug("玩家 " + name + " 入队传送失败（被领地/插件拦截或取消），已从队列移除");
                        // 记分板与私发提示属于“玩家自身状态”，交回该玩家所属线程
                        if (online != null) {
                            schedulers.onEntity(online, () -> {
                                scoreboard.detach(online);
                                alerts.sendTo(online, "queue-teleport-failed", Map.of());
                            }, null);
                        }
                        alerts.broadcast("queue-leave", Map.of("player", name));
                    });
                });
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

    /**
     * 玩家是否正在参与本插件玩法：对局中 / 已入队等候 / 正在观战。
     *
     * <p>本插件对玩家施加的一切<b>有副作用的操作</b>（清背包、改游戏模式、传送、取消动作、
     * 改写重生点……）都必须先过这一关——服务器上与本局无关的玩家，FMWar 一根手指都不该碰。</p>
     *
     * <p>它<b>不等同</b>于 {@link #isMember}：排队中的玩家在开局前还不是成员，观战者永远
     * 不是成员，但他们同样属于“本局相关玩家”。反过来，准备房间里的路人、提示接收范围内
     * 路过的玩家都不算。</p>
     */
    public boolean isParticipant(UUID uuid) {
        return members.contains(uuid) || queue.contains(uuid) || teams.inSpectatorTeam(uuid);
    }

    /** 对局名单人数（包含暂时离线的成员）。 */
    public int memberCount() {
        return members.size();
    }

    /**
     * 死亡淘汰时是否保留玩家已有经验（{@code start.keep-experience}）。
     *
     * <p>由 {@code PlayerStateListener} 在死亡事件里读取：清背包只管物品，
     * 经验值是否随死亡没收是另一件事，用一个显式开关表达，避免"清背包"的语义继续膨胀。</p>
     */
    public boolean keepExperienceOnDeath() {
        return config.settings().start().keepExperience();
    }

    /**
     * 从队列里把还在准备房间的玩家直接拉进对局并立即开局（/fmwar start）。
     *
     * @return 实际参战人数
     */
    public int prepareFromQueue() {
        // 指令入口：开局会改 members/phase/timer，必须先回到权威线程
        if (!schedulers.guardAuthoritative(this::prepareFromQueue)) {
            return 0;
        }
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
        // 准备阶段结束：把“准备人数”从同一块侧栏里收起，
        // 让 update() 写入的“剩余时间/存活人数”占据这块侧栏
        scoreboard.hidePrepRoom();
        // 领地权限不在这里常驻打开：开局分散、传决斗圈、观战进场各自走 teleport()，
        // 由它在每次传送期间临时放行、传送完成立刻恢复
        phase = GamePhase.RUNNING;
        timer = new Timer(GamePhase.RUNNING, Bukkit.getCurrentTick(), settings.timing().gameDurationSeconds() * 20L);
        members.clear();
        duelTeleported = false;
        ended = false;
        lastOvertimeSecond = -1L;
        lastEmeraldSecond = 0L;
        lastEmeraldCountdownShown = -1L;
        lastCountdownSecond = -1L;
        prepareClicks = 0;
        // 新一局从空白追踪名单开始：否则会把上一局残留的区块也算进来，
        // 结算时白扫一批
        cleaner.resetTracking();
        lastTrackTick = Bukkit.getCurrentTick();

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
        // 规则说明紧跟“游戏开始”，同样在传送之前发出：广播是同步的、分散传送是异步的，
        // 因此玩家一定是在原地看完规则之后才被传送，聊天栏顺序不会错乱
        broadcastGameRules();
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
        // 分散结果由 disperseOne 的收尾单独打一行：Folia 上分散可能跨若干 tick 才完成，
        // 不能再像以前那样把结果拼进这条日志里（那时会打到“进行中”的旧值）
        plugin.getLogger().info("对局开始：参战 " + participants.size() + " 人");
    }

    /**
     * 开局规则说明：紧跟 {@code game-start} 之后广播。
     *
     * <p>{@code messages.game-rule} 用 YAML 的 {@code |-} 块写成多行，渲染结果里带
     * 换行符。整条作为一条聊天消息发出，客户端会按 {@code \n} 逐行渲染，因此这里
     * 不需要在代码里拆分。</p>
     *
     * <p>{@code {interval}} 取自 {@code timing.emerald-interval}；
     * {@code {duel}} 取自 {@code timing.duel-duration}，规则文案与两处实际节奏始终一致。</p>
     */
    private void broadcastGameRules() {
        long interval = Math.max(1L, config.settings().timing().emeraldIntervalSeconds());
        long duelSeconds = Math.max(1L, config.settings().timing().duelDurationSeconds());
        alerts.broadcast("game-rule", Map.of(
                "interval", Long.toString(interval),
                "duel", Long.toString(duelSeconds)));
    }

    /**
     * 开局前：切回生存模式、清空背包与药水效果（需求：游戏内物品不能带出场地）。
     *
     * <p>刻意**不做背包备份**：备份只存在于内存里，崩服会连同备份一起丢失，
     * 反而让玩家物品更不安全。需要保护玩家物品请用专门的背包备份插件。</p>
     */
    private void applyStartState(Player player) {
        Settings settings = config.settings();
        // 整段都是“玩家自身状态”（游戏模式、背包、药水、血量、饥饿）：派发到该玩家所属线程。
        // Paper 上就地执行，时序与改造前一致；Folia 上才真正落到该玩家的区域线程。
        schedulers.runOwned(player, () -> {
            // 需求：开局把场内玩家（队伍 fm）改成生存模式
            player.setGameMode(GameMode.SURVIVAL);
            if (settings.start().clearInventory()) {
                // 只清物品：经验值（等级/经验条/总经验）不在这里，也不该在这里被顺手清掉
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
            Settings.FishingRod rod = settings.start().fishingRod();
            if (rod.usable()) {
                ItemStack item = buildRod(rod);
                if (item != null) {
                    player.getInventory().addItem(item);
                }
            }
            player.updateInventory();
        }, null);
    }

    /** 按配置构造钓竿；材质无法识别时返回 null（调用方跳过发放）。 */
    private ItemStack buildRod(Settings.FishingRod rod) {
        Material material = Material.matchMaterial(rod.material().toUpperCase(Locale.ROOT));
        if (material == null) {
            plugin.getLogger().warning("start.fishing-rod.material 无法识别: " + rod.material());
            return null;
        }
        ItemStack stack = new ItemStack(material);

        // 附魔与显示属性都写在同一个 ItemMeta 上，最后一次 setItemMeta 写回。
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        for (Map.Entry<String, Integer> entry : rod.enchantments().entrySet()) {
            Enchantment enchantment = resolveEnchantment(entry.getKey());
            if (enchantment == null) {
                continue;
            }
            // 第三个参数 true = 忽略原版等级上限，等价于原来的 addUnsafeEnchantment
            meta.addEnchant(enchantment, entry.getValue(), true);
        }

        applyDisplay(meta, rod);

        stack.setItemMeta(meta);
        return stack;
    }

    /** 解析附魔键；不认识的键记一条日志并返回 null。 */
    private Enchantment resolveEnchantment(String keyText) {
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
        }
        return enchantment;
    }

    /**
     * 应用“不可破坏 / 自定义名称 / Lore”，全部走 Adventure 组件。
     *
     * <p>名称与每一行 Lore 都显式关掉斜体：Paper 在渲染 {@code ItemMeta} 的自定义
     * 名称与 Lore 时，会给没有显式设置该装饰的组件补上 {@code ITALIC}，不关掉的话
     * 文字会全部歪着显示。MiniMessage 自己不会补斜体，补的是 Paper 的渲染层，
     * 所以这里必须显式设置，不能依赖“标签里没写 italic 就不斜”。</p>
     */
    private void applyDisplay(ItemMeta meta, Settings.FishingRod rod) {
        if (rod.unbreakable()) {
            meta.setUnbreakable(true);
            if (rod.hideUnbreakable()) {
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            }
        }

        String name = rod.name();
        if (name != null && !name.isBlank()) {
            meta.displayName(parseMini(name));
        }

        List<String> loreLines = rod.lore();
        if (loreLines != null && !loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreLines.size());
            for (String line : loreLines) {
                lore.add(parseMini(line));
            }
            meta.lore(lore);
        }
    }

    /**
     * 解析 MiniMessage 文本为聊天组件，并关掉斜体。
     *
     * <p>解析失败时退回纯文本：标签写错（如 {@code <glod>}）在 MiniMessage 里会抛
     * {@code ParsingException}，不能让一条文案把整个开局流程打断。退回纯文本时
     * 同样要关掉斜体，否则文案会以斜体显示，反而比标签写错更难排查。</p>
     */
    private Component parseMini(String text) {
        Component component;
        try {
            component = MINI.deserialize(text);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("物品文案解析失败，已按纯文本处理: " + text);
            component = Component.text(text);
        }
        return component.decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------
    // 开局分散与决斗圈
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 落点采样（按“列”派发到区块区域线程）
    // ------------------------------------------------------------------

    /** 在单个 x/z 列上求一个落点。实现由调用方给（最高可落脚面 / 锁定高度）。 */
    @FunctionalInterface
    private interface ColumnSampler {
        Location sampleAt(World world, double x, double z);
    }

    /**
     * 在单个 x/z 列上求一个落点，并把结果交回权威线程。
     *
     * <p><b>这一层是本插件适配 Folia 的关键取舍。</b>{@link SafeLocation} 的
     * {@code find} / {@code exact} 只读写同一个 x/z 列上的方块，而一列必然完整落在
     * <b>同一个区块</b>里，于是它可以被整体派发到“拥有那个区块的区域线程”上执行。
     * 反过来，分散半径 100 格的正方形、乃至整个决斗圈，都会横跨多个区块、多个区域——
     * <b>没有任何一个线程能合法地把它们一次读完</b>，改造前那种“一个同步循环扫完
     * 整片地形”的写法在 Folia 上是不成立的。</p>
     *
     * <p>因此采样被拆成“每个候选列一次派发 + 结果回到权威线程”，由
     * {@link #sampleSquareAsync} / {@link #sampleRegionAsync} 负责把候选列串起来。
     * Paper 上当前线程本就是该区块的归属线程，采样就地同步完成，整条链仍在同一 tick
     * 内跑完，与改造前等价。</p>
     *
     * @param callback 结果回调，<b>保证在权威线程上执行</b>；找不到落点时传 null
     */
    private void sampleColumn(World world, double x, double z, ColumnSampler sampler,
                              Consumer<Location> callback) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (schedulers.owns(world, chunkX, chunkZ)) {
            // Paper（或已经在该区块的线程上）：就地采样，当前 tick 就出结果
            callback.accept(sampler.sampleAt(world, x, z));
            return;
        }
        // 从未生成过的区块里不可能有落点，也不该为了求落点去生成地形
        if (!world.isChunkGenerated(chunkX, chunkZ)) {
            callback.accept(null);
            return;
        }
        world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) ->
                schedulers.onRegion(world, chunkX, chunkZ, () -> {
                    Location found = (error != null || chunk == null || !chunk.isLoaded())
                            ? null
                            : sampler.sampleAt(world, x, z);
                    // 采样结果一律回到权威线程再交给调用方：调用方要在那里改“已选落点”列表
                    schedulers.onMain(() -> callback.accept(found));
                }));
    }

    /**
     * 候选落点是否可用：非空、落在指定区域内（带 y 的复核），且与已选落点保持最小间距。
     *
     * <p>“采到之后再复核一次区域”是必要的：地形落差可能让实际落脚点偏出边界。
     * {@code within} 为 null 时不检查区域。只允许在权威线程调用（会读 {@code taken}）。</p>
     */
    private boolean acceptLanding(Location candidate, Region within, List<Location> taken, double minSpacing) {
        if (candidate == null) {
            return false;
        }
        if (within != null && !within.contains(candidate)) {
            return false;
        }
        for (Location other : taken) {
            if (other.getWorld() != candidate.getWorld()) {
                continue;
            }
            double dx = other.getX() - candidate.getX();
            double dz = other.getZ() - candidate.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < minSpacing) {
                return false;
            }
        }
        return true;
    }

    /**
     * 正方形采样（{@code SafeLocation.sampleSquare} 的异步版）。
     *
     * <p>判定与原实现逐条对应：候选点先按<b>水平</b>判定落在 {@code within} 内，
     * 采到落点后再用带 y 的 {@code contains} 复核一次，最后检查最小间距。</p>
     *
     * <p>同一次调用内，只要候选列就落在当前区块上就继续<b>循环</b>（Paper 上即整段同步）；
     * 一旦某个候选列属于别的区域，就派发出去、并在它失败时带着剩余次数重新进入本方法。</p>
     *
     * @param attemptsLeft 还剩几次尝试机会
     */
    private void sampleSquareAsync(World world, Settings.Disperse disperse, Region within,
                                   double referenceY, List<Location> taken, int attemptsLeft,
                                   Consumer<Location> callback) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < attemptsLeft; attempt++) {
            double x = disperse.centerX() + random.nextDouble(-disperse.radius(), disperse.radius());
            double z = disperse.centerZ() + random.nextDouble(-disperse.radius(), disperse.radius());
            // 必须用只判水平的 containsXZ：候选点的 y 此刻未知，用带 y 的判定传占位值
            // （例如 0）会把所有候选点判成区域外，分散会 100% 失败
            if (within != null && !within.containsXZ(world.getName(), x, z)) {
                continue;
            }
            int chunkX = (int) Math.floor(x) >> 4;
            int chunkZ = (int) Math.floor(z) >> 4;
            ColumnSampler sampler = (w, bx, bz) -> SafeLocation.find(w, bx, bz, referenceY);
            if (schedulers.owns(world, chunkX, chunkZ)) {
                Location candidate = sampler.sampleAt(world, x, z);
                if (acceptLanding(candidate, within, taken, disperse.minSpacing())) {
                    callback.accept(candidate);
                    return;
                }
                continue;
            }
            int remaining = attemptsLeft - attempt - 1;
            sampleColumn(world, x, z, sampler, candidate -> {
                if (acceptLanding(candidate, within, taken, disperse.minSpacing())) {
                    callback.accept(candidate);
                    return;
                }
                sampleSquareAsync(world, disperse, within, referenceY, taken, remaining, callback);
            });
            return;
        }
        callback.accept(null);
    }

    /**
     * 决斗圈采样（{@code SafeLocation.sampleRegion} / {@code sampleRegionExactY} 的异步版）。
     *
     * <p>取点方式与原实现一致：在区域的<b>整个水平范围</b>内随机取点，而不是
     * “中心 ± 半径”的正方形——决斗圈多为长方形，正方形采样会有死角且容易越界。</p>
     *
     * @param exactY    true 表示锁定高度（完全不看地形），此时 {@code exactOrReferenceY} 是落点脚部高度
     * @param maxY      非锁定模式下的落点高度上限（&gt; 0 生效）
     * @param allowWater 是否允许落在水面上
     */
    private void sampleRegionAsync(World world, Region region, boolean exactY, double exactOrReferenceY,
                                   double maxY, boolean allowWater, double minSpacing, int maxAttempts,
                                   List<Location> taken, Consumer<Location> callback) {
        if (world == null || region == null) {
            callback.accept(null);
            return;
        }
        // 区域内取点要含边界：+1 让 maxX/maxZ 那一格也能被抽到
        double widthX = Math.max(1.0, region.maxX() - region.minX() + 1);
        double widthZ = Math.max(1.0, region.maxZ() - region.minZ() + 1);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double x = region.minX() + random.nextDouble(widthX);
            double z = region.minZ() + random.nextDouble(widthZ);
            if (!region.containsXZ(world.getName(), x, z)) {
                continue;
            }
            int chunkX = (int) Math.floor(x) >> 4;
            int chunkZ = (int) Math.floor(z) >> 4;
            ColumnSampler sampler = exactY
                    ? (w, bx, bz) -> SafeLocation.exact(w, bx, exactOrReferenceY, bz)
                    : (w, bx, bz) -> SafeLocation.find(w, bx, bz, exactOrReferenceY, maxY, allowWater);
            if (schedulers.owns(world, chunkX, chunkZ)) {
                Location candidate = sampler.sampleAt(world, x, z);
                if (acceptLanding(candidate, region, taken, minSpacing)) {
                    callback.accept(candidate);
                    return;
                }
                continue;
            }
            int remaining = maxAttempts - attempt - 1;
            sampleColumn(world, x, z, sampler, candidate -> {
                if (acceptLanding(candidate, region, taken, minSpacing)) {
                    callback.accept(candidate);
                    return;
                }
                sampleRegionAsync(world, region, exactY, exactOrReferenceY, maxY, allowWater,
                        minSpacing, remaining, taken, callback);
            });
            return;
        }
        callback.accept(null);
    }

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
                teleport(player, levelView(player, settings.location("arena-spawn")));
            }
            return;
        }
        // center 的 y 作为“最高可落脚点”的搜索参考高度；配置里省略 y 时传 0，
        // 此时 SafeLocation 会自行从世界最高点向下找，行为与之前一致
        double referenceY = disperse.centerY();
        List<Location> taken = new ArrayList<>();
        AtomicInteger fallback = new AtomicInteger();
        disperseOne(participants, 0, taken, fallback, settings, disperse, arena, referenceY);
    }

    /**
     * 逐个玩家求分散落点。
     *
     * <p><b>必须串行</b>：最小间距是相对“已选落点”判定的，并发求点会让两名玩家互相
     * 看不到对方的落点，间距约束直接失效。因此这里是“求完一个再求下一个”的链。</p>
     *
     * <p>Paper 上每个 {@link #sampleColumn} 都就地完成，整条链在同一 tick 内跑完，
     * 与改造前的同步循环等价；Folia 上跨区域的候选列会让链跨若干 tick 继续。</p>
     */
    private void disperseOne(List<Player> participants, int index, List<Location> taken,
                             AtomicInteger fallback, Settings settings, Settings.Disperse disperse,
                             Region arena, double referenceY) {
        if (index >= participants.size()) {
            lastDisperseReport = taken.size() + "/" + participants.size() + " 人成功分散"
                    + "（中心 " + (long) disperse.centerX() + "," + (long) disperse.centerZ()
                    + " 正方形半边长 " + (long) disperse.radius()
                    + "，最小间距 " + (long) disperse.minSpacing()
                    + (fallback.get() > 0 ? "，" + fallback.get() + " 人走兜底点" : "") + "）";
            plugin.getLogger().info("分散完成：" + lastDisperseReport);
            return;
        }
        final Player player = participants.get(index);
        sampleSquareAsync(world, disperse, arena, referenceY, taken, disperse.maxAttempts(), sample -> {
            if (sample != null) {
                taken.add(sample);
                teleport(player, levelView(player, sample));
                // 落点由 SafeLocation 采样得出，本身就保证是“该列最高可落脚面”；
                // 这里只记录最终落点。改造前还会额外读一次该列最高方块 y 做交叉验证，
                // 但那是一次跨区块读，在 Folia 上正是不能做的事，故去掉。
                debug("分散落点 " + player.getName() + " -> "
                        + sample.getBlockX() + "," + sample.getBlockY() + "," + sample.getBlockZ());
            } else {
                fallback.incrementAndGet();
                plugin.getLogger().warning("玩家 " + player.getName() + " 未能找到分散落点（槽位 " + index
                        + "），已退回场地传送点。请检查 regions.arena 是否覆盖分散范围、"
                        + "以及该范围内是否有可站立地面");
                teleport(player, levelView(player, settings.location("arena-spawn")));
            }
            disperseOne(participants, index + 1, taken, fallback, settings, disperse, arena, referenceY);
        });
    }

    /**
     * 把存活玩家与观战者送进决斗圈。
     *
     * <p>需求：到点时**随机选一个**决斗圈（duel-1 / duel-2），选定后本局所有存活玩家
     * 都进**同一个**圈；落点必须在该圈区域内、且不高于配置的高度上限
     * （室内场地的封顶玻璃会挡住“最高可落脚面”，必须靠上限把人压回场地内部）。</p>
     *
     * <p>观战者一并送进同一个圈，直接落在圈中心的安全位（{@code fallback}）：
     * 旁观模式没有碰撞体积，多人重叠没有影响，集中在中心反而能看清对决。</p>
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

        // 名单在权威线程上一次性取好：后面的异步链只处理这份快照，不再反复遍历队伍
        List<Player> membersToPlace = new ArrayList<>(onlineMembers());
        List<Player> spectatorsToPlace = new ArrayList<>(onlineSpectators());
        List<Location> taken = new ArrayList<>();
        AtomicInteger fallbackCount = new AtomicInteger();

        // 兜底落点本身也要读地形（非锁定模式要搜最高可落脚面，锁定模式要判细雪），
        // 因此它同样得走按列派发；拿到之后再开始逐个安排玩家。
        ColumnSampler fallbackSampler = exactY
                ? (w, bx, bz) -> SafeLocation.exact(w, bx, arena.centerY(), bz)
                : (w, bx, bz) -> SafeLocation.find(w, bx, bz, arena.centerY(), effectiveMaxY,
                        arena.allowWater());
        sampleColumn(target, arena.centerX(), arena.centerZ(), fallbackSampler, sampled -> {
            Location fallback = sampled;
            if (fallback == null) {
                // 中心点也采不到（例如整片是水/岩浆）：退回纯坐标兜底，保证流程不中断
                fallback = new Location(target, arena.centerX() + 0.5,
                        Math.max(duelRegion.minY() + 1, arena.centerY()), arena.centerZ() + 0.5);
            }
            duelTeleportOne(target, arena, duelRegion, exactY, effectiveMaxY, fallback, taken,
                    membersToPlace, spectatorsToPlace, 0, fallbackCount);
        });
    }

    /**
     * 逐个把存活玩家送进本局选定的决斗圈。
     *
     * <p>与开局分散同一条串行链，原因也相同：落点最小间距是相对“已选落点”判定的。</p>
     *
     * <p>Paper 上整条链在同一 tick 内跑完，与改造前的同步循环等价。</p>
     */
    private void duelTeleportOne(World target, Settings.DuelArena arena, Region duelRegion,
                                 boolean exactY, double maxY, Location fallback, List<Location> taken,
                                 List<Player> members, List<Player> spectators, int index,
                                 AtomicInteger fallbackCount) {
        if (index >= members.size()) {
            teleportSpectatorsToDuel(fallback, spectators);
            if (fallbackCount.get() > 0) {
                plugin.getLogger().warning("决斗圈：" + fallbackCount.get() + " 名玩家未能采样到落点，"
                        + "已使用兜底点（" + fallback.getBlockX() + "," + fallback.getBlockY() + ","
                        + fallback.getBlockZ() + "）。请检查 regions." + arena.region()
                        + (exactY ? " 与 center 的 y" : " 与 max-y"));
            }
            return;
        }
        Player player = members.get(index);
        // 在**该区域的整个范围**内取点，而不是“中心 ± 半径”的正方形：
        // 决斗圈区域多为长方形，正方形采样会有死角且容易越界。
        // 锁定高度模式下完全不看地形，直接把高度钉在配置值上。
        sampleRegionAsync(target, duelRegion, exactY, arena.centerY(), maxY, arena.allowWater(),
                arena.minSpacing(), arena.maxAttempts(), taken, sample -> {
                    if (sample != null) {
                        taken.add(sample);
                        teleport(player, levelView(player, sample));
                    } else {
                        fallbackCount.incrementAndGet();
                        teleport(player, levelView(player, fallback));
                    }
                    alerts.sendActionBarTo(player, "duel-teleport", Map.of());
                    duelTeleportOne(target, arena, duelRegion, exactY, maxY, fallback, taken,
                            members, spectators, index + 1, fallbackCount);
                });
    }

    /**
     * 观战者一并送进本局选定的那个决斗圈，直接落在圈中心的安全位。
     *
     * <p>不参与落点采样——旁观模式没有碰撞体积，多人重叠没有影响，集中在中心反而能看清对决。
     * 决斗圈区域本就落在 arena 之内，因此这里不会触发 {@code checkArenaPresence} 里的
     * “观战者离场”，但仍登记宽限窗口，避免异步传送落地前的几 tick 被误判。</p>
     */
    private void teleportSpectatorsToDuel(Location fallback, List<Player> spectators) {
        int count = 0;
        for (Player spectator : spectators) {
            if (!spectator.isOnline()) {
                continue;
            }
            spectatorGrace.put(spectator.getUniqueId(),
                    Bukkit.getCurrentTick() + SPECTATOR_GRACE_TICKS);
            teleport(spectator, levelView(spectator, fallback));
            alerts.sendActionBarTo(spectator, "duel-teleport", Map.of());
            count++;
        }
        if (count > 0) {
            debug("决斗圈：已把 " + count + " 名观战者传送到圈中心点（"
                    + fallback.getBlockX() + "," + fallback.getBlockY() + ","
                    + fallback.getBlockZ() + "）");
        }
    }

    /**
     * 把落点调整为“平视”：保留玩家当前的水平朝向（yaw），俯仰角（pitch）归零。
     *
     * <p>{@link SafeLocation} 采样出来的 Location 只保证坐标可用，yaw/pitch 是 0 或
     * 沿用调用方给的参考值；直接传送会让玩家的视线被重置（常见是突然朝正南、略微俯视）。
     * 这里统一在传送前改写，让玩家落地后立刻是水平视角。</p>
     */
    private Location levelView(Player player, Location target) {
        if (target == null) {
            return null;
        }
        Location leveled = target.clone();
        leveled.setYaw(player.getLocation().getYaw());
        leveled.setPitch(0F);
        return leveled;
    }

    /** {@link #levelView(Player, Location)} 的 Position 版本；target 为 null 时原样返回。 */
    private Location levelView(Player player, Position target) {
        return target == null ? null : levelView(player, target.toLocation());
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
        List<Position> positions = loot.chestLocations();
        int total = positions.size();
        // 计数器用原子类型：实际放箱子的方块读写可能被派发到各区块所属的区域线程
        AtomicInteger placed = new AtomicInteger();
        AtomicInteger totalItems = new AtomicInteger();
        // 每个坐标都必须在处理完（含跳过）后归还一份，归零时打汇总日志。
        // Paper 上全部就地执行，因此这段日志出现的时机与改造前完全一致。
        AtomicInteger remaining = new AtomicInteger(total);
        int deferred = 0;
        for (Position position : positions) {
            Location location = position.toLocation();
            if (location == null || location.getWorld() == null) {
                plugin.getLogger().warning("奖励箱坐标世界未加载: " + position.world());
                reportChestsDone(remaining, placed, totalItems, total);
                continue;
            }
            World target = location.getWorld();
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            if (target.isChunkLoaded(chunkX, chunkZ)) {
                // 已加载：就地放。Paper 上就在当前 tick，行为与改造前逐字一致。
                placeChest(position, placed, totalItems);
                reportChestsDone(remaining, placed, totalItems, total);
                continue;
            }
            // 未加载：先异步加载区块，再回到该区块所属的区域线程里放。
            // 改造前这里是同步的 loadChunk + 方块读写，在 Folia 上全局线程做不了这件事。
            // generate=true 与原先 loadChunk 的语义一致：坐标本来就该有区块。
            deferred++;
            target.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) ->
                    schedulers.onMain(() -> {
                        if (error != null || chunk == null) {
                            plugin.getLogger().warning("奖励箱坐标 " + position.x() + "," + position.y()
                                    + "," + position.z() + " 所在区块加载失败，已跳过");
                        } else {
                            schedulers.runOwned(target, chunkX, chunkZ,
                                    () -> placeChest(position, placed, totalItems));
                        }
                        reportChestsDone(remaining, placed, totalItems, total);
                    }));
        }
        if (deferred > 0) {
            debug("奖励箱：" + deferred + " 个坐标所在区块未加载，已转为异步加载后生成");
        }
    }

    /** 一个奖励箱处理完毕（含跳过）时归还额度；全部处理完再打汇总日志。 */
    private void reportChestsDone(AtomicInteger remaining, AtomicInteger placed,
                                  AtomicInteger totalItems, int total) {
        if (remaining.decrementAndGet() != 0) {
            return;
        }
        plugin.getLogger().info("奖励箱：已生成 " + placed.get() + "/" + total
                + " 个，共放入 " + totalItems.get() + " 件"
                + (config.settings().start().lootBooksOnly() ? "附魔书" : "物品"));
    }

    /**
     * 在配置坐标放置一个奖励箱并按 {@code loot-groups} 随机取一行填进去。
     *
     * <p><b>必须在拥有该区块的线程上调用</b>：它会读方块、必要时新建箱子并写方块实体，
     * 全是区块内的世界操作。带完整诊断日志——箱子是否放上、用了哪一行、解析出几件物品、
     * 失败在哪一步，都写进控制台。</p>
     */
    private void placeChest(Position position, AtomicInteger placed, AtomicInteger totalItems) {
        Location location = position.toLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        World target = location.getWorld();
        Settings.Loot loot = config.settings().loot();
        boolean booksOnly = config.settings().start().lootBooksOnly();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!target.isChunkLoaded(chunkX, chunkZ)) {
            plugin.getLogger().warning("奖励箱坐标 " + position.x() + "," + position.y() + "," + position.z()
                    + " 所在区块未加载，已跳过");
            return;
        }

        Block block = location.getBlock();
        // 只覆盖空气或可替换方块：避免把别人放的建筑直接抹掉，同时保留已有的箱子（只填内容）
        if (block.getType() != Material.CHEST && !block.isReplaceable()) {
            plugin.getLogger().warning("奖励箱坐标 " + position.x() + "," + position.y() + "," + position.z()
                    + " 被 " + block.getType() + " 占用且不可替换，已跳过");
            return;
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
            return;
        }
        Inventory inventory = chest.getBlockInventory();

        // 先清空再写入，而不是只覆盖前 N 格。
        // loot-groups 的一行通常只有 1~2 件，箱子却有 27 格：只靠 setItem 覆盖，
        // 上一局没被玩家取走的物品会原样留在后面的槽位里，看起来就是"箱子里还有
        // 上一局的物品"。同样地，只要上一局的清箱因任何原因没生效
        // （区块未加载、方块被替换、进程异常中止），这里也一定能兜住。
        inventory.clear();

        List<String> group = loot.lootGroups().get(ThreadLocalRandom.current().nextInt(loot.lootGroups().size()));
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
        placed.incrementAndGet();
        totalItems.addAndGet(items.size());
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

    /** 奖励箱坐标列表（供指令读回校验）。 */
    public List<Location> chestLocations() {
        return List.copyOf(chests);
    }

    /**
     * 清掉本局生成的奖励箱：先清空内容，再删除方块本身。
     *
     * <p>只 {@code setType(AIR)} 也能连内容一起丢掉，显式 clear 一层是为了覆盖
     * 「坐标上已经不是箱子」（被玩家换成别的容器或挖走又放回）这类边角情形，
     * 同时让收尾日志能反映真实清理结果，而不是静默地什么也没做。</p>
     *
     * <p><b>必须在 {@code chests} 还是满的时候调用。</b>{@link #resetRuntimeState()}
     * 刻意不再清空这个列表——它先于本方法执行，一旦它清了，这里就无事可做。</p>
     *
     * <p>方块读写会逐个派发到箱子所在区块的区域线程（Paper 上就地执行）。汇总日志挂在
     * “全部派发完毕”上，因此 Paper 上的输出与改造前逐字一致。</p>
     */
    private void clearChests() {
        // 先取快照再清空清单：清单本身是“待办”，取到就立刻消费掉，避免收尾路径重复进入
        List<Location> pending = new ArrayList<>(chests);
        chests.clear();
        if (pending.isEmpty()) {
            return;
        }
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger remaining = new AtomicInteger(pending.size());
        for (Location location : pending) {
            schedulers.runOwned(location, () -> {
                if (removeChestAt(location)) {
                    removed.incrementAndGet();
                }
                if (remaining.decrementAndGet() == 0) {
                    plugin.getLogger().info("奖励箱：已清空并移除 " + removed.get() + "/" + pending.size() + " 个");
                }
            });
        }
    }

    /**
     * 就地清掉一个奖励箱：先清内容再删方块。
     *
     * <p><b>必须在拥有该区块的线程上调用</b>（与 {@link #placeChest} 同一约束）。</p>
     *
     * @return 是否确实移除了一个箱子；坐标上已经不是箱子时返回 false，且不计入清理数
     */
    private boolean removeChestAt(Location location) {
        Block block = location.getBlock();
        if (block.getType() != Material.CHEST) {
            return false;
        }
        // 用活体方块实体（useSnapshot=false）而不是快照：与 placeChest() 保持一致，
        // 改动直接落在 chunk 里的方块实体上，不依赖 update() 写回
        if (block.getState(false) instanceof Container container) {
            container.getInventory().clear();
        }
        block.setType(Material.AIR, false);
        return true;
    }

    // ------------------------------------------------------------------
    // 对局中的周期事件
    // ------------------------------------------------------------------

    private void giveEmeralds() {
        int amount = Math.max(1, config.settings().emerald().amount());
        ItemStack emerald = new ItemStack(Material.EMERALD, amount);
        for (Player player : onlineMembers()) {
            // 往背包里塞东西是玩家自身状态：逐个派发到该玩家所属线程
            schedulers.runOwned(player, () -> {
                player.getInventory().addItem(emerald.clone());
                player.updateInventory();
            }, null);
        }
    }

    private void applyOvertimeDamage() {
        double damage = config.settings().timing().overtimeDamage();
        for (Player player : onlineMembers()) {
            // 扣血是玩家自身状态：逐个派发到该玩家所属线程
            schedulers.runOwned(player, () -> player.damage(damage), null);
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
        long now = Bukkit.getCurrentTick();
        for (Player player : onlineSpectators()) {
            // 观战者传送是异步的：落地前的几 tick 内玩家读到的仍是旧坐标，
            // 宽限窗口内跳过离场判定，否则会“概率传送成功，失败时提示超出范围”。
            Long grace = spectatorGrace.get(player.getUniqueId());
            if (grace != null) {
                if (now < grace) {
                    continue;
                }
                spectatorGrace.remove(player.getUniqueId());
            }
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
                if (now < grace) {
                    continue;
                }
                recentEliminations.remove(uuid);
            }
            // 冷却 + 目标在受保护区域内：即使某次传送被领地守卫取消，也不会形成每 tick 重传的循环
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
        // 全是玩家自身状态：派发到该玩家所属线程
        schedulers.runOwned(player, () -> {
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
        }, null);
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
        // 淘汰会改 members/待重生名单/队伍，并触发胜负判定：一律在权威线程上做
        if (!schedulers.guardAuthoritative(() -> eliminate(player, messageKey, broadcast, deferredRespawn))) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!members.remove(uuid)) {
            return;
        }
        clearPlayerState(player);
        teams.leaveAll(uuid);
        scoreboard.detach(player);
        spectatorGrace.remove(uuid);
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
        UUID uuid = player.getUniqueId();
        // respawn() 与落点改写都是“玩家自身状态”，必须在该玩家所属线程执行；
        // PlayerRespawnEvent 也在同一线程同步触发，因此 pendingRespawn 的
        // 增删读在同一线程上串行（集合本身用并发实现，兼容权威线程的写入）
        schedulers.onEntity(player, () -> {
            if (!player.isOnline()) {
                pendingRespawn.remove(uuid);
                return;
            }
            if (player.isDead()) {
                // 必须在 respawn() **之前**保留待重生标记：PlayerRespawnEvent 正是在
                // respawn() 内部同步触发的，标记若先被清掉，监听就不会把落点改成大厅
                player.spigot().respawn();
            }
            pendingRespawn.remove(uuid);
            // 传送这一步不能留在玩家线程上：teleport() 会先向领地插件下发 /res 指令，
            // 而指令必须在权威线程派发。因此交回权威线程（Paper 上本就是同一个线程）。
            schedulers.onMain(() -> teleport(player, config.settings().location("hall-spawn")));
        }, () -> pendingRespawn.remove(uuid));
    }

    /**
     * 离场/淘汰时清空玩家状态。
     *
     * <p>不做任何背包还原：本插件不保留入场备份（内存备份在崩服时会丢失，
     * 反而让玩家物品更不安全）。需要保护玩家原有物品请用专门的背包备份插件。</p>
     */
    private void clearPlayerState(Player player) {
        Settings settings = config.settings();
        // 清背包 / 光标 / 药水效果都是玩家自身状态：派发到该玩家所属线程。
        // 注意顺序——这里是**离场清理**，不涉及死亡结算，所以延后一 tick 也无副作用。
        schedulers.runOwned(player, () -> {
            if (settings.start().clearInventory()) {
                // 只清物品；经验值归玩家自己（死亡路径的保留见 PlayerStateListener 的 keepLevel）
                player.getInventory().clear();
                player.setItemOnCursor(null);
            }
            if (settings.start().clearEffects()) {
                for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                    player.removePotionEffect(effect.getType());
                }
            }
            player.updateInventory();
        }, null);
    }

    /** 观战玩家离场：回大厅 + 生存模式 + 移出队伍。 */
    public void exitSpectator(Player player) {
        if (!schedulers.guardAuthoritative(() -> exitSpectator(player))) {
            return;
        }
        spectatorGrace.remove(player.getUniqueId());
        teams.leaveAll(player.getUniqueId());
        scoreboard.detach(player);
        setGameMode(player, GameMode.SURVIVAL);
        teleport(player, config.settings().location("hall-spawn"));
        alerts.sendTo(player, "spectator-arena-exit", Map.of());
    }

    /** 右键观战按钮。 */
    public boolean trySpectate(Player player) {
        // 观战会改队伍/宽限窗口/记分板：事件回调可能不在权威线程
        if (!schedulers.guardAuthoritative(() -> trySpectate(player))) {
            return false;
        }
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
        // 传送是异步的：先把队伍标好，同时登记宽限窗口。宽限窗口在
        // checkArenaPresence 里被消费，避免“人还在大厅、队伍已是 fmgz”的那几
        // tick 被误判为“观战者离场”。
        spectatorGrace.put(player.getUniqueId(),
                Bukkit.getCurrentTick() + SPECTATOR_GRACE_TICKS);
        teams.joinSpectatorTeam(player.getUniqueId());
        setGameMode(player, GameMode.SPECTATOR);
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
        // 掉线事件在 Folia 上未必跑在权威线程，而这里要增删 members/queue/pendingHall
        if (!schedulers.guardAuthoritative(() -> onQuit(player))) {
            return;
        }
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
            spectatorGrace.remove(uuid);
            alerts.broadcast("quit", Map.of("player", player.getName()));
            if (phase == GamePhase.RUNNING && !ended) {
                checkVictory();
            }
            retiredFromGame = true;
        } else if (teams.inSpectatorTeam(uuid)) {
            spectatorGrace.remove(uuid);
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
        // 上线事件在 Folia 上未必跑在权威线程，而这里要增删 members/disconnectedSpectators/pendingHall
        if (!schedulers.guardAuthoritative(() -> onJoin(player))) {
            return;
        }
        UUID uuid = player.getUniqueId();

        // 需求 104：对局中掉线的观战者重新上线，仍为观战模式、仍在队伍 fmgz。
        // 这一支必须先于 pendingHall 判定——观战者掉线时也会进 pendingHall，
        // 但对局仍在进行时应当恢复观战，而不是被送回大厅
        if (disconnectedSpectators.remove(uuid)) {
            pendingHall.remove(uuid);
            if (phase == GamePhase.RUNNING) {
                spectatorGrace.put(uuid, Bukkit.getCurrentTick() + SPECTATOR_GRACE_TICKS);
                setGameMode(player, GameMode.SPECTATOR);
                scoreboard.attach(player, config.settings());
                teleport(player, config.settings().location("arena-spawn"));
            } else {
                setGameMode(player, GameMode.SURVIVAL);
                teams.leaveAll(uuid);
                scoreboard.detach(player);
                teleport(player, config.settings().location("hall-spawn"));
            }
            return;
        }

        // 需求 108 / 39：对局中掉线的参战者、准备房间内掉线的入队玩家——
        // 无论对局是否已结束，上线一律移出队伍、恢复生存模式并传送至大厅
        if (pendingHall.remove(uuid)) {
            setGameMode(player, GameMode.SURVIVAL);
            teams.leaveAll(uuid);
            scoreboard.detach(player);
            teleport(player, config.settings().location("hall-spawn"));
            alerts.sendTo(player, "player-arena-exit", Map.of("player", player.getName()));
            return;
        }

        if (phase == GamePhase.RUNNING) {
            if (members.contains(uuid)) {
                // 名单里仍有此人（例如跨 tick 的边界情况）：按观战处理，不再回到对局
                spectatorGrace.put(uuid, Bukkit.getCurrentTick() + SPECTATOR_GRACE_TICKS);
                teams.joinSpectatorTeam(uuid);
                setGameMode(player, GameMode.SPECTATOR);
                scoreboard.attach(player, config.settings());
                teleport(player, config.settings().location("arena-spawn"));
                return;
            }
            if (teams.inSpectatorTeam(uuid)) {
                spectatorGrace.put(uuid, Bukkit.getCurrentTick() + SPECTATOR_GRACE_TICKS);
                setGameMode(player, GameMode.SPECTATOR);
                scoreboard.attach(player, config.settings());
                teleport(player, config.settings().location("arena-spawn"));
                return;
            }
        }
        if (teams.inSpectatorTeam(uuid) || teams.inPlayerTeam(uuid)) {
            // 对局已结束或已离开：清干净并送回大厅
            teams.leaveAll(uuid);
            scoreboard.detach(player);
            setGameMode(player, GameMode.SURVIVAL);
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
    private void checkVictory() {
        if (phase != GamePhase.RUNNING || ended) {
            return;
        }
        schedulers.onMain(() -> {
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
        // 死亡事件在 Folia 上跑在该玩家所属区域线程（受害者自己所在的区域），
        // 而淘汰流程要改 members/待重生名单/队伍/积分：整段交给权威线程。
        // 事件里对掉落与经验的处理（setDrops/setKeepLevel）仍留在监听器里同步完成。
        if (!schedulers.guardAuthoritative(() -> onPlayerDeath(player))) {
            return false;
        }
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
        if (!schedulers.guardAuthoritative(this::requestEnd)) {
            return;
        }
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

    /**
     * 结算：把所有人先送回大厅，再做清场。
     *
     * <p><b>顺序是硬性的，不能调换。</b>玩家的传送与状态复位必须<b>先于</b>任何批量工作完成：
     * 清场要加载/遍历区块，一旦它排在前面，主线程被占住的这一两秒里回大厅的传送
     * 根本发不出去，玩家看到的就是“对局结束了但人卡在场地里出不来”，客户端同时卡顿。
     * 这正是此前把{@code clearArenaEntities()} 放在 {@code sendPendingHallToLobby()}
     * 之前造成的现象。</p>
     *
     * <p>清场本身已被改成“只排一个后续任务”，因此这里的调用不会阻塞当前 tick。</p>
     */
    private void finishGame() {
        Settings settings = config.settings();
        for (Player player : new ArrayList<>(onlineMembers())) {
            eliminate(player, null, false);
        }
        for (Player player : onlineSpectators()) {
            teams.leaveAll(player.getUniqueId());
            scoreboard.detach(player);
            setGameMode(player, GameMode.SURVIVAL);
            teleport(player, settings.location("hall-spawn"));
        }
        // --- 玩家侧收尾：以下必须在任何区块批量操作之前 ---
        sendPendingHallToLobby();
        scoreboard.detachAll();
        resetRuntimeState();
        // --- 环境侧收尾：可以慢慢做，不能挡住玩家 ---
        clearChests();
        clearArenaEntities();
        shops.despawnAll();
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
            setGameMode(player, GameMode.SURVIVAL);
            teleport(player, config.settings().location("hall-spawn"));
        }
        // 与 finishGame 同一个原则：先把玩家送走，再做环境清理
        sendPendingHallToLobby();
        scoreboard.detachAll();
        resetRuntimeState();
        clearChests();
        // 走“仅已加载区块”那条路：这条清理同时服务于 /fmwar stop 与插件停用，
        // 而停用时不能再调度异步加载区块的任务。远处未加载区块的残留留到下次结算处理。
        cleaner.startLoadedOnly();
        shops.despawnAll();
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
            setGameMode(player, GameMode.SURVIVAL);
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
        spectatorGrace.clear();
        lastDamager.clear();
        // chests 刻意**不在这里**清空：它是 clearChests() 的待办清单，而本方法在两条
        // 清理路径（finishGame / forceCleanup）里都排在 clearChests() 之前。
        // 若在此清空，clearChests() 会遍历一个空列表、一个箱子都不清，奖励箱连同
        // 上一局的物品就原地留到下一局（实测 bug：开局看到箱子里还有上局的物品）。
        // 清空动作由 clearChests() 自己负责。
        prepareClicks = 0;
        duelTeleported = false;
        lastOvertimeSecond = -1L;
        lastEmeraldSecond = 0L;
        lastEmeraldCountdownShown = -1L;
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
     * 清空场地范围内所有实体（不含玩家，也不含自定义铁砧）。
     *
     * <p>需求 118：“清空游戏场地范围内所有实体（不包括玩家，此时玩家应全部离开场地范围）”。
     * 只有奖励箱、掉落物、箭矢、载具等都要一并清理。</p>
     *
     * <p><b>实现已移交给 {@link ArenaCleaner}</b>：{@code World#getEntities()} 只返回
     * 已加载区块里的实体，而场地横跨上千个区块，单靠它会漏掉大量掉落物。
     * 铁砧排除规则（记分板标签 / PDC）随之一起搬过去，两条清场路径共用同一套判定。</p>
     */
    private void clearArenaEntities() {
        cleaner.start();
    }

    /** 强制中止（/fmwar stop）。 */
    public void stop() {
        if (!schedulers.guardAuthoritative(this::forceCleanup)) {
            return;
        }
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
     *
     * <p><b>调用前提：必须在权威线程上调用。</b>它内部要经
     * {@link ResidenceService#runWithAccess} 下发 {@code /res set} 指令，而指令派发只能在
     * 权威线程。之所以不像 {@link #teleport(Player, Location)} 那样自带守卫，
     * 是因为这里必须把<b>真实</b>的 future 交回调用方——重排之后就没法再返回它了。
     * 目前的唯一调用方 {@link #tryJoinQueue} 已经在入口处被守卫，前提成立。</p>
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
        // 传送前要下发领地指令（必须是权威线程），因此这里也做一次守卫：
        // 从玩家自身线程（例如重生流程）发起的传送会被重排回权威线程再执行。
        if (!schedulers.guardAuthoritative(() -> teleport(player, location))) {
            return;
        }
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
