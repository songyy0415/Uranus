# MC 26.2 Item / Tool / Armor API 迁移表

> 结论全部来自本地 `D:\aiminecraftdev\minecraft26.2` sources 直接核对（非类名猜测）。P2.2 阶段产出。
> 依据 §12 分三类：**A**=仅改名，**B**=职责不变但构造/签名变，**C**=职责重新分配。

## 一、核心结论

26.2 移除物品/工具/装备体系多个类，职责重新分配：

| 旧 API (1.21.1) | 26.2 API | 类型 | 说明 |
|---|---|---|---|
| `net.minecraft.world.item.Tier` (interface) | `net.minecraft.world.item.ToolMaterial` (record) | C | 字段重命名；`getRepairIngredient()`(Ingredient) → `repairItems()`(TagKey\<Item\>) |
| `net.minecraft.world.item.SwordItem` | **移除** | C | `new Item(new Item.Properties().sword(material, 3.0F, -2.4F))` |
| `DiggerItem`/`PickaxeItem`/`AxeItem`/`ShovelItem`/`HoeItem` | **移除** | C | `Item.Properties.pickaxe/axe/shovel/hoe/tool(material, ...)` |
| `net.minecraft.world.item.ArmorItem` | **移除** | C | `new Item(new Item.Properties().humanoidArmor(material, type))` |
| `ArmorItem.Type` (enum) | `net.minecraft.world.item.equipment.ArmorType` (enum) | A+B | HELMET/CHESTPLATE/LEGGINGS/BOOTS + **BODY**；含 `getDurability(int)`/`getSlot()` |
| `net.minecraft.world.item.ArmorMaterial` | `net.minecraft.world.item.equipment.ArmorMaterial` (record) | A+B | `Holder<ArmorMaterial>` → 直接 `ArmorMaterial`（不再注册） |
| `ArmorMaterial.Layer` / `Layer.texture(boolean)` | **移除** → `EquipmentAsset` | C | 贴图层机制被 `equipment_asset` model 系统替代 |
| `Registries.ARMOR_MATERIAL` | **移除** | C | 材料不再是 registry 对象 → `ArmorMaterials` 常量 / 直接构造 |
| `Item.Properties.repairable(Ingredient)` | `repairable(TagKey\<Item\>)` / `repairable(Item)` | B | 修复材料改为 tag |
| `Item.Properties.attributeModifiers(...)` | `Item.Properties.attributes(ItemAttributeModifiers)` | A | 改名 |
| `Item.Properties.equipmentSlot(EquipmentSlot)` | `Item.Properties.equippable(EquipmentSlot)` / `equippableUnswappable` | A | 改名 |
| `Registry.get(ResourceKey)` | `Registry.getValue(ResourceKey)` | A | 返回 `@Nullable T` |
| `RegistryAccess.registryOrThrow(ResourceKey)` | `RegistryAccess.lookupOrThrow(ResourceKey)` | A | 改名 |

## 二、ToolMaterial（`net.minecraft.world.item.ToolMaterial`，record）

```java
public record ToolMaterial(
    TagKey<Block> incorrectBlocksForDrops, int durability, float speed,
    float attackDamageBonus, int enchantmentValue, TagKey<Item> repairItems)
```

| 旧 Tier | 新 ToolMaterial | 行为差异 |
|---|---|---|
| `getUses()` | `durability()` | 无 |
| `getSpeed()` | `speed()` | 无 |
| `getAttackDamageBonus()` | `attackDamageBonus()` | 无 |
| `getEnchantmentValue()` | `enchantmentValue()` | 无 |
| `getIncorrectBlocksForDrops()` | `incorrectBlocksForDrops()` | 无 |
| `getRepairIngredient()` (Ingredient) | `repairItems()` (TagKey\<Item\>) | **C：修复材料由 Ingredient 变为 item tag** |

辅助方法（vanilla 内部工具属性生成）：`applyCommonProperties`、`applyToolProperties(properties, minesEfficiently, attackDamageBaseline, attackSpeedBaseline, disableBlockingSeconds)`、`applySwordProperties(properties, attackDamageBaseline, attackSpeedBaseline)`。
常量：`ToolMaterial.WOOD/STONE/COPPER/IRON/GOLD/DIAMOND/NETHERITE`。

**项目使用位置**：`object/item/ToolMaterialUtil.java`、`object/item/SwordItemBase.java`、`object/item/FoilSwordItemBase.java`。

**迁移方案**（保留 ToolMaterialUtil 为兼容层，`ItemLike...` 修复参数 → `TagKey<Item>`）：
```java
public static ToolMaterial of(TagKey<Block> inverseTag, int uses, float speed, float attackDamageBonus, int enchantmentLevel, TagKey<Item> repairItems) {
    return new ToolMaterial(inverseTag, uses, speed, attackDamageBonus, enchantmentLevel, repairItems);
}
```

## 三、SwordItem 移除 → 属性/组件组合

旧 `SwordItem(Tier tier, Item.Properties properties)`（1.21.1）= 攻击伤害 `3 + tier.getAttackDamageBonus()`、攻击速度 `-2.4F`、剑专属横扫/破网/武器组件。

26.2 等价（vanilla Items.java:995 原样）：
```java
new Item(new Item.Properties().sword(ToolMaterial.WOOD, 3.0F, -2.4F))
```
`Item.Properties.sword(material, attackDamageBaseline, attackSpeedBaseline)` → `ToolMaterial.applySwordProperties`：durability/repairable/enchantable + `DataComponents.TOOL`（破网、横扫、剑高效方块）+ `DataComponents.WEAPON` + attributes（`attackDamageBaseline + attackDamageBonus`，`attackSpeedBaseline`）。

**项目迁移**：`SwordItemBase extends Item`，`super(settings.sword(toolMaterial, 3.0F, -2.4F))`；行为等价，无功能丢失。

## 四、ArmorMaterial（`net.minecraft.world.item.equipment.ArmorMaterial`，record）

```java
public record ArmorMaterial(
    int durability,
    Map<ArmorType, Integer> defense,
    int enchantmentValue,
    Holder<SoundEvent> equipSound,
    float toughness,
    float knockbackResistance,
    TagKey<Item> repairIngredient,
    ResourceKey<EquipmentAsset> assetId)
```

| 旧 ArmorMaterial | 新 | 行为差异 |
|---|---|---|
| `getDurabilityForType(type)` | `durability()`(unit) × `ArmorType.getDurability()` | durability 变 unit，按 type 计算 |
| `getDefenseForType(type)` | `defense()` Map\<ArmorType,Integer\> | int[] → Map |
| `getEnchantmentValue()` | `enchantmentValue()` | 无 |
| `getEquipSound()` | `equipSound()` (Holder\<SoundEvent\>) | 无 |
| `getToughness()` | `toughness()` | 无 |
| `getKnockbackResistance()` | `knockbackResistance()` | 无 |
| `getRepairIngredient()` | `repairIngredient()` (TagKey\<Item\>) | **C：Ingredient → tag** |
| `getName()` / `ArmorMaterial.Layer` | `assetId()` (ResourceKey\<EquipmentAsset\>) | **C：贴图走 `equipment_asset` 模型系统，Layer 移除** |

`ArmorMaterial.createAttributes(ArmorType)` 生成护甲属性（ARMOR/ARMOR_TOUGHNESS/KNOCKBACK_RESISTANCE，按槽位 EquipmentSlotGroup）。
常量接口：`ArmorMaterials.LEATHER/COPPER/CHAINMAIL/IRON/GOLD/DIAMOND/TURTLE_SCUTE/NETHERITE/ARMADILLO_SCUTE`。

**项目使用位置**：
- `object/RegistryHelper.java` `getArmorMaterial` — `Registries.ARMOR_MATERIAL`（已移除，无内部调用方）
- `object/item/ArmorWithTickItem.java` — `extends ArmorItem`、`(Holder<ArmorMaterial>, Type, Properties)`
- `client/render/armor/IArmorRendererBase.java` — `ArmorMaterial.Layer`/`getMaterial().value().layers()`（**渲染，暂缓**）
- `client/render/armor/IArmorTextureProvider.java` — `ArmorMaterial.Layer` 参数（**渲染，暂缓**）
- `fabric/.../mixin/ArmorFeatureRendererMixin.java` — mixin 描述符（**P3，暂缓**）

**迁移方案**：
- `ArmorWithTickItem extends Item`，`super(settings.humanoidArmor(material, type))`。`humanoidArmor` 内部等价于旧 ArmorItem 构造：`durability(type.getDurability(material.durability())) + attributes(material.createAttributes(type)) + enchantable + EQUIPPABLE 组件 + repairable`。
- `RegistryHelper.getArmorMaterial` 删除：26.2 无 ARMOR_MATERIAL 注册表，等价物是 `ArmorMaterials.X` 常量或直接 `new ArmorMaterial(...)`（语义迁移，非"删功能硬凑编译"）。

## 五、Item.Properties（26.2 相关方法）

- `humanoidArmor(ArmorMaterial, ArmorType)` — 护甲
- `sword(ToolMaterial, float, float)` / `pickaxe/axe/shovel/hoe` / `tool(ToolMaterial, TagKey<Block>, float, float, float)`
- `repairable(TagKey<Item>)` / `repairable(Item)`、`enchantable(int)`、`durability(int)`
- `equippable(EquipmentSlot)` / `equippableUnswappable(EquipmentSlot)`
- `attributes(ItemAttributeModifiers)`、`component(DataComponentType, value)`、`stacksTo(int)`、`rarity(Rarity)`

**项目使用位置**：项目自身 `Item.Properties` 链（`ItemBase`/`FoilItemBase` 的 `new Properties().rarity(Rarity.COMMON).stacksTo(64)`）在 26.2 编译通过，**无需修改**。

## 六、Mixin 目标记录（本阶段只记录，不重写）

| Mixin | 旧 target | 26.2 现状 | 变化原因 | 处理 |
|---|---|---|---|---|
| fabric `ArmorFeatureRendererMixin` | `Lnet/minecraft/world/item/ArmorMaterial$Layer;texture(Z)Lnet/minecraft/resources/ResourceLocation;` | `ArmorMaterial$Layer` **已移除**；`ResourceLocation`→`Identifier`；`HumanoidArmorLayer` 类仍在 | Layer 贴图机制被 `equipment_asset` 模型系统替代 | P3 mixin 重写（随渲染） |
| common `ArmorFeatureRendererMixin` | `renderArmorPiece` + `MultiBufferSource` | `MultiBufferSource` 移除；渲染 API 重写 | 渲染系统重写 | P4 |
| common `BuiltinModelItemRendererMixin` | `BlockEntityWithoutLevelRenderer` | 该类移除 | 渲染系统重写 | P4 |

## 七、风险与行为注意

- **SwordItem 替换风险**：必须以 `Item.Properties.sword(material, 3.0F, -2.4F)` 还原 3 点攻击伤害、-2.4 攻速、破网/横扫行为，禁止自制"能编译的假 SwordItem"。
- **护甲替换风险**：`humanoidArmor` 同时承担耐久/属性/装备音效/装备槽/修复材料设置，不允许把旧字段塞进单个构造器。
- **修复材料语义变化**：Ingredient → TagKey\<Item\>，是职责重新分配（C），调用方需改用 item tag。
- **IArmorRendererBase / IArmorTextureProvider / 两个 ArmorFeatureRendererMixin 暂缓**：依赖已移除的 `ArmorMaterial.Layer` 与渲染系统，属渲染/装备资产重写（P3/P4），本阶段不触碰。
