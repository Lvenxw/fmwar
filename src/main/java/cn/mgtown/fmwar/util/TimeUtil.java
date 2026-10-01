package cn.mgtown.fmwar.util;

import java.time.Duration;

/** 计时相关的格式化工具。 */
public final class TimeUtil {

    private TimeUtil() {
    }

    /** 把剩余 tick 数格式化成 {@code mm:ss}；负数按 0 处理。 */
    public static String mmss(long ticks) {
        long seconds = Math.max(0L, ticks) / 20L;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return String.format("%02d:%02d", minutes, rest);
    }

    /** 把剩余 tick 数格式化成秒（向上取整，倒计时显示用）。 */
    public static long ceilSeconds(long ticks) {
        if (ticks <= 0) {
            return 0L;
        }
        return (ticks + 19L) / 20L;
    }

    /** 把 Duration 转成 tick。 */
    public static long ticks(Duration duration) {
        return duration.toMillis() / 50L;
    }
}
