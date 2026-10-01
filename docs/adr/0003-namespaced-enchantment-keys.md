# 0003. 附魔在配置里写完整命名空间键，解析失败只告警不崩溃

- 状态：已接受
- 日期：2026-02-14（会话日期）

## 背景

奖励箱内容里出现的 `overlay_network`、`blood_roar`、`doom_hammer`、`union`、`daedalus`、
`faith`、`fusion_strike`、`eternal`、`evil_frenzy`、`magnetic_link`、`beach_boy`、`fortitude`
都不是原版附魔；同一份清单里的 `wind_burst` 与 `lunge` 却是 Paper 1.21.11 自带注册的
原版附魔（`io.papermc.paper.registry.keys.EnchantmentKeys` 中有 `WIND_BURST` 与 `LUNGE`）。

也就是说：清单里混着**原版注册的**与**由服务端数据包/附魔插件提供的**两类附魔，
而后者是否存在，本插件的编译期与仓库内都无法验证。

## 决策

配置里每个附魔写完整键字符串，缺省命名空间为 `minecraft`：

```yaml
loot-groups:
  - - 'TRIDENT:daedalus=1,loyalty=3'
```

解析路径统一为 `NamespacedKey.fromString` → `Registry.ENCHANTMENT.get(key)` → `addUnsafeEnchantment`。
解析失败（键不存在、格式非法、材质不匹配）时：**记一条 WARNING、跳过该条**，
箱子退化为放入该行剩余可解析的物品，绝不让加载或开局失败。
使用 `addUnsafeEnchantment` 是为了支持需求里 255 级这类越界等级。

## 后果

- 换服务端、换数据包时不需要改代码，只改配置里的键。
- 键名写错不会立刻报错，只在控制台留下 WARNING；`/fmwar doctor` 无法覆盖这一点，
  因为附魔键只有运行期注册表才权威。开服后需要玩家实际开箱验证一次。
- 配置里的附魔键与真实注册键是否一致，属于**部署前置检查项**，不是本仓库能保证的事。
  同类前置项还有：六个 eshop 商店（`fmcm1`..`fmcm6`）必须已在服务器上定义并摆放、
  领地插件 Residence 的 FM 领地需关闭 `tp` 权限。
