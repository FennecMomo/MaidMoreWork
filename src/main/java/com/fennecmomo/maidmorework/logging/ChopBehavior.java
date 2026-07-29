package com.fennecmomo.maidmorework.logging;

import java.util.Map;
import java.util.UUID;

import com.fennecmomo.maidmorework.MaidBubbleHelper;
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.project.ChoppingProject;
import com.fennecmomo.maidmorework.project.ProjectBase;
import com.fennecmomo.maidmorework.project.center.ProjectCenterBlockEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

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
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            return false;
        }

        ProjectCenterBlockEntity center = ensureCenter(maid);
        if (center == null) return false;

        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid != null && ProjectCenterBlockEntity.findProject(projectUuid, ChoppingProject.class) != null)
        {
            return true;
        }

        ProjectBase project = center.findOrCreateProject(maid);
        if (project == null) return false;

        maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), project.getId());
        maid.setData(ModAttachments.PROJECT_UUID_SAVED, java.util.Optional.of(project.getId()));
        return true;
    }

    private ProjectCenterBlockEntity ensureCenter(EntityMaid maid)
    {
        UUID centerUuid = maid.getBrain().getMemory(ModMemories.PROJECT_CENTER_UUID.get()).orElse(null);
        if (centerUuid != null)
        {
            com.fennecmomo.maidmorework.lib.region.IRegionalManager mgr =
                    com.fennecmomo.maidmorework.lib.region.RegionalManagerRegistry.get(centerUuid);
            if (mgr instanceof ProjectCenterBlockEntity be && be.hasInstance()
                    && "chopping".equals(be.getProjectTypeId()))
            {
                return be;
            }
        }

        ProjectCenterBlockEntity center = ProjectCenterBlockEntity.findNearestActiveCenter(maid);
        if (center != null && "chopping".equals(center.getProjectTypeId()))
        {
            center.joinCenter(maid);
            return center;
        }
        return null;
    }

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
        if (projectUuid == null) return false;

        ChoppingProject project = ProjectCenterBlockEntity.findProject(projectUuid, ChoppingProject.class);
        return project != null && !project.isCompleted();
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        MaidBubbleHelper.get(maid).clearAll();
        MaidBubbleHelper.get(maid).clearFloor();
        maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), ModMemories.WORK_ACTION_CHOPPING);
        maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), ModMemories.WORK_TARGET_LOG);
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

        ChoppingProject project = ProjectCenterBlockEntity.findProject(projectUuid, ChoppingProject.class);
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
                    ProjectCenterBlockEntity.removeByProject(project, false);
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

    private void stopChop(EntityMaid maid)
    {
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid != null)
        {
            ChoppingProject project = ProjectCenterBlockEntity.findProject(projectUuid, ChoppingProject.class);
            if (project != null)
            {
                project.release(maid.getUUID());
            }
        }
        clearAllMemory(maid);
        maid.removeData(ModAttachments.PROJECT_UUID_SAVED);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid != null)
        {
            ChoppingProject project = ProjectCenterBlockEntity.findProject(projectUuid, ChoppingProject.class);
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
        maid.getBrain().eraseMemory(ModMemories.LOG_BLOCKS.get());
        maid.getBrain().eraseMemory(ModMemories.LOG_TARGET.get());
        maid.getBrain().eraseMemory(ModMemories.CHOP_TIMER.get());
        maid.getBrain().eraseMemory(ModMemories.LOG_INITIALIZED.get());
        maid.getBrain().eraseMemory(ModMemories.SCAFFOLDING_BLOCKS.get());
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
