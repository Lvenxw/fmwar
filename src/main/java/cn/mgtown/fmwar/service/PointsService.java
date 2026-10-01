package cn.mgtown.fmwar.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 附魔战争积分：每击杀一人 +1，获得最终胜利 +3。
 *
 * <p>积分是**跨对局累计**并落盘到 {@code plugins/fmwar/points.yml} 的，因此它同时充当
 * 服务器上的长期排行榜。写入时机刻意选在“对局结束”与“插件停用”，而不是每次加分都写盘——
 * 一次对局里积分变动很频繁，逐次落盘没有必要。</p>
 *
 * <p>同时保存玩家名：排名展示时即使玩家离线也能显示名字，而不是一串 UUID。</p>
 */
public final class PointsService {

    /** 击杀得分。 */
    public static final int POINTS_PER_KILL = 1;
    /** 获胜得分。 */
    public static final int POINTS_PER_WIN = 3;

    private final Plugin plugin;
    private final File file;

    /** UUID -> 积分。 */
    private final Map<UUID, Integer> points = new LinkedHashMap<>();
    /** UUID -> 最近一次见到的玩家名（便于离线展示）。 */
    private final Map<UUID, String> names = new LinkedHashMap<>();

    private boolean dirty;

    public PointsService(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "points.yml");
        load();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public int pointsOf(UUID uuid) {
        return points.getOrDefault(uuid, 0);
    }

    /** 按积分降序、名字升序排名。 */
    public List<Entry> ranking() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : points.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            entries.add(new Entry(names.getOrDefault(entry.getKey(), entry.getKey().toString().substring(0, 8)),
                    entry.getValue(), entry.getKey()));
        }
        entries.sort(Comparator.comparingInt(Entry::points).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /** 排行榜条目。 */
    public record Entry(String name, int points, UUID uuid) {
    }

    // ------------------------------------------------------------------
    // 加分
    // ------------------------------------------------------------------

    /** 击杀一人：+1 分。 */
    public void addKill(UUID killer) {
        add(killer, POINTS_PER_KILL);
    }

    /** 获得最终胜利：+3 分。 */
    public void addWin(UUID winner) {
        add(winner, POINTS_PER_WIN);
    }

    /** 直接加分（内部使用）。 */
    private void add(UUID uuid, int delta) {
        if (uuid == null || delta == 0) {
            return;
        }
        points.merge(uuid, delta, Integer::sum);
        dirty = true;
    }

    /** 记录玩家名（登录/参与时刷新，保证排行榜显示的是最新名字）。 */
    public void remember(UUID uuid, String name) {
        if (uuid == null || name == null) {
            return;
        }
        if (!name.equals(names.put(uuid, name))) {
            dirty = true;
        }
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                points.put(uuid, section.getInt(key + ".points"));
                String name = section.getString(key + ".name");
                if (name != null) {
                    names.put(uuid, name);
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("points.yml 中存在无法解析的玩家键，已跳过: " + key);
            }
        }
        plugin.getLogger().info("已载入 " + points.size() + " 名玩家的附魔战争积分");
    }

    /**
     * 把积分写回磁盘。
     *
     * <p>只在有变动时写，避免无意义的磁盘 IO；写失败只记日志，不影响玩法。</p>
     */
    public void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("players");
        for (Map.Entry<UUID, Integer> entry : points.entrySet()) {
            String path = entry.getKey().toString();
            section.set(path + ".points", entry.getValue());
            String name = names.get(entry.getKey());
            if (name != null) {
                section.set(path + ".name", name);
            }
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().severe("附魔战争积分写入失败: " + exception.getMessage());
        }
    }
}
