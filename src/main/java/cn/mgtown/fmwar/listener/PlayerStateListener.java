package cn.mgtown.fmwar.listener;

import cn.mgtown.fmwar.game.GameEngine;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

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
        if (!engine.isRunning() || !engine.isMember(player.getUniqueId())) {
            return;
        }
        engine.eliminate(player, "death", true);
    }
}
