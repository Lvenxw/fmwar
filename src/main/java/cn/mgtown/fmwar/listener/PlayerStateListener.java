package cn.mgtown.fmwar.listener;

import cn.mgtown.fmwar.game.GameEngine;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 玩家生命周期入口：上线、掉线、死亡。
 *
 * <p>三条判定都直接对应需求文档里的“掉线/死亡”条款：
 * 掉线即视为离开游戏（不再回到对局），死亡即淘汰。</p>
 */
public final class PlayerStateListener implements Listener {

    private final GameEngine engine;

    public PlayerStateListener(GameEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        engine.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!engine.isActive() || !engine.isMember(player.getUniqueId())) {
            return;
        }
        // 需求：死亡玩家的背包要被清空。原版死亡会先把物品掉在场地里，
        // 物品本身不再落地（避免与“游戏内物品不能带出游戏场地”冲突），
        // 只有“入场前备份的背包”会在离场时还给玩家。
        event.getDrops().clear();
        event.setDroppedExp(0);
        engine.eliminate(player, "death", true, true);
    }

    /**
     * 被本插件淘汰的玩家在主动重生时落点应为大厅。
     *
     * <p>关键约束：FMWar **绝不能**影响服务器上其他玩家的重生位置。因此这里只对
     * “刚被淘汰、正在等待重生”的玩家（由 {@link GameEngine#consumePendingRespawn} 判定）
     * 改写落点，其余一律不碰。</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!engine.consumePendingRespawn(player.getUniqueId())) {
            return;
        }
        org.bukkit.Location hall = engine.hallLocation();
        if (hall != null) {
            event.setRespawnLocation(hall);
        }
    }
}
