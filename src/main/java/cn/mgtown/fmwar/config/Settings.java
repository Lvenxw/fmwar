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
        Residence residence,
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

    /**
     * 开局分散。
     *
     * @param centerX    场地中心 X
     * @param centerY    中心 Y；仅作为“最高可落脚点”搜索的参考高度，可省略（配置里不写 y 时为 0）
     * @param centerZ    场地中心 Z
     * @param radius     正方形分散的半边长（格）
     * @param minSpacing 玩家之间的最小间距（格）
     */
    public record Disperse(boolean enabled, double centerX, double centerY, double centerZ,
                           double radius, double minSpacing, int maxAttempts) {
    }

    /**
     * 单个决斗圈的参数。
     *
     * <p>落点范围**由 region 决定**，不再使用“中心 ± 半径”的正方形——决斗圈区域多为
     * 长方形，正方形采样会留下死角且容易越界。</p>
     *
     * <p><b>两种落点模式互斥且各自独立</b>：</p>
     * <ul>
     *   <li>{@code exactY = true}：落点高度**锁定为 center 的 y**，完全不搜索地形。
     *       适合不规则场地（水域、悬挂藤蔓）——服主把高度量准后直接钉死。</li>
     *   <li>{@code exactY = false}：按 {@link #maxY} 上限搜索“最高可落脚面”。
     *       适合室内场地避开封顶玻璃。</li>
     * </ul>
     * <p>两种模式的配置项不会互相影响：exactY 为 true 时 maxY、allowWater 都不参与。</p>
     *
     * @param region     落点约束区域（{@code regions} 里的键）
     * @param exactY     是否把落点高度锁定为 center 的 y
     * @param maxY       落点高度上限（&gt; 0 生效）；仅在 exactY 为 false 时参与
     * @param allowWater 是否允许落在水面上；仅在 exactY 为 false 时参与
     */
    public record DuelArena(String region, double centerX, double centerY, double centerZ,
                            double minSpacing, int maxAttempts,
                            boolean exactY, double maxY, boolean allowWater) {
    }

    /**
     * 解析实际的落点模式。
     *
     * <p><b>区域的 y 范围窄（≤ 3 格）时一律锁定高度，以区域为准。</b>
     * 这是给“水面上一格”这类场地的硬保障：窄 y 范围本身就说明服主把高度量准了，
     * 此时配置里的 max-y（例如从上一个圈抄下来忘了删）绝不能把落点顶到区域之外。</p>
     *
     * @param region       该圈对应的区域
     * @param explicitExact 配置里是否显式写了 exact-y: true
     * @return true 表示锁定高度（用 center 的 y），false 表示搜索最高可落脚面
     */
    public static boolean resolveExactY(Region region, boolean explicitExact) {
        if (explicitExact) {
            return true;
        }
        return region != null && (region.maxY() - region.minY()) <= LOCKED_Y_RANGE;
    }

    /** y 范围不超过这个值就视为“高度已锁定”的场地。 */
    public static final double LOCKED_Y_RANGE = 3.0;

    /**
     * 搜索模式下的高度上限。
     *
     * <p>锁定高度的场合返回 0（不需要上限）；其余情况取 max-y。若 max-y 高于区域最高点，
     * 会被压到区域最高点——否则落点会跑到区域上方。</p>
     */
    public static double resolveMaxY(Region region, boolean exactY, double configuredMaxY) {
        if (exactY || region == null) {
            return configuredMaxY;
        }
        if (configuredMaxY <= 0) {
            return 0.0;
        }
        return Math.min(configuredMaxY, region.maxY());
    }

    /**
     * 决斗圈。
     *
     * <p>可以有多个：到点后**随机选一个**，本局所有存活玩家都传送到同一个圈里。</p>
     *
     * @param arenas 全部候选决斗圈，至少一个
     * @param random 是否随机选择（false 时固定用第一个）
     */
    public record Duel(List<DuelArena> arenas, boolean random) {

        /** 随机（或固定）选出本次使用的决斗圈。 */
        public DuelArena pick(java.util.Random generator) {
            if (arenas.isEmpty()) {
                return null;
            }
            if (!random || arenas.size() == 1) {
                return arenas.get(0);
            }
            return arenas.get(generator.nextInt(arenas.size()));
        }
    }

    /**
     * 开局发放的钓竿配置。
     *
     * <p>收成一个 record 而不是在 {@code Start} 上摊开十个字段：钓竿的每一项
     * （材质、附魔、名称、Lore、不可破坏）都属于同一件物品，摊开之后
     * {@code Start} 会同时装着“清背包”“抗性”“钓竿”三组互不相干的配置，
     * 加一项就要改一次构造器。收成 record 之后 {@code Start} 只多一个字段，
     * 钓竿内部怎么加项都不影响调用方。</p>
     *
     * <p>所有字段都不可变：{@code name} 缺失与空串统一归一为 {@code ""}，
     * {@code lore} 缺失归一为空列表——这样调用方判空时不用区分“没配”和“配了空”。</p>
     */
    public record FishingRod(
            boolean enabled,
            String material,
            Map<String, Integer> enchantments,
            String name,
            boolean unbreakable,
            boolean hideUnbreakable,
            List<String> lore) {

        /** 材质可用且未禁用时才发竿；材质名写错时当作“不发”，由 buildRod 记日志。 */
        public boolean usable() {
            return enabled && material != null && !material.isBlank();
        }
    }

    public record Start(
            boolean clearInventory,
            boolean clearEffects,
            boolean blockSneak,
            boolean lootBooksOnly,
            boolean resistanceEnabled,
            long resistanceDurationTicks,
            int resistanceAmplifier,
            boolean heal,
            FishingRod fishingRod) {
    }

    public record Emerald(int amount) {
    }

    /** 奖励箱配置：坐标列表 + 随机行（每行是一组物品）。 */
    public record Loot(List<Position> chestLocations, List<List<String>> lootGroups) {
    }

    public record Teams(String player, String spectator) {
    }

    /**
     * 领地（Residence）临时权限联动。
     *
     * <p>准备房间与场地的领地在服务器上常态关闭传送权限，本插件只在需要时临时打开、
     * 用完立刻恢复。{@code flag} 默认 {@code move}——它才是“能不能进出这片领地”的实际
     * 生效项（{@code tp} 只约束领地自身的 {@code /res tp} 指令）。</p>
     *
     * @param prepRegions  排队期间需要放行的领地（如 FM.zb）
     * @param arenaRegions 对局期间需要放行的领地（如 FM）
     */
    public record Residence(boolean enabled, String flag,
                            List<String> prepRegions, List<String> arenaRegions) {
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
