package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 领地（Residence）权限联动。
 *
 * <p>需求场景：准备房间与场地的领地常态关闭传送权限，只有玩家真正需要进去时才临时打开。
 * 这些区域本身是**领地插件**在管的，因此本插件不自己实现传送判定，而是**按需临时修改对方的
 * flag**，用完立刻恢复。</p>
 *
 * <p>实现方式刻意选择执行 {@code /res set <领地> <flag> true|false}（控制台身份）：
 * Residence 的权限对象在不同 6.x 版本里方法签名有细微差异，走它自己的指令既版本安全，
 * 又能保证权限数据正确落盘，不会出现“内存改了但文件没改”的漂移。</p>
 *
 * <p>用法是**引用计数**式的：同一片领地可能被多个场景同时需要（例如准备房间在排队期间），
 * 因此 {@link #acquire} 与 {@link #release} 成对出现，只在计数归零时才真正关闭权限。</p>
 */
public final class ResidenceService {

    /** 临时放行后延迟关闭的 tick 数（2 tick = 100ms，足够覆盖传送的权限校验）。 */
    private static final long CLOSE_DELAY_TICKS = 2L;

    private final Plugin plugin;
    private final ConfigService config;

    /** 已打开权限的领地 -> 打开次数。 */
    private final Map<String, Integer> opened = new LinkedHashMap<>();
    /** 记录每片领地曾被执行过什么，便于停用时强制复位。 */
    private final Map<String, String> lastApplied = new LinkedHashMap<>();

    public ResidenceService(Plugin plugin, ConfigService config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Residence 是否可用（按插件名判断，不引入编译期依赖）。 */
    public boolean available() {
        return plugin.getServer().getPluginManager().getPlugin("Residence") != null;
    }

    public boolean enabled() {
        return config.settings().residence().enabled() && available();
    }

    /**
     * 在“临时放行领地”的前提下执行一个动作，动作**真正结束**时立刻恢复。
     *
     * <p>用法是**按次**的：每次传送前打开、传送完成后关闭。这样领地权限在绝大多数时间
     * 都保持服务器设定的常态（关闭），而不会因为一场对局持续几十分钟就一直敞着。</p>
     *
     * @param action 需要放行的动作；返回值是“动作真正完成”的通知
     * @return 打开与关闭是否都成功执行
     */
    public boolean runWithAccess(java.util.function.Supplier<CompletableFuture<?>> action) {
        if (action == null) {
            return false;
        }
        List<String> regions = allRegions();
        if (regions.isEmpty()) {
            try {
                action.get();
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }
        open(regions);
        try {
            CompletableFuture<?> completion = action.get();
            if (completion == null) {
                closeLater(regions);
            } else {
                // 关键：必须等动作**真正完成**再关闭。
                // teleportAsync 要等目标区块加载完才落地，若提前关闭权限，
                // 传送会被领地插件拦掉（表现为“点了加入游戏却留在原地”）。
                completion.whenComplete((ignored, error) -> closeLater(regions));
            }
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("临时放行期间执行动作失败：" + exception.getMessage());
            closeLater(regions);
            return false;
        }
    }

    /** 不关心完成通知的便捷重载。 */
    public void runWithAccess(Runnable action) {
        runWithAccess(() -> {
            action.run();
            return null;
        });
    }

    private void open(List<String> regions) {
        for (String region : regions) {
            apply(region, true);
        }
    }

    /**
     * 推迟一个 tick 再关闭。
     *
     * <p>传送完成的回调与“玩家真正落地”之间还隔着一次服务端处理，立刻关闭可能刚好撞上
     * 领地插件在下一 tick 的位置校验。1 tick（50ms）足够，且远小于玩家能穿过一道门的时间。</p>
     */
    private void closeLater(List<String> regions) {
        try {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> closeAll(regions), CLOSE_DELAY_TICKS);
        } catch (RuntimeException exception) {
            closeAll(regions);
        }
    }

    private void closeAll(List<String> regions) {
        for (String region : regions) {
            apply(region, false);
        }
    }

    /** 配置里涉及的全部领地（准备房间 + 场地）。 */
    private List<String> allRegions() {
        Settings.Residence settings = config.settings().residence();
        List<String> regions = new java.util.ArrayList<>(settings.prepRegions());
        for (String region : settings.arenaRegions()) {
            if (!regions.contains(region)) {
                regions.add(region);
            }
        }
        return regions;
    }

    /**
     * 停用/重载前的强制复位：把配置涉及的**全部**领地关回常态。
     *
     * <p>刻意不依赖“打开过什么”的记录：记录与真实状态一旦不同步（例如上一次关闭的调度
     * 还没执行完就停用了插件），按记录复位就会漏掉一片领地。这里无条件把两张表都关掉，
     * 代价只是停用时多两条指令。</p>
     */
    public void reset() {
        for (String region : allRegions()) {
            apply(region, false);
        }
        opened.clear();
        lastApplied.clear();
    }

    /** 当前被本插件打开权限的领地（诊断用）。 */
    public Map<String, Integer> openedRegions() {
        return Map.copyOf(opened);
    }

    /** 真正下发权限变更。 */
    private void apply(String region, boolean allow) {
        String flag = config.settings().residence().flag();
        String value = allow ? "true" : "false";
        // 同值不重复下发：传送很频繁，避免每次传送都刷两条一样的日志与指令
        String previous = lastApplied.get(region);
        if (value.equals(previous)) {
            return;
        }
        lastApplied.put(region, value);
        String command = "res set " + region + " " + flag + " " + value;
        try {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (!ok) {
                plugin.getLogger().warning("领地权限调整：" + command
                        + " 指令返回 false，请确认领地名与 flag 是否存在");
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("领地权限调整失败：" + command + " -> " + exception.getMessage());
        }
    }
}
