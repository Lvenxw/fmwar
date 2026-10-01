package cn.mgtown.fmwar.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * 配置里的一个坐标点，可带朝向点。
 *
 * <p>朝向点只用于算 yaw / pitch：{@code point [x,y,z] facing [x,y,z]}。</p>
 */
public record Position(String world, double x, double y, double z,
                       double[] facing, double yaw, double pitch) {

    public static Position of(String world, double x, double y, double z,
                              double[] facing, double yaw, double pitch) {
        return new Position(world, x, y, z, facing, yaw, pitch);
    }

    /** 转换为 Bukkit Location；世界未加载时返回 null。 */
    public Location toLocation() {
        World bukkit = Bukkit.getWorld(world);
        if (bukkit == null) {
            return null;
        }
        Location location = new Location(bukkit, x, y, z);
        if (facing != null && facing.length >= 3) {
            location.setDirection(new org.bukkit.util.Vector(
                    facing[0] - x, facing[1] - y, facing[2] - z));
        } else {
            location.setYaw((float) yaw);
            location.setPitch((float) pitch);
        }
        return location;
    }

    /** 仅用作距离判定的中心点，不依赖世界是否加载。 */
    public boolean isNear(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equals(world)) {
            return false;
        }
        double dx = location.getX() - x;
        double dy = location.getY() - y;
        double dz = location.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** 方块坐标的距离判定（按钮判定用，比较方块中心）。 */
    public boolean isNearBlock(org.bukkit.block.Block block, double radius) {
        if (block == null) {
            return false;
        }
        return isNear(block.getLocation().add(0.5, 0.5, 0.5), radius);
    }
}
