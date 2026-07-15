package com.fennecmomo.maidmorework.spblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;

// SPBlock 方案的核心方块（实现 EntityBlock 接口以关联 SPBlockEntity）
//
// 设计目的：
// 伐木时把整棵树替换为 SPBlock，防止其他玩家/怪物破坏树木
// SPBlock 不可被任何工具破坏（destroyTime=-1，像基岩一样）
// 外观完全由 SPDynamicModel 接管：
//   SOLID 状态 → 渲染成原始方块的样子（完美伪装）
//   BLUEPRINT 状态 → 渲染为天蓝色半透明方块（蓝图虚影）
//
// 砍伐完成后由 SPBlockManager.collectBlock 逐个销毁并还原为原始方块
public class SPBlock extends Block implements EntityBlock
{
    public SPBlock(BlockBehaviour.Properties properties)
    {
        super(properties);
    }

    // 创建对应的 SPBlockEntity，存储原始方块状态和绑定信息
    // MC 在放置 SPBlock 时自动调用
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new SPBlockEntity(pos, state);
    }
}
