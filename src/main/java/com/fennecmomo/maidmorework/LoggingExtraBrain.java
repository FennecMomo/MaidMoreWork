package com.fennecmomo.maidmorework;

import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.List;

// 伐木行为包的 Brain 扩展
// 注册 4 个自定义 Memory 类型到 TLM Brain
// 这些 Memory 只在伐木行为执行期间使用，不序列化
public class LoggingExtraBrain implements IExtraMaidBrain
{
    @Override
    public List<MemoryModuleType<?>> getExtraMemoryTypes()
    {
        return List.of(
                ModMemories.LOG_BLOCKS.get(),
                ModMemories.LEAVES_BLOCKS.get(),
                ModMemories.SCAFFOLDING_BLOCKS.get(),
                ModMemories.LOG_TARGET.get(),
                ModMemories.CHOP_TIMER.get(),
                ModMemories.LOG_INITIALIZED.get(),
                ModMemories.WORK_ACTION.get(),
                ModMemories.WORK_TARGET.get()
        );
    }
}
