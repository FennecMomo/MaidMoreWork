package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.maidmorework.ModMemories;
import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.List;

// 挖矿行为包的 Brain 扩展（IExtraMaidBrain 实现）
//
// LOG_BLOCKS 从伐木侧移入：挖矿 SearchBehavior/MiningBehavior 的目标词条
// 挖矿数据主体存于 MineBlockEntity，此处仅注册目标列表词条
public class MiningExtraBrain implements IExtraMaidBrain
{
    @Override
    public List<MemoryModuleType<?>> getExtraMemoryTypes()
    {
        return List.of(
                ModMemories.LOG_BLOCKS.get()
        );
    }
}
