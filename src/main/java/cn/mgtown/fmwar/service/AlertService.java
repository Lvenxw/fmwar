package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 提示的渲染与投递。
 *
 * <p>两条规格约束在这里落地：</p>
 * <ul>
 *   <li>“提示仅在提示接收范围内的玩家可见”——除 {@code sendTo} 这类明确只给某人的提示外，
 *       所有广播都会用 {@link #canSee} 过滤。</li>
 *   <li>高频状态提示走动作栏，关键事件（开始/胜利/无人生还）走聊天栏。</li>
 * </ul>
 */
public final class AlertService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** 占位符 -> 替换值；{@code {prefix}} 会在渲染时自动补上。 */
    public static final String PREFIX = "{prefix}";

    /**
     * 仍使用原色 {@code {prefix}} 的消息（“玩家动向”提示，颜色保持原样）。
     *
     * <p>其余全部消息统一改用 light_purple 的 {@code war-prefix}。
     * 在渲染层按消息键决定前缀，而不是把几十条文案各改一遍——这样以后新增文案
     * 默认就是 light_purple，不会再漏。</p>
     */
    private static final Set<String> QUEUE_MESSAGES = Set.of(
            "queue-join",
            "queue-leave",
            "queue-joined-self",
            "queue-left-self",
            "queue-already-joined");

    /** 判断某条消息该用哪个前缀（返回占位符名）。 */
    public static boolean usesPlainPrefix(String messageKey) {
        return QUEUE_MESSAGES.contains(messageKey);
    }

    private final ConfigService config;

    public AlertService(ConfigService config) {
        this.config = config;
    }

    /** 判断玩家是否在提示接收范围内。 */
    public boolean canSee(Player player) {
        return config.settings().region("notify").contains(player.getLocation());
    }

    /** 渲染文案：替换占位符并保留 & 颜色代码。 */
    public String render(String messageKey, Map<String, String> placeholders) {
        Settings settings = config.settings();
        String raw = settings.message(messageKey);
        // 服主的 config.yml 是他自己的文件，插件不会覆盖它——升级后新增的文案键
        // 在他那份里并不存在。此时退回 jar 内置默认文案，而不是把键名
        //（例如 prepare-countdown-bar）直接显示给玩家。
        if (raw.equals(messageKey)) {
            String fallback = config.manager().defaultMessages().get(messageKey);
            if (fallback != null) {
                raw = fallback;
            }
        }
        String plain = settings.prefix() == null ? "" : settings.prefix();
        String war = settings.warPrefix() == null ? plain : settings.warPrefix();
        // “玩家动向”那几条保持原色，其余一律 light_purple
        String chosen = usesPlainPrefix(messageKey) ? plain : war;
        String text = raw.replace(PREFIX, chosen).replace("{war_prefix}", chosen);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return text;
    }

    /** 发给单个玩家（聊天栏）。 */
    public void sendTo(Player player, String messageKey, Map<String, String> placeholders) {
        sendChat(player, render(messageKey, placeholders));
    }

    /** 发给单个玩家（动作栏）。 */
    public void sendActionBarTo(Player player, String messageKey, Map<String, String> placeholders) {
        sendActionBar(player, render(messageKey, placeholders));
    }

    /** 发给单个玩家：高频状态 -> 动作栏，其余 -> 聊天栏。 */
    public void sendStatusTo(Player player, String messageKey, Map<String, String> placeholders) {
        String rendered = render(messageKey, placeholders);
        if (config.settings().actionbarMessages()) {
            sendActionBar(player, rendered);
        } else {
            sendChat(player, rendered);
        }
    }

    /** 广播给提示接收范围内的所有玩家（聊天栏）。 */
    public void broadcast(String messageKey, Map<String, String> placeholders) {
        String rendered = render(messageKey, placeholders);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (canSee(player)) {
                sendChat(player, rendered);
            }
        }
    }

    /** 只发给指定玩家集合，且仍需落在提示接收范围内。 */
    public void broadcastTo(Collection<Player> players, String messageKey, Map<String, String> placeholders) {
        String rendered = render(messageKey, placeholders);
        for (Player player : players) {
            if (canSee(player)) {
                sendChat(player, rendered);
            }
        }
    }

    /** 直接发送已渲染文本。 */
    public void sendChat(Player player, String rendered) {
        player.sendMessage(LEGACY.deserialize(rendered));
    }

    /** 直接发送动作栏文本。 */
    public void sendActionBar(Player player, String rendered) {
        player.sendActionBar(LEGACY.deserialize(rendered));
    }

    /** 供其它服务复用：把 & 文案转成 Component。 */
    public Component component(String rendered) {
        return LEGACY.deserialize(rendered);
    }
}
