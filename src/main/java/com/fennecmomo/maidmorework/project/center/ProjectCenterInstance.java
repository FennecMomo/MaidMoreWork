package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.lib.projecttype.IProjectType;
import com.fennecmomo.maidmorework.lib.projecttype.ProjectTypeRegistry;
import com.fennecmomo.maidmorework.project.ProjectBase;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

// 工程中心后台实例（中心本体）
//
// 全局后台数据类，与方块/区块生命周期完全解耦：
//   - 由 ProjectCenterManager 持有，随 ServerTickEvent.Post 全局驱动
//   - 通过 ProjectCenterData (SavedData) 持久化，世界加载即恢复，区块卸载不影响
//   - 方块（ProjectCenterBlock）与方块实体（ProjectCenterBlockEntity）只是交互桥梁
//
// 工程管理全部归本类：
//   - 目标列表扫描、工程创建/分配/重分配（assignments 为唯一真相）
//   - 区块未加载时的虚拟工作模式（参与者席位保留，不误踢）
//   - 未加载目标的延迟销毁记账（pendingDestroy）
public class ProjectCenterInstance
{
    // ===================== 多态序列化 =====================

    public static final MapCodec<ProjectCenterInstance> MAP_CODEC =
        RecordCodecBuilder.mapCodec(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(ProjectCenterInstance::getId),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(ProjectCenterInstance::getOwner),
            BlockPos.CODEC.fieldOf("blockPos").forGetter(ProjectCenterInstance::getBlockPos),
            Codec.INT.fieldOf("radius").forGetter(ProjectCenterInstance::getRadius),
            Codec.INT.fieldOf("anchor").forGetter(ProjectCenterInstance::getAnchor),
            Codec.STRING.fieldOf("projectTypeId").forGetter(ProjectCenterInstance::getProjectTypeId),
            Codec.BOOL.fieldOf("boundaryVisible").forGetter(ProjectCenterInstance::isBoundaryVisible),
            BlockPos.CODEC.listOf().fieldOf("inactiveTargets")
                    .forGetter(c -> new ArrayList<>(c.inactiveTargets)),
            BlockPos.CODEC.listOf().fieldOf("activeTargets")
                    .forGetter(c -> new ArrayList<>(c.activeTargets)),
            ProjectBase.CODEC.listOf().fieldOf("managedProjects")
                    .forGetter(c -> c.managedProjects),
            AssignmentEntry.CODEC.listOf().fieldOf("assignments")
                    .forGetter(ProjectCenterInstance::encodeAssignments),
            SavedHomeEntry.CODEC.listOf().fieldOf("savedHomes")
                    .forGetter(ProjectCenterInstance::encodeSavedHomes),
            PendingDestroyEntry.CODEC.listOf().fieldOf("pendingDestroy")
                    .forGetter(ProjectCenterInstance::encodePendingDestroy)
        ).apply(inst, ProjectCenterInstance::fromCodec));

    public static final Codec<ProjectCenterInstance> CODEC = MAP_CODEC.codec();

    // Codec 工厂方法：从反序列化字段构建实例
    private static ProjectCenterInstance fromCodec(
        UUID id, UUID owner, BlockPos blockPos,
        int radius, int anchor, String projectTypeId, boolean boundaryVisible,
        List<BlockPos> inactive, List<BlockPos> active, List<ProjectBase> projects,
        List<AssignmentEntry> assignments, List<SavedHomeEntry> savedHomes,
        List<PendingDestroyEntry> pendingDestroy)
    {
        return new ProjectCenterInstance(id, owner, blockPos, radius, anchor,
                projectTypeId, boundaryVisible, inactive, active, projects,
                assignments, savedHomes, pendingDestroy);
    }

    // ===================== Map 持久化辅助 =====================

    // unboundedMap 要求键编码为字符串，UUIDUtil.CODEC（INT 数组）与 BlockPos.CODEC（列表）都不满足，
    // 因此 Map 一律转为 pair 列表编码，解码时再还原
    public record AssignmentEntry(UUID maidUuid, UUID projectUuid)
    {
        public static final Codec<AssignmentEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("maid").forGetter(AssignmentEntry::maidUuid),
            UUIDUtil.CODEC.fieldOf("project").forGetter(AssignmentEntry::projectUuid)
        ).apply(inst, AssignmentEntry::new));
    }

    public record SavedHomeEntry(UUID maidUuid, BlockPos home)
    {
        public static final Codec<SavedHomeEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("maid").forGetter(SavedHomeEntry::maidUuid),
            BlockPos.CODEC.fieldOf("home").forGetter(SavedHomeEntry::home)
        ).apply(inst, SavedHomeEntry::new));
    }

    public record PendingDestroyEntry(BlockPos pos, UUID maidUuid)
    {
        public static final Codec<PendingDestroyEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(PendingDestroyEntry::pos),
            UUIDUtil.CODEC.fieldOf("maid").forGetter(PendingDestroyEntry::maidUuid)
        ).apply(inst, PendingDestroyEntry::new));
    }

    private List<AssignmentEntry> encodeAssignments()
    {
        List<AssignmentEntry> list = new ArrayList<>(assignments.size());
        for (var e : assignments.entrySet())
        {
            list.add(new AssignmentEntry(e.getKey(), e.getValue()));
        }
        return list;
    }

    private List<SavedHomeEntry> encodeSavedHomes()
    {
        List<SavedHomeEntry> list = new ArrayList<>(savedHomes.size());
        for (var e : savedHomes.entrySet())
        {
            list.add(new SavedHomeEntry(e.getKey(), e.getValue()));
        }
        return list;
    }

    private List<PendingDestroyEntry> encodePendingDestroy()
    {
        List<PendingDestroyEntry> list = new ArrayList<>(pendingDestroy.size());
        for (var e : pendingDestroy.entrySet())
        {
            list.add(new PendingDestroyEntry(e.getKey(), e.getValue()));
        }
        return list;
    }

    // ===================== 身份数据 =====================

    private final UUID id;
    private final UUID owner;
    private final BlockPos blockPos;
    private int radius;
    private int anchor;
    private String projectTypeId;
    private boolean boundaryVisible;

    // 几何范围（由 blockPos + radius + anchor 推导，不持久化）
    private BlockPos cornerNW;
    private BlockPos cornerSE;

    // ===================== 业务状态 =====================

    private final Set<BlockPos> inactiveTargets = new HashSet<>();
    private final Set<BlockPos> activeTargets = new HashSet<>();
    private final List<ProjectBase> managedProjects = new ArrayList<>();

    // 女仆↔工程归属（唯一真相，持久化）
    private final Map<UUID, UUID> assignments = new HashMap<>();

    // 女仆 Home 备份（加入中心时改家，离开时恢复；持久化）
    private final Map<UUID, BlockPos> savedHomes = new HashMap<>();

    // 未加载目标的延迟销毁记账（区块加载后结算）
    private final Map<BlockPos, UUID> pendingDestroy = new LinkedHashMap<>();

    // 扫描状态
    private boolean scanInProgress = false;
    private long lastFullScanGameTime = 0;
    private int infoTick = 0;

    // ===================== 构造 =====================

    // 新建中心：放置方块时由 ProjectCenterManager.create 调用
    public ProjectCenterInstance(UUID id, UUID owner, BlockPos blockPos, int radius, int anchor)
    {
        this.id = id;
        this.owner = owner;
        this.blockPos = blockPos;
        this.radius = radius;
        this.anchor = anchor;
        this.projectTypeId = "";
        this.boundaryVisible = false;
        recalcCorners();
    }

    // Codec 反序列化构造：指定全部字段
    private ProjectCenterInstance(UUID id, UUID owner, BlockPos blockPos,
                                  int radius, int anchor, String projectTypeId, boolean boundaryVisible,
                                  List<BlockPos> inactive, List<BlockPos> active, List<ProjectBase> projects,
                                  List<AssignmentEntry> assignments, List<SavedHomeEntry> savedHomes,
                                  List<PendingDestroyEntry> pendingDestroy)
    {
        this(id, owner, blockPos, radius, anchor);
        this.projectTypeId = projectTypeId;
        this.boundaryVisible = boundaryVisible;
        this.inactiveTargets.addAll(inactive);
        this.activeTargets.addAll(active);
        this.managedProjects.addAll(projects);
        for (AssignmentEntry e : assignments)
        {
            this.assignments.put(e.maidUuid(), e.projectUuid());
        }
        for (SavedHomeEntry e : savedHomes)
        {
            this.savedHomes.put(e.maidUuid(), e.home());
        }
        for (PendingDestroyEntry e : pendingDestroy)
        {
            this.pendingDestroy.put(e.pos(), e.maidUuid());
        }
        recalcCorners();
    }

    // ===================== 女仆加入/离开 =====================

    // 女仆加入中心：备份原家（只备份一次，重复加入不覆盖）+ 改家到中心 + 记录归属
    public void joinCenter(EntityMaid maid)
    {
        UUID uuid = maid.getUUID();
        savedHomes.putIfAbsent(uuid, maid.hasHome() ? maid.getHomePosition() : null);
        maid.setHomeTo(getBlockPos(), radius);
        maid.getBrain().setMemory(ModMemories.PROJECT_CENTER_UUID.get(), getId());
    }

    public void leaveCenter(EntityMaid maid)
    {
        UUID uuid = maid.getUUID();
        BlockPos home = savedHomes.remove(uuid);
        if (home != null)
        {
            maid.setHomeTo(home, radius);
        }
        maid.getBrain().eraseMemory(ModMemories.PROJECT_CENTER_UUID.get());
        maid.removeData(ModAttachments.PROJECT_CENTER_UUID_SAVED);
    }

    // ===================== 工程分配 =====================

    // 女仆请求工程：优先恢复已有 assignment，其次加入有空位的工程，最后新建
    // 返回 null 表示无可用目标（女仆应等待）
    public ProjectBase assignOrResume(ServerLevel level, EntityMaid maid)
    {
        UUID maidUuid = maid.getUUID();
        UUID assigned = assignments.get(maidUuid);
        if (assigned != null)
        {
            ProjectBase p = ProjectCenterManager.findProject(assigned, ProjectBase.class);
            if (p != null && !p.isCompleted() && managedProjects.contains(p))
            {
                p.claim(maidUuid);
                return p;
            }
            assignments.remove(maidUuid);
        }

        for (ProjectBase p : managedProjects)
        {
            if (p.hasAvailableSlot())
            {
                p.claim(maidUuid);
                assignments.put(maidUuid, p.getId());
                return p;
            }
        }

        BlockPos nearest = findNearestInactive(maid.blockPosition());
        if (nearest == null) return null;

        IProjectType type = ProjectTypeRegistry.get(projectTypeId);
        if (type == null) return null;

        Set<BlockPos> targets = type.bfs(level, nearest);
        if (targets.isEmpty()) return null;

        ProjectBase project = type.createProject(UUID.randomUUID(), nearest, new ArrayList<>(targets));
        project.setDimension(level.dimension());
        project.claim(maidUuid);
        claimTargets(targets, project);
        assignments.put(maidUuid, project.getId());

        return project;
    }

    // 释放女仆的工程席位（幂等）：女仆停止/收起/确认丢失时调用
    public void releaseAssignment(EntityMaid maid)
    {
        UUID maidUuid = maid.getUUID();
        UUID projectId = assignments.remove(maidUuid);
        if (projectId == null) return;

        ProjectBase p = ProjectCenterManager.findProject(projectId, ProjectBase.class);
        if (p == null) return;

        p.release(maidUuid);
        if (p.getParticipants().isEmpty())
        {
            removeProject(p, p.isCompleted());
        }
    }

    private BlockPos findNearestInactive(BlockPos from)
    {
        BlockPos nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : inactiveTargets)
        {
            double dist = p.distSqr(from);
            if (dist < bestDist)
            {
                bestDist = dist;
                nearest = p;
            }
        }
        return nearest;
    }

    // ===================== 目标流转 API =====================

    public void claimTargets(Collection<BlockPos> targets, ProjectBase project)
    {
        inactiveTargets.removeAll(targets);
        activeTargets.addAll(targets);
        if (!managedProjects.contains(project))
        {
            managedProjects.add(project);
        }
        project.setCenterId(getId());
        ProjectCenterManager.registerProject(project);
    }

    public void releaseTargets(Collection<BlockPos> targets, boolean completed)
    {
        activeTargets.removeAll(targets);
        if (!completed)
        {
            inactiveTargets.addAll(targets);
        }
    }

    public void removeProject(ProjectBase project, boolean completed)
    {
        releaseTargets(project.getTargetBlocks(), completed);
        managedProjects.remove(project);
        ProjectCenterManager.unregisterProject(project.getId());
        assignments.entrySet().removeIf(e -> e.getValue().equals(project.getId()));
    }

    public void onProjectCacheStale(ProjectBase project)
    {
        releaseTargets(project.getTargetBlocks(), false);
    }

    public void onProjectRebuilt(ProjectBase project, Collection<BlockPos> newTargets)
    {
        claimTargets(newTargets, project);
    }

    public void onProjectCompleted(ProjectBase project)
    {
        removeProject(project, true);
    }

    // ===================== 扫描系统 =====================

    private void resetScan()
    {
        inactiveTargets.clear();
        activeTargets.clear();
        for (ProjectBase p : managedProjects)
        {
            ProjectCenterManager.unregisterProject(p.getId());
        }
        managedProjects.clear();
        assignments.clear();
        scanInProgress = false;
        lastFullScanGameTime = 0;
    }

    private void doScanTick(ServerLevel level)
    {
        IProjectType type = ProjectTypeRegistry.get(projectTypeId);
        if (type == null) return;
        if (!isAreaLoaded(level)) return;

        Predicate<BlockState> filter = type.stateFilter();
        Set<BlockPos> active = activeTargets;

        for (int cx = cornerNW.getX() >> 4; cx <= cornerSE.getX() >> 4; cx++)
        {
            for (int cz = cornerNW.getZ() >> 4; cz <= cornerSE.getZ() >> 4; cz++)
            {
                level.getChunk(cx, cz).findBlocks(filter, (pos, state) ->
                {
                    BlockPos immutable = pos.immutable();
                    if (!contains(immutable)) return;
                    if (!active.contains(immutable))
                    {
                        inactiveTargets.add(immutable);
                    }
                });
            }
        }

        scanInProgress = false;
        lastFullScanGameTime = level.getGameTime();
    }

    // 中心活动区域（cornerNW~cornerSE 的 chunk 范围）是否全部已加载
    // 用于参与者判定：区域已加载且找不到女仆 = 女仆真丢失；区域未加载 = 虚拟工作模式
    public boolean isAreaLoaded(ServerLevel level)
    {
        int minCX = cornerNW.getX() >> 4;
        int maxCX = cornerSE.getX() >> 4;
        int minCZ = cornerNW.getZ() >> 4;
        int maxCZ = cornerSE.getZ() >> 4;
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

    // ===================== 延迟销毁 =====================

    public void processPendingDestroys(ServerLevel level)
    {
        if (pendingDestroy.isEmpty()) return;
        List<BlockPos> done = new ArrayList<>();
        for (var entry : pendingDestroy.entrySet())
        {
            BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos)) continue;
            UUID maidUuid = entry.getValue();
            EntityMaid maid = null;
            Entity e = level.getEntity(maidUuid);
            if (e instanceof EntityMaid m) maid = m;

            BlockState state = level.getBlockState(pos);
            if (!state.isAir())
            {
                if (maid != null)
                {
                    maid.getItemManager().dropResourcesToMaidInv(
                            state, level, pos, level.getBlockEntity(pos), maid.getMainHandItem());
                }
                level.destroyBlock(pos, true);
            }
            done.add(pos);
        }
        for (BlockPos pos : done)
        {
            pendingDestroy.remove(pos);
        }
    }

    public void addPendingDestroy(BlockPos pos, UUID maidUuid)
    {
        pendingDestroy.put(pos, maidUuid);
    }

    // ===================== 工程推进 =====================

    // 参与者判定三分支：
    //   实体在 → 正常推进
    //   实体不在 + 区域全部已加载 → 女仆真丢失（死/消失/其他维度）→ 清退
    //   实体不在 + 区域未完全加载 → 虚拟工作模式：席位保留，等待女仆回来
    private void doProjectTick(ServerLevel level)
    {
        List<ProjectBase> toRemove = new ArrayList<>();
        boolean areaLoaded = isAreaLoaded(level);

        for (ProjectBase p : managedProjects)
        {
            if (p.isCompleted())
            {
                toRemove.add(p);
                continue;
            }

            List<UUID> toRelease = new ArrayList<>();
            for (UUID uuid : new ArrayList<>(p.getParticipants()))
            {
                if (level.getEntity(uuid) instanceof EntityMaid)
                {
                    continue;
                }
                if (areaLoaded)
                {
                    toRelease.add(uuid);
                }
            }
            for (UUID uuid : toRelease)
            {
                p.release(uuid);
                assignments.remove(uuid);
            }

            if (p.getParticipants().isEmpty())
            {
                toRemove.add(p);
                continue;
            }

            p.tick(level);
        }

        for (ProjectBase p : toRemove)
        {
            removeProject(p, p.isCompleted());
        }
    }

    // ===================== 周期调度（由 ProjectCenterManager 全局驱动） =====================

    public void tick(ServerLevel level)
    {
        if (projectTypeId.isEmpty()) return;

        if (!scanInProgress)
        {
            long interval = MaidMoreWorkConfig.REFRESH_INTERVAL_TICKS;
            if (lastFullScanGameTime == 0
                    || level.getGameTime() - lastFullScanGameTime >= interval)
            {
                scanInProgress = true;
            }
        }

        if (scanInProgress)
        {
            doScanTick(level);
        }
        else
        {
            infoTick++;
            if (infoTick >= 20)
            {
                infoTick = 0;
                IProjectType type = ProjectTypeRegistry.get(projectTypeId);
                String typeName = type != null ? type.displayName().getString() : projectTypeId;
                var info = new ProjectCenterInfoPayload(
                        getBlockPos(), typeName, managedProjects.size(), savedHomes.size(), getRadius());
                PacketDistributor.sendToAllPlayers(info);
            }
        }

        doProjectTick(level);
        processPendingDestroys(level);
    }

    // ===================== 数据访问 =====================

    public UUID getId() { return id; }
    public UUID getOwner() { return owner; }
    public BlockPos getBlockPos() { return blockPos; }
    public int getRadius() { return radius; }
    public int getAnchor() { return anchor; }
    public String getProjectTypeId() { return projectTypeId; }
    public boolean isBoundaryVisible() { return boundaryVisible; }
    public int getInactiveCount() { return inactiveTargets.size(); }
    public int getManagedProjectCount() { return managedProjects.size(); }
    public int getActiveCount() { return activeTargets.size(); }
    public List<ProjectBase> getManagedProjects() { return managedProjects; }

    public void setBoundaryVisible(boolean visible)
    {
        this.boundaryVisible = visible;
    }

    public void setRadius(int radius)
    {
        this.radius = radius;
        recalcCorners();
        resetScan();
    }

    public void setAnchor(int anchor)
    {
        this.anchor = anchor;
        recalcCorners();
        resetScan();
    }

    public void setProjectTypeId(String projectTypeId)
    {
        this.projectTypeId = projectTypeId;
    }

    public BlockPos getMinCorner() { return cornerNW; }
    public BlockPos getMaxCorner() { return cornerSE; }

    // ===================== 几何 =====================

    private void recalcCorners()
    {
        int r = radius;
        int minY = switch (anchor)
        {
            case 0 -> blockPos.getY() - 2 * r;
            case 2 -> blockPos.getY();
            default -> blockPos.getY() - r;
        };
        int maxY = switch (anchor)
        {
            case 0 -> blockPos.getY();
            case 2 -> blockPos.getY() + 2 * r;
            default -> blockPos.getY() + r;
        };
        this.cornerNW = new BlockPos(blockPos.getX() - r, minY, blockPos.getZ() - r);
        this.cornerSE = new BlockPos(blockPos.getX() + r, maxY, blockPos.getZ() + r);
    }

    public boolean contains(BlockPos pos)
    {
        return pos.getX() >= cornerNW.getX() && pos.getX() <= cornerSE.getX()
                && pos.getY() >= cornerNW.getY() && pos.getY() <= cornerSE.getY()
                && pos.getZ() >= cornerNW.getZ() && pos.getZ() <= cornerSE.getZ();
    }
}
