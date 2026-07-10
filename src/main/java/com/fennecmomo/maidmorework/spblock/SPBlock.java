package com.fennecmomo.maidmorework.spblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;

// SPBlock方案的核心方块
// 不可被任何工具破坏（destroyTime=-1，像基岩一样）
// 无掉落表，不阻挡光线（noOcclusion）
// 渲染完全由DynamicBlockStateModel + ModelData接管
// 碰撞箱用默认完整方块形状（SOLID/BLUEPRINT状态切换在渲染层处理）
public class SPBlock extends Block implements EntityBlock
{
    public SPBlock(BlockBehaviour.Properties properties)
    {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new SPBlockEntity(pos, state);
    }
}
