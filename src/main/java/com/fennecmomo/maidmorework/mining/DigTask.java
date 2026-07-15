package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;

// 女仆在矿井中的一次具体操作任务
public record DigTask(BlockPos pos, Type type)
{
    public enum Type
    {
        DIG,     // 挖掉这个方块
        FILL,    // 在这个位置放置垫脚方块
        LIGHT,   // 光源位置：挖掉方块后放火把
        REPLACE  // 替换位置：挖掉非垫脚方块后放垫脚方块
    }
}
