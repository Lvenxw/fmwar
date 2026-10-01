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
        if (!location.getWorld().getName().equals(world)) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** 解析区域所在世界；世界未加载时返回 null。 */
    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }
}
