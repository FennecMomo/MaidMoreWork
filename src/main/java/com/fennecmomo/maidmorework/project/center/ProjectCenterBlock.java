package com.fennecmomo.maidmorework.project.center;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

public class ProjectCenterBlock extends BaseEntityBlock
{
    private static final int DEFAULT_RADIUS = 5;

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
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack)
    {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) return;
        if (level.getBlockEntity(pos) instanceof ProjectCenterBlockEntity be)
        {
            UUID id = UUID.randomUUID();
            UUID owner = placer instanceof Player player ? player.getUUID() : new UUID(0, 0);
            int r = DEFAULT_RADIUS;
            BlockPos nw = new BlockPos(pos.getX() - r, pos.getY() - r, pos.getZ() - r);
            BlockPos se = new BlockPos(pos.getX() + r, pos.getY() + r, pos.getZ() + r);
            be.setInstanceData(id, owner, r, nw, se);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult)
    {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ProjectCenterBlockEntity be && be.hasInstance())
        {
            if (player instanceof ServerPlayer sp)
            {
                var payload = new ProjectCenterEditPayload(
                        be.getId(), be.getBlockPos(), be.getRadius(), be.getAnchor(), be.getProjectTypeId());
                PacketDistributor.sendToPlayer(sp, payload);
            }
        }
        return InteractionResult.CONSUME;
    }
}
