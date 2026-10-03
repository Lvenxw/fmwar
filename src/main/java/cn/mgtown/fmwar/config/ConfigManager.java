package cn.mgtown.fmwar.config;

import cn.mgtown.FMWar;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置的加载、校验与原子替换。
 *
 * <p>reload 语义：先在内存里完整解析出一份 {@link Settings}，全部成功后才替换运行期快照。
 * 任何一步失败都保留旧配置并返回错误，避免"加载了一半"的状态污染进行中的对局。</p>
 */
public final class ConfigManager {

    /** 需求文档里的按钮/区域键，缺失即视为配置错误。 */
    private static final List<String> REQUIRED_REGIONS =
            List.of("arena", "notify", "prep-room", "hall", "duel-1", "duel-2");
    private static final List<String> REQUIRED_LOCATIONS =
            List.of("arena-spawn", "prep-spawn", "hall-spawn");
    private static final List<String> REQUIRED_BUTTONS =
            List.of("join", "prepare", "back-to-hall", "spectator");

    /**
     * 必须在服主自己的 config.yml 里出现的路径。
     *
     * <p>这些项若只靠 jar 内置 defaults 补齐，说明文件被清空或截断——此时宁可拒绝加载，
     * 也不能把服主的配置悄悄换成默认值。</p>
     */
    private static final List<String> CRITICAL_PATHS = List.of(
            "world",
            "regions.arena.min", "regions.arena.max",
            "regions.notify.min", "regions.notify.max",
            "regions.prep-room.min", "regions.prep-room.max",
            "regions.hall.min", "regions.hall.max",
            "regions.duel-1.min", "regions.duel-1.max",
            "regions.duel-2.min", "regions.duel-2.max",
            "locations.arena-spawn.point", "locations.prep-spawn.point", "locations.hall-spawn.point",
            "timing.game-duration", "timing.prepare-clicks",
            "chests.locations", "chests.loot-groups",
            "extra-shops.ids");

    private final FMWar plugin;
    private final File file;
    private final LootParser lootParser;

    private volatile Settings settings;
    private volatile Spec.Validation validation = new Spec.Validation(List.of());
    /** 本次加载中缺失的必填键（用于 validate 报告，避免靠“值为 0”猜）。 */
    private volatile java.util.Set<String> missingRequired = java.util.Set.of();

    /**
     * jar 内置默认文案。
     *
     * <p>服主的 config.yml 是他自己的文件，插件不会覆盖它——因此升级后新增的文案键
     * 在他那份文件里并不存在。此前这类缺失会退化成“把键名当文案显示”
     * （例如界面直接出现 {@code prepare-countdown-bar}），这里改为回退到内置默认文案。</p>
     */
    private volatile Map<String, String> defaultMessages = Map.of();

    /** jar 内置的默认文案（供 AlertService 兜底）。 */
    public Map<String, String> defaultMessages() {
        return defaultMessages;
    }

    /** 读取一份配置里的 messages 段。 */
    private Map<String, String> readMessages(FileConfiguration yaml) {
        Map<String, String> result = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("messages");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof String text) {
                result.put(key, text);
            }
        }
        return Map.copyOf(result);
    }

    public ConfigManager(FMWar plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "config.yml");
        this.lootParser = new LootParser(message -> plugin.getLogger().warning("[config] " + message));
    }

    public LootParser lootParser() {
        return lootParser;
    }

    public Settings settings() {
        return settings;
    }

    public Spec.Validation validation() {
        return validation;
    }

    /** 保存默认配置（不存在时）并加载；返回是否加载成功。 */
    public boolean load() {
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // 用打包在 jar 里的默认配置补全缺失项，方便版本升级后平滑增项
        FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(
                        java.util.Objects.requireNonNull(
                                plugin.getResource("config.yml"), "jar 内缺少 config.yml"),
                        java.nio.charset.StandardCharsets.UTF_8));
        yaml.setDefaults(defaults);
        this.defaultMessages = readMessages(defaults);

        Settings parsed = parse(yaml);
        Spec.Validation checked = validate(yaml, parsed);
        if (!checked.ok()) {
            // 校验不通过时保留上一份可用配置：reload 写坏了 YAML 不应该让进行中的对局
            // 立刻切到半残配置（例如区域被占位成 0,0,0）
            plugin.getLogger().warning("配置存在问题，已保留上一份可用配置：" + checked.describe());
            this.validation = checked;
            return false;
        }
        this.settings = parsed;
        this.validation = checked;
        plugin.getLogger().info("配置加载完成");
        return true;
    }
    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private Settings parse(FileConfiguration yaml) {
        String defaultWorld = yaml.getString("world", "");
        if (defaultWorld == null || defaultWorld.isBlank()) {
            defaultWorld = "world";
        }

        Map<String, Region> regions = parseRegions(yaml, defaultWorld);
        Map<String, Position> locations = parseLocations(yaml, defaultWorld);
        Map<String, Spec.Button> buttons = parseButtons(yaml, defaultWorld);
        applySafeDefaults(regions, locations, buttons, defaultWorld);

        Settings.Timing timing = new Settings.Timing(
                yaml.getLong("timing.prepare-clicks", 7),
                yaml.getLong("timing.prepare-countdown", 10),
                yaml.getLong("timing.game-duration", 900),
                yaml.getLong("timing.duel-teleport-at", 60),
                yaml.getLong("timing.emerald-interval", 30),
                yaml.getDouble("timing.overtime-damage", 2.0));

        // center 是标准 [x, y, z] 坐标。y 允许省略（省略时该位不参与计算）。
        double[] disperseCenter = parseCenter(yaml, "disperse.center", defaultWorld);
        Settings.Disperse disperse = new Settings.Disperse(
                yaml.getBoolean("disperse.enabled", true),
                disperseCenter[0], disperseCenter[1], disperseCenter[2],
                yaml.getDouble("disperse.radius", 100.0),
                yaml.getDouble("disperse.min-spacing", 25.0),
                (int) yaml.getLong("disperse.max-attempts", 600));

        Settings.Duel duel = parseDuel(yaml, defaultWorld);

        // 钓竿整组收进 FishingRod record：Start 只多一个字段，
        // 之后钓竿内部加项（例如新的显示属性）都不必再改 Start 的签名
        Settings.FishingRod fishingRod = new Settings.FishingRod(
                yaml.getBoolean("start.fishing-rod.enabled", true),
                yaml.getString("start.fishing-rod.material", "FISHING_ROD"),
                lootParser.parseEnchantments(
                        yaml.getStringList("start.fishing-rod.enchantments"),
                        "start.fishing-rod.enchantments"),
                // name 缺失 / 空串都归一为 ""，applyDisplay 用 isBlank 判空
                yaml.getString("start.fishing-rod.name", ""),
                yaml.getBoolean("start.fishing-rod.unbreakable", false),
                yaml.getBoolean("start.fishing-rod.hide-unbreakable", false),
                // 显式拷贝成不可变列表：record 的 List 字段若直接指向 YAML 内部结构，
                // 后续 reload 时可能与旧快照共享同一份底层数组
                List.copyOf(yaml.getStringList("start.fishing-rod.lore")));

        Settings.Start start = new Settings.Start(
                yaml.getBoolean("start.clear-inventory", true),
                yaml.getBoolean("start.clear-effects", true),
                yaml.getBoolean("start.block-sneak", false),
                yaml.getBoolean("start.loot-books-only", true),
                yaml.getBoolean("start.resistance.enabled", true),
                yaml.getLong("start.resistance.duration-ticks", 100),
                (int) yaml.getLong("start.resistance.amplifier", 4),
                yaml.getBoolean("start.heal", true),
                fishingRod);

        Settings.Emerald emerald = new Settings.Emerald(
                (int) yaml.getLong("emerald.amount", 1));

        List<Position> chestLocations = new ArrayList<>();
        for (Object raw : yaml.getList("chests.locations", List.of())) {
            Position position = parsePosition(raw, defaultWorld);
            if (position != null) {
                chestLocations.add(position);
            }
        }
        List<List<String>> lootGroups = new ArrayList<>();
        for (Object rawGroup : yaml.getList("chests.loot-groups", List.of())) {
            List<String> group = new ArrayList<>();
            if (rawGroup instanceof List<?> list) {
                for (Object element : list) {
                    group.add(String.valueOf(element));
                }
            } else if (rawGroup != null) {
                group.add(String.valueOf(rawGroup));
            }
            if (!group.isEmpty()) {
                lootGroups.add(List.copyOf(group));
            }
        }
        Settings.Loot loot = new Settings.Loot(List.copyOf(chestLocations), List.copyOf(lootGroups));

        Settings.Teams teams = new Settings.Teams(
                yaml.getString("teams.player", "fm"),
                yaml.getString("teams.spectator", "fmgz"));

        Settings.Scoreboard scoreboard = new Settings.Scoreboard(
                yaml.getBoolean("scoreboard.enabled", true),
                yaml.getString("scoreboard.title", "附魔战争"),
                yaml.getBoolean("scoreboard.time-seconds", true),
                yaml.getString("scoreboard.time-line", "&e剩余时间 &f{time}"),
                yaml.getString("scoreboard.alive-line", "&e存活人数 &f{alive}"),
                yaml.getBoolean("scoreboard.points-enabled", true),
                yaml.getString("scoreboard.points-main", "fmjfb"),
                yaml.getString("scoreboard.points-title", "&6附魔战争积分榜"),
                yaml.getString("scoreboard.points-header", "&e玩家 &7| &e积分"),
                yaml.getString("scoreboard.points-line", "&f{rank}. &a{player} &7- &e{points}"),
                (int) yaml.getLong("scoreboard.points-rows", 10));

        // getConfigurationSection 会合并 defaults，因此这里拿到的已经是
        // “服主文件 + jar 内置默认”的完整文案表；缺失键的具体兜底逻辑在 AlertService
        Map<String, String> messages = readMessages(yaml);
        boolean actionbar = Boolean.parseBoolean(messages.getOrDefault("actionbar", "true"));

        List<String> shopIds = new ArrayList<>();
        for (String id : yaml.getStringList("extra-shops.ids")) {
            if (id != null && !id.isBlank()) {
                shopIds.add(id.trim());
            }
        }

        return new Settings(
                yaml.getString("prefix", "&6[附魔战争]&r "),
                yaml.getString("war-prefix", "&d[附魔战争]&r "),
                defaultWorld,
                Map.copyOf(regions),
                Map.copyOf(locations),
                Map.copyOf(buttons),
                timing,
                disperse,
                duel,
                start,
                emerald,
                loot,
                List.copyOf(shopIds),
                yaml.getBoolean("extra-shops.enabled", true),
                teams,
                scoreboard,
                new Settings.Residence(
                        yaml.getBoolean("residence.enabled", true),
                        yaml.getString("residence.flag", "move"),
                        yaml.getStringList("residence.prep-regions"),
                        yaml.getStringList("residence.arena-regions")),
                Map.copyOf(messages),
                actionbar);
    }

    /**
     * 给缺失的必填项补一个“空但安全”的占位值。
     *
     * <p>两类失效方式必须区分：配置错误应当在 /fmwar doctor 里被明确报出来，
     * 但不应该让主循环每 tick 抛异常。所以缺失项在这里补占位值，由 validate() 负责报告。</p>
     */
    private void applySafeDefaults(Map<String, Region> regions, Map<String, Position> locations,
                                   Map<String, Spec.Button> buttons, String defaultWorld) {
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        for (String key : REQUIRED_REGIONS) {
            if (regions.putIfAbsent(key, Region.of(defaultWorld, 0, 0, 0, 0, 0, 0)) == null) {
                missing.add("regions." + key);
            }
        }
        for (String key : REQUIRED_LOCATIONS) {
            if (locations.putIfAbsent(key, Position.of(defaultWorld, 0, 64, 0, null, 0.0, 0.0)) == null) {
                missing.add("locations." + key);
            }
        }
        for (String key : REQUIRED_BUTTONS) {
            Spec.Button placeholder = new Spec.Button(key,
                    Position.of(defaultWorld, 0, 64, 0, null, 0.0, 0.0), 1.5);
            if (buttons.putIfAbsent(key, placeholder) == null) {
                missing.add("buttons." + key);
            }
        }
        this.missingRequired = java.util.Set.copyOf(missing);
    }

    private Map<String, Region> parseRegions(FileConfiguration yaml, String defaultWorld) {
        Map<String, Region> regions = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("regions");
        if (section == null) {
            return regions;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection regionSection = section.getConfigurationSection(key);
            if (regionSection == null) {
                continue;
            }
            double[] min = parseTriple(regionSection.get("min"));
            double[] max = parseTriple(regionSection.get("max"));
            if (min == null || max == null) {
                plugin.getLogger().warning("[config] regions." + key + " 缺少 min/max 角点，已跳过");
                continue;
            }
            String world = regionSection.getString("world", defaultWorld);
            regions.put(key, Region.of(world, min[0], min[1], min[2], max[0], max[1], max[2]));
        }
        return regions;
    }

    private Map<String, Position> parseLocations(FileConfiguration yaml, String defaultWorld) {
        Map<String, Position> locations = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("locations");
        if (section == null) {
            return locations;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            String world = entry.getString("world", defaultWorld);
            double[] point = parseTriple(entry.get("point"));
            if (point == null) {
                plugin.getLogger().warning("[config] locations." + key + " 缺少 point，已跳过");
                continue;
            }
            double[] facing = parseTriple(entry.get("facing"));
            Position position = Position.of(world, point[0], point[1], point[2], facing,
                    entry.getDouble("yaw", 0.0), entry.getDouble("pitch", 0.0));
            locations.put(key, position);
        }
        return locations;
    }

    private Map<String, Spec.Button> parseButtons(FileConfiguration yaml, String defaultWorld) {
        Map<String, Spec.Button> buttons = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("buttons");
        if (section == null) {
            return buttons;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            String world = entry.getString("world", defaultWorld);
            double radius = entry.getDouble("radius", 2.0);
            List<Position> positions = parseButtonBlocks(entry, world);
            if (positions.isEmpty()) {
                plugin.getLogger().warning("[config] buttons." + key + " 缺少 block/blocks，已跳过");
                continue;
            }
            buttons.put(key, new Spec.Button(key, List.copyOf(positions), radius));
        }
        return buttons;
    }

    /**
     * 解析按钮坐标，同时支持单坐标与多坐标写法：
     * <pre>
     * block:  [-880, 103, -1711]          # 单方块
     * block:  { x: -880, y: 103, z: -1711 }
     * blocks: [[-880, 103, -1711], [-881, 103, -1711]]
     * </pre>
     */
    private List<Position> parseButtonBlocks(ConfigurationSection entry, String world) {
        List<Position> positions = new ArrayList<>();
        Object single = entry.get("block");
        if (single != null) {
            double[] triple = parseTriple(single);
            if (triple != null) {
                positions.add(Position.of(world, triple[0], triple[1], triple[2], null, 0.0, 0.0));
            }
        }
        for (Object raw : entry.getList("blocks", List.of())) {
            double[] triple = parseTriple(raw);
            if (triple != null) {
                positions.add(Position.of(world, triple[0], triple[1], triple[2], null, 0.0, 0.0));
            }
        }
        return positions;
    }

    // ------------------------------------------------------------------
    // 按钮就地校准（由 /fmwar button 调用）
    // ------------------------------------------------------------------

    /**
     * 把一个方块登记到指定按钮上，并写回 config.yml。
     *
     * <p>用途：服务器上按钮的实际方块坐标常常与需求文档的坐标差一两格（按钮贴墙时
     * {@code PlayerInteractEvent#getClickedBlock()} 返回的是被点中的那一格），
     * 用指令就地校准比手改 YAML 再 reload 更不容易出错。</p>
     *
     * @return 注册成功返回 true；按钮键未知或写入失败返回 false
     */
    public boolean appendButtonBlock(String key, String world, int x, int y, int z) {
        if (settings == null || !settings.buttons().containsKey(key)) {
            return false;
        }
        // 用 loadConfiguration 而不是 loadConfiguration+setDefaults：
        // 显式读取才能保证 save 时写回的是服主文件里的完整内容，改动最小
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("buttons." + key);
        if (section == null) {
            section = yaml.createSection("buttons." + key);
        }
        List<List<Integer>> blocks = new ArrayList<>();
        Object existing = section.get("block");
        if (existing != null) {
            double[] triple = parseTriple(existing);
            if (triple != null) {
                blocks.add(List.of((int) triple[0], (int) triple[1], (int) triple[2]));
            }
        }
        for (Object raw : section.getList("blocks", List.of())) {
            double[] triple = parseTriple(raw);
            if (triple != null) {
                blocks.add(List.of((int) triple[0], (int) triple[1], (int) triple[2]));
            }
        }
        List<Integer> target = List.of(x, y, z);
        if (blocks.contains(target)) {
            return false;
        }
        blocks.add(target);
        // 统一写进 blocks 列表；同时清掉单值 block，避免两处坐标叠加造成歧义
        section.set("block", null);
        section.set("blocks", blocks);
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("按钮配置写入失败: " + exception.getMessage());
            return false;
        }
        plugin.getLogger().info("已把按钮 " + key + " 的坐标 " + x + " " + y + " " + z + " 写入 config.yml");
        return true;
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private Spec.Validation validate(FileConfiguration yaml, Settings parsed) {
        List<Spec.Problem> problems = new ArrayList<>();
        Map<String, Region> regions = new LinkedHashMap<>(parsed.regions());
        Map<String, Position> locations = new LinkedHashMap<>(parsed.locations());

        // 关键项必须在**服主自己的文件**里存在，否则视为损坏配置。
        // 原因：Bukkit 读取配置时会回退到 jar 内置的 defaults，一份被写成空文件或被
        // 截断的 config.yml 会被 defaults 补全并通过所有数值校验，从而把服主原来的
        // 配置悄悄替换成默认值。isSet 不查 defaults，能识别出这种情况。
        for (String path : CRITICAL_PATHS) {
            if (!yaml.isSet(path)) {
                problems.add(new Spec.Problem(path, "配置文件缺少该项（可能被清空或截断），已拒绝加载以保留上一份可用配置"));
            }
        }
        // 按钮允许单坐标(block)或多坐标(blocks)两种写法，因此逐个按钮判断
        for (String key : REQUIRED_BUTTONS) {
            if (!yaml.isSet("buttons." + key + ".block") && !yaml.isSet("buttons." + key + ".blocks")) {
                problems.add(new Spec.Problem("buttons." + key,
                        "缺少 block / blocks（可能被清空或截断），已拒绝加载以保留上一份可用配置"));
            }
        }

        for (String key : REQUIRED_REGIONS) {
            if (missingRequired.contains("regions." + key)) {
                problems.add(new Spec.Problem("regions." + key, "缺失（已用空区域占位，玩法不会生效）"));
                continue;
            }
            Region region = regions.get(key);
            if (region == null) {
                problems.add(new Spec.Problem("regions." + key, "缺失"));
                continue;
            }
            if (worldMissing(region.world())) {
                problems.add(new Spec.Problem("regions." + key + ".world", "世界未加载: " + region.world()));
            }
        }

        // 开局分散圆与决斗圈必须完全落在场地内（水平投影）。
        // 越界落点会被 checkArenaPresence 立刻判成“离开游戏”，表现为一开局就“无人生还”。
        Region arenaRegion = regions.get("arena");
        if (arenaRegion != null && !missingRequired.contains("regions.arena")) {
            Settings.Disperse disperse = parsed.disperse();
            if (disperse.enabled() && !arenaRegion.containsCircleXZ(
                    disperse.centerX(), disperse.centerZ(), disperse.radius())) {
                problems.add(new Spec.Problem("disperse",
                        "分散圆越出 regions.arena（中心 " + fmt(disperse.centerX()) + "," + fmt(disperse.centerZ())
                                + " 半径 " + fmt(disperse.radius()) + " 超出场地边界）"
                                + " —— 玩家会落在场地外并被立刻判为离开游戏"));
            }
            Settings.Duel duel = parsed.duel();
            for (Settings.DuelArena arena : duel.arenas()) {
                // 决斗圈的区域（duel-1 / duel-2）必须**完全落在场地内**：
                // 只要有一角伸出场地，被传到那里的玩家会立刻被判“离开游戏”。
                // 判定用区域本身而不是“中心+半径”，因为每个圈的范围就是它自己的区域。
                Region duelRegion = regions.get(arena.region());
                if (duelRegion == null) {
                    problems.add(new Spec.Problem("duel.arenas",
                            "决斗圈引用了不存在的区域 regions." + arena.region()
                                    + " —— 该决斗圈不会生效"));
                    continue;
                }
                if (!arenaRegion.covers(duelRegion)) {
                    problems.add(new Spec.Problem("regions." + arena.region(),
                            "未完全落在 regions.arena 之内"
                                    + " —— 被传到界外的玩家会被立刻判为离开游戏"));
                }
                // 用解析后的实际模式校验：区域 y 范围窄时一律锁定高度
                boolean exactY = Settings.resolveExactY(duelRegion, arena.exactY());
                if (exactY) {
                    // 锁定高度模式：center 的 y 必须落在区域 y 范围内，否则永远采不到点
                    if (arena.centerY() < duelRegion.minY() || arena.centerY() > duelRegion.maxY()) {
                        problems.add(new Spec.Problem("duel.arenas[].center",
                                "锁定高度模式下高度 " + fmt(arena.centerY())
                                        + " 不在 regions." + arena.region() + " 的 y 范围 ["
                                        + fmt(duelRegion.minY()) + ", " + fmt(duelRegion.maxY())
                                        + "] 内 —— 永远找不到落点"));
                    }
                } else {
                    double maxY = Settings.resolveMaxY(duelRegion, false, arena.maxY());
                    if (maxY <= 0) {
                        problems.add(new Spec.Problem("duel.arenas[].max-y",
                                "未设置高度上限 —— 室内场地的封顶玻璃会被当成“最高可落脚面”，"
                                        + "玩家会站到屋顶上。请给出 max-y，或改用 exact-y"));
                    } else if (maxY < duelRegion.minY()) {
                        problems.add(new Spec.Problem("duel.arenas[].max-y",
                                "高度上限 " + fmt(maxY) + " 低于区域 regions." + arena.region()
                                        + " 的最低高度 " + fmt(duelRegion.minY()) + " —— 永远找不到落点"));
                    }
                }
            }
        }
        for (String key : REQUIRED_LOCATIONS) {
            if (missingRequired.contains("locations." + key)) {
                problems.add(new Spec.Problem("locations." + key, "缺失（已用占位坐标，传送不会生效）"));
                continue;
            }
            Position position = locations.get(key);
            if (position == null) {
                problems.add(new Spec.Problem("locations." + key, "缺失"));
                continue;
            }
            if (worldMissing(position.world())) {
                problems.add(new Spec.Problem("locations." + key + ".world", "世界未加载: " + position.world()));
            }
        }

        // 世界一致性：本插件的区域/传送点/按钮/奖励箱都只在同一个世界里生效。
        // 若某项自带的 world 与顶层 world 不一致，全部判定会静默失效（点按钮没反应、
        // 进场地被判离场、传送失效），这类故障排查起来极费劲，因此加载期就报出来。
        for (Map.Entry<String, Region> entry : regions.entrySet()) {
            if (missingRequired.contains("regions." + entry.getKey())) {
                continue;
            }
            if (!entry.getValue().world().equals(parsed.world())) {
                problems.add(new Spec.Problem("regions." + entry.getKey() + ".world",
                        "与顶层 world(" + parsed.world() + ") 不一致: " + entry.getValue().world()
                                + " —— 跨世界配置会让该区域的所有判定失效"));
            }
        }
        for (Map.Entry<String, Position> entry : locations.entrySet()) {
            if (missingRequired.contains("locations." + entry.getKey())) {
                continue;
            }
            if (!entry.getValue().world().equals(parsed.world())) {
                problems.add(new Spec.Problem("locations." + entry.getKey() + ".world",
                        "与顶层 world(" + parsed.world() + ") 不一致: " + entry.getValue().world()
                                + " —— 该传送点不会被使用"));
            }
        }
        // 奖励箱坐标不带键名，逐个按序号报告
        List<Position> chestLocations = parsed.loot().chestLocations();
        for (int index = 0; index < chestLocations.size(); index++) {
            Position chest = chestLocations.get(index);
            if (!chest.world().equals(parsed.world())) {
                problems.add(new Spec.Problem("chests.locations[" + index + "]",
                        "所在世界 " + chest.world() + " 与顶层 world(" + parsed.world()
                                + ") 不一致 —— 该奖励箱不会生成"));
            }
        }
        for (String key : REQUIRED_BUTTONS) {
            if (missingRequired.contains("buttons." + key)) {
                problems.add(new Spec.Problem("buttons." + key, "缺失（按钮不会响应）"));
            }
        }
        if (parsed.regions().containsKey("arena") && parsed.regions().containsKey("notify")
                && sameWorld(parsed.regions().get("arena"), parsed.regions().get("notify"))) {
            Region arena = parsed.regions().get("arena");
            Region notify = parsed.regions().get("notify");
            if (!missingRequired.contains("regions.arena") && !missingRequired.contains("regions.notify")
                    && !notify.covers(arena)) {
                problems.add(new Spec.Problem("regions.notify",
                        "未完全覆盖 regions.arena（提示接收范围应当包含整个场地）"));
            }
        }
        if (parsed.timing().prepareClicks() <= 0) {
            problems.add(new Spec.Problem("timing.prepare-clicks", "必须为正整数"));
        }
        if (parsed.timing().gameDurationSeconds() <= 0) {
            problems.add(new Spec.Problem("timing.game-duration", "必须为正整数"));
        }
        if (parsed.extraShopsEnabled() && parsed.extraShopIds().isEmpty()) {
            problems.add(new Spec.Problem("extra-shops.ids", "启用 ExtraShop 集成但未配置任何 shopId"));
        }
        if (parsed.loot().chestLocations().isEmpty()) {
            problems.add(new Spec.Problem("chests.locations", "未配置任何奖励箱坐标"));
        }
        if (parsed.loot().lootGroups().isEmpty()) {
            problems.add(new Spec.Problem("chests.loot-groups", "未配置任何奖励箱内容行"));
        }
        return new Spec.Validation(List.copyOf(problems));
    }

    private boolean worldMissing(String world) {
        return world == null || Bukkit.getWorld(world) == null;
    }

    /** 校验信息里用的紧凑数字格式（去掉多余小数位）。 */
    private String fmt(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private boolean sameWorld(Region a, Region b) {
        return a.world().equals(b.world());
    }

    // ------------------------------------------------------------------
    // 基础读取工具
    // ------------------------------------------------------------------

    /**
     * 解析 center —— **标准 {@code [x, y, z]} 坐标**。
     *
     * <p>返回数组固定为 {@code [x, y, z]}。刻意不做任何“某一位其实是 z”的假设：
     * 早先的实现按下标 {@code [0]}/{@code [1]} 取值并把它当 (x, z) 用，只要配置写法
     * 与假设不一致（例如写成 {@code [x, y, z]}），z 就会变成一个毫不相干的数字，
     * 表现为“玩家被传送到场地外、一落地就被判离开游戏”。</p>
     *
     * <p>y 允许省略：写成 {@code [x, z]} 时按 (x, y=0, z) 还原，与标准写法等价。</p>
     *
     * @return 长度 3 的数组 {@code [x, y, z]}；无法解析时返回 {@code {0,0,0}} 并记录警告
     */
    /**
     * 解析决斗圈配置。
     *
     * <p>支持两种写法：</p>
     * <ul>
     *   <li>新版 {@code duel.arenas} 列表——每个圈可独立指定区域、高度上限与是否允许落在水上；</li>
     *   <li>旧版 {@code duel.center / radius / min-spacing / max-attempts}——自动视为唯一的
     *       决斗圈（区域取 {@code duel-1}），保证老配置继续可用。</li>
     * </ul>
     */
    private Settings.Duel parseDuel(FileConfiguration yaml, String defaultWorld) {
        boolean random = yaml.getBoolean("duel.random", true);
        List<Settings.DuelArena> arenas = new ArrayList<>();
        List<Map<?, ?>> rawArenas = yaml.getMapList("duel.arenas");
        for (Map<?, ?> raw : rawArenas) {
            String region = raw.get("region") == null ? "duel-1" : String.valueOf(raw.get("region"));
            Object centerRaw = raw.get("center");
            double[] center = centerRaw instanceof List<?> list
                    ? parseCenterValue(list, "duel.arenas[].center")
                    : new double[]{0.0, 0.0, 0.0};
            arenas.add(new Settings.DuelArena(
                    region,
                    center[0], center[1], center[2],
                    toDoubleOrDefault(raw.get("min-spacing"), 5.0),
                    (int) toDoubleOrDefault(raw.get("max-attempts"), 300.0),
                    Boolean.TRUE.equals(raw.get("exact-y")),
                    toDoubleOrDefault(raw.get("max-y"), 0.0),
                    Boolean.TRUE.equals(raw.get("allow-water"))));
        }
        if (arenas.isEmpty()) {
            // 旧版写法：单个决斗圈
            double[] center = parseCenter(yaml, "duel.center", defaultWorld);
            arenas.add(new Settings.DuelArena(
                    "duel-1",
                    center[0], center[1], center[2],
                    yaml.getDouble("duel.min-spacing", 5.0),
                    (int) yaml.getLong("duel.max-attempts", 300),
                    yaml.getBoolean("duel.exact-y", false),
                    yaml.getDouble("duel.max-y", 0.0),
                    yaml.getBoolean("duel.allow-water", false)));
        }
        return new Settings.Duel(List.copyOf(arenas), random);
    }

    /** 解析 {@code [x, y, z]} / {@code [x, z]}；失败返回 0,0,0 并告警。 */
    private double[] parseCenterValue(List<?> list, String path) {
        if (list.size() == 3) {
            Double x = toDouble(list.get(0));
            Double y = toDouble(list.get(1));
            Double z = toDouble(list.get(2));
            if (x != null && y != null && z != null) {
                return new double[]{x, y, z};
            }
        } else if (list.size() == 2) {
            Double x = toDouble(list.get(0));
            Double z = toDouble(list.get(1));
            if (x != null && z != null) {
                return new double[]{x, 0.0, z};
            }
        }
        plugin.getLogger().warning("[config] " + path + " 格式应为 [x, y, z]（或简写 [x, z]），"
                + "当前为 " + list);
        return new double[]{0.0, 0.0, 0.0};
    }

    private double toDoubleOrDefault(Object raw, double fallback) {
        Double value = toDouble(raw);
        return value == null ? fallback : value;
    }

    private double[] parseCenter(FileConfiguration yaml, String path, String defaultWorld) {
        Object raw = yaml.get(path);
        if (raw instanceof List<?> list) {
            if (list.size() == 3) {
                Double x = toDouble(list.get(0));
                Double y = toDouble(list.get(1));
                Double z = toDouble(list.get(2));
                if (x != null && y != null && z != null) {
                    return new double[]{x, y, z};
                }
            } else if (list.size() == 2) {
                // [x, z] 简写——与标准写法含义一致
                Double x = toDouble(list.get(0));
                Double z = toDouble(list.get(1));
                if (x != null && z != null) {
                    return new double[]{x, 0.0, z};
                }
            }
            // 明确报错而不是静默按位置猜：坐标搞错的代价是整局玩法失效
            plugin.getLogger().warning("[config] " + path + " 格式应为 [x, y, z]（或简写 [x, z]），"
                    + "当前为 " + list + " —— 该中心点将无法正确生效");
            return new double[]{0.0, 0.0, 0.0};
        }
        double[] triple = parseTriple(raw);
        if (triple != null) {
            return triple;
        }
        plugin.getLogger().warning("[config] " + path + " 缺失或格式错误，已退回 0,0,0");
        return new double[]{0.0, 0.0, 0.0};
    }

    /** 解析 {@code [x,y,z]} 或 {@code {x:..,y:..,z:..}}；无法解析返回 null。 */
    private double[] parseTriple(Object raw) {
        if (raw instanceof List<?> list) {
            if (list.size() < 3) {
                return null;
            }
            Double x = toDouble(list.get(0));
            Double y = toDouble(list.get(1));
            Double z = toDouble(list.get(2));
            if (x == null || y == null || z == null) {
                return null;
            }
            return new double[]{x, y, z};
        }
        if (raw instanceof ConfigurationSection section) {
            Double x = toDouble(section.get("x"));
            Double y = toDouble(section.get("y"));
            Double z = toDouble(section.get("z"));
            if (x == null || y == null || z == null) {
                return null;
            }
            return new double[]{x, y, z};
        }
        if (raw instanceof Map<?, ?> map) {
            Double x = toDouble(map.get("x"));
            Double y = toDouble(map.get("y"));
            Double z = toDouble(map.get("z"));
            if (x == null || y == null || z == null) {
                return null;
            }
            return new double[]{x, y, z};
        }
        return null;
    }

    private Position parsePosition(Object raw, String defaultWorld) {
        double[] triple = parseTriple(raw);
        if (triple == null) {
            return null;
        }
        return Position.of(defaultWorld, triple[0], triple[1], triple[2], null, 0.0, 0.0);
    }

    private Double toDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.parseDouble(text.trim().toLowerCase(Locale.ROOT));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    /** 供 /fmwar doctor 打印用。 */
    public void saveDefaultIfMissing() {
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
    }

    /** 把当前配置整份写回磁盘（仅用于生成带注释的默认文件场景，正常不调用）。 */
    public void flush(FileConfiguration yaml) {
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("配置保存失败: " + exception.getMessage());
        }
    }
}
