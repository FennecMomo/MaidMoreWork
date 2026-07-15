package com.fennecmomo.maidmorework;

import com.fennecmomo.maidmorework.entity.ai.LoggingTask;
import com.fennecmomo.maidmorework.entity.ai.MiningTask;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.ExtraMaidBrainManager;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

// maidmorework 的 TLM 扩展入口（ILittleMaid 实现）
//
// TLM 启动时通过 @LittleMaidExtension 注解自动发现此类
// 在此注册伐木和挖矿行为包到 TLM 任务管理器，并注册自定义 Memory 到 Brain
//
// 注册流程：
//   addMaidTask: 注册 LoggingTask（伐木）和 MiningTask（挖矿）到 TaskManager
//   addExtraMaidBrain: 注册 LoggingExtraBrain 和 MiningExtraBrain 的自定义 Memory
//
// 注册后女仆可以在任务列表中看到伐木和挖矿任务
@LittleMaidExtension
public class MaidMoreWorkExtension implements ILittleMaid
{
    // 注册伐木和挖矿行为包到 TLM TaskManager
    // 每个 Task 包含 createBrainTasks() 注入行为到女仆 Brain
    @Override
    public void addMaidTask(TaskManager manager)
    {
        manager.add(new LoggingTask());
        manager.add(new MiningTask());
    }

    // 注册自定义 Memory 类型到 TLM Brain
    // 这些 Memory 在女仆 Brain 初始化时注册，用于存储运行时行为数据
    @Override
    public void addExtraMaidBrain(ExtraMaidBrainManager manager)
    {
        manager.addExtraMaidBrain(new LoggingExtraBrain());
        manager.addExtraMaidBrain(new MiningExtraBrain());
    }
}
