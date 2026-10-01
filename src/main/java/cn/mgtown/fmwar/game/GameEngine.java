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
    /** 上次发放绿宝石的秒数。 */
    private long lastEmeraldSecond = -1L;
    /** 是否已经执行过决斗圈传送。 */
    private boolean duelTeleported;
    /** 本局生成的奖励箱方块坐标（结束时清理）。 */
    private final List<Location> chests = new ArrayList<>();

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
    }

    /** 插件停用：强制结算并清场，运行期状态不落盘。 */
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        forceCleanup();
    }

    /** /fmwar reload 之后重新解析世界引用。 */
    public void onReload() {
        World resolved = resolveWorld();
        if (resolved != null) {
            this.world = resolved;
        }
    }

    private World resolveWorld() {
        String name = config.settings().world();
        World resolved = Bukkit.getWorld(name);
        if (resolved == null && !Bukkit.getWorlds().isEmpty()) {
            resolved = Bukkit.getWorlds().get(0);
            plugin.getLogger().warning("world 配置为 " + name + " 但该世界未加载，已退回 " + resolved.getName());
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
                // 空闲阶段只需保证准备进度归零
                if (prepareClicks != 0) {
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
        long durationSeconds = timer.durationTicks() / 20L;
        Settings settings = config.settings();

        // 0) 场地范围判定（离场/观战离场）
        checkArenaPresence();

        // 1) 记分板（剩余时间 + 存活人数）
        if (settings.scoreboard().enabled()) {
            scoreboard.update(settings, TimeUtil.mmss(remainingTicks), Integer.toString(aliveCount()));
        }

        // 2) 剩 N 秒时把场内玩家集中到决斗圈
        if (!duelTeleported && remainingTicks <= settings.timing().duelTeleportAtSeconds() * 20L) {
            duelTeleported = true;
            teleportToDuel();
        }

        // 3) 每 interval 秒发一颗绿宝石
        long elapsedSeconds = TimeUtil.ceilSeconds(elapsedTicks);
        long interval = Math.max(1L, settings.timing().emeraldIntervalSeconds());
        if (elapsedSeconds > 0 && elapsedSeconds % interval == 0 && elapsedSeconds != lastEmeraldSecond) {
            lastEmeraldSecond = elapsedSeconds;
            giveEmeralds();
        }

        // 4) 倒计时归零：每秒扣血
        if (remainingTicks <= 0) {
            long overtime = TimeUtil.ceilSeconds(-remainingTicks);
            if (overtime > lastOvertimeSecond) {
                lastOvertimeSecond = overtime;
                applyOvertimeDamage();
            }
        }

        // 5) 周期性复核：对局中存活人数跌破两人时放弃本局（正常情况下 checkVictory 会先结束对局）
        if (durationSeconds > 0 && elapsedTicks % 80L == 0L && aliveCount() < 2) {
            endGame();
        }
    }

    private void tickEnding(long now) {
        if (timer == null || timer.expired(now)) {
            finishGame();
        }
    }

    // ------------------------------------------------------------------
    // 准备房间
    // ------------------------------------------------------------------

    /** 准备房间内玩家集合有变化时重置准备进度（需求：期间有玩家进入则重置）。 */
    private void syncPrepRoster() {
        Set<UUID> current = playersInRegion(config.settings().region("prep-room"));
        if (!current.equals(prepRoster)) {
            prepRoster = current;
            if (timer != null) {
                // 倒计时期间人数变化：人数不足则退回准备阶段并提示
                if (queue.size() < 2) {
                    timer = null;
                    prepareClicks = 0;
                    lastCountdownSecond = -1L;
                    alerts.broadcastTo(onlinePlayers(current), "prepare-reset", Map.of());
                }
            } else if (prepareClicks > 0) {
                prepareClicks = 0;
                alerts.broadcastTo(onlinePlayers(prepRoster), "prepare-reset", Map.of());
            }
        }
    }

    /** 队列是否够人（准备房间内至少两名已入队玩家）。 */
    public boolean canStart() {
        Set<UUID> inRoom = playersInRegion(config.settings().region("prep-room"));
        int queued = 0;
        for (UUID uuid : inRoom) {
            if (queue.contains(uuid)) {
                queued++;
            }
        }
        return queued >= 2;
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

    public boolean isRunning() {
        return phase == GamePhase.RUNNING || phase == GamePhase.ENDING;
    }

    public boolean tryJoinQueue(Player player) {
        if (phase != GamePhase.IDLE) {
            // 对局中与准备倒计时中都不再接收新玩家
            alerts.sendTo(player, "game-already-running", Map.of());
            return false;
        }
        if (queue.contains(player.getUniqueId())) {
            return false;
        }
        queue.add(player.getUniqueId());
        teleport(player, config.settings().location("prep-spawn"));
        alerts.sendTo(player, "queue-joined-self", Map.of());
        alerts.broadcast("queue-join", Map.of("player", player.getName()));
        return true;
    }

    public void quitQueue(Player player) {
        if (queue.remove(player.getUniqueId())) {
            alerts.broadcast("queue-leave", Map.of("player", player.getName()));
        }
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
        lastOvertimeSecond = -1L;
        lastEmeraldSecond = -1L;
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
            scoreboard.attach(player);
            participants.add(player);
        }
        queue.clear();

        if (participants.isEmpty()) {
            alerts.broadcast("game-over", Map.of());
            phase = GamePhase.ENDING;
            timer = new Timer(GamePhase.ENDING, Bukkit.getCurrentTick(), 1L);
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
                    duel.radius(), 0.0, duel.maxAttempts(), taken);
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
            block.setType(Material.CHEST, false);
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

    /** 每 tick 检查离场：游戏内玩家离开场地按“离开游戏”处理，观战玩家离开则送回大厅。 */
    private void checkArenaPresence() {
        if (phase != GamePhase.RUNNING) {
            return;
        }
        Region arena = config.settings().region("arena");
        for (UUID uuid : new ArrayList<>(members)) {            Player player = Bukkit.getPlayer(uuid);
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
    }

    /** 淘汰一名游戏内玩家：清背包、清效果、移出队伍、送回大厅并广播。 */
    public void eliminate(Player player, String messageKey, boolean broadcast) {
        UUID uuid = player.getUniqueId();
        if (!members.remove(uuid)) {
            return;
        }
        clearPlayerState(player);
        teams.leaveAll(uuid);
        scoreboard.detach(player);
        scheduleRespawn(player);
        teleport(player, config.settings().location("hall-spawn"));
        if (messageKey != null && !messageKey.isEmpty()) {
            if (broadcast) {
                alerts.broadcast(messageKey, Map.of("player", player.getName()));
            } else {
                alerts.sendTo(player, messageKey, Map.of("player", player.getName()));
            }
        }
        checkVictory();
    }

    /**
     * 死亡淘汰后把玩家从死亡界面拉回来。
     *
     * <p>需求要求死亡玩家“传送至大厅传送点”，但玩家死亡时会停留在重生界面，
     * 直接 teleport 会让其在点击重生后被拉回死亡点。这里延后 1 tick 主动重生，
     * 且只在玩家确实处于死亡状态时才调用，避免对存活玩家误触发重生。</p>
     */
    private void scheduleRespawn(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (player.isDead()) {
                player.spigot().respawn();
            }
            teleport(player, config.settings().location("hall-spawn"));
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

    /** 可选：把入场时备份的背包还给玩家（默认关闭，交给服务器自己的背包备份插件）。 */
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
        if (!isRunning()) {
            alerts.sendTo(player, "spectator-unavailable", Map.of());
            return false;
        }
        if (members.contains(player.getUniqueId())) {
            alerts.sendTo(player, "spectator-only-in-game", Map.of());
            return false;
        }
        teams.joinSpectatorTeam(player.getUniqueId());
        player.setGameMode(GameMode.SPECTATOR);
        scoreboard.attach(player);
        teleport(player, config.settings().location("arena-spawn"));
        alerts.sendTo(player, "spectator-enter", Map.of());
        return true;
    }

    /** 玩家掉线。 */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        if (members.contains(uuid)) {
            members.remove(uuid);
            alerts.broadcast("quit", Map.of("player", player.getName()));
            checkVictory();
        }
        queue.remove(uuid);
    }

    /** 玩家上线。 */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (phase == GamePhase.RUNNING) {
            if (teams.inPlayerTeam(uuid)) {
                // 对局中离线的参战者不再回到对局，按观战处理并送到场地观战点
                teams.joinSpectatorTeam(uuid);
                player.setGameMode(GameMode.SPECTATOR);
                scoreboard.attach(player);
                teleport(player, config.settings().location("arena-spawn"));
                return;
            }
            if (teams.inSpectatorTeam(uuid)) {
                player.setGameMode(GameMode.SPECTATOR);
                scoreboard.attach(player);
                teleport(player, config.settings().location("arena-spawn"));
                return;
            }
        }
        if (teams.inSpectatorTeam(uuid) || teams.inPlayerTeam(uuid)) {
            // 对局已结束：清干净并送回大厅
            teams.leaveAll(uuid);
            scoreboard.detach(player);
            player.setGameMode(GameMode.SURVIVAL);
            teleport(player, config.settings().location("hall-spawn"));
            return;
        }
        if (queue.contains(uuid)) {
            return;
        }
        if (prepRoster.contains(uuid)) {
            // 准备房间内掉线的玩家已在 onQuit 退出队列，上线时回大厅
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
        if (phase != GamePhase.RUNNING) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (phase != GamePhase.RUNNING) {
                return;
            }
            List<Player> alive = onlineMembers();
            if (alive.size() == 1) {
                Player winner = alive.get(0);
                alerts.broadcast("win", Map.of("player", winner.getName()));
                eliminate(winner, null, false);
                endGame();
            } else if (alive.isEmpty()) {
                alerts.broadcast("no-survivor", Map.of());
                endGame();
            }
        });
    }

    // ------------------------------------------------------------------
    // 结束与清场
    // ------------------------------------------------------------------

    /** 请求结束对局（/fmwar stop 或胜负判定）。 */
    public void requestEnd() {
        if (phase == GamePhase.IDLE) {
            return;
        }
        if (phase == GamePhase.RUNNING || phase == GamePhase.PREPARING) {
            endGame();
        }
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
        shops.despawnAll();
        scoreboard.detachAll();
        members.clear();
        queue.clear();
        prepareClicks = 0;
        prepRoster = Set.of();
        timer = null;
        phase = GamePhase.IDLE;
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
        shops.despawnAll();
        scoreboard.detachAll();
        members.clear();
        queue.clear();
        timer = null;
        phase = GamePhase.IDLE;
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
        Location location = target.toLocation();
        if (location == null) {
            plugin.getLogger().warning("传送目标世界未加载: " + target.world());
            return;
        }
        teleport(player, location);
    }

    /** 传送并把 UUID 加入豁免集合，供 EntityTeleportEvent 放行。 */
    public void teleport(Player player, Location location) {
        if (location == null) {
            return;
        }
        teleportBypass.add(player.getUniqueId());
        player.teleportAsync(location).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> teleportBypass.remove(player.getUniqueId())));
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
