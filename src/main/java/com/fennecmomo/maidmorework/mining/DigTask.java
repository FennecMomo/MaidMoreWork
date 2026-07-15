package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;

// 女仆在矿井中的一次具体操作任务（不可变记录）
// 由 MineBlockEntity.requestNextTask() 根据当前周期/层分配
// MiningBehavior.tick() 拿到后根据 type 执行对应操作：
//   DIG    → 挖掉目标方块（普通挖掘）
//   FILL   → 在目标位置放置垫脚方块（铺设螺旋楼梯保留区）
//   LIGHT  → 挖掉方块后放火把（光源位，每周期固定位置）
//   REPLACE → 挖掉非垫脚方块后放垫脚方块（替换为保留区）
public record DigTask(BlockPos pos, Type type)
{
    // 任务类型枚举，决定女仆到达目标位置后执行什么操作
    public enum Type
    {
        DIG,     // 挖掉这个方块
        FILL,    // 在这个位置放置垫脚方块
        LIGHT,   // 光源位置：挖掉方块后放火把
        REPLACE  // 替换位置：挖掉非垫脚方块后放垫脚方块
    }
}
