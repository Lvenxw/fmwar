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
 *   <li>取不到 API（ExtraShop 未装/未启用）→ 集成为不可用，只在集成被启用时提示一次；</li>
 *   <li>某个 shopId 未定义 → <b>静默跳过</b>，只在开局时汇总一条提示，详情交给 {@code /fmwar doctor}；</li>
 *   <li>某个 shopId 未处于“已生成”状态 → <b>不必移除</b>，跳过（对不存在的东西调用 despawn
 *       会同时触发 ExtraShop 自己的 WARN 和本插件的一条 WARN，属纯噪音）；</li>
 *   <li>spawn / despawn 真正失败 → 记日志，不影响游戏流程。</li>
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

    /**
     * 重新获取 API 实例（ExtraShop 重新启用后需要重新取）。
     *
     * <p>只有集成确实被启用时才提示“不可用”。未启用属于正常配置，反复刷 WARN 只会掩盖真问题。</p>
     */
    public void refresh() {
        this.api = ExtraShopApiProvider.get();
        if (api == null && config.settings().extraShopsEnabled()) {
            plugin.getLogger().warning("ExtraShop API 不可用：商店 NPC 功能已禁用（其余玩法不受影响）。"
                    + "若不需要商店，可在 config.yml 设置 extra-shops.enabled=false 关闭该集成");
        }
    }

    public boolean available() {
        return api != null;
    }

    /**
     * 安全查询商店是否已定义。
     *
     * <p>绝对不让商店查询把玩法拖下水：ExtraShop 若处于异常状态（装了但未启用等），
     * 其方法可能抛异常；在 onEnable 里抛出会导致整个插件启用失败，因此一律吞掉并视为“未定义”。</p>
     */
    private boolean isDefined(String id) {
        if (api == null) {
            return false;
        }
        try {
            return api.isShopDefined(id);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("查询商店定义失败 [" + id + "]: " + exception.getMessage());
            return false;
        }
    }

    /** 安全查询商店是否已生成；异常一律视为“未生成”。 */
    private boolean isSpawned(String id) {
        if (api == null) {
            return false;
        }
        try {
            return api.isSpawned(id);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("查询商店状态失败 [" + id + "]: " + exception.getMessage());
            return false;
        }
    }

    /** 校验配置里的每个 shopId 是否已定义；返回未定义的 id 列表。 */
    public List<String> findUndefinedShops() {
        List<String> missing = new ArrayList<>();
        if (!config.settings().extraShopsEnabled() || api == null) {
            return missing;
        }
        for (String id : config.settings().extraShopIds()) {
            if (!isDefined(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    /**
     * 生成全部配置的商店；返回成功数量。
     *
     * <p>未定义的 id 会被静默跳过并汇总成一条 WARN——按需求这是**部署前置条件**，
     * 开局时逐条刷六行日志没有帮助，逐项状态交给 {@code /fmwar doctor}。</p>
     */
    public int spawnAll() {
        if (!config.settings().extraShopsEnabled() || api == null) {
            return 0;
        }
        int success = 0;
        List<String> undefined = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String id : config.settings().extraShopIds()) {
            if (!isDefined(id)) {
                undefined.add(id);
                continue;
            }
            try {
                ShopOperationResult result = api.spawn(id);
                if (result.success()) {
                    success++;
                } else {
                    failed.add(id + "(" + result.status() + ": " + result.message() + ")");
                }
            } catch (RuntimeException exception) {
                failed.add(id + "(" + exception.getMessage() + ")");
            }
        }
        if (!undefined.isEmpty()) {
            plugin.getLogger().warning("以下商店未在本服定义，本次未生成：" + String.join(", ", undefined)
                    + " —— 请在服务器上用 /eshop 预先配置，或用 /fmwar doctor 查看逐项状态");
        }
        if (!failed.isEmpty()) {
            plugin.getLogger().warning("以下商店生成失败：" + String.join(", ", failed));
        }
        return success;
    }

    /**
     * 移除本插件生成过的商店；返回成功数量。
     *
     * <p>先查 {@code isSpawned} 再决定是否调用 {@code despawn}：对未生成的商店调用 despawn
     * 只会得到 SHOP_NOT_FOUND，产生成对的噪音 WARN。插件停用与对局结束都会走这条兜底清理，
     * 清理不存在的东西不该报警。</p>
     */
    public int despawnAll() {
        if (api == null) {
            return 0;
        }
        int success = 0;
        List<String> failed = new ArrayList<>();
        for (String id : config.settings().extraShopIds()) {
            try {
                // 未定义或未生成 → 无需移除，静默跳过
                if (!isDefined(id) || !isSpawned(id)) {
                    continue;
                }
                ShopOperationResult result = api.despawn(id);
                if (result.success()) {
                    success++;
                } else {
                    failed.add(id + "(" + result.status() + ": " + result.message() + ")");
                }
            } catch (RuntimeException exception) {
                failed.add(id + "(" + exception.getMessage() + ")");
            }
        }
        if (!failed.isEmpty()) {
            plugin.getLogger().warning("以下商店移除失败：" + String.join(", ", failed));
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
            lines.add(id + ": defined=" + isDefined(id) + ", spawned=" + isSpawned(id));
        }
        return lines;
    }
}
