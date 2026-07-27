package com.fennecmomo.maidmorework.deprecated;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * @deprecated 2026-07-28
 * 动态碰撞方案参考：方块根据玩家手持物品切换碰撞/无碰撞。
 * 当前工程中心已改为始终有碰撞，此方案作为后续可能的参考保留。
 */
@Deprecated
public abstract class DynamicCollisionBlock extends BaseEntityBlock
{
    protected DynamicCollisionBlock(Properties properties)
    {
        super(properties);
    }

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

    protected abstract boolean isHoldingMarker(Player player);
}
