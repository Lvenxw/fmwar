package cn.mgtown.fmwar.listener;

import cn.mgtown.fmwar.config.Region;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ConfigService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

import java.util.Map;

/**
 * 行为限制：在提示接收范围内禁止骑乘（含骑在其他玩家头上）。
 *
 * <p>需求：“在提示接收范围内，玩家不能躺下、坐下、趴下、骑在其他玩家头上，判定到直接取消动作”。</p>
 *
 * <p>实现边界：原版协议没有“坐下/躺下/趴下”姿态，这些动作由坐姿类插件（如 GSit）提供，
 * 其事件不在 paper-api 里，因此本插件只能拦下**原版范围内**可判定的部分：
 * 骑乘生物/载具/其他玩家，以及潜行。坐姿插件的拦截需要按其 API 另接一个监听器。</p>
 */
public final class ActionGuardListener implements Listener {

    private final ConfigService config;
    private final AlertService alerts;

    public ActionGuardListener(ConfigService config, AlertService alerts) {
        this.config = config;
        this.alerts = alerts;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!inNotifyRegion(player)) {
            return;
        }
        event.setCancelled(true);
        alerts.sendActionBarTo(player, "action-blocked", Map.of());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking()) {
            return;
        }
        // 默认关闭：原版协议没有“坐下/躺下”姿态，潜行只是最接近的可判定动作。
        // 若服上确实要求连潜行一起禁，把 start.block-sneak 打开即可。
        if (!config.settings().start().blockSneak()) {
            return;
        }
        if (!inNotifyRegion(player)) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean inNotifyRegion(Player player) {
        Region notify = config.settings().optionalRegion("notify");
        return notify != null && notify.contains(player.getLocation());
    }
}
