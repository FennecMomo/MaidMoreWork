package com.fennecmomo.maidmorework.logging;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fennecmomo.maidmorework.MaidBubbleHelper;
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.project.ChoppingProject;
import com.fennecmomo.maidmorework.project.ProjectServerHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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
// 5. 全部完成后清空 Memory，工程由 ProjectServerHelper 移除
//
// 树叶由原版自然腐栏机制清理，本行为不管理
//
// 本行为只负责：导航、动画、计时、状态清理
// 砍伐逻辑（缓存验证、重 BFS、批量破坏、掉落物分配）全部在 ChoppingProject 中
public class ChopBehavior extends Behavior<EntityMaid>
{
    private int chopTimer = 0;        // 当前砍伐计时
    private boolean reachedTree = false;  // 是否已到达树脚附近
    private boolean roaming = false;      // 导航失败后正在游荡，stop 时不打断导航
    private boolean savedPickup = false;  // start 时保存的原始拾取状态

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
    // 跟随模式下直接拒绝（女仆被收起/重置后不应继续砍树）
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            clearAllMemory(maid);
            maid.removeData(ModAttachments.PROJECT_UUID_SAVED);
            return false;
        }

        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (ProjectServerHelper.getAvailableProject(projectUuid, ChoppingProject.class) != null)
        {
            return true;
        }

        // Memory 丢失时从 Attachment 恢复引用
        Optional<UUID> savedUuid = maid.getData(ModAttachments.PROJECT_UUID_SAVED);
        if (savedUuid.isPresent())
        {
            ChoppingProject project = ProjectServerHelper.getAvailableProject(savedUuid.get(), ChoppingProject.class);
            if (project != null)
            {

                maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), savedUuid.get());
                maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), ModMemories.WORK_ACTION_CHOPPING);
                maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), ModMemories.WORK_TARGET_LOG);
                return true;
            }
            else
            {

            }
        }
        return false;
    }

    // ===================== 生命周期 =====================

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {

        MaidBubbleHelper.get(maid).clearAll();
        MaidBubbleHelper.get(maid).clearFloor();
        maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), ModMemories.WORK_ACTION_CHOPPING);
        if (maid.getNavigation().isInProgress())
        {

        }
        maid.getNavigation().stop();
        equipAxe(maid);
        chopTimer = 0;
        reachedTree = false;
        savedPickup = maid.getConfigManager().isPickup();
        maid.getConfigManager().setPickup(false);
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

        ChoppingProject project = ProjectServerHelper.getProject(projectUuid, ChoppingProject.class);
        if (project == null)
        {

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

    // 导航到树脚旁边
    private void tickNavigate(ServerLevel level, EntityMaid maid, ChoppingProject project)
    {
        BlockPos treeBase = project.getPosition();
        double dx = maid.getX() - (treeBase.getX() + 0.5);
        double dz = maid.getZ() - (treeBase.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;

        if (distSq <= MaidMoreWorkConfig.WALK_REACH_SQ)
        {
            reachedTree = true;
            maid.getNavigation().stop();

            return;
        }

        if (!maid.getNavigation().isInProgress())
        {
            BlockPos target = treeBase;
            for (BlockPos adj : new BlockPos[]{
                treeBase.east(), treeBase.west(), treeBase.south(), treeBase.north()
            })
            {
                if (level.getBlockState(adj).isAir())
                {
                    target = adj;
                    break;
                }
            }
            boolean moved = maid.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, MaidMoreWorkConfig.WALK_SPEED);
            if (!moved)
            {
                if (distSq < MaidMoreWorkConfig.CLOSE_ENOUGH_SQ)
                {
                    reachedTree = true;

                }
                else
                {

                    ProjectServerHelper.remove(project.getId());
                    roaming = true;
                    pickRandomAndMove(maid, level);
                    stopChop(maid);
                }
            }
        }
        else
        {

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

        // 距离过远：被拾取等行为拉走，回退到导航阶段
        BlockPos treeBase = project.getPosition();
        double dx = maid.getX() - (treeBase.getX() + 0.5);
        double dz = maid.getZ() - (treeBase.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;
        if (distSq > MaidMoreWorkConfig.CLOSE_ENOUGH_SQ)
        {

            reachedTree = false;
            return;
        }

        // 动画目标：朝向树脚（progress 是抽象计数器，不与具体方块索引挂钩）
        BlockPos target = project.getPosition();
        maid.getLookControl().setLookAt(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                30f, 30f);

        chopTimer++;
        maid.swing(maid.getUsedItemHand());

        if (chopTimer >= MaidMoreWorkConfig.CHOP_INTERVAL)
        {
            chopTimer = 0;

            // 委托工程执行一次砍伐
            boolean isParticipant = project.getParticipants().contains(maid.getUUID());


            boolean continuing = project.execute(level, maid.getUUID());



            equipAxe(maid);

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
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid != null)
        {
            ChoppingProject project = ProjectServerHelper.getProject(projectUuid, ChoppingProject.class);
            if (project != null)
            {
                project.release(maid.getUUID());
            }
        }
        clearAllMemory(maid);
        maid.removeData(ModAttachments.PROJECT_UUID_SAVED);

    }

    // 行为停止（被打断）：清空状态
    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid != null)
        {
            ChoppingProject project = ProjectServerHelper.getProject(projectUuid, ChoppingProject.class);
            if (project != null)
            {
                project.release(maid.getUUID());
            }
        }
        clearAllMemory(maid);
        maid.removeData(ModAttachments.PROJECT_UUID_SAVED);
        if (!roaming)
        {
            maid.getNavigation().stop();
        }
        roaming = false;
        chopTimer = 0;
        reachedTree = false;
        maid.getConfigManager().setPickup(savedPickup);

    }

    // ===================== 辅助方法 =====================

    // 装备斧子：主手、副手、背包全扫，挑破坏速度最高的换上
    private void equipAxe(EntityMaid maid)
    {
        BlockState logState = Blocks.OAK_LOG.defaultBlockState();

        // 主手当前破坏速度（没斧子当0）
        ItemStack currentTool = maid.getMainHandItem();
        float currentSpeed = currentTool.getItem() instanceof AxeItem
                ? currentTool.getDestroySpeed(logState) : 0f;

        // 检查副手
        ItemStack offHand = maid.getOffhandItem();
        if (offHand.getItem() instanceof AxeItem)
        {
            float offSpeed = offHand.getDestroySpeed(logState);
            if (offSpeed > currentSpeed)
            {
                maid.setItemSlot(EquipmentSlot.OFFHAND, currentTool);
                maid.setItemSlot(EquipmentSlot.MAINHAND, offHand);

                return;
            }
        }

        // 扫背包找破坏速度最高的斧子
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        int bestSlot = -1;
        float bestSpeed = currentSpeed;

        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.getItem() instanceof AxeItem)
            {
                float speed = res.getItem().getDefaultInstance().getDestroySpeed(logState);
                if (speed > bestSpeed)
                {
                    bestSpeed = speed;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot < 0 || bestSpeed <= currentSpeed)
        {
            return;
        }

        ItemResource bestRes = inv.getResource(bestSlot);
        ItemStack oldHand = maid.getMainHandItem();
        try (Transaction tx = Transaction.openRoot())
        {
            inv.extract(bestSlot, bestRes, 1, tx);
            if (!oldHand.isEmpty())
            {
                inv.insert(bestSlot, ItemResource.of(oldHand), oldHand.getCount(), tx);
            }
            tx.commit();
        }
        maid.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(bestRes.getItem(), 1));

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

    // 导航失败后随机游荡，避免螺旋搜索立刻重新碰到同一棵树
    private void pickRandomAndMove(EntityMaid maid, ServerLevel level)
    {
        BlockPos here = maid.blockPosition();
        double angle = maid.getRandom().nextDouble() * Math.PI * 2;
        double dist = MaidMoreWorkConfig.ROAM_MIN_DIST + maid.getRandom().nextDouble() * MaidMoreWorkConfig.ROAM_MAX_RANGE;
        int x = here.getX() + (int) Math.round(Math.cos(angle) * dist);
        int z = here.getZ() + (int) Math.round(Math.sin(angle) * dist);
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

        maid.getNavigation().moveTo(x, groundY, z, MaidMoreWorkConfig.ROAM_SPEED);
    }
}
