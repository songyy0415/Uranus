# P2.3-C3-C-R — 26.2 Armor Renderer / Mixin 架构分析

> 纯分析文档。所有结论均以本地 26.2 源码（`D:\aiminecraftdev\minecraft26.2`）与本地编译 class
> （`javap` 于 Loom 缓存 `minecraft-merged-deobf-26.2.jar`）验证，不来自记忆/教程。
> 本阶段未修改任何 Java / Mixin / 资源 / 构建文件（§25 完成条件：0 修改）。

---

## §1 Status

`P2.3-C3-C-R COMPLETE` — 纯分析，0 代码修改。

## §2 Baseline

命令：`./gradlew :common:compileJava`（本阶段启动前基线）。

- javac 汇总：**88 errors, 4 warnings**（`p23c3c-before.log`，同一文件错误在日志中出现两次，distinct 行数见下）。
- Armor 相关错误文件（distinct error lines）：
  | 文件 | distinct error lines |
  |---|---|
  | `common/.../client/render/armor/IArmorRenderHelper.java` | `{9, 11, 12, 13, 34, 35, 40}`（7 行） |
  | `common/.../mixin/ArmorFeatureRendererMixin.java` | `{8, 22, 23, 28, 31}`（5 行） |
  | `common/.../client/render/armor/ArmorModelBase.java` | 0（已 C3-A 迁移，干净） |
  | `common/.../client/render/armor/IArmorRendererBase.java` | 0（已 C3-A 迁移，干净） |
- 无关基线（不属 C3-C）：Pathfinding 54 行、Collision 16 行、`BuiltinModelItemRendererMixin` 8 行（C3-D）、`BasicModelBase`/`HideableLayer`/`DynamicItemRenderer`/`EntityUtil`/`PathUtil`/`SoundUtil`/`WorldChunkUtil` 等。
- `fabric`/`neoforge` 模块不在 `:common:compileJava` 范围；neoforge 已整体移出构建（`settings.gradle` 仅 `include 'common' 'fabric'`）。

## §3 Current project architecture

读取当前工作树（非历史报告）：

- `IArmorRendererBase<S extends HumanoidRenderState>`：`HashMap<ItemLike, IArmorRendererBase<? extends HumanoidRenderState>> RENDERERS`；
  `getHumanoidArmorModel(S state, ItemStack, EquipmentSlot, HumanoidModel<S> defaultModel)`；
  `render(PoseStack, SubmitNodeCollector, S, EquipmentSlot, int, ItemStack, HumanoidModel<S>, RenderType)` default（内部 visibility + `order(0).submitModel` + `hasFoil` → `order(1).submitModel(RenderTypes.armorEntityGlint())`）；
  `register(IArmorRendererBase<S>, ItemLike...)`。
- `ArmorModelBase extends HumanoidModel<HumanoidRenderState>`：`setupAnim(HumanoidRenderState)`（ArmorStand 经 `instanceof ArmorStandRenderState` 读 `headPose.x()/y()/z()` 等）；`render(slot,...)`/`renderHelmet/.../renderBoots` → `setArmorPartVisibility` + `submitArmor`（`order(0).submitModel` + glint）。
- `IArmorRenderHelper`（7 个 distinct 编译错误，无任何项目内调用方）：
  - `:9` `import net.minecraft.client.model.PlayerModel;` — 26.2 移到 `net.minecraft.client.model.player.PlayerModel`（符号缺失）。
  - `:11` `import net.minecraft.client.renderer.MultiBufferSource;` — 26.2 该类已删（替换为 `SubmitNodeCollector`）。
  - `:12` `import net.minecraft.client.renderer.RenderType;` — 移到 `net.minecraft.client.renderer.rendertype.RenderType`。
  - `:13` `import net.minecraft.client.renderer.entity.ItemRenderer;` — `ItemRenderer` 类在 26.2 删除。
  - `:34` `ItemRenderer.getArmorFoilBuffer(...)` — 方法/类删除。
  - `:35` `model.renderToBuffer(...)` — `Model.renderToBuffer` 现为 `final`，且提交期无 `VertexConsumer`。
  - `:40` `PlayerModel<AbstractClientPlayer> model` 于 `translateToChest` — `PlayerModel` 包迁移；`player.isCrouching()`/`model.riding` 为 Entity 驱动，须改 RenderState。
- `mixin/ArmorFeatureRendererMixin`（common，5 个 distinct 编译错误）：
  - `:8` `import net.minecraft.client.renderer.MultiBufferSource;` — 类删除。
  - `:22` `extends RenderLayer<T, M>` — `RenderLayer<S extends EntityRenderState, M extends EntityModel<? super S>>`（26.2 `RenderLayer.java:14`），而 `T extends LivingEntity`（Entity，非 RenderState）不在 `S extends EntityRenderState` 界内（2 条）。
  - `:23` 构造器 `super(context)` — 级联自 `:22`。
  - `:28` handler 签名 `MultiBufferSource vertexConsumers, T entity` — `MultiBufferSource` 删除 + `T` 非法。
  - `:31` `(IArmorRendererBase<T>) RENDERERS.get(...)` — `T` 不在 `S extends HumanoidRenderState` 界内，且 `? extends HumanoidRenderState` 不能转为 `IArmorRendererBase<T>`（2 条）。

## §4 26.2 HumanoidArmorLayer architecture

`D:\aiminecraftdev\minecraft26.2\net\minecraft\client\renderer\entity\layers\HumanoidArmorLayer.java`（83 行，全文读取）：

```java
public class HumanoidArmorLayer<S extends HumanoidRenderState, M extends HumanoidModel<S>, A extends HumanoidModel<S>>
        extends RenderLayer<S, M> {
    private final ArmorModelSet<A> modelSet;      // 成人模型
    private final ArmorModelSet<A> babyModelSet;  // 婴儿模型
    private final EquipmentLayerRenderer equipmentRenderer;  // 贴图/染色/trim/glint 唯一入口
}
```

- `ArmorModelSet<T>(T head, T chest, T legs, T feet)`（`ArmorModelSet.java`）：`get(EquipmentSlot)` 按 slot 取模型。盔甲几何**就是 `HumanoidModel`**：`HumanoidModel.createArmorMeshSet(innerDeformation, outerDeformation)` 产出 `ArmorModelSet<MeshDefinition>`，再 `ArmorModelSet.bake(...)` 烘焙为 `HumanoidModel`。无独立 `ArmorModel` 类。
- vanilla 按 slot 预烘焙**部分网格**（`HumanoidModel.java:43-54` `ADULT_ARMOR_PARTS_PER_SLOT`）：
  HEAD→{head}、CHEST→{body,left_arm,right_arm}、LEGS→{left_leg,right_leg,body}、FEET→{left_leg,right_leg}。
  因此 vanilla **不需要** visibility 开关——网格本身只含该 slot 部件。自定义 renderer 返回的完整 `HumanoidModel` 则必须自己按 slot 设置可见性。
- `shouldRender(ItemStack, EquipmentSlot)` = `itemStack.get(DataComponents.EQUIPPABLE)` 非空 且 `equippable.assetId().isPresent()` 且 `equippable.slot() == slot`（`HumanoidArmorLayer.java:37-44`）。

## §5 renderArmorPiece exact signature

源码（`HumanoidArmorLayer.java:55-74`）：

```java
private void renderArmorPiece(final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector,
        final ItemStack itemStack, final EquipmentSlot slot, final int lightCoords, final S state)
```

**javap 验证的 JVM descriptor**（`minecraft-merged-deobf-26.2.jar`）：

```
renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;
                 Lnet/minecraft/client/renderer/SubmitNodeCollector;
                 Lnet/minecraft/world/item/ItemStack;
                 Lnet/minecraft/world/entity/EquipmentSlot;
                 I;
                 Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V
```

- private、instance、非 static、非 synthetic。
- 类级泛型 `S extends HumanoidRenderState` 在擦除后落到 bound `HumanoidRenderState`。
- **六个数据参数全部是方法形参**，无任何 local capture 需求（§16）。

## §6 renderArmorPiece call chain

`LivingEntityRenderer.java:103-104`：

```java
for (RenderLayer<S, M> layer : this.layers)
    layer.submit(poseStack, submitNodeCollector, state.lightCoords, state, state.yRot, state.xRot);
```

`HumanoidArmorLayer.submit`（public，`:46-53`）按 CHEST→LEGS→FEET→HEAD 顺序各调用一次 `renderArmorPiece`，每次传入 `state.<slot>Equipment`：

```java
this.renderArmorPiece(poseStack, submitNodeCollector, state.chestEquipment, EquipmentSlot.CHEST, lightCoords, state);
this.renderArmorPiece(poseStack, submitNodeCollector, state.legsEquipment, EquipmentSlot.LEGS, lightCoords, state);
this.renderArmorPiece(poseStack, submitNodeCollector, state.feetEquipment, EquipmentSlot.FEET, lightCoords, state);
this.renderArmorPiece(poseStack, submitNodeCollector, state.headEquipment, EquipmentSlot.HEAD, lightCoords, state);
```

`renderArmorPiece` 内部（`:63-73`）：

```java
Equippable equippable = itemStack.get(DataComponents.EQUIPPABLE);
if (equippable != null && shouldRender(equippable, slot)) {
    A model = this.getArmorModel(state, slot);          // (state.isBaby ? babyModelSet : modelSet).get(slot)
    LayerType layerType = state.isBaby && state.entityType != EntityTypes.ARMOR_STAND
            ? LayerType.HUMANOID_BABY
            : (this.usesInnerModel(slot) ? LayerType.HUMANOID_LEGGINGS : LayerType.HUMANOID);
    this.equipmentRenderer.renderLayers(layerType, equippable.assetId().orElseThrow(),
            model, state, itemStack, poseStack, submitNodeCollector, lightCoords, state.outlineColor);
}
```

职责划分（§6.2 全部答齐）：
- slot 判断：`submit` 对每个 slot 单独调 `renderArmorPiece`（每个 slot 独立执行，`ci.cancel()` 只影响当前 slot）。
- 可见性：vanilla 不设——由烘焙网格决定（§4）。
- model：`getArmorModel(state, slot)`（`babyModelSet`/`modelSet`，`usesInnerModel` = `slot == LEGS`）。
- baby：`state.isBaby && state.entityType != EntityTypes.ARMOR_STAND` → `HUMANOID_BABY`。
- dye / overlay / trim / glint / texture：全部由 `EquipmentLayerRenderer.renderLayers` 承担（§7）。
- outline：`state.outlineColor` 直传 `renderLayers`。

## §7 EquipmentLayerRenderer

`D:\aiminecraftdev\minecraft26.2\net\minecraft\client\renderer\entity\layers\EquipmentLayerRenderer.java`（131 行，全文读取）。

### 7.1 renderLayers 签名（javap 验证）

```java
// 9 参便捷版（等价 11 参传 (null, outlineColor, 1)）
public <S> void renderLayers(LayerType layerType, ResourceKey<EquipmentAsset> equipmentAssetId,
        Model<? super S> model, S state, ItemStack itemStack, PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector, int lightCoords, int outlineColor)
// descriptor: (LayerType;ResourceKey;Model;Object;ItemStack;PoseStack;SubmitNodeCollector;II)V  （S→Object）

// 11 参完整版
public <S> void renderLayers(LayerType layerType, ResourceKey<EquipmentAsset> equipmentAssetId,
        Model<? super S> model, S state, ItemStack itemStack, PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector, int lightCoords,
        @Nullable Identifier playerTextureOverride, int outlineColor, int order)
```

### 7.2 责任清单（源码逐行）

| 责任 | 承担者 | 证据 |
|---|---|---|
| texture lookup | `equipmentAssets.get(assetId).getLayers(layerType)` + 私有 memoized `layerTextureLookup`（`layer.getTextureLocation(layerType)`） | `:36,:67,:78,:123` |
| layer 迭代 | `for (Layer layer : layers)` | `:73` |
| dye | `DyedItemColor.getOrDefault(itemStack, 0)` + `getColorForLayer`（dyeable 层：`dyeColor != 0 ? dyeColor : colorWhenUndyed`；非 dyeable 层返回 -1） | `:69,:113-121` |
| overlay 层 | 作为**第二个** `Layer` 条目渲染（同 `layerType`，无 dyeable） | `:73-101` |
| submitModel | `submitNodeCollector.order(nextOrder++).submitModel(model, state, poseStack, RenderTypes.armorCutoutNoCull(layerTexture), lightCoords, OverlayTexture.NO_OVERLAY, color, null, outlineColor, null)` | `:79-91` |
| glint | `renderFoil = itemStack.hasFoil()`；每件盔甲**仅首个 layer** 后额外 `submitModel(..., RenderTypes.armorEntityGlint(), ..., color, ...)`；随后 `renderFoil = false` | `:70,:92-99` |
| trim | `itemStack.get(DataComponents.TRIM)`；`layerType != HUMANOID_BABY` 时 `trimSpriteLookup` → `Sheets.armorTrimsSheet(trim.pattern().value().decal())`，`submitModel(..., -1, sprite, outlineColor, null)` | `:103-109` |

### 7.3 是否适合作为 Mod SPI 边界

**是，且是唯一合规边界。** 证据：
- 26.2 纹理 100% 数据驱动：`Equippable.assetId()` → `EquipmentAssetManager.get(id)` → `EquipmentClientInfo.getLayers(layerType)` → `Layer.getTextureLocation(layerType)`（`EquipmentClientInfo.java:102-104`）。
- `EquipmentAssetManager` 运行时**无公开 getter**：`Minecraft.java:585-586` 创建后直接 `registerReloadListener` + 传入 `EntityRenderDispatcher` 构造器；`EntityRenderDispatcher` 持有 `private final EquipmentAssetManager equipmentAssets`（`:58`）且无 getter；`EntityRendererProvider.Context.getEquipmentAssets()` 仅注册期可用。→ 渲染期 mixin **无法**独立取到 asset manager。
- 因此「mixin 自己推导 RenderType」路径不可行；正确方向 = mixin 委托 `this.equipmentRenderer.renderLayers(...)`（与 vanilla `renderArmorPiece` 自身调用完全一致，`:69-72`）。

## §8 EquipmentClientInfo

`D:\aiminecraftdev\minecraft26.2\net\minecraft\client\resources\model\EquipmentClientInfo.java`（145 行，全文读取）：

- `record EquipmentClientInfo(Map<LayerType, List<Layer>> layers)`；JSON schema（`netherite.json`/`leather.json` 验证）：`{"layers":{"<layertype>":[{"texture":"<id>",optional "dyeable":{"color_when_undyed":N},optional "use_player_texture":bool}]}}`。
- `Layer(Identifier textureId, Optional<Dyeable> dyeable, boolean usePlayerTexture)`；`getTextureLocation(LayerType)` = `textures/entity/equipment/<layertype_serialized>/<path>.png`。
- `LayerType`：`HUMANOID="humanoid"`、`HUMANOID_LEGGINGS="humanoid_leggings"`、`HUMANOID_BABY="humanoid_baby"`（+ WINGS/WOLF_BODY/HORSE_BODY/SADDLE 系列/NAUTILUS_*）。
- `EquipmentAssetManager`：`SimpleJsonResourceReloadListener<EquipmentClientInfo>`，从 `assets/<ns>/equipment/*.json` 加载；`get(ResourceKey<EquipmentAsset>)` 缺省 `MISSING`。

## §9 SubmitNodeCollector

- `SubmitNodeCollector extends OrderedSubmitNodeCollector`（接口，`SubmitNodeCollector.java:6`）：`order(int)` 返回 `OrderedSubmitNodeCollector`。
- `OrderedSubmitNodeCollector.submitModel`：10 参规范形（`OrderedSubmitNodeCollector.java:61-72`）：
  `(Model<? super S> model, S state, PoseStack, RenderType, int lightCoords, int overlayCoords, int tintedColor, @Nullable TextureAtlasSprite sprite, int outlineColor, @Nullable CrumblingOverlay)`；8 参便捷形（tintedColor=-1, sprite=null）；Identifier 纹理形；sprite 形；`submitModelPart`（包 `Model.Simple` + `Unit.INSTANCE`，用于单 `ModelPart`）。
- `EquipmentLayerRenderer` 用 `order(nextOrder++).submitModel(...)`；`RenderLayer.renderColoredCutoutModel` 亦用 `submitModel`（`RenderLayer.java:46-58`）。
- **原则成立**：完整 ArmorModel（`HumanoidModel`/`ArmorModelBase`）应走 `submitModel`，不用 `submitCustomGeometry`（后者无 light/overlay/color，只给自定义几何渲染器）。
- 参数来源（C3-C 上下文）：
  | 参数 | 来源 |
  |---|---|
  | model | 自定义 renderer 返回的 `HumanoidModel<S>` |
  | state | `renderArmorPiece` 方法形参 `S state` |
  | poseStack | `renderArmorPiece` 方法形参 |
  | renderType | `EquipmentLayerRenderer` 内部 `RenderTypes.armorCutoutNoCull(layerTexture)` |
  | lightCoords | `renderArmorPiece` 形参（源自 `state.lightCoords`） |
  | overlayCoords | `OverlayTexture.NO_OVERLAY` |
  | tintedColor | dye 计算出的 `color`（`getColorForLayer`），非 dyeable 层 -1 |
  | sprite | trim 精灵（`trimSpriteLookup`）；常规层 null |
  | outlineColor | `state.outlineColor` |
  | crumblingOverlay | null |

## §10 ArmorModelBase integration

- 当前 `ArmorModelBase extends HumanoidModel<HumanoidRenderState>`，含 `setupAnim(state)`（ArmorStand 姿态）、`render(slot,...)` → `setArmorPartVisibility` + `submitArmor`（`order(0).submitModel` + `hasFoil` glint）。
- 在 C3-C 目标架构（§14 Design A）中，`ArmorModelBase` **几何/姿态** 侧已就绪：自定义 renderer 的 `getHumanoidArmorModel` 返回 `ArmorModelBase`（或子类），其 `setupAnim(state)` 在 `ModelFeatureRenderer.prepareModel` 中被调用（`ModelFeatureRenderer.java:35-36`：`model.setupAnim(submit.state()); model.renderToBuffer(...)`）。
- C3-A 的 `render(...)`/`submitArmor`（自带 submit+glint）在 Design A 下**不再由 common mixin 调用**——这是 C3-A 与 26.2 调用链的一处设计冲突，记录如下（不修改 C3-A）：

> **冲突（记录，不修改）**：C3-A `IArmorRendererBase.render(...)` 设计为「调用方提供 `RenderType`，SPI 自行 submit+glint」。但 26.2 渲染期无法取到 `EquipmentAssetManager` 以构造该 `RenderType`（§7.3），vanilla 的染色/trim/glint 也只在 `EquipmentLayerRenderer.renderLayers` 内完成。→ 修正方案：common mixin 采用 Design A（§14），委托 `renderLayers` 获得 26.2 的贴图/染色/trim/glint；`render(RenderType)` default 方法保留为「完全自控」可选路径（消费者可从别处自行构造 RenderType），并建议 C3-C 实施时在 `IArmorRendererBase` 增加一个只做「模型+可见性」的默认方法（如 `setArmorPartVisibility(HumanoidModel<S>, EquipmentSlot)`），使 Design A 不复制可见性逻辑。

## §11 IArmorRendererBase integration

- 26.2 真实调用关系（grep 全项目，仅）：`RENDERERS`/`register`/`getHumanoidArmorModel`/`render` 仅在 `IArmorRendererBase.java` 定义；**common 与 neoforge 两个 mixin** 引用 `RENDERERS.get`；`ArmorModelBase` 只被外部消费者继承。项目内**没有**任何自定义 renderer 实现。
- `getHumanoidArmorModel(S, ItemStack, EquipmentSlot, HumanoidModel<S>)` 的调用方：在旧架构是 mixin（替换 vanilla 模型）；在 26.2 目标架构（Design A）**仍是 mixin** —— 在 `renderArmorPiece` HEAD 拦截，查到 renderer 后调用它取得自定义模型，再交给 `renderLayers`。
- 无 unchecked-cast 方案：`RENDERERS` 值类型为 `? extends HumanoidRenderState`，读侧 `(IArmorRendererBase<S>)` 属 unchecked，且项目规则禁止 `@SuppressWarnings("unchecked")`。推荐 C3-C 实施时将 map 值类型改为 `IArmorRendererBase<HumanoidRenderState>`、`register` 接收 `IArmorRendererBase<HumanoidRenderState>`（`register` 泛型改为非泛型或界上界），mixinh 直读无需强转；或保留泛型但集中一处带注释的 cast helper（不推荐）。此为 C3-A API 微调，列入 C3-C 决策，不在本阶段修改。

## §12 IArmorRenderHelper error classification

每个错误分类（§3 已列逐行符号）：

| Line | 符号/现象 | 分类 | 处置子阶段 |
|---|---|---|---|
| 9 | `PlayerModel` 移到 `net.minecraft.client.model.player` | **A** 纯 API rename（包迁移） | C3-C1 |
| 11 | `MultiBufferSource` 删除 | **C** → `SubmitNodeCollector` | C3-C1 |
| 12 | `RenderType` 移到 `...rendertype.RenderType` | **A** 纯 API rename（包迁移） | C3-C1 |
| 13 | `ItemRenderer` 类删除 | **C** → `RenderTypes` + `submitModel` | C3-C1 |
| 34 | `ItemRenderer.getArmorFoilBuffer` 删除 | **C** → 独立 `RenderTypes.armorEntityGlint()` submit | C3-C1 |
| 35 | `model.renderToBuffer(...)`：`Model.renderToBuffer` 现 final、提交期无 `VertexConsumer` | **C** → `collector.order(n).submitModel(...)` | C3-C1 |
| 40 | `translateToChest`：`PlayerModel<AbstractClientPlayer>` 包迁移 + Entity 驱动（`player.isCrouching()`/`model.riding`） | **E** → RenderState（`state.isCrouching`/`state.isPassenger`/`state.isSwimming`） | C3-C1 |

- 无 F（Equipment Asset）类错误；`IArmorRenderHelper` 不涉贴图。
- **建议：全部归入 C3-C1**（独立库 API，无项目内调用方，低风险）。

## §13 Common Mixin feasibility

**可行，采用 `@Inject HEAD + cancellable`。**

- `renderArmorPiece` 是 private 实例方法；Sponge 可注入 private。
- **无需 @Local / LocalCapture**：六个数据参数全部是方法形参（§5），handler 直接收取。→ §16 结论：**C3-C 可完全避免 local capture**。
- `@Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)`，descriptor 用 §5 javap 结果（或仅方法名，因类内唯一；建议带 descriptor 以抗未来重载）。
- cancel 语义：`submit` 对 CHEST/LEGS/FEET/HEAD 各调一次 `renderArmorPiece`；cancel 仅跳过当前 slot 的渲染，其余 slot 继续（`HumanoidArmorLayer.java:49-52`）。
- handler 参数：
  ```java
  private void onRenderArmor(PoseStack poseStack, SubmitNodeCollector collector,
          ItemStack itemStack, EquipmentSlot slot, int lightCoords, HumanoidRenderState state, CallbackInfo ci)
  ```
- `state` 在 26.2 是**方法形参**，因此渲染期**零 Entity 访问**（旧代码 `entity.getItemBySlot` 删除）——符合 RenderState 架构。
- 可行性的剩余条件（全部已源码验证存在）：
  - `this.getParentModel()` → `HumanoidModel<S>`（`RenderLayer.java:61-63`，mixin 声明 `M extends HumanoidModel<S>`）。
  - `@Shadow private EquipmentLayerRenderer equipmentRenderer;`（字段存在，`HumanoidArmorLayer.java:19`）。
  - `DataComponents.EQUIPPABLE`（`DataComponents.java:193`）、`EntityTypes.ARMOR_STAND`（`EntityTypes.java:181`）、`state.isBaby`（`LivingEntityRenderState.java:22`）、`state.entityType`/`outlineColor`（`EntityRenderState.java:15,28`）。
  - `EquipmentLayerRenderer.renderLayers` 9 参便捷版（§7.1）。

## §14 Fabric Mixin feasibility

旧 fabric mixin（`fabric/.../mixin/ArmorFeatureRendererMixin.java`）：

```java
@ModifyExpressionValue(method = "renderArmorPiece",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ArmorMaterial$Layer;texture(Z)Lnet/minecraft/resources/ResourceLocation;"))
private ResourceLocation pushTexture(ResourceLocation original,
        @Local(ordinal = 0) ItemStack itemStack, @Local(ordinal = 0, argsOnly = true) T entity,
        @Local(ordinal = 0, argsOnly = true) EquipmentSlot armorSlot, @Local(ordinal = 0) ArmorMaterial.Layer layer)
```

源码证据链：
- `ArmorMaterial$Layer` 在 26.2 **不存在**（`ArmorMaterial` 是 40 行 record，无嵌套类；`ArmorMaterial.java`）。target `texture(Z)ResourceLocation` 不存在。
- `IArmorTextureProvider` 已在 C3-B 删除（零实现，唯一 consumer 即本 mixin）。
- `ResourceLocation` → `Identifier`；`ArmorMaterial.Layer` local 类型已删。
- 该 mixin **注册于** `fabric/src/main/resources/uranus-fabric.mixins.json` client 列表（`required:true`、`defaultRequire:1`）。不处理则启动期 mixin 应用失败崩溃。
- **结论：删除** fabric mixin 类，并从 `uranus-fabric.mixins.json` 移除该条目。职责（逐 item 换纹理）已被 C3-B 的 Equipment Asset 数据驱动完全取代；26.2 无等价 native hook，且无实现可迁移。
- 在 `uranus.mixins.json` 中注册的 **common** mixin 才是真正的 Armor SPI hook（模型替换入口），fabric 不需要额外 hook。fabric 模块的 arm 渲染全部走 common mixin（fabric-only 构建：common source 被 fabric 依赖）。

## §15 NeoForge Mixin status

- `settings.gradle` 仅 `include 'common' 'fabric'` → **neoforge 模块不在构建中**。
- `neoforge/.../ArmorFeatureRendererMixin.java`（旧 common 同款）不被编译、不被注册、不参与 common source-set。
- **结论：完全忽略**，不为「保持所有 loader」修改 NeoForge。

## §16 @Local / LocalCapture analysis

- 旧 fabric mixin 依赖 `@Local(ordinal=0) ItemStack`、`@Local(ordinal=0, argsOnly=true) T entity`、`@Local(ordinal=0) EquipmentSlot armorSlot`、`@Local(ordinal=0) ArmorMaterial.Layer layer` —— 这些 local 在 26.2 `renderArmorPiece` 中**要么是形参、要么类型已删**（§5/§14）。
- 新 common mixin（Design A）handler 全部数据取自**方法形参**，**零 @Local / LocalCapture**。答案：**C3-C 完全避免 local capture**。
- 唯一需要的 @Shadow：`private EquipmentLayerRenderer equipmentRenderer`（字段，非 local）。

## §17 JVM descriptor

§5 已给出 `renderArmorPiece` 完整 descriptor（javap 实证）。补充：
- `submit` descriptor：`(PoseStack;SubmitNodeCollector;IHumanoidRenderState;FF)V`（javap 实证）。
- `getArmorModel` descriptor：`(HumanoidRenderState;EquipmentSlot;)HumanoidModel`（javap 实证）。
- `EquipmentLayerRenderer.renderLayers` 9 参：`(LayerType;ResourceKey;Model;Object;ItemStack;PoseStack;SubmitNodeCollector;II)V`；11 参：`(...;ILIdentifier;II)V`（javap 实证，`S`→`Object`）。

## §18 Glint path

- vanilla：`EquipmentLayerRenderer.renderLayers` 内 `renderFoil = itemStack.hasFoil()`，**每件盔甲首个 layer** 后提交 `submitModel(..., RenderTypes.armorEntityGlint(), ...)`，随后 `renderFoil=false`（`EquipmentLayerRenderer.java:70,92-99`）。**glint 由 EquipmentLayerRenderer 自己处理。**
- C3-A：`IArmorRendererBase.render`/`ArmorModelBase.submitArmor` 也各自提交 glint（`order(1).submitModel(RenderTypes.armorEntityGlint())`）。
- 责任划分结论：
  - **Design A**（推荐）：common mixin 走 `renderLayers` → glint 由 vanilla 承担；SPI 的 `render()`（自带 glint）不被调用，不重复。
  - 若消费者直接调 `IArmorRendererBase.render(...)`（完全自控路径），则 glint 由 SPI 承担——两者**互斥**，同一渲染路径不得同时走，避免重复 glint submit。
  - 记录为设计注意点（C3-A 的 glint 提交与 vanilla 存在职责重叠，但仅在 Design B/自控路径激活，不冲突）。
- RenderType 差异：`armorCutoutNoCull(texture)` 与 `armorEntityGlint()`（`RenderTypes.java:401-403,542-544`）。

## §19 Dynamic texture compatibility

- C3-B 已删除 `IArmorTextureProvider`。再次全项目 grep 确认：`getArmorTexture(` 无任何 live 调用；`ArmorMaterial.Layer.texture(` 无 live 调用；无「动态 armor texture」实现。
- **Equipment Asset 已完整覆盖当前项目需求。** 26.2 下自定义盔甲 item 必须携带 `Equippable`（`Item.Properties.humanoidArmor(material, slot)` 自动 `setAsset(material.assetId())`）；纹理由 `assets/<ns>/equipment/<id>.json` + `assets/<ns>/textures/entity/equipment/<layertype>/<path>.png` 数据驱动。
- 唯一行为差异（记录）：旧 SPI 允许「任意 item 任意纹理」渲染；26.2 若无 `Equippable.assetId`，vanilla `renderArmorPiece` 的 `shouldRender` 门会直接跳过——自定义模型 item 必须配置 equipment asset 才能被渲染。C3-B 已将此文档化。

## §20 Proposed C3-C implementation architecture

**Design A（推荐，与 26.2 vanilla 一致）**：

```
HumanoidArmorLayer.renderArmorPiece (HEAD, cancellable)
        │  itemStack.getItem() ∈ IArmorRendererBase.RENDERERS?
        ▼ 是
customModel = renderer.getHumanoidArmorModel(state, itemStack, slot, this.getParentModel())
setArmorPartVisibility(customModel, slot)          // 见 §10 建议的 helper
layerType = state.isBaby && state.entityType != ARMOR_STAND ? HUMANOID_BABY
          : (slot == LEGS ? HUMANOID_LEGGINGS : HUMANOID)
this.equipmentRenderer.renderLayers(layerType, assetId, customModel, state, itemStack,
        poseStack, collector, lightCoords, state.outlineColor)
ci.cancel()
        ▼
EquipmentLayerRenderer 负责 texture/dye/overlay/trim/glint/submitModel
```

不采用 Design B（mixin 推导 `RenderType` 后调 `renderer.render(...)`）：因 `EquipmentAssetManager` 渲染期不可达（§7.3）。

## §21 File-by-file migration plan

### C3-C1 — `IArmorRenderHelper.java`（API 迁移，无调用方）
- Current errors：`{9,11,12,13,34,35,40}`（§12）。
- Old API → 26.2 API：
  - `renderPart(PoseStack, MultiBufferSource, int, ItemStack, Model, Identifier)` → `renderPart(PoseStack, SubmitNodeCollector, HumanoidRenderState, int, ItemStack, Model, Identifier)`：内部 `collector.order(0).submitModel(model, state, poseStack, RenderTypes.armorCutoutNoCull(texture), lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null)` + `hasFoil` → `order(1).submitModel(..., RenderTypes.armorEntityGlint(), ...)`。
  - `translateToChest(PoseStack, PlayerModel<AbstractClientPlayer>, AbstractClientPlayer)` → `translateToChest(PoseStack, HumanoidModel<S>, S state)`：读 `state.isCrouching`/`state.isPassenger`/`state.isSwimming`/`state.bodyRot`，用 `state.bodyRot` 旋转；`PlayerModel` import → `net.minecraft.client.model.player.PlayerModel`（若保留）。
- Migration type：A+C（包迁移 + SubmitNodeCollector 重构）。
- Dependencies：`RenderTypes`/`SubmitNodeCollector`/`HumanoidRenderState`。
- Risk：低（库 API，无项目内调用方；签名变更须记录在 javadoc）。
- Order：1。

### C3-C2 — `common/.../mixin/ArmorFeatureRendererMixin.java`（重写，核心）
- Current errors：`{8,22,23,28,31}`（§3）。
- Old API → 26.2 API：
  - 类声明 `ArmorFeatureRendererMixin<T extends LivingEntity, M extends HumanoidModel<T>, A extends HumanoidModel<T>> extends RenderLayer<T, M>` → `ArmorFeatureRendererMixin<S extends HumanoidRenderState, M extends HumanoidModel<S>> extends RenderLayer<S, M>`。
  - handler `onRenderArmor(PoseStack, MultiBufferSource, T, EquipmentSlot, int, A, CallbackInfo)` → `onRenderArmor(PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int, HumanoidRenderState, CallbackInfo)`。
  - 删除 `entity.getItemBySlot(armorSlot)`（state 已是形参）。
  - 删除 `@SuppressWarnings("unchecked")` cast → 按 §11 的 map/register 上界方案（map 值 `IArmorRendererBase<HumanoidRenderState>`）或 cast helper。
  - 新增 `@Shadow private EquipmentLayerRenderer equipmentRenderer;`。
  - 按 §20 Design A 实现 HEAD+cancel；descriptor 用 §5。
- Migration type：架构重写（RenderState + EquipmentLayerRenderer 委托）。
- Dependencies：C3-A（`IArmorRendererBase`）、C3-B（`Equippable.assetId` 链）、本 doc §5/§7/§20。
- Risk：中高——mixin 注册于 `uranus.mixins.json`（required，defaultRequire 1），descriptor 必须精确，否则客户端启动即崩；实施后须 `runClient` 验证。
- Order：2。

### C3-C3 — fabric mixin cleanup
- `fabric/.../mixin/ArmorFeatureRendererMixin.java`：**删除**（§14）；同时从 `fabric/src/main/resources/uranus-fabric.mixins.json` client 列表移除 `"ArmorFeatureRendererMixin"`。
- `neoforge/.../mixin/ArmorFeatureRendererMixin.java`：**不动**（模块不在构建，§15）。
- Migration type：删除。
- Risk：低；但必须同步改 mixins.json，否则 fabric 启动找类失败。
- Order：3。

### C3-C4 — 验证
- `./gradlew :common:compileJava`（目标：IArmorRenderHelper + common mixin 清零；无关基线不动）。
- `./gradlew runClient`（或至少 `:fabric:compileJava` + mixin 应用自检）验证 mixin descriptor 命中、armor 渲染、ArmorStand/baby/glint/trim。
- `git diff --check`、`git status`、`git diff --stat`。

### 允许但仅当 C3-C 证据确凿
- `IArmorRendererBase.java`：按 §10/§11 增加 `setArmorPartVisibility(HumanoidModel<S>, EquipmentSlot)` 默认方法（去重）、map/register 上界化（去 cast）。属 C3-A 的**最小补充**，须在 C3-C2 实施时单独记录。
- `ArmorModelBase.java`：仅当 Design A 暴露问题才动（当前 0 错误，不动）。

## §22 Risk assessment

| 风险 | 等级 | 缓解 |
|---|---|---|
| mixin descriptor 错误 → 客户端启动崩溃（`uranus.mixins.json` required） | 高 | §5 javap descriptor 直接使用；C3-C4 runClient |
| `EquipmentAssetManager` 不可达 → 误选 Design B 而阻塞 | 高 | §7.3 已定性：Design A 唯一合规 |
| 自定义模型可见性未设置 → 全模型渲染 | 中 | §10 建议 helper + 单测一个 slot 模型 |
| `@SuppressWarnings("unchecked")` 禁止规则被触发 | 中 | §11 map 上界方案 |
| glint 双重提交（SPI + vanilla） | 中 | §18 职责互斥记录 |
| C3-A `render()` 与 Design A 分歧 | 低 | §10 冲突记录 + 保留自控路径 |
| IArmorRenderHelper 签名变更影响下游 mod | 低 | 库 API，javadoc 记录 old→new→breaking |

## §23 Verification requirements（本阶段已完成项）

- ✓ 26.2 vanilla source：`HumanoidArmorLayer`、`EquipmentLayerRenderer`、`EquipmentClientInfo`、`ArmorModelSet`、`RenderLayer`、`Model`、`EntityModel`、`HumanoidModel`、`HumanoidRenderState`、`EntityRenderState`、`LivingEntityRenderState`、`RenderTypes`、`DataComponents`、`EntityTypes`、`Minecraft`、`EntityRenderDispatcher`、`ModelFeatureRenderer`、`LivingEntityRenderer`、`ArmorMaterial`、`Equippable`、`EquipmentAssetManager` 全部直接读取。
- ✓ current project source：common/fabric/neoforge 三个 mixin + `IArmorRenderHelper` + `ArmorModelBase` + `IArmorRendererBase` 直接读取。
- ✓ exact method signature：§5（源码 + javap）。
- ✓ JVM descriptor：§5/§17（javap `minecraft-merged-deobf-26.2.jar`）。
- ✓ Mixin target existence：`renderArmorPiece` 存在（javap 实证）；`ArmorMaterial$Layer;texture(Z)` 不存在（删除）。
- ✓ Equipment Asset path：§7/§8（`assetId → EquipmentAssetManager.get → getLayers(layerType) → getTextureLocation`）。
- ✓ SubmitNodeCollector path：§9。
- ✓ ArmorModelBase integration：§10。
- ✓ IArmorRendererBase integration：§11。
- ✓ no hidden compile errors：baseline 88 errors 全量分类（§2）；本阶段 0 修改。

## §24 Explicit blockers / decisions

- **B1** `EquipmentAssetManager` 渲染期不可达 → Design A（委托 `equipmentRenderer.renderLayers`）为唯一合规方案；Design B 废弃。
- **B2** fabric mixin 必须删除 + 同步移除 `uranus-fabric.mixins.json` 条目；neoforge 忽略。
- **B3** common mixin descriptor 必须用 §5 javap 值；`defaultRequire:1` 下写错即启动崩。
- **B4** C3-A `render(RenderType)` 在 Design A 下不被 common mixin 调用 → 记录为设计分歧，保留为自控路径；C3-C 实施时可对 `IArmorRendererBase` 做最小补充（可见性 helper / map 上界）且单独记录。
- **D1** 自定义盔甲 item 在 26.2 必须携带 `Equippable`（equipment asset），否则 `shouldRender` 门跳过（C3-B 已文档化）。
- **D2** 旧 `@Local` fabric mixin 废弃；C3-C 零 local capture。
- **D3** 无动态纹理需求：Equipment Asset 覆盖当前项目全部需求。

### Q&A（§22 重点设计问题）

- **Q1** 是否继续 `@Inject renderArmorPiece HEAD + cancellable`？→ **是**。descriptor：
  `(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V`。
- **Q2** mixin 是否可直接拿到 SubmitNodeCollector / ItemStack / EquipmentSlot / HumanoidRenderState / light？→ **全部是形参，可直接拿到**（§5）。缺：自定义模型（`renderer.getHumanoidArmorModel` 获得）、`RenderType`/纹理（交由 `@Shadow equipmentRenderer.renderLayers`）。
- **Q3** `IArmorRendererBase.render()` 最终在哪被调用？→ Design A 下 common mixin **不调用** `render()`；mixin 调用 `getHumanoidArmorModel` 后委托 `equipmentRenderer.renderLayers`。`render()` 保留给需要完全自控的消费者自行调用（其 `RenderType` 由消费者自构造）。
- **Q4** `EquipmentLayerRenderer` 与 `IArmorRendererBase` 职责边界？→ `EquipmentLayerRenderer`：texture lookup、layer 迭代、dye、overlay、trim、glint、submitModel（vanilla 数据驱动渲染）；`IArmorRendererBase`：自定义模型几何（`getHumanoidArmorModel`）+ 可选可见性。渲染由前者统一执行。
- **Q5** fabric 旧 `ArmorMaterial.Layer.texture` mixin → **删除**（target/类型/SPI 均已删，无等价 hook，无实现）。
- **Q6** 最小提交拆分？→ **C3-C1 → C3-C2 → C3-C3 → C3-C4**（§21），每阶段独立 commit 并停止。
