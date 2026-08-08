# Uranus — Fabric 1.21.1 → Minecraft 26.2 迁移分析报告

**阶段：** Phase 1 (P0) — 分析阶段
**状态：** 分析完成，等待批准后进入 P1（Build 系统迁移）

---

## 0. 结论摘要

- 迁移前编译基线：**BUILD SUCCESSFUL，0 编译错误**（JDK 21 + Gradle 8.10 + Loom 1.7.435 + MC 1.21.1 + Fabric API 0.107.0）
- 目标工具链已确认可用：MC **26.2** / Loader **0.19.3** / Loom **1.17+** / Gradle **9.5.1** / Java **25** / Fabric API **0.156.0+26.2** / Architectury **21.0.7**
- 项目结构：`common` 139 个 Java 文件 + `fabric` 3 个 Java 文件；Architectury 架构保持不变（NeoForge 已按先前要求移除，不在本次范围）
- 14 个 Mixin 逐一定位完毕：9 个服务端、4 个 common 客户端、1 个 fabric 客户端
- 风险最高区域：**渲染系统重写**（MultiBufferSource / BlockEntityWithoutLevelRenderer 移除）、**Mixin 目标签名变化**、**系统化重命名**（ResourceLocation→Identifier）
- 迁移不需要删除任何功能。所有移除的 API 在 26.2 中都有对应替代机制

---

## 1. 基线（P0 完成标准）

| 项 | 迁移前 | 目标 |
|---|---|---|
| Minecraft | 1.21.1 | 26.2 |
| Fabric Loader | 0.16.7 | 0.19.3 |
| Fabric Loom | 1.7.435（dev.architectury.loom） | 1.17-SNAPSHOT / 1.17+ |
| Architectury Plugin | 3.4-SNAPSHOT | 3.5-SNAPSHOT（按 26.2 项目） |
| Gradle Wrapper | 8.10 | 9.5.1 |
| Java | 21（zulu-21，`org.gradle.java.home` 固定） | 25（移除版本固定） |
| Fabric API | 0.107.0+1.21.1 | 0.156.0+26.2 |
| Architectury API | 13.0.8 | 21.0.7（architectury-fabric） |

**验证命令：** `./gradlew build` → BUILD SUCCESSFUL，0 错误（已执行，见迁移前基线提交 `dafda4e`）。

---

## 2. 项目结构

```
Uranus/
├── settings.gradle          # include common, fabric（neoforge 已移除）
├── build.gradle             # root：architectury.loom 1.7-SNAPSHOT / plugin 3.4-SNAPSHOT / Java 21
├── gradle.properties        # 版本变量 + org.gradle.java.home 固定到 zulu-21
├── common/                  # 139 Java 文件
│   ├── animation/           # 动画引擎（Animation, AnimationHandler, ...）
│   ├── client/              # model/, render/（AdvancedModelBox, DynamicItemRenderer, armor/）
│   ├── data/                # EntityPropertyDelegate
│   ├── event/               # 自定义事件系统（Event, EntityEvents, ...）
│   ├── mixin/               # 13 个 mixin
│   ├── network/             # AnimationPayload, PacketBufferUtils
│   ├── object/              # BlockUtil, EntityUtil, RegistryHelper, entity/collision/, entity/pathfinding/raycoms/
│   ├── util/                # ClientUtils, Timeout, 函数式接口
│   └── world/               # WorldChunkUtil
└── fabric/                  # 3 Java 文件
    ├── fabric/              # UranusFabric, client/UranusFabricClient
    ├── fabric/mixin/        # ArmorFeatureRendererMixin（fabric 专用）
    └── resources/           # fabric.mod.json, uranus-fabric.mixins.json, uranus.accesswidener, logo.png
```

### 资源文件
- `common/src/main/resources/uranus.accesswidener` — **Mojmap 正确**，需 26.2 路径更新
- `common/src/main/resources/uranus.mixins.json` — 9 服务端 + 4 客户端
- `fabric/src/main/resources/uranus.accesswidener` — **过时 Yarn 重复文件**，删除（`fabric.mod.json` 只引用一个 accesswidener，实际使用的是 common 的）
- `fabric/src/main/resources/fabric.mod.json` — `"minecraft": "1.21.x"` → `"26.x"`
- `fabric/src/main/resources/uranus-fabric.mixins.json` — 1 客户端 mixin

---

## 3. 依赖可用性（已确认）

| 依赖 | 版本 | 验证来源 |
|---|---|---|
| `net.minecraft:minecraft:26.2` | 26.2 | `D:\aiminecraftdev\minecraft26.2` 完整源码存在 |
| `net.fabricmc:fabric-loader:0.19.3` | 0.19.3 | `D:\aiminecraftdev\fabric-loader-0.19.3` 源码存在 |
| `net.fabricmc.fabric-api:fabric-api:0.156.0+26.2` | 0.156.0 | `D:\aiminecraftdev\fabric-api-0.156.0-26.2` 源码存在 |
| `dev.architectury:architectury-fabric:21.0.7` | 21.0.7 | Maven 元数据 + `architectury-api26.2` 项目 build.gradle 确认 |
| `dev.architectury.loom` | 1.17-SNAPSHOT | `architectury-api26.2` 项目实际使用 |
| `architectury-plugin` | 3.5-SNAPSHOT | `architectury-api26.2` 项目实际使用 |
| Gradle | 9.5.1 | `architectury-api26.2/gradle/wrapper/gradle-wrapper.properties` |

> 注：fabric-api 0.156.0+26.2 为 AGENTS.md 指定的权威参考版本；architectury-api26.2 自身使用 fabric-api 0.154.2+26.2，但 AGENTS.md 指定 0.156.0，作为本项目首选。
>
> ⚠️ **关键警告（deps 审计确认）**：fabric-api 最新版 `0.156.3+26.3` 目标 MC **26.3**，不可用于本项目（target 26.2）。版本矩阵 `0.145.4 … 0.156.0` 均为 +26.2，取 `0.156.0+26.2`。
>
> ⚠️ **Loom 插件 marker 路径**：`dev.architectury.loom` 的 marker 实际位于 `maven.architectury.dev/dev/architectury/loom/dev.architectury.loom.gradle.plugin/`（`dev/architectury/loom/` 裸路径会 404，早期 WebFetch 已遇到）。marker 解析到实现 `dev.architectury:architectury-loom:1.17.x`。architectury 26.2 项目实际用 `dev.architectury.loom-no-remap` 1.17-SNAPSHOT。

---

## 4. 26 类迁移检查表（文件分类）

| # | 类别 | 文件数 | 风险 |
|---|---|---|---|
| 1 | Networking（NetworkManager, AnimationPayload） | 8 | LOW-MED |
| 2 | Common MC API（Entity, Goal, Level） | 20 | LOW |
| 3 | Rendering — 模型层级（BasicEntityModel, BasicModelPart, AdvancedEntityModel, TabulaModel, ...） | 28 | **HIGH** |
| 4 | Rendering — item 渲染（DynamicItemRenderer, BuiltinModelItemRendererMixin） | 2 | **HIGH** |
| 5 | Rendering — armor 渲染（ArmorFeatureRendererMixin, IArmorRendererBase, ...） | 6 | **HIGH** |
| 6 | Rendering — texture（ArrayLayeredTexture, SimpleTexture, ...） | 4 | MED |
| 7 | Pathfinding（AbstractPathJob, AdvancedPathNavigate, ChunkCache, MNode, ...） | 20 | MED-HIGH |
| 8 | Collision（CustomCollisionsBlockCollisions, CustomCollisionsNodeProcessor, ...） | 4 | MED |
| 9 | Mixin（14 个） | 14 | **HIGH** |
| 10 | Accesswidener | 2 | MED |
| 11 | Data / Object / Util helpers | 20 | LOW |
| 12 | 纯 POJO（函数式接口 Consumer/Function/Predicate/Tuple） | 33 | LOW |
| 13 | Build 配置（build.gradle, gradle.properties, settings.gradle, wrapper） | 4 | MED |
| 14 | 资源（fabric.mod.json, mixins.json, aw, logo） | 6 | MED |

> 分类数据由 8 代理并行分析工作流（wf_1cdf776c-c9c）产出（已全部完成），逐文件归类 + 四区 API 审计 + 依赖验证，见 §7bis。

---

## 5. Mixin 审计（14 个，全部对照 26.2 源码验证）

### 5.1 服务端（9 个）

| Mixin | Target (26.2) | 状态 | 所需修改 |
|---|---|---|---|
| `EntityMixin` | `net.minecraft.world.entity.Entity` | **需修改** | `spawnAtLocation` 增加 `ServerLevel` 首参：`spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;` |
| `EntityTrackerEntryMixin` | `net.minecraft.server.level.ServerEntity` | ✅ OK | `sendPairingData` 不变 |
| `LevelPropertiesMixin` | `net.minecraft.world.level.storage.PrimaryLevelData` | ✅ OK | `worldGenSettingsLifecycle()` 不变 |
| `LivingEntityMixin` | `net.minecraft.world.entity.LivingEntity` | **需修改** | `causeFallDamage` 首参 `float`→`double`；`swing`/`getItemInHand` 不变 |
| `MinecraftServerMixin` | `net.minecraft.server.MinecraftServer` | ✅ OK | `<init>`/`stopServer`（protected）不变 |
| `PlayerEntityMixin` | `net.minecraft.world.entity.player.Player` | **需修改** | `actuallyHurt` 增加 `ServerLevel` 首参；`Math.max(FF)F` 注入点仍有效，handler 需加 ServerLevel 参数 |
| `PlayerInventoryMixin` | `net.minecraft.world.entity.player.Inventory` | ✅ OK | `tick()` 不变 |
| `PlayerManagerMixin` | `net.minecraft.server.players.PlayerList` | ✅ OK | `respawn(ServerPlayer,boolean,Entity.RemovalReason)` 不变（包移动 server.level→server.players 仅影响 import） |
| `ServerEntityManagerMixin` | `net.minecraft.world.level.entity.PersistentEntitySectionManager` | ✅ OK | `addEntity`（private, erased descriptor 不变） |

### 5.2 客户端（5 个）

| Mixin | Target (26.2) | 状态 | 所需修改 |
|---|---|---|---|
| `ArmorFeatureRendererMixin` (common) | `net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer` | **彻底重写** | `renderArmorPiece` 签名完全改变：`(PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int, HumanoidRenderState)`；类泛型基类变为 `RenderLayer<S extends EntityRenderState, ...>`；渲染委托 `EquipmentLayerRenderer.renderLayers` |
| `BuiltinModelItemRendererMixin` (common) | `net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer` | **REMOVED** | 类与方法在 26.2 全部移除。替代：`SpecialModelRenderer` + `SpecialModelWrapper`（JSON `special` 模型类型） |
| `ClientWorldMixin` | `net.minecraft.client.multiplayer.ClientLevel` | ✅ OK | `addEntity(Entity)` 不变 |
| `ModelLoaderMixin` | `net.minecraft.client.resources.model.ModelBakery` | ✅ OK（需注意） | `<init>` 仍可绑定（单一构造函数）；但 26.2 烘焙异步化（`bakeModels`），`<init>` RETURN 时 reloadModel 语义变弱，需评估是否改挂到 `bakeModels` |
| `ArmorFeatureRendererMixin` (fabric) | `HumanoidArmorLayer` + `ArmorMaterial$Layer` | **REMOVED/重写** | `ArmorMaterial.Layer.texture(Z)` 移除（`ArmorMaterial` 移到 `net.minecraft.world.item.equipment`，无嵌套 Layer）；替代 `EquipmentClientInfo.Layer` + `getTexturePath(...)` |

---

## 6. 系统化重命名与移除

| 旧 (1.21.1) | 新 (26.2) | 影响 |
|---|---|---|
| `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` | 17 个文件，系统化替换 |
| `client/renderer/block/model/ItemTransform` / `ItemTransforms` | `client/resources/model/cuboid/ItemTransform` / `ItemTransforms` | accesswidener + import 更新 |
| `net.minecraft.client.renderer.MultiBufferSource` | **移除**（SubmitNodeCollector 体系） | 渲染重写 |
| `BlockEntityWithoutLevelRenderer.renderByItem` | **移除**（SpecialModelRenderer 体系） | item 渲染重写 |
| `net.minecraft.world.item.ArmorMaterial` | `net.minecraft.world.item.equipment.ArmorMaterial` | package 变化 |
| `ArmorMaterial.Layer` | `EquipmentClientInfo.Layer` | 装备资源系统重写 |
| `RenderType` package | `net.minecraft.client.renderer.rendertype.RenderType` | 若代码 import 需更新 |
| `server.level.PlayerList` | `server.players.PlayerList` | import 更新 |
| `server.network.ServerEntity` | `server.level.ServerEntity` | import 更新 |
| `client/renderer/entity/ItemRenderer` | **移除**（ItemModelResolver / ItemStackRenderState / ClientItem） | item 渲染重写 |

---

## 7. 渲染系统重写（最高风险）

26.2 渲染架构：`EntityRenderState` 体系取代 `LivingEntity` 直接渲染；`SubmitNodeCollector` 取代 `MultiBufferSource`；`ItemModel`/`ItemModelResolver`/`ItemStackRenderState` 取代 `ItemRenderer`；`SpecialModelRenderer` 取代 `BlockEntityWithoutLevelRenderer`。

受影响文件：
- `DynamicItemRenderer`（接口 + RENDERERS map）— 需要 26.2 等价钩子
- `BuiltinModelItemRendererMixin` — 重写为 SpecialModelRenderer / item model
- `IArmorRendererBase` / `IArmorTextureProvider` / `ArmorModelBase` / `IArmorRenderHelper` — 装备渲染重写
- common + fabric 两个 `ArmorFeatureRendererMixin` — 重写
- `BasicEntityModel` / `AdvancedEntityModel` / `BasicModelPart` / `AdvancedModelBox` / `TabulaModel` / `BasicModelBase` / `BasicModelRenderer` / `HideableLayer` / `HideableModelRenderer` — `EntityModel<T extends Entity>` → `EntityModel<T extends EntityRenderState>` 泛型重构（模型层级断裂）
- `AdvancedModelBox` 的 11 参 `consumer.addVertex(...)` — 与 26.2 VertexConsumer 兼容（已验证）

### 7.1 模型层级核心断点（model-render 审计）

| 类 | 26.2 变化 |
|---|---|
| `EntityModel` | `<T extends Entity>` → `<T extends EntityRenderState>`；构造必须传 `ModelPart root`；`renderToBuffer` 在父类 `Model` 变 **final**（不能再 override）；`prepareMobModel` 删除；`setupAnim(T,floats...)` → `setupAnim(S state)` 单参；`RenderType` → `net.minecraft.client.renderer.rendertype.RenderType`；`RenderType::entityCutoutNoCull` → `RenderTypes::entityCutout` |
| `BasicModelPart` | 自包含 ModelPart 副本，内部顶点/PoseStack/Axis/JOML API 全部未变；但 `ModelPart` 本身 26.2 是 final，且树必须挂到 root ModelPart 才能经 `renderToBuffer` 渲染 |
| `BasicModelBase` | `EntityModel(Function<ResourceLocation,RenderType>)` 构造移除 → `EntityModel(ModelPart root[, Function<Identifier,RenderType>])` |
| `ModelAnimator` / `ChainBuffer` | `Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)` 移除 → `DeltaTracker.getGameTimeDeltaPartialTick(boolean)`（render 调用中传入） |
| `TabulaModelHandler` | `ItemTransform/ItemTransforms` 移包到 `client/resources/model/cuboid/`；`Deserializer` 变为 `protected static class`（可能无法从该包实例化） |
| `HideableLayer` | `RenderLayer` 泛型变 `<S extends EntityRenderState, M extends EntityModel<? super S>>`；`render(...)` → `submit(PoseStack, SubmitNodeCollector, int, S, float, float)` |
| `ArrayLayeredTexture` / `SimpleTexture` | 纹理系统重写：`AbstractTexture.load(ResourceManager)/getId()` 移除 → `getTexture()/getTextureView()/getSampler()`；`TextureUtil.prepareImage` 移除；`NativeImage.upload/blendPixel/getPixelRGBA` 移除 → `DynamicTexture` / `ReloadableTexture` / `GpuDevice` |
| `ArmorModelBase` | `HumanoidModel<LivingEntity>` → `HumanoidModel<HumanoidRenderState>`；`setupAnim` 单参；`ItemRenderer.getArmorFoilBuffer` 删除 → `EquipmentLayerRenderer`/`RenderTypes.armorEntityGlint()`；`ModelPart.copyFrom` 移除 → `hat.loadPose(head.storePose())`；`Rotations` 移到 `net.minecraft.core.Rotations`（record，`x()/y()/z()`） |
| `IArmorRendererBase` | `copyPropertiesTo` 移除；`ArmorItem` 类删除；`ArmorMaterial.Layer/layers()` 删除 → 数据驱动 `DataComponents.EQUIPPABLE` + `EquipmentAsset` + `EquipmentClientInfo`；`RenderType.armorCutoutNoCull` → `RenderTypes.armorCutoutNoCull` |
| `IArmorRenderHelper` | `PlayerModel<AbstractClientPlayer>` → 非泛型 `net.minecraft.client.model.player.PlayerModel`；`model.riding` 字段移除 |

### 7.2 item / object 层断点（item-object 审计）

| 文件 | 26.2 断点 | 修复 |
|---|---|---|
| `EntityUtil` | (1) `EntityType.create(Level)` 单参移除；(2) `Entity.moveTo` → `snapTo`；(3) `MobSpawnType` 枚举移除 → `EntitySpawnReason`；(4) `EntityType.LIGHTNING_BOLT` → `EntityTypes.LIGHTNING_BOLT` | `create(Level, EntitySpawnReason.MOB_SUMMONED)`；`snapTo(...)`；`finalizeSpawn(ServerLevelAccessor, DifficultyInstance, EntitySpawnReason, SpawnGroupData)` |
| `RegistryHelper` | `RegistryAccess.registryOrThrow(ResourceKey)` 移除 → `lookup(key).orElseThrow()`；`Registry.get(ResourceKey)` → `getValue(ResourceKey)`；`Registries.ARMOR_MATERIAL` **移除**（ArmorMaterial 不再是 registry/Holder） | 按左修复；装甲材料改用 `Item.Properties.humanoidArmor(ArmorMaterial, ArmorType)` |
| `DamageUtil` | 转置断点：`RegistryHelper.getDamageSource` 用了移除的 `registryOrThrow` | 修 RegistryHelper 即可 |
| `IdUtil` / `SoundUtil` | `ResourceLocation` → `Identifier`；`Registry.get(ResourceLocation)` → `Registry.getValue(Identifier)`；`ClientboundStopSoundPacket(ResourceLocation,...)` → `(Identifier,...)` | 系统化替换 |
| `ArmorWithTickItem` | `ArmorItem` 类**移除**；`super(material, slot, settings)` 目标删除 | extends `Item` + `Item.Properties().humanoidArmor(material, type)`；`onArmorTick` 移到 `Item.inventoryTick` |
| `SwordItemBase` / `FoilSwordItemBase` | `SwordItem` 类**移除**；`Tier` 接口删除 | extends `Item` + `Item.Properties().sword(ToolMaterial, attackDamageBaseline, attackSpeedBaseline)` |
| `ToolMaterialUtil` | `Tier` 匿名类 → `ToolMaterial` record（repair 变 `TagKey<Item>`，非 Ingredient） | 用 `ToolMaterial` record + `Identifier.parse` |

### 7.3 其余（events-network / 通用）审计

| 文件 | 26.2 变化 |
|---|---|
| `AnimationHandler` | `level.isClientSide` 字段 → `isClientSide()` 方法 |
| `AnimationPayload` | `ResourceLocation` → `Identifier`；`CustomPacketPayload.Type` 现为 `record Type(Identifier id)` |
| `ServerHelper` / `Uranus` / `UranusClient` | `NetworkManager.sendToPlayers/registerS2CPayloadType/registerReceiver` 全部存在（已验证）；`Platform.getEnvironment()==Env.SERVER` 不变 |
| `PacketBufferUtils` / `Timeout` / `event/*` | 全部 OK，无需修改 |
| `BlockUtil` / `FoodUtils` | `BlockState.is(TagKey)` 移到 `TypedInstance` 默认方法，源码不改仍编译 |

---

## 7bis. 补充审计代理交叉验证状态

后台 8 代理分析工作流（wf_1cdf776c-c9c）已全部完成（8/8，0 错误，726k tokens），产出：categorize（逐文件归类）、mixins（14 mixin 审计）、api（pathfinding/modelRender/itemObject/eventsNetwork 四区逐文件）、deps（依赖可用性）。上表已合并其主要断点；**与直接验证一致，无冲突**。以 26.2 权威源码为准。

---

## 8. Pathfinding 内部变化

| 旧 API | 26.2 状态 | 影响 |
|---|---|---|
| `serverChunkCache.chunkMap`（字段） | `public final ChunkMap chunkMap`（**公开**） | 无需 accesswidener（原来是访问私有字段？实际 1.21.1 已是 public） |
| `ChunkMap.getVisibleChunkIfPresent` | `protected`（**保持不变**） | accesswidener 继续需要 |
| `ChunkHolder.ChunkResult` + `UNLOADED_LEVEL_CHUNK` + `getFullChunkFuture` | 全部不变 | ✅ |
| `ServerLevel.isPositionEntityTicking` | 不变 | ✅ |
| `LevelChunk.getBlockEntity(pos, EntityCreationType.CHECK)` | 不变 | ✅ |
| `PathFinder`, `WalkNodeEvaluator`, `Node`, `Path`, `PathfindingContext`, `PathComputationType`, `GroundPathNavigation` | 全部存在 | ✅ |
| `Path.getNode/getNodeCount/getNextNodeIndex/advance/getDistToTarget` | 不变 | ✅ |

ChunkCache / WorldChunkUtil 主要依赖的 `chunkMap` 字段在 26.2 已 `public final`，`getVisibleChunkIfPresent` 仍 protected（accesswidener 保留），其余 pathfinding API 稳定。

### Pathfinding 具体编译断点（pathfinding 审计，逐文件确认）

| 文件 | 26.2 断点 | 修复 |
|---|---|---|
| `AbstractAdvancedPathNavigate` | `net.minecraft.util.Tuple` **移除** | → `com.mojang.datafixers.util.Pair` / record |
| `AdvancedPathNavigate` | (1) `DebugPackets` **移除**；(2) `Tuple` 移除；(3) `this.level.random` 变 `protected final` | (1) `PathFinder.setCaptureDebug(BooleanSupplier)` / `DebugSubscriptions`；(2) Pair；(3) `this.level.getRandom()` |
| `ChunkCache` | (1) `getMinBuildHeight/getMaxBuildHeight` → `getMinY/getMaxY`；(2) `ChunkPos.asLong(x,z)` 移除 → `ChunkPos.pack(x,z)`；(3) `getShade(Direction,boolean)` 移除；(4) `getVisibleChunkIfPresent` 变 protected | 按上表；或改用 `serverChunkCache.getChunk(x,z,ChunkStatus.FULL,false)` |
| `AbstractPathJob` | (1) `world.getMinBuildHeight/getMaxBuildHeight` 移除；(2) `Direction.getNearest(x,0,z)` 移除；(3) 转置 `PathUtil` 的 PathType 常量 | → `getMinY/getMaxY`；→ `Direction.getApproximateNearest(x,0,z)` |
| `PathingStuckHandler` | `Direction.getNearest(vx,vy,vz)` 移除 | → `Direction.getApproximateNearest(vx,vy,vz)` |
| `PathJobRandomPos` | `Tuple<Direction,Direction>` + `getA/getB` | → `Pair<Direction,Direction>` + `getFirst/getSecond` |
| `CustomCollisionsNodeProcessor` | (1) `PathType.DAMAGE_FIRE/DAMAGE_OTHER` 移除；(2) `(LevelReader)` 强转 PathfindingContext 不再合法 | (1) → `PathType.FIRE/DAMAGING`；(2) PathfindingContext 现持 `CollisionGetter` 而非 LevelReader → 用 `ctx.level()`，`PathUtil` 参数放宽为 `BlockGetter` |
| `PathUtil` | `PathType` 常量重命名：`DAMAGE_FIRE/DANGER_FIRE`→`FIRE/FIRE_IN_NEIGHBOR`；`DAMAGE_OTHER/DANGER_OTHER`→`DAMAGING/DAMAGING_IN_NEIGHBOR` | 重写 getDanger/getAiPathNodeType 逻辑 |

已验证 OK（无需修改）：`PathFinder(NodeEvaluator,int)`、`WalkNodeEvaluator/FlyNodeEvaluator` 无参构造、`new Path(List<Node>,BlockPos,boolean)`、`Path.getNode/getNodeCount/getNextNodeIndex/advance/getDistToTarget`、`Node(int,int,int)`、`PathfindingContext`、`FriendlyByteBuf.readBlockPos/writeBlockPos`、`NodeEvaluator.entityWidth/Height/Depth`、`MobSpawnType`→`EntitySpawnReason` 相关在 EntityUtil（见 §12）。

---

## 9. Accesswidener 处理

### `common/src/main/resources/uranus.accesswidener`（保留，更新路径）
```diff
- accessible class net/minecraft/client/renderer/block/model/ItemTransform$Deserializer
- accessible method net/minecraft/client/renderer/block/model/ItemTransform$Deserializer <init> ()V
- accessible class net/minecraft/client/renderer/block/model/ItemTransforms$Deserializer
- accessible method net/minecraft/client/renderer/block/model/ItemTransforms$Deserializer <init> ()V
+ accessible class net/minecraft/client/resources/model/cuboid/ItemTransform$Deserializer
+ accessible method net/minecraft/client/resources/model/cuboid/ItemTransform$Deserializer <init> ()V
+ accessible class net/minecraft/client/resources/model/cuboid/ItemTransforms$Deserializer
+ accessible method net/minecraft/client/resources/model/cuboid/ItemTransforms$Deserializer <init> ()V
  accessible field net/minecraft/world/level/pathfinder/NodeEvaluator entityWidth I
  accessible field net/minecraft/world/level/pathfinder/NodeEvaluator entityHeight I
  accessible field net/minecraft/world/level/pathfinder/NodeEvaluator entityDepth I
  accessible method net/minecraft/server/level/ChunkMap getVisibleChunkIfPresent (J)Lnet/minecraft/server/level/ChunkHolder;
```

### `fabric/src/main/resources/uranus.accesswidener`（过时 Yarn 重复文件，**删除**）
- 内容为 Yarn 映射（`PathNodeMaker`、`DamageSources`、`ServerChunkLoadingManager`），在 Mojmap 26.2 下无效
- `fabric.mod.json` 的 `"accessWidener": "uranus.accesswidener"` 通过 Loom 的 `accessWidenerPath = project(":common").loom.accessWidenerPath` 引用 common 的 AW，fabric 端重复文件不生效 → 删除

---

## 10. 版本配置变更清单（P1 待执行）

### `gradle/wrapper/gradle-wrapper.properties`
```
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
```

### `gradle.properties`
```diff
- org.gradle.java.home=C:/Program Files/Zulu/zulu-21
  # 移除 Java 21 固定（或改为 zulu-25）
- minecraft_version=1.21.1
+ minecraft_version=26.2
- architectury_api_version=13.0.8
+ architectury_api_version=21.0.7
- fabric_loader_version=0.16.7
+ fabric_loader_version=0.19.3
- fabric_api_version=0.107.0+1.21.1
+ fabric_api_version=0.156.0+26.2
  # yarn_mappings 行删除或保留无影响（build.gradle 用 officialMojangMappings）
```

### `build.gradle`
```diff
- id 'dev.architectury.loom' version '1.7-SNAPSHOT'
+ id 'dev.architectury.loom' version '1.17-SNAPSHOT'
- id 'architectury-plugin' version '3.4-SNAPSHOT'
+ id 'architectury-plugin' version '3.5-SNAPSHOT'
  sourceCompatibility = JavaVersion.VERSION_25
  targetCompatibility = JavaVersion.VERSION_25
  it.options.release = 25
```

### `fabric.mod.json`
```diff
- "minecraft": "1.21.x"
+ "minecraft": "26.x"
```

---

## 11. P0/P1/P2 迁移计划

### P0 — 项目环境检查（本次完成）
- [x] 编译基线（0 错误）
- [x] 依赖版本确认
- [x] API 差异核对（26.2 源码）
- [x] 本分析报告

### P1 — Build 系统迁移（下一步执行）
1. 更新 wrapper / gradle.properties / build.gradle / fabric.mod.json 版本
2. 移除 `org.gradle.java.home` 固定（需确认本机 Java 25 可用；AGENTS.md 要求 Java 25+，若缺失先报告环境）
3. `./gradlew clean build` → 记录第一批真实编译错误
4. 每类错误批量修复（不删除功能）

### P2 — 代码迁移（分多阶段）
1. **系统化重命名**：ResourceLocation→Identifier，RenderType package，ArmorMaterial package，PlayerList/ServerEntity package
2. **Accesswidener**：更新 common AW 路径，删除 fabric AW 重复文件
3. **Mixin 签名适配**（9 个服务端 + 2 个客户端签名变化）
4. **Rendering 重写**：EntityRenderState 泛型模型层级 / SubmitNodeCollector / SpecialModelRenderer / EquipmentLayerRenderer（最高风险，独立小步推进）
5. **Pathfinding**：验证 compile 后行为
6. **Datagen / 资源**：如需
7. **Runtime 验证**：`runClient` / `runServer`

### 错误处理优先级（AGENTS.md §20）
Gradle/依赖 → MC 版本 → Java → Loom → Architectury → common 编译 → fabric 编译 → Registry → Datagen → Mixin → Client → Runtime → 行为

---

## 12. 风险清单

1. **渲染系统重写**（最高风险）：EntityRenderState/SubmitNodeCollector/SpecialModelRenderer 是与 1.21.1 完全不同的体系，不是机械替换。需逐个文件在 26.2 源码确认后重写。
2. **Mixin 绑定**：14 个 mixin 中 7 个需要修改/重写。任何 `defaultRequire=1` 的绑定失败都会导致启动 fatal。不能关闭 mixin 或降 require。
3. **模型泛型断裂**：`EntityModel<T extends Entity>` → `<T extends EntityRenderState>` 使整个自定义模型层级（Tabula/Basic/Advanced）需要适配新 `setupAnim(EntityRenderState,...)` / `renderToBuffer(EntityRenderState,...)` 方法签名。
4. **异步模型烘焙**：ModelBakery `<init>` 语义变化，Tabula 模型加载时机需调整。
5. **行为保持**：装备纹理、自定义 item 渲染、动画帧同步在重写后必须保持 1.21.1 行为。
6. **Java 25 / Gradle 9.5.1 工具链**：需确认本机 JDK 25 存在；Gradle 9 对 Groovy DSL 的兼容性变化（如 `withSourcesJar` 在 `java{}` 块内可能需调整）。
7. **fabric 端重复 AW 文件**：删除时确认无其他引用。

---

## 13. 验证命令

- `./gradlew --version`（Java 25 + Gradle 9.5.1）
- `./gradlew clean build`
- `./gradlew :common:compileJava` / `:fabric:compileJava`
- `./gradlew runClient` / `runServer`
- `./gradlew runDatagen`（如项目有 datagen）

---

## 14. 下一阶段

**P1 — Build 系统迁移**：应用 §10 的版本变更 → `./gradlew clean build` → 记录真实错误 → 按 §11 P2 顺序逐类修复。

（8 代理分析工作流 wf_1cdf776c-c9c 已于本报告定稿前全部完成并合并，见 §7bis。）

---

# P1 阶段报告 — Build 系统迁移

**阶段：** P1 — Build Script 迁移
**状态：** ✅ 构建配置迁移完成；`./gradlew clean build` 已通过全部构建配置阶段，编译阶段记录到第一批真实错误（100 个，属于后续阶段）

## 修改文件

| 文件 | 修改内容 |
|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.10 → 9.5.1 |
| `gradle.properties` | `org.gradle.java.home` 指向 Zulu JDK 25；`minecraft_version=26.2`；`fabric_loader_version=0.19.3`；`fabric_api_version=0.156.0+26.2`；`architectury_api_version=21.0.7`；`mod_version=2.4.1-bugfix`；`enabled_platforms=fabric` |
| `build.gradle` | `dev.architectury.loom-no-remap` 1.17-SNAPSHOT（apply false）+ `architectury-plugin` 3.5-SNAPSHOT + Shadow 8.1.1；`minecraft "com.mojang:minecraft:26.2"`（去掉 `mappings` 行）；Java 25（source/target + `options.release`） |
| `settings.gradle` | pluginManagement 仓库保留 fabric/architectury/forge + gradlePluginPortal（无需修改，当前已满足 26.2 解析） |
| `common/build.gradle` | `modImplementation` → `implementation`（loom-no-remap 无 `modImplementation` 配置）；AW 指向 `common/src/main/resources/uranus.accesswidener` |
| `fabric/build.gradle` | `modImplementation` → `api`/`implementation`；去掉 `remapJar` 块；`jar { archiveClassifier = 'raw' }`；`shadowJar` 作为最终产物（zip64、bundles architectury-fabric + common 的 `transformProductionFabric`）；`assemble.dependsOn shadowJar`；`injectAccessWidener(tasks.named('jar'))` |
| `fabric/src/main/resources/fabric.mod.json` | `"minecraft": "26.x"` |
| `common/src/main/resources/uranus.accesswidener` | 头改为 `accessWidener v2 official`；`ItemTransform/ItemTransforms` 路径改为 `client/resources/model/cuboid/`（已验证 26.2 源码位置） |

## 新增
- `docs/fabric-26.2-migration.md` 追加本 P1 阶段报告

## 删除
- `fabric/src/main/resources/uranus.accesswidener`（Yarn 命名空间的残留副本，与 common AW 重复；已由 common AW 注入 fabric jar）

## 主要 API 变化（本阶段在 26.2 源码中确认）
- `net.minecraft.resources.ResourceLocation` → **`net.minecraft.resources.Identifier`**
- `net.minecraft.client.renderer.RenderType` **移除** → 渲染管线改为 `RenderPipelines` / SubmitNode 系统
- `net.minecraft.client.renderer.MultiBufferSource` → **`SubmitNodeCollector`**；实体渲染签名变为 `submit(state, poseStack, collector, cameraRenderState)`
- `net.minecraft.client.renderer.entity.ItemRenderer`、`client.model.PlayerModel`、`BlockEntityWithoutLevelRenderer` **移除** → `client.renderer.special.SpecialModelRenderer` / `ArmorModelSet`
- `ItemTransform` / `ItemTransforms` 移至 `client/resources/model/cuboid/`
- `ArmorItem` / `ArmorMaterial` **移除** → `net.minecraft.world.item.equipment.ArmorMaterial`（record）+ `Item.Properties.humanoidArmor(material, type)`
- `Tier` → **`net.minecraft.world.item.ToolMaterial`**
- `SwordItem`、`net.minecraft.util.Tuple`、`DebugPackets`、`MobSpawnType` **完全移除**（无同名替代类）
- `Level.isClientSide` 改为 **private** → 需用 `level().isClientSide()`

## 解决的错误（4 个构建配置错误，全部修复）
- **Error 1** — `Failed to find official mojang mappings for 26.2`：MC 26.2 官方名内置、无独立 mappings 工件 → 换 `dev.architectury.loom-no-remap` 插件 + `com.mojang:minecraft:26.2` + 去掉 `mappings loom.officialMojangMappings()`（与 architectury-api26.2 参考项目一致）
- **Error 2** — `Could not find method modImplementation()`：loom-no-remap 不注册 remap-only 的 `modImplementation` 配置 → common 用 `implementation`，fabric 用 `api`（loader/fabric-api）+ `implementation`/`shadowBundle`（architectury-fabric）
- **Error 3** — `Expected official namespace for access widener entry, found: named`：AW 头 `v2 named` 与 no-remap 模式冲突 → 头改 `accessWidener v2 official`、条目改官方（mojmap）路径，删除 fabric 端 Yarn 残留 AW
- **Error 4** — `Could not find method remapJar()`：loom-no-remap 无 remapJar 任务 → 移除 remapJar 块，`jar{archiveClassifier='raw'}` + shadowJar 作为最终产物

## 剩余错误（第一批真实错误，100 个，已按 26.2 源码确认替换目标）
| 类别 | 涉及文件 | 错误数 | 26.2 替换 |
|---|---|---|---|
| ResourceLocation → Identifier | 15 | 37 | `net.minecraft.resources.Identifier` |
| 渲染系统重写（RenderType/MultiBufferSource/ItemRenderer/PlayerModel/ItemTransform/BlockEntityWithoutLevelRenderer 移除） | 11 | 32 | `SubmitNodeCollector`、`SpecialModelRenderer`、`cuboid` 包 |
| 物品/工具重构（ArmorItem/ArmorMaterial→equipment、Tier→ToolMaterial、SwordItem 移除） | 7 | 20 | `world.item.equipment.ArmorMaterial`、`ToolMaterial` |
| 寻路（Tuple / DebugPackets 移除） | 3 | 5 | 需设计替代（见 P4） |
| MobSpawnType 移除 | 1 | 1 | 需设计替代（见 P4） |
| `isClientSide` 私有化 | 1 | 1 | `level().isClientSide()` |
| 级联错误（ArmorItem/Tier 未解析导致的 Type/Properties） | 3 | 4 | 随上述修复自动消失 |

**合计 100 个错误**（common 模块，`fabric` 模块尚未编译到）。

## 风险
- **渲染系统重写（32 个错误）是最大风险区**：不是简单的类名替换，需要按 26.2 的 `EntityRenderState` + `submit()` + `SubmitNodeCollector` 模型重写实体/物品渲染路径，涉及 `BasicEntityModel`、Tabula 模型系统、护甲渲染、`DynamicItemRenderer`、两个 Mixin
- `SwordItem` / `Tuple` / `DebugPackets` / `MobSpawnType` 在 26.2 中无同名类，不能机械替换，需要确认替代 API 后再动手（§24-12 禁止未确认的批量替换）
- 物品系统重构（20 个错误）会影响注册层（`RegistryHelper.getArmorMaterial`），需同步确认 registry 侧 API

## 验证命令
- `./gradlew clean build`（P1 验证命令）

## 验证结果
- 构建配置阶段全部通过：Loom 1.17.491 + Architectury Plugin 3.5.169 + Gradle 9.5.1 + Java 25 正常加载；AW 校验通过；MC 26.2 官方命名解析成功
- `:common:compileJava` **失败**：100 个编译错误（第一批，见上表），`fabric` 模块编译被阻断（依赖 common）
- 4 个构建配置错误全部解决，无遗留

## 下一阶段
- **P2 — Common 模块兼容性**（§9）：按错误优先级（§20）逐类修复 —— 先做**系统化重命名**（`ResourceLocation`→`Identifier`，37 个错误、涉及 15 文件，纯机械替换、风险最低），再做物品/工具重构（`ArmorMaterial`→equipment、`Tier`→`ToolMaterial`、`SwordItem` 替代）
- **P3 — Mixin 适配**：`@Mixin(BlockEntityWithoutLevelRenderer)` 等目标签名随渲染重写同步更新
- **P4 — 渲染系统重写**：按 `EntityRenderState` / `submit()` / `SubmitNodeCollector` 模型重写实体模型与护甲渲染
- **P5+**：寻路（Tuple/DebugPackets 替代）、datagen、resources、运行时验证（runClient/runServer）
