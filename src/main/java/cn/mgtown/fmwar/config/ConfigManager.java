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

    /** 需求文档里的六个按钮/区域键，缺失即视为配置错误。 */
    private static final List<String> REQUIRED_REGIONS =
            List.of("arena", "notify", "prep-room", "hall", "duel-1");
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
        yaml.setDefaults(YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(
                        java.util.Objects.requireNonNull(
                                plugin.getResource("config.yml"), "jar 内缺少 config.yml"),
                        java.nio.charset.StandardCharsets.UTF_8)));

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

        double[] disperseCenter = parseCenter(yaml, "disperse.center", defaultWorld);
        Settings.Disperse disperse = new Settings.Disperse(
                yaml.getBoolean("disperse.enabled", true),
                disperseCenter[0], disperseCenter[2],
                yaml.getDouble("disperse.radius", 100.0),
                yaml.getDouble("disperse.min-spacing", 25.0),
                (int) yaml.getLong("disperse.max-attempts", 600));

        double[] duelCenter = parseCenter(yaml, "duel.center", defaultWorld);
        Settings.Duel duel = new Settings.Duel(
                duelCenter[0], duelCenter[2],
                yaml.getDouble("duel.radius", 40.0),
                yaml.getDouble("duel.min-spacing", 5.0),
                (int) yaml.getLong("duel.max-attempts", 300));

        Settings.Start start = new Settings.Start(
                yaml.getBoolean("start.clear-inventory", true),
                yaml.getBoolean("start.clear-effects", true),
                yaml.getBoolean("start.restore-on-leave", true),
                yaml.getBoolean("start.block-sneak", false),
                yaml.getBoolean("start.resistance.enabled", true),
                yaml.getLong("start.resistance.duration-ticks", 100),
                (int) yaml.getLong("start.resistance.amplifier", 4),
                yaml.getBoolean("start.heal", true),
                yaml.getBoolean("start.fishing-rod.enabled", true),
                yaml.getString("start.fishing-rod.material", "FISHING_ROD"),
                lootParser.parseEnchantments(
                        yaml.getStringList("start.fishing-rod.enchantments"),
                        "start.fishing-rod.enchantments"));

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
                yaml.getString("scoreboard.time-line", "&e剩余时间 &f{time}"),
                yaml.getString("scoreboard.alive-line", "&e存活人数 &f{alive}"));

        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection messageSection = yaml.getConfigurationSection("messages");
        if (messageSection != null) {
            for (String key : messageSection.getKeys(false)) {
                Object value = messageSection.get(key);
                if (value instanceof String text) {
                    messages.put(key, text);
                }
            }
        }
        boolean actionbar = Boolean.parseBoolean(messages.getOrDefault("actionbar", "true"));

        List<String> shopIds = new ArrayList<>();
        for (String id : yaml.getStringList("extra-shops.ids")) {
            if (id != null && !id.isBlank()) {
                shopIds.add(id.trim());
            }
        }

        return new Settings(
                yaml.getString("prefix", "&6[附魔战争]&r "),
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

    private boolean sameWorld(Region a, Region b) {
        return a.world().equals(b.world());
    }

    // ------------------------------------------------------------------
    // 基础读取工具
    // ------------------------------------------------------------------

    private double[] parseCenter(FileConfiguration yaml, String path, String defaultWorld) {
        double[] center = parseTriple(yaml.get(path));
        if (center == null) {
            plugin.getLogger().warning("[config] " + path + " 缺失或格式错误，已退回 0,0");
            return new double[]{0.0, 0.0, 0.0};
        }
        return center;
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
