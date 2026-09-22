# MaidMoreWork 已知遗留问题

> 最后更新：2026-09-22
> 当前无未解决条目；以下为已关闭记录（保留技术积累）
> 全项目总览见 [../../../docs/TODO.md](../../../docs/TODO.md)

---

## 已关闭

### 1. mine_block 动态可见性渲染 — 已作废（2026-09-22）

- **原期望**：手持标记工具时才显示烟熏炉模型，不持有时完全不可见
- **关闭原因**：`mine_block` 已随矿井重构移除（现为 `mine_center`，工程中心子类），新方块为正常可见方块，该需求未再提出
- **历史尝试**（备查）：
  - `DynamicBlockStateModel` + `collectParts` 检查手持物品 → `collectParts` 缓存不刷新
  - `RenderLevelStageEvent` 每帧 `setBlockDirty` → 性能差 + 渲染刷新不生效
  - `BlockEntityRenderer` → MC26 API 大改（两泛型 + `RenderState` 模式），无法直接用

### 2. GUI 弹窗未实现 — 已完成（2026-09-22）

- **原期望**：用 FennecLib 的 `ConfirmPopupMenu` 替代聊天栏可点击消息
- **完成情况**：标记工具确认创建流程已接入 `ConfirmPopupMenu`（`MineCenterMarkerEventHandler`）；命令内保留纯文本提示（非交互确认场景）

### 3. 矿井范围线框渲染未实现 — 已完成（2026-09-22）

- **原期望**：手持标记工具时显示矿井立方体线框
- **完成情况**：`ProjectCenterBoundaryRenderer` + `lib/render/WireframeBoxRenderer` 已实现边界线框渲染
