package com.fennecmomo.maidmorework;

import com.fennecmomo.maidmorework.entity.ai.LoggingTask;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.ExtraMaidBrainManager;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

// maidmorework 的 TLM 扩展入口
// TLM 启动时通过 @LittleMaidExtension 注解自动发现此类
// 在此注册伐木行为包到 TLM 任务管理器，并注册自定义 Memory 到 Brain
@LittleMaidExtension
public class MaidMoreWorkExtension implements ILittleMaid
{
    // 注册伐木行为包
    @Override
    public void addMaidTask(TaskManager manager)
    {
        manager.add(new LoggingTask());
    }

    // 注册自定义 Memory 类型到 TLM Brain
    @Override
    public void addExtraMaidBrain(ExtraMaidBrainManager manager)
    {
        manager.addExtraMaidBrain(new LoggingExtraBrain());
    }
}
