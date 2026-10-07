package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Spec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * 按钮就地校准：管理员在游戏里右键一次目标方块，即可把它登记到指定按钮上。
 *
 * <p>存在的理由：服务器上按钮的**实际方块坐标**经常与需求文档给的坐标差一两格——
 * 按钮贴墙/贴地时 {@code PlayerInteractEvent#getClickedBlock()} 返回的是被点中的那一格。
 * 与其手改 YAML 再反复 reload，不如在游戏里直接点一下。</p>
 */
public final class ButtonCapture {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final long TIMEOUT_TICKS = 20L * 30L;

    private final ConfigService config;
    private final AlertService alerts;

    /**
     * 玩家 -> 待绑定的按钮键。
     *
     * <p>用并发表：写入来自指令线程（{@code /fmwar button <键>}），读取与移除来自右键事件
     * ——Folia 上后者跑在该玩家所属区域线程，两者不是同一个线程。</p>
     */
    private final Map<UUID, String> pendingKey = new java.util.concurrent.ConcurrentHashMap<>();
    /** 玩家 -> 本次等待的截止 tick，超时自动作废。理由同 {@link #pendingKey}。 */
    private final Map<UUID, Long> pendingUntil = new java.util.concurrent.ConcurrentHashMap<>();

    public ButtonCapture(ConfigService config, AlertService alerts) {
        this.config = config;
        this.alerts = alerts;
    }

    /** 该玩家是否正在等待右键一个方块来绑定按钮。 */
    public boolean awaiting(UUID uuid) {
        Long until = pendingUntil.get(uuid);
        if (until == null) {
            return false;
        }
        if (org.bukkit.Bukkit.getCurrentTick() > until) {
            pendingKey.remove(uuid);
            pendingUntil.remove(uuid);
            return false;
        }
        return pendingKey.containsKey(uuid);
    }

    /** 发起一次绑定：提示玩家去右键目标方块。 */
    public void begin(Player player, String key) {
        pendingKey.put(player.getUniqueId(), key);
        pendingUntil.put(player.getUniqueId(), org.bukkit.Bukkit.getCurrentTick() + TIMEOUT_TICKS);
        alerts.sendTo(player, "button-capture-prompt", Map.of("button", key, "seconds", "30"));
    }

    /** 取消该玩家的待绑定状态。 */
    public void cancel(UUID uuid) {
        pendingKey.remove(uuid);
        pendingUntil.remove(uuid);
    }

    /**
     * 玩家右键了一个方块：完成绑定。
     *
     * @param key   玩家在提示里选的按钮键
     * @param block 被点中的方块
     */
    public void finish(Player player, String key, Block block) {
        cancel(player.getUniqueId());
        boolean ok = config.manager().appendButtonBlock(
                key, block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (!ok) {
            alerts.sendTo(player, "button-capture-failed", Map.of("button", key));
            return;
        }
        // 立刻生效：写回文件后重新加载配置
        config.reload();
        if (block.getType() != org.bukkit.Material.AIR) {
            alerts.sendTo(player, "button-capture-done", Map.of(
                    "button", key,
                    "world", block.getWorld().getName(),
                    "x", Integer.toString(block.getX()),
                    "y", Integer.toString(block.getY()),
                    "z", Integer.toString(block.getZ()),
                    "material", block.getType().name()));
        }
    }

    /**
     * 右键事件里用：若该玩家正在等待绑定，就取出待绑定键、清除状态并**当场完成绑定**。
     *
     * @return 实际绑定的按钮键；玩家没有待绑定状态时返回 null
     */
    public String consume(Player player, Block block) {
        String key = pendingKey.remove(player.getUniqueId());
        pendingUntil.remove(player.getUniqueId());
        if (key == null) {
            return null;
        }
        finish(player, key, block);
        return key;
    }

    /**
     * 向玩家展示可点击选择按钮键的提示。
     *
     * <p>用可点击文本而不是让玩家记住键名：{@code /fmwar button <名字>} 里的名字
     * 与配置键必须完全一致，点一下比手打可靠。</p>
     */
    public void suggestKeys(Player player) {
        // 统一经 AlertService 下发：消息属于玩家自身状态，Folia 上要在该玩家所属线程发
        alerts.sendChat(player, config.settings().message("button-capture-choose"));
        for (Spec.Button button : config.settings().buttons().values()) {
            String key = button.key();
            Component line = LEGACY.deserialize("&e - &f" + key + " &7(" + describe(button) + ")")
                    .clickEvent(ClickEvent.runCommand("/fmwar button " + key))
                    .hoverEvent(Component.text("点击后右键一次目标方块即可绑定到 " + key));
            alerts.sendComponent(player, line);
        }
    }

    private String describe(Spec.Button button) {
        if (button.blocks().isEmpty()) {
            return "未配置坐标";
        }
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (var position : button.blocks()) {
            if (shown > 0) {
                builder.append(" / ");
            }
            builder.append(String.format("%.0f %.0f %.0f", position.x(), position.y(), position.z()));
            shown++;
            if (shown >= 3) {
                break;
            }
        }
        if (button.blocks().size() > 3) {
            builder.append(" …共 ").append(button.blocks().size()).append(" 个");
        }
        return builder.toString();
    }
}
