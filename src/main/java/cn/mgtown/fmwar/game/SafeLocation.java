package cn.mgtown.fmwar.game;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 把玩家放到一个“最高可落脚点”，且排除水与细雪。
 *
 * <p>需求里两处传送都要求“传送位置为最高可落脚点，不包括水”：开局分散与决斗圈收缩。
 * 判定规则（围绕世界最高非空气方块向下找首个可站立面）：</p>
 * <ol>
 *   <li>目标方块必须是固体、不是液体，且上方两格可容纳玩家；</li>
 *   <li>若是水/岩浆（液体）则继续向下找；</li>
 *   <li>落点所在格（玩家脚部）如果是细雪，该 x/z 直接作废——
 *       细雪无碰撞体积，玩家会陷进去并持续受冻伤；</li>
 *   <li>找不到合格落脚点时返回 null，由调用方决定降级策略。</li>
 * </ol>
 */
public final class SafeLocation {

    private SafeLocation() {
    }

    /** 在给定 x/z 上寻找最高可落脚点；失败返回 null。 */
    public static Location find(World world, double x, double z) {
        return find(world, x, z, 0.0, 0.0, false);
    }

    /**
     * 在给定 x/z 上寻找最高可落脚点；失败返回 null。
     *
     * <p>落点取该 x/z 上**最高的可落脚面**（最高方块的上表面），不会落到房子内部。
     * 参考高度只用来抬高搜索起点上限，不改变这个语义。</p>
     */
    public static Location find(World world, double x, double z, double referenceY) {
        return find(world, x, z, referenceY, 0.0, false);
    }

    /**
     * 在给定 x/z 上寻找最高可落脚点；失败返回 null。
     *
     * @param referenceY 参考高度；只用于抬高搜索起点上限
     * @param maxY       落点高度**上限**（&gt; 0 时生效）。室内场地的封顶玻璃会挡住
     *                   “最高可落脚面”，把落点顶到屋顶（玻璃）上；给出上限即可让落点
     *                   回到场地内部
     * @param allowWater 是否允许落在水面上（水面不可站立，落点取水面那一格的上方）
     */
    public static Location find(World world, double x, double z, double referenceY,
                                double maxY, boolean allowWater) {
        if (world == null) {
            return null;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        // 起点取世界最高点（需求要求“最高可落脚点”）；给了高度上限就压到上限处，
        // 这样高于上限的封顶玻璃不会被选中
        int startY = Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(blockX, blockZ) + 1);
        if (referenceY > 0) {
            startY = Math.min(world.getMaxHeight() - 2, Math.max(startY, (int) Math.floor(referenceY)));
        }
        if (maxY > 0) {
            startY = Math.min(startY, (int) Math.floor(maxY));
        }
        for (int y = startY; y > world.getMinHeight(); y--) {
            Block ground = world.getBlockAt(blockX, y, blockZ);
            if (ground.isEmpty()) {
                continue;
            }
            if (ground.isLiquid()) {
                // 水面：只有显式允许时才作为落点
                if (!allowWater || !isWater(ground)) {
                    continue;
                }
            } else if (!ground.getType().isSolid()) {
                // 细雪、草、花等没有碰撞体积的方块都在这里被跳过
                continue;
            }
            // 需求：禁止把落点放在细雪上。
            // ground 本身不可能是细雪（细雪不是 solid，上一行已 continue），
            // 但落点所在格（玩家脚部）可能是——细雪没有碰撞体积，
            // isPassable() 会返回 true，只看它会把细雪当成空气放过去，
            // 玩家一落地就陷进去并持续受冻伤。这里直接作废该 x/z，
            // 让上层采样换点，而不是继续向下搜（继续向下会让玩家落到细雪下方）。
            if (world.getBlockAt(blockX, y + 1, blockZ).getType() == Material.POWDER_SNOW) {
                return null;
            }
            if (!ground.getRelative(0, 1, 0).isPassable() || !ground.getRelative(0, 2, 0).isPassable()) {
                continue;
            }
            Location location = new Location(world, blockX + 0.5, y + 1.0, blockZ + 0.5);
            location.setDirection(new Vector(0, 0, 0));
            return location;
        }
        return null;
    }

    /**
     * 在给定 x/z 上取**精确高度**的落点（不做任何地形搜索）。
     *
     * <p>用于规则不规则、且服主已经把高度量准的场地：直接按配置的 y 放置，
     * 完全不看该列的地形。适用场景是“水面上方一格”这类无法靠“最高可落脚面”
     * 描述的位置——水面不是可站立方块，而悬挂的藤蔓/垂叶又会被误判为落脚面。</p>
     *
     * <p>唯一会拒绝的情形是细雪：锁定高度模式不做地形搜索，一旦脚部那一格
     * 恰好铺着细雪，玩家就会陷进去。这里直接返回 null，让调用方走兜底点，
     * 而不是把人塞进细雪里。</p>
     *
     * @param y 落点的脚部高度（即玩家站在 y 这一格）
     */
    public static Location exact(World world, double x, double y, double z) {
        if (world == null) {
            return null;
        }
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        if (world.getBlockAt(blockX, blockY, blockZ).getType() == Material.POWDER_SNOW) {
            return null;
        }
        Location location = new Location(world,
                Math.floor(x) + 0.5, y, Math.floor(z) + 0.5);
        location.setDirection(new Vector(0, 0, 0));
        return location;
    }

    /** 是否水（不含岩浆）。 */
    private static boolean isWater(Block block) {
        Material type = block.getType();
        return type == Material.WATER
                || type == Material.BUBBLE_COLUMN
                || type == Material.KELP
                || type == Material.KELP_PLANT
                || type == Material.SEAGRASS
                || type == Material.TALL_SEAGRASS;
    }

    /**
     * 在给定**区域**的整个水平范围内采样落脚点；找不到返回 null。
     *
     * <p>决斗圈用这个方法而不是 {@link #sampleSquare}：决斗圈的区域通常是长方形
     * （例如 x 跨度 17 格、z 跨度 35 格），用“中心 ± 半径”的正方形采样会留下大量
     * 覆盖不到的死角，半径写大了还会越出区域。直接按区域范围取点，
     * 既铺满整个圈，也天然保证不越界。</p>
     *
     * @param region     落点必须落在其中
     * @param maxY       落点高度上限（&gt; 0 生效）
     * @param allowWater 是否允许落在水面上
     */
    public static Location sampleRegion(World world, cn.mgtown.fmwar.config.Region region,
                                        double minSpacing, int maxAttempts,
                                        java.util.List<Location> taken,
                                        double referenceY, double maxY, boolean allowWater) {
        if (world == null || region == null) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 区域内取点要含边界：+1 让 maxX/maxZ 那一格也能被抽到
        double widthX = Math.max(1.0, region.maxX() - region.minX() + 1);
        double widthZ = Math.max(1.0, region.maxZ() - region.minZ() + 1);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double x = region.minX() + random.nextDouble(widthX);
            double z = region.minZ() + random.nextDouble(widthZ);
            if (!region.containsXZ(world.getName(), x, z)) {
                continue;
            }
            Location candidate = find(world, x, z, referenceY, maxY, allowWater);
            if (candidate == null) {
                continue;
            }
            // 地形落差可能让实际落脚点偏出区域，落地后再确认一次（含高度范围）
            if (!region.contains(candidate)) {
                continue;
            }
            if (isTooClose(candidate, taken, minSpacing)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * 在给定**区域**的整个水平范围内采样，落点高度**锁定为 {@code exactY}**。
     *
     * <p>不做任何地形搜索。区域只要 x/z 在范围内、且 {@code exactY} 落在区域的
     * y 范围里即可——这正好适配“不规则水域 + 只认准水面上一格”的场地。</p>
     *
     * @param exactY 落点脚部高度（配置的 center y）
     */
    public static Location sampleRegionExactY(World world, cn.mgtown.fmwar.config.Region region,
                                              double exactY, double minSpacing, int maxAttempts,
                                              java.util.List<Location> taken) {
        if (world == null || region == null) {
            return null;
        }
        double widthX = Math.max(1.0, region.maxX() - region.minX() + 1);
        double widthZ = Math.max(1.0, region.maxZ() - region.minZ() + 1);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double x = region.minX() + random.nextDouble(widthX);
            double z = region.minZ() + random.nextDouble(widthZ);
            if (!region.containsXZ(world.getName(), x, z)) {
                continue;
            }
            Location candidate = exact(world, x, exactY, z);
            if (!region.contains(candidate)) {
                continue;
            }
            if (isTooClose(candidate, taken, minSpacing)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /** 是否与已选落点距离过近。 */
    private static boolean isTooClose(Location candidate, java.util.List<Location> taken, double minSpacing) {
        for (Location other : taken) {
            if (other.getWorld() != candidate.getWorld()) {
                continue;
            }
            double dx = other.getX() - candidate.getX();
            double dz = other.getZ() - candidate.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < minSpacing) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在**正方形**区域内采样一个满足最小间距、且落在指定区域内的落脚点；找不到返回 null。
     *
     * <p>需求 8 要求“以中心点 -1078 -1774 为中心、半径 100 格的正方形分散”，
     * 因此这里按边长 {@code 2 * halfSize} 的正方形均匀取点，而不是取圆。
     * 越出 {@code within}（场地）的候选点直接丢弃——否则玩家一落地就被判“离开游戏”。</p>
     *
     * @param within     落点必须落在其中（通常传场地）；传 null 表示不限制
     * @param referenceY 落脚点搜索的参考高度（配置 center 的 y）；&lt;= 0 时从世界最高点向下找
     */
    public static Location sampleSquare(World world, double centerX, double centerZ,
                                        double halfSize, double minSpacing, int maxAttempts,
                                        java.util.List<Location> taken, cn.mgtown.fmwar.config.Region within,
                                        double referenceY) {
        return sampleSquare(world, centerX, centerZ, halfSize, minSpacing, maxAttempts,
                taken, within, referenceY, 0.0, false);
    }

    /**
     * 正方形采样的完整版本。
     *
     * @param maxY       落点高度上限（&gt; 0 生效）：室内场地用它避开封顶玻璃
     * @param allowWater 是否允许落在水面上
     */
    public static Location sampleSquare(World world, double centerX, double centerZ,
                                        double halfSize, double minSpacing, int maxAttempts,
                                        java.util.List<Location> taken, cn.mgtown.fmwar.config.Region within,
                                        double referenceY, double maxY, boolean allowWater) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double x = centerX + random.nextDouble(-halfSize, halfSize);
            double z = centerZ + random.nextDouble(-halfSize, halfSize);
            // 必须用只判水平的 containsXZ：候选点的 y 此刻未知，用带 y 的判定传占位值
            // （例如 0）会把所有候选点判成区域外，分散会 100% 失败
            if (within != null && !within.containsXZ(world.getName(), x, z)) {
                continue;
            }
            Location candidate = find(world, x, z, referenceY, maxY, allowWater);
            if (candidate == null) {
                continue;
            }
            // 地形落差可能让实际落脚点偏出场地边界，落地后再确认一次。
            // 这里必须用带 y 的 contains：高度上限已经保证落点在场地内部，
            // 而 duel-2 这类场地的 y 范围是有意义的（低于上限）
            if (within != null && !within.contains(candidate)) {
                continue;
            }
            boolean tooClose = false;
            for (Location other : taken) {
                if (other.getWorld() != candidate.getWorld()) {
                    continue;
                }
                double dx = other.getX() - candidate.getX();
                double dz = other.getZ() - candidate.getZ();
                if (Math.sqrt(dx * dx + dz * dz) < minSpacing) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 在圆形区域内采样一个满足最小间距、且**落在指定区域内**的落脚点；找不到返回 null。
     *
     * @param within 落点必须落在其中（通常传场地）；传 null 表示不限制
     */
    public static Location sample(World world, double centerX, double centerZ,
                                  double radius, double minSpacing, int maxAttempts,
                                  java.util.List<Location> taken, cn.mgtown.fmwar.config.Region within) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            // sqrt 保证落在圆内均匀分布
            double distance = Math.sqrt(random.nextDouble()) * radius;
            double x = centerX + Math.cos(angle) * distance;
            double z = centerZ + Math.sin(angle) * distance;
            // 越出场地矩形的候选点直接丢弃：否则玩家一落地就被判“离开游戏”
            if (within != null && !within.contains(world.getName(), x, 0, z)) {
                continue;
            }
            Location candidate = find(world, x, z);
            if (candidate == null) {
                continue;
            }
            if (within != null
                    && !within.containsXZ(world.getName(), candidate.getX(), candidate.getZ())) {
                continue;
            }
            boolean tooClose = false;
            for (Location other : taken) {
                if (other.getWorld() != candidate.getWorld()) {
                    continue;
                }
                double dx = other.getX() - candidate.getX();
                double dz = other.getZ() - candidate.getZ();
                if (Math.sqrt(dx * dx + dz * dz) < minSpacing) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                return candidate;
            }
        }
        return null;
    }
}
