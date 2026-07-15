package com.fennecmomo.maidmorework;

import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.List;

// 挖矿行为包的 Brain 扩展
// 注册挖矿相关的自定义 Memory 类型到 TLM Brain
public class MiningExtraBrain implements IExtraMaidBrain
{
    @Override
    public List<MemoryModuleType<?>> getExtraMemoryTypes()
    {
        return List.of(
                ModMemories.WORK_ACTION.get(),
                ModMemories.WORK_TARGET.get()
        );
    }
}
