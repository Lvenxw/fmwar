package cn.mgtown;

import cn.mgtown.fmwar.command.CommandHandler;
import cn.mgtown.fmwar.config.ConfigManager;
import cn.mgtown.fmwar.config.LootParser;
import cn.mgtown.fmwar.game.GameEngine;
import cn.mgtown.fmwar.listener.ActionGuardListener;
import cn.mgtown.fmwar.listener.ButtonListener;
import cn.mgtown.fmwar.listener.PlayerStateListener;
import cn.mgtown.fmwar.listener.TeleportGuardListener;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ConfigService;
import cn.mgtown.fmwar.service.GameScoreboard;
import cn.mgtown.fmwar.service.ShopService;
import cn.mgtown.fmwar.service.TeamService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 附魔战争主类。
 *
 * <p>装配顺序有意固定为：配置 -> 服务 -> 对局引擎 -> 监听器 -> 指令。
 * 配置在最后才被服务引用，因此 /fmwar reload 只需替换 {@link ConfigManager} 里的快照，
 * 各服务通过 {@link ConfigService} 每次现取，不需要重注册监听器。</p>
 */
public final class FMWar extends JavaPlugin {

    private ConfigManager configManager;
    private ConfigService configService;
    private AlertService alertService;
    private TeamService teamService;
    private GameScoreboard gameScoreboard;
    private ShopService shopService;
    private GameEngine engine;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);

        // 首次运行先释放默认 config.yml，再加载
        configManager.saveDefaultIfMissing();
        if (!configManager.load()) {
            getLogger().severe("配置加载失败，插件不会注册任何玩法逻辑");
            return;
        }

        configService = new ConfigService(configManager);
        alertService = new AlertService(configService);
        teamService = new TeamService(configService);
        gameScoreboard = new GameScoreboard(teamService);
        shopService = new ShopService(this, configService);
        engine = new GameEngine(this, configService, alertService, teamService, gameScoreboard, shopService);

        getServer().getPluginManager().registerEvents(
                new ButtonListener(configService, alertService, engine), this);
        getServer().getPluginManager().registerEvents(
                new PlayerStateListener(engine), this);
        getServer().getPluginManager().registerEvents(
                new ActionGuardListener(configService, alertService), this);
        getServer().getPluginManager().registerEvents(
                new TeleportGuardListener(configService, engine), this);

        CommandHandler handler = new CommandHandler(
                this, configService, alertService, engine, teamService, shopService);
        PluginCommand command = getCommand("fmwar");
        if (command != null) {
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("plugin.yml 未声明 fmwar 指令，/fmwar 不可用");
        }

        engine.onEnable();

        var validation = configManager.validation();
        if (!validation.ok()) {
            getLogger().warning("配置校验发现 " + validation.problems().size()
                    + " 个问题，/fmwar doctor 可查看详情");
        }
        getLogger().info("附魔战争已启用");
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.onDisable();
        }
        getLogger().info("附魔战争已停用");
    }

    /** 供各服务读取物品解析器（奖励箱内容）。 */
    public LootParser lootParser() {
        return configManager == null ? null : configManager.lootParser();
    }
}
