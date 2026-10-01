package cn.mgtown.fmwar.listener;

import cn.mgtown.fmwar.game.GameEngine;
import cn.mgtown.fmwar.service.ConfigService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 传送守卫：保证本插件的传送一定成功，同时阻止其它来源把玩家送进游戏区域。
 *
 * <p>需求原文：“服务器中存在领地插件 residence（领地名：FM），传送可能会没有权限（需要解决），
 * 但要保证其他玩家无法传送到领地内或在领地内互相传送（领地传送权限默认为关）”。</p>
 *
 * <p>分工：</p>
 * <ul>
 *   <li>本插件自己的传送带 {@code teleportBypass} 标记 —— 放行，决斗圈/大厅/准备房间的传送不会被领地插件拦住；</li>
 *   <li>其它来源（/res tp、传送指令、其它插件）把玩家送进场地/准备房间/大厅/决斗圈 —— 取消。</li>
 * </ul>
 *
 * <p>另外“领地传送权限默认为关”属于 Residence 侧配置（领地 FM 的 {@code tp} flag），
 * 本插件不代改别人的权限数据，只作为部署检查项记录在文档里。</p>
 */
public final class TeleportGuardListener implements Listener {

    private final ConfigService config;
    private final GameEngine engine;

    public TeleportGuardListener(ConfigService config, GameEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (engine.isBypassingTeleport(player.getUniqueId())) {
            return;
        }
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (!insideProtectedRegions(to)) {
            return;
        }
        // 已经在场内且在场地内部移动：不拦（避免打断观战/游戏内的正常位移检测）
        Location from = event.getFrom();
        if (from != null && insideArena(from) && insideArena(to)) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean insideProtectedRegions(Location location) {
        return inside(location, "arena")
                || inside(location, "prep-room")
                || inside(location, "hall")
                || inside(location, "duel-1");
    }

    private boolean insideArena(Location location) {
        return inside(location, "arena");
    }

    private boolean inside(Location location, String regionKey) {
        var region = config.settings().optionalRegion(regionKey);
        return region != null && region.contains(location);
    }
}
