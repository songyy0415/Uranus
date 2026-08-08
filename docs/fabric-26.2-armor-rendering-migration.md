# MC 26.2 Armor Rendering 迁移表（P2.3-C3-R 产出）

> 本阶段只做 **Armor Rendering 架构分析**，不做大规模代码迁移。
> 所有结论均直接核对本地 26.2 sources（`D:\aiminecraftdev\minecraft26.2`），**不是**按网络教程或 1.21.1 经验猜测。
> 迁移类型：**A**=mechanical replacement，**B**=signature adaptation，**C**=architectural rewrite，**D**=may become unnecessary。
> 严禁在本阶段修改任何 Java / Mixin / resource / build.gradle / fabric.mod.json / mappings 文件；唯一允许的新文件即本文档。

---

## 1. 状态

- 本阶段（P2.3-C3-R）为**纯分析**，未改动任何代码文件。
- 26.2 装甲渲染核心链路已通过本地源码逐行验证：`HumanoidArmorLayer`、`EquipmentLayerRenderer`、`EquipmentClientInfo`、`EquipmentAssetManager`、`ArmorModelSet`、`ArmorMaterial`、`Equippable`、`ArmorType`、`Item.Properties.humanoidArmor`、`ModelLayers`、`RenderTypes`、`Sheets`、`ArmorTrim`、`DyedItemColor`、`RenderLayer`/`LivingEntityRenderer`/`ModelFeatureRenderer`/`OrderedSubmitNodeCollector`、`Rotations`、`ArmorStandArmorModel`、`PlayerModel` 等。
- 项目侧 5 个装甲文件 + 3 个 Mixin 变体 + 3 个 mixin 配置已全部读完，并核对了仓库内引用（除自身与 mixin 外**无其他调用方**）。
- 本表是后续 P2.3-C3 实际代码迁移的实施地图。完成后按指令 commit `docs: add 26.2 armor rendering migration map` 并停止，不自动进入代码迁移。

---

## 2. 当前基线

- 全项目 javac 错误基线：**100 个**（`./gradlew :common:compileJava` 去重后）。
- 装甲相关错误簇：**67 个**，分布：
  | 文件 | 错误数 |
  |---|---|
  | `ArmorModelBase.java` | 38 |
  | `IArmorRendererBase.java` | 13 |
  | `IArmorRenderHelper.java` | 8 |
  | `IArmorTextureProvider.java` | 2 |
  | `ArmorFeatureRendererMixin.java`（common） | 6 |
  | fabric / neoforge 两个变体 | 计入各自模块（neoforge 未参与构建，见 §12） |
- 仓库内**无任何**对这 5 个类/接口的调用方（`grep -rn "IArmorRendererBase|IArmorRenderHelper|IArmorTextureProvider|ArmorModelBase|ArmorFeatureRendererMixin"`，排除文件自身与 mixin）——即它们是**对外公共 API**（Uranus 是库），但不被仓库内部消费。
- 三个 mixin 均注册于各自 mixin json 的 `"client"` 段：`uranus.mixins.json` L18、`uranus-fabric.mixins.json` L10、`uranus-neoforge.mixins.json` L10。

---

## 3. 1.21.1 装甲渲染架构（旧）

```
HumanoidArmorLayer.render(PoseStack, MultiBufferSource, int light, T entity, ...)
  └ renderArmorPiece(PoseStack, MultiBufferSource, int light, ItemStack stack,
                     EquipmentSlot slot, A model, boolean leggings, boolean inner, ...)   // protected
      ├ shouldRender → model = getArmorModel(entity, slot)
      ├ texture: stack.getItem() as ArmorItem → getMaterial().value().layers()
      │          → ArmorMaterial.Layer.texture(innerModel)   // inner=true 时返回 _layer_2.png，否则 _layer_1.png
      ├ ItemRenderer.getArmorFoilBuffer(vertexConsumers, RenderType.armorCutoutNoCull(texture), stack.hasFoil())
      ├ 按部件：model.head/hat/body/leftArm/rightArm/leftLeg/rightLeg.render(matrices, vc, light, overlay, -1)
      ├ 染色：ArmorItem.getColor() / ArmorMaterial.Layer.dyeable → 顶点颜色
      ├ 纹饰：Sheets.armorTrimsSheet(...)（多层）
      └ 闪光：getArmorFoilBuffer 的 foil 变体
```

特征：
- 渲染时**直接访问服务器实体**：`entity.getItemBySlot(slot)`、`entity` 传给 `setupAnim`。
- 纹理由 **ArmorMaterial（服务端 item 定义）内的 `Layer`** 决定；`ArmorMaterial.Layer.texture(Z)` 直接返回最终 `ResourceLocation`。
- 渲染是**立即写入顶点**：`vertexConsumers.getBuffer(RenderType)` → `VertexConsumer` → `model.renderToBuffer`。
- 装甲模型是 `HumanoidModel.ArmorModel`（1.21.1 内部类），靠 `baseModel.copyPropertiesTo(armorModel)` 复制基底模型姿态。
- `ArmorItem` 是具体 Item 子类，`getMaterial()` 返回 `ArmorMaterial`（含 `layers()`）。

---

## 4. 26.2 装甲渲染架构（新）

### 4.1 提取阶段（唯一允许读实体的地方）

```
HumanoidMobRenderer.extractHumanoidRenderState(entity, state, ...)   // HumanoidMobRenderer.java:58-90
  ├ (经 ArmedEntityRenderState.extractArmedEntityRenderState 填充手部)
  └ 装甲：state.headEquipment / chestEquipment / legsEquipment / feetEquipment
          = getEquipmentIfRenderable(entity, slot)                    // :86-89
    getEquipmentIfRenderable = HumanoidArmorLayer.shouldRender(stack, slot)
        ? entity.getItemBySlot(slot).copy() : ItemStack.EMPTY         // :92-95
```

装甲 ItemStack 落在 **`HumanoidRenderState`** 四个公共字段（`HumanoidRenderState.java:21-24`）：
```java
public ItemStack headEquipment = ItemStack.EMPTY;
public ItemStack chestEquipment = ItemStack.EMPTY;
public ItemStack legsEquipment = ItemStack.EMPTY;
public ItemStack feetEquipment = ItemStack.EMPTY;
```
**没有任何 getter**（`grep` 整个 `entity/state` 目录无 `getItemInSlot`/`getEquippedItem`），必须直接读公共字段。`state.entityType`/`state.isBaby`/`state.outlineColor` 来自父类。

### 4.2 提交阶段（只读 render state，禁止访问实体）

```
LivingEntityRenderer.submit(S state, PoseStack, SubmitNodeCollector, CameraRenderState)   // :72
  ├ this.model.setupAnim(state)                       // :100-101
  └ for layer : this.layers → layer.submit(poseStack, submitNodeCollector, state.lightCoords, state, state.yRot, state.xRot)   // :102-106

HumanoidArmorLayer<S extends HumanoidRenderState, M extends HumanoidModel<S>, A extends HumanoidModel<S>>
    extends RenderLayer<S, M>                          // :16
  submit(...)  [public]                                // :46-53
    └ renderArmorPiece 4 次（CHEST, LEGS, FEET, HEAD）
      renderArmorPiece(PoseStack, SubmitNodeCollector, ItemStack stack, EquipmentSlot slot, int lightCoords, S state)
                                                       // :55-74, PRIVATE, 新描述符
        ├ shouldRender(stack, slot) = equippable != null
        │     && equippable.assetId().isPresent() && equippable.slot() == slot   // :37-44
        ├ model = (state.isBaby ? babyModelSet : modelSet).get(slot)             // :76-78, ArmorModelSet
        ├ LayerType = state.isBaby && state.entityType != ARMOR_STAND
        │     ? HUMANOID_BABY : (usesInnerModel(slot) ? HUMANOID_LEGGINGS : HUMANOID)   // :66-68
        │     usesInnerModel(slot) == (slot == EquipmentSlot.LEGS)               // :80-82
        └ equipmentRenderer.renderLayers(layerType, equippable.assetId().orElseThrow(),
                                         model, state, stack, poseStack, submitNodeCollector,
                                         lightCoords, state.outlineColor)        // :69-72  [public]

EquipmentLayerRenderer.renderLayers(layerType, equipmentAssetId, Model<? super S> model, S state,
        ItemStack stack, PoseStack, SubmitNodeCollector, int lightCoords, int outlineColor)
                                                       // EquipmentLayerRenderer.java:40-111, public
  ├ layers = this.equipmentAssets.get(equipmentAssetId).getLayers(layerType)     // :67
  ├ dyeColor = DyedItemColor.getOrDefault(stack, 0)                              // :69
  ├ per Layer：
  │   layerTexture = layer.usePlayerTexture() && override != null
  │        ? override : this.layerTextureLookup.apply(new LayerTextureKey(layerType, layer))   // :76-78
  │   color = getColorForLayer(layer, dyeColor)                                  // :113-121
  │   submitNodeCollector.order(n).submitModel(model, state, poseStack,
  │        RenderTypes.armorCutoutNoCull(layerTexture), lightCoords,
  │        OverlayTexture.NO_OVERLAY, color, null, outlineColor, null)           // :79-91
  │   若 stack.hasFoil() → 追加一次 submitModel(RenderTypes.armorEntityGlint(), ...) 且 renderFoil=false 只发一次  // :92-99
  └ 若 trim != null 且 layerType != HUMANOID_BABY → 追加 trim：                          // :103-109
        sprite = armorTrimAtlas.getSprite(trim.layerAssetId(layerType.trimAssetPrefix(), assetId))
        submitNodeCollector.order(n).submitModel(model, state, poseStack,
             Sheets.armorTrimsSheet(trim.pattern().value().decal()), lightCoords,
             OverlayTexture.NO_OVERLAY, -1, sprite, outlineColor, null)
```

### 4.3 延迟绘制

`submitModel` 只记录延迟配方；顶点真正发出在帧绘制阶段：
`SubmitNodeCollection.submitModel`（按 renderType 路由到 solid/translucent/outline/breakingOverlay 相位）→ `ModelFeatureRenderer.prepareModel`（`ModelFeatureRenderer.java:25-37`：`getVertexBuilder(submit.renderType())` → `model.setupAnim(state)` → `model.renderToBuffer(pose, buffer, light, overlay, color)`）→ `Model.renderToBuffer`（final，只渲染 root）→ `ModelPart.render` 递归压入 Cube 顶点 → GPU `drawFromBuffer`。

---

## 5. ArmorMaterial 迁移

位置：`net.minecraft.world.item.ArmorMaterial` → **`net.minecraft.world.item.equipment.ArmorMaterial`**（`world/item/equipment/`）。

| 1.21.1 | 26.2 | 备注 |
|---|---|---|
| `class`（非 record） | `public record ArmorMaterial(...)` | 40 行文件 |
| `int durability` | `int durability` | 不变 |
| `Map<EquipmentSlot, Integer> defense` | `Map<ArmorType, Integer> defense` | key 改为 `ArmorType`（含新增 `BODY`） |
| `int enchantmentValue` | `int enchantmentValue` | 不变 |
| `Holder<SoundEvent> equipSound` | `Holder<SoundEvent> equipSound` | 不变 |
| `float toughness` | `float toughness` | 不变 |
| `float knockbackResistance` | `float knockbackResistance` | 不变 |
| `Supplier<Set<Item>> repairItems` | `TagKey<Item> repairIngredient` | 单个 TagKey |
| `List<Layer> layers` | `ResourceKey<EquipmentAsset> assetId` | **Layer 整块删除**，改为装备资源 key |
| `ArmorMaterial.Layer`（嵌套类） | **不存在** | §7 替代表 |
| `texture(boolean innerModel)` 逻辑 | 移到 `EquipmentClientInfo.Layer.getTextureLocation(LayerType)` | §7 |

唯一方法（`ArmorMaterial.java:25-39`）：
```java
public ItemAttributeModifiers createAttributes(final ArmorType type) {
    int defense = this.defense.getOrDefault(type, 0);
    // + ARMOR / ARMOR_TOUGHNESS / KNOCKBACK_RESISTANCE 属性，id = "armor.<name>"
}
```

**`ArmorItem` 类在 26.2 已被删除**（`**/ArmorItem*.java` 无结果）。装甲物品是普通 `Item` + `Item.Properties` 工厂：

```java
public Item.Properties humanoidArmor(final ArmorMaterial material, final ArmorType type) {   // Item.java:550-556
    return this.durability(type.getDurability(material.durability()))
        .attributes(material.createAttributes(type))
        .enchantable(material.enchantmentValue())
        .component(DataComponents.EQUIPPABLE,
            Equippable.builder(type.getSlot()).setEquipSound(material.equipSound())
                .setAsset(material.assetId()).build())
        .repairable(material.repairIngredient());
}
```

- `ArmorMaterials` 变为**接口 + static final 字段**（`LEATHER..ARMADILLO_SCUTE`），辅助 `makeDefense(boots,legs,chest,helm,body)`。
- `ArmorType` 枚举（`world/item/equipment/ArmorType.java:8-12`）：`HELMET(HEAD,11,"helmet") CHESTPLATE(CHEST,16,"chestplate") LEGGINGS(LEGS,15,"leggings") BOOTS(FEET,13,"boots") BODY(BODY,16,"body")`；`getSlot()/getName()/getDurability(int)`。
- `stack.getItem() as ArmorItem`、`getMaterial()`、`layers()` 全部失效。

---

## 6. EquipmentAsset 迁移

新增的**客户端数据驱动资源系统**，替代旧 ArmorMaterial 内的纹理定义。

```
ItemStack
  → DataComponents.EQUIPPABLE            // DataComponents.java:193-195, DataComponentType<Equippable>
  → Equippable.assetId()                 // Optional<ResourceKey<EquipmentAsset>>
  → EquipmentAssetManager.get(key)       // → EquipmentClientInfo（MISSING 兜底，永不为 null）
  → getLayers(LayerType)                 // → List<EquipmentClientInfo.Layer>
  → Layer.getTextureLocation(LayerType)  // → textures/entity/equipment/<layerType>/<texture>.png
```

- `EquipmentAsset` = 空 marker class；`EquipmentAssets.ROOT_ID = minecraft:equipment_asset`。
- `Equippable`（`world/item/equipment/Equippable.java:33-45`）：`record Equippable(EquipmentSlot slot, Holder<SoundEvent> equipSound, Optional<ResourceKey<EquipmentAsset>> assetId, Optional<Identifier> cameraOverlay, Optional<HolderSet<EntityType<?>>> allowedEntities, boolean dispensable, boolean swappable, boolean damageOnHurt, boolean equipOnInteract, boolean canBeSheared, Holder<SoundEvent> shearingSound)`。**无 `model` 字段，无 `equipAnimation` 字段**（grep 验证）。
- `Equippable.Builder.setAsset(ResourceKey<EquipmentAsset>)`（注意是 `setAsset`，不是 `setAssetId`，`Equippable.java:201`）。
- `EquipmentAssetManager`（`client/resources/model/EquipmentAssetManager.java`）＝ `SimpleJsonResourceReloadListener<EquipmentClientInfo>`，监听前缀 `equipment`；`get(key)` → `EquipmentClientInfo`。在 `Minecraft.java:585-586,608` 注册并注入 `EntityRenderDispatcher` → `EntityRendererProvider.Context.getEquipmentRenderer()`。
- `EquipmentClientInfo`（`client/resources/model/EquipmentClientInfo.java:18`）：`record EquipmentClientInfo(Map<LayerType, List<Layer>> layers)`；`Layer(textureId, Optional<Dyeable> dyeable, boolean usePlayerTexture)`（:78）；`Dyeable(Optional<Integer> colorWhenUndyed)`（:71）；`LayerType` 枚举（:107-143）：`HUMANOID, HUMANOID_LEGGINGS, HUMANOID_BABY, WINGS, WOLF_BODY, HORSE_BODY, LLAMA_BODY, PIG_SADDLE, STRIDER_SADDLE, CAMEL_SADDLE, CAMEL_HUSK_SADDLE, HORSE_SADDLE, DONKEY_SADDLE, MULE_SADDLE, ZOMBIE_HORSE_SADDLE, SKELETON_HORSE_SADDLE, HAPPY_GHAST_BODY, NAUTILUS_SADDLE, NAUTILUS_BODY`。
- CODEC 约束：`layers` map 非空、每个 layer 数组非空。

---

## 7. ArmorMaterial.Layer 替代表（确定结论）

**`ArmorMaterial.Layer` 在 26.2 完全不存在**（ArmorMaterial 只有 40 行、无嵌套 Layer、无 `layers()`；`world/item/equipment` 全包 grep 零命中）。

| 旧（1.21.1） | 新（26.2） | 证据 |
|---|---|---|
| `ArmorMaterial.Layer` 类 | 删除，由 `EquipmentClientInfo.Layer`（客户端 JSON 记录）承担 | `ArmorMaterial.java`（40 行） |
| `layer.texture(boolean innerModel)` → 最终 `ResourceLocation` | `EquipmentClientInfo.Layer.getTextureLocation(LayerType)` = `textureId.withPath("textures/entity/equipment/" + type.getSerializedName() + "/" + path + ".png")` | `EquipmentClientInfo.java:102-104` |
| `innerModel` 布尔（LEGS 内层） | `LayerType.HUMANOID_LEGGINGS`（LEGS 槽）vs `HUMANOID`（其余）；婴儿 → `HUMANOID_BABY` | `HumanoidArmorLayer.java:66-68,80-82` |
| `layer.dyeable`（boolean）+ tintIndex | `Optional<Dyeable> colorWhenUndyed`；渲染颜色 `getColorForLayer(layer, DyedItemColor.getOrDefault(stack,0))`；非染色层 `-1` | `EquipmentClientInfo.java:71-76,92-100`；`EquipmentLayerRenderer.java:69,113-121` |
| `layers()` 内 `_layer_1`/`_layer_2` 双列表 | 同一 `LayerType` 下**多个 Layer 条目**（如 leather 底 + `leather_overlay`） | `assets/minecraft/equipment/leather.json` |
| `ArmorMaterial$Layer.texture(Z)`（fabric mixin 目标） | **方法不存在**，目标失效 | fabric mixin `ArmorFeatureRendererMixin.java:32` |
| 旧的 mod 计算出的 `Identifier` 纹理参数 | mod 不再直接产纹理；改为提供 `ResourceKey<EquipmentAsset>`（`material.assetId()`），由客户端按 LayerType 推导 | §16 |

---

## 8. ArmorModelBase 分析

现状（`common/.../client/render/armor/ArmorModelBase.java`）：
- `extends HumanoidModel<LivingEntity>`（L17）——26.2 泛型已改为 `HumanoidModel<T extends HumanoidRenderState>`，`LivingEntity` 泛型报错。
- `setupAnim(LivingEntity, ...)`（L26-49）：ArmorStand 姿态分支用 `armorStand.getHeadPose().getX()` —— **`Rotations` 现在是 record**，访问器是 `x()/y()/z()` 而非 `getX()/getY()/getZ()`；且提交阶段禁止访问实体，ArmorStand 姿态必须从 `ArmorStandRenderState` 公共字段 `headPose/bodyPose/...`（`ArmorStandRenderState.java`）读取。
- `this.hat.copyFrom(this.head)`（L46）—— **`ModelPart.copyFrom` 已删除**（`net/minecraft` 全树 grep 无 copy helper）。
- `render(slot, PoseStack, MultiBufferSource, int light, ItemStack, Identifier)`（L51-58）+ 4 个 per-part 方法（L60-82）：用 `ItemRenderer.getArmorFoilBuffer`（**已删除**，`ItemRenderer` 类不存在）、`part.render(matrices, vc, light, overlay, -1)`。

26.2 事实：
- 装甲模型 = **每个槽一个独立 `HumanoidModel<S>`**（`ArmorModelSet<T>` record：`(T head, T chest, T legs, T feet)`，`get(EquipmentSlot)`，`static bake(ArmorModelSet<ModelLayerLocation>, EntityModelSet, Function<ModelPart,M>)`）。vanilla 就是 `HumanoidModel` 本身或其子类（`ArmorStandArmorModel extends HumanoidModel<ArmorStandRenderState>`）。
- 姿态不再靠 `copyPropertiesTo`：`ModelFeatureRenderer.prepareModel` 对每个装甲模型独立跑 `model.setupAnim(state)`，用同一份 `HumanoidRenderState` 复现基底姿态（装甲子网格保留同名部件）。
- `Model.renderToBuffer` 是 **final**（`Model.java:31-33`），只渲染 root —— 旧的重写/逐部件渲染路径不可行。

三方案对比：

| 方案 | 内容 | 评估 |
|---|---|---|
| **A：`extends HumanoidModel<HumanoidRenderState>`** | 保留类，改泛型 + `setupAnim(state)` + ArmorStand 分支改读 `ArmorStandRenderState` | 可行但**没必要作为基类**：26.2 装甲模型只需 vanilla `HumanoidModel`（或 `ArmorStandArmorModel`）即可，不必自建子类 |
| **B：直接用 vanilla `HumanoidModel<S>`** | 丢弃 `ArmorModelBase`，per-slot bake 时传 `HumanoidModel::new`；ArmorStand 用 `ArmorStandArmorModel` | 最贴近 26.2 原生；但丢掉项目自定义姿态逻辑 |
| **C：自定义适配（推荐）** | `ArmorModelBase` 改造为**项目自己的装甲模型工厂/基类**：`extends HumanoidModel<HumanoidRenderState>`，构造 `ModelPart`，`setupAnim(state)` 内对 `ArmorStandRenderState` 做 pose 覆盖（直接照抄 `ArmorStandArmorModel.setupAnim` 的 `state.headPose.x()` 模式），`hat` 用 `hat.xRot=head.xRot; hat.yRot=...; hat.zRot=...` 替代 `copyFrom`；删除 per-part `render` 系列（渲染职责移交 §18 的 submit 管线） | 保留项目 API 语义 + 符合 26.2 架构 |

**结论**：推荐 **C**（架构重写）。若项目对 ArmorStand 无特殊需求，退化到 B 更省。`INNER_MODEL_OFFSET`/`OUTER_MODEL_OFFSET` 常量在 26.2 无对应（inner/outer 由 LayerType 表达），删除。

---

## 9. IArmorRendererBase 分析

现状（`common/.../armor/IArmorRendererBase.java`）：
- `interface IArmorRendererBase<T extends LivingEntity>` + `HashMap<ItemLike, IArmorRendererBase<? extends LivingEntity>> RENDERERS`（L24-25）。
- `getHumanoidArmorModel(LivingEntity, ItemStack, EquipmentSlot, HumanoidModel<T>)`（L27）。
- `default getArmorTexture(ItemStack, Entity, EquipmentSlot)`（L29-35）：`ArmorItem.getMaterial().value().layers().getFirst().texture(...)` —— **ArmorItem/getMaterial/layers/texture 全失效**。
- `default render(PoseStack, MultiBufferSource, LivingEntity, EquipmentSlot, int light, ItemStack, HumanoidModel<T>)`（L37-49）：`defaultModel.copyPropertiesTo(armorModel)`（**已删**）→ 按槽设可见性 → `vertexConsumers.getBuffer(RenderType.armorCutoutNoCull(...))`（**MultiBufferSource/getBuffer 已删**）→ `armorModel.renderToBuffer`。

26.2 事实：
- `ItemLike` 仍存在（`world/level/ItemLike.java`）；`RENDERERS` 注册表可保留。
- 26.2 资产系统**只选纹理/染色/纹饰/模型**，**没有提供"整件替换"的 hook** → 注册表 + mixin cancel 仍是项目"自定义装甲渲染器"能力的唯一入口（§12 结论）。
- `render` 必须改为 submit 管线：**没有 VertexConsumer 可拿**，只能 `submitNodeCollector.order(n).submitModel(model, state, poseStack, RenderTypes.armorCutoutNoCull(texture), light, OverlayTexture.NO_OVERLAY, color, null, outlineColor, null)`，glint 追加一次 `armorEntityGlint()`。`entity` → `HumanoidRenderState`；姿态由 `setupAnim(state)` 独立驱动，`copyPropertiesTo` 不再需要。

**结论**：接口存活但需 **C 重构**。建议签名（示例，实施阶段定稿）：
```java
@Environment(EnvType.CLIENT)
public interface IArmorRendererBase<S extends HumanoidRenderState> {
    Map<ItemLike, IArmorRendererBase<? extends HumanoidRenderState>> RENDERERS = new HashMap<>();
    default HumanoidModel<S> getHumanoidArmorModel(S state, ItemStack stack, EquipmentSlot slot) { return null; } // 或改为提供 ModelPart
    void render(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, S state, EquipmentSlot slot, int lightCoords, ItemStack stack, HumanoidModel<S> defaultModel);
    static <S extends HumanoidRenderState> void register(IArmorRendererBase<S> renderer, ItemLike... items) { ... }
}
```

---

## 10. IArmorRenderHelper 分析

现状（`common/.../armor/IArmorRenderHelper.java`，无仓库内调用方）：
- `static renderPart(PoseStack, MultiBufferSource, int light, ItemStack, Model, Identifier)`（L34-37）：`ItemRenderer.getArmorFoilBuffer`（**已删**）→ `model.renderToBuffer`。来源 Fabric `ArmorRenderer`。
- `static translateToChest(PoseStack, PlayerModel<AbstractClientPlayer>, AbstractClientPlayer)`（L40-47）：`player.isCrouching()`（**提交阶段禁读实体**）、`model.body.xRot`。来源 trinkets。

26.2 事实：
- `PlayerModel` 移到 `net.minecraft.client.model.player.PlayerModel` 且泛型变为 `extends HumanoidModel<AvatarRenderState>` —— `PlayerModel<AbstractClientPlayer>` 双重失效。`AbstractClientPlayer` 仍存在。
- 裸 `Model` 无法直接 submit（`submitModel` 需要 `S state`）—— 可用 `Model.Simple(modelPart, rt)` + `Unit.INSTANCE` 包一层，或 `submitCustomGeometry`（但该接口回调 `render(PoseStack.Pose, VertexConsumer)` **不传 light/overlay/color**，需烤进 RenderType）。

**结论**：**B/C 适配，优先级中**。`renderPart` → submit 版（`submitModel` + 手动 glint）；`translateToChest` → `(PoseStack, S state)`（读 `state.isCrouching`，body 旋转来自 `HumanoidModel.setupAnim` 后的姿态，或改由调用方提供）。无调用方，可延后。

---

## 11. IArmorTextureProvider 分析

现状（`common/.../armor/IArmorTextureProvider.java`）：
- `@Deprecated(forRemoval = true)`（L9）。
- `Identifier getArmorTexture(ItemStack, Entity, EquipmentSlot, ArmorMaterial.Layer layer, boolean innerModel)`（L11）—— **`ArmorMaterial.Layer` 参数类型已不存在**，接口必然编译失败。
- 唯一调用方 = fabric mixin（§12），该 mixin 目标 `ArmorMaterial$Layer.texture(Z)` 也不存在。

26.2 事实：
- 纹理选择**100% 数据驱动**（`Equippable.assetId` → `EquipmentClientInfo.Layer.getTextureLocation`）。`EquipmentClientInfo.Layer` 是纯 record，**没有**任何 mod 扩展点。
- `EquipmentLayerRenderer.renderLayers` 唯一动态覆盖点是 `@Nullable Identifier playerTextureOverride` 参数（`EquipmentLayerRenderer.java:63`），**仅**当 `layer.usePlayerTexture()==true` 且 override != null 时替换成玩家皮肤 —— HumanoidArmorLayer 路径传 null。

**结论**：**D 移除/弃用（优先级中）**。静态换肤 → mod 自带 `Equippable.assetId` + `assets/<ns>/equipment/<id>.json` + PNG（无需 mixin）。若确需**逐 item/实体动态**选纹理，需新增 `EquipmentLayerRenderer.renderLayers` / `layerTextureLookup` 的新 mixin（超出本阶段，列为风险）。接口本身因引用已删类型而失效，建议直接删除（连同 fabric mixin），或保留一个不含 `ArmorMaterial.Layer` 的新签名。

---

## 12. ArmorFeatureRendererMixin 分析（common / fabric / neoforge）

### 12.1 common（`com.iafenvoy.uranus.mixin.ArmorFeatureRendererMixin`）

- `@Mixin(HumanoidArmorLayer.class)`，`@Inject(method="renderArmorPiece", at=@At("HEAD"), cancellable=true)`。
- 旧 handler 描述符 `(PoseStack, MultiBufferSource, T entity, EquipmentSlot, int light, A model, CallbackInfo)` **不再匹配**：
  - `renderArmorPiece` **仍存在但变成 private**，描述符重写为 `(PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int lightCoords, S state)V`（`HumanoidArmorLayer.java:55-74`）。
  - `MultiBufferSource` → `SubmitNodeCollector`；`entity` → `HumanoidRenderState state`；`A model` 参数被删（模型在方法内 `getArmorModel(state, slot)` 计算）。
- 新 handler 必须形如：
  ```java
  @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
  private void onRenderArmor(PoseStack matrices, SubmitNodeCollector collector, ItemStack stack,
                             EquipmentSlot armorSlot, int lightCoords, HumanoidRenderState state, CallbackInfo ci) { ... }
  ```
  `ItemStack` 是直接参数（index 2）、`EquipmentSlot` index 3 —— **不要**再 `entity.getItemBySlot(slot)`。
- mixin 类泛型要从 `<T extends LivingEntity, M extends HumanoidModel<T>, A extends HumanoidModel<T>>` 改为 `<S extends HumanoidRenderState, M extends HumanoidModel<S>, A extends HumanoidModel<S>>`；`extends RenderLayer<T, M>` 的基类写法在 26.2 泛型下不合法，改用 `@Shadow` 拿 `getParentModel()`（`RenderLayer.java:61` 仍提供 `public M getParentModel()`）。
- 可注入 private 方法（mixin 允许）；HEAD 取消粒度正好一个槽位。
- **结论：必要（保留自定义渲染器能力）+ 重写（D 换新描述符），优先级 Very High。** 若只注入 `submit(...)` 则一次要处理 4 槽（读 state 四字段），粒度太粗，不推荐。

### 12.2 fabric（`com.iafenvoy.uranus.fabric.mixin.ArmorFeatureRendererMixin`）

- `@ModifyExpressionValue(method="renderArmorPiece", at=@At(value="INVOKE", target="Lnet/minecraft/world/item/ArmorMaterial$Layer;texture(Z)Lnet/minecraft/resources/ResourceLocation;"))`（L32）。
- 目标 `ArmorMaterial$Layer.texture(Z)` **在 26.2 不存在**（ArmorMaterial 无 Layer）→ mixin **无法应用**，且引用已删类型。它调用的 `IArmorTextureProvider` 也失效。
- **结论：D 移除/替换，优先级 Very High。** 静态纹理替换已由装备 JSON 数据驱动取代；如确需动态纹理，另写 `EquipmentLayerRenderer` mixin（风险，见 §20）。

### 12.3 neoforge（`com.iafenvoy.uranus.neoforge.mixin.ArmorFeatureRendererMixin`）

- 逻辑与 common 相同 + `@OnlyIn(Dist.CLIENT)`。
- 记忆记录：**NeoForge 已按用户要求移出构建**（`settings.gradle` 只 `include 'common'`、`include 'fabric'`，neoforge 源码保留但不参与构建）→ 该 mixin 当前**休眠**，不在 javac 基线内。
- **结论：D 重写（与 common 同步），优先级低**；若后续恢复 NeoForge 构建再启用。

### 12.4 mixin 配置

三个 json 均注册 `"client"` 段（common L18、fabric L10、neoforge L10）。若 mixin 类更名/删除，需同步更新；若仅改内部描述符则无需改配置。

---

## 13. MultiBufferSource → SubmitNodeCollector 映射

| 1.21.1 | 26.2 |
|---|---|
| `MultiBufferSource vertexConsumers`（层参数） | `SubmitNodeCollector`（层参数）；**没有 VertexConsumer 可拿** |
| `vertexConsumers.getBuffer(RenderType)` | **无直接对应**。VertexConsumer 由相位运行器在绘制期延迟获取：`ModelFeatureRenderer.prepareModel` → `getVertexBuilder(submit.renderType())`（`ModelFeatureRenderer.java:27`） |
| `VertexConsumer vc = source.getBuffer(RenderTypes.armorCutoutNoCull(texture)); model.renderToBuffer(matrices, vc, light, overlay, color)` | `submitNodeCollector.order(n).submitModel(model, state, poseStack, RenderTypes.armorCutoutNoCull(texture), light, OverlayTexture.NO_OVERLAY, color, null, outlineColor, null)` |
| `ItemRenderer.getArmorFoilBuffer(source, type, hasFoil)` | **已删**（`ItemRenderer` 类不存在）。glint = 独立一次 `submitModel(...)`，RenderType 用 `RenderTypes.armorEntityGlint()` |
| `modelPart.render(matrices, vc, light, overlay, color)`（immediate） | 延迟到 `ModelFeatureRenderer.prepareModel`（`:36`）`model.renderToBuffer(pose, buffer, light, overlay, color)` → `ModelPart.render` |
| `ItemRenderer.getArmorGlintBuffer` | 不存在；装甲 glint 纹理常量 `ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR` |

`submitModel` 权威签名（`OrderedSubmitNodeCollector.java:61-72`）：
```java
<S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                     int lightCoords, int overlayCoords, int tintedColor,
                     @Nullable TextureAtlasSprite sprite, int outlineColor,
                     ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay);
```
重载：`Identifier texture` 版（经 `model.renderType(texture)`，`:87-98`）、sprite/SpriteGetter 版（`:100-124`）、`submitModelPart(ModelPart,...)` 版（把裸 ModelPart 包进 `Model.Simple` + `Unit.INSTANCE`，`:126-163`）。
`submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)`（`:186`）：回调 `void render(PoseStack.Pose pose, VertexConsumer buffer)` **不带 light/overlay/color**，仅用于无 Model/state 的原始几何；装甲需要逐 submit 的 light/overlay/tint，故走 `submitModel`。

---

## 14. RenderType 迁移

| 1.21.1 | 26.2 | 证据 |
|---|---|---|
| `RenderType.armorCutoutNoCull(ResourceLocation)` | `RenderTypes.armorCutoutNoCull(Identifier)` | `RenderTypes.java:401` |
| `RenderType.armorEntityGlint()` | 同（无参工厂） | `RenderTypes.java:542`；`RenderPipelines.GLINT` + `ARMOR_ENTITY_GLINT_TEXTURING`(scroll 0.16) |
| `RenderType.armorGlint(...)` / `armorTrim(...)` | **不存在**。glint 无纹理枚举（`GlintTexture`/`GlintType` 概念删除）；trim 用 `Sheets.armorTrimsSheet(boolean decal)` | 全树 grep 零命中 |
| `Sheets.armorTrimsSheet(boolean decal)` | 仍存在（decal → `ARMOR_TRIMS_DECAL_SHEET_TYPE`） | `Sheets.java:68-70` |
| 新增 | `RenderTypes.createArmorDecalCutoutNoCull(Identifier)` | `RenderTypes.java:405` |
| `RenderType` 可变状态工厂 | 不可变 `RenderType` = `RenderPipelines` + `RenderSetup`（`isOutline()/hasBlending()/affectsCrumbling()/outputTarget()` 驱动相位路由） | `RenderType.java` |
| `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` | 全工程 |

---

## 15. 染色 / 纹饰 / 闪光数据流

### 15.1 染色（Dye）
- `EquipmentLayerRenderer.java:69`：`int dyeColor = DyedItemColor.getOrDefault(stack, 0);`（`DyedItemColor` 在 `world/item/component/DyedItemColor.java`）。
- `getColorForLayer(layer, dyeColor)`（:113-121）：有 `dyeable` → `dyeColor != 0 ? dyeColor : colorWhenUndyed`；无 → `-1`。`color==0` 时跳过该层。
- 皮革默认 `color_when_undyed = -6265536`（= `0xFFA06540`，未染色皮革棕）。
- 旧 `ArmorItem.getColor()` / `layer.dyeable` 布尔 → 上述 Optional + JSON `dyeable.color_when_undyed`。

### 15.2 纹饰（Trim）
- `DataComponents.TRIM` → `ArmorTrim record(Holder<TrimMaterial> material, Holder<TrimPattern> pattern)`（包移到 `world/item/equipment/trim/`）。**无 `showInTooltip` 字段、无 `getPattern()/getMaterial()`**；访问器 `material()/pattern()`，经 `.value()` 解包。
- `trim.layerAssetId(String prefix, ResourceKey<EquipmentAsset> equipmentAsset)`（`ArmorTrim.java:41-44`）＝ `prefix + "/" + patternAssetId + "_" + materialSuffix`，如 `trims/entity/humanoid/sentry_iron_darker`。`prefix = LayerType.trimAssetPrefix()` = `"trims/entity/" + id`。
- `TrimMaterial` **无 `color()`**；`MaterialAssetGroup`（base + per-equipment overrides，如 `iron → iron_darker`）。
- sprite 来自 `armorTrimAtlas.getSprite(...)`（`EquipmentLayerRenderer` ctor）；RenderType = `Sheets.armorTrimsSheet(trim.pattern().value().decal())`；tint 固定 `-1`（**pre-tinted by palette**，运行时无 trim 染色概念）；`HUMANOID_BABY` 不渲染 trim。
- 纹饰仅 humanoid/humanoid_leggings 两组（`AtlasProvider.armorTrims`）。

### 15.3 闪光（Glint）
- `stack.hasFoil()`（`EquipmentLayerRenderer.java:70`）→ 基色层后追加一次 `submitModel(..., RenderTypes.armorEntityGlint(), ...)`（:92-97），`renderFoil=false` 保证**只发一次**。
- 与物品侧的 `ItemStackRenderState.FoilType {NONE,STANDARD,SPECIAL}` / `glintTranslucent()/glint()/entityGlint()` 无关（那是 `ItemFeatureRenderer`）。

---

## 16. 资源迁移

### 16.1 新增装备资源（旧的 `ArmorMaterial.Layer` 纹理的替代）
文件：`assets/<namespace>/equipment/<asset>.json`（资产 id = 文件名，命名空间来自文件路径）。
```json
{
  "layers": {
    "<layerTypeId>": [
      { "texture": "<ns>:<path>",
        "dyeable": { "color_when_undyed": -6265536 },
        "use_player_texture": false }
    ]
  }
}
```
- `<layerTypeId>` ∈ `humanoid / humanoid_leggings / humanoid_baby / wings / wolf_body / horse_body / llama_body / *_saddle / happy_ghast_body / nautilus_body`。
- 每个 layer 数组必须非空；`texture` 必填；`dyeable`/`use_player_texture` 可选。
- 纹理 PNG 位置：`textures/entity/equipment/<layerTypeId>/<texture path>.png`（命名空间保留）。子目录（实测）：`humanoid, humanoid_baby, humanoid_leggings, horse_body, wolf_body, llama_body, nautilus_body, happy_ghast_body, wings` + 各类 saddle。

实测 `netherite.json`：
```json
{ "layers": {
    "horse_body": [ { "texture": "minecraft:netherite" } ],
    "humanoid": [ { "texture": "minecraft:netherite" } ],
    "humanoid_baby": [ { "texture": "minecraft:netherite" } ],
    "humanoid_leggings": [ { "texture": "minecraft:netherite" } ],
    "nautilus_body": [ { "texture": "minecraft:netherite" } ] } }
```
实测 `leather.json`（双栈层 = 旧 `_layer_1`+`_layer_2` 惯例的替代）：
```json
{ "layers": { "humanoid": [
    { "texture": "minecraft:leather", "dyeable": { "color_when_undyed": -6265536 } },
    { "texture": "minecraft:leather_overlay" } ],
    "humanoid_baby": [ ... 同上 ... ],
    "humanoid_leggings": [ ... 同上 ... ],
    "horse_body": [ ... 同上 ... ] } }
```
- 旧 `textures/models/armor/<name>_layer_1.png / _layer_2.png`（`models/armor` 目录在 26.2 不存在）→ 迁移到上述新路径；inventory/手持物品模型（`models/item/...`）**不变**。

### 16.2 纹饰资源
- `atlases/armor_trims.json`（paletted permutation）→ `trims/entity/humanoid/...`、`trims/entity/humanoid_leggings/...`；调色板 `trims/color_palettes/trim_palette`。
- 自定义纹饰材质需要自定义 palette 与 suffix（`MaterialAssetGroup`）。

### 16.3 迁移动作（对 mod 使用者）
1. 给装备物品提供 `Equippable.assetId`（`Item.Properties.humanoidArmor` 已自动从 `material.assetId()` 设置）。
2. 新增 `assets/<ns>/equipment/<id>.json` 覆盖所需的 LayerType（humanoid / humanoid_leggings / humanoid_baby，需要马/狼/鞘翅再补）。
3. 新增对应 PNG。**缺 JSON 时 `EquipmentAssetManager.get` 返回 MISSING（空层）→ 什么都不渲染**，这是必须的资源迁移，否则视觉上"消失"。

---

## 17. 公共 API 兼容性

- `ArmorModelBase.render(slot, PoseStack, MultiBufferSource, int, ItemStack, Identifier)` / `renderHelmet/renderChestplate/renderLeggings/renderBoots`：26.2 无等价签名（无 MultiBufferSource、无 mod 产纹理 Identifier）—— **破坏性变更**（C）。
- `IArmorRendererBase`：`register(renderer, items...)` 可保留；`getArmorTexture`（ArmorItem 系）失效；`getHumanoidArmorModel` 需按 state 重构；`render` 破坏性变更（C）。
- `IArmorRenderHelper.renderPart`（MultiBufferSource/ItemRenderer）破坏性变更；`translateToChest`（`PlayerModel<AbstractClientPlayer>` + `player.isCrouching()`）双重失效（B/C）。
- `IArmorTextureProvider`：引用已删类型，**无法编译**，破坏性（D）。
- mixin 是类级 API：common 行为保持（HEAD cancel 自定义渲染器），签名破坏性；fabric 的换肤能力删除（数据驱动替代）。
- 仓库内无调用方 → 破坏只影响外部 mod 作者；Uranus 作为库需在 release notes 里标注 breaking。

---

## 18. 推荐实现架构

1. **保留"注册表 + mixin cancel"**作为自定义整件渲染器的唯一入口（26.2 资产系统无整件替换 hook）。
2. `IArmorRendererBase` 改为 `S extends HumanoidRenderState`，`render` 走 submit 管线（`submitNodeCollector.order(n).submitModel(...)`，自带 light/overlay/tint/outline），glint 由渲染器负责追加 `armorEntityGlint()`。
3. `ArmorModelBase` 按 §8-C 改造为项目自己的 `HumanoidModel<HumanoidRenderState>` 基类：`setupAnim(state)` 内对 `ArmorStandRenderState` 覆盖 pose（照 `ArmorStandArmorModel.setupAnim` 用 `state.headPose.x()`），`hat` 手动拷贝 `head` 旋转替代 `copyFrom`；删掉 per-part render。
4. 删 `IArmorTextureProvider` + fabric 换肤 mixin；静态纹理改数据驱动（§16）。若保留"动态逐 item 纹理"需求，另立 `EquipmentLayerRenderer` mixin（风险项）。
5. `IArmorRenderHelper` 适配 submit 版（可延后，无调用方）。
6. 为 mod 作者提供资源模板约定（equipment JSON + 目录结构）作为文档/示例。

---

## 19. 逐文件迁移计划

| 文件 | 类型 | 优先级 | 动作 |
|---|---|---|---|
| `common/.../mixin/ArmorFeatureRendererMixin.java` | D 替换 | **Very High** | 更新 `renderArmorPiece` 新描述符 `(PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int, HumanoidRenderState, CallbackInfo)`；泛型改 `S extends HumanoidRenderState`；去掉 `extends RenderLayer<T,M>` 改 `@Shadow getParentModel()`；ItemStack 直接取参 |
| `fabric/.../mixin/ArmorFeatureRendererMixin.java` | D 移除/替换 | **Very High** | `ArmorMaterial$Layer.texture(Z)` 目标不存在 → 删除；连同 `IArmorTextureProvider` 一并处理 |
| `common/.../armor/IArmorRendererBase.java` | C 重构 | **High** | 泛型→state；`render` → submit 管线；删 `getArmorTexture`/`copyPropertiesTo`；`RENDERERS` 保留 |
| `common/.../armor/ArmorModelBase.java` | C 重写 | **High** | `extends HumanoidModel<HumanoidRenderState>`；`setupAnim(state)` + ArmorStand(`state.headPose.x()` 等)；`hat` 手拷；删 per-part render；删 INNER/OUTER_OFFSET |
| `common/.../armor/IArmorTextureProvider.java` | D 删除 | Medium | 引用已删 `ArmorMaterial.Layer`，直接删除 |
| `common/.../armor/IArmorRenderHelper.java` | B/C 适配 | Medium | `renderPart` → submit 版；`translateToChest` → `(PoseStack, S state)`；无调用方可延后 |
| `neoforge/.../mixin/ArmorFeatureRendererMixin.java` | D 重写（休眠） | Low | 与 common 同步；NeoForge 未入构建，暂不阻塞 |
| `uranus.mixins.json` / `uranus-fabric.mixins.json` / `uranus-neoforge.mixins.json` | B | Medium | 若 mixin 类更名/删减才需改注册 |

---

## 20. 风险评估

| 风险 | 等级 | 说明 |
|---|---|---|
| 私有 `renderArmorPiece` 注入依赖当前字节码 | 高 | `@Inject` 可注入 private，但描述符/方法名一旦随版本漂移即失效；HEAD cancel 粒度正确但脆弱。备选 `submit(...)` 粒度太粗 |
| 26.2 无 VertexConsumer 可拿 | 高 | 自定义渲染器必须整体改为 submit 管线（`order(n).submitModel`），无法复用任何 `renderToBuffer` 立即渲染逻辑 |
| `copyPropertiesTo`/`copyFrom` 全删 | 高 | 姿态复制不复存在；每模型独立 `setupAnim(state)`，自定义渲染器不能再假设 baseModel 姿态被拷贝 |
| 资源缺失导致视觉空白 | 高 | 装甲物品若无对应 `equipment/*.json` → MISSING 空层，什么都不渲染；资源迁移是硬前置 |
| fabric 换肤 mixin 已死 | 中 | 目标类型删除；如保留需求需新 `EquipmentLayerRenderer` mixin（新 hook 点，稳定性未验证） |
| `Rotations.getX()` → `x()` | 中 | ArmorStand 姿态访问器改名（record）；漏改则编译错误 |
| `PlayerModel` 移包 + 泛型变更 | 中 | `net.minecraft.client.model.player.PlayerModel extends HumanoidModel<AvatarRenderState>`；影响 `IArmorRenderHelper` |
| 公共 API 破坏 | 中 | 对外库接口签名全变，需 release note；仓库内无调用方缓冲 |
| NeoForge 未参与构建 | 低 | neoforge mixin 休眠，恢复构建前无法验证 |

---

## 21. 实施顺序

1. **删除 fabric 换肤 mixin + `IArmorTextureProvider`**（消除不存在的 mixin 目标，先解锁编译）。
2. **重写 common mixin**：新 `renderArmorPiece` 描述符 + state 泛型 + submit 管线（`IArmorRendererBase` 新签名同步）。
3. **`IArmorRendererBase` 重构**（state 泛型、submit、去 copyPropertiesTo）。
4. **`ArmorModelBase` 重写**（state 泛型、ArmorStand pose、hat 手拷、删 per-part render）。
5. **`IArmorRenderHelper` 适配**（submit 版；可最后做）。
6. **同步 neoforge mixin**（休眠，随 common）。
7. **mixin 配置核对**；跑 `:common:compileJava` 收敛到非装甲剩余错误簇（原 100 - 67 = 33）。
8. 每步保持"不做假实现、不用反射、不强行模拟旧 API"。

---

## 22. 明确阻塞项（实施前需决策）

- **B1：`renderArmorPiece` 是 private** —— mixin 可注入，但自定义渲染器拿不到模型实例（模型在方法内 `getArmorModel` 计算）。要么在 mixin 里 @Shadow 调用 `getArmorModel(state, slot)`，要么让自定义渲染器自备模型。**需定夺注入粒度**（HEAD cancel 单槽 vs `submit` 全量）。
- **B2：是否保留"动态逐 item/实体纹理"能力** —— 数据驱动只支持静态；动态需新 `EquipmentLayerRenderer` mixin（hook 点不成熟）。若不保留，`IArmorTextureProvider` 直接删。
- **B3：`IArmorRendererBase` 是否保留 `getHumanoidArmorModel` SPI** —— 26.2 模型由 `ArmorModelSet.bake` 提供；若自定义渲染器需要独特几何，需决定"渲染器自建模型"还是"沿用 bake 的 vanilla 模型"。这决定接口签名。
- **B4：ArmorStand 特殊姿态是否还需要** —— 26.2 原生 `ArmorStandArmorModel` 已处理；项目只需验证自家自定义 armor 在 armor stand 上姿态正确（`ArmorStandRenderState` 字段）。若放弃自定义 ArmorStand 逻辑，`ArmorModelBase` 可退化为 vanilla `HumanoidModel`（方案 B）。
- **B5：资源前置** —— 项目现有自定义装甲物品必须先补 `equipment/*.json` + PNG，否则迁移后视觉消失。需确认这些物品是否存在、材质在何处。
- **B6：neoforge 处置** —— NeoForge 未入构建，neoforge mixin 是否保留待同步、还是删除，需用户确认（记忆已记录 NeoForge disabled）。
