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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// SPBlock的管理器
// 回退到稳定版本，防止无限递归
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

    // 标记某个SPBlock为蓝图状态（渲染切换为玻璃模型，无碰撞）
    public static void markBlueprint(ServerLevel level, BlockPos pos)
    {
        if (!level.isLoaded(pos))
        {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.is(SPRegistration.SP_BLOCK.get()) || state.getValue(SPBlock.BLUEPRINT))
        {
            return;
        }
        // 保存BlockEntity数据，因为setBlockAndUpdate会重建
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SPBlockEntity spbe))
        {
            return;
        }
        BlockState originalState = spbe.getOriginalState();
        UUID ownerUuid = spbe.getOwnerUuid();
        // 改BlockState属性
        level.setBlockAndUpdate(pos, state.setValue(SPBlock.BLUEPRINT, true));
        // 恢复数据
        BlockEntity newBe = level.getBlockEntity(pos);
        if (newBe instanceof SPBlockEntity newSpbe)
        {
            newSpbe.setOriginalState(originalState);
            newSpbe.setOwnerUuid(ownerUuid);
        }
    }

    // 检查某个位置是否是蓝图状态的SPBlock
    public static boolean isBlueprint(ServerLevel level, BlockPos pos)
    {
        if (!level.isLoaded(pos))
        {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.is(SPRegistration.SP_BLOCK.get()) && state.getValue(SPBlock.BLUEPRINT);
    }

    // 销毁蓝图状态的SPBlock，掉落物给指定实体
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
        // 触发原始方块的销毁，生成粒子、音效、掉落物
        Block.dropResources(originalState, level, pos, null, entity, entity.getMainHandItem());
        level.destroyBlock(pos, false, entity);
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(originalState));
        level.playSound(null, pos, SoundType.WOOD.getBreakSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        return true;
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

    // 尝试把物品堆叠放入实体背包
    private static void addToInventory(LivingEntity entity, ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return;
        }
        entity.spawnAtLocation(stack.copy());
    }
}