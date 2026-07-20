package com.fennecmomo.maidmorework.logging;

import com.fennecmomo.maidmorework.ModMemories;
import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.List;

// 伐木行为包的 Brain 扩展（IExtraMaidBrain 实现）
//
// 注册伐木相关的自定义 Memory 类型到 TLM Brain
// 这些 Memory 只在伐木行为执行期间使用，不序列化（运行时数据）
//
// Memory 列表：
//   LOG_BLOCKS       — 待砍的原木方块列表
//   LEAVES_BLOCKS    — 待清理的树叶方块列表
//   SCAFFOLDING_BLOCKS — 垫脚方块列表（砍树时临时放置）
//   LOG_TARGET       — 当前正在砍的原木位置
//   CHOP_TIMER       — 砍树计时器
//   LOG_INITIALIZED  — 是否已初始化砍树数据
//   WORK_ACTION      — 工作动作名称（显示在气泡框中）
//   WORK_TARGET      — 工作目标名称（显示在气泡框中）
//   PROJECT_UUID     — 当前工作工程 UUID（关联 ProjectManager）
public class LoggingExtraBrain implements IExtraMaidBrain
{
    // 返回伐木相关的 Memory 类型列表
    // TLM 在初始化女仆 Brain 时把这些 Memory 注册进去
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
                ModMemories.WORK_TARGET.get(),
                ModMemories.PROJECT_UUID.get()
        );
    }
}
