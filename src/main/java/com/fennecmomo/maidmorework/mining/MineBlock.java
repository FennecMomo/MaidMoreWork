package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

public class MineBlock extends Block implements EntityBlock
{
    public MineBlock(Properties properties)
    {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new MineBlockEntity(pos, state);
    }

    // 手持标记工具时有碰撞可交互，否则可穿过
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx)
    {
        if (ctx instanceof EntityCollisionContext entityCtx
                && entityCtx.getEntity() instanceof Player player
                && isHoldingMarker(player))
        {
            return Shapes.block();
        }
        return Shapes.empty();
    }

    public static boolean isHoldingMarker(Player player)
    {
        return player.getMainHandItem().getItem() instanceof MineMarkerItem
                || player.getOffhandItem().getItem() instanceof MineMarkerItem;
    }
}
