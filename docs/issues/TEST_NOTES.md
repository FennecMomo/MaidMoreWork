# 砍树功能测试笔记

## 测试结果

33 项通过，仅 #11、B1 待测。

## 本轮修复

| 问题 | 修复 |
|------|------|
| B2 拾取拉走 | 订阅 `MaidPickupEvent.ItemResultPre`，砍树中 `setCanPickup(false)` |
| B1 气泡残留 | `clearBubble` 暴力遍历 ChatBubbleManager 清空 |

## 待优化

| 条目 | 说明 |
|------|------|
| B1 clearBubble | 当前直接遍历 ChatBubbleManager 删所有气泡，未走 MaidBubbleHelper 生命周期。后续需改为精确命中而非全清。 |

## 已修复 bug 汇总

| 问题 | 修复 |
|------|------|
| BFS 遍历 LEAVES 合并两树 | `bfsLogs()` 只沿 LOGS |
| (ServerLevel) 强转崩溃 | instanceof 守卫 |
| momolib mod_id 大写 | `momolib` |
| maidtown mod_id `lordling` | `maidtown` |
| 导航失败死锁 | nav fail 删工程 + 随机游荡 |
| SILENCE_TICKS 太长 | 40→10 |
| 跟随恢复旧工程 | canStillUse 第一行 |
| 砍树时被拾取拉走 | 事件阻断 + 距离兜底 |
