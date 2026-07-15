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

// SPBlock 的管理器（纯静态工具类）
// 提供方块替换、蓝图标记、收集销毁、还原等核心操作
// 整个伐木流程：replaceBlocks → markBlueprint → collectBlock
// 中断恢复：restoreAll（SPBlock 还原回原始方块）
public class SPBlockManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 把一批方块替换成 SPBlock，绑定到指定实体
    // 原始方块的 BlockState 保存在 SPBlockEntity 里，后续可还原
    // 跳过空气和已经是 SPBlock 的位置，避免重复替换
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

    // 标记某个 SPBlock 为蓝图状态（SOLID → BLUEPRINT）
    // 只改 BlockEntity 的 state 字段 + 手动发包同步客户端
    // 已经是蓝图的跳过，避免重复发包
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

    // 检查某个位置是否是蓝图状态的 SPBlock
    // 用于 collectBlock 前置条件检查：只有蓝图状态才可被收集销毁
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

    // 销毁蓝图状态的 SPBlock，掉落物放入女仆背包，塞不下的从方块位置爆出
    // 流程：还原原始方块 → TLM 掉落物收集 → 销毁方块 → 播放破坏音效
    // 非蓝图状态直接返回 false，不做任何操作
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
        // 先还原回原始方块，这样掉落物计算基于原始方块
        level.setBlockAndUpdate(pos, originalState);
        // 女仆走 TLM 内置的掉落物收集（自动塞背包+溢出爆地上）
        if (entity instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)
        {
            maid.getItemManager().dropResourcesToMaidInv(
                    originalState, level, pos,
                    level.getBlockEntity(pos), maid.getMainHandItem());
        }
        level.destroyBlock(pos, false, entity);
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(originalState));
        level.playSound(null, pos, SoundType.WOOD.getBreakSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        return true;
    }

    // 把单个 SPBlock 还原回原始方块（用于中断恢复）
    // 直接覆盖 SPBlock，恢复为保存的 originalState
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

    // 把一批 SPBlock 全部还原回原始方块（用于砍伐中断时的恢复）
    // 逐个调用 restoreBlock，跳过未加载的区块
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
