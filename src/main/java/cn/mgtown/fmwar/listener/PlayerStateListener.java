package cn.mgtown.fmwar.listener;

import cn.mgtown.fmwar.game.GameEngine;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
        // 只处理**本局参战玩家**：清掉落是本局规则，绝不能让 FMWar 影响服务器上
        // 其他玩家在自己世界里死亡时的掉落与经验。
        if (!engine.isMember(player.getUniqueId())) {
            return;
        }
        // 需求：死亡玩家的背包要被清空。原版死亡会先把物品掉在场地里，
        // 因此先清空掉落，再交给引擎做淘汰与计分。
        event.getDrops().clear();
        // 经验值不属于"背包内容"，不能被清背包顺手抹掉。
        // 原版在**重生那一刻**把经验清零，开关是 keepLevel；droppedExp 只管"地上掉几个
        // 经验球"，两者必须分开设置——此前只设了 droppedExp=0，经验球没了，
        // 玩家的等级也在重生时被清空，看起来就是"清背包把经验也清空了"。
        if (engine.keepExperienceOnDeath()) {
            event.setKeepLevel(true);
        }
        event.setDroppedExp(0);
        engine.onPlayerDeath(player);
    }

    /** 记录最后一名对玩家造成伤害的人：死亡时据此记击杀分（含弹射物伤害）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        // 同样只关心本局参战玩家：服务器上其他玩家之间的互殴与 FMWar 无关，
        // 不必走到解析伤害来源那一步（击杀分的资格判定在引擎里还有一道）。
        if (!engine.isMember(victim.getUniqueId())) {
            return;
        }
        Player damager = resolveDamager(event.getDamager());
        if (damager == null) {
            return;
        }
        engine.recordDamager(victim.getUniqueId(), damager.getUniqueId());
    }

    /** 把弹射物 / 驯服生物的主人解析成实际造成伤害的玩家。 */
    private Player resolveDamager(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (entity instanceof org.bukkit.entity.Tameable tameable
                && tameable.getOwner() instanceof Player owner) {
            return owner;
        }
        return null;
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
