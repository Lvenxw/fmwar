package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Region;
import cn.mgtown.fmwar.util.Schedulers;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * <p><b>线程模型</b>：驱动游标（{@link SweepStep#run()}）跑在插件权威线程
 * （{@link Schedulers#onMain}，Paper 上即主线程，Folia 上是全局区域线程）；
 * 真正的区块清理派发到 {@link Schedulers#onRegion}，即<b>拥有该区块的区域线程</b>——
 * Folia 上从别的线程直接读方块/实体是非法的。因此统计计数器与收尾标志全部改用原子类型，
 * 它们会被多个区域线程并发写入。</p>
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
    private final Schedulers schedulers;

    /**
     * 对局期间活动过的区块（打包成 long 作为键）。
     *
     * <p>用 {@link LinkedHashSet} 而非 {@code HashSet}：结算时按插入顺序处理，
     * 于是“先被加载过的区块”先被清理，行为可预期。</p>
     *
     * <p>只在权威线程上读写（{@link #track()} 由引擎 tick 调用、{@link #buildQueue}
     * 在 {@link #start()} 里调用），因此刻意<b>不</b>做并发保护。</p>
     */
    private final Set<Long> touchedChunks = new LinkedHashSet<>();

    /** 正在进行的分批扫荡任务；同一时间只允许一个。跨线程读写，故 volatile。 */
    private volatile ScheduledTask sweepTask;
    /**
     * 当前扫荡的“世代”标记。
     *
     * <p>存在它的唯一理由：{@link #cancel()} 只能取消定时任务，**无法取消已经发出的
     * 区块加载回调**。这些回调仍会在稍后回到某个区域线程，若不加以识别，它们会
     * ① 用新一轮的计数器累加出错误统计，② 调 {@code finish()} 把<b>新一轮</b>的
     * 任务也置空，导致新一轮扫荡被静默掐断。每个 {@link SweepStep} 出生时领一个
     * 世代号，回来时先核对身份，不是自己这一轮就直接放弃。</p>
     *
     * <p>跨线程读（区域线程返回时核对），故 volatile。</p>
     */
    private volatile long sweepGeneration;
    /** 以下三个计数器会被多个区域线程并发写入，必须原子。 */
    private final AtomicInteger sweptChunks = new AtomicInteger();
    private final AtomicInteger removedEntities = new AtomicInteger();
    private final AtomicInteger failedChunks = new AtomicInteger();

    public ArenaCleaner(Plugin plugin, ConfigService config, Schedulers schedulers) {
        this.plugin = plugin;
        this.config = config;
        this.schedulers = schedulers;
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
        sweptChunks.set(0);
        removedEntities.set(0);
        failedChunks.set(0);

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
        // 下一 tick 才开始，且每 tick 只处理 budget 个：权威线程不再被清场占用。
        // 驱动用 GlobalRegionScheduler（单线程、每 tick 推进），单个区块的清理
        // 再由 SweepStep 派发到该区块所属的区域线程——见类文档的“线程模型”。
        long generation = ++sweepGeneration;
        SweepStep step = new SweepStep(world, arena, queue, budget, generation);
        sweepTask = schedulers.mainTimer(step, 1L, 1L);
    }

    /**
     * 同步清场，只处理已加载区块。
     *
     * <p>供插件停用等<b>不能再调度异步任务</b>的场合使用。此时不能临时加载区块
     * （加载后无人卸载，且停用过程中调度任务会抛异常）。未加载区块的残留留到下次
     * 正常结算时再清——服务器停机时这些区块本来就还在磁盘上。</p>
     *
     * <p><b>Folia 上直接跳过</b>：枚举 {@code world.getLoadedChunks()} 与随后逐区块的
     * {@code chunk.getEntities()} / {@code entity.remove()} 都是跨区域访问，在全局线程上
     * 做不了——而这条路径只由停用触发，代价是停用时清理不生效，残留留到下次正常结算。</p>
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
        if (schedulers.folia()) {
            plugin.getLogger().info("场地清场（仅已加载区块）：Folia 上无法在停用路径枚举已加载区块，"
                    + "已跳过；残留实体留待下次正常结算时清理");
            return;
        }
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
     * <p>{@link #run()} 由 {@link Schedulers#mainTimer} 在权威线程上每 tick 调一次，
     * 因此游标 {@code cursor} 与队列都是<b>单线程</b>访问；真正的区块清理则由
     * {@code process()} 派发到该区块所属的<b>区域线程</b>执行，因此
     * {@code inFlight} 与 {@code done} 会被多个线程并发读写，必须原子。</p>
     */
    private final class SweepStep implements Runnable {

        private final World world;
        private final Region arena;
        private final List<long[]> queue;
        private final int budget;
        /** 本轮身份；用于识别“已被 cancel 掉的那一轮”的迟到回调。 */
        private final long generation;
        /** 只由权威线程推进；区域线程会读，故 volatile。 */
        private volatile int cursor;
        /** 已派发、尚未完成的区块数；被多个区域线程并发归还，原子。 */
        private final AtomicInteger inFlight = new AtomicInteger();
        /** 本轮是否已收尾，避免 {@code finish()} 被调两次而重复打日志。 */
        private final AtomicBoolean done = new AtomicBoolean();

        SweepStep(World world, Region arena, List<long[]> queue, int budget, long generation) {
            this.world = world;
            this.arena = arena;
            this.queue = queue;
            this.budget = budget;
            this.generation = generation;
        }

        @Override
        public void run() {
            if (generation != sweepGeneration || done.get()) {
                return;
            }
            // 先加 inFlight 再推 cursor：这样“inFlight 归零”的观察者一定能看到完整的
            // cursor，不会在仍有待派发区块时误判“已全部完成”
            while (inFlight.get() < budget && cursor < queue.size()) {
                inFlight.incrementAndGet();
                long[] coord = queue.get(cursor);
                cursor++;
                process((int) coord[0], (int) coord[1]);
            }
            if (cursor >= queue.size() && inFlight.get() == 0) {
                finish();
            }
        }

        /**
         * 处理单个区块：已加载的直接清；未加载的临时加载 → 清 → 卸载。
         *
         * <p><b>调用约定</b>：{@link #run()} 在调用本方法前已把 {@code inFlight} 加一。
         * 三条出口都必须经由 {@link #onComplete()} 归还额度——否则计数器只增不减，
         * 扫到第 {@code budget} 个区块后 {@code while (inFlight < budget)} 永远为假，
         * 整个扫荡就此卡死、{@link #finish()} 也不会被调用（连收尾日志都没有）。</p>
         *
         * <p>清理动作一律派发到 {@link Schedulers#onRegion}：Folia 上只有拥有该区块的
         * 区域线程才能安全地读写它的方块与实体。</p>
         *
         * <p>{@code generate=false} 是硬性要求：万一区块在这段时间被删除，
         * 也不能因此生成新区块。</p>
         */
        private void process(int chunkX, int chunkZ) {
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                // 已加载：派发到该区块所属区域线程就地清理，不涉及加载/卸载，不会惊动客户端
                schedulers.onRegion(world, chunkX, chunkZ, () -> {
                    try {
                        if (generation != sweepGeneration || done.get()) {
                            return;
                        }
                        // 派发与真正执行之间区块可能已被卸载。此时绝不能走 getChunkAt
                        //（它会同步生成区块）——那正是本类禁止的事，直接跳过。
                        if (!world.isChunkLoaded(chunkX, chunkZ)) {
                            return;
                        }
                        sweptChunks.incrementAndGet();
                        removedEntities.addAndGet(purge(world.getChunkAt(chunkX, chunkZ), arena));
                    } catch (RuntimeException exception) {
                        failedChunks.incrementAndGet();
                    } finally {
                        onComplete();
                    }
                });
                return;
            }
            if (!world.isChunkGenerated(chunkX, chunkZ)) {
                // 从未生成过：里面不可能有掉落物，也不该为清场去生成它
                onComplete();
                return;
            }
            world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) ->
                    // 加载回调不保证线程：统一回到该区块所属的区域线程再动它
                    schedulers.onRegion(world, chunkX, chunkZ, () -> {
                        try {
                            // 本轮已被取消/替换：区块已经加载上来了，但不再做后续处理。
                            // 仍要卸载它，否则就是一次纯泄漏。
                            if (generation != sweepGeneration || done.get()) {
                                return;
                            }
                            if (error != null || chunk == null || !chunk.isLoaded()) {
                                failedChunks.incrementAndGet();
                                return;
                            }
                            sweptChunks.incrementAndGet();
                            removedEntities.addAndGet(purge(chunk, arena));
                        } catch (RuntimeException exception) {
                            failedChunks.incrementAndGet();
                        } finally {
                            unloadQuietly(world, chunkX, chunkZ);
                            onComplete();
                        }
                    }));
        }

        /** 归还一份在途额度；全部派发完毕且全部完成时收尾。 */
        private void onComplete() {
            if (inFlight.decrementAndGet() > 0) {
                return;
            }
            if (cursor >= queue.size()) {
                finish();
            }
        }

        private void finish() {
            // 已被 cancel / 被新一轮替换的旧轮不许收尾：否则会用新一轮的计数器
            // 打出一条属于旧轮的“清场完成”日志
            if (generation != sweepGeneration) {
                return;
            }
            if (!done.compareAndSet(false, true)) {
                return;
            }
            ScheduledTask task = sweepTask;
            if (task != null) {
                task.cancel();
                sweepTask = null;
            }
            StringBuilder message = new StringBuilder("场地清场完成：扫描 ")
                    .append(sweptChunks.get()).append(" 个区块，删除实体 ")
                    .append(removedEntities.get()).append(" 个");
            int failed = failedChunks.get();
            if (failed > 0) {
                message.append("（另有 ").append(failed).append(" 个区块未能处理）");
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
     *
     * <p><b>Folia 上没有“已加载优先”这一层。</b>枚举已加载区块（{@code getLoadedChunks()}）
     * 本身就是一次跨区域的区块访问，全局线程做不了；此时退回“只按对局期间活动过的名单
     * 清理”——这本来就是本类的核心设计，掉落物只可能出现在那批区块里。
     * 换句话说 Folia 上少掉的只是排序优化，不漏清理范围。</p>
     */
    private List<long[]> buildQueue(World world, Region arena) {
        List<long[]> loadedFirst = new ArrayList<>();
        List<long[]> others = new ArrayList<>();

        boolean canEnumerateLoaded = !schedulers.folia();
        if (canEnumerateLoaded) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (chunkIntersectsArena(chunk.getX(), chunk.getZ(), arena)) {
                    loadedFirst.add(new long[]{chunk.getX(), chunk.getZ()});
                }
            }
        }
        for (long key : touchedChunks) {
            int x = (int) (key >> 32);
            int z = (int) key;
            // 只有真的枚举过已加载区块时才能用“已加载”去重；Folia 上没有那份名单，
            // 这里若照样跳过，这些区块就会既不在 loadedFirst 也不在 others 里，被整批漏掉。
            if (canEnumerateLoaded && world.isChunkLoaded(x, z)) {
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
