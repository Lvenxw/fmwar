package cn.mgtown.fmwar.service;

import cn.mgtown.fmwar.config.Settings;
import cn.mgtown.fmwar.util.Schedulers;
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
 *
 * <p><b>线程归属</b>：给玩家发消息改的是玩家自身的网络连接状态，在 Folia 上必须落在
 * <b>该玩家所属的区域线程</b>。而本类的调用方几乎都在插件权威线程上（状态机 tick、
 * 指令、事件守卫之后的处理体），因此真正的下发统一经 {@link Schedulers#runOwned}——
 * Paper 上就地执行（与改造前逐字一致），Folia 上才派发过去。
 * {@link #canSee} 读的是 {@code player.getLocation()}，在 Folia 上是允许的快照读，
 * 不需要归属化，于是“过滤”仍留在当前线程做。</p>
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
    private final Schedulers schedulers;

    public AlertService(ConfigService config, Schedulers schedulers) {
        this.config = config;
        this.schedulers = schedulers;
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

    /** 直接发送已经渲染好的 Component（聊天气泡/可点击文本等）。 */
    public void sendComponent(Player player, Component component) {
        if (player == null || component == null) {
            return;
        }
        schedulers.runOwned(player, () -> player.sendMessage(component), null);
    }

    /** 直接发送已渲染文本。 */
    public void sendChat(Player player, String rendered) {
        if (player == null) {
            return;
        }
        // 消息下发是“玩家自身状态”：Folia 上必须在该玩家所属线程。Paper 上就地执行。
        schedulers.runOwned(player, () -> player.sendMessage(LEGACY.deserialize(rendered)), null);
    }

    /** 直接发送动作栏文本。 */
    public void sendActionBar(Player player, String rendered) {
        if (player == null) {
            return;
        }
        schedulers.runOwned(player, () -> player.sendActionBar(LEGACY.deserialize(rendered)), null);
    }

    /** 供其它服务复用：把 & 文案转成 Component。 */
    public Component component(String rendered) {
        return LEGACY.deserialize(rendered);
    }
}
