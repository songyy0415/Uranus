# MC 26.2 Rendering 迁移表

> 结论全部来自本地 `D:\aiminecraftdev\minecraft26.2` sources 直接核对（非按其他版本教程猜测）。P2.3-R（分析阶段）产出。
> 本阶段**不进行大规模代码修改**；本文档是后续 P2.3（Rendering 实际迁移）的实施地图。
> 迁移类型：**A**=mechanical replacement，**B**=signature adaptation，**C**=architectural rewrite，**D**=may become unnecessary。

---

## 1. 1.21.1 pipeline（旧架构）

```
Entity
  ↓ render(entity, yaw, partialTicks, poseStack, bufferSource, light)
EntityRenderer<T extends Entity>
  ↓ getTextureLocation(entity) → RenderType
model.renderToBuffer / renderLayers → vertexConsumer
  ↓
MultiBufferSource.BufferSource.getBuffer(RenderType)
  ↓
VertexConsumer.vertex(x,y,z).uv(u,v).color(r,g,b,a).overlay(o).light(l).normal(nx,ny,nz).endVertex()
```

关键特征：
- 渲染时**直接访问服务器实体**：`entity.getX()/getY()/getZ()`、`entity.getPartialTick()`、`entity.tickCount`、`entity.level()`。
- 渲染器持有一个 buffer 源，渲染是**立即写入顶点**的。
- `RenderLayer` 抽象方法：`render(PoseStack, MultiBufferSource, int light, T entity, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch)`。
- 模型 API：`EntityModel<T extends Entity>`，`renderToBuffer(PoseStack, VertexConsumer, int, int, int)`（可 override），`setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch)`。
- Armor：`HumanoidArmorLayer.renderArmorPiece`（protected）→ `ArmorItem.getMaterial().value().layers()` → `ArmorMaterial.Layer.texture(innerModel)` → 纹理 `ResourceLocation` → `RenderType.armorCutoutNoCull(texture)`。
- Item 特殊渲染：`BlockEntityWithoutLevelRenderer.renderByItem(ItemStack, ItemDisplayContext, PoseStack, MultiBufferSource, int light, int overlay)`。
- `RenderType` 是巨型工厂类：`entityCutout/entityTranslucent/entitySolid/armorCutoutNoCull(...)` 都在其上。

---

## 2. 26.2 pipeline（新架构）

```
Phase 1 — 提取（每帧一次，可访问服务器实体）
LevelExtractor.extractEntities()
  → DeltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity))   // partial tick
  → EntityRenderDispatcher.extractEntity(entity, partialTicks)
    → renderer.createRenderState(entity, partialTicks)        // final
      = createRenderState()                                    // abstract，new 一个 state
      + extractRenderState(entity, state, partialTicks)        // 填充 state（唯一允许访问 Entity 的地方）
      + finalizeRenderState(entity, state)                     // protected
    → state 存入 output.entityRenderStates

Phase 2 — 提交（渲染期，只读 state，禁止访问 Entity）
LevelRenderer.renderLevel() [line 661]
  → EntityRenderDispatcher.submit(state, cameraRenderState, state.x-camX, state.y-camY, state.z-camZ, poseStack, output)
    → renderer.getRenderOffset(state)   // 默认 state.passengerOffset
    → renderer.submit(state, poseStack, submitNodeCollector, camera)
      → submitNodeCollector.submitModel(model, state, poseStack, renderType, light, overlay, tintedColor, sprite, outlineColor, crumblingOverlay)
      → 各 RenderLayer.submit(poseStack, submitNodeCollector, light, state, yRot, xRot)
    → dispatcher 另提交火焰/shadow
```

**关键结论**：
- **26.2 中没有 `render()` 方法**（`grep 'void render('` 在 renderer/entity 下无结果）。`extractRenderState + submit` 取代它。
- `EntityRenderer<T extends Entity, S extends EntityRenderState>` 三段式：
  - `public abstract S createRenderState();`
  - `public final S createRenderState(final T entity, final float partialTicks)`
  - `public void extractRenderState(final T entity, final S state, final float partialTicks)`
  - `protected void finalizeRenderState(final T entity, final S state)`
  - `public void submit(final S state, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final CameraRenderState camera)`
- **服务器数据只在 extractRenderState 中读取**；model/layer 的 submit 只能读 state。这是本项目最大的架构约束。
- 自定义渲染器注册：`EntityRendererProvider<T>`（`@FunctionalInterface`，`EntityRenderer<T,?> create(Context)`）→ `EntityRenderers.register(EntityType, provider)`（静态块）→ 资源重载时 `EntityRenderers.createEntityRenderers(context)` 构建 `Map<EntityType<?>, EntityRenderer<?,?>>`。
- partial tick **没有** `Entity.getPartialTick()`/`getTimer()`：由 `LevelExtractor` 计算，作为 float 参数贯穿提取阶段；渲染期改用 state 内预提取字段。

---

## 3. EntityRenderState

### 3.1 位置/数据提取表

| 旧 Rendering 数据 | 26.2 RenderState 字段 | 数据来源 | 是否需要自定义 RenderState |
|---|---|---|---|
| `entity.getX()/getY()/getZ()` | `state.x/y/z`（`public double`） | `Mth.lerp(partialTicks, entity.xOld, entity.getX())`（EntityRenderer.extractRenderState :163-165） | 不需要（基类已有） |
| `entity.tickCount` | `state.ageInTicks`（`public float`） | `entity.tickCount + partialTicks`（:167） | 不需要 |
| `entity.getPartialTick()` | 无字段（提取期参数） | `DeltaTracker.getGameTimeDeltaPartialTick` | 渲染期不可用 |
| `entity.yBodyRot/yBodyRotO` | `state.bodyRot` | LivingEntityRenderer | 不需要 |
| `yHeadRot` | **合并进 `state.yRot`**（相对 bodyRot） | `Mth.wrapDegrees(headRot - state.bodyRot)`（:244-246） | 不需要 |
| `entity.xRot` | `state.xRot` | LivingEntityRenderer | 不需要 |
| walkAnimation | `state.walkAnimationPos / walkAnimationSpeed` | `entity.walkAnimation.position/speed(partialTicks)`（:255-256） | 不需要 |
| `deathTime` | `state.deathTime` | `entity.deathTime > 0 ? deathTime + partialTicks : 0`（:297） | 不需要 |
| 装备 ItemStack | `state.head/chest/legs/feetEquipment` | LivingEntityRenderer（HumanoidRenderState） | 需要（Humanoid 系） |
| 左手/右手物品 | `state.leftHandItemStack / rightHandItemStack` | ArmedEntityRenderState | 需要（Armed 系） |
| pose/baby/scale | `state.pose / isBaby / scale / ageScale` | LivingEntityRenderer | 需要（Living 系） |

### 3.2 26.2 state 类层次（`net.minecraft.client.renderer.entity.state`）

```
EntityRenderState            x, y, z, ageInTicks, entityType, isInvisible, lightCoords(默认15728880),
                             outlineColor, passengerOffset, nameTag, leashStates, shadowRadius, shadowPieces, ...
  └─ LivingEntityRenderState  bodyRot, yRot, xRot, deathTime, walkAnimationPos/Speed, isBaby, pose, scale,
                             ageScale, isUpsideDown, isInWater, isAutoSpinAttack, hasRedOverlay, bedOrientation,
                             headItem(ItemStackRenderState), wornHeadType, wornHeadProfile, ...
       └─ ArmedEntityRenderState  mainArm, leftArmPose/rightArmPose, leftHandItemStack/rightHandItemStack,
                             attackTime, swingAnimationType(SwingAnimationType.WHACK), ...
            └─ HumanoidRenderState  swimAmount, isCrouching, isFallFlying, elytraRotX/Y/Z,
                             headEquipment/chestEquipment/legsEquipment/feetEquipment(ItemStack), ...
```

**重要**：`PositionRenderState / OriginOffsetRenderState / RotationRenderState` **不存在**于 26.2（全树 grep 无结果）。位置就是 `EntityRenderState.x/y/z` 普通 double。

### 3.3 对本项目的意义

项目自定义实体渲染器（`EntityWithMarkerTextureProvider` 等）若在渲染期读 `entity.getX()` 等，必须改为读取自定义 RenderState（在 `extractRenderState` 中填充）。这属于 **C（architectural rewrite）**，本项目自有模型渲染路径的核心约束。

---

## 4. SubmitNodeCollector

### 4.1 职责（非简单类型替换！）

`SubmitNodeCollector`（`net.minecraft.client.renderer`）**不是** `MultiBufferSource` 的改名：
- 旧 `getBuffer(RenderType)` 立即可写；新架构**延迟渲染**——submit 只记录节点，真正画在帧 draw 阶段。
- 旧 `MultiBufferSource` 直接返回 `VertexConsumer`；新 `submitCustomGeometry(poseStack, renderType, callback)` 传一个回调，`VertexConsumer` 到 draw 时才由 `StagedVertexBuffer` 提供。

```java
public interface SubmitNodeCollector extends OrderedSubmitNodeCollector {
    OrderedSubmitNodeCollector order(int order);
    interface CustomGeometryRenderer { void render(PoseStack.Pose pose, VertexConsumer buffer); }
}
```

- `OrderedSubmitNodeCollector`：无序接口，声明全部 `submit*`（submitModel、submitItem、submitCustomGeometry、submitShadow、submitNameTag、submitText、submitFlame、submitLeash、submitBlockModel、submitBreakingBlockModel、submitShapeOutline、submitQuadParticleGroup、submitGizmoPrimitives、...）。
- `SubmitNodeCollector.order(int)`：把不同 layer 放进独立的固定顺序桶（`SubmitNodeCollection`），避免交错。
- 实现 `SubmitNodeStorage`：`Int2ObjectAVLTreeMap<Integer, SubmitNodeCollection>`，`order(0)` 为默认桶。
- 节点类型：`ModelFeatureRenderer.Submit`、`CustomFeatureRenderer.Submit`、`ItemFeatureRenderer.Submit`、`ShadowFeatureRenderer.Submit`、`TextFeatureRenderer.Submit` 等，基接口 `SubmitNode`（`FeatureRendererType<? extends SubmitNode> featureType()`）。
- submit 时 `poseStack.last()` 快照成 `PoseStack.Pose`（Matrix4f 拷贝）。
- buffer 划分：`hasBlending()` → translucent 桶；`isOutline()`/outlineColor!=0 → outline 桶；`== RenderTypes.waterMask()` → waterMask。

### 4.2 模型提交核心

```java
// SubmitNodeCollection.submitModel（9 参完整版）
<S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
    int lightCoords, int overlayCoords, int tintedColor,
    @Nullable TextureAtlasSprite sprite, int outlineColor,
    ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay);
```

draw 时 `ModelFeatureRenderer.prepareModel(submit)`：
1. `poseStack.last().set(submit.pose())`
2. `buffer = getVertexBuilder(submit.renderType())`（经 `renderType.prepare()` → `stagedBuffer.appendDraw(format, topology, quadSorting)` → `getVertexBuilder(draw)`）
3. 可选 `SheetedDecalTextureGenerator` / `sprite.wrap(buffer)` 包装
4. `model.setupAnim(state)`
5. `model.renderToBuffer(poseStack, buffer, light, overlay, tintedColor)`

### 4.3 原始几何（旧 vertex/uv/color/normal/endVertex 的等价）

```java
submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
    buffer.addVertex(pose, x, y, z);       // 或 addVertex(x,y,z)
    buffer.setColor(r, g, b, a);
    buffer.setUv(u, v);
    buffer.setLight(lightCoords);          // → setUv2
    buffer.setOverlay(overlayCoords);      // → setUv1
    buffer.setNormal(pose, nx, ny, nz);
});
```

**`VertexConsumer` 改名对照**（`com.mojang.blaze3d.vertex` 仍存在）：

| 旧 (1.21.1) | 26.2 |
|---|---|
| `vertex(x,y,z)` | `addVertex(x,y,z)`（或 `addVertex(PoseStack.Pose, x,y,z)`） |
| `color(r,g,b,a)` | `setColor(r,g,b,a)` / `setColor(int)` |
| `uv(u,v)` | `setUv(u,v)` |
| `light(packed)` | `setLight(packed)`（→ `setUv2`） |
| `overlay(packed)` | `setOverlay(packed)`（→ `setUv1`） |
| `normal(x,y,z)` | `setNormal(x,y,z)` / `setNormal(Pose, x,y,z)` |
| `endVertex()` | **移除**（addVertex 即开始新顶点） |

---

## 5. Model API

### 5.1 类声明与泛型界

```java
public abstract class Model<S>                       // S 任意（Simple 用 Unit）
public abstract class EntityModel<T extends EntityRenderState> extends Model<T>   // ← 界是 RenderState，不是 Entity！
public class HumanoidModel<T extends HumanoidRenderState> extends EntityModel<T> implements ArmedModel<T>, HeadedModel
```

- `EntityModel` 构造器：`protected EntityModel(ModelPart root)` 和 `protected EntityModel(ModelPart root, Function<Identifier, RenderType> renderType)`。**没有无参构造器，没有 (Function) 构造器** → `BasicEntityModel` 的 `super(layerFunction)` 也会失败。
- `Model.renderToBuffer` 是 **final**：`public final void renderToBuffer(PoseStack, VertexConsumer, int light, int overlay, int color)`，内部 `root().render(...)`。**不再可 override**。
- `Model` 公开：`final ModelPart root()`、`final List<ModelPart> allParts()`、`final Function<Identifier, RenderType> renderType()`、`public void setupAnim(S state)`（默认 `resetPose()`）、`final void resetPose()`。

### 5.2 setupAnim / 动画

- `setupAnim(S state)` 单参数（模型侧）；旧 7 参 `setupAnim(entity, limbSwing, ...)` 和 `prepareMobModel` **移除**。
- 流程：renderer 的 `extractRenderState` 填 state → draw 时 `model.setupAnim(state)` 每帧一次。
- HumanoidModel.setupAnim 读取：`leftArmPose/rightArmPose/swimAmount/isFallFlying/xRot/yRot/walkAnimationPos/walkAnimationSpeed/isPassenger/mainArm/isUsingItem/useItemHand/isCrouching/ageInTicks/attackArm/attackTime/ageScale/swingAnimationType`。

### 5.3 copyFrom / copyPropertiesTo / setRotationAngle（全部移除）

| 旧 API | 26.2 等价 |
|---|---|
| `ModelPart.copyFrom(ModelPart)` | `target.loadPose(source.storePose())`（`storePose()`/`loadPose()` 存在） |
| `HumanoidModel.copyPropertiesTo(HumanoidModel)` | **移除**，被 render-state 提取模式取代（anim 状态用 `AnimationState.copyFrom(AnimationState)`） |
| `ModelPart.setRotationAngle(float,float,float)` | `ModelPart.setRotation(xRot, yRot, zRot)` |
| `ModelPart.allCubes` | **移除**（`cubes` private，无 public accessor；`isEmpty()`/`getRandomCube(RandomSource)`/`visit(PoseStack, Visitor)`） |
| `ModelPart.storePrevRotations()` | 从来不是现代 vanilla API；用 `storePose()/loadPose()` |

**保持有效**（ChainBuffer 依赖，编译未 flag）：`ModelPart.x/y/z/xRot/yRot/zRot/xScale/yScale/zScale` 仍是 public float 字段；`visible/skipDraw` 仍在；`render(PoseStack, VertexConsumer, int, int)` + 5 参 color 版本在；`setPos/getChild/hasChild/translateAndRotate/getAllParts/createPartLookup` 在。`children` 是 private（无 public accessor）。

### 5.4 构造流程

```
CubeListBuilder.create().texOffs(u,v).addBox(...)     // texOffs 仍在！
  → PartDefinition root = mesh.getRoot()
  → root.addOrReplaceChild(name, cubeList, PartPose.offsetAndRotation(...))
  → LayerDefinition.create(mesh, texSizeX, texSizeY)
  → layerDefinition.bakeRoot() → ModelPart
```

- `PartPose` 是 9 分量 record：`(x, y, z, xRot, yRot, zRot, xScale, yScale, zScale)`；`ZERO/offset/rotation/offsetAndRotation/translated/withScale/scaled`。**没有 `ModelPart$Builder`**。
- `CubeListBuilder`：`texOffs(int,int)` **仍在**，`mirror()`，多种 `addBox` 重载（含 `CubeDeformation`、`Set<Direction> visibleSides`、`String id`）。
- `LayerDefinition.create(MeshDefinition, int, int)`、`bakeRoot()`、`apply(MeshTransformer)`。
- `PartDefinition.addOrReplaceChild(name, cubes, partPose)`、`bake(int texScaleX, int texScaleY)`、`getChild`、`retainPartsAndChildren/retainExactParts/clearRecursively/transformed`。

### 5.5 运行时查询

- `EntityModelSet.bakeLayer(ModelLayerLocation)`（抛 `IllegalArgumentException` if missing）、`EMPTY`、`vanilla()`。
- `ModelLayerLocation(Identifier model, String layer)` record。
- `ModelLayers`：静态常量 + `getKnownLocations()`；`LayerDefinitions.createRoots()` 构建 vanilla map，**缺定义会抛 `IllegalStateException`**。
- **mod 没有 vanilla hook 注册额外 layer**（map 在 createRoots() 后关闭）→ 需 Fabric API 注册或 mixin 注入。

---

## 6. HumanoidModel

```java
public class HumanoidModel<T extends HumanoidRenderState> extends EntityModel<T> implements ArmedModel<T>, HeadedModel {
    public void setupAnim(final T state);                                  // 单参
    public void translateToHand(final HumanoidRenderState state, final HumanoidArm arm, final PoseStack poseStack);
    public ModelPart getArm(final HumanoidArm arm);
    @Override public ModelPart getHead();
    public static MeshDefinition createMesh(final CubeDeformation g, final float yOffset);
}
```

- **泛型界改为 `HumanoidRenderState`** → 项目 `HumanoidModel<LivingEntity>` 等用法全部出界（错误 "type argument not within bounds of type-variable T"）。
- 项目 `ArmorModelBase extends HumanoidModel<LivingEntity>` 必须改为 `HumanoidModel<HumanoidRenderState>`，且 `setupAnim` 从 `(entity, limbSwing, ...)` 改为 `(state)`。

---

## 7. Armor / Equipment Asset

### 7.1 旧 → 新结构

```
1.21.1                              26.2
HumanoidArmorLayer                  HumanoidArmorLayer<S extends HumanoidRenderState, M extends HumanoidModel<S>, A extends HumanoidModel<S>>
  ↓ renderArmorPiece(protected)       ↓ submit() → renderArmorPiece(private)   ← 变 private！
  ↓ ArmorItem.getMaterial()           ↓ state.chestEquipment/...  (ItemStack)
  ↓ .value().layers()                 ↓ DataComponents.EQUIPPABLE.assetId() (Optional<ResourceKey<EquipmentAsset>>)
  ↓ ArmorMaterial.Layer.texture(b)    ↓ ArmorModelSet.get(slot)  (几何)
  ↓ 纹理 ResourceLocation             ↓ LayerType: HUMANOID / HUMANOID_LEGGINGS(腿) / HUMANOID_BABY(婴儿)
                                        ↓ EquipmentLayerRenderer.renderLayers(layerType, assetId, model, state, stack, pose, collector, light, outline)
                                          → asset.layers[layerType] → layer.getTextureLocation(type)
                                          → RenderTypes.armorCutoutNoCull(texture)  （每 layer 一次 submitModel）
                                          → 额外: DyedItemColor dye + armor_trims atlas trim + armorEntityGlint
```

### 7.2 核心类

- `ArmorMaterial`（record，`net.minecraft.world.item.equipment`）最后字段：`ResourceKey<EquipmentAsset> assetId`。
- `Item.Properties.humanoidArmor(material, type)`：`component(DataComponents.EQUIPPABLE, Equippable.builder(type.getSlot()).setEquipSound(...).setAsset(material.assetId()).build())`。
- `EquipmentAsset`：**空 marker class**（无字段）。`EquipmentAssets.ROOT_ID = ResourceKey.createRegistryKey("equipment_asset")`，常量 LEATHER/IRON/...；`createId(name)`。**没有真实 `Registry<EquipmentAsset>` 实例**——纯客户端 JSON 资源。
- `Equippable` record：`slot, equipSound, Optional<ResourceKey<EquipmentAsset>> assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound`。
- `EquipmentAssetManager extends SimpleJsonResourceReloadListener<EquipmentClientInfo>`：从 `assets/<ns>/equipment/*.json` 加载（`FileToIdConverter.json("equipment")`）；`get(key)` 返回 `EquipmentClientInfo`。
- `EquipmentClientInfo(Map<LayerType, List<Layer>>)`；`Layer(Identifier textureId, Optional<Dyeable> dyeable, boolean usePlayerTexture)`。
- `Layer.getTextureLocation(type)` = `textureId.withPath("textures/entity/equipment/" + type.getSerializedName() + "/" + path + ".png")`。
- `LayerType`（`StringRepresentable`）：`humanoid / humanoid_leggings / humanoid_baby / wings / wolf_body / horse_body / llama_body / pig_saddle / strider_saddle / camel_saddle / horse_saddle / donkey_saddle / mule_saddle / zombie_horse_saddle / skeleton_horse_saddle / happy_ghast_body / nautilus_saddle / nautilus_body`。
- `ArmorModelSet<A>` record：`(head, chest, legs, feet)`；`get(EquipmentSlot)`；`static <M extends HumanoidModel<?>> ArmorModelSet<M> bake(ArmorModelSet<ModelLayerLocation>, EntityModelSet, Function<ModelPart,M>)`。**几何来自模型 layer 定义，不是 asset**。
- `EquipmentLayerRenderer.renderLayers(...)`（`net.minecraft.client.renderer.entity.layers`）：
  1. `layers = equipmentAssets.get(assetId).getLayers(layerType)`
  2. `dyeColor = DyedItemColor.getOrDefault(itemStack, 0)`；`color = getColorForLayer(layer, dyeColor)`（dyeable → 用 dyeColor 或 colorWhenUndyed；color==0 跳过）
  3. `layerTexture = layer.usePlayerTexture() && playerTextureOverride != null ? override : layer.getTextureLocation(type)`
  4. `submitNodeCollector.order(...).submitModel(model, state, poseStack, RenderTypes.armorCutoutNoCull(layerTexture), light, NO_OVERLAY, color, null, outlineColor, null)`
  5. trim：`itemStack.get(DataComponents.TRIM)` → `armor_trims` atlas sprite → `Sheets.armorTrimsSheet(trim.pattern().value().decal())`
  6. foil：`RenderTypes.armorEntityGlint()`（`ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR`）

### 7.3 对本项目 Armor 体系的意义

- `IArmorRendererBase`/`ArmorModelBase` 的 `getArmorTexture`/`layers()` 链（`ArmorMaterial.Layer`）**已不存在** → 必须重设计为 26.2 的 equipment asset + `EquipmentLayerRenderer` 对接。
- `HumanoidArmorLayer.renderArmorPiece` 现在是 **private** → 项目 `ArmorFeatureRendererMixin`（@Inject HEAD）**无法再 target**；`@ModifyExpressionValue` 的 `ArmorMaterial$Layer.texture(Z)` target **也不存在**。
- **没有 native hook 替换 armor texture**：texture 完全由 equipment asset JSON + LayerType 决定。自定义 texture 的合规路径 = 给 item 自己的 `Equippable.assetId` + `assets/<ns>/equipment/<id>.json` + `assets/<ns>/textures/entity/equipment/<layertype>/<path>.png`。运行时逐物品实例替换的合法缝隙：mixin `EquipmentLayerRenderer.renderLayers`（public）或替换 `EquipmentClientInfo`。
- `ArmorModelSet` 需要项目 bake 自己的 armor 模型 layer 定义（`ModelLayers.<X>_ARMOR`）。

---

## 8. MultiBufferSource

**26.2 不存在 `MultiBufferSource`/`RenderBufferSource`/`BufferSource`**（全树 grep 无结果）。它被 SubmitNodeCollector 取代（见 §4）。

| 旧 | 26.2 |
|---|---|
| `bufferSource.getBuffer(RenderType)` | `submitCustomGeometry(poseStack, renderType, callback)` / `submitModel(...)` |
| `MultiBufferSource.immediate(...)` | 无直接等价 |
| `ItemRenderer.getArmorFoilBuffer(source, type, hasFoil)` | **移除** → `RenderTypes.armorCutoutNoCull(texture)` + `RenderTypes.armorEntityGlint()`，foil 由 `itemStack.hasFoil()` 控制 |
| `RenderBuffers.bufferSource()` | `RenderBuffers` 只持有 `SectionBufferBuilderPool` + `fixedBufferPack` + `StagedVertexBuffer`（无 bufferSource） |

项目 9 处 `MultiBufferSource`（ArmorModelBase ×N、IArmorRendererBase、IArmorRenderHelper、HideableLayer、DynamicItemRenderer、BuiltinModelItemRendererMixin、common ArmorFeatureRendererMixin）全部需要按 submit 模式重写。**不是简单类型替换**。

---

## 9. RenderType

### 9.1 类拆分

- `RenderType`（`net.minecraft.client.renderer.rendertype.RenderType`）幸存，但变成**薄数据 holder**：`RenderType(String name, RenderSetup state)`，`package-private static create(String, RenderSetup)`。公开 `pipeline()/format()/primitiveTopology()/prepare()/hasBlending()/isOutline()/outline()/canConsolidateConsecutiveGeometry()/affectsCrumbling()`。
- **工厂方法移到 `RenderTypes`**（同 package）。
- vertex format / shader / topology 移到 `RenderPipeline`（`com.mojang.blaze3d.pipeline.RenderPipeline`），`RenderPipelines` 持有约 100 个常量（ENTITY_CUTOUT、ARMOR_CUTOUT_NO_CULL 等），`withVertexBinding(0, DefaultVertexFormat.ENTITY)`。

### 9.2 RenderTypes 工厂（都是 Identifier 参数）

```java
public static RenderType entityCutout(Identifier texture)                       // = entityCutout(texture, true)
public static RenderType entityCutout(Identifier texture, boolean affectsOutline)
public static RenderType entityTranslucent(Identifier texture)                  // + boolean 重载
public static RenderType entitySolid(Identifier texture)
public static RenderType entityCutoutCull(Identifier texture)
public static RenderType entitySolidZOffsetForward(Identifier texture)
public static RenderType entityCutoutZOffset(Identifier texture)
public static RenderType armorCutoutNoCull(Identifier texture)
public static RenderType createArmorDecalCutoutNoCull(Identifier texture)
public static RenderType armorTranslucent(Identifier texture)
public static RenderType entityGlint()              // 无 texture 参数
public static RenderType armorEntityGlint()         // 无 texture 参数
public static RenderType outline(Identifier texture)
public static RenderType eyes(Identifier texture)
```

**旧→新映射（机械）**：`RenderType.entityCutout(x)` → `RenderTypes.entityCutout(x)`；`RenderType.armorCutoutNoCull(x)` → `RenderTypes.armorCutoutNoCull(x)`；`entityNoOutline(x)` 不存在 → `entityCutout(x, false)`。

### 9.3 RenderType 与 buffer 绑定

旧 `getBuffer(renderType)` → 新 draw 期 `RenderTypeFeatureRenderer.Group.getVertexBuilder(renderType)`：`renderType.prepare()` → `PreparedRenderType` → `stagedBuffer.appendDraw(renderType.format(), renderType.primitiveTopology(), quadSorting)` → `getVertexBuilder(draw)`。同一 renderType 连续提交合并到同一个 `StagedVertexBuffer.Draw`。

### 9.4 自定义 pipeline（一般不需要）

```java
RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
    .withVertexShader("core/entity").withFragmentShader(...)
    .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(...)
    .withLocation("pipeline/...").build();
RenderSetup.builder(pipeline).withTexture("Sampler0", texture).createRenderSetup()
RenderType.create(name, setup)
```
对 library mod，标准 `RenderTypes` 工厂通常已足够。

---

## 10. Item Rendering

### 10.1 架构

- `net.minecraft.client.renderer.ItemRenderer` **不存在**。职责拆分：`ItemInHandRenderer`（第一人称）、`ItemStackRenderState`（每帧预计算状态）、`ItemModelResolver`（从 stack + display context 建 state）、`GuiRenderer`（GUI）。
- item model **数据驱动**：`DataComponents.ITEM_MODEL`（Identifier）→ `ItemModels` 注册的 `ItemModel.Unbaked` codec 分派。
- `ItemModel`：`void update(ItemStackRenderState, ItemStack, ItemModelResolver, ItemDisplayContext, @Nullable ClientLevel, @Nullable ItemOwner, int seed)`；`Unbaked { MapCodec type(); ItemModel bake(BakingContext, Matrix4fc); }`。
- `ItemStackRenderState.submit(PoseStack, SubmitNodeCollector, int light, int overlay, int outlineColor)`；`LayerRenderState.setupSpecialModel(renderer, argument)` / `setItemTransform(transform)` / `setLocalTransform(matrix)`。
- `ModelRenderProperties(boolean usesBlockLight, Material.Baked particleMaterial, ItemTransforms transforms)`，`fromResolvedModel(baker, resolvedModel, textureSlots)`。
- `ClientItem(ItemModel.Unbaked model, ClientItem.Properties properties, RegistryContextSwapper registrySwapper)`。

### 10.2 ItemTransform / ItemTransforms 迁移（项目 TabulaModelHandler 依赖）

| 旧 (1.21.1) | 26.2 |
|---|---|
| `net.minecraft.client.renderer.block.model.ItemTransform` | `net.minecraft.client.resources.model.cuboid.ItemTransform`（record：rotation/translation/scale；`apply(boolean leftHandFix, PoseStack.Pose)`） |
| `net.minecraft.client.renderer.block.model.ItemTransforms` | `net.minecraft.client.resources.model.cuboid.ItemTransforms`（9 字段 record；`getTransform(ItemDisplayContext)`） |
| `ItemTransform.Deserializer` / `ItemTransforms.Deserializer` | **变成 `protected static` 嵌套类** → 跨包 mod **不能 `new`**（TabulaModelHandler.java:21 报 "package ItemTransform does not exist"） |

### 10.3 项目 DynamicItemRenderer 的意义

`DynamicItemRenderer`（`Map<Item, DynamicItemRenderer>` + `render(ItemStack, ItemDisplayContext, PoseStack, MultiBufferSource, int, int)`）的 mixin target `BlockEntityWithoutLevelRenderer.renderByItem` **不存在**。可作为兼容 shim 适配进 `SpecialModelRenderer`（见 §11），或重设计。

---

## 11. SpecialModelRenderer

```java
public interface SpecialModelRenderer<T> {                       // net.minecraft.client.renderer.special
    void submit(@Nullable T argument, PoseStack poseStack, SubmitNodeCollector collector,
                int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor);
    void getExtents(Consumer<Vector3fc> output);
    @Nullable T extractArgument(ItemStack stack);
    interface BakingContext { EntityModelSet entityModelSet(); SpriteGetter sprites(); PlayerSkinRenderCache playerSkinRenderCache(); }
    interface Unbaked<T> { @Nullable SpecialModelRenderer<T> bake(BakingContext context); MapCodec<? extends SpecialModelRenderer.Unbaked<T>> type(); }
}
public interface NoDataSpecialModelRenderer extends SpecialModelRenderer<Void> { ... }
```

- 注册：`SpecialModelRenderers` 持 private `ID_MAPPER`（`ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends Unbaked<?>>>`）+ `CODEC`。内置（Chest/Shulker/Skull/PlayerHead/Shield/Trident/Banner/DecoratedPot/Conduit/Bell/Book/EndCube/CopperGolemStatue SpecialRenderer）在 `bootstrap()` 中 `ID_MAPPER.put(...)`。**mod 需 mixin `SpecialModelRenderers.bootstrap()`/`ClientBootstrap.bootstrap()` 注入自定义类型**（无 datapack 注册）。
- 接线：item model JSON `{"type":"special","base":<block model>,"transformation":{...},"model":{"type":"your_mod:my_special",...}}` → `SpecialModelWrapper.Unbaked` → `bake()` 组合 `Transformation.compose` + bake special renderer。
- **这是 `BlockEntityWithoutLevelRenderer` 的正式替代**。项目 `BuiltinModelItemRendererMixin` 应重定向为注册 special model 而非 @Inject renderByItem。`ChestSpecialRenderer`（复用 ChestModel + LAYERS）是 "拿在手上的方块模型" 的范本。
- 项目 `DynamicItemRenderer` 可保留为 shim：`renderByItem` 语义 → `submit(argument=stack, ...)`；`MultiBufferSource` → `SubmitNodeCollector`。

---

## 12. Tabula

- `TabulaModelHandler` 是项目**自有** Tabula `.tbl` 导入器（pau101 血统），核心于模型加载管线，**必须保留**。
- 编译失败**纯粹是 vanilla**：`ItemTransform`/`ItemTransforms`（及其 `Deserializer` 嵌套类）移包 + Deserializer 变 protected（见 §10.2）。`TabulaModelContainer` 用自己的 Transform 类型，ItemTransform/ItemTransforms 的使用是**附带性**的（仅 Gson 注册）。
- 处理：从 Gson 注册中移除 `ItemTransform.Deserializer`/`ItemTransforms.Deserializer`（或用 26.2 cuboid 类的新构造方式），不破坏 Tabula 模型数据。
- `BasicEntityModel` / `BasicModelBase` 是项目模型框架基类，问题全在 vanilla API 表面（EntityModel 泛型界、final renderToBuffer、移除的 7 参 setupAnim/prepareMobModel、RenderType 移包）→ **C（架构重写）但项目模型几何数据不变**。
- `ModelAnimator` / `ChainBuffer`：唯一 vanilla 失败点是 `Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)`（`getTimer()` 移除）。`AdvancedModelBox.rotateAngleX/Y/Z`、`rotationPointX/Y/Z` 是项目自有字段，不受影响；ChainBuffer 用的 `ModelPart.xRot/yRot`、`LivingEntity.yBodyRot/yBodyRotO/xRotO` 仍有效。

---

## 13. Mixins

### 13.1 BuiltinModelItemRendererMixin（`common/.../mixin/BuiltinModelItemRendererMixin.java`）

| 项 | 值 |
|---|---|
| Mixin class | `BuiltinModelItemRendererMixin` |
| Old target | `net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer` |
| Old target method | `renderByItem(ItemStack, ItemDisplayContext, PoseStack, MultiBufferSource, int light, int overlay)` |
| Old descriptor | `(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V` |
| 26.2 target class | **不存在**（`BlockEntityWithoutLevelRenderer` 移除） |
| 26.2 target method | **无**（替换为 `SpecialModelRenderer` 体系） |
| 是否仍需要 Mixin | **结构需重设计**：从 @Inject renderByItem → 注册 `SpecialModelRenderer`/`SpecialModelRenderers.bootstrap()` mixin。功能（动态物品渲染）仍需要，但实现路径完全不同 |

### 13.2 ArmorFeatureRendererMixin（common，`mixin/ArmorFeatureRendererMixin.java`）

| 项 | 值 |
|---|---|
| Mixin class | `ArmorFeatureRendererMixin` |
| Old target | `net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer` |
| Old target method | `renderArmorPiece(PoseStack, MultiBufferSource, T, EquipmentSlot, int, A)` @Inject HEAD |
| Old descriptor | `(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V` |
| 26.2 target class | `HumanoidArmorLayer`（仍在） |
| 26.2 target method | `renderArmorPiece` 存在但为 **private**：`(PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int, S state)` |
| 26.2 descriptor | `(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V` |
| 是否仍需要 Mixin | **本阶段不修改（§11 要求）**；记录判定：@Inject HEAD 无法匹配（private + 参数变化），且 texture 选择已被 equipment asset 接管 → 该 mixin 职责（拦截 IArmorRendererBase 渲染）**大概率需重设计/移除**，旧功能由自定义 equipment asset + `EquipmentLayerRenderer`（或自建 RenderLayer）接管 |

### 13.3 ArmorFeatureRendererMixin（fabric，`fabric/.../fabric/mixin/ArmorFeatureRendererMixin.java`）

| 项 | 值 |
|---|---|
| Mixin class | `ArmorFeatureRendererMixin`（fabric） |
| Old target | `HumanoidArmorLayer.renderArmorPiece` @ModifyExpressionValue |
| Old target method | `ArmorMaterial$Layer.texture(Z)Lnet/minecraft/resources/ResourceLocation;` |
| Old descriptor | `(Z)Lnet/minecraft/resources/ResourceLocation;` |
| 26.2 target class | `HumanoidArmorLayer`（仍在，renderArmorPiece private） |
| 26.2 target method | **`ArmorMaterial$Layer.texture(Z)` 不存在**（Layer 移除） |
| 26.2 descriptor | — |
| 是否仍需要 Mixin | **记录判定：不可能 apply**。该文件走 deprecated `IArmorTextureProvider` 路径，已被 IArmorRendererBase 主路径取代。需先确认 26.2 `HumanoidArmorLayer.usesInnerModel(EquipmentSlot)`（现为 private，仅 LEGS 为 inner）后决定去留。**本阶段不修改** |

---

## 14. Texture system

### 14.1 来源对照

| 旧 texture 来源 | 26.2 texture 来源 |
|---|---|
| `entity.getTextureLocation()`（entity 参数） | `LivingEntityRenderer.getTextureLocation(final S state)`（abstract，state 参数） |
| `RenderType.entityCutout(texture)` | `RenderTypes.entityCutout(Identifier)` |
| armor：`ArmorMaterial.Layer.texture(innerModel)` | armor：`EquipmentClientInfo.Layer.getTextureLocation(LayerType)` = `textures/entity/equipment/<layertype>/<path>.png`（独立文件，非 sprite） |
| armor trim：`ArmorMaterial.Layer` + trims | armor trim：`armor_trims` atlas（`AtlasIds.ARMOR_TRIMS`，`Sheets.ARMOR_TRIMS_SHEET`，`PalettedPermutations` 于 `trims/entity/<layer>/<pattern>.png`） |
| 实体纹理：`textures/entity/...` | 不变（独立文件；`TextureManager.getTexture(Identifier)` 返回 `AbstractTexture`） |
| `TextureAtlas`（LOCATION_BLOCKS/ITEMS） | `TextureAtlas` 仍在（`textures/atlas/*.png`），另有新 atlas：shulker_boxes/banner_patterns/shield_patterns/chest/armor_trims/decorated_pot/gui/map_decorations/paintings/celestials |
| `TextureAtlasSprite.getU0/getU1/...` | 仍在；`wrap(VertexConsumer)` → `SpriteCoordinateExpander` |

### 14.2 关键注意

- 26.2 的 RenderType texture 是 **named binding**（`Map<String, TextureBinding>`，sampler 槽 `Sampler0/1/2`），不一定是 atlas。实体 texture 是独立文件。
- **armor equipment asset 不能按旧 `Identifier texture` 逻辑处理**：texture 路径由 asset JSON 的 LayerType 拼接决定（`textures/entity/equipment/<layertype>/<path>.png`），不是代码里直接给定的 ResourceLocation。

---

## 15. File-by-file migration plan（§18）

### 错误基线（`build-p23-before.log`，100 errors / 17 文件 / 88 唯一 location）

**Scope（13 个 Rendering 文件）**：

| File | Errors |
|---|---|
| `client/render/armor/ArmorModelBase.java` | 76 |
| `client/render/armor/IArmorRendererBase.java` | 26 |
| `client/model/util/HideableLayer.java` | 16 |
| `client/render/armor/IArmorRenderHelper.java` | 14 |
| `client/model/TabulaModelHandler.java` | 12 |
| `client/model/basic/BasicEntityModel.java` | 12 |
| `mixin/BuiltinModelItemRendererMixin.java` | 8 |
| `client/model/tools/BasicModelBase.java` | 6 |
| `client/model/tools/ChainBuffer.java` | 4 |
| `client/render/DynamicItemRenderer.java` | 4 |
| `mixin/ArmorFeatureRendererMixin.java` (common) | 4 |
| `client/render/armor/IArmorTextureProvider.java` | 4 |
| `client/model/ModelAnimator.java` | 2 |

**Excluded（P2.4 Pathfinding）**：`AbstractAdvancedPathNavigate`(2)、`AdvancedPathNavigate`(4)、`PathJobRandomPos`(4)、`EntityUtil`(2)。

### 15.1 `client/render/armor/ArmorModelBase.java`

```
File:                 ArmorModelBase.java
Current errors:       76
Old API:              HumanoidModel<LivingEntity>（超类泛型）、HumanoidModel.setupAnim(7 参)、
                      MultiBufferSource、RenderType.armorCutoutNoCull、ItemRenderer.getArmorFoilBuffer、
                      ArmorStand Rotations.getX/getY/getZ、ModelPart.copyFrom、VertexConsumer
26.2 API:             HumanoidModel<HumanoidRenderState>、setupAnim(state)、
                      RenderTypes.armorCutoutNoCull(texture)、RenderTypes.armorEntityGlint()、
                      ModelPart.loadPose(source.storePose())、submitModel/submitCustomGeometry
Migration type:       C（architectural rewrite：渲染管线从立即写入 vertex 改为 submit 回调）
Risk:                 高（project armor 渲染核心基类，A 层模型）
Dependencies:         IArmorRendererBase、IArmorRenderHelper、EquipmentLayerRenderer/equipment asset 体系
Recommended order:    6（依赖 IArmorRendererBase/IArmorRenderHelper 先定型）
```

### 15.2 `client/render/armor/IArmorRendererBase.java`

```
File:                 IArmorRendererBase.java
Current errors:       26
Old API:              MultiBufferSource、RenderType、ArmorItem、ArmorMaterial.Layer、getMaterial().value().layers()、
                      Layer.texture(boolean)、HumanoidModel<T> 泛型界、HumanoidModel.copyPropertiesTo、VertexConsumer
26.2 API:             HumanoidArmorLayer/EquipmentLayerRenderer（asset 驱动）、ArmorModelSet、HumanoidRenderState、
                      自定义 RenderLayer submit(PoseStack, SubmitNodeCollector, int, S, float, float)
Migration type:       C（project 自有 SPI：模型/材质控制权需要按 render-state + equipment asset 重设计）
Risk:                 高（整个 project armor 扩展体系的入口）
Dependencies:         ArmorModelBase、ArmorFeatureRendererMixin（common）
Recommended order:    5（先于 ArmorModelBase 定型接口）
```

### 15.3 `client/model/util/HideableLayer.java`

```
File:                 HideableLayer.java
Current errors:       16
Old API:              MultiBufferSource（import + render 参数）、RenderLayer<T extends Entity, M extends EntityModel<T>>、
                      RenderLayerParent<T,M>、render(...) override（26.2 移除此抽象方法）
26.2 API:             RenderLayer<S extends EntityRenderState, M extends EntityModel<? super S>>、
                      abstract submit(PoseStack, SubmitNodeCollector, int lightCoords, S state, float yRot, float xRot)
Migration type:       B（signature adaptation：render → submit，泛型界调整；装饰器逻辑保留）
Risk:                 中（纯装饰器，改签名即可）
Dependencies:         RenderLayer 新抽象方法、EntityModel 新泛型界
Recommended order:    2
```

### 15.4 `client/render/armor/IArmorRenderHelper.java`

```
File:                 IArmorRenderHelper.java
Current errors:       14
Old API:              MultiBufferSource、RenderType.armorCutoutNoCull、ItemRenderer.getArmorFoilBuffer、PlayerModel
26.2 API:             RenderTypes.armorCutoutNoCull/armorEntityGlint、submitNodeCollector.submitModel、
                      PlayerModel<T extends PlayerRenderState>（或改用 HumanoidModel 系）
Migration type:       B（signature adaptation：helper 参数从 buffer 源改为 submit collector + model/state）
Risk:                 中
Dependencies:         ArmorModelBase、IArmorRendererBase
Recommended order:    7
```

### 15.5 `client/model/TabulaModelHandler.java`

```
File:                 TabulaModelHandler.java
Current errors:       12
Old API:              ItemTransform/ItemTransforms（net.minecraft.client.renderer.block.model）+ 各自 Deserializer
26.2 API:             net.minecraft.client.resources.model.cuboid.ItemTransform/ItemTransforms（record；Deserializer 变 protected）
Migration type:       A（mechanical：移除 Gson 对 ItemTransform/ItemTransforms 的 adapter 注册 —— 仅附带性使用）
Risk:                 低（不动 Tabula 数据/Transform 类型）
Dependencies:         无（独立）
Recommended order:    1（快速清零 12 错误）
```

### 15.6 `client/model/basic/BasicEntityModel.java`

```
File:                 BasicEntityModel.java
Current errors:       12
Old API:              EntityModel<T extends Entity> 泛型界、net.minecraft.client.renderer.RenderType import + ctor param、
                      renderToBuffer override（26.2 final）、setupAnim(7 参) @Override、prepareMobModel @Override
26.2 API:             EntityModel<T extends EntityRenderState>、RenderTypes（移包）、final renderToBuffer（不 override）、
                      setupAnim(S state) 单参、无 prepareMobModel
Migration type:       C（架构：泛型界 + 模型渲染入口重设计；但项目 BasicModelPart.render(...) 几何不受影响）
Risk:                 高（project 模型框架根；所有 entity model 派生自此）
Dependencies:         Model/EntityModel 新 API、BasicModelPart/AdvancedModelBox 链
Recommended order:    3（先于其他 entity model）
```

### 15.7 `mixin/BuiltinModelItemRendererMixin.java`

```
File:                 BuiltinModelItemRendererMixin.java
Current errors:       8
Old API:              BlockEntityWithoutLevelRenderer（class + @Mixin target）、renderByItem、MultiBufferSource
26.2 API:             SpecialModelRenderer / SpecialModelRenderers.bootstrap()（注册 custom special type）
Migration type:       C（从 @Inject renderByItem 重定向为 special-model 注册/兼容 shim）
Risk:                 中高（涉及 26.2 special model 注册缝隙 —— 需 mixin SpecialModelRenderers.bootstrap()）
Dependencies:         DynamicItemRenderer、SpecialModelRenderer/BakingContext
Recommended order:    8
```

### 15.8 `client/model/tools/BasicModelBase.java`

```
File:                 BasicModelBase.java
Current errors:       6
Old API:              EntityModel<T extends Entity> 泛型界、net.minecraft.client.renderer.RenderType import + ctor param
26.2 API:             EntityModel<T extends EntityRenderState>、RenderTypes
Migration type:       B（signature adaptation：泛型界调整；setupAnim/prepareMobModel 为声明非 override，修 EntityModel 后需跟进）
Risk:                 中（legacy Forge 兼容 shim，改动需同步项目旧式 model 代码）
Dependencies:         EntityModel 新泛型界、BasicEntityModel
Recommended order:    4
```

### 15.9 `client/model/tools/ChainBuffer.java`

```
File:                 ChainBuffer.java
Current errors:       4
Old API:              Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)
26.2 API:             partial tick 提取期参数 / state 预提取（或 DeltaTracker 注入）
Migration type:       B（signature adaptation：partial-tick 获取路径；ModelPart.xRot/yRot 与 LivingEntity 字段保持不变）
Risk:                 低（已验证 xRot/yRot、yBodyRot/yBodyRotO/xRotO 仍有效）
Dependencies:         无
Recommended order:    1
```

### 15.10 `client/render/DynamicItemRenderer.java`

```
File:                 DynamicItemRenderer.java
Current errors:       4
Old API:              MultiBufferSource（import + 方法签名）
26.2 API:             SubmitNodeCollector（signature：render(ItemStack, ItemDisplayContext, PoseStack, SubmitNodeCollector, int, int)）
Migration type:       B（signature adaptation：先让编译通过）+ 后续 C（适配 SpecialModelRenderer 才算完整替代）
Risk:                 中
Dependencies:         BuiltinModelItemRendererMixin
Recommended order:    9（与 BuiltinModelItemRendererMixin 一起）
```

### 15.11 `mixin/ArmorFeatureRendererMixin.java`（common）

```
File:                 ArmorFeatureRendererMixin.java（common）
Current errors:       4
Old API:              MultiBufferSource（import + handler 参数）、HumanoidArmorLayer.renderArmorPiece（旧签名）
26.2 API:             HumanoidArmorLayer.renderArmorPiece(private, (PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int, S state))
Migration type:       D（may become unnecessary）+ 记录判定：@Inject HEAD 无法匹配；旧功能由 equipment asset + 自建 RenderLayer 接管
Risk:                 高（§11：本阶段不修改，仅记录）
Dependencies:         IArmorRendererBase、EquipmentLayerRenderer
Recommended order:    10（P2.3 实际迁移时最后处理）
```

### 15.12 `client/render/armor/IArmorTextureProvider.java`

```
File:                 IArmorTextureProvider.java
Current errors:       4
Old API:              ArmorMaterial、ArmorMaterial.Layer（方法签名 getArmorTexture(ItemStack, Entity, EquipmentSlot, ArmorMaterial.Layer, boolean)）
26.2 API:             EquipmentClientInfo.Layer / ResourceKey<EquipmentAsset>（替换 Layer 参数）
Migration type:       D（已 @Deprecated(forRemoval=true)，被 IArmorRendererBase 路径取代）+ B（若保留则换签名）
Risk:                 低
Dependencies:         IArmorRendererBase
Recommended order:    11
```

### 15.13 `client/model/ModelAnimator.java`

```
File:                 ModelAnimator.java
Current errors:       2
Old API:              Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)
26.2 API:             partial tick 参数 / state 预提取（同 ChainBuffer）
Migration type:       B（signature adaptation：getTimer() 路径；AdvancedModelBox 字段为项目自有，不受影响）
Risk:                 低
Dependencies:         无
Recommended order:    1
```

### 实施顺序汇总

```
① 机械/低风险（A/B，独立）:    TabulaModelHandler(A), ChainBuffer(B), ModelAnimator(B)
② 模型框架根（C，前置）:        BasicEntityModel, BasicModelBase, HideableLayer(B)
③ Armor SPI（C，互相依赖）:     IArmorRendererBase → ArmorModelBase → IArmorRenderHelper → ArmorFeatureRendererMixin(common, D)
④ Item（C）:                   DynamicItemRenderer + BuiltinModelItemRendererMixin
⑤ 收尾（D，记录判定）:          IArmorTextureProvider, ArmorFeatureRendererMixin(fabric)
```
