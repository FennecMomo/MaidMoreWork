package com.fennecmomo.maidmorework.logging;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.project.ChoppingProject;
import com.fennecmomo.maidmorework.project.ProjectManager;
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

// 伐木砍伐行为：导航到树脚 → 委托 ChoppingProject 执行砍伐
// 由 LoggingTask 组装到 Brain，与 SearchBehavior 配合工作
//
// 整体流程：
// 1. 从 PROJECT_UUID Memory 获取工程实例（或从 Attachment 恢复）
// 2. 导航到树脚附近（2格内）
// 3. 到达后每 CHOP_INTERVAL tick 调用 project.execute() 推进计数
// 4. 工程计数达标后 project 自动批量破坏 + 分配掉落物
// 5. 全部完成后清空 Memory，工程由 ProjectManager 移除
//
// 树叶由原版自然腐栏机制清理，本行为不管理
//
// 本行为只负责：导航、动画、计时、状态清理
// 砍伐逻辑（缓存验证、重 BFS、批量破坏、掉落物分配）全部在 ChoppingProject 中
public class ChopBehavior extends Behavior<EntityMaid>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    private static final int CHOP_INTERVAL = 10;    // 破坏一个方块的 tick 间隔
    private static final double WALK_REACH_SQ = 4.0;  // 到达树脚的判定距离平方（2格）
    private static final double WALK_SPEED = 0.6;     // 导航速度倍率

    private int chopTimer = 0;        // 当前砍伐计时
    private boolean reachedTree = false;  // 是否已到达树脚附近

    // 构造：无内存需求，永不超时
    public ChopBehavior()
    {
        super(Map.of(), Integer.MAX_VALUE);
    }

    // ===================== 启动/继续条件 =====================

    // 启动条件：委托 canStillUse 检查
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        return canStillUse(level, maid, 0);
    }

    // 持续条件：Memory 或 Attachment 中有可用的未完成工程
    // Memory 丢失时自动从 Attachment 恢复引用
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (ProjectManager.getAvailableProject(projectUuid, ChoppingProject.class) != null)
        {
            return true;
        }

        // Memory 丢失时从 Attachment 恢复引用
        Optional<UUID> savedUuid = maid.getData(ModAttachments.PROJECT_UUID_SAVED);
        if (savedUuid.isPresent())
        {
            ChoppingProject project = ProjectManager.getAvailableProject(savedUuid.get(), ChoppingProject.class);
            if (project != null)
            {
                maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), savedUuid.get());
                maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), ModMemories.WORK_ACTION_CHOPPING);
                maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), ModMemories.WORK_TARGET_LOG);
                return true;
            }
        }
        return false;
    }

    // ===================== 生命周期 =====================

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("ChopBehavior START maid={}", maid.getId());
        equipAxe(maid);
        chopTimer = 0;
        reachedTree = false;
    }

    // 每 tick 驱动：导航到树脚 → 委托工程砍原木
    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid == null)
        {
            stopChop(maid);
            return;
        }

        ChoppingProject project = ProjectManager.getProject(projectUuid, ChoppingProject.class);
        if (project == null)
        {
            LOGGER.info("ChopBehavior: project not found, finishing maid={}", maid.getId());
            stopChop(maid);
            return;
        }

        if (!reachedTree)
        {
            tickNavigate(level, maid, project);
            return;
        }

        tickChopLogs(level, maid, project);
    }

    // ===================== 导航阶段 =====================

    // 导航到树脚附近，使用原生导航系统自动寻路
    private void tickNavigate(ServerLevel level, EntityMaid maid, ChoppingProject project)
    {
        BlockPos treeBase = project.getPosition();
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
            boolean moved = maid.getNavigation().moveTo(
                    treeBase.getX() + 0.5, treeBase.getY(),
                    treeBase.getZ() + 0.5, WALK_SPEED);
            if (!moved)
            {
                if (distSq < 25.0)
                {
                    LOGGER.info("ChopBehavior: close enough despite nav failure maid={}", maid.getId());
                    reachedTree = true;
                }
                else
                {
                    LOGGER.info("ChopBehavior: too far and nav failed, discarding maid={}", maid.getId());
                    stopChop(maid);
                }
            }
        }
    }

    // ===================== 砍原木阶段 =====================

    // 委托 CountingProject.execute() 执行计数型砍伐
    // 本方法只负责：计时、动画、调用委托
    private void tickChopLogs(ServerLevel level, EntityMaid maid, ChoppingProject project)
    {
        // 工程已完成，直接结束
        if (project.isCompleted())
        {
            stopChop(maid);
            return;
        }

        // 动画目标：朝向树脚（progress 是抽象计数器，不与具体方块索引挂钩）
        BlockPos target = project.getPosition();
        maid.getLookControl().setLookAt(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                30f, 30f);

        chopTimer++;
        maid.swing(maid.getUsedItemHand());

        if (chopTimer >= CHOP_INTERVAL)
        {
            chopTimer = 0;

            // 委托工程执行一次砍伐
            boolean continuing = project.execute(level, maid.getUUID());

            LOGGER.info("ChopBehavior: chop progress={}/{} project={} maid={}",
                    project.getProgress(), project.getWorkload(), project.getId(), maid.getId());

            if (!continuing)
            {
                stopChop(maid);
            }
        }
    }

    // ===================== 完成与停止 =====================

    // 停止砍树行为并清理状态（可能是工程完成、导航失败、工程找不到等各种原因）
    private void stopChop(EntityMaid maid)
    {
        clearAllMemory(maid);
        maid.removeData(ModAttachments.PROJECT_UUID_SAVED);
        LOGGER.info("ChopBehavior: stopped maid={}", maid.getId());
    }

    // 行为停止（被打断）：清空状态
    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        clearAllMemory(maid);
        maid.removeData(ModAttachments.PROJECT_UUID_SAVED);
        maid.getNavigation().stop();
        chopTimer = 0;
        reachedTree = false;
        LOGGER.info("ChopBehavior: interrupted maid={}", maid.getId());
    }

    // ===================== 辅助方法 =====================

    // 装备斧子
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

    // 清空所有伐木相关 Memory
    // WORK_ACTION 和 WORK_TARGET 故意不清除，用于气泡框提示文案
    private void clearAllMemory(EntityMaid maid)
    {
        maid.getBrain().eraseMemory(ModMemories.LOG_BLOCKS.get());
        maid.getBrain().eraseMemory(ModMemories.LOG_TARGET.get());
        maid.getBrain().eraseMemory(ModMemories.CHOP_TIMER.get());
        maid.getBrain().eraseMemory(ModMemories.LOG_INITIALIZED.get());
        maid.getBrain().eraseMemory(ModMemories.SCAFFOLDING_BLOCKS.get());
        maid.getBrain().eraseMemory(ModMemories.PROJECT_UUID.get());
    }
}
