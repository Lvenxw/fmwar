package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 在“临时放行领地”的前提下执行一个动作，动作结束立刻恢复。
     *
     * <p>用法是**按次**的：每次传送前打开、传送完成即关闭。这样领地权限在绝大多数时间
     * 都保持服务器设定的常态（关闭），而不会因为一场对局持续几十分钟就一直敞着。</p>
     *
     * @param action 需要放行的动作（通常是传送）
     */
    public void runWithAccess(Runnable action) {
        if (action == null) {
            return;
        }
        List<String> regions = allRegions();
        if (regions.isEmpty()) {
            action.run();
            return;
        }
        for (String region : regions) {
            apply(region, true);
        }
        // 单个 tick 内不要立刻关闭：传送（尤其是异步传送）的权限校验可能在本 tick
        // 稍后才执行，立刻关闭会让它刚好撞上“已关闭”。延迟 2 tick 再恢复，
        // 间隔远小于玩家能穿过一道门的时间。
        boolean delayed = scheduleClose(regions);
        if (!delayed) {
            // 调度器不可用（极端情况）时至少不要留下敞开的权限
            closeAll(regions);
        }
    }

    /** 延迟关闭；返回是否成功排入调度。 */
    private boolean scheduleClose(List<String> regions) {
        try {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> closeAll(regions), CLOSE_DELAY_TICKS);
            return true;
        } catch (RuntimeException exception) {
            return false;
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
     * 立即放行全部领地（停用/复位前不使用；仅保留给极端场景）。
     *
     * @deprecated 改用 {@link #runWithAccess(Runnable)}，避免权限长时间敞开
     */
    @Deprecated
    public void acquire(String region) {
        if (region == null || region.isBlank() || !enabled()) {
            return;
        }
        int count = opened.merge(region, 1, Integer::sum);
        if (count == 1) {
            apply(region, true);
        }
    }

    /** 释放一次申请；计数归零时关闭权限。 */
    public void release(String region) {
        if (region == null || region.isBlank() || !enabled()) {
            return;
        }
        Integer count = opened.get(region);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            opened.remove(region);
            apply(region, false);
        } else {
            opened.put(region, count - 1);
        }
    }

    /**
     * 进入某个场景：按配置把该场景涉及的领地全部打开。
     *
     * @deprecated 改用 {@link #runWithAccess(Runnable)}；常驻打开会让权限在对局期间一直敞开
     */
    @Deprecated
    public void enter(String context) {
        for (String region : regionsFor(context)) {
            acquire(region);
        }
    }

    /** 离开某个场景：把该场景涉及的领地全部释放。 */
    public void exit(String context) {
        for (String region : regionsFor(context)) {
            release(region);
        }
    }

    /** 停用/重载前的强制复位：关闭所有仍被本插件打开的权限。 */
    public void reset() {
        for (String region : List.copyOf(opened.keySet())) {
            opened.remove(region);
            apply(region, false);
        }
        opened.clear();
    }

    /** 当前被本插件打开权限的领地（诊断用）。 */
    public Map<String, Integer> openedRegions() {
        return Map.copyOf(opened);
    }

    private List<String> regionsFor(String context) {
        Settings.Residence settings = config.settings().residence();
        return switch (context) {
            case "prep" -> settings.prepRegions();
            case "arena" -> settings.arenaRegions();
            default -> List.of();
        };
    }

    /** 真正下发权限变更。 */
    private void apply(String region, boolean allow) {
        String flag = config.settings().residence().flag();
        String value = allow ? "true" : "false";
        // 记录“打开过什么”，停用时才能准确复位，而不是盲目关闭
        lastApplied.put(region, value);
        String command = "res set " + region + " " + flag + " " + value;
        try {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            plugin.getLogger().info("领地权限调整：" + command + (ok ? "（成功）" : "（指令返回 false，请确认领地名与 flag 是否存在）"));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("领地权限调整失败：" + command + " -> " + exception.getMessage());
        }
    }
}
