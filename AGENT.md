# AGENTS.md

# Uranus — Minecraft 1.21.1 Yarn Architectury → Minecraft 26.2 Mojmap 迁移规范

> 本文件是本项目 AI Agent（Claude Code / Codex 等）进行代码迁移时的最高优先级项目规范。
>
> **核心目标：**
>
> 将 Uranus 从：
>
> `Minecraft 1.21.1 + Yarn + Architectury 13.0.8`
>
> 完整迁移到：
>
> `Minecraft 26.2 + Official Mojmap + Architectury 21.0.x`
>
> 同时最大程度保持原 Mod 的游戏逻辑、功能和行为。
>
> **原则：API 以目标版本为准，行为以原项目为准。**

---

# 1. 项目概况

## 1.1 源项目

项目：Uranus

源环境：

* Minecraft 1.21.1
* Yarn mappings
* Architectury 13.0.8
* Gradle 8.10
* Java 21
* Architectury Loom 1.7-SNAPSHOT
* Architectury Plugin 3.4
* Shadow 8.1.1

项目结构：

```text
Uranus
├── common/
│   ├── animation/
│   ├── event/
│   ├── mixin/
│   ├── network/
│   ├── object/
│   │   ├── item/
│   │   ├── entity/
│   │   └── ...
│   └── util/
├── fabric/
└── neoforge/
```

规模：

* Java 文件：145
* 总 Java 代码：约 10,593 行
* common：139 文件 / 10,465 行
* Fabric：3 文件 / 65 行
* NeoForge：3 文件 / 63 行

重要特征：

* 没有 Datagen 模块
* 没有 Worldgen 模块
* 资源文件较少
* common 中包含完整客户端 model/render 代码
* 包含 Tabula 动画系统
* 包含自研 Event 总线
* 包含自定义 AnimationPayload 网络包
* 包含 13 个 Mixin
* 寻路 / 碰撞系统约占 common 代码的 40%
* 深度修改 Minecraft 原版寻路、碰撞及实体行为

因此，本项目不是普通的简单 Mapping Migration。

---

# 2. 目标环境

目标环境必须严格遵循以下配置：

```text
Minecraft:       26.2
Mappings:        Official / Mojmap
Java:            25
Gradle:          9.5.1

Architectury:     21.0.x
Architectury API: 26.2

Fabric Loader:    0.19.3
Fabric API:       0.154.2+26.2

NeoForge:         26.2.0.43-beta

Loom:
dev.architectury.loom-no-remap: 1.17-SNAPSHOT

Architectury Plugin:
3.5

Shadow:
com.gradleup.shadow 9.4.3
```

目标项目仍然保持：

```text
common/
fabric/
neoforge/
```

除非用户明确要求，否则：

* 不删除 Fabric 模块
* 不删除 NeoForge 模块
* 不删除 Architectury
* 不将项目改造成纯 NeoForge
* 不改变模块职责

---

# 3. 权威参考源码

迁移过程中提供以下完整源码：

```text
Minecraft 1.21.1 Yarn:
D:\aiminecraftdev\minecraft 1.21.1 yarn

Minecraft 1.21.1 Mojmap:
D:\aiminecraftdev\minecraft1.21.1mojmap

Minecraft 26.2 Mojmap:
D:\aiminecraftdev\minecraft26.2

NeoForge 1.21.1:
D:\aiminecraftdev\NeoForge1.21.1

NeoForge 26.2:
D:\aiminecraftdev\NeoForge26.2

Fabric Loader 0.19.3:
D:\aiminecraftdev\fabric-loader-0.19.3

Fabric API 1.21.1:
D:\aiminecraftdev\fabric-api1.21.1

Architectury API 1.21:
D:\aiminecraftdev\architectury-api-1.21

Architectury API 26.2:
D:\aiminecraftdev\architectury-api26.2
```

如果这些路径实际存在，Agent 必须优先使用这些源码进行验证。

---

# 4. Reference Source 优先级

## 4.1 Minecraft 26.2 Mojmap

这是目标 Minecraft API 的最高优先级参考。

用于确认：

* 类
* 方法
* 字段
* 构造器
* 参数顺序
* 返回值
* 泛型
* Registry
* Holder
* ResourceKey
* ResourceLocation
* Component
* Entity
* Level
* Item
* Block
* Rendering
* Networking
* Data Components
* Codec
* Mixin target

**任何目标 API 不确定时，必须先查这里。**

禁止：

> “我记得 26.2 应该还有这个 API。”

必须：

```text
grep / rg
↓
打开目标源码
↓
确认真实签名
↓
再修改代码
```

---

# 5. Architectury API 26.2

Architectury API 是 common 层的重要抽象来源。

当某项功能需要跨 Fabric / NeoForge 时：

```text
Minecraft API
↓
Architectury API
↓
平台实现
```

如果 Architectury 26.2 已经提供合适的抽象：

> 优先使用 Architectury。

重点检查：

* Registry
* Event
* LifecycleEvent
* EntityEvent
* PlayerEvent
* Network
* Menu
* Screen
* Client
* Platform
* Config
* Transfer
* Rendering
* Common API

但不得为了使用 Architectury 而强行改变原 Mod 的设计。

---

# 6. NeoForge 26.2

NeoForge 26.2 是目标平台实现的重要参考。

用于：

* NeoForge API
* 事件
* 注册
* Networking
* Rendering
* Client
* Access Transformer
* Mixin / Injection target
* Architectury NeoForge backend
* 平台特有实现

但是：

> NeoForge API 不是 Common API。

Common 中禁止直接引入：

```java
net.neoforged.*
```

除非该文件明确属于 NeoForge 平台模块。

---

# 7. 源版本参考

Minecraft 1.21.1 Yarn 用于：

* 理解原代码
* 确认旧 API
* 理解旧方法语义
* 分析旧 Mixin
* 分析旧 Minecraft 行为
* 分析原有寻路逻辑
* 分析原有渲染逻辑

Minecraft 1.21.1 Mojmap 用于：

> 作为 Yarn → Mojmap 的中间语义参考。

它不是目标 API。

---

# 8. 总体原则

## 8.1 API 以目标版本为准

```text
1.21.1 Yarn
    ↓
理解原 API
    ↓
Minecraft 26.2 Mojmap
    ↓
Architectury 26.2
    ↓
NeoForge 26.2
    ↓
实现
```

## 8.2 行为以原项目为准

迁移过程中不得因为 API 变化而无理由改变：

* 游戏逻辑
* 数值
* 算法
* AI 行为
* 寻路行为
* 碰撞行为
* 动画行为
* 网络行为
* 物品行为
* 渲染效果

如果目标 API 无法直接表达原实现：

> 使用 26.2 的正确架构重新实现原行为。

---

# 9. 严禁机械 Mapping

禁止：

```text
YarnName → MojmapName
```

全局替换。

例如：

```text
Identifier
Text
World
ServerWorld
ClientWorld
MobEntity
PlayerEntity
RegistryEntry
PacketByteBuf
```

都必须重新检查。

Mapping 只解决：

> “这个名字对应什么？”

不能解决：

> “目标版本 API 是否仍然存在？”

更不能解决：

> “目标版本架构是否已经改变？”

---

# 10. 不以 migrateMappings 作为主迁移方案

本项目属于：

```text
Minecraft 1.21.1
Yarn
        ↓
Minecraft 26.2
Official Mojmap
```

这是跨版本、跨 Mapping、跨渲染/网络/物品架构的大规模迁移。

目标项目还使用：

```text
loom-no-remap
```

因此：

> 不得假设 `migrateMappings` 可以完成整个迁移。

如果项目存在 Mapping Migration 工具，可以作为辅助工具。

但最终迁移必须人工根据：

```text
Minecraft 26.2 source
Architectury 26.2 source
NeoForge 26.2 source
```

完成。

---

# 11. 目标构建系统

构建系统必须优先迁移。

目标：

```text
loom-no-remap
+
Architectury Plugin 3.5
+
Shadow 9.4.3
+
Gradle 9.5.1
+
Java 25
```

目标项目不再使用 Yarn mappings 块。

删除：

```text
mappings
```

相关旧配置。

同时：

```text
remapJar
```

不再作为最终产物流程。

目标：

```text
shadowJar
```

直接生成最终 Jar。

---

# 12. Access Widener / Access Transformer

源项目使用：

```text
Access Widener v2
named
```

目标项目：

```text
official
```

迁移时必须逐条验证。

不能简单修改头部：

```text
named → official
```

必须确认：

* target class
* method
* field
* descriptor
* visibility
* target 是否仍然存在

如果目标 NeoForge 机制要求：

```text
Access Transformer
```

则根据 NeoForge 26.2 实际机制进行迁移。

---

# 13. 目标 API 已知重大变化

以下变化必须特别注意。

## 13.1 DamageSource

构造参数语义发生变化。

必须检查：

```text
directEntity
causingEntity
```

禁止仅按照参数数量迁移。

这是高风险语义错误。

---

## 13.2 Damageable API

注意：

```text
hurt()
```

可能变为：

```text
hurtServer(...)
```

以及：

```text
applyDamage()
```

可能对应：

```text
actuallyHurt()
```

必须以 26.2 源码实际实现为准。

---

# 14. Item 系统

目标版本中：

```text
ArmorItem
SwordItem
```

等旧类级体系可能已经被重构或删除。

尤其注意：

```text
ArmorMaterial
ToolMaterial
```

已经发生结构变化。

必须检查：

* Item.Properties
* humanoidArmor
* sword
* EquipmentAsset
* EquipmentAssetManager
* DataComponents
* Equippable

禁止为了保留旧继承关系而创造无意义兼容层。

---

# 15. Registry / Holder

重点检查：

```text
Registry.get
Registry.getEntry
RegistryEntry
RegistryKeys
```

以及：

```text
Holder
Holder.Reference
HolderSet
ResourceKey
TagKey
```

目标版本可能使用：

```text
lookupOrThrow()
getValueOrThrow()
Holder
```

代替旧 API。

禁止通过：

```java
(Object)
```

或 raw cast：

```java
(Holder) value
```

规避类型错误。

---

# 16. ResourceLocation

Yarn：

```text
Identifier
```

通常对应：

```text
ResourceLocation
```

但创建方式必须重新检查。

例如：

```text
of()
```

是否仍存在必须以 26.2 源码为准。

重点确认：

* namespace
* path
* parsing
* registry key
* tag
* codec
* factory method

---

# 17. 网络

Uranus 当前只有一个核心自定义网络包：

```text
AnimationPayload
```

因此网络迁移应集中围绕该 Payload 完成。

注意：

```text
CustomPayload
→
CustomPacketPayload

Id
→
Type

PacketCodec
→
StreamCodec

PacketByteBuf
→
FriendlyByteBuf
```

但这些只作为调查线索。

最终必须以 26.2 实际 API 为准。

优先检查：

```text
Architectury 26.2 Network
Minecraft 26.2 CustomPacketPayload
NeoForge 26.2 networking
```

---

# 18. Event 系统

Uranus 自己存在：

```text
common/event/
```

这是项目核心组件。

迁移时：

> 不得直接用 Architectury Event 替换掉 Uranus 自研 Event。

必须首先理解：

* Event 总线结构
* Event 注册方式
* Event listener 生命周期
* Event 参数
* 调用位置
* 线程模型

然后判断：

```text
是否应该继续保留 Uranus Event
```

如果 Architectury 26.2 有合适的底层事件：

```text
Architectury Event
```

可以作为 Minecraft / Loader 事件入口。

但不得改变 Uranus 自身 Event 的行为。

---

# 19. 寻路 / 碰撞系统

这是 Uranus 最大的迁移风险之一。

寻路 / 碰撞相关代码约占 common 的 40%。

重点模块：

```text
AbstractPathJob
AdvancedPathNavigate
ChunkCache
CustomCollisions
GroundPathNavigation
WalkNodeEvaluator
Node
Path
```

必须优先处理。

---

# 20. 寻路迁移原则

不得仅进行：

```text
previous → cameFrom
```

这种机械替换。

必须检查完整 API。

例如：

```text
Node.previous
→
Node.cameFrom

Path.getLength()
→
Path.getNodeCount()

Path.next()
→
Path.advance()
```

以及：

```text
PathfindingContext
EntityAccess
Level.random
```

等变化。

每个变化必须检查目标源码。

---

# 21. DebugInfoSender

目标版本：

```text
DebugInfoSender
```

可能已经删除。

Uranus 原寻路调试代码如果依赖它：

> 必须判断该调试功能是否属于 Mod 核心行为。

如果确实没有目标 API：

* 不得伪造不存在的 API
* 不得无意义返回 null
* 不得留下 UnsupportedOperationException

可以在 migration notes 中明确记录：

```text
目标 API 删除
→
原功能无法直接迁移
→
确认是否为开发调试功能
→
采用目标版本合理替代或移除该调试路径
```

---

# 22. CustomCollisions

这是高风险模块。

重点重新验证：

```text
canMoveDirectly(...)
isSafeToStandAt(...)
isPositionClear(...)
```

不能根据旧 Yarn 签名猜测。

如果目标版本删除尺寸参数：

> 必须重新分析 26.2 碰撞 API 和实体尺寸获取方式。

---

# 23. Tabula / Animation

Uranus 使用自定义 Tabula 动画系统。

这是独立高风险区域。

不得简单删除动画逻辑。

必须分别分析：

```text
Animation
Model
ModelPart
PoseStack
EntityModel
RenderState
```

如果目标 26.2 已经将渲染体系改为：

```text
RenderState
SubmitNodeCollector
```

必须将 Tabula 的渲染输出适配到目标架构。

目标：

> 保留动画数据、动画计算和最终视觉行为。

而不是：

> 让旧 `renderToBuffer` 代码勉强编译。

---

# 24. Client Rendering

这是本项目最高风险区域之一。

目标 26.2 发生了 render-state 架构变化。

重点关注：

```text
VertexConsumerProvider
SubmitNodeCollector

EntityModel
RenderState

FeatureRenderer
RenderLayer.submit

Transformation
ModelTransformation
```

以及：

```text
Armor rendering
Item rendering
Texture loading
```

---

# 25. Armor Rendering

旧体系：

```text
ArmorItem
ArmorMaterial
ArmorMaterial.Layer
BipedEntityModel
```

目标可能采用：

```text
DataComponents.EQUIPPABLE
EquipmentAssetManager
EquipmentLayerRenderer
HumanoidArmorLayer.submit
```

迁移时必须按 26.2 实际架构重新实现。

禁止：

> 为了保留 ArmorItem 继承关系自行创建一个假的 ArmorItem。

---

# 26. Dynamic Item Rendering

如果旧代码依赖：

```text
BuiltinModelItemRenderer
```

必须检查 26.2：

```text
SpecialModelRenderer
SpecialModelRenderers
ItemModel
```

重新设计动态物品渲染。

---

# 27. Texture API

旧 API 可能包括：

```text
AbstractTexture
TextureUtil.prepareImage
NativeImage.upload
RenderSystem.recordRenderCall
```

目标版本可能改为：

```text
ReloadableTexture
TextureContents
GpuDevice
```

必须按照 26.2 的 GPU / texture 生命周期重写。

禁止通过创建旧 API 兼容包装类来模拟不存在的 Minecraft 内部 API。

---

# 28. Mixin

Uranus 当前：

```text
13 mixins
```

其中：

```text
9 server-side
4 client-side
```

Mixin 必须逐个迁移。

对每个 Mixin 建立：

```text
Mixin
├── Target class
├── Target method
├── Injection point
├── Method descriptor
├── Callback signature
├── 原修改逻辑
└── 26.2 新实现
```

必须重新确认：

```text
旧 Yarn target
↓
1.21.1 Mojmap
↓
26.2 Mojmap
↓
26.2 实际方法
```

禁止：

> 只修改 target class 名称就认为 Mixin 已迁移。

---

# 29. Mixin Require

目标项目如果使用：

```text
defaultRequire: 1
```

则必须特别谨慎。

如果 target 不精确：

```text
Mixin apply failure
```

可能直接导致游戏启动崩溃。

每一个 injection 都必须验证实际匹配。

---

# 30. Fabric / NeoForge 架构

Common：

```text
Minecraft
+
Architectury
+
项目自身代码
```

不得：

```java
import net.neoforged.*;
```

或者：

```java
import net.fabricmc.*;
```

平台代码：

```text
fabric/
neoforge/
```

负责平台差异。

推荐：

```text
common/
    XxxPlatform.java

fabric/
    XxxPlatformImpl.java

neoforge/
    XxxPlatformImpl.java
```

而不是把平台 API 塞进 common。

---

# 31. Fabric 模块

目标项目保留 Fabric。

除非用户明确要求删除，否则不得：

* 删除 Fabric
* 删除 Fabric entrypoint
* 删除 Fabric mixin
* 删除 Fabric metadata
* 将 Fabric 代码移动到 common

---

# 32. NeoForge 模块

NeoForge 模块可以使用：

```text
net.neoforged.*
```

但应尽量保持平台实现最小化。

平台差异应封装，而不是传播到 common。

---

# 33. Resources

必须检查：

```text
src/main/resources
src/fabric/resources
src/neoforge/resources
```

重点：

```text
fabric.mod.json
neoforge.mods.toml
mixins
lang
models
textures
item definitions
tags
sounds
```

目标 metadata 必须符合：

```text
pack_format = 88
loaderVersion = [1,)
NeoForge = [26.2,)
Java = 25
```

具体字段以目标 Loader / NeoForge 26.2 实际规范为准。

---

# 34. Datagen / Worldgen

Uranus 当前：

```text
无 Datagen 模块
无 Worldgen 模块
```

因此：

> 不得为了套用通用迁移流程而创建 Datagen / Worldgen。

只有当后续发现项目实际存在相关代码时才处理。

---

# 35. Phase 0 — 调查

**禁止修改代码。**

首先分析：

```text
Git
Gradle
common
fabric
neoforge
resources
Mixin
Registry
Event
Network
Animation
Pathfinding
Collision
Rendering
```

输出：

```text
docs/migration-analysis.md
```

至少记录：

* 文件数量
* 代码行数
* 模块结构
* Gradle
* 依赖
* Registry
* Event
* Network
* Mixin
* Animation
* Pathfinding
* Collision
* Rendering
* Resources
* 高风险 API
* 删除 API
* 平台差异

Phase 0 完成后建立 Git checkpoint。

---

# 36. Phase 1 — 建立 Mapping

建立：

```text
docs/yarn-to-mojmap-mapping.md
```

只记录：

> Uranus 实际使用到的 API。

不要制作整个 Minecraft 的 mapping 表。

记录：

```text
Class
Method
Field
Constructor
API migration
Semantic change
```

推荐格式：

```markdown
## Identifier → ResourceLocation

Source:
net.minecraft.util.Identifier

Target:
net.minecraft.resources.ResourceLocation

Change:
creation API changed

Reason:
26.2 removed the old factory

Reference:
Minecraft 26.2 source
```

---

# 37. Phase 2 — 构建系统

先完成：

```text
Gradle 9.5.1
Java 25
loom-no-remap
Architectury Plugin 3.5
Shadow 9.4.3
Minecraft 26.2
Architectury 21.x
Fabric Loader 0.19.3
Fabric API 0.154.2+26.2
NeoForge 26.2.0.43-beta
```

删除：

```text
Yarn mappings
remapJar
旧 Loom mappings 配置
```

然后确认：

```bash
./gradlew tasks
```

以及基础：

```bash
./gradlew compileJava
```

---

# 38. Phase 3 — Common 核心

迁移顺序：

```text
util
↓
object
↓
item
↓
event
↓
network
↓
entity
```

先解决基础类型和 API。

---

# 39. Phase 4 — Pathfinding / Collision

严格按照依赖关系：

```text
AbstractPathJob
↓
AdvancedPathNavigate
↓
ChunkCache
↓
CustomCollisions
↓
Node / Path
↓
Minecraft navigation API
```

每完成一个核心类：

```text
compile
↓
error count
↓
semantic check
```

---

# 40. Phase 5 — Animation / Model

处理：

```text
Tabula
Animation
Model
ModelPart
EntityModel
```

目标是：

> 保留动画计算和视觉行为。

---

# 41. Phase 6 — Client Rendering

处理：

```text
RenderState
SubmitNodeCollector
Model
Armor
Item
Texture
Renderer
```

由于 26.2 渲染体系发生架构变化：

> 可以重写实现，但不得删除功能。

---

# 42. Phase 7 — Mixin

依次处理：

```text
Mixin target
↓
Injection point
↓
Callback
↓
Access
↓
Runtime validation
```

对于每一个 Mixin：

```text
编译成功
≠
Mixin 成功
```

必须最终进行运行测试。

---

# 43. Phase 8 — Platform

分别验证：

```text
Fabric
NeoForge
```

确认：

```text
entrypoint
registry
event
network
client
mixin
metadata
```

---

# 44. Phase 9 — Resources

验证：

```text
models
textures
lang
tags
item definitions
sounds
mixins
metadata
```

Java 编译成功不代表迁移完成。

---

# 45. Phase 10 — 完整验证

至少执行：

```bash
./gradlew clean build
```

如果任务存在：

```bash
./gradlew runClient
./gradlew runServer
```

必须进行测试。

---

# 46. 编译错误量化

每次重要修改后必须统计错误数量。

例如：

```text
Initial:
523 errors

After Registry:
381 errors

After Entity:
207 errors

After Pathfinding:
72 errors

After Rendering:
12 errors

Final:
0 errors
```

禁止仅报告：

> “修复了大量错误。”

必须提供数字。

---

# 47. 错误修复顺序

推荐：

```text
1. Gradle
2. Dependency
3. Mapping
4. Basic Minecraft API
5. Registry
6. Common
7. Item
8. Entity
9. Network
10. Event
11. Pathfinding
12. Collision
13. Animation
14. Rendering
15. Mixin
16. Platform
17. Resources
18. Runtime
```

不要同时大规模修改所有模块。

---

# 48. 遇到 API 不确定时

必须遵循：

```text
不知道
↓
搜索 26.2 source
↓
找到定义
↓
读取调用方
↓
确认语义
↓
修改
```

如果仍然不确定：

```text
检查 Architectury
↓
检查 NeoForge
↓
检查 1.21.1 Mojmap
↓
检查 1.21.1 Yarn
```

禁止凭记忆猜 API。

---

# 49. 禁止行为

## 49.1 禁止删除功能

禁止：

```java
return null;
```

作为迁移方案。

禁止：

```java
throw new UnsupportedOperationException();
```

作为长期方案。

---

## 49.2 禁止大规模注释代码

禁止：

```java
/*
 * old implementation
 */
```

来规避编译错误。

---

## 49.3 禁止无意义强转

禁止：

```java
(Object) value
```

或者：

```java
(SomeType) value
```

仅为了通过编译。

---

## 49.4 禁止 Raw Type

禁止：

```java
List list
Map map
Holder holder
```

除非目标 API 本身明确要求。

---

## 49.5 禁止 SuppressWarnings 逃避问题

禁止大量：

```java
@SuppressWarnings("unchecked")
@SuppressWarnings("rawtypes")
```

---

## 49.6 禁止伪造旧 API

不要为了保留旧代码而自己创造：

```text
假的 ArmorItem
假的 SwordItem
假的 PacketCodec
假的 VertexConsumerProvider
```

如果目标 API 删除了旧架构：

> 按目标架构重写。

---

## 49.7 禁止 NeoForge 泄漏进 Common

Common 禁止：

```java
net.neoforged.*
```

---

# 50. 功能完整性

迁移过程中始终优先级：

```text
原始功能
>
行为一致性
>
目标 API 正确性
>
代码结构相似
```

代码不需要长得像旧代码。

如果 26.2 架构发生根本变化：

> 允许重构。

但：

> 不允许无理由删除原功能。

---

# 51. Migration Notes

重大 API 迁移记录到：

```text
docs/migration-notes.md
```

例如：

```markdown
## Armor Rendering

### Old
ArmorItem + BipedEntityModel

### New
EquipmentAssetManager + EquipmentLayerRenderer

### Reason
Minecraft 26.2 changed armor rendering architecture.

### Behavioral requirement
Preserve Uranus armor visual appearance.

### References
- Minecraft 26.2
- Architectury API 26.2
```

---

# 52. 每个阶段的完成条件

一个阶段只有满足：

```text
代码修改完成
↓
compile
↓
错误数量统计
↓
语义检查
↓
没有明显临时方案
```

才可以进入下一阶段。

---

# 53. Git Checkpoint

开始迁移前：

```bash
git status
git branch
git log -5
```

Phase 0 完成后：

> 建立 Git checkpoint。

重大阶段完成后建议：

```text
Phase 1
Phase 2
Phase 3
...
```

分别建立 checkpoint。

不要在没有 checkpoint 的情况下连续进行数千行高风险修改。

---

# 54. Agent 工作纪律

开始工作：

1. 阅读 `AGENTS.md`
2. 阅读 README
3. 检查 Git
4. 检查 Gradle
5. 检查 reference source
6. 分析项目结构
7. 检查当前编译状态
8. 制定当前 Phase 计划

然后才开始修改。

---

# 55. 不允许未经确认扩大任务范围

如果当前任务是：

```text
迁移 Registry
```

不要同时：

```text
重写 Rendering
重写 Pathfinding
重写 Mixin
```

除非当前修改确实导致这些模块成为必要依赖。

---

# 56. 修改前后必须验证

每次重要修改：

```text
Before
↓
修改
↓
Compile
↓
Error count
↓
Compare
```

如果错误数量突然增加：

> 必须分析原因，不允许继续盲目修改。

---

# 57. 重要决策原则

当存在多个实现方案：

优先：

```text
1. 26.2 原生 API
2. Architectury API
3. 目标平台 API
4. 自己实现适配层
5. 兼容旧结构
```

但是必须同时满足：

```text
功能不丢失
行为尽量一致
架构合理
Common / Platform 边界清晰
```

---

# 58. 最终验收

必须满足：

```text
[ ] Minecraft 26.2
[ ] Official Mojmap
[ ] Architectury 21.x
[ ] Java 25
[ ] Gradle 9.5.1

[ ] Common 编译成功
[ ] Fabric 编译成功
[ ] NeoForge 编译成功

[ ] Animation 正常
[ ] Pathfinding 正常
[ ] Collision 正常
[ ] Networking 正常
[ ] Event 正常
[ ] Item 正常
[ ] Rendering 正常
[ ] Mixin 正常

[ ] Resources 正常
[ ] Metadata 正常

[ ] ./gradlew clean build 成功

[ ] Client 可以启动
[ ] Server 可以启动

[ ] 没有临时 TODO
[ ] 没有 UnsupportedOperationException 迁移占位
[ ] 没有大量注释代码
[ ] 没有无意义 cast
[ ] 没有 Raw Type
[ ] 没有无意义 SuppressWarnings
[ ] Common 没有 NeoForge/Fabric API 泄漏
[ ] 原有核心功能没有被删除
```

---

# 59. 最终目标

本项目最终不是：

> 把 Yarn 名字替换成 Mojmap 名字。

也不是：

> 让 `compileJava` 通过。

而是：

> **得到一个真正符合 Minecraft 26.2 + Official Mojmap + Architectury 21.x 架构，并尽可能保持 Uranus 原有功能、行为、动画、寻路、碰撞、网络和渲染效果的现代化项目。**

核心原则：

```text
查源码，不猜 API。

理解行为，再迁移 API。

目标架构发生变化时，正确重构。

可以重写实现，但不能无理由删除功能。

编译成功只是中间目标，不是最终目标。
```
