package cn.mgtown.fmwar.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * 全项目唯一的调度入口：一律走 Paper 提供的四类调度器。
 *
 * <p><b>为什么不能再直接用 {@code Bukkit.getScheduler()}</b>：传统 {@code BukkitScheduler}
 * 在 Folia 上会直接抛 {@code UnsupportedOperationException}，插件连启用都做不到。
 * Paper 为此提供了四类区域化调度器，本类把它们收敛成一组语义明确的辅助方法，
 * 让业务代码不再关心“这段逻辑该跑在哪个线程上”。</p>
 *
 * <table border="1">
 *   <caption>四类调度器与对应的辅助方法</caption>
 *   <tr><th>调度器</th><th>方法</th><th>跑在哪个线程</th><th>适用场景</th></tr>
 *   <tr>
 *     <td>{@code GlobalRegionScheduler}</td>
 *     <td>{@link #onMain} / {@link #onMainLater} / {@link #mainTimer}</td>
 *     <td>Paper：主线程；Folia：全局区域线程（都是单线程，每 tick 推进）</td>
 *     <td>插件的“权威线程”——所有跨玩家的状态机逻辑、没有实体归属的定时任务</td>
 *   </tr>
 *   <tr>
 *     <td>{@code RegionScheduler}</td>
 *     <td>{@link #onRegion} / {@link #runOwned}</td>
 *     <td>拥有该区块/坐标的<b>区域线程</b></td>
 *     <td>读写方块、区块与坐标相关的世界操作</td>
 *   </tr>
 *   <tr>
 *     <td>{@code EntityScheduler}</td>
 *     <td>{@link #onEntity} / {@link #onEntityLater} / {@link #runOwned}</td>
 *     <td>拥有该实体/玩家的区域线程</td>
 *     <td>改动玩家自身状态（背包、游戏模式、血量、记分板、重生）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code AsyncScheduler}</td>
 *     <td>{@link #async}</td>
 *     <td>独立线程池</td>
 *     <td>纯 CPU/IO，<b>禁止</b>触碰世界与实体</td>
 *   </tr>
 * </table>
 *
 * <p>在 Paper（非 Folia）上，前三类调度器都由服务端退化为“主线程执行”，
 * 因此本类的语义与改造前的 {@code runTask*} 完全一致；只有 Folia 上才真正分流。</p>
 */
public final class Schedulers {

    /** Folia 的“区域化服务端”标记类；存在即说明是 Folia。 */
    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    private final Plugin plugin;
    private final boolean folia;

    public Schedulers(Plugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
    }

    private static boolean detectFolia() {
        try {
            Class.forName(FOLIA_MARKER);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** 当前服务端是否为 Folia（区域化多线程）。 */
    public boolean folia() {
        return folia;
    }

    // ------------------------------------------------------------------
    // 线程归属判定
    // ------------------------------------------------------------------

    /**
     * 当前线程是否为<b>权威线程</b>（Paper 主线程 / Folia 全局区域线程）。
     *
     * <p>本插件的全部对局状态（{@code GameEngine} 的字段）只允许权威线程改动。事件回调与
     * 指令在 Folia 上跑在“玩家所属区域线程”，与权威线程<b>并发</b>，因此每个从外部进来的
     * 入口都要先用本方法判定。</p>
     *
     * <p>Paper 上该方法等价于 {@code isPrimaryThread()}，判定恒为真，于是下面那层守卫
     * 在 Paper 上不产生任何行为差异——这正是“改造后 Paper 行为不变”的依据。</p>
     */
    public boolean authoritative() {
        return Bukkit.isGlobalTickThread();
    }

    /**
     * 入口守卫：不在权威线程就把整个动作重排到权威线程（下一 tick 执行），并返回
     * {@code false}。
     *
     * <p>调用方拿到 {@code false} <b>必须立即 return</b>：动作已经在权威线程上重新进入
     * 了一次，继续往下走就会在错误的线程上改状态。</p>
     *
     * <pre>{@code
     * public void onClick(Player player) {
     *     if (!schedulers.guardAuthoritative(() -> onClick(player))) {
     *         return;   // 已重排，本次调用到此为止
     *     }
     *     ... 真正改状态的逻辑 ...
     * }
     * }</pre>
     *
     * @return 当前已在权威线程时返回 true（调用方继续执行）；已重排时返回 false
     */
    public boolean guardAuthoritative(Runnable action) {
        if (authoritative()) {
            return true;
        }
        onMain(action);
        return false;
    }

    /** 当前线程是否拥有该实体（可以安全地改它的背包/游戏模式/血量等自身状态）。 */
    public boolean owns(Entity entity) {
        return entity != null && Bukkit.isOwnedByCurrentRegion(entity);
    }

    /** 当前线程是否拥有该区块（可以安全地读写它的方块与实体）。 */
    public boolean owns(World world, int chunkX, int chunkZ) {
        return world != null && Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    /** 当前线程是否拥有该坐标所在的区块。 */
    public boolean owns(Location location) {
        return location != null && location.getWorld() != null
                && Bukkit.isOwnedByCurrentRegion(location);
    }

    // ------------------------------------------------------------------
    // 权威线程（GlobalRegionScheduler）
    // ------------------------------------------------------------------

    /**
     * 在权威线程上执行，下一 tick 生效。
     *
     * <p>等价于改造前的 {@code runTask(plugin, task)}。注意 {@code GlobalRegionScheduler}
     * 的最小延迟是 1 tick，因此本方法<b>不会</b>同步执行——需要“立刻执行”的场合请直接调用。</p>
     */
    public void onMain(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    /** 延迟若干 tick 后在权威线程执行。{@code delayTicks <= 0} 会被钳到 1。 */
    public void onMainLater(Runnable task, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(),
                Math.max(1L, delayTicks));
    }

    /**
     * 在权威线程上跑一个固定周期任务。
     *
     * <p>等价于改造前的 {@code runTaskTimer(plugin, task, initialDelayTicks, periodTicks)}。</p>
     */
    public ScheduledTask mainTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(),
                Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
    }

    // ------------------------------------------------------------------
    // 区域线程（RegionScheduler）
    // ------------------------------------------------------------------

    /**
     * 在拥有该区块的区域线程上执行。
     *
     * <p>Folia 上从其它线程读写方块是非法的；凡是要动“某个区块里的世界内容”，
     * 都必须走这里，而不是 {@link #onMain}。</p>
     */
    public void onRegion(World world, int chunkX, int chunkZ, Runnable task) {
        if (world == null) {
            onMain(task);
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    /** 在拥有该坐标的区域线程上执行；坐标或世界缺失时退回权威线程。 */
    public void onRegion(Location location, Runnable task) {
        if (location == null || location.getWorld() == null) {
            onMain(task);
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    /**
     * 在拥有该区块的区域线程上执行；<b>已经拥有就地执行</b>。
     *
     * <p>与 {@link #onRegion} 的区别是“能不能就地做”。{@code onRegion} 一律排进队列、
     * 下一 tick 才跑；而方块读写类收尾（放箱子、清箱子）在 Paper 上一直是在当前 tick
     * 同步做完的，改成延迟一 tick 会让“开局就有箱子”这类行为可见地变样。
     * 因此这些场合一律用本方法：Paper 上就地、行为不变，Folia 上才真正派发。</p>
     */
    public void runOwned(World world, int chunkX, int chunkZ, Runnable task) {
        if (owns(world, chunkX, chunkZ)) {
            task.run();
            return;
        }
        onRegion(world, chunkX, chunkZ, task);
    }

    /** 在拥有该坐标的区域线程上执行；已经拥有就地执行。 */
    public void runOwned(Location location, Runnable task) {
        if (owns(location)) {
            task.run();
            return;
        }
        onRegion(location, task);
    }

    // ------------------------------------------------------------------
    // 实体线程（EntityScheduler）
    // ------------------------------------------------------------------

    /**
     * 在拥有该实体的区域线程上执行。
     *
     * <p>改动玩家自身状态的唯一合法姿势：背包、游戏模式、血量、记分板、重生等，
     * 在 Folia 上从别的线程调用会抛异常或造成数据错乱。</p>
     *
     * @param retired 实体已失效（离线/被移除）时的兜底动作；为 null 表示直接丢弃
     */
    public void onEntity(Entity entity, Runnable task, Runnable retired) {
        if (entity == null || !entity.isValid()) {
            if (retired != null) {
                retired.run();
            }
            return;
        }
        Runnable fallback = retired == null ? () -> { } : retired;
        entity.getScheduler().execute(plugin, task, fallback, 1L);
    }

    /** 延迟若干 tick 后在实体所属线程执行；实体失效时执行 {@code retired}。 */
    public void onEntityLater(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        if (entity == null || !entity.isValid()) {
            if (retired != null) {
                retired.run();
            }
            return;
        }
        entity.getScheduler().runDelayed(plugin, ignored -> task.run(),
                retired == null ? () -> { } : retired, Math.max(1L, delayTicks));
    }

    /**
     * 在拥有该实体的区域线程上执行；<b>已经拥有就地执行</b>。
     *
     * <p>与 {@link #onEntity} 的区别是“能不能就地做”。{@link #onEntity} 一律延后到下一
     * tick，适合“刻意要等一 tick 再动”的场合（例如死亡后从死亡界面拉回来）；
     * 而清背包、改游戏模式、改血量这类操作<b>改造前就是在当前 tick 同步做的</b>，
     * 必须用本方法保持时序——Paper 上就地执行，Folia 上才派发到该玩家所属线程。</p>
     *
     * @param retired 实体已失效（离线/被移除）时的兜底动作；为 null 表示直接丢弃
     */
    public void runOwned(Entity entity, Runnable task, Runnable retired) {
        if (entity == null || !entity.isValid()) {
            if (retired != null) {
                retired.run();
            }
            return;
        }
        if (owns(entity)) {
            task.run();
            return;
        }
        entity.getScheduler().execute(plugin, task, retired == null ? () -> { } : retired, 1L);
    }

    // ------------------------------------------------------------------
    // 异步线程（AsyncScheduler）
    // ------------------------------------------------------------------

    /**
     * 在异步线程池上执行。
     *
     * <p><b>只能做纯计算/IO</b>：任何对世界、区块、实体的访问都必须在回到
     * {@link #onMain} / {@link #onRegion} / {@link #onEntity} 之后再做。</p>
     */
    public void async(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }
}
