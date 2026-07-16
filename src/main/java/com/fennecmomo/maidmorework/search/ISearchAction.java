package com.fennecmomo.maidmorework.search;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

// 单点检索方法（函数式接口）
//
// SearchBehavior 螺旋遍历每个坐标点，每点调用一次 search()
// 找到目标就把结果写进 Memory 并返回 true
//
// 实现者：
//   LoggingTask — 检查是否有可砍的树（查找原木方块）
//   MiningTask — 检查是否有可挖的矿井（查找 MineBlockEntity）
@FunctionalInterface
public interface ISearchAction
{
    // 检索单个坐标点，找到目标返回 true（同时写 Memory）
    boolean search(ServerLevel level, BlockPos point, EntityMaid maid);
}
