package cn.mgtown.fmwar.game;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 把玩家放到一个“最高可落脚点”，且排除水。
 *
 * <p>需求里两处传送都要求“传送位置为最高可落脚点，不包括水”：开局分散与决斗圈收缩。
 * 判定规则（围绕世界最高非空气方块向下找首个可站立面）：</p>
 * <ol>
 *   <li>目标方块必须是固体、不是液体，且上方两格可容纳玩家；</li>
 *   <li>若是水/岩浆（液体）则继续向下找；</li>
 *   <li>找不到合格落脚点时返回 null，由调用方决定降级策略。</li>
 * </ol>
 */
public final class SafeLocation {

    private SafeLocation() {
    }

    /** 在给定 x/z 上寻找最高可落脚点；失败返回 null。 */
    public static Location find(World world, double x, double z) {
        if (world == null) {
            return null;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int startY = Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(blockX, blockZ) + 1);
        for (int y = startY; y > world.getMinHeight(); y--) {
            Block ground = world.getBlockAt(blockX, y, blockZ);
            if (ground.isLiquid() || ground.isEmpty()) {
                continue;
            }
            if (!ground.getType().isSolid()) {
                continue;
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
}
