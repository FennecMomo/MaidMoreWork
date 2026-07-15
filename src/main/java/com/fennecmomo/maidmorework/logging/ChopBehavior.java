package com.fennecmomo.maidmorework.logging;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.spblock.SPBlockEntity;
import com.fennecmomo.maidmorework.spblock.SPBlockManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

// 伐木砍伐行为：导航到树脚 → 替换为 SPBlock → 逐个标记蓝图 → 收集掉落物
// 由 LoggingTask 组装到 Brain，与 SearchBehavior 配合工作
// SearchBehavior 找到树 → BFS 整棵树 → 聚类 → 写 Memory → 本行为启动
// 整体流程：
// 1. 导航到树脚附近（2格内）
// 2. 到达后把所有原木和树叶替换为 SPBlock（不可破坏的代理方块）
// 3. 逐个标记为蓝图状态（每 10 tick 标记一个）
// 4. 全部标记后调用 finishTree：收集所有方块掉落物 + 清空 Memory
// 5. 如果被打断，stop() 会把剩余 SPBlock 还原回原始方块
public class ChopBehavior extends Behavior<EntityMaid>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    private static final int CHOP_INTERVAL = 10;   // 标记一个方块为蓝图的 tick 间隔
    private static final double WALK_REACH_SQ = 4.0; // 到达树脚的判定距离平方（2格）
    private static final double WALK_SPEED = 0.6;    // 导航速度倍率

    private int currentIndex = 0;     // 当前正在标记的原木索引
    private int chopTimer = 0;       // 当前方块的砍伐计时
    private boolean reachedTree = false; // 是否已到达树脚附近

    // 构造：无内存需求，永不超时
    public ChopBehavior()
    {
        super(Map.of(), Integer.MAX_VALUE);
    }

    // ===================== 启动/继续条件 =====================

    // 启动条件：Memory 中有原木列表（或可从 Attachment 恢复）
    // Memory 为空时尝试从 Attachment 恢复持久化数据（世界重进后 Memory 被清空）
    // 恢复时同步恢复树叶列表和工作关键词
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        Optional<List<BlockPos>> blocks = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        // Memory为空时尝试从Attachment恢复持久化数据
        if (blocks.isEmpty() || blocks.get().isEmpty())
        {
            List<BlockPos> saved = maid.getData(ModAttachments.LOG_BLOCKS_SAVED);
            List<BlockPos> savedLeaves = maid.getData(ModAttachments.LEAVES_BLOCKS_SAVED);
            if (saved != null && !saved.isEmpty())
            {
                LOGGER.info("ChopBehavior: restored {} logs from attachment maid={}", saved.size(), maid.getId());
                maid.getBrain().setMemory(ModMemories.LOG_BLOCKS.get(), new ArrayList<>(saved));
                if (savedLeaves != null && !savedLeaves.isEmpty())
                {
                    maid.getBrain().setMemory(ModMemories.LEAVES_BLOCKS.get(), new ArrayList<>(savedLeaves));
                }
                // 恢复工作关键词
                String savedAction = maid.getData(ModAttachments.WORK_ACTION_SAVED);
                if (savedAction != null && !savedAction.isEmpty())
                    maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), savedAction);
                String savedTarget = maid.getData(ModAttachments.WORK_TARGET_SAVED);
                if (savedTarget != null && !savedTarget.isEmpty())
                    maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), savedTarget);
                blocks = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
            }
        }
        boolean hasBlocks = blocks.isPresent() && !blocks.get().isEmpty();
        return hasBlocks;
    }

    // 持续条件：原木列表非空（还没砍完）
    // 砍完时 finishTree 会清空 Memory，canStillUse 自然返回 false
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        Optional<List<BlockPos>> blocks = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        boolean stillUse = blocks.isPresent() && !blocks.get().isEmpty();
        if (!stillUse)
        {
            LOGGER.info("ChopBehavior canStillUse=false: blocks empty or absent maid={}", maid.getId());
        }
        return stillUse;
    }

    // ===================== 生命周期 =====================

    // 行为启动：装备斧子、验证树脚方块有效性
    // 如果是从 Attachment 恢复的，树脚可能已被替换为 SPBlock，这也算有效
    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("ChopBehavior START maid={}", maid.getId());
        equipAxe(maid);
        currentIndex = 0;
        chopTimer = 0;
        reachedTree = false;

        // 持久化恢复后验证：检查树脚方块是否还是原木或已被替换为SPBlock
        // SPBlock也是有效目标，不能因为已替换就清空数据
        Optional<List<BlockPos>> blocksOpt = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        if (blocksOpt.isPresent() && !blocksOpt.get().isEmpty())
        {
            BlockPos treeBase = findTreeBase(blocksOpt.get());
            boolean stillValid = level.getBlockState(treeBase).is(net.minecraft.tags.BlockTags.LOGS)
                    || level.getBlockState(treeBase).getBlock() instanceof com.fennecmomo.maidmorework.spblock.SPBlock;
            if (!stillValid)
            {
                LOGGER.info("ChopBehavior: persisted tree base {} is no longer a log or SPBlock, clearing memory", treeBase);
                clearAllMemory(maid);
                maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
                maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
            }
        }
    }

    // 每 tick 驱动：导航到树脚 → 替换 SPBlock → 逐个标记蓝图
    // 流程：
    // 1. 未到达树脚：导航到树脚附近，到达后替换所有原木+树叶为 SPBlock
    // 2. 已到达树脚：逐个标记原木为蓝图（每 CHOP_INTERVAL 标记一个）
    // 3. 全部标记完后调用 finishTree 收集掉落物
    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        Optional<List<BlockPos>> blocksOpt = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        if (blocksOpt.isEmpty() || blocksOpt.get().isEmpty())
        {
            finishTree(level, maid);
            return;
        }
        List<BlockPos> blocks = blocksOpt.get();

        if (!reachedTree)
        {
            BlockPos treeBase = findTreeBase(blocks);
            double distSq = maid.distanceToSqr(
                    treeBase.getX() + 0.5, treeBase.getY() + 0.5, treeBase.getZ() + 0.5);

            if (distSq <= WALK_REACH_SQ)
            {
                reachedTree = true;
                SPBlockManager.replaceBlocks(level, blocks, maid.getUUID());
                LOGGER.info("ChopBehavior: reached tree base, replaced {} logs maid={}", blocks.size(), maid.getId());
                Optional<List<BlockPos>> leavesOpt = maid.getBrain().getMemory(ModMemories.LEAVES_BLOCKS.get());
                if (leavesOpt.isPresent() && !leavesOpt.get().isEmpty())
                {
                    SPBlockManager.replaceBlocks(level, leavesOpt.get(), maid.getUUID());
                    LOGGER.info("ChopBehavior: replaced {} leaves maid={}", leavesOpt.get().size(), maid.getId());
                }
            }
            else
            {
                if (!maid.getNavigation().isInProgress())
                {
                    // 找树脚旁边可站立的位置，不往原木里面导航
                    BlockPos walkTarget = findWalkTarget(level, treeBase);
                    // 范围外不导航，防止被TLM拉回
                    if (maid.hasHome() && !maid.isWithinHome(walkTarget))
                    {
                        LOGGER.info("ChopBehavior: walkTarget {} outside home, discarding tree maid={}",
                                walkTarget, maid.getId());
                        clearAllMemory(maid);
                        maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
                        maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
                        return;
                    }
                    LOGGER.info("ChopBehavior navigating: treeBase={} walkTarget={} distSq={} maid={}",
                            treeBase, walkTarget, String.format("%.1f", distSq), maid.getId());
                    boolean moved = maid.getNavigation().moveTo(
                            walkTarget.getX() + 0.5, walkTarget.getY(),
                            walkTarget.getZ() + 0.5, WALK_SPEED);
                    if (!moved)
                    {
                        LOGGER.warn("ChopBehavior moveTo FAILED: walkTarget={} maid={}", walkTarget, maid.getId());
                        // 导航走不动但已经离树脚不到5格，直接开砍
                        if (distSq < 25.0)
                        {
                            LOGGER.info("ChopBehavior: close enough despite nav failure, starting chop maid={}", maid.getId());
                            reachedTree = true;
                            SPBlockManager.replaceBlocks(level, blocks, maid.getUUID());
                            Optional<List<BlockPos>> leavesOpt = maid.getBrain().getMemory(ModMemories.LEAVES_BLOCKS.get());
                            if (leavesOpt.isPresent() && !leavesOpt.get().isEmpty())
                            {
                                SPBlockManager.replaceBlocks(level, leavesOpt.get(), maid.getUUID());
                            }
                        }
                        else
                        {
                            // 离太远导航不到，放弃这棵重新搜索
                            LOGGER.info("ChopBehavior: too far and nav failed, discarding tree maid={}", maid.getId());
                            clearAllMemory(maid);
                            maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
                            maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
                            return;
                        }
                    }
                }
                else
                {
                    LOGGER.info("ChopBehavior nav in progress: distSq={} maidPos={} maid={}",
                            String.format("%.1f", distSq), maid.blockPosition(), maid.getId());
                }
                return;
            }
        }

        if (currentIndex >= blocks.size())
        {
            finishTree(level, maid);
            return;
        }

        BlockPos target = blocks.get(currentIndex);

        // 检查是否已经被标记过蓝图，是就直接跳过不等待
        if (level.getBlockEntity(target) instanceof SPBlockEntity spbe
                && spbe.getBlockState2() == SPBlockEntity.State.BLUEPRINT)
        {
            LOGGER.info("ChopBehavior: skipping already blueprint {} ({}/{}) maid={}",
                    target, currentIndex + 1, blocks.size(), maid.getId());
            currentIndex++;
            chopTimer = 0;
            return;
        }

        maid.getLookControl().setLookAt(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                30f, 30f);

        chopTimer++;
        maid.swing(maid.getUsedItemHand());

        if (chopTimer >= CHOP_INTERVAL)
        {
            SPBlockManager.markBlueprint(level, target);
            currentIndex++;
            chopTimer = 0;
            LOGGER.info("ChopBehavior: marked blueprint {} ({}/{}) maid={}",
                    target, currentIndex, blocks.size(), maid.getId());
        }
    }

    // ===================== 完成与停止 =====================

    // 砍伐完成：标记树叶蓝图 → 收集所有方块掉落物 → 清空 Memory + Attachment
    // 先标记树叶为蓝图，再逐个收集原木和树叶的掉落物
    // 最后清空所有伐木相关 Memory 和 Attachment
    private void finishTree(ServerLevel level, EntityMaid maid)
    {
        Optional<List<BlockPos>> leavesOpt = maid.getBrain().getMemory(ModMemories.LEAVES_BLOCKS.get());
        if (leavesOpt.isPresent() && !leavesOpt.get().isEmpty())
        {
            for (BlockPos leafPos : leavesOpt.get())
            {
                SPBlockManager.markBlueprint(level, leafPos);
            }
        }

        Optional<List<BlockPos>> blocksOpt = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        if (blocksOpt.isPresent())
        {
            for (BlockPos pos : blocksOpt.get())
            {
                SPBlockManager.collectBlock(level, pos, maid);
            }
        }
        if (leavesOpt.isPresent())
        {
            for (BlockPos leafPos : leavesOpt.get())
            {
                SPBlockManager.collectBlock(level, leafPos, maid);
            }
            maid.getBrain().eraseMemory(ModMemories.LEAVES_BLOCKS.get());
        }

        clearAllMemory(maid);
        // 砍完清 Attachment
        maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
        maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
        LOGGER.info("ChopBehavior: tree finished, all blocks collected maid={}", maid.getId());
    }

    // 行为停止（被打断）：还原剩余 SPBlock → 清空 Memory + Attachment
    // 只还原还未被标记蓝图的方块（currentIndex 之后的原木 + 所有树叶）
    // SPBlock 还原回原始方块后清除 Attachment（不需要再次恢复了）
    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        List<BlockPos> remaining = new ArrayList<>();

        Optional<List<BlockPos>> blocksOpt = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        if (blocksOpt.isPresent())
        {
            List<BlockPos> blocks = blocksOpt.get();
            for (int i = currentIndex; i < blocks.size(); i++)
            {
                remaining.add(blocks.get(i));
            }
        }

        Optional<List<BlockPos>> leavesOpt = maid.getBrain().getMemory(ModMemories.LEAVES_BLOCKS.get());
        if (leavesOpt.isPresent())
        {
            remaining.addAll(leavesOpt.get());
        }

        if (!remaining.isEmpty())
        {
            SPBlockManager.restoreAll(level, remaining);
            LOGGER.info("ChopBehavior: interrupted, restored {} SPBlocks maid={}", remaining.size(), maid.getId());
        }

        clearAllMemory(maid);
        // 中断也清 Attachment（SPBlock 已还原回原始方块，不需要恢复了）
        maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
        maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
        maid.getNavigation().stop();
        currentIndex = 0;
        chopTimer = 0;
        reachedTree = false;
    }

    // ===================== 辅助方法 =====================

    // 装备斧子：从背包找斧子换到主手，已有斧子则跳过
    // 用 Transaction 保证背包操作的原子性
    private void equipAxe(EntityMaid maid)
    {
        if (maid.getMainHandItem().getItem() instanceof AxeItem) return;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.getItem() instanceof AxeItem)
            {
                ItemStack oldHand = maid.getMainHandItem();
                try (Transaction tx = Transaction.openRoot())
                {
                    inv.extract(i, res, 1, tx);
                    if (!oldHand.isEmpty())
                    {
                        inv.insert(i, ItemResource.of(oldHand), oldHand.getCount(), tx);
                    }
                    tx.commit();
                }
                maid.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(res.getItem(), 1));
                return;
            }
        }
    }

    // 找到树脚（原木列表中 Y 最低的方块）
    private BlockPos findTreeBase(List<BlockPos> blocks)
    {
        BlockPos base = blocks.get(0);
        for (BlockPos p : blocks)
        {
            if (p.getY() < base.getY())
            {
                base = p;
            }
        }
        return base;
    }

    // 在树脚周围 5 格范围内找离树脚最近的可站立位置
    // 用 BFS 螺旋搜索，找到空气 + 脚下实心的位置
    // 返回最接近树脚的站立点，找不到则返回树脚下方
    private BlockPos findWalkTarget(ServerLevel level, BlockPos treeBase)
    {
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;

        // 螺旋 BFS 搜树脚周围5格，按切比雪夫距离选最近树脚的
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(treeBase);
        visited.add(treeBase);

        while (!queue.isEmpty())
        {
            BlockPos p = queue.poll();
            int dx = Math.abs(p.getX() - treeBase.getX());
            int dz = Math.abs(p.getZ() - treeBase.getZ());
            if (dx > 5 || dz > 5) continue;

            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolid())
            {
                int dist = dx + dz + Math.abs(p.getY() - treeBase.getY());
                if (dist < bestDist)
                {
                    bestDist = dist;
                    best = p;
                }
            }

            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
            {
                BlockPos nb = p.relative(d);
                if (visited.add(nb))
                {
                    queue.add(nb);
                }
            }
        }

        return best != null ? best : treeBase.below();
    }

    // 清空所有伐木相关 Memory（关键词不清，切任务前一直保留）
    // WORK_ACTION 和 WORK_TARGET 故意不清除，用于气泡框提示文案
    private void clearAllMemory(EntityMaid maid)
    {
        maid.getBrain().eraseMemory(ModMemories.LOG_BLOCKS.get());
        maid.getBrain().eraseMemory(ModMemories.LOG_TARGET.get());
        maid.getBrain().eraseMemory(ModMemories.CHOP_TIMER.get());
        maid.getBrain().eraseMemory(ModMemories.LOG_INITIALIZED.get());
        maid.getBrain().eraseMemory(ModMemories.LEAVES_BLOCKS.get());
        maid.getBrain().eraseMemory(ModMemories.SCAFFOLDING_BLOCKS.get());
        // 关键词不清，切任务前一直保留
    }
}
