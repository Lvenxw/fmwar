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
     * 申请打开某片领地的传送权限。
     *
     * @param region 领地名（如 {@code FM.zb}）
     */
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
     * @param context {@code prep} 或 {@code arena}
     */
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
