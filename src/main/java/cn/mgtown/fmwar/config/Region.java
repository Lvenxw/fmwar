package cn.mgtown.fmwar.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * 配置里的一个区域（长方体），单位是方块。
 *
 * <p>两个角点顺序无关：{@link #of} 会自动取小值为 min、大值为 max。
 * 这是为了避免需求文档里 dx/dy/dz 与角点两种写法造成的歧义。</p>
 */
public record Region(String world, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public static Region of(String world, double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Region(world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** 判断坐标是否在区域内（含边界）。世界名不匹配时返回 false。 */
    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        return contains(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    /**
     * 纯几何判定：不接触任何 Bukkit 类型。
     *
     * <p>抽出这个方法是为了让“区域包含关系”这类核心判定可以被自动化测试直接覆盖——
     * 它决定了成员资格、存活人数、离场淘汰与提示可见性，是玩法里最不该出错的一条逻辑。</p>
     */
    public boolean contains(String worldName, double x, double y, double z) {
        if (worldName == null || !worldName.equals(world)) {
            return false;
        }
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * 圆形范围是否完全落在本区域的水平投影内（忽略 y）。
     *
     * <p>用于校验“开局分散圆”“决斗圈”有没有伸出场地：一旦伸出，落在界外的玩家会在
     * 开局后立刻被判“离开游戏”，表现为刚开局就“无人生还”。这是纯几何判定，可被断言覆盖。</p>
     */
    public boolean containsCircleXZ(double centerX, double centerZ, double radius) {
        return centerX - radius >= minX && centerX + radius <= maxX
                && centerZ - radius >= minZ && centerZ + radius <= maxZ;
    }

    /** 本区域是否完全包含另一个区域（用于校验提示接收范围覆盖场地）。 */
    public boolean covers(Region other) {
        return other != null
                && minX <= other.minX && minY <= other.minY && minZ <= other.minZ
                && maxX >= other.maxX && maxY >= other.maxY && maxZ >= other.maxZ;
    }

    /**
     * 只判水平范围（忽略 y）。
     *
     * <p>分散/决斗圈的落点判定必须用这个方法：候选点的 y 在采样阶段还不知道，
     * 用带 y 的 {@link #contains} 传一个占位值（例如 0）会把水泥地以外的一切都判成
     * “区域外”，导致**所有候选点被拒、分散彻底失败**——这正是曾经的错误。</p>
     */
    public boolean containsXZ(String worldName, double x, double z) {
        if (worldName == null || !worldName.equals(world)) {
            return false;
        }
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** 解析区域所在世界；世界未加载时返回 null。 */
    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }
}
