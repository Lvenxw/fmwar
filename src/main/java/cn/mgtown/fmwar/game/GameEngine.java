package cn.mgtown.fmwar.game;

import cn.mgtown.FMWar;
import cn.mgtown.fmwar.config.Settings;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ConfigService;
import cn.mgtown.fmwar.service.GameScoreboard;
import cn.mgtown.fmwar.service.ShopService;
import cn.mgtown.fmwar.service.TeamService;
import cn.mgtown.fmwar.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
import java.util.concurrent.ThreadLocalRandom;

import cn.mgtown.fmwar.config.Position;
import cn.mgtown.fmwar.config.Region;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;

/**
 * 对局引擎：唯一的状态机 + 唯一的 20tick 主循环。
 *
 * <p>整份规格的时序都挂在同一个时钟上（准备倒计时、900s 对局倒计时、剩 60s 传决斗圈、
 * 每 30s 发绿宝石、归零后每秒扣血），因此这里只允许存在一个循环，按阶段分派，
 * 避免多个定时器互相竞态。</p>
 */
public final class GameEngine {

    /** 玩家被本插件传送时的豁免标记，配合 EntityTeleportEvent 放行。 */
    private final Set<UUID> teleportBypass = new HashSet<>();

    /** 每个玩家在入场时的背包快照，用于“死亡/离场后恢复原背包”（可配置）。 */
    private final Map<UUID, ItemStack[]> inventoryBackups = new HashMap<>();

    /** 准备的游戏世界。 */
    private World world;

    private final FMWar plugin;
    private final ConfigService config;
    private final AlertService alerts;
    private final TeamService teams;
    private final GameScoreboard scoreboard;
    private final ShopService shops;

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
    /** 掉线玩家的入场背包快照：离线期间写背包会被服务端覆盖，留到上线时再写回。 */
    private final Map<UUID, ItemStack[]> disconnectedInventory = new HashMap<>();

    /** 本局是否已判定结束（结束后不再接受任何淘汰/胜利判定，避免同一局重复结算）。 */
    private boolean ended;

    private BukkitTask tickTask;

    public GameEngine(FMWar plugin, ConfigService config, AlertService alerts,
                      TeamService teams, GameScoreboard scoreboard, ShopService shops) {
        this.plugin = plugin;
        this.config = config;
        this.alerts = alerts;
        this.teams = teams;
        this.scoreboard = scoreboard;
        this.shops = shops;
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
        chunks.add(chunkKey((int) Math.floor(duel.centerX()) >> 4, (int) Math.floor(duel.centerZ()) >> 4));

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

    /** 插件停用：强制结算并清场，运行期状态不落盘。 */
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        forceCleanup();
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
                // 空闲阶段：准备进度归零，并保证“离开准备房间即退出队列”始终生效
                if (prepareClicks != 0) {
                    prepareClicks = 0;
                }
                syncPrepRoster();
            }
            case PREPARING -> tickPreparing(now);
            case RUNNING -> tickRunning(now);
            case ENDING -> tickEnding(now);
        }
    }

    private void tickPreparing(long now) {
        syncPrepRoster();
        if (timer != null && timer.expired(now)) {
            beginGame();
            return;
        }
        if (timer != null) {
            long remaining = TimeUtil.ceilSeconds(timer.remainingTicks(now));
            if (remaining != lastCountdownSecond) {
                lastCountdownSecond = remaining;
                for (UUID uuid : queue) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        alerts.sendActionBarTo(player, "prepare-countdown",
                                Map.of("seconds", Long.toString(remaining)));
                    }
                }
            }
        }
    }

    private void tickRunning(long now) {
        if (timer == null) {
            return;
        }
        long remainingTicks = timer.remainingTicks(now);
        long elapsedTicks = timer.elapsedTicks(now);
        Settings settings = config.settings();

        // 0) 场地范围判定（离场/观战离场/无关玩家清场）
        checkArenaPresence();
        // 存活人数只算一次：本 tick 的记分板、周期复核都复用它，
        // 否则同一 tick 会把名单遍历三遍
        int alive = aliveCount();

        // 1) 记分板（剩余时间 + 存活人数）
        if (settings.scoreboard().enabled()) {
            scoreboard.update(settings, TimeUtil.mmss(remainingTicks), Integer.toString(alive));
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

    /**
     * 同步准备房间名单，并处理“离开准备房间即退出队列”（需求 39）。
     *
     * <p>这段逻辑每 tick 都跑（含 IDLE 阶段），因此队列成员集合始终等于
     * “已入队且仍在准备房间内”的玩家，准备倒计时的人数判定也用它，而不是队列总数。</p>
     */
    private void syncPrepRoster() {
        Set<UUID> current = playersInRegion(config.settings().region("prep-room"));
        if (current.equals(prepRoster)) {
            return;
        }
        prepRoster = current;

        // 已入队但已不在准备房间内的玩家：退出队列
        for (UUID uuid : new ArrayList<>(queue)) {
            if (current.contains(uuid)) {
                continue;
            }
            Player player = Bukkit.getPlayer(uuid);
            queue.remove(uuid);
            if (player != null) {
                alerts.sendTo(player, "queue-left-self", Map.of());
                alerts.broadcast("queue-leave", Map.of("player", player.getName()));
            }
        }

        if (timer != null) {
            // 倒计时期间准备房间内人数不足两人：退回准备阶段并提示
            if (queuedInRoom() < 2) {
                timer = null;
                prepareClicks = 0;
                lastCountdownSecond = -1L;
                alerts.broadcastTo(onlinePlayers(current), "prepare-reset", Map.of());
            }
        } else if (prepareClicks > 0) {
            prepareClicks = 0;
            alerts.broadcastTo(onlinePlayers(current), "prepare-reset", Map.of());
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
        if (!canStart()) {
            alerts.sendActionBarTo(player, "prepare-need-two", Map.of());
            return;
        }
        prepareClicks++;
        long required = settings.timing().prepareClicks();
        alerts.sendActionBarTo(player, "prepare-progress", Map.of(
                "clicks", Integer.toString(prepareClicks),
                "required", Long.toString(required)));
        if (prepareClicks >= required) {
            long countdownTicks = Math.max(1L, settings.timing().prepareCountdownSeconds()) * 20L;
            timer = new Timer(GamePhase.PREPARING, Bukkit.getCurrentTick(), countdownTicks);
            lastCountdownSecond = -1L;
            alerts.broadcast("prepare-announce", Map.of());
        }
    }

    /** 右键返回大厅按钮：退出队列并回大厅。 */
    public void leaveToHall(Player player) {
        queue.remove(player.getUniqueId());
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
        if (phase != GamePhase.IDLE) {
            // 对局中、结算中与准备倒计时中都不再接收新玩家
            alerts.sendTo(player, "game-already-running", Map.of());
            return false;
        }
        if (queue.contains(player.getUniqueId())) {
            // 以前这里静默返回，玩家只会觉得“按钮没反应”
            alerts.sendTo(player, "queue-already-joined", Map.of());
            return false;
        }
        queue.add(player.getUniqueId());
        teleport(player, config.settings().location("prep-spawn"));
        alerts.sendTo(player, "queue-joined-self", Map.of());
        alerts.broadcast("queue-join", Map.of("player", player.getName()));
        return true;
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
        alerts.broadcast("game-start", Map.of());
    }

    /** 开局前：清空背包与药水效果（需求：无法带出/带入）。 */
    private void applyStartState(Player player) {
        Settings settings = config.settings();
        if (settings.start().clearInventory()) {
            inventoryBackups.put(player.getUniqueId(), player.getInventory().getContents().clone());
            player.getInventory().clear();
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

    private void disperse(List<Player> participants) {
        Settings settings = config.settings();
        Settings.Disperse disperse = settings.disperse();
        if (!disperse.enabled() || world == null) {
            for (Player player : participants) {
                teleport(player, settings.location("arena-spawn"));
            }
            return;
        }
        List<Location> taken = new ArrayList<>();
        for (int index = 0; index < participants.size(); index++) {
            final Player player = participants.get(index);
            final int slot = index;
            Location sample = SafeLocation.sample(world, disperse.centerX(), disperse.centerZ(),
                    disperse.radius(), disperse.minSpacing(), disperse.maxAttempts(), taken);
            if (sample != null) {
                taken.add(sample);
                teleport(player, sample);
                continue;
            }
            // 找不到合格点：异步加载该区块后再试一次，仍失败则退回场地传送点
            int chunkX = (int) Math.floor(disperse.centerX()) >> 4;
            int chunkZ = (int) Math.floor(disperse.centerZ()) >> 4;
            world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
                Location retry = SafeLocation.sample(world, disperse.centerX(), disperse.centerZ(),
                        disperse.radius(), disperse.minSpacing(), disperse.maxAttempts(), taken);
                if (retry != null) {
                    taken.add(retry);
                    teleport(player, retry);
                } else {
                    plugin.getLogger().warning("玩家 " + player.getName() + " 未能找到分散落点（槽位 " + slot + "），已退回场地传送点");
                    teleport(player, settings.location("arena-spawn"));
                }
            }));
        }
    }

    private void teleportToDuel() {
        Settings settings = config.settings();
        Settings.Duel duel = settings.duel();
        World target = world;
        if (target == null) {
            return;
        }
        List<Location> taken = new ArrayList<>();
        for (Player player : onlineMembers()) {
            Location sample = SafeLocation.sample(target, duel.centerX(), duel.centerZ(),
                    duel.radius(), duel.minSpacing(), duel.maxAttempts(), taken);
            if (sample == null) {
                teleport(player, settings.location("arena-spawn"));
                continue;
            }
            taken.add(sample);
            teleport(player, sample);
            alerts.sendActionBarTo(player, "duel-teleport", Map.of());
        }
    }

    // ------------------------------------------------------------------
    // 奖励箱
    // ------------------------------------------------------------------

    private void spawnChests() {
        Settings.Loot loot = config.settings().loot();
        if (loot.lootGroups().isEmpty()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Position position : loot.chestLocations()) {
            Location location = position.toLocation();
            if (location == null || location.getWorld() == null) {
                plugin.getLogger().warning("奖励箱坐标世界未加载: " + position.world());
                continue;
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
            }
            if (block.getState() instanceof Chest chest) {
                // 每个箱子独立随机取一行，且只放这一行的物品
                List<String> group = loot.lootGroups().get(random.nextInt(loot.lootGroups().size()));
                List<ItemStack> items = plugin.lootParser().parseGroup(group);
                for (int slot = 0; slot < items.size() && slot < chest.getInventory().getSize(); slot++) {
                    chest.getInventory().setItem(slot, items.get(slot));
                }
                chest.update(true, false);
                chests.add(block.getLocation());
            }
        }
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
        teleport(player, config.settings().locationOrNull("hall-spawn"), true);
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
     * 把玩家的入场背包备份从 {@code inventoryBackups} 挪到 {@code disconnectedInventory}。
     *
     * <p>离线期间写入背包会被服务端在保存玩家数据时覆盖，所以掉线时只做转移，
     * 等玩家上线后再写回。</p>
     */
    private void takeInventoryBackup(Player player) {
        ItemStack[] backup = inventoryBackups.remove(player.getUniqueId());
        if (backup != null) {
            disconnectedInventory.put(player.getUniqueId(), backup);
        }
    }

    /** 上线时把掉线前扣留的入场背包写回。 */
    private void restoreInventoryBackup(Player player) {
        ItemStack[] backup = disconnectedInventory.remove(player.getUniqueId());
        if (backup != null) {
            player.getInventory().setContents(backup);
            player.updateInventory();
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
            teleport(player, config.settings().locationOrNull("hall-spawn"), true);
        });
    }

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
        restoreInventory(player);
        player.updateInventory();
    }

    /**
     * 可选：把入场时备份的背包还给玩家。
     *
     * <p>默认开启；还原动作本身就会覆盖掉场地内拾取的物品，因此顺序是先清空再还原。
     * 不开启时（{@code start.restore-on-leave=false}）玩家入场前的物品会被丢弃——
     * 这是为了“游戏内物品不能带出场地”这条硬约束，请按服上是否另有背包备份机制来选择。</p>
     */
    private void restoreInventory(Player player) {
        if (!config.settings().start().restoreOnLeave()) {
            return;
        }
        ItemStack[] backup = inventoryBackups.remove(player.getUniqueId());
        if (backup != null) {
            player.getInventory().setContents(backup);
        }
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
            // 吞下背包备份：玩家上线时由 onJoin 写回（离线期间写 inventory 会被服务端覆盖）
            takeInventoryBackup(player);
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
            restoreInventoryBackup(player);
            return;
        }

        // 需求 108 / 39：对局中掉线的参战者、准备房间内掉线的入队玩家——
        // 无论对局是否已结束，上线一律移出队伍、恢复生存模式并传送至大厅
        if (pendingHall.remove(uuid)) {
            player.setGameMode(GameMode.SURVIVAL);
            teams.leaveAll(uuid);
            scoreboard.detach(player);
            teleport(player, config.settings().location("hall-spawn"));
            restoreInventoryBackup(player);
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
    private void checkVictory() {
        if (phase != GamePhase.RUNNING || ended) {
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
                alerts.broadcast("win", Map.of("player", winner.getName()));
                eliminate(winner, null, false);
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
            restoreInventoryBackup(player);
        }
    }

    /**
     * 清空全部运行期状态。
     *
     * <p>两条清理路径（正常结算 {@code finishGame} 与停用兜底 {@code forceCleanup}）
     * 共用一份实现，避免漏清某个集合——此前的“漏清冷却表 / 漏清背包快照”正是这么来的。</p>
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
        inventoryBackups.clear();
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
        // 刻意不清 pendingHall / disconnectedInventory：它们描述的是“离线玩家下次上线怎么处理”，
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
    private void clearArenaEntities() {
        Region arena = config.settings().optionalRegion("arena");
        if (arena == null) {
            return;
        }
        World target = arena.bukkitWorld();
        if (target == null) {
            return;
        }
        for (org.bukkit.entity.Entity entity : target.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (arena.contains(entity.getLocation())) {
                entity.remove();
            }
        }
    }

    /** 强制中止（/fmwar stop）。 */
    public void stop() {
        forceCleanup();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 传送并把 UUID 加入豁免集合，供 EntityTeleportEvent 放行。 */
    public void teleport(Player player, Position target) {
        if (target == null) {
            plugin.getLogger().warning("传送目标未配置，玩家 " + player.getName() + " 未被传送");
            return;
        }
        teleport(player, target.toLocation());
    }

    /** 传送（带豁免开关）。 */
    public void teleport(Player player, Position target, boolean bypass) {
        if (target == null) {
            plugin.getLogger().warning("传送目标未配置，玩家 " + player.getName() + " 未被传送");
            return;
        }
        teleport(player, target.toLocation(), bypass);
    }

    /** 传送并把 UUID 加入豁免集合，供 EntityTeleportEvent 放行。 */
    public void teleport(Player player, Location location) {
        teleport(player, location, true);
    }

    /**
     * 传送。
     *
     * @param bypass true 时把该玩家短暂加入传送豁免集合，使领地插件的传送限制不会打断本插件的传送
     */
    public void teleport(Player player, Location location, boolean bypass) {
        if (location == null) {
            return;
        }
        if (bypass) {
            teleportBypass.add(player.getUniqueId());
            player.teleportAsync(location).whenComplete((result, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> teleportBypass.remove(player.getUniqueId())));
        } else {
            player.teleportAsync(location);
        }
    }

    public boolean isBypassingTeleport(UUID uuid) {
        return teleportBypass.contains(uuid);
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
