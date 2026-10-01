package cn.mgtown.fmwar.service;

import cn.mgtown.extrashop.api.ExtraShopApi;
import cn.mgtown.extrashop.api.ExtraShopApiProvider;
import cn.mgtown.extrashop.api.ShopOperationResult;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * ExtraShop 集成：对局开始时生成商店 NPC，结束时移除。
 *
 * <p>ExtraShop 的 API 与插件本体分开提供，因此这里做**分级降级**，绝不因为商店缺失而中止对局：</p>
 * <ul>
 *   <li>取不到 API（ExtraShop 未装/未启用）→ 记一条 WARN，功能标记为不可用；</li>
 *   <li>某个 shopId 未定义 → 记一条 WARN，继续处理其余商店；</li>
 *   <li>spawn / despawn 失败 → 记日志，不影响游戏流程。</li>
 * </ul>
 *
 * <p>注意：API 不做权限校验，且所有方法必须在服务端主线程调用。</p>
 */
public final class ShopService {

    private final Plugin plugin;
    private final ConfigService config;
    private ExtraShopApi api;

    public ShopService(Plugin plugin, ConfigService config) {
        this.plugin = plugin;
        this.config = config;
        refresh();
    }

    /** 重新获取 API 实例（ExtraShop 重新启用后需要重新取）。 */
    public void refresh() {
        this.api = ExtraShopApiProvider.get();
        if (api == null) {
            plugin.getLogger().warning("ExtraShop API 不可用：商店 NPC 功能已禁用（其余玩法不受影响）");
        }
    }

    public boolean available() {
        return api != null;
    }

    /** 校验配置里的每个 shopId 是否已定义；返回未定义的 id 列表。 */
    public List<String> findUndefinedShops() {
        List<String> missing = new ArrayList<>();
        if (!config.settings().extraShopsEnabled() || api == null) {
            return missing;
        }
        for (String id : config.settings().extraShopIds()) {
            if (!api.isShopDefined(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    /** 生成全部配置的商店；返回成功数量。 */
    public int spawnAll() {
        if (!config.settings().extraShopsEnabled()) {
            return 0;
        }
        if (api == null) {
            plugin.getLogger().warning("跳过商店生成：ExtraShop API 不可用");
            return 0;
        }
        int success = 0;
        for (String id : config.settings().extraShopIds()) {
            ShopOperationResult result = api.spawn(id);
            if (result.success()) {
                success++;
            } else {
                plugin.getLogger().warning("商店生成失败 [" + id + "] " + result.status() + ": " + result.message());
            }
        }
        return success;
    }

    /** 移除全部配置的商店；返回成功数量。 */
    public int despawnAll() {
        if (api == null) {
            return 0;
        }
        int success = 0;
        for (String id : config.settings().extraShopIds()) {
            try {
                ShopOperationResult result = api.despawn(id);
                if (result.success()) {
                    success++;
                } else {
                    plugin.getLogger().warning("商店移除失败 [" + id + "] " + result.status() + ": " + result.message());
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("商店移除异常 [" + id + "]: " + exception.getMessage());
            }
        }
        return success;
    }

    /** /fmwar doctor 用：每个商店的 defined / spawned 状态。 */
    public List<String> diagnose() {
        List<String> lines = new ArrayList<>();
        if (!config.settings().extraShopsEnabled()) {
            lines.add("ExtraShop 集成已在配置中关闭 (extra-shops.enabled=false)");
            return lines;
        }
        if (api == null) {
            lines.add("ExtraShop API 不可用（未安装或未启用 ExtraShop）");
            return lines;
        }
        for (String id : config.settings().extraShopIds()) {
            lines.add(id + ": defined=" + api.isShopDefined(id) + ", spawned=" + api.isSpawned(id));
        }
        return lines;
    }
}
