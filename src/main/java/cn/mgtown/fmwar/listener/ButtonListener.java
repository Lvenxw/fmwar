package cn.mgtown.fmwar.listener;

import cn.mgtown.fmwar.config.Position;
import cn.mgtown.fmwar.config.Settings;
import cn.mgtown.fmwar.game.GameEngine;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ConfigService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;

/**
 * 按钮入口：把右键某个坐标方块翻译成游戏动作。
 *
 * <p>四个按钮全部由配置驱动（{@code buttons.*}）：大厅的加入/观战、准备房间的准备/返回大厅。
 * 判定用固定坐标邻域而不是方块材质，所以按钮方块本身换成按钮/压力板/告示牌都不影响。</p>
 */
public final class ButtonListener implements Listener {

    private final ConfigService config;
    private final AlertService alerts;
    private final GameEngine engine;

    public ButtonListener(ConfigService config, AlertService alerts, GameEngine engine) {
        this.config = config;
        this.alerts = alerts;
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("fmwar.play")) {
            return;
        }

        String key = resolve(block);
        if (key == null) {
            return;
        }
        event.setCancelled(true);

        switch (key) {
            case "join" -> engine.tryJoinQueue(player);
            case "spectator" -> engine.trySpectate(player);
            case "prepare" -> engine.prepareClick(player);
            case "back-to-hall" -> engine.leaveToHall(player);
            default -> alerts.sendTo(player, "reload-failed", java.util.Map.of());
        }
    }

    /** 返回被点击方块命中的按钮键；没有命中返回 null。 */
    private String resolve(Block block) {
        Settings settings = config.settings();
        for (String key : new String[]{"join", "spectator", "prepare", "back-to-hall"}) {
            Position position;
            try {
                position = settings.button(key).position();
            } catch (IllegalStateException exception) {
                continue;
            }
            double radius = settings.button(key).radius();
            if (position.isNearBlock(block, radius)) {
                return key;
            }
        }
        return null;
    }

    /** 判定两个位置是否同一个方块（调试用）。 */
    static boolean sameBlock(Position position, Block block) {
        return Objects.equals(position.world(), block.getWorld().getName())
                && (int) position.x() == block.getX()
                && (int) position.y() == block.getY()
                && (int) position.z() == block.getZ();
    }
}
