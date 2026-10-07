package cn.mgtown;

import cn.mgtown.fmwar.command.CommandHandler;
import cn.mgtown.fmwar.config.ConfigManager;
import cn.mgtown.fmwar.config.LootParser;
import cn.mgtown.fmwar.game.GameEngine;
import cn.mgtown.fmwar.listener.ActionGuardListener;
import cn.mgtown.fmwar.listener.ButtonListener;
import cn.mgtown.fmwar.listener.PlayerStateListener;
import cn.mgtown.fmwar.service.AlertService;
import cn.mgtown.fmwar.service.ButtonCapture;
import cn.mgtown.fmwar.service.ConfigService;
import cn.mgtown.fmwar.service.GameScoreboard;
import cn.mgtown.fmwar.service.PointsService;
import cn.mgtown.fmwar.service.ResidenceService;
import cn.mgtown.fmwar.service.ShopService;
import cn.mgtown.fmwar.service.TeamService;
import cn.mgtown.fmwar.util.Schedulers;
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
    private PointsService pointsService;
    private ResidenceService residenceService;
    private Schedulers schedulers;
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
        // 唯一的调度入口：所有服务都通过它调度，绝不直接碰 Bukkit.getScheduler()
        schedulers = new Schedulers(this);
        alertService = new AlertService(configService, schedulers);
        teamService = new TeamService(configService, schedulers);
        gameScoreboard = new GameScoreboard(teamService, schedulers);
        shopService = new ShopService(this, configService);
        pointsService = new PointsService(this);
        residenceService = new ResidenceService(this, configService, schedulers);
        engine = new GameEngine(this, configService, alertService, teamService,
                gameScoreboard, shopService, pointsService, residenceService, schedulers);
        buttonCapture = new ButtonCapture(configService, alertService);

        getServer().getPluginManager().registerEvents(
                new ButtonListener(configService, alertService, engine, buttonCapture), this);
        getServer().getPluginManager().registerEvents(
                new PlayerStateListener(engine), this);
        getServer().getPluginManager().registerEvents(
                new ActionGuardListener(configService, alertService, engine), this);

        CommandHandler handler = new CommandHandler(
                this, configService, alertService, engine, teamService, shopService,
                buttonCapture, pointsService, schedulers);
        PluginCommand command = getCommand("fmwar");
        if (command != null) {
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("plugin.yml 未声明 fmwar 指令，/fmwar 不可用");
        }

        engine.onEnable();
        reportShopReadiness();
        reportWorldReadiness();

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
        if (pointsService != null) {
            pointsService.save();
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

    /**
     * 启动时确认游戏世界用对了。
     *
     * <p>世界名写错是**最难排查的一类配置错误**：插件不会崩，但区域判定、按钮命中、
     * 传送全部静默失效（点按钮没反应、进场地被判离场）。因此这里主动报告世界是否
     * 存在、里面有没有人，让这类错误在启动日志里就暴露。</p>
     */
    private void reportWorldReadiness() {
        String configured = configManager.settings().world();
        var world = getServer().getWorld(configured);
        if (world == null) {
            getLogger().severe("游戏世界 " + configured + " 未加载！本插件的区域判定、按钮与传送都不会生效。"
                    + "请把 config.yml 的 world 改成服务器上实际的世界名后 /fmwar reload");
            return;
        }
        int players = world.getPlayers().size();
        if (players == 0) {
            getLogger().warning("游戏世界 " + configured + " 当前没有玩家。若你的游戏场地其实在别的世界，"
                    + "玩家会看不到任何按钮响应 —— 请核对 config.yml 的 world");
        } else {
            getLogger().info("游戏世界 " + configured + " 已就绪（当前 " + players + " 名玩家）");
        }
        reportDuelReadiness();
    }

    /**
     * 报告每个决斗圈的**实际落点模式**。
     *
     * <p>落点模式由“区域 y 范围 + 配置”共同决定：区域 y 范围窄（≤3 格）时一律锁定高度。
     * 这层推断容易让人误以为“改了配置就生效”，所以启动时直接把结论打出来，
     * 避免再出现“落点跑到决斗圈上方”这种只能靠试的现象。</p>
     */
    private void reportDuelReadiness() {
        var settings = configManager.settings();
        for (var arena : settings.duel().arenas()) {
            var region = settings.optionalRegion(arena.region());
            if (region == null) {
                getLogger().warning("决斗圈 " + arena.region() + " 引用的区域不存在，该圈不会生效");
                continue;
            }
            boolean exact = cn.mgtown.fmwar.config.Settings.resolveExactY(region, arena.exactY());
            double maxY = cn.mgtown.fmwar.config.Settings.resolveMaxY(region, exact, arena.maxY());
            getLogger().info("决斗圈 " + arena.region() + "："
                    + (exact
                            ? "落点高度锁定为 y=" + (long) arena.centerY() + "（区域 y 范围窄，不搜索地形）"
                            : "搜索最高可落脚面，高度上限 " + (long) maxY
                                    + "，允许水面 " + arena.allowWater())
                    + "；区域 y [" + (long) region.minY() + ", " + (long) region.maxY() + "]"
                    + (exact && !arena.exactY()
                            ? "（由区域推断，配置里未写 exact-y）" : ""));
        }
    }
}
