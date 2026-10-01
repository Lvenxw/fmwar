package cn.mgtown.fmwar.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 把配置里的物品字符串解析成 ItemStack。
 *
 * <p>格式：{@code <材质>[:<片段>[;<片段>...]]}</p>
 * <ul>
 *   <li>片段 {@code [命名空间:]键=等级}：附魔，命名空间缺省为 {@code minecraft}</li>
 *   <li>片段 {@code potion=瞬间伤害药水键}：给药水箭设置药水效果</li>
 *   <li>片段 {@code amplifier=数字}：药水效果等级（0 = I 级）</li>
 * </ul>
 *
 * <p>例：{@code NETHERITE_SWORD:overlay_network=6}、
 * {@code MACE:doom_hammer=1,wind_burst=1}、
 * {@code TIPPED_ARROW:potion=instant_damage;amplifier=1}。</p>
 *
 * <p>解析失败的条目不会让插件崩溃：记录到 warn 回调后跳过该条。这是有意为之——
 * 自定义附魔键是否存在于本服由服务端数据包/插件决定，插件无法在编译期保证。</p>
 */
public final class LootParser {

    private final Consumer<String> warn;

    public LootParser(Consumer<String> warn) {
        this.warn = warn;
    }

    /** 解析一行物品（可含 , 或 、 分隔的多个附魔片段）。 */
    public List<ItemStack> parseGroup(List<String> spec) {
        List<ItemStack> items = new ArrayList<>();
        for (String raw : spec) {
            ItemStack item = parse(raw);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * 从**奖励箱物品**定义里静态提取附魔键（不做注册表查询、不产生副作用）。
     *
     * <p>供 /fmwar doctor 在运行期核对"配置里写的键在本服注册表里到底存不存在"——
     * 这是本插件唯一无法在编译期或仓库内验证的外部依赖。</p>
     *
     * <p><b>只接受物品格式</b> {@code <材质>:<片段>=<等级>}，例如
     * {@code MACE:doom_hammer=1,wind_burst=1}。钓竿用的 {@code <键>=<等级>} 格式
     * （如 {@code minecraft:lure=255}）不要喂给它——那种格式没有材质前缀，
     * 冒号会被当成材质分隔符。钓竿的键请直接用 {@code start.fishing-rod.enchantments} 的键集。</p>
     */
    public static List<String> rawLootEnchantmentKeys(String raw) {
        List<String> keys = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return keys;
        }
        String text = raw.trim();
        int colon = text.indexOf(':');
        if (colon < 0) {
            return keys;
        }
        for (String fragment : text.substring(colon + 1).split("[;,]|、")) {
            String piece = fragment.trim();
            int equals = piece.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String keyText = piece.substring(0, equals).trim();
            if (keyText.isEmpty() || keyText.equalsIgnoreCase("potion") || keyText.equalsIgnoreCase("amplifier")) {
                continue;
            }
            keys.add(keyText);
        }
        return keys;
    }

    /** 解析单个物品定义；失败返回 null 并回调 warn。 */
    public ItemStack parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int colon = text.indexOf(':');
        String materialPart = colon < 0 ? text : text.substring(0, colon);
        String rest = colon < 0 ? "" : text.substring(colon + 1);

        Material material = Material.matchMaterial(materialPart.trim().toUpperCase(java.util.Locale.ROOT));
        if (material == null) {
            warn.accept("物品材质无法识别: " + raw);
            return null;
        }

        ItemStack item = new ItemStack(material);
        if (rest.isBlank()) {
            return item;
        }

        for (String fragment : rest.split("[;,]|、")) {
            String piece = fragment.trim();
            if (piece.isEmpty()) {
                continue;
            }
            int equals = piece.indexOf('=');
            if (equals <= 0) {
                warn.accept("附魔片段缺少 = 等级: " + piece + "（条目: " + raw + "）");
                continue;
            }
            String keyText = piece.substring(0, equals).trim();
            String valueText = piece.substring(equals + 1).trim();

            if (keyText.equalsIgnoreCase("potion")) {
                applyPotion(item, valueText, 0);
                continue;
            }
            if (keyText.equalsIgnoreCase("amplifier")) {
                int amplifier = parseInt(valueText, -1);
                if (amplifier >= 0) {
                    applyPotion(item, null, amplifier);
                }
                continue;
            }

            int level = parseInt(valueText, 0);
            if (level <= 0) {
                warn.accept("附魔等级必须是正整数: " + piece + "（条目: " + raw + "）");
                continue;
            }
            NamespacedKey key = toKey(keyText);
            Enchantment enchantment = key == null ? null : Registry.ENCHANTMENT.get(key);
            if (enchantment == null) {
                warn.accept("附魔不存在（本服未注册该键，需数据包或插件提供）: " + keyText + "（条目: " + raw + "）");
                continue;
            }
            // 绕过等级上限与适用性限制：需求文档明确要求 255 级钓竿这类越界附魔
            item.addUnsafeEnchantment(enchantment, level);
        }
        return item;
    }

    /** 把 "{@code minecraft:lure=255}" 这类键值解析成附魔表（用于开局钓竿）。 */
    public Map<String, Integer> parseEnchantments(List<String> specs, String path) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String raw : specs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            int equals = raw.lastIndexOf('=');
            if (equals <= 0) {
                warn.accept(path + " 中的附魔缺少 = 等级: " + raw);
                continue;
            }
            String keyText = raw.substring(0, equals).trim();
            int level = parseInt(raw.substring(equals + 1).trim(), 0);
            if (level <= 0) {
                warn.accept(path + " 中的附魔等级非法: " + raw);
                continue;
            }
            result.put(keyText, level);
        }
        return result;
    }

    private void applyPotion(ItemStack item, String potionKey, int amplifier) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof PotionMeta potionMeta)) {
            if (potionKey != null) {
                warn.accept("材质 " + item.getType() + " 不支持药水效果，已忽略: " + potionKey);
            }
            return;
        }
        if (potionKey == null) {
            // 只给了 amplifier：保留已有自定义效果，仅调整等级
            if (potionMeta.getCustomEffects().isEmpty()) {
                warn.accept("材质 " + item.getType() + " 尚未指定药水效果，amplifier 已忽略");
                return;
            }
            PotionEffect existing = potionMeta.getCustomEffects().get(0);
            potionMeta.clearCustomEffects();
            potionMeta.addCustomEffect(
                    new PotionEffect(existing.getType(), existing.getDuration(), Math.max(0, amplifier)), true);
            item.setItemMeta(potionMeta);
            return;
        }
        NamespacedKey key = toKey(potionKey);
        PotionEffectType type = key == null ? null : Registry.POTION_EFFECT_TYPE.get(key);
        if (type == null) {
            warn.accept("药水效果不存在: " + potionKey);
            return;
        }
        potionMeta.clearCustomEffects();
        potionMeta.addCustomEffect(new PotionEffect(type, 1, Math.max(0, amplifier)), true);
        item.setItemMeta(potionMeta);
    }

    private NamespacedKey toKey(String text) {
        String value = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (!value.contains(":")) {
            value = "minecraft:" + value;
        }
        try {
            return NamespacedKey.fromString(value);
        } catch (RuntimeException exception) {
            warn.accept("附魔键格式非法: " + text);
            return null;
        }
    }

    private int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
