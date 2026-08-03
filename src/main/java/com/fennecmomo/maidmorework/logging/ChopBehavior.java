package com.fennecmomo.maidmorework.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fennecmomo.maidmorework.MaidBubbleHelper;
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.lib.projecttype.IProjectType;
import com.fennecmomo.maidmorework.lib.projecttype.ProjectTypeRegistry;
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

// 砍树行为
//
// 工作模式（跟随/Home 均可工作）：
//   1. 找到工程中心 → 走中心流程（ensureCenter → assignOrResume，工程归属归中心）
//   2. 找不到工程中心 → 个人工程位：女仆自带容量 1 的简化工程（仅自己使用），
//      按 CD 检索工作范围内的原木（复用中心的 findBlocks 扫描），找到就自己砍
//   3. 检索无果 → 弹气泡提示（3 秒），5 秒检索一次
public class ChopBehavior extends Behavior<EntityMaid>
{
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private int chopTimer = 0;
    private boolean reachedTree = false;
    private boolean roaming = false;
    private boolean savedPickup = false;

    // 个人工程位：无工程中心时女仆自带的简化工程（容量 1，仅自己使用）
    // 不注册进 ALL_PROJECTS（HUD 不显示），查找走 resolveProject
    // 运行时数据：行为实例重建（切任务/区块重载）后丢失，女仆会重新检索自愈
    private ChoppingProject personalProject = null;

    // 检索/气泡节流：上次检索的游戏 tick（无中心自检索与无目标气泡共用 5 秒节奏）
    private long lastSearchGameTime = 0;

    public ChopBehavior()
    {
        super(Map.of(), Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        LOGGER.debug("[ChopDebug] checkExtraStartConditions ENTER maid=" + maid.getUUID().toString().substring(0, 8));

        // 跟随模式：不加入工程中心也不自干活，提醒玩家切 Home 模式（5 秒节流）
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            LOGGER.debug("[ChopDebug] H1: follow mode -> REJECT with hint");
            if (level.getGameTime() - lastSearchGameTime >= MaidMoreWorkConfig.PERSONAL_SEARCH_CD_TICKS)
            {
                lastSearchGameTime = level.getGameTime();
                IProjectType type = ProjectTypeRegistry.get("chopping");
                String action = type != null ? type.displayName().getString() : "工作";
                MaidBubbleHelper.get(maid).setFollowWarn(action);
            }
            return false;
        }

        ProjectCenterInstance center = ensureCenter(level, maid);
        if (center == null)
        {
            // 无工程中心 → 个人工程位：继续已有工程，或按 CD 检索自干活
            LOGGER.debug("[ChopDebug] H2: no center, try personal work");
            return tryPersonalWork(level, maid);
        }

        LOGGER.debug("[ChopDebug] H3: center OK, pos=" + center.getBlockPos().toShortString()
                + " type=" + center.getProjectTypeId());

        // 已有运行句柄且工程仍存活 → 继续当前工程
        // 有中心时个人工程不再继续（由中心流程接管）
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid != null)
        {
            ChoppingProject existing = isPersonalProject(projectUuid) ? null
                    : ProjectCenterManager.findProject(projectUuid, ChoppingProject.class);
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
            // 有中心但无可用目标 → 气泡（与自检索共用 5 秒节流），文案用中心名 + 类型目标词条
            LOGGER.debug("[ChopDebug] H6: assignOrResume returned null -> REJECT");
            if (level.getGameTime() - lastSearchGameTime >= MaidMoreWorkConfig.PERSONAL_SEARCH_CD_TICKS)
            {
                lastSearchGameTime = level.getGameTime();
                IProjectType type = ProjectTypeRegistry.get("chopping");
                String target = type != null ? type.targetName().getString() : "目标";
                setIdleBubble(maid, "主人，" + center.getName() + "附近没有可用的" + target + "了");
            }
            return false;
        }

        LOGGER.debug("[ChopDebug] H7: project assigned, id=" + project.getId().toString().substring(0, 8) + " -> ACCEPT");

        maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), project.getId());
        return true;
    }

    // ===================== 个人工程位 =====================

    // 无中心时的个人工作流程：
    //   个人工程存活 → 继续；否则按 CD 检索工作范围 → 找到目标建工程自己砍；无果弹气泡
    private boolean tryPersonalWork(ServerLevel level, EntityMaid maid)
    {
        if (personalProject != null)
        {
            if (!personalProject.isCompleted())
            {
                personalProject.claim(maid.getUUID());
                maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), personalProject.getId());
                return true;
            }
            disposePersonalProject();
        }

        if (level.getGameTime() - lastSearchGameTime < MaidMoreWorkConfig.PERSONAL_SEARCH_CD_TICKS)
        {
            return false;
        }
        lastSearchGameTime = level.getGameTime();

        // 区域 chunk 未全部加载 → 本次跳过（不弹气泡，等下次 CD）
        if (!isSearchAreaLoaded(level, maid))
        {
            return false;
        }

        IProjectType type = ProjectTypeRegistry.get("chopping");
        if (type == null)
        {
            return false;
        }

        // 占用快照：跳过已被中心工程/其他个人工程认领的树（全局工程唯一）
        Set<BlockPos> occupied = ProjectCenterManager.collectOccupied(level);

        BlockPos nearest = searchPersonalTargets(level, maid, occupied);
        if (nearest == null)
        {
            // 检索无果（区域已全部加载）→ 弹气泡（本次检索已消耗 CD，直接展示）
            setIdleBubble(maid, "主人，这附近没有可用的" + type.targetName().getString() + "呢");
            return false;
        }

        Set<BlockPos> targets = type.bfs(level, nearest);
        if (targets.isEmpty())
        {
            return false;
        }

        // 整树重叠校验：连通树与占用集有交集 → 已被他人工程占用，放弃（下个 CD 再试）
        for (BlockPos t : targets)
        {
            if (occupied.contains(t))
            {
                return false;
            }
        }

        personalProject = new ChoppingProject(nearest, new ArrayList<>(targets));
        personalProject.setDimension(level.dimension());
        personalProject.claim(maid.getUUID());
        // 并入全局工程表：进度上 HUD、占用快照可见、孤儿清扫可回收
        ProjectCenterManager.registerProject(personalProject);
        maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), personalProject.getId());
        return true;
    }

    // 丢弃个人工程：注销全局注册 + 置空
    private void disposePersonalProject()
    {
        if (personalProject != null)
        {
            ProjectCenterManager.unregisterProject(personalProject.getId());
            personalProject = null;
        }
    }

    // 工作范围（家园半径，无家则默认 15）内区域是否全部加载
    private boolean isSearchAreaLoaded(ServerLevel level, EntityMaid maid)
    {
        int r = maid.hasHome() ? maid.getHomeRadius() : MaidMoreWorkConfig.SEARCH_HALF_XZ;
        BlockPos here = maid.blockPosition();
        int minCX = (here.getX() - r) >> 4;
        int maxCX = (here.getX() + r) >> 4;
        int minCZ = (here.getZ() - r) >> 4;
        int maxCZ = (here.getZ() + r) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++)
        {
            for (int cz = minCZ; cz <= maxCZ; cz++)
            {
                if (!level.hasChunk(cx, cz))
                {
                    return false;
                }
            }
        }
        return true;
    }

    // 复用工程中心的 findBlocks 扫描：工作范围（家园半径，无家则默认 15）内的原木
    // 跳过已占用块（中心工程/其他个人工程认领的树）
    // 返回 null 表示区域内没有可认领的原木（调用前须确保区域已全部加载）
    private BlockPos searchPersonalTargets(ServerLevel level, EntityMaid maid, Set<BlockPos> occupied)
    {
        int r = maid.hasHome() ? maid.getHomeRadius() : MaidMoreWorkConfig.SEARCH_HALF_XZ;
        BlockPos here = maid.blockPosition();
        int minCX = (here.getX() - r) >> 4;
        int maxCX = (here.getX() + r) >> 4;
        int minCZ = (here.getZ() - r) >> 4;
        int maxCZ = (here.getZ() + r) >> 4;

        BlockPos nw = new BlockPos(here.getX() - r, here.getY() - r, here.getZ() - r);
        BlockPos se = new BlockPos(here.getX() + r, here.getY() + r, here.getZ() + r);
        IProjectType type = ProjectTypeRegistry.get("chopping");
        if (type == null)
        {
            return null;
        }
        java.util.function.Predicate<BlockState> filter = type.stateFilter();

        List<BlockPos> found = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++)
        {
            for (int cz = minCZ; cz <= maxCZ; cz++)
            {
                level.getChunk(cx, cz).findBlocks(filter, (pos, state) ->
                {
                    BlockPos immutable = pos.immutable();
                    if (immutable.getX() >= nw.getX() && immutable.getX() <= se.getX()
                            && immutable.getY() >= nw.getY() && immutable.getY() <= se.getY()
                            && immutable.getZ() >= nw.getZ() && immutable.getZ() <= se.getZ())
                    {
                        if (occupied.contains(immutable))
                        {
                            return;
                        }
                        found.add(immutable);
                    }
                });
            }
        }

        if (found.isEmpty())
        {
            return null;
        }

        BlockPos best = found.get(0);
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : found)
        {
            double dist = p.distSqr(here);
            if (dist < bestDist)
            {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    private boolean isPersonalProject(UUID projectUuid)
    {
        return personalProject != null && personalProject.getId().equals(projectUuid);
    }

    // 空闲提示气泡（3 秒展示，节流由调用方控制）
    private void setIdleBubble(EntityMaid maid, String text)
    {
        MaidBubbleHelper.get(maid).set(text, MaidMoreWorkConfig.BUBBLE_DURATION_TICKS);
    }

    // ===================== 中心 =====================

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
            // 距离判定：中心必须在她家园范围内（工作范围 = 家园范围），
            // 否则视为已离开该中心（全局中心实例永远存在，不能只看查得到）
            boolean ok = center != null && "chopping".equals(center.getProjectTypeId())
                    && ProjectCenterManager.isWithinMaidRange(maid, center.getBlockPos());
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

        // C6: 附近没有可加入的中心 → 彻底脱离残留的中心记忆（清记忆/attachment/savedHomes/还家），
        // 之后走个人工程位；放回中心附近时会通过 C4 自动重新加入
        if (centerUuid != null)
        {
            ProjectCenterInstance old = ProjectCenterManager.get(level, centerUuid);
            if (old != null)
            {
                LOGGER.debug("[ChopDebug] C6: leaving stale center, uuid=" + centerUuid.toString().substring(0, 8));
                old.leaveCenter(maid);
            }
        }
        LOGGER.debug("[ChopDebug] C6: no valid center found -> RETURN null");
        return null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        // 运行中切到跟随模式：立即停止（释放中心席位、丢弃个人工程、清记忆），由 stop() 收尾
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            releaseAssignment(maid);
            disposePersonalProject();
            clearAllMemory(maid);
            return false;
        }

        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid == null)
        {
            return false;
        }

        ChoppingProject project = resolveProject(projectUuid);
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

        ChoppingProject project = resolveProject(projectUuid);
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

    // 工程查找：个人工程优先（无中心自干活），否则中心工程
    private ChoppingProject resolveProject(UUID projectUuid)
    {
        if (projectUuid == null)
        {
            return null;
        }
        if (isPersonalProject(projectUuid))
        {
            return personalProject;
        }
        return ProjectCenterManager.findProject(projectUuid, ChoppingProject.class);
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
                    // 中心工程走中心移除；个人工程直接丢弃（注销全局注册）
                    if (!isPersonalProject(project.getId()))
                    {
                        ProjectCenterManager.removeByProject(level, project, false);
                    }
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
        disposePersonalProject();
        clearAllMemory(maid);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        releaseAssignment(maid);
        disposePersonalProject();
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
