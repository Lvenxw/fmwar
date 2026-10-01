package cn.mgtown.fmwar.config;

import java.util.List;
import java.util.Map;

/**
 * 一次 /fmwar reload 解析出的完整配置快照。
 *
 * <p>不可变对象：重载时整体重建再原子替换，因此运行中的对局不会被半份配置污染。</p>
 */
public record Settings(
        String prefix,
        String warPrefix,
        String world,
        Map<String, Region> regions,
        Map<String, Position> locations,
        Map<String, Spec.Button> buttons,
        Timing timing,
        Disperse disperse,
        Duel duel,
        Start start,
        Emerald emerald,
        Loot loot,
        List<String> extraShopIds,
        boolean extraShopsEnabled,
        Teams teams,
        Scoreboard scoreboard,
        Map<String, String> messages,
        boolean actionbarMessages
) {

    /** 取区域；缺失时抛 IllegalStateException（配置校验阶段已保证存在）。 */
    public Region region(String key) {
        Region region = regions.get(key);
        if (region == null) {
            throw new IllegalStateException("缺少区域配置: regions." + key);
        }
        return region;
    }

    /**
     * 取可选区域：缺失返回 null。
     *
     * <p>监听器里一律用这个重载——配置缺项时应当安静跳过，而不是每 tick 抛异常刷日志。</p>
     */
    public Region optionalRegion(String key) {
        return regions.get(key);
    }

    /** 取坐标点；缺失时抛 IllegalStateException。 */
    public Position location(String key) {
        Position position = locations.get(key);
        if (position == null) {
            throw new IllegalStateException("缺少坐标配置: locations." + key);
        }
        return position;
    }

    /** 取可选坐标点：缺失返回 null。 */
    public Position locationOrNull(String key) {
        return locations.get(key);
    }

    /** 取按钮；缺失时抛 IllegalStateException。 */
    public Spec.Button button(String key) {
        Spec.Button button = buttons.get(key);
        if (button == null) {
            throw new IllegalStateException("缺少按钮配置: buttons." + key);
        }
        return button;
    }

    /** 全部按钮键（判定顺序按配置里的出现顺序）。 */
    public java.util.Set<String> buttonKeys() {
        return buttons.keySet();
    }

    /** 取提示文案；缺失时返回 key 本身，避免运行期 NPE。 */
    public String message(String key) {
        return messages.getOrDefault(key, key);
    }

    public record Timing(long prepareClicks,
                         long prepareCountdownSeconds,
                         long gameDurationSeconds,
                         long duelTeleportAtSeconds,
                         long emeraldIntervalSeconds,
                         double overtimeDamage) {
    }

    public record Disperse(boolean enabled, double centerX, double centerZ, double radius,
                           double minSpacing, int maxAttempts) {
    }

    public record Duel(double centerX, double centerZ, double radius, double minSpacing, int maxAttempts) {
    }

    /**
     * 开局设置。
     *
     * <p>刻意**不提供背包备份开关**：备份只存在于内存里，崩服会连同备份一起丢失，
     * 反而让玩家物品更不安全。需要保护玩家物品应当用专门的背包备份插件。</p>
     */
    public record Start(boolean clearInventory,
                        boolean clearEffects,
                        boolean blockSneak,
                        boolean resistanceEnabled,
                        long resistanceDurationTicks,
                        int resistanceAmplifier,
                        boolean heal,
                        boolean rodEnabled,
                        String rodMaterial,
                        Map<String, Integer> rodEnchantments) {
    }

    public record Emerald(int amount) {
    }

    /** 奖励箱配置：坐标列表 + 随机行（每行是一组物品）。 */
    public record Loot(List<Position> chestLocations, List<List<String>> lootGroups) {
    }

    public record Teams(String player, String spectator) {
    }

    public record Scoreboard(boolean enabled,
                             String title,
                             boolean timeSeconds,
                             String timeLine,
                             String aliveLine,
                             boolean pointsEnabled,
                             String pointsMain,
                             String pointsTitle,
                             String pointsHeader,
                             String pointsLine,
                             int pointsRows) {
    }
}
