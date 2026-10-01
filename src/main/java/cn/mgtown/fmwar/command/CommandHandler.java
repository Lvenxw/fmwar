package cn.mgtown.fmwar.command;

import cn.mgtown.FMWar;
import cn.mgtown.fmwar.game.GameEngine;
import cn.mgtown.fmwar.game.GamePhase;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ButtonCapture;
import cn.mgtown.fmwar.service.ConfigService;
import cn.mgtown.fmwar.service.PointsService;
import cn.mgtown.fmwar.service.ShopService;
import cn.mgtown.fmwar.service.TeamService;
import cn.mgtown.fmwar.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /fmwar} 指令：reload / start / stop / status / doctor。
 *
 * <p>权限：{@code fmwar.admin}（默认 op）。玩家侧只需要 {@code fmwar.play}（默认所有人），
 * 那条权限只用于按钮点击的预检查，不用在指令上。</p>
 *
 * <p>所有回显文案都取自 {@code messages.command-*} / {@code messages.status-*} /
 * {@code messages.doctor-*}，改配置即可改字，代码里不出现玩家可见的硬编码文案。</p>
 */
public final class CommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("reload", "start", "stop", "status", "doctor", "button", "points", "debug");

    private final FMWar plugin;
    private final ConfigService config;
    private final AlertService alerts;
    private final GameEngine engine;
    private final TeamService teams;
    private final ShopService shops;
    private final ButtonCapture buttonCapture;
    private final PointsService points;

    public CommandHandler(FMWar plugin, ConfigService config, AlertService alerts,
                          GameEngine engine, TeamService teams, ShopService shops,
                          ButtonCapture buttonCapture, PointsService points) {
        this.plugin = plugin;
        this.config = config;
        this.alerts = alerts;
        this.engine = engine;
        this.teams = teams;
        this.shops = shops;
        this.buttonCapture = buttonCapture;
        this.points = points;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        if (!sender.hasPermission("fmwar.admin")) {
            sender.sendMessage(alerts.component(alerts.render("no-permission", Map.of())));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "start" -> start(sender);
            case "stop" -> stop(sender);
            case "status" -> status(sender);
            case "doctor" -> doctor(sender, label);
            case "button" -> button(sender, args);
            case "points" -> points(sender, args);
            case "debug" -> debug(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    /**
     * {@code /fmwar points} —— 积分榜的查看与管理。
     *
     * <pre>
     * /fmwar points                     第 1 页
     * /fmwar points list &lt;页码&gt;         翻页（每页最多 10 条）
     * /fmwar points set &lt;玩家&gt; &lt;分值&gt;   设为指定分值
     * /fmwar points add &lt;玩家&gt; &lt;增量&gt;   加减分（增量可为负）
     * /fmwar points remove &lt;玩家&gt;       删除记录
     * /fmwar points reset               清空全部
     * </pre>
     */
    private void points(CommandSender sender, String[] args) {
        // 无子指令时：给出完整用法提示（此前直接当 list 处理，玩家看不到任何指令说明）
        if (args.length < 2) {
            pointsUsage(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list", "page" -> pointsList(sender, args.length >= 3 ? args[2] : "1");
            case "set" -> pointsSet(sender, args);
            case "add" -> pointsAdd(sender, args);
            case "remove", "delete", "del" -> pointsRemove(sender, args);
            case "reset", "clear" -> pointsReset(sender);
            case "help", "?" -> pointsUsage(sender);
            default -> pointsUsage(sender);
        }
    }

    /** {@code /fmwar debug} —— 切换准备/队列链路的详细日志（排查按钮与倒计时问题）。 */
    private void debug(CommandSender sender) {
        boolean on = engine.toggleDebug();
        line(sender, on ? "debug-on" : "debug-off", Map.of());
    }

    /** 打印积分榜的全部子指令。 */
    private void pointsUsage(CommandSender sender) {
        line(sender, "points-usage-title", Map.of());
        for (String usage : new String[]{"list", "set", "add", "remove", "reset"}) {
            line(sender, "points-usage-" + usage, Map.of());
        }
        line(sender, "points-usage-note", Map.of());
    }

    /** 每页最多显示的条目数。 */
    private static final int POINTS_PAGE_SIZE = 10;

    private void pointsList(CommandSender sender, String pageArg) {
        var ranking = points.ranking();
        int totalPages = Math.max(1, (ranking.size() + POINTS_PAGE_SIZE - 1) / POINTS_PAGE_SIZE);
        int page = parsePage(pageArg, totalPages);
        line(sender, "points-list-title", Map.of(
                "count", Integer.toString(ranking.size()),
                "page", Integer.toString(page),
                "pages", Integer.toString(totalPages)));
        if (ranking.isEmpty()) {
            line(sender, "points-empty", Map.of());
            return;
        }
        int from = (page - 1) * POINTS_PAGE_SIZE;
        int to = Math.min(ranking.size(), from + POINTS_PAGE_SIZE);
        for (int index = from; index < to; index++) {
            var entry = ranking.get(index);
            line(sender, "points-line", Map.of(
                    "rank", Integer.toString(index + 1),
                    "player", entry.name(),
                    "points", Integer.toString(entry.points())));
        }
        if (totalPages > 1) {
            line(sender, "points-page-hint", Map.of(
                    "page", Integer.toString(page),
                    "pages", Integer.toString(totalPages),
                    "next", Integer.toString(page >= totalPages ? 1 : page + 1)));
        }
    }

    private void pointsSet(CommandSender sender, String[] args) {
        UUID target = resolveTarget(sender, args, 2);
        if (target == null) {
            return;
        }
        Integer value = parseInt(args.length >= 4 ? args[3] : null);
        if (value == null) {
            line(sender, "points-usage-set", Map.of());
            return;
        }
        points.setPoints(target, value);
        points.save();
        line(sender, "points-updated", Map.of(
                "player", displayName(target), "points", Integer.toString(Math.max(0, value))));
        if (sender instanceof Player player) {
            engine.refreshPointsBoard(player, 1);
        }
    }

    private void pointsAdd(CommandSender sender, String[] args) {
        UUID target = resolveTarget(sender, args, 2);
        if (target == null) {
            return;
        }
        Integer delta = parseInt(args.length >= 4 ? args[3] : null);
        if (delta == null) {
            line(sender, "points-usage-add", Map.of());
            return;
        }
        int updated = points.adjustPoints(target, delta);
        points.save();
        line(sender, "points-updated", Map.of(
                "player", displayName(target), "points", Integer.toString(updated)));
    }

    private void pointsRemove(CommandSender sender, String[] args) {
        UUID target = resolveTarget(sender, args, 2);
        if (target == null) {
            return;
        }
        boolean removed = points.remove(target);
        points.save();
        line(sender, removed ? "points-removed" : "points-not-found",
                Map.of("player", displayName(target)));
    }

    private void pointsReset(CommandSender sender) {
        int cleared = points.clearAll();
        points.save();
        line(sender, "points-reset", Map.of("count", Integer.toString(cleared)));
    }

    /** 解析目标玩家：先按在线玩家名，再按已记录的玩家名。 */
    private UUID resolveTarget(CommandSender sender, String[] args, int nameIndex) {
        if (args.length <= nameIndex) {
            line(sender, "points-need-player", Map.of());
            return null;
        }
        String name = args[nameIndex];
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            points.remember(online.getUniqueId(), online.getName());
            return online.getUniqueId();
        }
        UUID known = points.findByName(name);
        if (known != null) {
            return known;
        }
        line(sender, "points-unknown-player", Map.of("player", name));
        return null;
    }

    /** 展示用的玩家名：优先记录名，其次在线名，最后截断的 UUID。 */
    private String displayName(UUID uuid) {
        String stored = points.nameOf(uuid);
        if (stored != null) {
            return stored;
        }
        org.bukkit.entity.Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        return uuid.toString().substring(0, 8);
    }

    private int parsePage(String text, int totalPages) {
        Integer value = parseInt(text);
        if (value == null || value < 1) {
            return 1;
        }
        return Math.min(value, totalPages);
    }

    private Integer parseInt(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * {@code /fmwar button} —— 按钮坐标的查看与游戏内校准。
     *
     * <p>无参数：列出每个按钮当前的坐标，提示玩家可以点名字进入校准。
     * 带参数：进入校准等待状态，玩家随后右键一次目标方块即完成登记并写回配置。</p>
     */
    private void button(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(alerts.component("&c按钮校准需要在游戏内执行（要右键方块）"));
            return;
        }
        if (buttonCapture == null) {
            sender.sendMessage(alerts.component("&c按钮校准功能未启用"));
            return;
        }
        if (args.length >= 2) {
            String key = args[1].toLowerCase(Locale.ROOT);
            if (!config.settings().buttons().containsKey(key)) {
                line(sender, "button-capture-failed", Map.of("button", key));
                return;
            }
            buttonCapture.begin(player, key);
            return;
        }

        var buttons = config.settings().buttons();
        line(sender, "button-list-title", Map.of(
                "count", Integer.toString(buttons.size()),
                "radius", String.format("%.1f", buttons.values().stream()
                        .mapToDouble(cn.mgtown.fmwar.config.Spec.Button::radius).max().orElse(2.0))));
        for (var entry : buttons.entrySet()) {
            String blocks = describeBlocks(entry.getValue());
            line(sender, "button-list-line", Map.of("button", entry.getKey(), "blocks", blocks));
        }
        buttonCapture.suggestKeys(player);
    }

    /** 把按钮的全部坐标渲染成一行，最多显示 4 个。 */
    private String describeBlocks(cn.mgtown.fmwar.config.Spec.Button button) {
        if (button.blocks().isEmpty()) {
            return alerts.render("button-list-none", Map.of());
        }
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (var position : button.blocks()) {
            if (shown > 0) {
                builder.append(" / ");
            }
            builder.append(String.format("%.0f %.0f %.0f", position.x(), position.y(), position.z()));
            if (++shown >= 4) {
                break;
            }
        }
        if (button.blocks().size() > shown) {
            builder.append(" …共 ").append(button.blocks().size()).append(" 个");
        }
        return builder.toString();
    }

    /** 按配置文案回显一行。 */
    private void line(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(alerts.component(alerts.render(key, placeholders)));
    }

    private void usage(CommandSender sender, String label) {
        line(sender, "command-usage-title", Map.of());
        for (String sub : SUBCOMMANDS) {
            line(sender, "command-usage-" + sub, Map.of("label", label));
        }
    }

    private void reload(CommandSender sender) {
        boolean ok = config.reload();
        engine.onReload();
        shops.refresh();
        line(sender, "command-reload-ok", Map.of());
        var validation = config.manager().validation();
        if (!validation.ok()) {
            line(sender, "command-reload-problems",
                    Map.of("count", Integer.toString(validation.problems().size())));
            for (var problem : validation.problems()) {
                sender.sendMessage(alerts.component("&7 - &f" + problem.path() + " &7" + problem.detail()));
            }
        } else if (!ok) {
            // 理论上不会走到：ok=false 必然伴随校验问题
            plugin.getLogger().warning("/fmwar reload 返回失败但校验无问题，请查看控制台日志");
        }
    }

    private void start(CommandSender sender) {
        if (engine.isRunning()) {
            line(sender, "command-start-running", Map.of());
            return;
        }
        int participants = engine.prepareFromQueue();
        if (participants < 2) {
            line(sender, "command-start-need-two", Map.of("count", Integer.toString(participants)));
            return;
        }
        line(sender, "command-start-ok", Map.of("count", Integer.toString(participants)));
    }

    private void stop(CommandSender sender) {
        if (engine.phase() == GamePhase.IDLE) {
            line(sender, "command-stop-idle", Map.of());
            return;
        }
        engine.requestEnd();
        line(sender, "command-stop-ok", Map.of());
    }

    private void status(CommandSender sender) {
        line(sender, "status-title", Map.of());
        line(sender, "status-phase", Map.of("phase", engine.phase().name()));
        line(sender, "status-queue", Map.of("count", Integer.toString(engine.queue().size())));
        line(sender, "status-roster", Map.of("count", Integer.toString(engine.memberCount())));
        line(sender, "status-alive", Map.of("count", Integer.toString(engine.aliveCount())));
        line(sender, "status-teams", Map.of("teams", teams.describe()));
        if (engine.phase() != GamePhase.IDLE) {
            line(sender, "status-remaining", Map.of("time", TimeUtil.display(
                    engine.remainingTicks(), config.settings().scoreboard().timeSeconds())));
        }
        if (sender instanceof Player player) {
            String team = teams.inPlayerTeam(player.getUniqueId()) ? config.settings().teams().player()
                    : teams.inSpectatorTeam(player.getUniqueId()) ? config.settings().teams().spectator()
                    : alerts.render("status-team-none", Map.of());
            line(sender, "status-self-team", Map.of("team", team));
        }
    }

    private void doctor(CommandSender sender, String label) {
        line(sender, "doctor-title", Map.of());
        var validation = config.manager().validation();
        if (validation.ok()) {
            line(sender, "doctor-config-ok", Map.of());
        } else {
            for (var problem : validation.problems()) {
                sender.sendMessage(alerts.component("&c - &f" + problem.path() + " &7" + problem.detail()));
            }
        }
        for (String shopLine : shops.diagnose()) {
            line(sender, "doctor-shop-line", Map.of("line", shopLine));
        }
        diagnoseLootKeys(sender);
        line(sender, "doctor-world", Map.of(
                "world", config.settings().world(),
                "chests", Integer.toString(config.settings().loot().chestLocations().size()),
                "groups", Integer.toString(config.settings().loot().lootGroups().size())));
    }

    /**
     * 核对奖励箱与钓竿配置里的附魔键是否在本服注册表里存在。
     *
     * <p>自定义附魔（数据包或附魔插件提供）无法在编译期验证；未注册的键在开局时
     * 只会被跳过并写 WARNING，因此提前在这里报出来，避免"箱子开出来是空的"这类现象
     * 到游戏中才发现。</p>
     */
    private void diagnoseLootKeys(CommandSender sender) {
        Set<String> configured = new LinkedHashSet<>();
        for (List<String> group : config.settings().loot().lootGroups()) {
            for (String raw : group) {
                configured.addAll(cn.mgtown.fmwar.config.LootParser.rawLootEnchantmentKeys(raw));
            }
        }
        configured.addAll(config.settings().start().rodEnchantments().keySet());
        if (configured.isEmpty()) {
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String keyText : configured) {
            String normalized = keyText.contains(":") ? keyText : "minecraft:" + keyText;
            org.bukkit.NamespacedKey key;
            try {
                key = org.bukkit.NamespacedKey.fromString(normalized.toLowerCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                key = null;
            }
            if (key == null || org.bukkit.Registry.ENCHANTMENT.get(key) == null) {
                missing.add(keyText);
            }
        }
        line(sender, "doctor-loot-keys", Map.of(
                "total", Integer.toString(configured.size()),
                "ok", Integer.toString(configured.size() - missing.size()),
                "missing", Integer.toString(missing.size())));
        for (String keyText : missing) {
            line(sender, "doctor-loot-key-missing", Map.of("key", keyText));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("points")) {
            List<String> result = new ArrayList<>();
            for (String action : new String[]{"list", "set", "add", "remove", "reset"}) {
                if (action.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(action);
                }
            }
            return result;
        }
        // points set/add/remove 的第三个参数补全玩家名（在线玩家 + 已有记录）
        if (args.length == 3 && args[0].equalsIgnoreCase("points")) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("set") || action.equals("add") || action.equals("remove")
                    || action.equals("delete") || action.equals("del")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                List<String> result = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(online.getName());
                    }
                }
                for (var entry : points.ranking()) {
                    if (entry.name().toLowerCase(Locale.ROOT).startsWith(prefix) && !result.contains(entry.name())) {
                        result.add(entry.name());
                    }
                }
                return result;
            }
        }
        return List.of();
    }
}
