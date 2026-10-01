package cn.mgtown.fmwar.config;

import org.bukkit.block.Block;

import java.util.List;

/** 配置里可直接写出来的小结构。 */
public final class Spec {

    private Spec() {
    }

    /**
     * 一个可右键的按钮。
     *
     * <p>支持**多个方块**：同一动作在不同位置放了多个按钮（或按钮贴墙/贴地导致被点中的
     * 方块与预期差一格）都能命中。判定顺序是“先精确方块匹配，再按 {@code radius} 取最近的一个”。</p>
     */
    public record Button(String key, List<Position> blocks, double radius) {

        /** 兼容单方块写法。 */
        public Button(String key, Position block, double radius) {
            this(key, List.of(block), radius);
        }

        /** 主要坐标（用于日志与诊断输出）。 */
        public Position position() {
            return blocks.isEmpty() ? null : blocks.get(0);
        }

        /**
         * 该方块是否属于本按钮。
         *
         * <p>先做精确的方块坐标比较（最可靠），再退回半径判定以容忍配置误差。</p>
         */
        public boolean matches(Block block) {
            return block != null && matches(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }

        /**
         * 纯几何判定：某个方块坐标是否属于本按钮（不接触任何 Bukkit 类型）。
         *
         * <p>抽出来是为了让“按钮能不能被点中”这条排查起来最费劲的逻辑可以被自动化断言覆盖。</p>
         */
        public boolean matches(String worldName, int x, int y, int z) {
            for (Position candidate : blocks) {
                if (candidate == null || !candidate.world().equals(worldName)) {
                    continue;
                }
                if ((int) Math.floor(candidate.x()) == x
                        && (int) Math.floor(candidate.y()) == y
                        && (int) Math.floor(candidate.z()) == z) {
                    return true;
                }
                double dx = candidate.x() - (x + 0.5);
                double dy = candidate.y() - (y + 0.5);
                double dz = candidate.z() - (z + 0.5);
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius) {
                    return true;
                }
            }
            return false;
        }

        /** 到最近一个配置方块的距离；无法计算时返回 {@link Double#MAX_VALUE}。 */
        public double distanceTo(Block block) {
            if (block == null) {
                return Double.MAX_VALUE;
            }
            double best = Double.MAX_VALUE;
            for (Position candidate : blocks) {
                if (candidate == null || !candidate.world().equals(block.getWorld().getName())) {
                    continue;
                }
                double dx = candidate.x() - (block.getX() + 0.5);
                double dy = candidate.y() - (block.getY() + 0.5);
                double dz = candidate.z() - (block.getZ() + 0.5);
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            return best;
        }
    }

    /** 配置校验中发现的问题。 */
    public record Problem(String path, String detail) {
    }

    /** 校验结果：problems 为空表示配置可用。 */
    public record Validation(List<Problem> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }

        public String describe() {
            StringBuilder builder = new StringBuilder();
            for (Problem problem : problems) {
                builder.append(problem.path()).append(" -> ").append(problem.detail()).append("; ");
            }
            return builder.toString();
        }
    }
}
