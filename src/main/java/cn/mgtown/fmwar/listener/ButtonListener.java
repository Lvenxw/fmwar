package cn.mgtown.fmwar.listener;

import cn.mgtown.fmwar.config.Settings;
import cn.mgtown.fmwar.config.Spec;
import cn.mgtown.fmwar.game.GameEngine;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ButtonCapture;
import cn.mgtown.fmwar.service.ConfigService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 按钮入口：把右键某个坐标方块翻译成游戏动作。
 *
 * <p>四个按钮全部由配置驱动（{@code buttons.*}）：大厅的加入/观战、准备房间的准备/返回大厅。
 * 判定用坐标而不是方块材质，所以按钮方块本身换成按钮/压力板/告示牌都不影响。
 * 每个按钮可配多个方块坐标，且保留 {@code radius} 容错——按钮贴墙/贴地时被点中的
 * 那一格经常与预期坐标差一格。</p>
 */
public final class ButtonListener implements Listener {

    private final ConfigService config;
    private final AlertService alerts;
    private final GameEngine engine;
    /** 可为 null（未启用校准功能时）。 */
    private final ButtonCapture buttonCapture;

    public ButtonListener(ConfigService config, AlertService alerts, GameEngine engine) {
        this(config, alerts, engine, null);
    }

    public ButtonListener(ConfigService config, AlertService alerts, GameEngine engine,
                          ButtonCapture buttonCapture) {
        this.config = config;
        this.alerts = alerts;
        this.engine = engine;
        this.buttonCapture = buttonCapture;
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

        // 若该玩家正在等待绑定按钮，这一次右键只用于登记坐标，不触发玩法动作
        if (buttonCapture != null && buttonCapture.awaiting(player.getUniqueId())) {
            event.setCancelled(true);
            buttonCapture.consume(player, block);
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
            default -> { }
        }
    }

    /** 返回被点击方块命中的按钮键；没有命中返回 null。 */
    private String resolve(Block block) {
        Settings settings = config.settings();
        for (String key : settings.buttonKeys()) {
            Spec.Button button;
            try {
                button = settings.button(key);
            } catch (IllegalStateException exception) {
                continue;
            }
            if (button.matches(block)) {
                return key;
            }
        }
        return null;
    }
}
