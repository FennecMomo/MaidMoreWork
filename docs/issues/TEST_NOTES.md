# MaidMoreWork 测试笔记

> AI 维护，按轮次追加；当前待测项见 [TEST_LOGGING.md](TEST_LOGGING.md)
> 当前轮次：文末「矿井仓库 UI 反馈 (2026-09-20)」；上方为历史轮次（归档）

## 本轮新增

| # | 功能 | 文件 |
|---|------|------|
| 1 | 斧子加速（原版公式） | `ChoppingProject.java` — `getProgressIncrement()` |
| 2 | 进度浮点化 | `CountingProject.java` / `ProjectHudPayload.java` / `HudLineRegistry.java` |
| 3 | 工具耐久扣减 | `CountingProject.java` — `toolWear` + `accumulateToolWear` |
| 4 | 魔数移到 Config | `MaidMoreWorkConfig.java` / `SearchBehavior.java` / `ChopBehavior.java` |
| 5 | 维度+坐标映射表 | `ProjectManager.java` — `DIMENSION_POS_TO_PROJECT` |
| 6 | 坐标占用检查 | `LoggingTask.java` — `scanForTree()` |
| 7 | 工程维度字段 | `ProjectBase.java` / `CountingProject.java` / `ChoppingProject.java` |
| 8 | HUD 注释补全 | `ProjectHudRenderer.java` / `ProjectHudPayload.java` |
| 9 | 孤儿工程自动清理 | `ProjectManager.java` — `cleanInactiveParticipants` → `removeProject` |
| 10 | ProjectManager 分块注释 | `ProjectManager.java` |

## 本轮修复

| # | 问题 | 修复 |
|---|------|------|
| 1 | 砍树人数恒为 0 — 诊断中 | `ChopBehavior.tickChopLogs()` 加参与者状态日志 |
| 2 | 孤儿工程 HUD 不消失 | `removeProject` 内加 `hudForceSync = true`，确保删除后立即标记 HUD 脏 | |
| 3 | 移除逻辑分散 | 提出 `removeProject(project, it)`，两处共用 |
| 4 | ProjectManager tick 裸代码 | 全方法加 `===== N. xxx =====` 分块注释 |
| 5 | 斧子无加速 | `destroySpeed * CHOP_INTERVAL / (hardness * 30)` |
| 6 | 工具不耗耐久 | `toolWear` 累计磨损，`>=1.0` 调 `hurtAndBreak(1)`，余额滚存 |
| 7 | 进度只支持整数 | `progress` int→double，HUD 显示 `%.2f` |
| 8 | ChopBehavior 冗余中间常量 | 删除，直接引用 `MaidMoreWorkConfig` |
| 9 | SearchBehavior 中间常量 | `SILENCE_TICKS` 删除，4 个魔法数字移到 Config |
| 10 | `searchOrphanProject` claim 失败仍继续 | 增加返回值检查 |
| 11 | 缺少维度+坐标映射表 | `DIMENSION_POS_TO_PROJECT` + `claimPositions`/`releasePositions` |
| 12 | `scanForTree` 不做坐标占用检查 | 加 `isPositionClaimed()` |
| 13 | `ProjectBase` 无维度字段 | 加 `dimension` + getter/setter，Codec 同步 |
| 14 | 第一轮误删的注释和单行 log | 全部恢复 |
| 15 | 诊断日志块误恢复 | 已撤销（只保留单行 log） |
| 16 | rebuild BFS 起点非 rootPos | 优先 `rootPos`，消失找最近 |
| 17 | 拾取打断 — 诊断中 | `MaidMoreWork` 拾取事件加 `PICKUP_DIAG` 日志 |

## 待修复（等本轮测试日志反馈）

| # | 问题 | 说明 |
|---|------|------|
| 1 | H1 人数恒为 0 | 已修复：WORK_ACTION 被 TLM 大脑刷新清理，导致 isActive 失败 → 工程被踢 → 进度归零死循环。移出 createBrainTasks，改到 ChopBehavior.start 写入。 |
| 2 | D1/D2 两女仆撞树 | 已修复：同上根因 |
| 3 | 拾取打断 | 等 `PICKUP_DIAG` 日志确认是否也是 1 的连锁反应 |

## 自测反馈修复 (2026-07-25)

| # | 问题 | 文件 | 测试状态 |
|---|------|------|----------|
| 4 | 参与人数正常 | — | ✓ 确认为正常 |
| 5 | 女仆收起放出后 HUD 仍显示旧工程 | `ProjectManager.java` — `syncHudToPlayers` 删空包拦截 | **✓ 已测** |
| 6 | 工程进度出错导致无法测试 | 被 Issue 7 阻塞，7 修完待重测 | **⚠ 待重测** |
| 7 | 工作进度 0-1 反复跳，树砍不掉 | `LoggingTask.java` / `ChopBehavior.java` — WORK_ACTION/WORK_TARGET 移出 createBrainTasks | **✓ 已测** |
| 8 | 互换任务 | — | ✓ 确认为正常 |
| 9（原） | 工程进度出错导致无法测试 | 同上 6，待重测 | **⚠ 待重测** |
| 其他1 | 首次放出提示"家园没有可用目标" | `SearchBehavior.java` — expandSpiral 增加原点在家判定 | **✓ 已测** |
| 其他2 | 找树创建工程后有概率换目标一刀不砍 | `ChopBehavior.java` — 距离判断改平面 XZ | **⚠ 已修待测** |
| — | 工具磨损逻辑被注释 | `CountingProject.java` — 还原 accumulateToolWear | **⚠ 已修待测** |
| 8（新） | 砍树过程不检查更好工具 | `ChopBehavior.java` — equipAxe 重写为挑最快 + 每次 execute 重检 | **⚠ 已修待测** |
| 9（新） | 找工具不扫描副手 | 同上 equipAxe | **⚠ 已修待测** |

## 待优化

| # | 条目 | 说明 |
|---|------|------|
| 1 | clearBubble | 当前暴力遍历，后续改精确命中 |
| 2 | ScrollBar/ContentList 魔数 | UI 像素值暂未提取 |
| 3 | 跨维度混淆 | PROJECTS 静态共享 Map，不同维度 SavedData 会相互覆盖 |
| 4 | 持久化 | DIMENSION_POS_TO_PROJECT 未持久化，世界重进需从 PROJECTS 重建 |

## 已修复 bug 汇总

| # | 问题 | 修复 |
|---|------|------|
| 1 | BFS 遍历 LEAVES 合并两树 | `bfsLogs()` 只沿 LOGS |
| 2 | (ServerLevel) 强转崩溃 | instanceof 守卫 |
| 3 | fenneclib mod_id 大写 | `fenneclib` |
| 4 | maidtown mod_id 错误 | `maidtown` |
| 5 | 导航失败死锁 | nav fail 删工程 + 随机游荡 |
| 6 | SILENCE_TICKS 太长 | 40→10 |
| 7 | 跟随恢复旧工程 | canStillUse 第一行检查 |
| 8 | 砍树时被拾取拉走 | 事件 setCanPickup(false) |
| 9 | drift/close_enough 振荡死锁 | 统一 CLOSE_ENOUGH_SQ |
| 10 | HUD 网络同步延迟 | counter 独立于 CHECK_INTERVAL |
| 11 | 孤儿工程 HUD 残留 | cleanInactiveParticipants 衔接 removeProject |

## 工程中心自测反馈 (2026-07-29)

| # | 问题 | 说明 |
|---|------|------|
| 1 | 激活面板未显示图标和介绍 | ActivationHud 当前只渲染 displayName 和 description 文本，没有渲染 IProjectType.icon() |
| 2 | 类型切换按钮未做边界禁用 | 只有1个类型时，左/右按钮应置为禁用状态（首个时左禁用，末个时右禁用），当前所有状态均可点击，末个点右会回到首个 |
| 3 | 右键方块一律打开编辑面板 | 之前逻辑：标记工具右键打开编辑面板，其他情况右键输出聊天栏信息；现在 useWithoutItem 对所有右键都直接发 Payload 打开面板

## 矿井仓库 UI 反馈 (2026-09-20)

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 1 | 角标不显示真实总数，显示原版 64 + 一层黑色遮罩 | ① 展示堆被钳到 min(total,64)，原版画的就是 64；② 角标文字用 6 位色 `0xFFFFFF`（alpha=0），26.x `GuiGraphicsExtractor.text` 直接跳过不渲染，只剩 `fill(0x80000000)` 黑罩 | `WarehouseScreen` 删除事后盖罩，改覆写 `renderSlotContents`，把真实总数作为原版 countText 传入（原版 `-1` 不透明白绘制） |
| 2 | 大总数会截断（潜在，未实测） | `SimpleContainerData` 经 `ClientboundContainerSetDataPacket.writeShort` 同步，>32767 会被截断变负 | 计数数据槽拆 lo/hi 两段（DATA_SIZE=92），`totalAt` 按 32 位还原 |
| 3 | 角标数字过长会显示不下 | — | 按 k/M/B 截断缩写（最长 4 字形）：999 / 1.5k / 12k / 2.0M / 2.1B；角标字号改原版 0.5x（pose 缩放自绘，空串抑制原版堆数量），数字底边对齐槽底（vanilla 数字底边 = y+16，首版锚行顶偏上）；悬停 tooltip 附灰色"总计 N"精确值 |
| 4 | 页码/翻页不用物品槽，改真 UI | — | 移除 9 个控制行槽位（菜单内容槽 0..44）；屏幕端用 `Button` 控件（上一页/下一页，不可翻置灰）+ 居中页码文本；翻页经 `clickMenuButton`（ServerboundContainerButtonClickPacket）在服务端改页；工具条底用面板底色 0xFFC6C6C6 盖掉原槽位贴图 |
