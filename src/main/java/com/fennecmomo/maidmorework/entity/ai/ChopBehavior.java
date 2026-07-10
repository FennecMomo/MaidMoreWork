package com.fennecmomo.maidmorework.entity.ai;

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
// 用SPBlock方案：开始砍树时把所有原木替换成SPBlock，逐个标记蓝图并回收
// 不需要垫脚，不需要导航到每个方块旁边
// 砍完底部原木后，站在原地逐个把高处的原木标记为蓝图再回收
public class ChopBehavior extends Behavior<EntityMaid>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 砍伐间隔（tick），每砍一块等一下
    private static final int CHOP_INTERVAL = 10;
    // 砍树开始时女仆要走到树脚附近的距离（平方）
    private static final double WALK_REACH_SQ = 4.0;
    // 导航速度
    private static final double WALK_SPEED = 0.6;

    // 当前正在砍的原木在列表中的索引
    private int currentIndex = 0;
    // 砍伐计时器
    private int chopTimer = 0;
    // 是否已走到树脚
    private boolean reachedTree = false;
    // 导航失败计数
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

        // 把所有原木替换成SPBlock
        Optional<List<BlockPos>> blocksOpt = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        if (blocksOpt.isPresent() && !blocksOpt.get().isEmpty())
        {
            SPBlockManager.replaceBlocks(level, blocksOpt.get(), maid.getUUID());
            LOGGER.info("ChopBehavior: replaced {} logs with SPBlock maid={}", blocksOpt.get().size(), maid.getId());
        }

        // 把所有树叶也替换成SPBlock
        Optional<List<BlockPos>> leavesOpt = maid.getBrain().getMemory(ModMemories.LEAVES_BLOCKS.get());
        if (leavesOpt.isPresent() && !leavesOpt.get().isEmpty())
        {
            SPBlockManager.replaceBlocks(level, leavesOpt.get(), maid.getUUID());
            LOGGER.info("ChopBehavior: replaced {} leaves with SPBlock maid={}", leavesOpt.get().size(), maid.getId());
        }

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

        // 还没走到树脚 -> 导航过去
        if (!reachedTree)
        {
            // 用第一个方块（通常是树脚）作为导航目标
            BlockPos treeBase = blocks.get(0);
            double distSq = maid.distanceToSqr(
                    treeBase.getX() + 0.5, treeBase.getY() + 0.5, treeBase.getZ() + 0.5);

            if (distSq <= WALK_REACH_SQ)
            {
                reachedTree = true;
                LOGGER.info("ChopBehavior: reached tree base maid={}", maid.getId());
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
                            // 导航失败，直接标记为到达（站着砍）
                            reachedTree = true;
                            LOGGER.info("ChopBehavior: nav failed, chopping in place maid={}", maid.getId());
                        }
                    }
                }
                return;
            }
        }

        // 已到达树脚 -> 逐个砍SPBlock
        if (currentIndex >= blocks.size())
        {
            // 全部砍完
            finishTree(level, maid);
            return;
        }

        BlockPos target = blocks.get(currentIndex);

        // 看向目标
        maid.getLookControl().setLookAt(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                30f, 30f);

        chopTimer++;
        maid.swing(maid.getUsedItemHand());

        if (chopTimer >= CHOP_INTERVAL)
        {
            // 只标记为蓝图（变玻璃），不销毁
            SPBlockManager.markBlueprint(level, target);
            currentIndex++;
            chopTimer = 0;
            LOGGER.info("ChopBehavior: marked blueprint {} ({}/{}) maid={}",
                    target, currentIndex, blocks.size(), maid.getId());
        }
    }

    // 整棵树原木砍完后的收尾
    // 原木全部标记完蓝图了 -> 树叶也标记蓝图 -> 最后统一销毁收掉落物
    private void finishTree(ServerLevel level, EntityMaid maid)
    {
        // 树叶也标记为蓝图
        Optional<List<BlockPos>> leavesOpt = maid.getBrain().getMemory(ModMemories.LEAVES_BLOCKS.get());
        if (leavesOpt.isPresent() && !leavesOpt.get().isEmpty())
        {
            for (BlockPos leafPos : leavesOpt.get())
            {
                SPBlockManager.markBlueprint(level, leafPos);
            }
        }

        // 统一销毁所有蓝图状态的SPBlock（原木+树叶），还原+销毁+收掉落物
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
        LOGGER.info("ChopBehavior: tree finished, all blocks collected maid={}", maid.getId());
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        // 中途被打断 -> 还原所有未砍的SPBlock（原木+树叶）
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
        maid.getNavigation().stop();
        currentIndex = 0;
        chopTimer = 0;
        reachedTree = false;
        navFailCount = 0;
    }

    // 切斧头到主手
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

    // 物品放入背包
    private void addToInventory(EntityMaid maid, ItemStack stack)
    {
        if (stack.isEmpty()) return;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        ItemResource resource = ItemResource.of(stack);
        int remaining = stack.getCount();
        try (Transaction tx = Transaction.openRoot())
        {
            for (int i = 0; i < inv.size() && remaining > 0; i++)
            {
                remaining -= inv.insert(i, resource, remaining, tx);
            }
            tx.commit();
        }
    }

    // 清空所有伐木 Memory
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
