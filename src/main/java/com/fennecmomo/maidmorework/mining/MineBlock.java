package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.util.ExtraCodecs;

import javax.annotation.Nullable;

public class MineBlock extends BaseEntityBlock
{
    public MineBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    protected com.mojang.serialization.MapCodec<MineBlock> codec()
    {
        return com.mojang.serialization.MapCodec.unit(this);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new MineBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
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

    // 右键打开容器（非标记工具时）
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult)
    {
        if (isHoldingMarker(player)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof MineBlockEntity be)
        {
            player.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new MineStorageMenu(id, inv, be),
                    Component.literal("矿井储物")));
        }
        return InteractionResult.CONSUME;
    }

    public static boolean isHoldingMarker(Player player)
    {
        return player.getMainHandItem().getItem() instanceof MineMarkerItem
                || player.getOffhandItem().getItem() instanceof MineMarkerItem;
    }
}
