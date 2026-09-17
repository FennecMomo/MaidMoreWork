package com.fennecmomo.maidmorework.project.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

// 矿道层控制方块（2026-09-04 拍板：表示该层矿道的控制中心）
//
// 属性：非合成物品、无掉落、生存不可破坏（创造可破坏）；
// 由女仆在放置火把工序后空手放置（替换平台"中间靠墙"格的垫脚方块）；
// 与矿井中心绑定，矿井中心移除时全部随之销毁
public class MineLayerControlBlock extends Block implements EntityBlock
{
    public MineLayerControlBlock(Properties properties)
    {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new MineLayerControlBlockEntity(pos, state);
    }

    // 兜底自检 ticker（服务端每 100 tick 校验绑定矿井是否存在）
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type)
    {
        if (level.isClientSide() || type != MineCenterRegistration.MINE_LAYER_CONTROL_BLOCK_ENTITY.get())
        {
            return null;
        }
        return (lvl, pos, st, be) ->
        {
            if (be instanceof MineLayerControlBlockEntity control)
            {
                MineLayerControlBlockEntity.serverTick(lvl, pos, st, control);
            }
        };
    }
}
