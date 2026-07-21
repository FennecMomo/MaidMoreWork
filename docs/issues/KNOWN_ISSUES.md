# MaidMoreWork 已知遗留问题

> 最后更新：2026-07-21

## 1. mine_block 动态可见性渲染未实现

- **状态**：待解决
- **期望**：手持标记工具时才显示烟熏炉模型，不持有时完全不可见
- **当前**：方块始终可见，手持标记工具有碰撞可右键交互，不手持无碰撞可穿过
- **尝试过的方案**：
  - `DynamicBlockStateModel` + `collectParts` 检查手持物品 → `collectParts` 缓存不刷新
  - `RenderLevelStageEvent` 每帧 `setBlockDirty` → 性能差 + 渲染刷新不生效
  - `BlockEntityRenderer` → MC26 API 大改（两泛型 + `RenderState` 模式），无法直接用
- **后续方向**：等 NeoForge 生态成熟后参考其他模组实现

## 2. GUI 弹窗未实现

- **状态**：可改进（MomoLib 已有基础设施）
- **期望**：用 MomoLib 的 `ConfirmPopupMenu` 替代聊天栏可点击消息
- **当前**：
  - 矿井确认创建 → 聊天栏可点击消息 `[确认]` `[取消]`
  - 矿井管理 → 聊天栏可点击消息 `[删除]` `[编辑]`
- **可用资源**：`momolib.template.Data.ConfirmPopupMenu.open()` 可直接调用
- **待办**：在 `MineCommand` 中替换现有聊天消息为 ConfirmPopup

## 3. 矿井范围线框渲染未实现

- **状态**：待解决
- **期望**：手持标记工具时显示矿井立方体线框
- **原因**：MC26 的 `RenderType` API 大改（无 `LINES` 常量、`RenderType.create` 签名变化），Forge 时代代码无法直接移植
- **参考**：Structurize 的 `WorldRenderMacros.renderLineBox` 核心逻辑可用，但 `RenderType` 创建需适配 MC26
