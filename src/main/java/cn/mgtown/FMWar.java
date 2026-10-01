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
import cn.mgtown.fmwar.service.ButtonCapture;
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
    private ButtonCapture buttonCapture;
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
        buttonCapture = new ButtonCapture(configService, alertService);

        getServer().getPluginManager().registerEvents(
                new ButtonListener(configService, alertService, engine, buttonCapture), this);
        getServer().getPluginManager().registerEvents(
                new PlayerStateListener(engine), this);
        getServer().getPluginManager().registerEvents(
                new ActionGuardListener(configService, alertService), this);
        getServer().getPluginManager().registerEvents(
                new TeleportGuardListener(configService, engine), this);

        CommandHandler handler = new CommandHandler(
                this, configService, alertService, engine, teamService, shopService, buttonCapture);
        PluginCommand command = getCommand("fmwar");
        if (command != null) {
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("plugin.yml 未声明 fmwar 指令，/fmwar 不可用");
        }

        engine.onEnable();
        reportShopReadiness();

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

    /**
     * 启动时汇总一次商店就绪情况。
     *
     * <p>商店是否已定义属于**部署前置条件**：它不会阻止插件启用，但没配好的话开局时
     * 场地里不会出现村民。与其在开局逐条刷 WARN（或在停用时对不存在的商店调用 despawn
     * 产生成对噪音），不如在启动时给一行明确结论，逐项状态交给 {@code /fmwar doctor}。</p>
     */
    private void reportShopReadiness() {
        if (shopService == null || !shopService.available()) {
            return;
        }
        var missing = shopService.findUndefinedShops();
        if (missing.isEmpty()) {
            getLogger().info("ExtraShop 集成就绪：配置的商店均已定义");
        } else {
            getLogger().warning("以下商店未在本服定义，开局时不会生成：" + String.join(", ", missing)
                    + " —— 请用 /eshop 配置，或 /fmwar doctor 查看逐项状态"
                    + "；不需要商店时可在 config.yml 设置 extra-shops.enabled=false");
        }
    }
}
