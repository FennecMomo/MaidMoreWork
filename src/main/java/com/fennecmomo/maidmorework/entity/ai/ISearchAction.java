package com.fennecmomo.maidmorework.entity.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

// 单点检索方法。SearchBehavior 螺旋遍历每个坐标点，每点调用一次
// 找到目标就把结果写进 Memory 并返回 true
@FunctionalInterface
public interface ISearchAction
{
    boolean search(ServerLevel level, BlockPos point, EntityMaid maid);
}
