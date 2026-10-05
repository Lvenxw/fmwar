package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Region;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 场地清场：清掉场地范围内的所有实体，**包括未加载区块里的**。
 *
 * <p><b>为什么不能只用 {@code World#getEntities()}</b>：该方法只返回**已加载区块**中的实体。
 * 对局中玩家只让身边一小部分区块保持加载，远处区块里的掉落物存在区块存档里，
 * {@code getEntities()} 根本看不到，于是永远清不掉、跨局累积。</p>
 *
 * <p><b>为什么不用“开局强加载整片场地”</b>：那要常驻上千个区块（详见
 * {@code docs/adr/0005-*.md}），代价远大于残留掉落物。</p>
 *
 * <p><b>为什么只扫“对局期间活动过的区块”，而不是场地矩形里的全部区块</b>：
 * 掉落物只能产生在曾被加载的区块里。场地矩形按当前配置约 1260 个区块，而一局里
 * 玩家真正活动过的通常只有几十个。扫全量不只是慢，还会连带出两种客户端卡顿：
 * 主线程被一次性派发的上千个区块加载饿死（客户端收不到 tick），
 * 以及服务端成批卸载区块导致客户端地形消失再重绘。</p>
 *
 * <p><b>三条关键约束</b>：</p>
 * <ul>
 *   <li><b>绝不生成新区块</b>：只处理 {@code isChunkGenerated} 为真的区块，
 *       加载时一律 {@code generate=false}。否则会为了清掉落物而凭空造地形。</li>
 *   <li><b>加载前后状态还原</b>：只卸载<b>本次自己加载的</b>区块。
 *       {@code unloadChunk} 在区块被玩家/其它插件占用时返回 false——宁可留着，
 *       也绝不把玩家脚下的区块踢掉。</li>
 *   <li><b>每tick 限量</b>：无论同步还是异步部分，都按 {@code cleanup.chunks-per-tick}
 *       分批，绝不在单个 tick 内把上千个区块处理完。</li>
 * </ul>
 */
public final class ArenaCleaner {

    /** 自定义铁砧（customanvil 插件）的记分板标签，属于场地常驻设施，不该清。 */
    private static final String ANVIL_TAG = "customanvil";

    /**
     * 追踪半径（格）。
     *
     * <p>掉落物会朝四周散开，玩家站定后钓竿/战斗抛射物的落点通常在数格外。
     * 取一个不大的余量，保证“玩家去过的地方”都被覆盖到。</p>
     */
    private static final int TRACK_RADIUS = 24;

    private final Plugin plugin;
    private final ConfigService config;

    /**
     * 对局期间活动过的区块（打包成 long 作为键）。
     *
     * <p>用 {@link LinkedHashSet} 而非 {@code HashSet}：结算时按插入顺序处理，
     * 于是“先被加载过的区块”先被清理，行为可预期。</p>
     */
    private final Set<Long> touchedChunks = new LinkedHashSet<>();

    /** 正在进行的分批扫荡任务；同一时间只允许一个。 */
    private BukkitTask sweepTask;
    /**
     * 当前扫荡的“世代”标记。
     *
     * <p>存在它的唯一理由：{@link #cancel()} 只能取消定时任务，**无法取消已经发出的
     * 区块加载回调**。这些回调仍会在稍后回到主线程，若不加以识别，它们会
     * ① 用新一轮的计数器累加出错误统计，② 调 {@code finish()} 把<b>新一轮</b>的
     * 任务也置空，导致新一轮扫荡被静默掐断。每个 {@link SweepStep} 出生时领一个
     * 世代号，回来时先核对身份，不是自己这一轮就直接放弃。</p>
     */
    private long sweepGeneration;
    private int sweptChunks;
    private int removedEntities;
    private int failedChunks;

    public ArenaCleaner(Plugin plugin, ConfigService config) {
        this.plugin = plugin;
        this.config = config;
    }

    // ------------------------------------------------------------------
    // 对局期间追踪
    // ------------------------------------------------------------------

    /**
     * 记录当前在场地内（或紧邻场地）的玩家所在区块。
     *
     * <p>由引擎在对局中低频调用（默认每 20 tick 一次）。它只读玩家坐标与区块编号，
     * 开销可以忽略；换来的是结算时只处理真正可能有掉落物的几十个区块，
     * 而不是整个 1260 区块的矩形。</p>
     *
     * <p>刻意包含“紧邻场地”的玩家：站在场地外一格钓鱼，掉落物也可能落在场地内。</p>
     */
    public void track() {
        Region arena = config.settings().optionalRegion("arena");
        if (arena == null) {
            return;
        }
        World world = arena.bukkitWorld();
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            Location2 pos = Location2.of(player);
            for (int x = pos.minChunkX(); x <= pos.maxChunkX(); x++) {
                for (int z = pos.minChunkZ(); z <= pos.maxChunkZ(); z++) {
                    if (chunkIntersectsArena(x, z, arena)) {
                        touchedChunks.add(chunkKey(x, z));
                    }
                }
            }
        }
    }

    /** 结算与开新一局时清空追踪名单。 */
    public void resetTracking() {
        touchedChunks.clear();
    }

    // ------------------------------------------------------------------
    // 启动清场
    // ------------------------------------------------------------------

    /**
     * 启动清场。
     *
     * <p><b>刻意不要求调用方处在“玩家已回大厅”的时机之后</b>：本方法自身
     * <b>不做任何同步批量工作</b>，只排一个从下一 tick 开始的任务。这样调用方
     * 后续的“传送回大厅 / 恢复领地权限”不会被清场阻塞——这正是上一版
     * “客户端卡顿、回不了大厅”的成因。</p>
     *
     * <p>已加载的区块会被排在队列<b>最前面</b>，因此玩家眼前的掉落物在最初几个
     * tick 内就会被清掉（而不是牺牲流畅度去追求“同一 tick 清完”）。</p>
     */
    public void start() {
        Region arena = config.settings().optionalRegion("arena");
        if (arena == null) {
            plugin.getLogger().warning("regions.arena 未配置，场地清场已跳过");
            return;
        }
        World world = arena.bukkitWorld();
        if (world == null) {
            plugin.getLogger().warning("场地所在世界未加载，场地清场已跳过");
            return;
        }

        cancel();
        sweptChunks = 0;
        removedEntities = 0;
        failedChunks = 0;

        if (touchedChunks.isEmpty()) {
            // 没有任何追踪记录（例如对局没跑过主循环就被中止），
            // 退回到“当前已加载的区块”这个保守集合，而不是扫全量地形
            plugin.getLogger().info("场地清场：没有对局期间的活动记录，"
                    + "改为只处理当前已加载区块");
        }

        List<long[]> queue = buildQueue(world, arena);
        if (queue.isEmpty()) {
            plugin.getLogger().info("场地清场完成：没有需要处理的区块");
            return;
        }
        if (!config.settings().cleanup().enabled()) {
            plugin.getLogger().warning("场地内还有 " + queue.size()
                    + " 个区块可能残留掉落物，但 cleanup.enabled=false 已关闭清理。"
                    + "开启后可一并清理（会临时加载这些区块）");
            return;
        }

        int budget = config.settings().cleanup().safeChunksPerTick();
        plugin.getLogger().info("场地清场：共 " + queue.size() + " 个区块待处理"
                + "（每 tick " + budget + " 个，分批进行不影响玩家返回大厅）");
        // 下一 tick 才开始，且每 tick 只处理 budget 个：主线程不再被清场占用
        long generation = ++sweepGeneration;
        sweepTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, new SweepStep(world, arena, queue, budget, generation), 1L, 1L);
    }

    /**
     * 同步清场，只处理已加载区块。
     *
     * <p>供插件停用等<b>不能再调度异步任务</b>的场合使用。此时不能临时加载区块
     * （加载后无人卸载，且停用过程中调度任务会抛异常）。未加载区块的残留留到下次
     * 正常结算时再清——服务器停机时这些区块本来就还在磁盘上。</p>
     */
    public void startLoadedOnly() {
        Region arena = config.settings().optionalRegion("arena");
        if (arena == null) {
            return;
        }
        World world = arena.bukkitWorld();
        if (world == null) {
            return;
        }
        cancel();
        int removed = 0;
        int chunks = 0;
        for (Chunk chunk : world.getLoadedChunks()) {
            if (!chunkIntersectsArena(chunk.getX(), chunk.getZ(), arena)) {
                continue;
            }
            removed += purge(chunk, arena);
            chunks++;
        }
        plugin.getLogger().info("场地清场（仅已加载区块）：扫描 " + chunks + " 个区块，删除实体 "
                + removed + " 个");
    }

    /** 取消未完成的分批扫荡（插件停用、重复开局时调用）。 */
    public void cancel() {
        // 递增世代号：已发出的加载回调回来时会发现身份不符，
        // 从而跳过清理逻辑（但仍会卸载自己加载的区块，避免泄漏）
        sweepGeneration++;
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    // ------------------------------------------------------------------
    // 分批扫荡
    // ------------------------------------------------------------------

    /**
     * 一轮扫荡的游标。
     *
     * <p>{@code runTaskTimer} 的回调每 tick 调一次，因此这里持有索引，
     * 每次至多领取 {@code budget} 个区块，处理完全部后自行取消任务并打收尾日志。</p>
     */
    private final class SweepStep implements Runnable {

        private final World world;
        private final Region arena;
        private final List<long[]> queue;
        private final int budget;
        /** 本轮身份；用于识别“已被 cancel 掉的那一轮”的迟到回调。 */
        private final long generation;
        private int cursor;
        /** 已经发起、等待回调完成的区块数。 */
        private int inFlight;
        /** 本轮是否已收尾，避免 {@code finish()} 被调两次而重复打日志。 */
        private boolean done;

        SweepStep(World world, Region arena, List<long[]> queue, int budget, long generation) {
            this.world = world;
            this.arena = arena;
            this.queue = queue;
            this.budget = budget;
            this.generation = generation;
        }

        @Override
        public void run() {
            if (generation != sweepGeneration || done) {
                return;
            }
            // 限制同时在途的数量：一次把整份队列派发出去会把主线程的回调队列塞满
            while (inFlight < budget && cursor < queue.size()) {
                long[] coord = queue.get(cursor++);
                inFlight++;
                process((int) coord[0], (int) coord[1]);
            }
            if (cursor >= queue.size() && inFlight == 0) {
                finish();
            }
        }

        /**
         * 处理单个区块：已加载的直接清；未加载的临时加载 → 清 → 卸载。
         *
         * <p><b>调用约定</b>：{@link #run()} 在调用本方法前已把 {@code inFlight} 加一。
         * 本方法有三条出口，其中<b>两条是同步完成的</b>（已加载 / 从未生成），
         * 它们必须自己把 {@code inFlight} 减回去——否则计数器只增不减，
         * 扫到第 {@code budget} 个同步区块后 {@code while (inFlight < budget)} 永远为假，
         * 整个扫荡就此卡死、{@link #finish()} 也不会被调用（连收尾日志都没有）。</p>
         *
         * <p>{@code generate=false} 是硬性要求：万一区块在这段时间被删除，
         * 也不能因此生成新区块。</p>
         */
        private void process(int chunkX, int chunkZ) {
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                // 已加载：就地清理，不涉及加载/卸载，不会惊动客户端
                inFlight--;
                try {
                    sweptChunks++;
                    removedEntities += purge(world.getChunkAt(chunkX, chunkZ), arena);
                } catch (RuntimeException exception) {
                    failedChunks++;
                }
                return;
            }
            if (!world.isChunkGenerated(chunkX, chunkZ)) {
                // 从未生成过：里面不可能有掉落物，也不该为清场去生成它
                inFlight--;
                return;
            }
            world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
                // 回到主线程再动区块/实体：加载回调不保证线程
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    inFlight--;
                    // 本轮已被取消/替换：区块已经加载上来了，但不再做后续处理。
                    // 仍要卸载它，否则就是一次纯泄漏。
                    if (generation != sweepGeneration || done) {
                        unloadQuietly(world, chunkX, chunkZ);
                        return;
                    }
                    try {
                        if (error != null || chunk == null || !chunk.isLoaded()) {
                            failedChunks++;
                            return;
                        }
                        sweptChunks++;
                        removedEntities += purge(chunk, arena);
                    } catch (RuntimeException exception) {
                        failedChunks++;
                    } finally {
                        unloadQuietly(world, chunkX, chunkZ);
                        if (cursor >= queue.size() && inFlight == 0) {
                            finish();
                        }
                    }
                });
            });
        }

        private void finish() {
            if (done) {
                return;
            }
            done = true;
            if (generation == sweepGeneration && sweepTask != null) {
                sweepTask.cancel();
                sweepTask = null;
            }
            StringBuilder message = new StringBuilder("场地清场完成：扫描 ")
                    .append(sweptChunks).append(" 个区块，删除实体 ")
                    .append(removedEntities).append(" 个");
            if (failedChunks > 0) {
                message.append("（另有 ").append(failedChunks).append(" 个区块未能处理）");
            }
            plugin.getLogger().info(message.toString());
        }
    }

    // ------------------------------------------------------------------
    // 队列构建
    // ------------------------------------------------------------------

    /**
     * 构建待处理队列：<b>已加载的排前面</b>，其次是对局期间活动过的区块。
     *
     * <p>这个顺序是刻意的：已加载区块是玩家正看着的那一片，先清它们能让可见范围内的
     * 掉落物在最初几个 tick 内消失；而不需要加载/卸载的区块也优先处理，
     * 进一步减少对客户端的扰动。</p>
     */
    private List<long[]> buildQueue(World world, Region arena) {
        List<long[]> loadedFirst = new ArrayList<>();
        List<long[]> others = new ArrayList<>();

        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunkIntersectsArena(chunk.getX(), chunk.getZ(), arena)) {
                loadedFirst.add(new long[]{chunk.getX(), chunk.getZ()});
            }
        }
        for (long key : touchedChunks) {
            int x = (int) (key >> 32);
            int z = (int) key;
            if (world.isChunkLoaded(x, z)) {
                continue;// 已在 loadedFirst 里
            }
            if (!chunkIntersectsArena(x, z, arena)) {
                continue;
            }
            others.add(new long[]{x, z});
        }
        loadedFirst.addAll(others);
        return loadedFirst;
    }

    // ------------------------------------------------------------------
    // 区块枚举与坐标工具
    // ------------------------------------------------------------------

    /**
     * 区块的水平范围是否与场地相交。
     *
     * <p>只比水平：区块本身是整列的，而场地的 y 范围只影响“实体是否在场地内”，
     * 不影响“这个区块要不要看”。实体归属仍由 {@link #purge} 里的精确判定决定。</p>
     */
    private boolean chunkIntersectsArena(int chunkX, int chunkZ, Region arena) {
        int blockMinX = chunkX << 4;
        int blockMinZ = chunkZ << 4;
        int blockMaxX = blockMinX + 15;
        int blockMaxZ = blockMinZ + 15;
        return blockMaxX >= arena.minX() && blockMinX <= arena.maxX()
                && blockMaxZ >= arena.minZ() && blockMinZ <= arena.maxZ();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * 卸载区块，且不关心结果。
     *
     * <p>刻意吞掉所有异常：卸载失败（区块仍被占用/有 ticket）是我们<b>期望</b>的
     * 结果，绝不能让一次“清理收尾”因为一个区块卸载不掉而抛出去。</p>
     */
    private void unloadQuietly(World world, int chunkX, int chunkZ) {
        try {
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                // 只卸载本次扫荡自己加载的区块；被玩家/其它插件占用的会返回 false，
                // 保留即可——绝不能把玩家脚下的区块踢掉
                world.unloadChunk(chunkX, chunkZ, true);
            }
        } catch (RuntimeException ignored) {
            // 见方法说明：卸载失败不影响清场结果
        }
    }

    // ------------------------------------------------------------------
    // 实体清理
    // ------------------------------------------------------------------

    /** 清空一个区块里落在场地范围内的全部实体，返回删除数量。 */
    private int purge(Chunk chunk, Region arena) {
        int removed = 0;
        // 先取快照再删：chunk.getEntities() 返回的是内部数组，直接在上面删会并发修改
        Entity[] entities = chunk.getEntities();
        for (Entity entity : entities) {
            if (shouldKeep(entity)) {
                continue;
            }
            if (!arena.contains(entity.getLocation())) {
                continue;
            }
            entity.remove();
            removed++;
        }
        return removed;
    }

    /**
     * 判定实体是否应当保留。
     *
     * <p>两类：玩家本身（此时应已全部离开场地），以及 customanvil 插件的自定义铁砧
     * ——它是场地里的常驻设施，清掉会破坏玩家自己的布置。标签与 PDC 任一命中即算。</p>
     */
    private boolean shouldKeep(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        if (entity.getScoreboardTags().contains(ANVIL_TAG)) {
            return true;
        }
        PersistentDataContainer container = entity.getPersistentDataContainer();
        return container.has(new NamespacedKey("customanvil", "anvil_entity"), PersistentDataType.BYTE)
                || container.has(new NamespacedKey("customanvil", "anvil_partner"), PersistentDataType.STRING);
    }

    /**
     * 玩家坐标 → 覆盖 {@link #TRACK_RADIUS} 的区块编号范围。
     *
     * <p>收成一个小 record 而不是直接散落着算 min/max：{@code track()} 每秒调用一次，
     * 把“范围怎么算”单独表达出来，读者不必在三层循环里反推边界。</p>
     */
    private record Location2(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {

        static Location2 of(Player player) {
            int x = player.getLocation().getBlockX();
            int z = player.getLocation().getBlockZ();
            return new Location2(
                    (x - TRACK_RADIUS) >> 4, (x + TRACK_RADIUS) >> 4,
                    (z - TRACK_RADIUS) >> 4, (z + TRACK_RADIUS) >> 4);
        }
    }
}
