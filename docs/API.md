# MaidMoreWork 公开 API 参考

> 本文档仅列出允许外部模块调用的 API，内部实现类不在此列。

---

## 一、搜索框架

### SearchBehavior — 通用螺旋搜索行为

继承 TLM `Behavior<EntityMaid>`，从女仆当前位置出发按切比雪夫距离逐层螺旋遍历坐标点，每点调用一次 `scanAction`。找到目标后再通过 `onFound` 回调处理工程创建等后续逻辑。

两种搜索模式：`useHomeRestriction=true` 限定家园范围（耗尽后静默等待），`=false` 限定固定范围（耗尽后随机游荡换地方）。

```java
import com.fennecmomo.maidmorework.search.SearchBehavior;
```

#### 构造（仅 scanAction）

```java
public SearchBehavior(ISearchAction scanAction, MemoryModuleType<?> targetMemory,
                      int scanHalfXZ, int scanYDown, int scanYUp, boolean useHomeRestriction)
```

| 参数 | 含义 |
|---|---|
| scanAction | 单点检索回调，判断当前坐标是否为目标 |
| targetMemory | 目标 Memory，为空时可启动，有值时搜索结束 |
| scanHalfXZ | 非家园模式 XZ 半边长 |
| scanYDown | 非家园模式 Y 向下范围 |
| scanYUp | 非家园模式 Y 向上范围 |
| useHomeRestriction | 是否受家园范围限制 |

#### 构造（带前置检索）

```java
public SearchBehavior(ISearchAction scanAction, ISearchAction preSearch,
                      MemoryModuleType<?> targetMemory,
                      int scanHalfXZ, int scanYDown, int scanYUp, boolean useHomeRestriction)
```

| 参数 | 含义 |
|---|---|
| scanAction | 单点检索回调 |
| preSearch | 前置检索回调（螺旋前优先查找孤儿工程，可为 null） |
| targetMemory | 目标 Memory |
| scanHalfXZ | XZ 半边长 |
| scanYDown | Y 向下范围 |
| scanYUp | Y 向上范围 |
| useHomeRestriction | 是否家园限制 |

#### 完整构造（含 onFound）

```java
public SearchBehavior(ISearchAction scanAction, ISearchAction preSearch, ISearchAction onFound,
                      MemoryModuleType<?> targetMemory,
                      int scanHalfXZ, int scanYDown, int scanYUp, boolean useHomeRestriction)
```

| 参数 | 含义 |
|---|---|
| scanAction | 单点检索回调 |
| preSearch | 前置检索回调（可为 null） |
| onFound | 找到目标后回调（负责 BFS、工程创建、Memory 写入，可为 null） |
| targetMemory | 目标 Memory |
| scanHalfXZ | XZ 半边长 |
| scanYDown | Y 向下范围 |
| scanYUp | Y 向上范围 |
| useHomeRestriction | 是否家园限制 |

#### 门控与生命周期（子类无需重写）

```java
protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
protected void start(ServerLevel level, EntityMaid maid, long time)
protected void tick(ServerLevel level, EntityMaid maid, long time)
protected void stop(ServerLevel level, EntityMaid maid, long time)
```

- `checkExtraStartConditions`：三层拦截（静默冷却 → 跟随模式 → Memory 占用），任意命中则拒绝启动。
- `canStillUse`：目标 Memory 为空时继续，一旦写入结果自动终止搜索。
- `start`：重置静默计时，优先执行 `preSearch`，未命中则初始化螺旋原点。
- `tick`：批量处理螺旋点（每 tick `SPIRAL_BATCH` 个），找到后调用 `onFound`；耗尽时根据模式静默或随机游荡。
- `stop`：清空所有螺旋状态和导航。

---

### ISearchAction — 单点检索函数式接口

SearchBehavior 螺旋遍历每个坐标点时调用一次，找到目标将结果写入 Memory 并返回 `true`。

```java
import com.fennecmomo.maidmorework.search.ISearchAction;
```

```java
boolean search(ServerLevel level, BlockPos point, EntityMaid maid)
```

| 参数 | 含义 |
|---|---|
| level | 服务端世界 |
| point | 当前螺旋坐标点 |
| maid | 女仆实体 |
| 返回 | `true` = 找到目标 |

**实现者**：`LoggingTask::scanForTree`（检查原木方块）、`MiningTask`（检查 MineBlockEntity）。

---

## 二、气泡管理

### MaidBubbleHelper — 女仆气泡生命周期管理器

每女仆一个单例，全局静态 `Map<UUID, MaidBubbleHelper>` 管理。支持多条定时文本、地板文字、跟随警告等。文本优先级：`textMap` 最后插入项 > `floorText`。时长 `-999` 视为永久有效。

```java
import com.fennecmomo.maidmorework.MaidBubbleHelper;
```

#### 获取实例

```java
public static MaidBubbleHelper get(EntityMaid maid)
```

| 参数 | 含义 |
|---|---|
| maid | 女仆实体 |
| 返回 | 该女仆的气泡管理器实例（首次调用时自动创建） |

#### 每 tick 入口

```java
public static void onMaidTick(EntityMaid maid)
```

| 参数 | 含义 |
|---|---|
| maid | 女仆实体 |

应在女仆每 tick 逻辑中调用，由外部驱动气泡渲染同步。服务端才生效，客户端直接返回。

#### 文本管理

```java
public void set(String text, int duration)
public void clear(String text)
public void clearAll()
```

| 参数 | 含义 |
|---|---|
| text | 气泡显示文本 |
| duration | 持续 tick 数（`-999` 为永久有效，不被自动清除） |

`set` 插入一条定时文本，最后插入的文本优先显示。`clear` 移除指定文本，`clearAll` 清空所有文本。

#### 地板文字

```java
public void setFloor(String text)
public void clearFloor()
```

| 参数 | 含义 |
|---|---|
| text | 地板文字内容 |

地板文字优先级低于 `textMap`，仅在没有任何定时文本时显示。常用于显示搜索进度等辅助信息。

#### 便利 API

```java
public void setFollowWarn(EntityMaid maid)
```

| 参数 | 含义 |
|---|---|
| maid | 女仆实体 |

跟随模式下提示"无法工作，请开启 Home 模式"，自动读取 `WORK_ACTION` Memory 拼凑文案，持续 `BUBBLE_DURATION_TICKS` tick。

---

## 三、工程系统

### ProjectBase — 工程基类

所有工程共享的基础设施：UUID 标识、目标方块缓存、参与者管理、贡献记录、缓存空洞检测。

```java
import com.fennecmomo.maidmorework.project.ProjectBase;
```

#### 关键字段

| 字段 | 类型 | 含义 |
|---|---|---|
| targetBlocks | `List<BlockPos>` | 目标方块坐标缓存（子类可直接访问） |
| participants | `List<UUID>`（private getter） | 参与女仆 UUID 列表 |
| contributions | `Map<UUID, Integer>`（private getter） | 贡献记录（UUID → 贡献次数） |

#### 标识

```java
public UUID getId()
public ResourceKey<Level> getDimension()
public void setDimension(ResourceKey<Level> dimension)
public abstract String type()
```

| 方法 | 含义 |
|---|---|
| getId | 获取工程唯一 UUID |
| getDimension | 获取工程所在维度 |
| setDimension | 设置维度（新建工程时调用，不通过 Codec 走的新建工程） |
| type | 子类返回类型标签（如 `"chopping"`），用于序列化分派 |

#### 参与者管理

```java
public List<UUID> getParticipants()
public int getMaxParticipants()
public boolean hasAvailableSlot()
public boolean claim(UUID maidUuid)
public void release(UUID maidUuid)
```

| 方法 | 含义 |
|---|---|
| getParticipants | 获取参与女仆 UUID 列表 |
| getMaxParticipants | 获取人数上限 |
| hasAvailableSlot | 是否有空位 |
| claim | 加入工程（满员返回 `false`，已在列表中返回 `true`） |
| release | 离开工程（同时清除贡献记录） |

#### 生命周期

```java
public abstract boolean isActive(ServerLevel level, UUID maidUuid)
public abstract boolean isCompleted()
public abstract void onComplete(ServerLevel level)
public abstract BlockPos getPosition()
```

| 方法 | 含义 |
|---|---|
| isActive | 判断指定女仆是否仍在进行本工程 |
| isCompleted | 判断工程是否完成 |
| onComplete | 工程完成时的清理/分配逻辑 |
| getPosition | 工程绑定的世界位置（用于距离判断、区块卸载检查） |

#### 目标方块与维度解析

```java
public List<BlockPos> getTargetBlocks()
protected ServerLevel resolveLevel(MinecraftServer server)
```

| 方法 | 含义 |
|---|---|
| getTargetBlocks | 获取缓存的目标方块坐标列表 |
| resolveLevel | 根据工程 `dimension` 解析 `ServerLevel`（维度为空或 server 为空返回 null） |

#### 缓存验证（子类需实现）

```java
protected abstract boolean isValidTarget(ServerLevel level, BlockPos pos)
protected abstract boolean rebuild(ServerLevel level)
```

| 方法 | 含义 |
|---|---|
| isValidTarget | 单点目标有效性判定（砍树覆写为 `BlockTags.LOGS` 检查） |
| rebuild | 目标列表重建（返回 `false` 表示已无有效目标） |

---

### CountingProject — 计数型工程

继承 `ProjectBase`，执行过程仅递增计数器（不影响实际方块），计数达标后一次性批量破坏并按贡献比例分配掉落物。

```java
import com.fennecmomo.maidmorework.project.CountingProject;
```

#### 关键字段

| 字段 | 类型 | 含义 |
|---|---|---|
| progress | `double`（protected） | 当前进度（支持小数，如斧子破坏速度） |
| workload | `int`（protected） | 目标总次数 |

#### 主入口

```java
public boolean execute(ServerLevel level, UUID maidUuid)
```

| 参数 | 含义 |
|---|---|
| level | 服务端世界 |
| maidUuid | 执行女仆的 UUID |
| 返回 | `true` = 正常推进，`false` = 工程完成或无有效目标 |

执行流程：`setLoaded(true)` → 检查 `targetBlocks` → 调用 `getProgressIncrement` / `getContributionIncrement` 获取增量 → 进度累加、贡献累加 → 进度达标则 `completeTargets` 批量破坏并分配。

#### 数据访问

```java
public double getProgress()
public int getWorkload()
```

| 方法 | 含义 |
|---|---|
| getProgress | 获取当前进度 |
| getWorkload | 获取目标总次数 |

#### 可覆写增量

```java
protected double getProgressIncrement(ServerLevel level, BlockPos pos, EntityMaid maid)
protected int getContributionIncrement(ServerLevel level, BlockPos pos, EntityMaid maid)
```

| 方法 | 含义 |
|---|---|
| getProgressIncrement | 本次执行的进度增量（默认 1） |
| getContributionIncrement | 本次执行的贡献增量（默认 1） |

---

### ChoppingProject — 砍树工程

继承 `CountingProject`，砍树特有业务：树脚坐标、`isActive` 砍树任务链判定、BFS 搜索连通原木。仅允许 1 人参与。

```java
import com.fennecmomo.maidmorework.project.ChoppingProject;
```

#### 构造

```java
public ChoppingProject(BlockPos rootPos, List<BlockPos> logs)
```

| 参数 | 含义 |
|---|---|
| rootPos | 树脚位置（Y 最低的原木） |
| logs | BFS 扫描出的连通原木坐标列表 |

`workload` 自动设为 `logs.size()`。

#### BFS 工具（静态方法）

```java
public static void bfsLogs(ServerLevel level, BlockPos start, List<BlockPos> logs)
```

| 参数 | 含义 |
|---|---|
| level | 服务端世界 |
| start | 起始坐标 |
| logs | 输出列表，BFS 收集到的连通原木坐标 |

从单点出发，向 6 方向扩展，仅沿 `BlockTags.LOGS` 连通（不沿树叶爬，保持树与树的边界）。

#### 覆写要点

| 方法 | 行为 |
|---|---|
| `type()` | 返回 `"chopping"` |
| `getPosition()` | 返回树脚坐标 `rootPos` |
| `isValidTarget()` | 检查是否为 `BlockTags.LOGS` |
| `getProgressIncrement()` | 按斧子破坏速度 / 原木硬度计算增量 |
| `rebuild()` | BFS 重扫连通原木，更新 `targetBlocks` + `workload` |
| `isActive()` | 检查女仆是否仍在砍树任务中且目标是本工程 |
| `onComplete()` | 空——原木已在 `completeTargets` 中批量破坏 |

---

### ProjectServerHelper — 服务端工程管理器

全局静态单例，统一管理所有工程实例的全生命周期（注册/注销/类型安全获取/周期检查/持久化同步）。

```java
import com.fennecmomo.maidmorework.project.ProjectServerHelper;
```

#### 工程注册

```java
public static void register(ProjectBase project)
public static void remove(UUID projectId)
public static void releaseMaid(EntityMaid maid)
```

| 方法 | 含义 |
|---|---|
| register | 注册工程到管理器（同时建立坐标映射） |
| remove | 按 UUID 注销工程（同时释放坐标映射） |
| releaseMaid | 强制女仆退出当前参与的工程，清理 Brain 与 Attachment 中的工程引用；参与者归零时自动删除工程 |

#### 工程获取

```java
public static <T extends ProjectBase> T getProject(UUID projectId, Class<T> projectClass)
public static <T extends ProjectBase> T getAvailableProject(UUID projectId, Class<T> projectClass)
public static <T extends ProjectBase> T findAvailableProject(EntityMaid maid, int rangeSq, Class<T> projectClass)
```

| 方法 | 含义 |
|---|---|
| getProject | 按 UUID + 类型安全获取工程（类型不匹配返回 null） |
| getAvailableProject | 按 UUID + 类型获取未完成的工程 |
| findAvailableProject | 在女仆附近指定 `rangeSq` 范围内查找未满员的指定类型工程（优先返回最近） |

#### 坐标查询与 HUD

```java
public static boolean isPositionClaimed(ServerLevel level, BlockPos pos)
public static ProjectHudPayload getNearby(ServerPlayer player, int range)
```

| 方法 | 含义 |
|---|---|
| isPositionClaimed | 查询某维度某坐标是否已被已有工程占用（供螺旋检索时跳过） |
| getNearby | 获取玩家附近指定范围内的工程快照（客户端 HUD 查询用） |

#### 只读视图

```java
public static Map<UUID, ProjectBase> getAllProjects()
```

| 方法 | 含义 |
|---|---|
| getAllProjects | 获取全部工程的只读视图（遍历用） |

---

### ProjectClientHelper — 客户端 HUD 渲染器

客户端工程数据管理与 HUD 渲染。每 10 tick 自动向服务端查询附近工程，在右上角渲染半透明面板。

```java
import com.fennecmomo.maidmorework.project.ProjectClientHelper;
```

#### 数据同步

```java
public static void sync(ProjectHudPayload payload)
```

| 参数 | 含义 |
|---|---|
| payload | 服务端回包（含附近工程摘要列表） |

清空本地缓存，存入非完成工程数据。由网络包处理代码调用。

---

## 四、砍伐行为

### ChopBehavior — 伐木行为

继承 TLM `Behavior<EntityMaid>`，由 `LoggingTask` 组装到 Brain，与 `SearchBehavior` 配合工作。流程：导航到树脚（2 格内）→ 到达后委托 `ChoppingProject.execute()` 推进计数 → 工程完成后清理。

```java
import com.fennecmomo.maidmorework.logging.ChopBehavior;
```

#### 构造

```java
public ChopBehavior()
```

无参数，无 Memory 需求，永不超时。

#### 门控与生命周期（子类无需重写）

```java
protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
protected void start(ServerLevel level, EntityMaid maid, long time)
protected void tick(ServerLevel level, EntityMaid maid, long time)
protected void stop(ServerLevel level, EntityMaid maid, long time)
```

- `canStillUse`：跟随模式下拒绝；Memory 或 Attachment 中有可用的未完成 `ChoppingProject` 时继续。
- `start`：装备斧子、停止导航、初始化计时器、关闭自动拾取。
- `tick`：分两阶段——`tickNavigate`（导航到树脚）→ `tickChopLogs`（到达后每 `CHOP_INTERVAL` tick 调用 `project.execute()`）。
- `stop`：清理工程引用、恢复拾取状态、停止导航。

---

### LoggingTask — 伐木任务

实现 TLM `IMaidTask`，组装 `SearchBehavior`（找树） + `ChopBehavior`（砍树）两个行为。

```java
import com.fennecmomo.maidmorework.logging.LoggingTask;
```

#### 任务标识

| 方法 | 值 |
|---|---|
| `getUid()` | `maidmorework:logging` |
| `getIcon()` | 铁斧 |
| `getAmbientSound()` | 木头破坏音效 |

#### 行为组装

```java
public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
```

返回两个行为：
- 优先级 5：`SearchBehavior`（螺旋搜索树木）
- 优先级 6：`ChopBehavior`（砍树执行）

其中 `SearchBehavior` 配置为：`scanAction=scanForTree`（检查原木+邻叶）、`preSearch=searchOrphanProject`（优先接取孤儿工程）、`onFound=onTreeFound`（BFS + 工程创建 + Memory 写入）、XZ 半径 15、Y 向下 1 向上 14、家园限制。

---

## 五、配置常量

### MaidMoreWorkConfig — 可配置常量

集中管理所有可调参数，后期接入玩家配置文件时从此类读取。

```java
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
```

#### SearchBehavior 相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| SILENCE_TICKS | 10 | 螺旋耗尽/跟随拦截后的静默 tick 数 |
| BUBBLE_DURATION_TICKS | 40 | 跟随警告气泡持续时间 |
| SEARCH_ROAM_MIN_DIST | 20.0 | 螺旋耗尽后随机游荡的最小距离 |
| SEARCH_ROAM_MAX_DIST | 35.0 | 螺旋耗尽后随机游荡的最大距离 |
| SEARCH_ROAM_WALK_SPEED | 0.3 | 游荡速度倍率 |
| SPIRAL_BATCH | 100 | 每 tick 处理的螺旋点数 |

#### ChopBehavior 相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| WALK_REACH_SQ | 4.0 | 到达树脚的判定距离平方（2 格） |
| CLOSE_ENOUGH_SQ | 25.0 | 允许砍伐的最大距离平方（5 格） |
| WALK_SPEED | 0.6 | 导航速度 |
| CHOP_INTERVAL | 10 | 砍伐间隔 tick 数 |
| ROAM_MIN_DIST | 10.0 | 导航失败后随机游荡的最小距离 |
| ROAM_MAX_RANGE | 10.0 | 导航失败后随机游荡的最大范围 |
| ROAM_SPEED | 0.3 | 游荡速度 |

#### LoggingTask 相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| SEARCH_BEHAVIOR_PRIORITY | 5 | 搜索行为优先级 |
| CHOP_BEHAVIOR_PRIORITY | 6 | 砍树行为优先级 |
| SEARCH_HALF_XZ | 15 | 搜索 XZ 半边长 |
| SEARCH_Y_DOWN | 1 | 搜索 Y 向下范围 |
| SEARCH_Y_UP | 14 | 搜索 Y 向上范围 |
| ORPHAN_SEARCH_RANGE_SQ | 900 | 孤儿工程查找范围平方（30 格） |

#### ProjectManager 相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| PROJECT_CHECK_INTERVAL | 60 | 工程检查间隔 tick 数（3 秒） |
| HUD_SYNC_INTERVAL | 10 | 客户端 HUD 同步间隔 |

#### HUD 渲染相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| HUD_RENDER_DISTANCE | 32 | 工程 HUD 显示距离（格） |
| PANEL_SCALE | 0.025 | 3D 面板缩放比例 |
| PANEL_TOWARD_PLAYER_OFFSET | 1.0 | 面板朝向玩家的偏移量 |
| PANEL_SEE_THROUGH_LIGHT | 0xF000F0 | 透视渲染光照值 |
| PANEL_Y_OFFSET | 2.0 | 面板 Y 轴偏移 |
