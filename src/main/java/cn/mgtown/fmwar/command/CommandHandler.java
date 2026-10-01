package cn.mgtown.fmwar.command;

import cn.mgtown.FMWar;
import cn.mgtown.fmwar.game.GameEngine;
import cn.mgtown.fmwar.game.GamePhase;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ConfigService;
import cn.mgtown.fmwar.service.ShopService;
import cn.mgtown.fmwar.service.TeamService;
import cn.mgtown.fmwar.util.TimeUtil;
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

    private static final List<String> SUBCOMMANDS = List.of("reload", "start", "stop", "status", "doctor");

    private final FMWar plugin;
    private final ConfigService config;
    private final AlertService alerts;
    private final GameEngine engine;
    private final TeamService teams;
    private final ShopService shops;

    public CommandHandler(FMWar plugin, ConfigService config, AlertService alerts,
                          GameEngine engine, TeamService teams, ShopService shops) {
        this.plugin = plugin;
        this.config = config;
        this.alerts = alerts;
        this.engine = engine;
        this.teams = teams;
        this.shops = shops;
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
            default -> usage(sender, label);
        }
        return true;
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
            line(sender, "status-remaining", Map.of("time", TimeUtil.mmss(engine.remainingTicks())));
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
        return List.of();
    }
}
