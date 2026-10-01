package cn.mgtown.fmwar.game;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 准备房间的名单演算。
 *
 * <p>单独立一个不依赖任何 Bukkit/Adventure 类型的小类,是因为“**哪些变化算玩家进入**”
 * 是需求 37 的核心规则,也是曾经出过错的地方:早先的实现把“任何名单变化”都当成进入,
 * 于是有人离开也会把准备进度清零,表现为进度永远停在 `1/7`。
 * 抽到这里之后,这条规则可以被自动化断言直接钉住。</p>
 */
public final class PrepRoom {

    private PrepRoom() {
    }

    /**
     * 本次采样相对上次采样**新进入**准备房间的玩家:{@code current - previous}。
     *
     * @return 新进入的玩家集合;无人进入时为空集
     */
    public static Set<UUID> entrants(Set<UUID> previous, Set<UUID> current) {
        Set<UUID> entered = new LinkedHashSet<>(current);
        entered.removeAll(previous);
        return entered;
    }

    /**
     * 准备进度是否应当因为名单变化而重置。
     *
     * <p>需求 37:“期间准备房间范围有玩家进入则重置”。因此返回值只取决于
     * {@link #entrants} 是否为空——有玩家离开、名单不变都不应重置。</p>
     *
     * @param previous      上次采样的房间内玩家
     * @param current       本次采样的房间内玩家
     * @param prepareClicks 当前已累计的准备点击次数
     */
    public static boolean shouldResetProgress(Set<UUID> previous, Set<UUID> current, int prepareClicks) {
        return prepareClicks > 0 && !entrants(previous, current).isEmpty();
    }
}
