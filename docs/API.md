# MaidMoreWork 公开 API 参考

> 本文档仅列出允许外部模块调用的 API，内部实现类不在此列。
> 最后更新：2026-09-22
> 历史 API（`SearchBehavior` / `ISearchAction` / `ProjectServerHelper`）已随重构移除，不在本文档范围内。

---

## 一、气泡管理

### MaidBubbleHelper — 女仆气泡生命周期管理器

每女仆一个实例，全局静态 `Map<UUID, MaidBubbleHelper>` 管理。支持多条定时文本、地板文字。文本优先级：`textMap` 最后插入项 > `floorText`。时长 `-999` 视为永久有效。

```java
import com.fennecmomo.maidmorework.MaidBubbleHelper;
```

#### 获取实例 / 每 tick 入口

```java
public static MaidBubbleHelper get(EntityMaid maid)
public static void onMaidTick(EntityMaid maid)
```

| 方法 | 含义 |
|---|---|
| get | 获取该女仆的气泡管理器实例（首次调用时自动创建） |
| onMaidTick | 女仆每 tick 调用，驱动时长递减与气泡渲染同步（服务端才生效） |

#### 文本管理

```java
public void set(String text, int duration)
public void clear(String text)
public void clearAll()
```

| 方法 | 含义 |
|---|---|
| set | 插入一条定时文本，最后插入的文本优先显示 |
| clear | 移除指定文本 |
| clearAll | 清空所有定时文本 |

#### 地板文字

```java
public void setFloor(String text)
public void clearFloor()
```

地板文字优先级低于 `textMap`，仅在没有任何定时文本时显示，常用于搜索进度等辅助信息。

#### 便利 API

```java
public void setFollowWarn(String action)
```

跟随模式提醒气泡：`action` 为动作名（如"挖矿"/"伐木"），自动拼文案，持续 `BUBBLE_DURATION_TICKS` tick。

---

## 二、工程体系

### ProjectBase — 工程基类

所有工程共享的基础设施：UUID 标识、维度、所属中心、目标方块缓存、参与者管理、贡献记录。

```java
import com.fennecmomo.maidmorework.project.ProjectBase;
```

#### 标识与中心

```java
public abstract String type()
public UUID getId()
public ResourceKey<Level> getDimension()
public void setDimension(ResourceKey<Level> dimension)
public UUID getCenterId()
public void setCenterId(UUID centerId)
public ProjectCenterInstance findCenter(ServerLevel level)
```

| 方法 | 含义 |
|---|---|
| type | 子类返回类型标签（如 `"chopping"`），用于序列化分派 |
| getId | 工程唯一 UUID |
| getDimension / setDimension | 工程所在维度 |
| getCenterId / setCenterId | 工程所属工程中心（可空） |
| findCenter | 解析所属中心实例（中心不存在返回 null） |

#### 参与者与贡献

```java
public List<UUID> getParticipants()
public int getMaxParticipants()
public boolean hasAvailableSlot()
public boolean claim(UUID maidUuid)
public void release(UUID maidUuid)
public Map<UUID, Integer> getContributions()
```

| 方法 | 含义 |
|---|---|
| claim | 加入工程（满员返回 `false`，已在列表中返回 `true`） |
| release | 离开工程（同时清除贡献记录） |
| getContributions | 贡献记录（UUID → 贡献次数） |

#### 目标与驱动

```java
public List<BlockPos> getTargetBlocks()
public void tick(ServerLevel level)
```

| 方法 | 含义 |
|---|---|
| getTargetBlocks | 缓存的目标方块坐标列表 |
| tick | 每周期驱动（缓存校验/重建等），由中心或行为方调用 |

#### 子类实现

```java
public abstract boolean isCompleted()
public abstract void onComplete(ServerLevel level)
public abstract BlockPos getPosition()
protected abstract boolean isValidTarget(ServerLevel level, BlockPos pos)
protected abstract boolean rebuild(ServerLevel level)
```

| 方法 | 含义 |
|---|---|
| isCompleted / onComplete | 完成判定与完成时清理 |
| getPosition | 工程绑定的世界位置（距离判断、区块卸载检查） |
| isValidTarget | 单点目标有效性判定 |
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
| progress | `double`（protected） | 当前进度（支持小数） |
| workload | `int`（protected） | 目标总次数 |

#### 数据访问与驱动

```java
public double getProgress()
public int getWorkload()
public boolean execute(ServerLevel level, UUID maidUuid)
public void tick(ServerLevel level)
```

| 方法 | 含义 |
|---|---|
| execute | 女仆执行一次推进：检查目标 → 累加进度/贡献 → 达标则批量破坏并分配 |
| tick | 周期驱动（缓存校验等） |

#### 可覆写增量

```java
protected double getProgressIncrement(ServerLevel level, BlockPos pos, EntityMaid maid)
protected int getContributionIncrement(ServerLevel level, BlockPos pos, EntityMaid maid)
protected void accumulateToolWear(EntityMaid maid, double amount)
```

| 方法 | 含义 |
|---|---|
| getProgressIncrement | 本次执行的进度增量（默认 1；砍树按工具速度计算） |
| getContributionIncrement | 本次执行的贡献增量（默认 1） |
| accumulateToolWear | 工具磨损累计（`>=1.0` 时扣 1 点耐久，余额滚存） |

---

### ChoppingProject — 砍树工程

继承 `CountingProject`，砍树特有业务：树脚坐标、BFS 搜索连通原木。仅允许 1 人参与。

```java
import com.fennecmomo.maidmorework.project.ChoppingProject;
```

#### 构造与 BFS 工具

```java
public ChoppingProject(BlockPos rootPos, List<BlockPos> logs)
public static void bfsLogs(ServerLevel level, BlockPos start, List<BlockPos> logs)
```

| 方法 | 含义 |
|---|---|
| 构造 | `rootPos` 树脚（Y 最低原木）、`logs` 连通原木列表；`workload` 自动设为 `logs.size()` |
| bfsLogs | 从单点 6 方向扩展，仅沿 `BlockTags.LOGS` 连通（不沿树叶爬，保持树与树的边界） |

覆写要点：`type()` 返回 `"chopping"`；`getPosition()` 返回树脚；`isValidTarget()` 检查 `BlockTags.LOGS`；`rebuild()` BFS 重扫更新目标与工作量。

---

## 三、工程类型扩展（`lib/projecttype`）

### IProjectType — 工程类型接口

```java
import com.fennecmomo.maidmorework.lib.projecttype.IProjectType;
```

```java
String id()
Component displayName()
ItemStack icon()
Component description()
Identifier taskUid()
Component targetName()                                  // 默认回落 displayName
boolean isValidTarget(ServerLevel level, BlockPos pos)  // 默认 false
Set<BlockPos> bfs(ServerLevel level, BlockPos start)    // 默认空集
ProjectBase createProject(UUID id, BlockPos rootPos, List<BlockPos> targets)
Predicate<BlockState> stateFilter()                     // 默认全 false
```

### ProjectTypeRegistry — 工程类型注册表

```java
public static void register(IProjectType type)
public static IProjectType get(String id)
public static List<IProjectType> getAll()
public static int size()
public static void discover()
```

### @RegProjectType — 自动注册注解

标注在 `IProjectType` 实现类上，`discover()` 扫描并注册。

---

## 四、区域管理器（`lib/region`）

### IRegionalManager — 区域管理器接口

由工程中心方块实体等实现，对外提供长方体区域查询与边界显隐。

```java
import com.fennecmomo.maidmorework.lib.region.IRegionalManager;
```

```java
UUID getId()
BlockPos getMinCorner()
BlockPos getMaxCorner()
default boolean contains(BlockPos pos)
void setBoundaryVisible(boolean visible)
boolean isBoundaryVisible()
```

### RegionalManagerRegistry — 区域管理器注册表

```java
public static void register(IRegionalManager manager)
public static void unregister(UUID id)
public static IRegionalManager get(UUID id)
public static Collection<IRegionalManager> values()
public static void clear()
```

---

## 五、客户端 HUD

### ProjectClientHelper — 客户端工程数据与中心信息缓存

```java
import com.fennecmomo.maidmorework.project.ProjectClientHelper;
```

```java
public static void sync(ProjectHudPayload payload)
public static void clear()
```

| 方法 | 含义 |
|---|---|
| sync | 收到服务端 HUD 回包后刷新本地缓存（由网络包处理代码调用） |
| clear | 清空本地缓存 |

---

## 六、配置常量

### MaidMoreWorkConfig — 可配置常量

集中管理所有可调参数，后期接入玩家配置文件时从此类读取。

```java
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
```

#### 搜索/游荡相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| SILENCE_TICKS | 10 | 搜索耗尽/跟随拦截后的静默 tick 数 |
| BUBBLE_DURATION_TICKS | 60 | 提示气泡持续时间 |
| SEARCH_ROAM_MIN_DIST | 20.0 | 螺旋耗尽后随机游荡的最小距离 |
| SEARCH_ROAM_MAX_DIST | 35.0 | 螺旋耗尽后随机游荡的最大距离 |
| SEARCH_ROAM_WALK_SPEED | 0.3 | 游荡速度倍率 |
| SPIRAL_BATCH | 100 | 每 tick 处理的螺旋点数 |

#### 砍树相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| WALK_REACH_SQ | 4.0 | 到达树脚的判定距离平方（2 格） |
| CLOSE_ENOUGH_SQ | 25.0 | 允许砍伐的最大距离平方（5 格） |
| WALK_SPEED | 0.6 | 导航速度 |
| CHOP_INTERVAL | 10 | 砍伐间隔 tick 数 |
| ROAM_MIN_DIST | 10.0 | 导航失败后随机游荡的最小距离 |
| ROAM_MAX_RANGE | 10.0 | 导航失败后随机游荡的最大范围 |
| ROAM_SPEED | 0.3 | 游荡速度 |
| PERSONAL_SEARCH_CD_TICKS | 100 | 个人搜索/空闲气泡的节流间隔 |

#### 任务与工程周期

| 常量 | 默认值 | 含义 |
|---|---|---|
| SEARCH_BEHAVIOR_PRIORITY | 5 | 搜索行为优先级 |
| CHOP_BEHAVIOR_PRIORITY | 6 | 砍树行为优先级 |
| SEARCH_HALF_XZ | 15 | 搜索 XZ 半边长 |
| SEARCH_Y_DOWN | 1 | 搜索 Y 向下范围 |
| SEARCH_Y_UP | 14 | 搜索 Y 向上范围 |
| ORPHAN_SEARCH_RANGE_SQ | 900 | 孤儿工程查找范围平方（30 格） |
| PROJECT_CHECK_INTERVAL | 60 | 工程检查间隔 tick 数（3 秒） |
| HUD_SYNC_INTERVAL | 10 | 客户端 HUD 同步间隔 |

#### HUD 渲染相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| HUD_RENDER_DISTANCE | 16 | 工程 HUD 显示距离（格） |
| PANEL_SCALE | 0.025 | 3D 面板缩放比例 |
| PANEL_TOWARD_PLAYER_OFFSET | 1.0 | 面板朝向玩家的偏移量 |
| PANEL_SEE_THROUGH_LIGHT | 0xF000F0 | 透视渲染光照值 |
| PANEL_Y_OFFSET | 2.0 | 面板 Y 轴偏移 |

#### 工程中心相关

| 常量 | 默认值 | 含义 |
|---|---|---|
| REFRESH_INTERVAL_TICKS | 100 | 中心全量扫描刷新间隔（5 秒） |
