package com.fennecmomo.maidmorework;

import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.List;

// 挖矿行为包的 Brain 扩展（IExtraMaidBrain 实现）
//
// 注册挖矿相关的自定义 Memory 类型到 TLM Brain
// 与 LoggingExtraBrain 共用 WORK_ACTION 和 WORK_TARGET Memory
//
// Memory 列表：
//   WORK_ACTION — 工作动作名称（显示在气泡框中，如 "挖矿"）
//   WORK_TARGET — 工作目标名称（显示在气泡框中，如 "矿井"）
public class MiningExtraBrain implements IExtraMaidBrain
{
    // 返回挖矿相关的 Memory 类型列表
    // 只需要 WORK_ACTION 和 WORK_TARGET，其他挖矿数据通过 MineBlockEntity 存储
    @Override
    public List<MemoryModuleType<?>> getExtraMemoryTypes()
    {
        return List.of(
                ModMemories.WORK_ACTION.get(),
                ModMemories.WORK_TARGET.get()
        );
    }
}
