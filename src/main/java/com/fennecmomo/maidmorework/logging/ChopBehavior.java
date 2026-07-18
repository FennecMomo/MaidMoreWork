package com.fennecmomo.maidmorework.logging;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// 伐木砍伐行为：导航到树脚 → 逐个验证并破坏原木 → 树叶自然凋落
// 由 LoggingTask 组装到 Brain，与 SearchBehavior 配合工作
// SearchBehavior 找到树 → BFS 整棵树 → 聚类 → 写 Memory → 本行为启动
//
// 整体流程：
// 1. 导航到树脚附近（2格内）
// 2. 到达后逐个破坏原木（每 CHOP_INTERVAL tick 一个）
// 3. 每次破坏前验证方块仍是原木，不是则重 BFS 更新列表
// 4. 全部破坏后清理树叶 → 清空 Memory
// 5. 如果被打断，stop() 只清空 Memory（无需还原方块）
//
// 设计依据：TreeChop 模组的懒 BFS 思路
// 不锁定整棵树，每次砍前仅做单点验证，不匹配时才重 BFS 刷新列表
// LOG_BLOCKS Memory 自身充当缓存，不再需要 SPBlock 代理方块体系
public class ChopBehavior extends Behavior<EntityMaid>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    private static final int CHOP_INTERVAL = 10;    // 破坏一个方块的 tick 间隔
    private static final double WALK_REACH_SQ = 4.0;  // 到达树脚的判定距离平方（2格）
    private static final double WALK_SPEED = 0.6;     // 导航速度倍率

    private int currentIndex = 0;     // 当前正在破坏的原木索引
    private int chopTimer = 0;        // 当前方块的砍伐计时
    private boolean reachedTree = false;  // 是否已到达树脚附近
    private boolean choppingLeaves = false; // 是否已进入树叶清理阶段

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

    // 行为启动：装备斧子、验证树脚方块是否仍为原木
    // 从 Attachment 恢复的树脚可能已被破坏，需要重新验证
    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("ChopBehavior START maid={}", maid.getId());
        equipAxe(maid);
        currentIndex = 0;
        chopTimer = 0;
        reachedTree = false;
        choppingLeaves = false;

        // 验证树脚方块是否仍为原木（从 Attachment 恢复时可能已被破坏）
        Optional<List<BlockPos>> blocksOpt = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        if (blocksOpt.isPresent() && !blocksOpt.get().isEmpty())
        {
            BlockPos treeBase = findTreeBase(blocksOpt.get());
            if (!level.getBlockState(treeBase).is(BlockTags.LOGS))
            {
                LOGGER.info("ChopBehavior: tree base {} is no longer a log, clearing memory", treeBase);
                clearAllMemory(maid);
                maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
                maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
            }
        }
    }

    // 每 tick 驱动：导航到树脚 → 逐个验证并破坏原木 → 破坏树叶
    // 流程：
    // 1. 未到达树脚：导航到树脚附近，到达后直接开始砍
    // 2. 砍原木阶段：逐个验证当前方块仍是原木，是则破坏，不是则重 BFS
    // 3. 原木砍完后切换 choppingLeaves=true，逐个破坏树叶
    // 4. 全部砍完后调用 finishTree 清空 Memory
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
            tickNavigate(level, maid, blocks);
            return;
        }

        if (!choppingLeaves)
        {
            tickChopLogs(level, maid, blocks);
        }
        else
        {
            tickChopLeaves(level, maid);
        }
    }

    // ===================== 导航阶段 =====================

    // 导航到树脚：已到达则标记 reachedTree，否则尝试移动
    // 导航失败但距离够近（5格内）也直接开砍
    private void tickNavigate(ServerLevel level, EntityMaid maid, List<BlockPos> blocks)
    {
        BlockPos treeBase = findTreeBase(blocks);
        double distSq = maid.distanceToSqr(
                treeBase.getX() + 0.5, treeBase.getY() + 0.5, treeBase.getZ() + 0.5);

        if (distSq <= WALK_REACH_SQ)
        {
            reachedTree = true;
            LOGGER.info("ChopBehavior: reached tree base, starting chop maid={}", maid.getId());
            return;
        }

        if (!maid.getNavigation().isInProgress())
        {
            BlockPos walkTarget = findWalkTarget(level, treeBase);
            if (maid.hasHome() && !maid.isWithinHome(walkTarget))
            {
                LOGGER.info("ChopBehavior: walkTarget {} outside home, discarding tree maid={}",
                        walkTarget, maid.getId());
                clearAllMemory(maid);
                maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
                maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
                return;
            }
            boolean moved = maid.getNavigation().moveTo(
                    walkTarget.getX() + 0.5, walkTarget.getY(),
                    walkTarget.getZ() + 0.5, WALK_SPEED);
            if (!moved)
            {
                if (distSq < 25.0)
                {
                    LOGGER.info("ChopBehavior: close enough despite nav failure, starting chop maid={}", maid.getId());
                    reachedTree = true;
                }
                else
                {
                    LOGGER.info("ChopBehavior: too far and nav failed, discarding tree maid={}", maid.getId());
                    clearAllMemory(maid);
                    maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
                    maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
                }
            }
        }
    }

    // ===================== 砍原木阶段 =====================

    // 逐个破坏原木：先验证仍为原木，否则重 BFS 刷新列表
    // 全部砍完后标记 choppingLeaves=true，切换到树叶清理
    private void tickChopLogs(ServerLevel level, EntityMaid maid, List<BlockPos> blocks)
    {
        if (currentIndex >= blocks.size())
        {
            // 原木全部砍完，清理树叶
            choppingLeaves = true;
            currentIndex = 0;
            chopTimer = 0;
            LOGGER.info("ChopBehavior: all logs chopped, switching to leaves maid={}", maid.getId());
            return;
        }

        BlockPos target = blocks.get(currentIndex);
        BlockState state = level.getBlockState(target);

        // 缓存失效检查：如果当前方块不再是原木，重 BFS 刷新整个列表
        if (!state.is(BlockTags.LOGS))
        {
            LOGGER.info("ChopBehavior: block {} no longer a log, re-BFSing maid={}", target, maid.getId());
            reBfsTree(level, maid, blocks);
            return;
        }

        maid.getLookControl().setLookAt(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                30f, 30f);

        chopTimer++;
        maid.swing(maid.getUsedItemHand());

        if (chopTimer >= CHOP_INTERVAL)
        {
            chopBlock(level, maid, target);
            currentIndex++;
            chopTimer = 0;
            LOGGER.info("ChopBehavior: chopped log {} ({}/{}) maid={}",
                    target, currentIndex, blocks.size(), maid.getId());
        }
    }

    // ===================== 砍树叶阶段 =====================

    // 逐个破坏树叶：同样先验证仍为树叶，否则跳过
    // 全部砍完后调用 finishTree
    private void tickChopLeaves(ServerLevel level, EntityMaid maid)
    {
        Optional<List<BlockPos>> leavesOpt = maid.getBrain().getMemory(ModMemories.LEAVES_BLOCKS.get());
        if (leavesOpt.isEmpty() || leavesOpt.get().isEmpty())
        {
            finishTree(level, maid);
            return;
        }
        List<BlockPos> leaves = leavesOpt.get();

        if (currentIndex >= leaves.size())
        {
            finishTree(level, maid);
            return;
        }

        BlockPos target = leaves.get(currentIndex);
        BlockState state = level.getBlockState(target);

        if (!state.is(BlockTags.LEAVES))
        {
            // 树叶已消失（自然凋落或被人破坏），跳过
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
            chopBlock(level, maid, target);
            currentIndex++;
            chopTimer = 0;
            LOGGER.info("ChopBehavior: chopped leaf {} ({}/{}) maid={}",
                    target, currentIndex, leaves.size(), maid.getId());
        }
    }

    // ===================== 破坏方块 =====================

    // 破坏单个方块并收集掉落物到女仆背包
    // 先从 TLM 的 dropResourcesToMaidInv 收集，再用 destroyBlock 移除方块
    private void chopBlock(ServerLevel level, EntityMaid maid, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        // 收集掉落物：TLM 内置方法从 BlockState 计算掉落物并塞入女仆背包
        maid.getItemManager().dropResourcesToMaidInv(
                state, level, pos,
                level.getBlockEntity(pos), maid.getMainHandItem());
        // 移除方块（不掉落，因为已手动收集）
        level.destroyBlock(pos, false, maid);
    }

    // ===================== 重 BFS =====================

    // 缓存失效时重 BFS 整棵树：从 Memory 中剩余已知原木位置出发
    // BFS 找到当前仍连通的整棵树，更新 LOG_BLOCKS + LEAVES_BLOCKS + Attachment
    // 如果已无任何有效原木位置，清空 Memory 结束砍伐
    private void reBfsTree(ServerLevel level, EntityMaid maid, List<BlockPos> oldBlocks)
    {
        // 从剩余原木中找一个仍有效的起点
        BlockPos start = null;
        for (int i = currentIndex + 1; i < oldBlocks.size(); i++)
        {
            if (level.getBlockState(oldBlocks.get(i)).is(BlockTags.LOGS))
            {
                start = oldBlocks.get(i);
                break;
            }
        }
        if (start == null)
        {
            LOGGER.info("ChopBehavior: no valid log remaining after re-BFS, finishing maid={}", maid.getId());
            finishTree(level, maid);
            return;
        }

        // BFS 重新扫描连通树
        List<BlockPos> newLogs = new ArrayList<>();
        List<BlockPos> newLeaves = new ArrayList<>();
        bfsAll(level, start, newLogs, newLeaves);

        LOGGER.info("ChopBehavior: re-BFS found {} logs, {} leaves maid={}",
                newLogs.size(), newLeaves.size(), maid.getId());

        // 更新 Memory + Attachment
        maid.getBrain().setMemory(ModMemories.LOG_BLOCKS.get(), newLogs);
        maid.getBrain().setMemory(ModMemories.LEAVES_BLOCKS.get(), newLeaves);
        maid.setData(ModAttachments.LOG_BLOCKS_SAVED, new ArrayList<>(newLogs));
        maid.setData(ModAttachments.LEAVES_BLOCKS_SAVED, new ArrayList<>(newLeaves));

        // 重置索引，从头开始砍新的列表
        currentIndex = 0;
        chopTimer = 0;
    }

    // ===================== 完成与停止 =====================

    // 砍伐完成：清空所有伐木相关 Memory + Attachment
    // 方块已在 tick 中逐个破坏并收集掉落物，此处只需清理状态
    private void finishTree(ServerLevel level, EntityMaid maid)
    {
        clearAllMemory(maid);
        maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
        maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
        LOGGER.info("ChopBehavior: tree finished maid={}", maid.getId());
    }

    // 行为停止（被打断）：清空 Memory + Attachment，无需还原方块
    // 已破坏的方块自然消失，未破坏的保持原样
    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        clearAllMemory(maid);
        maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
        maid.removeData(ModAttachments.LEAVES_BLOCKS_SAVED);
        maid.getNavigation().stop();
        currentIndex = 0;
        chopTimer = 0;
        reachedTree = false;
        choppingLeaves = false;
        LOGGER.info("ChopBehavior: interrupted maid={}", maid.getId());
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

    // BFS 搜索整棵连通树（原木+树叶）
    // 从单点出发，向 6 方向扩展，把连通的原木和树叶都收集起来
    // 用于缓存失效后重新扫描当前树结构
    static void bfsAll(ServerLevel level, BlockPos start, List<BlockPos> logs, List<BlockPos> leaves)
    {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty())
        {
            BlockPos p = queue.poll();
            BlockState state = level.getBlockState(p);
            if (state.is(BlockTags.LOGS)) { logs.add(p); }
            else if (state.is(BlockTags.LEAVES)) { leaves.add(p); }
            else { continue; }
            for (Direction d : Direction.values())
            {
                BlockPos nb = p.relative(d);
                if (!visited.contains(nb))
                {
                    BlockState ns = level.getBlockState(nb);
                    if (ns.is(BlockTags.LOGS) || ns.is(BlockTags.LEAVES))
                    {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
        }
    }
}
