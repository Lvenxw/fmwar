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
import java.util.List;

/**
 * 场地清场：清掉场地范围内的所有实体，**包括未加载区块里的**。
 *
 * <p><b>为什么不能只用 {@code world.getEntities()}</b>：该方法只返回**已加载区块**中的实体。
 * 而 {@code regions.arena} 在当前配置下横跨约 1260 个区块（x 461 格 × z 653 格，
 * 每列 30 × 整行 42 个区块），玩家在对局中只让其中靠近自己的一小部分保持加载。
 * 于是结算时 {@code getEntities()} 看到的只是冰山一角——落在远处区块里的掉落物
 * 会一直留在区块存档里，下一局继续堆。这也解释了“清场看着执行了，地上却还有东西”。</p>
 *
 * <p><b>为什么不改成开局强制加载整片场地</b>：那要同时常驻 1260 个区块，代价是
 * 1.5~5 GB 内存、1260 个区块的光照重算，以及每 tick 的实体/方块扫描；
 * 而且加的区块票在游戏结束后不会自动消失。这比残留几个掉落物贵得多。
 * 详见 {@code docs/adr/0005-*.md}。</p>
 *
 * <p><b>这里的做法</b>：结算时把场地范围内<b>已生成但未加载</b>的区块分批临时加载，
 * 删掉其中的实体后立即卸载还原。每次只处理 {@code cleanup.chunks-per-tick} 个区块，
 * 开销被摊平到若干 tick 上，不会造成结算瞬间的长卡顿；并且总量只与
 * “真的已生成过、且真的可能有掉落物”的区块数相关，而不是整片场地。</p>
 *
 * <p>三条关键约束：</p>
 * <ul>
 *   <li><b>绝不生成新区块</b>：只处理 {@code isChunkGenerated} 为真的区块。
 *       否则“为清掉落物而生成地形”，会在世界上凭空造出新区块。</li>
 *   <li><b>加载前后状态还原</b>：清完只卸载<b>本次自己加载的</b>区块，
 *       本来就在加载中的（含玩家所在）保持不动，避免把玩家的区块踢掉。</li>
 *   <li><b>排除玩家与自定义铁砧</b>：与原实现一致，见 {@link #shouldKeep}。</li>
 * </ul>
 */
public final class ArenaCleaner {

    /** 自定义铁砧（customanvil 插件）的记分板标签，属于场地常驻设施，不该清。 */
    private static final String ANVIL_TAG = "customanvil";

    private final Plugin plugin;
    private final ConfigService config;

    /** 正在进行的分批扫荡任务；同一时间只允许一个。 */
    private BukkitTask sweepTask;
    /**
     * 当前扫荡的“世代”标记。
     *
     * <p>存在它的唯一理由：{@code cancel()} 只能取消定时任务，**无法取消已经发出的
     * 区块加载回调**。这些回调仍会在稍后回到主线程，若不加以识别，它们会
     * ① 用新一轮的计数器累加出一份错误的统计，② 调用 {@code finish()} 把<b>新一轮</b>的
     * 任务也置空，导致新一轮扫荡被静默掐断。因此每个 {@link SweepStep} 出生时领一个
     * 世代号，回来时先核对身份，不是自己这一轮就直接放弃。</p>
     */
    private long sweepGeneration;
    /** 本轮已处理的区块数（仅用于收尾日志）。 */
    private int sweptChunks;
    /** 本轮删除的实体数（仅用于收尾日志）。 */
    private int removedEntities;
    /** 本轮因各种原因未能处理的区块数（仅用于收尾日志）。 */
    private int failedChunks;

    public ArenaCleaner(Plugin plugin, ConfigService config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * 立即启动清场：已加载区块同步清，未加载区块分批异步清。
     *
     * <p>同步部分保证“玩家眼前的掉落物这一 tick 就没了”，异步部分保证覆盖面。
     * 重复调用是安全的——上一轮未完成的扫荡会被取消，然后从头再来。</p>
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

        // 1) 已加载区块：同步清。这一步不能省——玩家正看着场地，
        //    让他们看到“清场跑了但脚边的火药还在”比多花几毫秒更糟。
        List<Chunk> loaded = loadedChunksInArena(world, arena);
        int syncRemoved = 0;
        for (Chunk chunk : loaded) {
            syncRemoved += purge(chunk, arena);
            sweptChunks++;
        }

        // 2) 已生成但未加载的区块：分批处理
        List<long[]> pending = unloadedGeneratedChunksInArena(world, arena);
        if (pending.isEmpty()) {
            plugin.getLogger().info("场地清场完成：扫描 " + sweptChunks + " 个区块，删除实体 "
                    + syncRemoved + " 个");
            return;
        }
        if (!config.settings().cleanup().enabled()) {
            plugin.getLogger().warning("场地内还有 " + pending.size()
                    + " 个未加载区块可能残留掉落物，但 cleanup.enabled=false 已关闭分批清场。"
                    + "开启后可一并清理（注意会临时加载这些区块）");
            return;
        }

                int budget = config.settings().cleanup().safeChunksPerTick();
        plugin.getLogger().info("场地清场：已同步清理 " + sweptChunks + " 个已加载区块（删除实体 "
                + syncRemoved + " 个），另有 " + pending.size() + " 个未加载区块将分批处理"
                + "（每 tick " + budget + " 个）");
        long generation = ++sweepGeneration;
        sweepTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, new SweepStep(world, arena, pending, budget, generation), 1L, 1L);
    }

    /**
     * 同步清场，只处理已加载区块。
     *
     * <p>供插件停用等<b>不能再调度异步任务</b>的场合使用：此时不能临时加载区块
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
        for (Chunk chunk : loadedChunksInArena(world, arena)) {
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
     * 每次领取 {@code budget} 个区块处理，处理完全部后自行取消任务并打收尾日志。
     * 用独立类而不是 lambda 内的可变数组，是为了避免把索引藏进可变的数组元素里
     * 这种读起来别扭的写法。</p>
     */
    private final class SweepStep implements Runnable {

        private final World world;
        private final Region arena;
        private final List<long[]> pending;
        private final int budget;
        /** 本轮身份；用于识别“已经被cancel 掉的那一轮”的迟到回调。 */
        private final long generation;
        private int cursor;
        /** 已经发起、等待回调完成的区块数。 */
        private int inFlight;
        /** 本轮是否已收尾，用于避免 finish() 被调用两次而重复打日志。 */
        private boolean done;

        SweepStep(World world, Region arena, List<long[]> pending, int budget, long generation) {
            this.world = world;
            this.arena = arena;
            this.pending = pending;
            this.budget = budget;
            this.generation = generation;
        }

        @Override
        public void run() {
            if (generation != sweepGeneration || done) {
                return;
            }
            // 异步加载回调可能跨 tick 回来，因此限制同时在途的数量，
            // 否则一次会把上百个区块全部拉起来
            while (inFlight < budget && cursor < pending.size()) {
                long[] coord = pending.get(cursor++);
                inFlight++;
                loadAndPurge((int) coord[0], (int) coord[1]);
            }
            if (cursor >= pending.size() && inFlight == 0) {
                finish();
            }
        }

        /**
         * 临时加载一个区块、清空其实体、卸载还原。
         *
         * <p>{@code generate=false} 是硬性要求：万一区块在这段时间被删除，
         * 也不能因此生成新区块。</p>
         */
        private void loadAndPurge(int chunkX, int chunkZ) {
            world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
                // 回到主线程再动区块/实体：加载回调不保证线程
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    inFlight--;
                    // 本轮已被取消/替换：区块已经加载上来了，但不再做后续处理。
                    // 仍要把它卸载掉，否则就是一次纯泄漏。
                    if (generation != sweepGeneration || done) {
                        if (chunk != null && chunk.isLoaded()) {
                            world.unloadChunk(chunkX, chunkZ, true);
                        }
                        return;
                    }
                    try {
                        if (error != null || chunk == null || !chunk.isLoaded()) {
                            failedChunks++;
                            return;
                        }
                        sweptChunks++;
                        removedEntities += purge(chunk, arena);
                    } finally {
                        // 只卸载自己加载的区块。若这块在我们处理期间被玩家或
                        // 其它插件用到，unloadChunk 会返回 false——那正是我们要的：
                        // 宁可留着，也绝不把玩家脚下的区块踢掉。
                        if (chunk != null && chunk.isLoaded()) {
                            world.unloadChunk(chunkX, chunkZ, true);
                        }
                        if (cursor >= pending.size() && inFlight == 0) {
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
    // 区块枚举
    // ------------------------------------------------------------------

    /**
     * 场地范围内当前已加载的区块。
     *
     * <p>遍历的是<b>世界已加载区块</b>，而不是把场地矩形铺满再逐个
     * {@code isChunkLoaded}——后者在这里要问 1260 次，前者只需问世界实际加载了多少个。</p>
     */
    private List<Chunk> loadedChunksInArena(World world, Region arena) {
        List<Chunk> result = new ArrayList<>();
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunkIntersectsArena(chunk.getX(), chunk.getZ(), arena)) {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 场地范围内<b>已生成但未加载</b>的区块坐标。
     *
     * <p>{@code isChunkGenerated} 过滤掉从未生成过的区块：那些区块里不可能存在
     * 掉落物，为它们去加载只会白白制造新区块。</p>
     */
    private List<long[]> unloadedGeneratedChunksInArena(World world, Region arena) {
        List<long[]> result = new ArrayList<>();
        int minX = (int) Math.floor(arena.minX()) >> 4;
        int maxX = (int) Math.floor(arena.maxX()) >> 4;
        int minZ = (int) Math.floor(arena.minZ()) >> 4;
        int maxZ = (int) Math.floor(arena.maxZ()) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (world.isChunkLoaded(x, z) || !world.isChunkGenerated(x, z)) {
                    continue;
                }
                result.add(new long[]{x, z});
            }
        }
        return result;
    }

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

    // ------------------------------------------------------------------
    // 实体清理
    // ------------------------------------------------------------------

    /** 清空一个区块里落在场地范围内的全部实体，返回删除数量。 */
    private int purge(Chunk chunk, Region arena) {
        int removed = 0;
        // 先取快照再删：chunk.getEntities() 返回的是内部数组，直接在上面删会并发修改
        for (Entity entity : new ArrayList<>(java.util.Arrays.asList(chunk.getEntities()))) {
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
}