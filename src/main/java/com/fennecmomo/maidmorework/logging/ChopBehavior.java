package com.fennecmomo.maidmorework.logging;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fennecmomo.maidmorework.MaidBubbleHelper;
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.project.ChoppingProject;
import com.fennecmomo.maidmorework.project.ProjectBase;
import com.fennecmomo.maidmorework.project.center.ProjectCenterInstance;
import com.fennecmomo.maidmorework.project.center.ProjectCenterManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

public class ChopBehavior extends Behavior<EntityMaid>
{
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private int chopTimer = 0;
    private boolean reachedTree = false;
    private boolean roaming = false;
    private boolean savedPickup = false;

    public ChopBehavior()
    {
        super(Map.of(), Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        LOGGER.debug("[ChopDebug] checkExtraStartConditions ENTER maid=" + maid.getUUID().toString().substring(0, 8));

        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            LOGGER.debug("[ChopDebug] H1: not HomeMode & canBrainMoving -> REJECT");
            return false;
        }

        ProjectCenterInstance center = ensureCenter(level, maid);
        if (center == null)
        {
            LOGGER.debug("[ChopDebug] H2: ensureCenter returned null -> REJECT");
            return false;
        }

        LOGGER.debug("[ChopDebug] H3: center OK, pos=" + center.getBlockPos().toShortString()
                + " type=" + center.getProjectTypeId());

        // 已有运行句柄且工程仍存活 → 继续当前工程
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid != null)
        {
            ChoppingProject existing = ProjectCenterManager.findProject(projectUuid, ChoppingProject.class);
            LOGGER.debug("[ChopDebug] H4: existing projectUuid=" + projectUuid.toString().substring(0, 8)
                    + " found=" + (existing != null));
            if (existing != null && !existing.isCompleted())
            {
                existing.claim(maid.getUUID());
                return true;
            }
            maid.getBrain().eraseMemory(ModMemories.PROJECT_UUID.get());
        }

        // 无有效工程 → 向中心请求分配（恢复 assignment 或新建工程，由中心裁决）
        LOGGER.debug("[ChopDebug] H5: no existing project, calling assignOrResume");
        LOGGER.debug("[ChopDebug] H5a: center inactive=" + center.getInactiveCount()
                + " managed=" + center.getManagedProjectCount() + " active=" + center.getActiveCount());

        ProjectBase project = center.assignOrResume(level, maid);
        if (project == null)
        {
            LOGGER.debug("[ChopDebug] H6: assignOrResume returned null -> REJECT");
            return false;
        }

        LOGGER.debug("[ChopDebug] H7: project assigned, id=" + project.getId().toString().substring(0, 8) + " -> ACCEPT");

        maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), project.getId());
        return true;
    }

    private ProjectCenterInstance ensureCenter(ServerLevel level, EntityMaid maid)
    {
        LOGGER.debug("[ChopDebug] ensureCenter ENTER");

        UUID centerUuid = maid.getBrain().getMemory(ModMemories.PROJECT_CENTER_UUID.get()).orElse(null);
        if (centerUuid == null)
        {
            Optional<UUID> saved = maid.getData(ModAttachments.PROJECT_CENTER_UUID_SAVED);
            if (saved.isPresent())
            {
                centerUuid = saved.get();
                maid.getBrain().setMemory(ModMemories.PROJECT_CENTER_UUID.get(), centerUuid);
            }
        }
        LOGGER.debug("[ChopDebug] C1: memory centerUuid=" + (centerUuid != null ? centerUuid.toString().substring(0, 8) : "null"));

        if (centerUuid != null)
        {
            ProjectCenterInstance center = ProjectCenterManager.get(level, centerUuid);
            boolean ok = center != null && "chopping".equals(center.getProjectTypeId());
            LOGGER.debug("[ChopDebug] C2: manager lookup ok=" + ok);
            if (ok)
            {
                // 恢复既有中心：补登记 savedHomes + 补写持久化印记
                LOGGER.debug("[ChopDebug] C3: returning existing center, re-join");
                center.joinCenter(maid);
                maid.setData(ModAttachments.PROJECT_CENTER_UUID_SAVED, Optional.of(centerUuid));
                return center;
            }
        }

        ProjectCenterInstance center = ProjectCenterManager.findNearestActive(maid);
        LOGGER.debug("[ChopDebug] C4: findNearestActive result="
                + (center != null ? center.getBlockPos().toShortString() : "null"));

        if (center != null && "chopping".equals(center.getProjectTypeId()))
        {
            LOGGER.debug("[ChopDebug] C5: joining center, pos=" + center.getBlockPos().toShortString());
            center.joinCenter(maid);
            maid.setData(ModAttachments.PROJECT_CENTER_UUID_SAVED, Optional.of(center.getId()));
            return center;
        }
        LOGGER.debug("[ChopDebug] C6: no valid center found -> RETURN null");
        return null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            LOGGER.debug("[ChopDebug] S1: canStillUse REJECT - not HomeMode");
            releaseAssignment(maid);
            clearAllMemory(maid);
            return false;
        }

        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        LOGGER.debug("[ChopDebug] S2: canStillUse projectUuid=" + (projectUuid != null ? projectUuid.toString().substring(0, 8) : "null"));

        if (projectUuid == null)
        {
            LOGGER.debug("[ChopDebug] S3: canStillUse REJECT - no projectUuid");
            return false;
        }

        ChoppingProject project = ProjectCenterManager.findProject(projectUuid, ChoppingProject.class);
        LOGGER.debug("[ChopDebug] S4: findProject result=" + (project != null ? "found, completed=" + project.isCompleted() : "null"));

        return project != null && !project.isCompleted();
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        MaidBubbleHelper.get(maid).clearAll();
        MaidBubbleHelper.get(maid).clearFloor();
        if (maid.getNavigation().isInProgress()) {}
        maid.getNavigation().stop();
        equipAxe(maid);
        chopTimer = 0;
        reachedTree = false;
        savedPickup = maid.getConfigManager().isPickup();
        maid.getConfigManager().setPickup(false);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid == null)
        {
            stopChop(maid);
            return;
        }

        ChoppingProject project = ProjectCenterManager.findProject(projectUuid, ChoppingProject.class);
        if (project == null)
        {
            stopChop(maid);
            return;
        }

        if (!level.isLoaded(project.getPosition()))
        {
            return;
        }

        if (!reachedTree)
        {
            tickNavigate(level, maid, project);
            return;
        }

        tickChopLogs(level, maid, project);
    }

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
                    ProjectCenterManager.removeByProject(level, project, false);
                    roaming = true;
                    pickRandomAndMove(maid, level);
                    stopChop(maid);
                }
            }
        }
    }

    private void tickChopLogs(ServerLevel level, EntityMaid maid, ChoppingProject project)
    {
        if (project.isCompleted())
        {
            stopChop(maid);
            return;
        }

        BlockPos treeBase = project.getPosition();
        double dx = maid.getX() - (treeBase.getX() + 0.5);
        double dz = maid.getZ() - (treeBase.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;
        if (distSq > MaidMoreWorkConfig.CLOSE_ENOUGH_SQ)
        {
            reachedTree = false;
            return;
        }

        BlockPos target = project.getPosition();
        maid.getLookControl().setLookAt(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                30f, 30f);

        chopTimer++;
        maid.swing(maid.getUsedItemHand());

        if (chopTimer >= MaidMoreWorkConfig.CHOP_INTERVAL)
        {
            chopTimer = 0;
            boolean continuing = project.execute(level, maid.getUUID());
            equipAxe(maid);
            if (!continuing)
            {
                stopChop(maid);
            }
        }
    }

    // 向中心释放当前 assignment（幂等）：停止/收起/失联时调用
    private void releaseAssignment(EntityMaid maid)
    {
        ProjectCenterInstance center = ProjectCenterManager.getCenterOf(maid);
        if (center != null)
        {
            center.releaseAssignment(maid);
        }
    }

    private void stopChop(EntityMaid maid)
    {
        releaseAssignment(maid);
        clearAllMemory(maid);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        releaseAssignment(maid);
        clearAllMemory(maid);
        if (!roaming)
        {
            maid.getNavigation().stop();
        }
        roaming = false;
        chopTimer = 0;
        reachedTree = false;
        maid.getConfigManager().setPickup(savedPickup);
    }

    private void equipAxe(EntityMaid maid)
    {
        BlockState logState = Blocks.OAK_LOG.defaultBlockState();
        ItemStack currentTool = maid.getMainHandItem();
        float currentSpeed = currentTool.getItem() instanceof AxeItem
                ? currentTool.getDestroySpeed(logState) : 0f;

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

        if (bestSlot < 0 || bestSpeed <= currentSpeed) return;

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

    private void clearAllMemory(EntityMaid maid)
    {
        maid.getBrain().eraseMemory(ModMemories.PROJECT_UUID.get());
    }

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
