# Uranus 迁移分析报告（Phase 0）

**目标**：将 Uranus（IAFEnvoy，基于 LLibrary / Citadel 的 MC 库模组）从
**Minecraft 1.21.1 + Yarn 映射 + Architectury** 迁移到
**Minecraft 26.2 + 官方 Mojmap + Architectury**。

**状态**：Phase 0 —— 项目调查 + 源码分析（只分析，未修改任何代码）。

本报告基于全部 8 个子代理工作流结果撰写，所有 26.2 API 均已对照
`D:\aiminecraftdev\minecraft26.2`、`architectury-api26.2`、`NeoForge26.2`、
`fabric-loader-0.19.3` 等参考源码验证，未凭经验猜测。

---

## 1. 项目模块结构

```
Uranus/
├── settings.gradle            # rootProject='uranus'; include common, fabric, neoforge
├── build.gradle               # loom 1.7-SNAPSHOT, architectury-plugin 3.4, johnrengelman-shadow 8.1.1
├── gradle.properties          # 版本中枢（见 §2）
├── gradle/wrapper/            # Gradle 8.10
├── common/                    # 库本体（139 个 Java 文件，10465 行）
│   └── src/main/
│       ├── java/com/iafenvoy/uranus/
│       │   ├── ServerHelper.java / Uranus.java
│       │   ├── animation/         # Animation, AnimationAI, AnimationEvents, AnimationHandler, IAnimatedEntity
│       │   ├── client/            # UranusClient + model/ (Tabula 模型引擎) + render/ (盔甲/纹理/动态物品渲染)
│       │   ├── data/              # EntityPropertyDelegate
│       │   ├── event/             # 自研 Event<T>, EntityEvents, LivingEntityEvents, PlayerEvents
│       │   ├── mixin/             # 13 个 mixin（含 4 个 client）
│       │   ├── network/           # AnimationPayload, PacketBufferUtils
│       │   ├── object/            # BlockUtil, DamageUtil, EntityUtil, IdUtil, PathUtil, RegistryHelper,
│       │   │                      #   SoundUtil, VecUtil, item/ (8 个), entity/ (collision + pathfinding, 31 个)
│       │   └── util/              # ClientUtils, ShapeBuilder, RandomHelper + function/ 泛型工具族
│       └── resources/            # uranus.accesswidener, uranus.mixins.json
├── fabric/                     # Fabric 入口（3 文件，65 行）
│   └── src/main/
│       ├── java/.../fabric/       # UranusFabric, client/UranusFabricClient, mixin/ArmorFeatureRendererMixin
│       └── resources/            # fabric.mod.json, uranus-fabric.mixins.json, uranus.accesswidener, logo.png
└── neoforge/                   # NeoForge 入口（3 文件，63 行）
    └── src/main/
        ├── java/.../neoforge/     # UranusNeoForge, client/UranusNeoForgeClient, mixin/ArmorFeatureRendererMixin
        └── resources/            # META-INF/neoforge.mods.toml, pack.mcmeta, uranus-neoforge.mixins.json, logo.png
```

要点：

- **没有独立的 datagen / worldgen / resources 模块**——common 是唯一代码库，客户端代码（model/render）也在 common 内，经 Architectury 平台抽象编译进两个 loader。
- **客户端 mixin 也在 common**（`uranus.mixins.json` 的 `client` 数组），平台模块各带一份 `ArmorFeatureRendererMixin`。
- 资源极简：无 lang / 模型 / 贴图 / 数据包 / 世界生成等资源文件。
- 自研基础设施：`event/Event.java`（自定义泛型事件总线，非 Architectury）、`util/function/` 全套多参数函数族。

## 2. 当前依赖

| 依赖 | 当前版本（1.21.1 / Yarn） | 目标版本（26.2 / Mojmap） | 依据 |
|---|---|---|---|
| Loom | `dev.architectury.loom` 1.7-SNAPSHOT | `dev.architectury.loom-no-remap` **1.17-SNAPSHOT** | architectury-api26.2/build.gradle:11 |
| Architectury Plugin | 3.4-SNAPSHOT | **3.5-SNAPSHOT** | architectury-api26.2/build.gradle:10 |
| Shadow | `com.github.johnrengelman.shadow` 8.1.1 | `com.gradleup.shadow` **9.4.3** | architectury-api26.2/build.gradle:12 |
| Minecraft | `net.minecraft:minecraft:1.21.1` | `com.mojang:minecraft:26.2` | architectury-api26.2 全部子项目 |
| Mappings | `loom.layered { yarn 1.21.1+build.3; yarn-mappings-patch-neoforge 1.21+build.4 }` | **移除整个 mappings 块**（loom-no-remap 默认官方 Mojmap） | architectury-api26.2 / fabric-api-0.156.0-26.2 / owo-lib26.2 均不声明 mappings |
| Java | 21（sourceCompatibility + options.release=21） | **25**（VERSION_25, options.release=25） | architectury 26.2 settings.gradle 强制 25+ |
| Gradle | 8.10 | **9.5.1**（上限 9.7.0） | architectury-api26.2 wrapper=9.5.1, NeoForge26.2=9.7.0 |
| Architectury API | 13.0.8 | **21.0.x**（base_version=21.0） | architectury-api26.2/gradle.properties |
| Fabric Loader | 0.16.7 | **0.19.3** | architectury-api26.2 锁定 |
| Fabric API | 0.107.0+1.21.1 | **0.154.2+26.2** | architectury-api26.2 锁定 |
| NeoForge | 21.1.76 | **26.2.0.43-beta** | architectury-api26.2 锁定 |

构建配置迁移要点（均已在 platform 分析中对照 architectury-api26.2 验证）：

- **`remapJar` 任务消失**：fabric/build.gradle 与 neoforge/build.gradle 中的
  `remapJar { input.set shadowJar.archiveFile }` 必须删除（loom-no-remap 不做 remap）。
  26.2 采用 `jar { archiveClassifier='raw' } + shadowJar + assemble.dependsOn shadowJar`。
- **NeoForge AW 转换 DSL 变化**：`enableTransitiveAccessWideners` /
  `addTransitiveAccessWideners` / `remapJar.atAccessWideners` 全部无效，
  改为 `loom.neoForge { convertAccessWideners(tasks.named('shadowJar', Jar), 'uranus.accessWidener') }`。
- **accessWidener 头必须改为** `accessWidener v2 official`（见 §8）。
- **元数据版本范围**：fabric.mod.json `minecraft "1.21.x"` → `">=26.2"`，补充
  `fabricloader ">=0.19.3"`、`fabric-api ">=0.154.0"`；
  neoforge.mods.toml `loaderVersion "[2,)"` → `"[1,)"`，`neoforge "[21,)"` → `"[26.2,)"`，
  `minecraft "[1.21,1.21.2)"` → `"[26.2,)"`，`architectury "[0,)"` → `"[21.0,)"`。
- **pack.mcmeta**：`pack_format` 15 → **88**，`forge:server_data_pack_format` 15 → **107**
  （`forge:` 命名空间键在 26.2 解析器未在 NeoForge 源码中找到——UNVERIFIED，建议删除该键）。
- **mixins.json 全部三份** `compatibilityLevel JAVA_17` → `JAVA_25`。
- 建议 `gradle.properties` 增加 `loom.ignoreDependencyLoomVersionValidation=true`。

## 3. 源码规模统计

- 总 Java 文件：**145**（common 139 + fabric 3 + neoforge 3）
- 总行数：**10,593**（common 10,465 + fabric 65 + neoforge 63）
- 资源文件：10 个（accesswidener、3 份 mixins.json、fabric.mod.json、neoforge.mods.toml、pack.mcmeta、2 份 logo、1 份 fabric accesswidener）
- 自定义事件：1（`event/Event.java`）
- Mixin：**15 个注入文件**（common 13 + fabric 1 + neoforge 1）

最大的文件（迁移工作量集中地）：

| 文件 | 行数 |
|---|---|
| `pathfinding/raycoms/pathjobs/AbstractPathJob.java` | 1186 |
| `pathfinding/raycoms/AdvancedPathNavigate.java` | 791 |
| `pathfinding/raycoms/PathingStuckHandler.java` | 532 |
| `pathfinding/raycoms/MNode.java` | 386 |
| `client/model/AdvancedModelBox.java` | 323 |
| `client/model/tools/IntermittentAnimation.java` | 318 |
| `client/model/tools/BasicModelRenderer.java` | 306 |
| `client/model/basic/BasicModelPart.java` | 273 |
| `client/model/tools/ControlledAnimation.java` | 272 |
| `client/model/AdvancedEntityModel.java` | 256 |
| `pathfinding/raycoms/ChunkCache.java` | 243 |

pathfinding/collision 子系统约占总行数 40%，是全项目最重的单一领域。

## 4. Yarn → Mojmap 映射概述

**迁移本质**：不是"包名替换"，而是**跨两代 API 的语义迁移**（1.21.1 → 26.2）。
loom-no-remap 无 mappings 层，源码必须直接写官方 Mojmap 名。
机械替换被 AGENT.md 明令禁止；但相当一部分映射确实是"同名重命名/包移动"，可批量处理。
完整映射表在 Phase 1 产出 `docs/yarn-to-mojmap-mapping.md`（只记录本项目用到的）。

高频映射族（已全部在 minecraft26.2 源码中验证）：

| 类族 | Yarn 1.21.1 | Mojmap 26.2 |
|---|---|---|
| 世界 | `World` / `ServerWorld` / `ClientWorld` | `Level` / `ServerLevel` / `ClientLevel` |
| 实体 | `Entity` / `LivingEntity` / `MobEntity` / `PlayerEntity` | `Entity` / `LivingEntity` / `Mob` / `Player`（包移动） |
| 玩家背包 | `PlayerInventory` | `Inventory` |
| 服务端玩家 | `ServerPlayerEntity` | `ServerPlayer` |
| 玩家管理器 | `PlayerManager` | `PlayerList` |
| 注册 | `RegistryKey` / `RegistryKeys` / `DynamicRegistryManager` / `RegistryEntry` | `ResourceKey` / `Registries` / `RegistryAccess` / `Holder` |
| 坐标 | `BlockPos` / `Vec3d` / `Box` / `MathHelper` / `Direction` | `BlockPos` / `Vec3` / `AABB` / `Mth` / `Direction`（包移动） |
| 寻路 | `PathNode` / `PathNodeMaker` / `MobNavigation` / `PathNodeType` / `Path` | `Node` / `NodeEvaluator` / `GroundPathNavigation` / `PathType` / `Path` |
| 网络 | `CustomPayload` / `PacketCodec` / `PacketByteBuf` | `CustomPacketPayload` / `StreamCodec` / `FriendlyByteBuf` |
| 渲染数学 | `MatrixStack` / `RotationAxis` / `VertexConsumer` | `PoseStack` / `com.mojang.math.Axis` / `VertexConsumer`(改名) |
| 模型变换 | `ModelTransformationMode` | `ItemDisplayContext`（枚举常量同名） |
| 资源标识 | `net.minecraft.util.Identifier` | `net.minecraft.resources.Identifier`（**仅包变化**；`of()` 删除 → `fromNamespaceAndPath/parse/withDefaultNamespace`） |
| 伤害 | `DamageSource` / `DamageSources` / `DamageType` | 同包名移动 + 构造器参数顺序对调 + `RegistryEntry`→`Holder` |

## 5. MC API 变化（关键，全部已验证）

### 5.1 伤害系统（跨切面，最容易出错）
- `DamageSource` 3 参构造器：Yarn `(Holder<DamageType> type, Entity source, Entity attacker)`
  → 26.2 `(Holder<DamageType> type, @Nullable Entity directEntity, @Nullable Entity causingEntity)`。
  **两个实体参数顺序对调**（yarn `source`=26.2 `causingEntity`，yarn `attacker`=26.2 `directEntity`）。
  迁移 `DamageUtil.build` 必须写 `new DamageSource(holder, origin.getDirectEntity(), origin.getEntity())`。
- `getSource()` → `getEntity()`；`getAttacker()` → `getDirectEntity()`；`getTypeRegistryEntry()` → `typeHolder()`。
- `DamageSources` 无 `builder()`；自定义源配方：
  `registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key)` → `Holder<DamageType>`。
- `LivingEntity.damage(DamageSource,float)` **已删除** → `hurtServer(ServerLevel, DamageSource, float)`
  （无 hurtClient；调用方必须显式传 ServerLevel）。
- `Player.applyDamage(float)` → `protected void actuallyHurt(ServerLevel, DamageSource, float)`，
  体内仍含 `dmg = Math.max(dmg - this.getAbsorptionAmount(), 0.0F);`（Player.java:753）——DAMAGE 事件 mixin 可重定向。
- `LivingEntity.handleFallDamage(float,float,DamageSource)` → `causeFallDamage(double,float,DamageSource)`（首参 float→double）。

### 5.2 注册系统
- `RegistryAccess.get(ResourceKey)` → `lookup(ResourceKey)`(Optional) / `lookupOrThrow(ResourceKey)`。
- `Registry.get(ResourceKey)` → `getValue` / `getValueOrThrow`；`Registry.getEntry(T)` **已删除**
  → `getResourceKey(obj).flatMap(registry::get)`（Holder 路径）或 `wrapAsHolder(T)`。
- `RegistryKeys.ARMOR_MATERIAL` **已删除**（全 net 树无 `armor_material` 字符串）——
  `RegistryHelper.getArmorMaterial()` 无对应物，必须重构/删除。
- `Registries.ENCHANTMENT/ITEM/BLOCK/DAMAGE_TYPE` 仍存在。

### 5.3 物品层级（结构性破坏）
- **`ArmorItem` 与 `SwordItem` 类在 26.2 不存在**。盔甲 → `Item.Properties().humanoidArmor(ArmorMaterial, ArmorType)`；
  剑 → `Item.Properties().sword(ToolMaterial, dmg, spd)`。
- `ArmorMaterial` 从 registry 对象变为 **record**（`net.minecraft.world.item.equipment.ArmorMaterial`），
  含 `ResourceKey<EquipmentAsset> assetId`；`ArmorMaterial.Layer` 删除（纹理层改为 EquipmentAsset/EquipmentAssets）。
- `ToolMaterial` 变为 record `(TagKey<Block> incorrectBlocksForDrops, int durability, float speed, float attackDamageBonus, int enchantmentValue, TagKey<Item> repairItems)`——
  `ToolMaterialUtil` 的匿名接口实现崩溃。

### 5.4 实体 / 世界
- `Entity.getWorld()` → `level()`；`getRegistryManager()` → `registryAccess()`；`getRandom()` 不变。
- `Entity.dropStack(ItemStack,float)` → `spawnAtLocation(ServerLevel, ItemStack, float)`（**ServerLevel 前置**）。
- `Entity.getWidth()/getHeight()` **已删除** → `getDimensions(Pose).width()/height()`（FoodUtils 崩溃）。
- `World.getEntityById(int)` → `Level.getEntity(int)`；`World.spawnEntity` → `ServerLevel.addFreshEntity`。
- `World.getLocalDifficulty(BlockPos)` → `ServerLevel.getCurrentDifficultyAt(BlockPos)`。
- `EntityType.create(Level)` → `create(Level, EntitySpawnReason)`；`EntityType.LIGHTNING_BOLT` → `EntityTypes.LIGHTNING_BOLT`。
- `setCosmetic` → `setVisualOnly`；`initialize` → `finalizeSpawn(ServerLevelAccessor, DifficultyInstance, EntitySpawnReason, SpawnGroupData)`；
  `refreshPositionAndAngles` → `snapTo(x,y,z,yRot,xRot)`。
- `MinecraftServer.getPlayerManager()` → `getPlayerList()`；`PlayerManager.getPlayerList()` → `PlayerList.getPlayers()`。
- `MinecraftServer.stop()` → `protected stopServer()`；26.2 构造器签名大改（无 ResourceKey<Level>/Holder<Level>，WorldStem 移入 net.minecraft.server）。

### 5.5 渲染（26.2 render-state 重构）
- `MatrixStack` → `PoseStack`（`pushPose/popPose/last/mulPose`，`Pose.pose()→Matrix4f` / `Pose.normal()→Matrix3f`）；
  `RotationAxis` → `com.mojang.math.Axis`（`POSITIVE_X/Y/Z` → `XP/YP/ZP`）；`MathHelper` → `net.minecraft.util.Mth`（**不是** com.mojang.math.Mth）。
- `VertexConsumer.vertex/color/uv/overlay/light/normal` → `addVertex/setColor/setUv/setUv1/setUv2/setNormal`
  （11 参打包默认方法仍存在，参数顺序不变）。
- **`VertexConsumerProvider` / `MultiBufferSource` 消失** → `SubmitNodeCollector`。
- `EntityModel` 泛型改为 `EntityModel<T extends EntityRenderState>`；`setAngles` → `setupAnim`；
  **`Model.render(...)` 变为 final `renderToBuffer(...)`**——项目自定义逐部件渲染无法再 override。
- `FeatureRenderer.render(...)` → `RenderLayer.submit(PoseStack, SubmitNodeCollector, int light, state, float yRot, float xRot)`。
- `net.minecraft.client.render.model.json.{Transformation, ModelTransformation}` **及其 Deserializer 全部删除**（TabulaModelHandler 的 Gson 注册崩溃）。
- `Minecraft.getTimer()` / `RenderTickCounter` **删除** → `getDeltaTracker().getGameTimeDeltaPartialTick(false)`。
- `ModelTransformationMode` → `ItemDisplayContext`（常量同名，机械）。

### 5.6 网络 / Codec
- `CustomPayload` → `CustomPacketPayload`（`net.minecraft.network.protocol.common.custom`）；
  `Id<T>` → `Type<T>` record；`getId()` → `type()`。
- `PacketCodec` → `StreamCodec`；`PacketCodecs` → `ByteBufCodecs`（含 `fromCodec(Codec)→StreamCodec<ByteBuf,T>`、`VAR_INT`、`INT`）。
- `PacketByteBuf` → `FriendlyByteBuf`；`writeNbt(CompoundTag)` → `writeNbt(Tag)`（参数放宽）。
- Codec/RecordCodecBuilder/NbtOps 属外部 DFU 库，**不变**。
- 26.2 官方范本：`BrandPayload`（record + `CustomPacketPayload.codec(writer, reader)` + `createType("brand")` + `type()`）。

### 5.7 已删除且无等价物的 API
- `DebugInfoSender` / `DebugPackets` **不存在**——`AdvancedPathNavigate.java:285` 的
  `DebugInfoSender.sendPathfindingData(...)` 无替代，必须删除/守护（新机制为 ClientboundDebug*Packet）。
- `LevelProperties` → `PrimaryLevelData`：`getLifecycle()`/`lifecycle` 字段**已删除**
  → 仅有 `worldGenSettingsLifecycle()`（PrimaryLevelData.java:286）——LevelPropertiesMixin 失效。
- `PathNavigation.adjustPath()` 删除；`GroundPathNavigation.canMoveDirectly(Vec3d,Vec3d,int,int,int)` → `(Vec3,Vec3)`（尺寸参数删除，内部辅助 isSafeToStandAt/isPositionClear 删除）。
- `Level.random` 字段变为 protected → 外部必须 `level.getRandom()`。
- `PersistentEntitySectionManager.addEntity(T,boolean)` 变 **private** → 公开 `addNewEntity(T)`（仅 existing=false）；泛型 `EntityLike` → `EntityAccess`。

## 6. Architectury API 变化

项目实际使用的 6 个 Arch API **在 26.2 全部存在且签名完全一致**（对照 architectury-api26.2 验证）：

| API | 状态 |
|---|---|
| `NetworkManager.registerS2CPayloadType(Type, StreamCodec)` | 不变（type 泛型改为 `CustomPacketPayload.Type<T>` + `StreamCodec`） |
| `NetworkManager.registerReceiver(Side, Type, StreamCodec, NetworkReceiver)` | 不变；新增推荐的 `registerS2C(Type, codec, receiver)` / `registerC2S(...)` 单调用模式 |
| `NetworkManager.sendToPlayers(Iterable<ServerPlayer>, payload)` | 不变（另含 sendToPlayer / sendToServer） |
| `Platform.getEnvironment()` → `Env` | 不变（`Uranus.init()` 的 `== Env.SERVER` 守卫有效） |
| `Env.SERVER / Env.CLIENT` | 不变 |
| `TickEvent.SERVER_POST` | `Event<Server>`，`Server extends TickEvent<MinecraftServer>`，不变 |

**Arch 26.2 可替代自定义 mixin 的事件**（AGENT.md 优先原则：能用 Arch 就用 Arch）：
- `EntityEvent.ADD`（双端：NeoForge EntityJoinLevelEvent / Fabric PersistentEntitySectionManager + ClientLevel hook）
  → 可替代 `ServerEntityManagerMixin` + `ClientWorldMixin` 的 ON_JOIN_WORLD。
- `PlayerEvent.PLAYER_JOIN / PLAYER_QUIT / PLAYER_RESPAWN` → 可替代 `PlayerManagerMixin` LOGGED_OUT（respawn 注入）。
- `TickEvent.PLAYER_POST` → 可替代 `PlayerInventoryMixin` 盔甲 tick。
- `LifecycleEvent.SERVER_STARTING / SERVER_STOPPING` → 可替代 `MinecraftServerMixin`。

**无 Arch 等价物、必须保留自定义 mixin**：
- **tracking 事件**（EntityEvent 无 tracking）；
- **fall 事件**（无 Arch fall 事件）；
- **伤害量修改**（`EntityEvent.LIVING_HURT` javadoc 明确"无法修改伤害量"，只能取消）。
  这三个注入点分别重定向到 `ServerEntity.addPairing/sendPairingData`、
  `LivingEntity.causeFallDamage(double,...)`、`Player.actuallyHurt(...)`。

Architectury 26.2 = 21.0.x 系列，构建自 MC 26.2 / NeoForge 26.2.0.43-beta / Fabric API 0.154.2，需 Java 25+。

## 7. NeoForge API 变化

- **入口全部无需改动**：`@Mod`、`@EventBusSubscriber(bus=MOD, value=Dist.CLIENT)`、`@SubscribeEvent`、
  `FMLClientSetupEvent.enqueueWork(Runnable)` 均在 26.2 有效（FMLClientSetupEvent 仍在
  CommonModLoader.java:76-77 派发；architectury 26.2 自身的 EventHandlerImplClient 使用同款写法）。
  `AddClientReloadListenersEvent` 存在但**不是** setup 事件的替代品。
- 纯版本/元数据变化见 §2（loaderVersion、依赖范围、pack_format）。
- mixin 注入点（MinecraftServer stop、ServerEntity、PrimaryLevelData 等）的 target 变更属于 common 范围，见 §8。
- 该模块无 NeoForge 专有业务代码，风险集中在构建配置。

## 8. Mixin 风险（HIGH）

`uranus.mixins.json` `defaultRequire:1`：**任何重定向 target 不精确 → 游戏启动即崩溃**。

**可机械迁移（5 个注入点）**：
| Mixin | 26.2 目标 | 变化 |
|---|---|---|
| `ClientWorldMixin` | `ClientLevel.addEntity(Entity)`（仍存在，ClientLevel.java:524） | 类名 |
| `PlayerEntityMixin` | `Player.actuallyHurt(ServerLevel, DamageSource, float)`（体内 Math.max 仍在 :753） | 方法名 applyDamage→actuallyHurt |
| `PlayerInventoryMixin` | `Inventory.tick()`（:242） | 方法名 updateItems→tick |
| `PlayerManagerMixin` | `PlayerList.respawn(ServerPlayer, boolean, RemovalReason)`（:387） | 类名/方法名 |
| `MinecraftServerMixin` `<init>` RETURN | 26.2 单一 public 构造器 | 注入点保留 |

**必须重新设计（9 个注入点）**：
| Mixin | 26.2 目标 / 问题 |
|---|---|
| `ArmorFeatureRendererMixin`（common + fabric + neoforge，3 份） | `ArmorFeatureRenderer` → `HumanoidArmorLayer<S,M,A>`；`renderArmor/renderArmorPiece` 无公开方法（renderArmorPiece 变 private）；VertexConsumerProvider 消失 |
| `BuiltinModelItemRendererMixin` | 类删除 → 重定向 `ItemModel` / `ItemStackRenderState` / `SpecialModelWrapper` / `ItemModelResolver` |
| `ModelLoaderMixin` | `ModelLoader` 删除 → `ModelManager.reload/apply`（返回 CompletableFuture<Void>） |
| `EntityTrackerEntryMixin` | `EntityTrackerEntry` → `ServerEntity`；`startTracking` → `addPairing(ServerPlayer)`（:267） |
| `LevelPropertiesMixin` | `LevelProperties` → `PrimaryLevelData`；`getLifecycle()`/`lifecycle` 删除 → `worldGenSettingsLifecycle()`（语义不同，需确认） |
| `ServerEntityManagerMixin` | → `PersistentEntitySectionManager`；`addEntity` private；`EntityLike` → `EntityAccess` |
| `EntityMixin` | `dropStack` → `spawnAtLocation(ServerLevel, ...)`（ServerLevel 前置） |
| `LivingEntityMixin` | `swingHand`→`swing`；`handleFallDamage`→`causeFallDamage(double,...)` |
| `MinecraftServerMixin` stop | `stop` → `stopServer()`（protected） |

**AccessWidener（common 与 fabric 各一份，构建级风险）**：
- 头 `accessWidener v2 named` → `v2 official`。
- 8 个目标中 4 个失效：`Transformation$Deserializer` / `ModelTransformation$Deserializer`（类删除，删除条目）；
  `DamageSources.registry` → private `damageTypes`（重定向或删除）；`ServerChunkLoadingManager.getChunkHolder(J)`
  → `ChunkMap.getUpdatingChunkIfPresent(J)`。
- `PathNodeMaker.entityBlockXSize/Y/ZSize` → `NodeEvaluator.entityWidth/entityHeight/entityDepth`（重命名）。
- AW 消费方：`AdvancedPathNavigate` / `ChunkCache` / `WorldChunkUtil` 同步改。

## 9. 客户端 / 渲染风险（HIGH，结构性）

26.2 渲染管线是**架构级重构**（render-state + SubmitNodeCollector），不是改名。

- **整个 Tabula/实体模型层级需要重构为 render-state**：`BasicEntityModel` / `BasicModelBase` /
  `TabulaModel` / `ITabulaModelAnimator` 依赖 `EntityModel<T extends EntityRenderState>` +
  `setUpAnim` + final `renderToBuffer`。项目自定义的逐部件 `render()` override 无法保留。
- `HideableLayer`（FeatureRenderer 子类）→ `RenderLayer`，`render(...)` → `submit(PoseStack, SubmitNodeCollector, int, state, float, float)`。
- `TabulaModelHandler` 的 Gson `registerTypeAdapter` 引用了已删除的
  `Transformation$Deserializer` / `ModelTransformation$Deserializer` —— 必须删除该注册。
- **盔甲渲染 SPI 整体过时**：`ArmorModelBase` / `IArmorRenderHelper` / `IArmorRendererBase` /
  `IArmorTextureProvider` 依赖 `ItemRenderer`、`VertexConsumerProvider.getBuffer`、
  `ArmorItem`、`ArmorMaterial.Layer`、`BipedEntityModel.copyBipedStateTo` 等全部删除/私有。
  新路径：`DataComponents.EQUIPPABLE → Equippable.assetId() → EquipmentAssetManager →
  EquipmentClientInfo.Layer.getTextureLocation(LayerType)`，经 `HumanoidArmorLayer.submit` +
  `EquipmentLayerRenderer.renderLayers` + `SubmitNodeCollector.submitModel` + `RenderTypes.armorCutoutNoCull/armorEntityGlint`。
- **动态物品渲染 SPI 过时**：`DynamicItemRenderer` + `BuiltinModelItemRendererMixin` 依赖的
  `BuiltinModelItemRenderer` 删除 → 重建到 `SpecialModelRenderer<T>`（submit/getExtents/extractArgument）
  注册于 `SpecialModelRenderers`（`Identifier → MapCodec<Unbaked<?>>`），经 `SpecialModelWrapper` 接入
  `ItemModel` / `ItemStackRenderState.setupSpecialModel`。
- **纹理加载原语全部删除**：`ArrayLayeredTexture` / `SimpleTexture` 用的 `AbstractTexture.getGlId/load`、
  `TextureUtil.prepareImage`、`NativeImage.upload/blend`、`RenderSystem.recordRenderCall` 均移除
  → 重建为 `ReloadableTexture.loadContents → TextureContents` + `GpuDevice.createCommandEncoder().writeToTexture`。
- `Direction.getUnitVector()` 变不可变 `getUnitVec3f()`（需 copy 后再 .mul）。
- `Registries.ITEM` 现在是 ResourceKey → 用 `BuiltInRegistries.ITEM`。

**风险最高的子目录**：`client/render/armor/`（整套 SPI 重写）、`client/model/`（模型渲染状态化重构）、
`client/render/`（纹理 + 动态物品渲染）。

## 10. 数据生成风险（LOW）

- **项目没有任何 datagen 模块或 datagen 代码**（无 `data/` 生成器、无 datagen source set、无 DatagenGradle 任务）。
- 唯一的 `data/` 包只有 `EntityPropertyDelegate.java`（`PropertyDelegate` → `ContainerData`，
  `size()` → `getCount()`，低风险机械改名）。
- **结论**：本模块无迁移工作，仅需在最终验收确认 `runDatagen` 不适用。

## 11. 世界生成风险（LOW）

- 项目无 worldgen 代码：无 Feature/PlacedFeature/Structure/Biome/Registries.WORLDGEN 使用。
- `ChunkCache.java` 虽涉及区块访问，但那属于 pathfinding 自建寻路视图（LevelReader 实现），
  不是世界生成，见 §14 高风险清单。
- `ServerChunkCache.chunkMap.getUpdatingChunkIfPresent(...)` 是寻路/碰撞模块的访问，非生成逻辑。
- **结论**：无世界生成迁移工作。

## 12. 网络风险（MEDIUM，但范围集中）

- 项目只有一个自定义 payload：`AnimationPayload`，**每个符号都要改**：
  `CustomPayload` → `CustomPacketPayload`；`Id` → `Type`；`getId()` → `type()`；
  `PacketCodec` → `StreamCodec`；`PacketCodecs.codec` → `ByteBufCodecs.fromCodec`；
  `Identifier.of(ns,path)` → `Identifier.fromNamespaceAndPath(ns,path)`。
- 26.2 推荐写法（已给出可直接套用的模板，仿 `BrandPayload`）：

```java
record AnimationPayload(int entityID, int index) implements CustomPacketPayload {
    private static final Identifier ANIMATION = Identifier.fromNamespaceAndPath(Uranus.MOD_ID, "animation");
    public static final Type<AnimationPayload> ID = new Type<>(ANIMATION);
    public static final StreamCodec<ByteBuf, AnimationPayload> CODEC =
        ByteBufCodecs.fromCodec(RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("entityID").forGetter(AnimationPayload::entityID),
            Codec.INT.fieldOf("index").forGetter(AnimationPayload::index)
        ).apply(instance, AnimationPayload::new)));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }
}
```

- 发送/接收链路：`ServerHelper`（`getPlayerManager().getPlayerList()` → `getPlayerList().getPlayers()`）、
  `UranusClient`（`MinecraftClient.getInstance()` → `Minecraft.getInstance()`，`world` → `level`，
  `getEntityById` → `getEntity`）。
- Arch `NetworkManager` 侧签名不变（§6），可保留现有 split 模式或改用 `registerS2C`。
- **结论**：风险被限定在 3 个文件（AnimationPayload + ServerHelper + UranusClient），规则清晰、风险可控。

## 13. 预计迁移阶段

依据 AGENT.md 工作流，本项目的实际阶段排序（含子代理已给出的模块内顺序）：

| 阶段 | 内容 | 风险 |
|---|---|---|
| **0**（当前） | 项目调查 + 源码分析 → 本报告 + 7 项摘要 → 等确认 | — |
| **1** | 建立 `docs/yarn-to-mojmap-mapping.md`（只记录实际用到的映射） | — |
| **2** | 确认自动 Mapping Migration：**判定不可行**（loom-no-remap 无 mappings 层；跨两代 + yarn→official + 大量结构 API 破坏）。若用户要求仍尝试，以 `./gradlew migrateMappings` 为前提做 git checkpoint 后试跑，仅作基线不依赖 | 低 |
| **3** | **构建系统先行**：loom-no-remap 1.17 / shadow 9.4.3 / Gradle 9.5.1 / Java 25 / com.mojang:minecraft:26.2 / 删 mappings 块 / remapJar 删除 / AW 转 AT / 元数据版本范围 / pack_format / JAVA_25 | 高（编译前置） |
| **4** | **Common 核心**：`object/` 工具（RegistryHelper、DamageUtil、EntityUtil、FoodUtils、SoundUtil、ToolMaterialUtil）、`item/`（ArmorItem/SwordItem 重构）、`network/`（AnimationPayload）、`event/`、`ServerHelper`、`Uranus` | 高 |
| **5** | **Pathfinding/Collision**：AbstractPathJob（Node.cameFrom）→ AdvancedPathNavigate（DebugInfoSender、adjustPath、Path API）→ ChunkCache（LevelReader）→ CustomCollisions×3 → 其余 support | 高 |
| **6** | **客户端模型**：BasicEntityModel/Tabula 系列 render-state 化、HideableLayer→RenderLayer、TabulaModelHandler（Gson 删除）、VertexConsumer/PoseStack/Axis/Mth 机械改名 | 高 |
| **7** | **客户端渲染**：盔甲 SPI 重建（Equippable/EquipmentLayerRenderer）、DynamicItemRenderer→SpecialModelRenderer、纹理加载重建 | 高 |
| **8** | **Mixin**：5 个机械迁移 + 9 个重设计（见 §8）+ accesswidener 更新 | 高 |
| **9** | **平台**：fabric/neoforge 入口验证（预期零改动）+ 元数据 + mixin 配置 | 低 |
| **10** | **最终验收**：`./gradlew clean build`；若有 runClient/runServer 则冒烟；产出 `docs/migration-report.md` | — |

每模块循环：编译 → 统计错误数/分类 → 修复 → 再编译，直到零错误。提交策略：Phase 0 结束先 git checkpoint，此后每模块一次 commit。

## 14. 高风险文件清单（迁移工作量集中地）

| 文件 | 行数 | 主要破坏点 | 建议优先级 |
|---|---|---|---|
| `pathfinding/raycoms/pathjobs/AbstractPathJob.java` | 1186 | `Node.previous`→`cameFrom`；Path API 改名族；World→Level 贯穿 | 1 |
| `pathfinding/raycoms/AdvancedPathNavigate.java` | 791 | `DebugInfoSender` 删除(:285)；`adjustPath()` 删除；Path/PathNavigation 字段与方法改名族；`Level.random` protected | 1 |
| `client/render/armor/ArmorModelBase.java` (+IArmorRenderHelper/IArmorRendererBase/IArmorTextureProvider) | ~150 | 盔甲渲染 SPI 整体过时（ArmorItem、ItemRenderer、BipedEntityModel、ArmorMaterial.Layer 全删除）→ Equippable/EquipmentLayerRenderer 重写 | 1 |
| `client/model/AdvancedEntityModel.java` / `basic/BasicEntityModel.java` / `TabulaModel.java` | 256/小/小 | `EntityModel<T extends EntityRenderState>` + final `renderToBuffer` → 模型层级状态化重构 | 1 |
| `client/render/DynamicItemRenderer.java` (+BuiltinModelItemRendererMixin) | 小 | BuiltinModelItemRenderer 删除 → SpecialModelRenderer/ItemModel 重建 | 1 |
| `client/render/ArrayLayeredTexture.java` / `SimpleTexture.java` | 小 | 纹理上传原语全删 → ReloadableTexture/GpuDevice 重建 | 1 |
| `pathfinding/raycoms/ChunkCache.java` | 243 | LevelReader ~22 个 override 改名 + 2 个签名变化 + ChunkHolder/ChunkResult/RegistryAccess.EMPTY/FeatureFlagSet.of() | 2 |
| `pathfinding/raycoms/PathingStuckHandler.java` | 532 | `hurt`→`hurtServer(ServerLevel,...)`；DamageSource(Holder) ctor | 2 |
| `object/RegistryHelper.java` | 小 | `RegistryKeys.ARMOR_MATERIAL` 删除；`get/getEntry` 删除 → lookupOrThrow/getValueOrThrow/wrapAsHolder | 2 |
| `object/item/SwordItemBase.java` / `FoilSwordItemBase.java` / `ArmorWithTickItem.java` | 小 | SwordItem/ArmorItem 类删除 → `Item.Properties().sword(...)` / `.humanoidArmor(...)` 重构 | 2 |
| `object/item/ToolMaterialUtil.java` | 小 | ToolMaterial 变 record；repair 槽位变 TagKey<Item> | 2 |
| `object/EntityUtil.java` | 小 | EntityType.create 需要 EntitySpawnReason；finalizeSpawn/snapTo/addFreshEntity/EntityTypes.LIGHTNING_BOLT/setVisualOnly | 2 |
| `object/item/FoodUtils.java` | 小 | `Entity.getWidth()/getHeight()` 删除 → getDimensions(Pose) | 2 |
| `object/SoundUtil.java` | 小 | 客户端音效需 `ClientLevel.playLocalSound`（8 参 playSound private） | 2 |
| `client/model/TabulaModelHandler.java` | 小 | Gson registerTypeAdapter 引用已删除的 Transformation/ModelTransformation Deserializer | 2 |
| `client/model/util/HideableLayer.java` | 小 | FeatureRenderer→RenderLayer + render→submit | 2 |
| `network/AnimationPayload.java` | 小 | 全符号改写（见 §12 模板） | 2 |
| 13 个 mixin + 2 个平台 ArmorFeatureRendererMixin | 小 | 5 机械 + 9 重设计（见 §8） | 2 |
| `uranus.accesswidener`（common + fabric） | 10 行 | v2 named→official；4/8 目标失效（见 §8） | 1（构建前置） |
| `build.gradle` / `gradle.properties` / `fabric/build.gradle` / `neoforge/build.gradle` | — | loom-no-remap 全家桶迁移（见 §2） | 1（构建前置） |

---

## 附录：映射策略总纲

1. **以 26.2 官方 Mojmap 为准**（AGENT.md 优先级：MC 26.2 Mojmap > Architectury 26.2 > NeoForge 26.2 > MC 1.21.1 Yarn > 原模组）。
2. **禁止机械全局替换**：优先语义迁移；同名包移动类（Level/Entity 包移动、Mth 改名）可批量，但每个都需过一遍。
3. **每次 API 使用都回查参考源码**：minecraft26.2（7055 文件）、architectury-api26.2、NeoForge26.2、fabric-loader-0.19.3、1.21.1 mojmap（5363 文件，作为 1.21.1→26.2 的中间参照）。
4. **能覆盖的用 Architectury 26.2 事件**（EntityEvent.ADD / PlayerEvent.* / LifecycleEvent.* / TickEvent.PLAYER_POST），
   但 tracking/fall/伤害量修改必须保留自定义 mixin。
5. **禁止**：删除功能换编译、注释掉代码、无意义强转、raw type、@SuppressWarnings 规避、NeoForge API 泄漏进 common、猜 API。
6. Phase 1 的 mapping 表不是唯一依据——**每处改动仍需对照 26.2 源码**。
