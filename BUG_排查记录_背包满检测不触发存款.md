# Bug #16：isInventoryFull 检测不触发存款

## 问题描述
女仆挖矿后，物品能塞进背包，但背包满了之后永远不会触发向矿井方块（MineBlockEntity）的存款操作。

## 根因（已确认）

### 日志证据
```
[18:17:57] MiningBehavior: inventory check total=36 empty=4 maid=4   （大背包）
[18:17:57] MiningBehavior: inventory check total=36 empty=27 maid=5  （无背包）
[18:18:15] MiningBehavior: inventory check total=36 empty=4 maid=4   （18秒后不变）
```

### 分析
- `inv.size()` 永远返回 **36**（MaidBackpackHandler 的物理总槽数）
- TLM 的 `ItemStacksResourceHandler`（`getMaidInv()`）**不执行背包等级限制**
- 无背包女仆实际只能用 **9 格**（BackpackLevel.EMPTY_CAPACITY=6 + 额外3格基础槽位），但代码遍历 36 格
- 结果：`emptyCount` 永远 > 0 → `isInventoryFull` 永远返回 false → 永远不触发存款

### TLM 背包等级常量
```java
// com.github.tartaricacid.touhoulittlemaid.item.BackpackLevel
EMPTY_CAPACITY  = 6   // 无背包
SMALL_CAPACITY  = 12  // 小背包
MIDDLE_CAPACITY = 24  // 中背包
BIG_CAPACITY    = 36  // 大背包
```

> 注：实际无背包可用 9 格，可能是基础槽位 + BackpackLevel 的组合。

## 修复方案

### 已应用的修改（MiningBehavior.java）

**核心思路**：用 `getAvailableBackpackInv()` 替换 `getMaidInv()`，前者返回当前背包等级下的**可用槽位**。

```java
// 修改前
ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();

// 修改后
CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
```

**已改方法**：
1. `isInventoryFull` → 改用 `getAvailableBackpackInv()`
2. `addToInventory` → 改用 `getAvailableBackpackInv()`
3. `depositToMineBlock` → **保留** `getMaidInv()`（清理可能漏进不可用槽位的残留物品）

**新增 import**：
```java
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
```

### 待检查的其他方法

MiningBehavior.java 中还有其他方法使用 `getMaidInv()` 遍历全部 36 槽，可能需要同样修改：
- `tryRetrieveFromMineBlock`（~472行）— 从矿井取物品回背包
- 其他使用 `inv.size()` 的方法（529、554、575、672行）

**判断原则**：
- 涉及"往背包塞东西"的 → 用 `getAvailableBackpackInv()`
- 涉及"从背包取东西/清理背包"的 → 保留 `getMaidInv()`

## 关键 API 参考

### MaidItemManager 方法
```java
// 返回物理全部36槽（不受背包等级限制）
ItemStacksResourceHandler getMaidInv()

// 返回当前背包等级下的可用槽位（推荐用于插入/检查）
CombinedResourceHandler<ItemResource> getAvailableBackpackInv()

// 返回全部可用库存（含护甲、手等）
CombinedResourceHandler<ItemResource> getAvailableInv(boolean)

// 返回所有库存
CombinedResourceHandler<ItemResource> getAllInv()
```

### CombinedResourceHandler vs ItemStacksResourceHandler
两者都实现 NeoForge Transfer API 的 `ResourceHandler` 接口，方法签名一致：
- `int size()`
- `ItemResource getResource(int slot)`
- `int insert(int slot, ItemResource resource, int maxAmount, Transaction tx)`
- `int extract(int slot, ItemResource resource, int maxAmount, Transaction tx)`
- `long getAmountAsLong(int slot)`

## 测试方法
1. 编译：`cd maidmorework && .\gradlew.bat compileJava`
2. 启动游戏，让女仆挖矿
3. 查看日志 `run-shared\logs\latest.log`，搜索 `inventory check total=`
4. 验证：
   - 无背包 maid 的 total 应该 ≈ 9（而非 36）
   - 大背包 maid 的 total 应该 = 36
   - 背包满后应出现 `inventory full=true`
   - 随后应出现 `deposited item to mine block`

## 相关文件
- `MiningBehavior.java` — 主要修复文件
- `MineBlockEntity.java` — 矿井方块容器（634行），Container 实现在 500-580 行
- `TODO_V1_ISSUES.md` — 全部 V1 issue 清单（19项）
- TLM jar: `libs/touhoulittlemaid-2.0.0-neoforge+mc26.1.2-snapshot.jar`
- 日志: `run-shared/logs/latest.log`

### 全部 getMaidInv() 使用位置（需逐个判断是否替换）

| 文件 | 行号 | 方法 | 建议 |
|------|------|------|------|
| ChopBehavior.java | 307 | equipAxe | ❌ 保留 — 换装备，应遍历全部槽 |
| MiningBehavior.java | 376 | isInventoryFull | ✅ 已改 |
| MiningBehavior.java | 399 | addToInventory | ✅ 已改 |
| MiningBehavior.java | 434 | depositToMineBlock | ❌ 保留 — 清理全部槽位 |
| MiningBehavior.java | 473 | tryRetrieveFromMineBlock | ✅ 已改 — 往背包塞东西 |
| MiningBehavior.java | 530 | hasScaffoldBlock | ❌ 保留 — 只读检查，不影响 |
| MiningBehavior.java | 555 | hasTorchBlock | ❌ 保留 — 只读检查，不影响 |
| MiningBehavior.java | 576 | tryPlaceScaffold | ❌ 保留 — 从背包取方块放置，应遍历全部槽 |
| MiningBehavior.java | 673 | equipPickaxe | ❌ 保留 — 换装备，应遍历全部槽 |

**判断原则**：
- "往背包插入/检查是否满" → 改用 `getAvailableBackpackInv()`
- "从背包提取/清理全部物品" → 保留 `getMaidInv()`
