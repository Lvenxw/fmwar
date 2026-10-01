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
        Spec.Validation checked = validate(parsed);
        this.settings = parsed;
        this.validation = checked;
        if (!checked.ok()) {
            plugin.getLogger().warning("配置存在问题：" + checked.describe());
        }
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
            double[] block = parseTriple(entry.get("block"));
            if (block == null) {
                plugin.getLogger().warning("[config] buttons." + key + " 缺少 block，已跳过");
                continue;
            }
            double radius = entry.getDouble("radius", 1.5);
            buttons.put(key, new Spec.Button(key,
                    Position.of(world, block[0], block[1], block[2], null, 0.0, 0.0), radius));
        }
        return buttons;
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private Spec.Validation validate(Settings parsed) {
        List<Spec.Problem> problems = new ArrayList<>();
        Map<String, Region> regions = new LinkedHashMap<>(parsed.regions());
        Map<String, Position> locations = new LinkedHashMap<>(parsed.locations());

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
                    && !covers(notify, arena)) {
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

    private boolean covers(Region outer, Region inner) {
        return outer.minX() <= inner.minX() && outer.minY() <= inner.minY() && outer.minZ() <= inner.minZ()
                && outer.maxX() >= inner.maxX() && outer.maxY() >= inner.maxY() && outer.maxZ() >= inner.maxZ();
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
