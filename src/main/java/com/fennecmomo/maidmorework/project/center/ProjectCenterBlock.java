package com.fennecmomo.maidmorework.project.center;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

public class ProjectCenterBlock extends BaseEntityBlock
{
    public ProjectCenterBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    protected com.mojang.serialization.MapCodec<ProjectCenterBlock> codec()
    {
        return com.mojang.serialization.MapCodec.unit(this);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new ProjectCenterBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
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

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult)
    {
        if (isHoldingMarker(player)) return InteractionResult.PASS;
        return InteractionResult.PASS;
    }

    public static boolean isHoldingMarker(Player player)
    {
        return player.getMainHandItem().getItem() instanceof ProjectCenterMarkerItem
                || player.getOffhandItem().getItem() instanceof ProjectCenterMarkerItem;
    }
}
