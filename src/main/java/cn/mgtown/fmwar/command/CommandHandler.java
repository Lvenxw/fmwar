package cn.mgtown.fmwar.command;

import cn.mgtown.FMWar;
import cn.mgtown.fmwar.game.GameEngine;
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
import java.util.List;
import java.util.Map;

/**
 * {@code /fmwar} 指令：reload / start / stop / status / doctor。
 *
 * <p>权限：{@code fmwar.admin}（默认 op）。玩家侧只需要 {@code fmwar.play}（默认所有人），
 * 那条权限只用于按钮点击的预检查，不用在指令上。</p>
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
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "start" -> start(sender);
            case "stop" -> stop(sender);
            case "status" -> status(sender);
            case "doctor" -> doctor(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(alerts.component("&6附魔战争指令："));
        sender.sendMessage(alerts.component("&e/" + label + " reload &7- 重新加载 config.yml"));
        sender.sendMessage(alerts.component("&e/" + label + " start &7- 立即开始一局（跳过准备按钮）"));
        sender.sendMessage(alerts.component("&e/" + label + " stop &7- 强制中止当前对局并清场"));
        sender.sendMessage(alerts.component("&e/" + label + " status &7- 查看当前状态"));
        sender.sendMessage(alerts.component("&e/" + label + " doctor &7- 自检配置与 ExtraShop 集成"));
    }

    private void reload(CommandSender sender) {
        boolean ok = config.reload();
        engine.onReload();
        shops.refresh();
        if (ok) {
            sender.sendMessage(alerts.component(alerts.render("reload-ok", Map.of())));
        } else {
            sender.sendMessage(alerts.component(alerts.render("reload-failed", Map.of())));
        }
        var validation = config.manager().validation();
        if (!validation.ok()) {
            sender.sendMessage(alerts.component("&c配置存在 " + validation.problems().size() + " 个问题："));
            for (var problem : validation.problems()) {
                sender.sendMessage(alerts.component("&7 - &f" + problem.path() + " &7" + problem.detail()));
            }
        }
    }

    private void start(CommandSender sender) {
        if (engine.isRunning()) {
            sender.sendMessage(alerts.component("&c当前已有进行中的对局"));
            return;
        }
        int participants = engine.prepareFromQueue();
        if (participants < 2) {
            sender.sendMessage(alerts.component("&c准备房间内至少需要两名已入队玩家，当前 " + participants + " 名"));
            return;
        }
        sender.sendMessage(alerts.component("&a已强制开始对局，参战 " + participants + " 名玩家"));
    }

    private void stop(CommandSender sender) {
        if (engine.phase() == cn.mgtown.fmwar.game.GamePhase.IDLE) {
            sender.sendMessage(alerts.component("&7当前没有进行中的对局"));
            return;
        }
        engine.requestEnd();
        sender.sendMessage(alerts.component("&a已中止对局，正在清场"));
    }

    private void status(CommandSender sender) {
        sender.sendMessage(alerts.component("&6=== 附魔战争状态 ==="));
        sender.sendMessage(alerts.component("&7阶段: &f" + engine.phase()));
        sender.sendMessage(alerts.component("&7队列人数: &f" + engine.queue().size()));
        sender.sendMessage(alerts.component("&7对局内玩家: &f" + engine.memberCount()));
        sender.sendMessage(alerts.component("&7场地内存活: &f" + engine.aliveCount()));
        sender.sendMessage(alerts.component("&7队伍: &f" + teams.describe()));
        if (engine.phase() != cn.mgtown.fmwar.game.GamePhase.IDLE) {
            sender.sendMessage(alerts.component("&7剩余时间: &f" + TimeUtil.mmss(engine.remainingTicks())));
        }
        if (sender instanceof Player player) {
            sender.sendMessage(alerts.component("&7你的队伍: &f"
                    + (teams.inPlayerTeam(player.getUniqueId()) ? config.settings().teams().player()
                    : teams.inSpectatorTeam(player.getUniqueId()) ? config.settings().teams().spectator()
                    : "无")));
        }
    }

    private void doctor(CommandSender sender) {
        sender.sendMessage(alerts.component("&6=== 附魔战争自检 ==="));
        var validation = config.manager().validation();
        if (validation.ok()) {
            sender.sendMessage(alerts.component("&a配置校验通过"));
        } else {
            for (var problem : validation.problems()) {
                sender.sendMessage(alerts.component("&c - &f" + problem.path() + " &7" + problem.detail()));
            }
        }
        for (String line : shops.diagnose()) {
            sender.sendMessage(alerts.component("&7商店 &f" + line));
        }
        diagnoseLootKeys(sender);
        sender.sendMessage(alerts.component("&7世界: &f" + config.settings().world()
                + " &7奖励箱: &f" + config.settings().loot().chestLocations().size()
                + " &7内容行: &f" + config.settings().loot().lootGroups().size()));
    }

    /**
     * 核对奖励箱与钓竿配置里的附魔键是否在本服注册表里存在。
     *
     * <p>自定义附魔（数据包或附魔插件提供）无法在编译期验证；未注册的键在开局时
     * 只会被跳过并写 WARNING，因此提前在这里报出来，避免"箱子开出来是空的"这类现象
     * 到游戏中才发现。</p>
     */
    private void diagnoseLootKeys(CommandSender sender) {
        java.util.Set<String> configured = new java.util.LinkedHashSet<>();
        for (java.util.List<String> group : config.settings().loot().lootGroups()) {
            for (String raw : group) {
                configured.addAll(cn.mgtown.fmwar.config.LootParser.rawEnchantmentKeys(raw));
            }
        }
        configured.addAll(config.settings().start().rodEnchantments().keySet());

        if (configured.isEmpty()) {
            return;
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String keyText : configured) {
            String normalized = keyText.contains(":") ? keyText : "minecraft:" + keyText;
            org.bukkit.NamespacedKey key;
            try {
                key = org.bukkit.NamespacedKey.fromString(normalized.toLowerCase(java.util.Locale.ROOT));
            } catch (RuntimeException exception) {
                key = null;
            }
            if (key == null || org.bukkit.Registry.ENCHANTMENT.get(key) == null) {
                missing.add(keyText);
            }
        }
        sender.sendMessage(alerts.component("&7附魔键: &f共 " + configured.size() + " 个，"
                + "&a可用 " + (configured.size() - missing.size()) + "&7 / &c缺失 " + missing.size()));
        for (String keyText : missing) {
            sender.sendMessage(alerts.component("&c - 未注册: &f" + keyText
                    + " &7(需要数据包或附魔插件提供)"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(sub);
                }
            }
            return result;
        }
        return List.of();
    }
}
