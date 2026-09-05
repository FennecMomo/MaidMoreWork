package com.fennecmomo.maidmorework.project.mine;

import net.minecraft.core.BlockPos;

// 矿井派发任务（MINE_REDESIGN §4 四型 + B2 索光源指令）
//
// DESTROY / FILL / REPLACE / SETLIGHT 为四类真实派发的坐标任务；
// FETCH_LIGHT 不是坐标任务：pos 指向矿井方块，Behavior 驱动女仆前去仓库取光源
// （B2 拍板：领到 SETLIGHT 而背包无光源时先取灯）
public record MineTask(BlockPos pos, Type type)
{
    public enum Type
    {
        DESTROY,
        FILL,
        REPLACE,
        SETLIGHT,
        FETCH_LIGHT
    }
}
