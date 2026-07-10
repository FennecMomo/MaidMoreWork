package com.fennecmomo.maidmorework.spblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

// SPBlock方案的核心方块
// 不可被任何工具破坏（destroyTime=-1，像基岩）
// 无掉落表，不阻挡光线（noOcclusion）
// 用BlockState属性blueprint切换：false=原木，true=玻璃
// 回退到稳定方案，防止DynamicBlockStateModel无限递归
public class SPBlock extends Block implements EntityBlock
{
    public static final BooleanProperty BLUEPRINT = BooleanProperty.create("blueprint");

    public SPBlock(BlockBehaviour.Properties properties)
    {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(BLUEPRINT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(BLUEPRINT);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new SPBlockEntity(pos, state);
    }
}