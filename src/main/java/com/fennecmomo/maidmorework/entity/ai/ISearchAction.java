package com.fennecmomo.maidmorework.entity.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

// 区域检索方法。SearchBehavior 每到一个区域就调用一次。
// @return true=找到目标，false=继续搜
@FunctionalInterface
public interface ISearchAction
{
    boolean search(ServerLevel level, BlockPos center, int halfXZ, int yDown, int yUp, EntityMaid maid);
}
