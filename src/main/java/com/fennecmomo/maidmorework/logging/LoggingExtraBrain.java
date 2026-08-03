package com.fennecmomo.maidmorework.logging;

import com.fennecmomo.maidmorework.ModMemories;
import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.List;

// 伐木行为包的 Brain 扩展（IExtraMaidBrain 实现）
//
// 注册伐木相关的自定义 Memory 类型到 TLM Brain
// 女仆侧只记两个词条：
//   PROJECT_CENTER_UUID — 所属中心（持久化副本在 Attachment）
//   PROJECT_UUID — 中心授予的工程句柄（运行时，不持久化）
public class LoggingExtraBrain implements IExtraMaidBrain
{
    @Override
    public List<MemoryModuleType<?>> getExtraMemoryTypes()
    {
        return List.of(
                ModMemories.PROJECT_UUID.get(),
                ModMemories.PROJECT_CENTER_UUID.get()
        );
    }
}
