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

// 矿井实体方块（BaseEntityBlock）：
// - 不可破坏（strength=-1，像基岩一样）
// - 默认无碰撞（玩家可穿过）
// - 手持标记工具时才有碰撞（防止误触）
// - 右键打开储物容器（非标记工具时）
// - 手持标记工具时的右键交给 MineMarkerEventHandler 事件处理
//
// 关联：
//   MineBlockEntity: 存储矿井实例数据 + 容器物品
//   MineMarkerItem: 标记工具，用于创建/编辑矿井
//   MineStorageMenu: 储物容器界面
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

    // 创建对应的 BlockEntity（MineBlockEntity）
    // MC 在放置方块时自动调用
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new MineBlockEntity(pos, state);
    }

    // 渲染形状：标准模型（实际外观由贴图决定）
    // MODEL 表示使用普通方块模型渲染，不是特殊形状
    @Override
    protected RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.MODEL;
    }

    // 碰撞箱动态计算：
    // 手持标记工具时返回完整方块碰撞箱（防止玩家穿过，方便交互）
    // 否则返回空碰撞箱（玩家可穿过，不会挡路）
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

    // 右键方块交互：打开储物容器（非标记工具时）
    // 手持标记工具时返回 PASS，让事件处理器接管（MineMarkerEventHandler）
    // 客户端返回 SUCCESS，服务端打开 Menu 并返回 CONSUME
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

    // 检查玩家是否手持矿井标记工具（主手或副手）
    // MineBlock.getCollisionShape 和 useWithoutItem 都依赖此方法
    public static boolean isHoldingMarker(Player player)
    {
        return player.getMainHandItem().getItem() instanceof MineMarkerItem
                || player.getOffhandItem().getItem() instanceof MineMarkerItem;
    }
}
