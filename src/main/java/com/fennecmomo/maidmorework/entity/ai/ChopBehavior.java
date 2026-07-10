package com.fennecmomo.maidmorework.entity.ai;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.spblock.SPBlockManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
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

// 伐木砍伐行为
public class ChopBehavior extends Behavior<EntityMaid>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    private static final int CHOP_INTERVAL = 10;
    private static final double WALK_REACH_SQ = 4.0;
    private static final double WALK_SPEED = 0.6;

    private int currentIndex = 0;
    private int chopTimer = 0;
    private boolean reachedTree = false;
    private int navFailCount = 0;

    public ChopBehavior()
    {
        super(Map.of(), Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        return maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get()).isPresent();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        Optional<List<BlockPos>> blocks = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        return blocks.isPresent() && !blocks.get().isEmpty();
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("ChopBehavior START maid={}", maid.getId());
        equipAxe(maid);
        currentIndex = 0;
        chopTimer = 0;
        reachedTree = false;
        navFailCount = 0;
    }

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
                    boolean found = maid.getNavigation().moveTo(
                            treeBase.getX() + 0.5, maid.getY(), treeBase.getZ() + 0.5, WALK_SPEED);
                    if (!found)
                    {
                        navFailCount++;
                        if (navFailCount > 3)
                        {
                            reachedTree = true;
                            SPBlockManager.replaceBlocks(level, blocks, maid.getUUID());
                            Optional<List<BlockPos>> leavesOpt2 = maid.getBrain().getMemory(ModMemories.LEAVES_BLOCKS.get());
                            if (leavesOpt2.isPresent() && !leavesOpt2.get().isEmpty())
                            {
                                SPBlockManager.replaceBlocks(level, leavesOpt2.get(), maid.getUUID());
                            }
                            LOGGER.info("ChopBehavior: nav failed, chopping in place maid={}", maid.getId());
                        }
                    }
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
        navFailCount = 0;
    }

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

    private void clearAllMemory(EntityMaid maid)
    {
        maid.getBrain().eraseMemory(ModMemories.LOG_BLOCKS.get());
        maid.getBrain().eraseMemory(ModMemories.LOG_TARGET.get());
        maid.getBrain().eraseMemory(ModMemories.CHOP_TIMER.get());
        maid.getBrain().eraseMemory(ModMemories.LOG_INITIALIZED.get());
        maid.getBrain().eraseMemory(ModMemories.LEAVES_BLOCKS.get());
        maid.getBrain().eraseMemory(ModMemories.SCAFFOLDING_BLOCKS.get());
    }
}
