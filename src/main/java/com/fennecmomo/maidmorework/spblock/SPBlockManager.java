package com.fennecmomo.maidmorework.spblock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// SPBlock的管理器
public class SPBlockManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 把一批方块替换成SPBlock，绑定到指定实体
    public static void replaceBlocks(ServerLevel level, List<BlockPos> positions, UUID ownerUuid)
    {
        int count = 0;
        for (BlockPos pos : positions)
        {
            if (!level.isLoaded(pos))
            {
                continue;
            }
            BlockState originalState = level.getBlockState(pos);
            if (originalState.isAir() || originalState.is(SPRegistration.SP_BLOCK.get()))
            {
                continue;
            }

            // 保存数据到临时变量
            UUID tempOwner = ownerUuid;
            BlockState tempOriginal = originalState;

            // 替换为SPBlock
            level.setBlockAndUpdate(pos, SPRegistration.SP_BLOCK.get().defaultBlockState());
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SPBlockEntity spbe)
            {
                spbe.setOriginalState(tempOriginal);
                spbe.setOwnerUuid(tempOwner);
            }

            count++;
        }
        LOGGER.info("SPBlockManager: replaced {} blocks with SPBlock, owner={}", count, ownerUuid);
    }

    // 标记某个SPBlock为蓝图状态
    // 只改BlockEntity的state字段 + 手动发包同步客户端
    public static void markBlueprint(ServerLevel level, BlockPos pos)
    {
        if (!level.isLoaded(pos))
        {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SPBlockEntity spbe))
        {
            return;
        }
        if (spbe.getBlockState2() == SPBlockEntity.State.BLUEPRINT)
        {
            return;
        }
        spbe.setBlockState2(SPBlockEntity.State.BLUEPRINT);
    }

    // 检查某个位置是否是蓝图状态的SPBlock
    public static boolean isBlueprint(ServerLevel level, BlockPos pos)
    {
        if (!level.isLoaded(pos))
        {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SPBlockEntity spbe)
        {
            return spbe.getBlockState2() == SPBlockEntity.State.BLUEPRINT;
        }
        return false;
    }

    // 销毁蓝图状态的SPBlock，掉落物放入实体背包
    public static boolean collectBlock(ServerLevel level, BlockPos pos, LivingEntity entity)
    {
        if (!isBlueprint(level, pos))
        {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SPBlockEntity spbe))
        {
            return false;
        }
        BlockState originalState = spbe.getOriginalState();
        // 先还原回原始方块
        level.setBlockAndUpdate(pos, originalState);
        // 获取掉落物列表
        List<ItemStack> drops = Block.getDrops(originalState, level, pos,
                level.getBlockEntity(pos), entity, entity.getMainHandItem());
        // 尝试放入实体背包
        for (ItemStack stack : drops)
        {
            addToInventory(entity, stack.copy());
        }
        level.destroyBlock(pos, false, entity);
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(originalState));
        level.playSound(null, pos, SoundType.WOOD.getBreakSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        return true;
    }

    // 尝试把物品放入实体背包，放不下的丢在地上
    private static void addToInventory(LivingEntity entity, ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return;
        }
        // 女仆用ItemManager背包
        if (entity instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)
        {
            net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler inv =
                    maid.getItemManager().getMaidInv();
            net.neoforged.neoforge.transfer.item.ItemResource resource =
                    net.neoforged.neoforge.transfer.item.ItemResource.of(stack);
            int remaining = stack.getCount();
            try (net.neoforged.neoforge.transfer.transaction.Transaction tx =
                    net.neoforged.neoforge.transfer.transaction.Transaction.openRoot())
            {
                for (int i = 0; i < inv.size() && remaining > 0; i++)
                {
                    remaining -= inv.insert(i, resource, remaining, tx);
                }
                tx.commit();
            }
            if (remaining > 0)
            {
                entity.spawnAtLocation(
                        (net.minecraft.server.level.ServerLevel) entity.level(),
                        new ItemStack(stack.getItem(), remaining));
            }
        }
        else
        {
            entity.spawnAtLocation(
                    (net.minecraft.server.level.ServerLevel) entity.level(),
                    stack.copy());
        }
    }

    // 把单个SPBlock还原回原始方块
    public static void restoreBlock(ServerLevel level, BlockPos pos)
    {
        if (!level.isLoaded(pos))
        {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SPBlockEntity spbe))
        {
            return;
        }
        BlockState original = spbe.getOriginalState();
        level.setBlockAndUpdate(pos, original);
    }

    // 把一批SPBlock全部还原回原始方块
    public static void restoreAll(ServerLevel level, List<BlockPos> positions)
    {
        int count = 0;
        for (BlockPos pos : positions)
        {
            if (!level.isLoaded(pos))
            {
                continue;
            }
            restoreBlock(level, pos);
            count++;
        }
        LOGGER.info("SPBlockManager: restored {} SPBlocks", count);
    }
}
