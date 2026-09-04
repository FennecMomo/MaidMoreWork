package com.fennecmomo.maidmorework.project.mine;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

// 矿井中心方块（工程中心方块的子类实现，MINE_REDESIGN §1）
//
// 与普通中心的差异：
//   - 不支持手持方块物品直接放置：矿井实例只能经"标记工具两角点 → 确认"流程创建
//     （setPlacedBy 置空，避免走基类的 create 逻辑生成普通中心实例）
//   - 右键展示矿井信息（竖井边长/挖尽状态）
// 拆除清理与生命周期继承基类方块实体（MineCenterBlockEntity）
public class MineCenterBlock extends com.fennecmomo.maidmorework.project.center.ProjectCenterBlock
{
    public MineCenterBlock(Properties properties)
    {
        super(properties);
    }

    // MapCodec 不变型，无法协变收窄返回类型；unit(this) 捕获的是本类实例
    @Override
    protected MapCodec<com.fennecmomo.maidmorework.project.center.ProjectCenterBlock> codec()
    {
        return MapCodec.unit(this);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new MineCenterBlockEntity(pos, state);
    }

    // 矿井实例只能经标记流程（ProjectCenterManager.createMine）创建，
    // 手持方块物品直接放置不生成任何实例（刻意不调 super，避免走基类 create 生成普通中心）
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack)
    {
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult)
    {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof MineCenterBlockEntity be && be.hasInstance())
        {
            String anchorStr = switch (be.getAnchor())
            {
                case 0 -> "顶部";
                case 2 -> "底部";
                default -> "中心";
            };
            player.sendSystemMessage(Component.literal(
                    "§b矿井中心 | §fID: " + be.getId().toString().substring(0, 8)
                    + "§b, 竖井: §f" + be.getShaftLength() + "x" + be.getShaftWidth()
                    + "§b, 边界半径: §f" + be.getRadius()
                    + "§b, 基准点: §f" + anchorStr
                    + (be.isExhausted() ? "§c, 已挖尽" : "")));
        }
        return InteractionResult.CONSUME;
    }
}
