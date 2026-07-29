package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.lib.projecttype.IProjectType;
import com.fennecmomo.maidmorework.lib.projecttype.ProjectTypeRegistry;
import com.fennecmomo.maidmorework.lib.region.IRegionalManager;
import com.fennecmomo.maidmorework.lib.region.RegionalManagerRegistry;
import com.fennecmomo.maidmorework.project.ProjectBase;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ProjectCenterBlockEntity extends BlockEntity implements IRegionalManager
{
    private static final Map<Object, Set<BlockPos>> OCCUPIED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, ProjectBase> ALL_PROJECTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isBlockOccupied(ServerLevel level, BlockPos pos)
    {
        Set<BlockPos> set = OCCUPIED.get(level.dimension());
        return set != null && set.contains(pos);
    }

    @SuppressWarnings("unchecked")
    public static <T extends ProjectBase> T findProject(UUID projectId, Class<T> clazz)
    {
        if (projectId == null) return null;
        ProjectBase p = ALL_PROJECTS.get(projectId);
        if (clazz.isInstance(p)) return (T) p;
        return null;
    }

    public static void registerProject(ProjectBase project)
    {
        ALL_PROJECTS.put(project.getId(), project);
    }

    public static void unregisterProject(UUID projectId)
    {
        ALL_PROJECTS.remove(projectId);
    }

    public static <T extends ProjectBase> T findAvailable(EntityMaid maid)
    {
        T best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos maidPos = maid.blockPosition();
        int rangeSq = 30 * 30;

        for (ProjectBase project : ALL_PROJECTS.values())
        {
            if (!project.hasAvailableSlot()) continue;
            if (project.isCompleted()) continue;

            @SuppressWarnings("unchecked")
            T typed = (T) project;
            double dist = maidPos.distSqr(project.getPosition());
            if (dist <= rangeSq && dist < bestDist)
            {
                bestDist = dist;
                best = typed;
            }
        }
        return best;
    }

    public static java.util.Collection<ProjectBase> getAllProjects()
    {
        return ALL_PROJECTS.values();
    }

    public static com.fennecmomo.maidmorework.project.hud.ProjectHudPayload getNearby(net.minecraft.server.level.ServerPlayer player, int range)
    {
        java.util.List<com.fennecmomo.maidmorework.project.hud.ProjectHudPayload.Entry> list = new java.util.ArrayList<>();
        int rangeSq = range * range;
        BlockPos playerPos = player.blockPosition();
        for (ProjectBase project : ALL_PROJECTS.values())
        {
            if (project.isCompleted()) continue;
            if (playerPos.distSqr(project.getPosition()) <= rangeSq)
            {
                list.add(com.fennecmomo.maidmorework.project.hud.ProjectHudPayload.Entry.from(project));
            }
        }
        return new com.fennecmomo.maidmorework.project.hud.ProjectHudPayload(list);
    }

    public static ProjectCenterBlockEntity findNearestActiveCenter(EntityMaid maid)
    {
        ProjectCenterBlockEntity best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos maidPos = maid.blockPosition();
        for (IRegionalManager mgr : RegionalManagerRegistry.values())
        {
            if (mgr instanceof ProjectCenterBlockEntity be)
            {
                if (!be.hasInstance() || be.projectTypeId.isEmpty()) continue;
                double dist = maidPos.distSqr(be.getBlockPos());
                if (dist < bestDist)
                {
                    bestDist = dist;
                    best = be;
                }
            }
        }
        return best;
    }

    public boolean hasWork()
    {
        if (!inactiveTargets.isEmpty()) return true;
        for (ProjectBase p : managedProjects)
        {
            if (p.hasAvailableSlot()) return true;
        }
        return false;
    }

    public static void removeByProject(ProjectBase project, boolean completed)
    {
        unregisterProject(project.getId());
        if (project.getCenterId() != null)
        {
            IRegionalManager mgr = RegionalManagerRegistry.get(project.getCenterId());
            if (mgr instanceof ProjectCenterBlockEntity be)
            {
                be.releaseTargets(project.getTargetBlocks(), completed);
                be.managedProjects.remove(project);
            }
        }
    }

    public static void releaseMaid(EntityMaid maid)
    {
        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        if (projectUuid == null) return;

        ProjectBase project = findProject(projectUuid, ProjectBase.class);
        if (project == null)
        {
            maid.getBrain().eraseMemory(ModMemories.PROJECT_UUID.get());
            maid.removeData(ModAttachments.PROJECT_UUID_SAVED);
            return;
        }

        project.release(maid.getUUID());
        maid.getBrain().eraseMemory(ModMemories.PROJECT_UUID.get());
        maid.removeData(ModAttachments.PROJECT_UUID_SAVED);

        if (project.getParticipants().isEmpty())
        {
            removeByProject(project, false);
        }
    }

    private void addToOccupied(java.util.Collection<BlockPos> targets)
    {
        if (level == null || targets.isEmpty()) return;
        OCCUPIED.computeIfAbsent(level.dimension(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).addAll(targets);
    }

    private void removeFromOccupied(java.util.Collection<BlockPos> targets)
    {
        if (level == null || targets.isEmpty()) return;
        Set<BlockPos> set = OCCUPIED.get(level.dimension());
        if (set != null) set.removeAll(targets);
    }
    private long idMost = 0L;
    private long idLeast = 0L;
    private long ownerMost = 0L;
    private long ownerLeast = 0L;

    private BlockPos cornerNW = BlockPos.ZERO;
    private BlockPos cornerSE = BlockPos.ZERO;
    private int radius = 5;
    private int anchor = 1;
    private boolean boundaryVisible = true;
    private String projectTypeId = "";

    private final Set<BlockPos> inactiveTargets = new HashSet<>();
    private final Set<BlockPos> activeTargets = new HashSet<>();
    private final List<ProjectBase> managedProjects = new ArrayList<>();

    // 扫描状态
    private boolean scanInProgress = false;
    private int scanCursor = 0;
    private int scanTotal = 0;
    private long lastFullScanGameTime = 0;

    private int infoTick = 0;

    // 女仆 Home 保存
    private final Map<UUID, BlockPos> savedHomes = new HashMap<>();

    public ProjectCenterBlockEntity(BlockPos pos, BlockState state)
    {
        super(ProjectCenterRegistration.PROJECT_CENTER_BLOCK_ENTITY.get(), pos, state);
    }

    // ===================== 女仆加入/离开 =====================

    public void joinCenter(EntityMaid maid)
    {
        UUID uuid = maid.getUUID();
        if (maid.hasHome())
        {
            savedHomes.put(uuid, maid.getHomePosition());
        }
        maid.setHomeTo(getBlockPos(), radius);
        maid.getBrain().setMemory(
                com.fennecmomo.maidmorework.ModMemories.PROJECT_CENTER_UUID.get(), getId());
    }

    public void leaveCenter(EntityMaid maid)
    {
        UUID uuid = maid.getUUID();
        BlockPos home = savedHomes.remove(uuid);
        if (home != null)
        {
            maid.setHomeTo(home, radius);
        }
        maid.getBrain().eraseMemory(com.fennecmomo.maidmorework.ModMemories.PROJECT_CENTER_UUID.get());
    }

    // ===================== 工程分配 =====================

    public ProjectBase findOrCreateProject(EntityMaid maid)
    {
        ServerLevel level = (ServerLevel) maid.level();

        for (ProjectBase p : managedProjects)
        {
            if (p.hasAvailableSlot())
            {
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
        claimTargets(targets, project);

        return project;
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

    public void claimTargets(java.util.Collection<BlockPos> targets, ProjectBase project)
    {
        inactiveTargets.removeAll(targets);
        activeTargets.addAll(targets);
        managedProjects.add(project);
        project.setCenterId(getId());
        addToOccupied(targets);
        registerProject(project);
    }

    public void releaseTargets(java.util.Collection<BlockPos> targets, boolean completed)
    {
        activeTargets.removeAll(targets);
        removeFromOccupied(targets);
        if (!completed)
        {
            inactiveTargets.addAll(targets);
        }
    }

    public void removeProject(ProjectBase project, boolean completed)
    {
        releaseTargets(project.getTargetBlocks(), completed);
        managedProjects.remove(project);
        unregisterProject(project.getId());
    }

    public void onProjectCacheStale(ProjectBase project)
    {
        releaseTargets(project.getTargetBlocks(), false);
    }

    public void onProjectRebuilt(ProjectBase project, java.util.Collection<BlockPos> newTargets)
    {
        claimTargets(newTargets, project);
    }

    public void onProjectCompleted(ProjectBase project)
    {
        releaseTargets(project.getTargetBlocks(), true);
        managedProjects.remove(project);
        unregisterProject(project.getId());
    }

    // ===================== 扫描系统 =====================

    private void resetScan()
    {
        inactiveTargets.clear();
        activeTargets.clear();
        for (ProjectBase p : managedProjects)
        {
            unregisterProject(p.getId());
        }
        managedProjects.clear();
        scanInProgress = false;
        scanCursor = 0;
        lastFullScanGameTime = 0;
    }

    private void startScan()
    {
        scanInProgress = true;
        scanCursor = 0;
        BlockPos min = cornerNW;
        BlockPos max = cornerSE;
        int dx = max.getX() - min.getX() + 1;
        int dy = max.getY() - min.getY() + 1;
        int dz = max.getZ() - min.getZ() + 1;
        scanTotal = dx * dy * dz;
    }

    private void doScanTick(ServerLevel level)
    {
        IProjectType type = ProjectTypeRegistry.get(projectTypeId);
        if (type == null || scanTotal == 0) return;

        BlockPos min = cornerNW;
        int dx = cornerSE.getX() - min.getX() + 1;
        int dz = cornerSE.getZ() - min.getZ() + 1;
        int slice = dx * dz;

        int batch = MaidMoreWorkConfig.SCAN_BLOCKS_PER_TICK;
        for (int i = 0; i < batch && scanCursor < scanTotal; i++, scanCursor++)
        {
            int idx = scanCursor;
            int x = min.getX() + idx % dx;
            int z = min.getZ() + (idx / dx) % dz;
            int y = min.getY() + idx / slice;
            BlockPos pos = new BlockPos(x, y, z);

            if (activeTargets.contains(pos) || inactiveTargets.contains(pos)) continue;

            if (type.isValidTarget(level, pos))
            {
                inactiveTargets.add(pos);
            }
        }

        if (scanCursor >= scanTotal)
        {
            scanInProgress = false;
            lastFullScanGameTime = level.getGameTime();
            sendScanPayload();
        }
        else
        {
            sendScanPayload();
        }
    }

    private void sendScanPayload()
    {
        if (level instanceof ServerLevel serverLevel)
        {
            var payload = new ProjectCenterScanPayload(getBlockPos(), scanInProgress ? scanCursor : 0, scanInProgress ? scanTotal : 0);
            net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(payload);
        }
    }

    private void doProjectTick(ServerLevel level)
    {
        List<ProjectBase> toRemove = new ArrayList<>();
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
                Entity entity = level.getEntity(uuid);
                if (!(entity instanceof EntityMaid))
                {
                    toRelease.add(uuid);
                }
            }
            for (UUID uuid : toRelease)
            {
                p.release(uuid);
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
            if (p.isCompleted())
            {
                removeProject(p, true);
            }
            else
            {
                removeProject(p, false);
            }
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ProjectCenterBlockEntity be)
    {
        if (!be.hasInstance() || be.projectTypeId.isEmpty()) return;
        ServerLevel serverLevel = (ServerLevel) level;

        if (!be.scanInProgress)
        {
            long interval = (long) MaidMoreWorkConfig.REFRESH_INTERVAL_MINUTES * 60 * 20;
            if (be.lastFullScanGameTime == 0
                    || serverLevel.getGameTime() - be.lastFullScanGameTime >= interval)
            {
                be.startScan();
            }
        }

        if (be.scanInProgress)
        {
            be.doScanTick(serverLevel);
        }
        else
        {
            be.infoTick++;
            if (be.infoTick >= 20)
            {
                be.infoTick = 0;
                com.fennecmomo.maidmorework.lib.projecttype.IProjectType type =
                        ProjectTypeRegistry.get(be.projectTypeId);
                String typeName = type != null ? type.displayName().getString() : be.projectTypeId;
                var info = new ProjectCenterInfoPayload(
                        be.getBlockPos(), typeName, be.managedProjects.size(), be.savedHomes.size());
                net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(info);
            }
        }

        be.doProjectTick(serverLevel);
    }

    // ===================== 原有 API =====================

    public boolean hasInstance()
    {
        return idMost != 0L || idLeast != 0L;
    }

    @Override
    public UUID getId()
    {
        if (!hasInstance()) return null;
        return new UUID(idMost, idLeast);
    }

    public UUID getOwner()
    {
        return new UUID(ownerMost, ownerLeast);
    }

    @Override
    public BlockPos getMinCorner() { return cornerNW; }

    @Override
    public BlockPos getMaxCorner() { return cornerSE; }

    @Override
    public void setBoundaryVisible(boolean visible)
    {
        this.boundaryVisible = visible;
        setChanged();
    }

    @Override
    public boolean isBoundaryVisible()
    {
        return boundaryVisible;
    }

    public void setInstanceData(UUID id, UUID owner, int radius, BlockPos nw, BlockPos se)
    {
        this.idMost = id.getMostSignificantBits();
        this.idLeast = id.getLeastSignificantBits();
        this.ownerMost = owner.getMostSignificantBits();
        this.ownerLeast = owner.getLeastSignificantBits();
        this.radius = radius;
        this.cornerNW = nw;
        this.cornerSE = se;
        this.boundaryVisible = false;
        this.projectTypeId = "";
        this.inactiveTargets.clear();
        this.activeTargets.clear();
        this.managedProjects.clear();
        setChanged();
    }

    public int getRadius() { return radius; }
    public int getAnchor() { return anchor; }
    public String getProjectTypeId() { return projectTypeId; }

    public void setRadius(int radius)
    {
        this.radius = radius;
        recalcCorners();
        resetScan();
        setChanged();
    }

    public void setAnchor(int anchor)
    {
        this.anchor = anchor;
        recalcCorners();
        resetScan();
        setChanged();
    }

    public void setProjectTypeId(String projectTypeId)
    {
        this.projectTypeId = projectTypeId;
        setChanged();
    }

    private void recalcCorners()
    {
        BlockPos center = getBlockPos();
        int r = radius;
        int minY = switch (anchor)
        {
            case 0 -> center.getY() - 2 * r;
            case 2 -> center.getY();
            default -> center.getY() - r;
        };
        int maxY = switch (anchor)
        {
            case 0 -> center.getY();
            case 2 -> center.getY() + 2 * r;
            default -> center.getY() + r;
        };
        this.cornerNW = new BlockPos(center.getX() - r, minY, center.getZ() - r);
        this.cornerSE = new BlockPos(center.getX() + r, maxY, center.getZ() + r);
    }

    // ===================== NBT =====================

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        idMost = input.getLongOr("idMost", 0L);
        idLeast = input.getLongOr("idLeast", 0L);
        ownerMost = input.getLongOr("ownerMost", 0L);
        ownerLeast = input.getLongOr("ownerLeast", 0L);
        cornerNW = new BlockPos(
                input.getIntOr("nwX", 0),
                input.getIntOr("nwY", 0),
                input.getIntOr("nwZ", 0));
        cornerSE = new BlockPos(
                input.getIntOr("seX", 0),
                input.getIntOr("seY", 0),
                input.getIntOr("seZ", 0));
        boundaryVisible = input.getBooleanOr("boundaryVisible", true);
        radius = input.getIntOr("radius", 5);
        anchor = input.getIntOr("anchor", 1);
        projectTypeId = input.getStringOr("projectTypeId", "");

        inactiveTargets.clear();
        int inactiveCount = input.getIntOr("ic", 0);
        for (int i = 0; i < inactiveCount; i++)
        {
            inactiveTargets.add(new BlockPos(
                    input.getIntOr("ix" + i, 0),
                    input.getIntOr("iy" + i, 0),
                    input.getIntOr("iz" + i, 0)));
        }

        activeTargets.clear();
        int activeCount = input.getIntOr("ac", 0);
        for (int i = 0; i < activeCount; i++)
        {
            activeTargets.add(new BlockPos(
                    input.getIntOr("ax" + i, 0),
                    input.getIntOr("ay" + i, 0),
                    input.getIntOr("az" + i, 0)));
        }

        managedProjects.clear();
        int projCount = input.getIntOr("pc", 0);
        for (int i = 0; i < projCount; i++)
        {
            String data = input.getStringOr("pj" + i, "");
            if (!data.isEmpty())
            {
                try
                {
                    Tag tag = TagParser.create(NbtOps.INSTANCE).parseFully(data);
                    ProjectBase p = ProjectBase.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
                    managedProjects.add(p);
                }
                catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onLoad()
    {
        super.onLoad();
        if (hasInstance())
        {
        RegionalManagerRegistry.register(this);
        addToOccupied(activeTargets);
        for (ProjectBase p : managedProjects)
        {
            registerProject(p);
        }
        if (level != null && !level.isClientSide())
            {
                UUID id = getId();
                if (ProjectCenterInstanceManager.get(level, id) == null)
                {
                    ProjectCenterInstance inst = new ProjectCenterInstance(id, getOwner(), cornerNW, cornerSE);
                    ProjectCenterInstanceManager.put(level, inst);
                }
            }
        }
    }

    @Override
    public void setRemoved()
    {
        super.setRemoved();
        removeFromOccupied(activeTargets);
        removeFromOccupied(inactiveTargets);
        if (hasInstance() && level != null && !level.isClientSide())
        {
            RegionalManagerRegistry.unregister(getId());
            ProjectCenterInstanceManager.remove(level, getId());
        }
    }

    @Override
    public void onChunkUnloaded()
    {
        super.onChunkUnloaded();
        removeFromOccupied(activeTargets);
        removeFromOccupied(inactiveTargets);
        if (hasInstance())
        {
            RegionalManagerRegistry.unregister(getId());
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        if (hasInstance())
        {
            output.putLong("idMost", idMost);
            output.putLong("idLeast", idLeast);
            output.putLong("ownerMost", ownerMost);
            output.putLong("ownerLeast", ownerLeast);
            output.putInt("nwX", cornerNW.getX());
            output.putInt("nwY", cornerNW.getY());
            output.putInt("nwZ", cornerNW.getZ());
            output.putInt("seX", cornerSE.getX());
            output.putInt("seY", cornerSE.getY());
            output.putInt("seZ", cornerSE.getZ());
            output.putBoolean("boundaryVisible", boundaryVisible);
            output.putInt("radius", radius);
            output.putInt("anchor", anchor);
            output.putString("projectTypeId", projectTypeId);

            output.putInt("ic", inactiveTargets.size());
            int idx = 0;
            for (BlockPos p : inactiveTargets)
            {
                output.putInt("ix" + idx, p.getX());
                output.putInt("iy" + idx, p.getY());
                output.putInt("iz" + idx, p.getZ());
                idx++;
            }

            output.putInt("ac", activeTargets.size());
            idx = 0;
            for (BlockPos p : activeTargets)
            {
                output.putInt("ax" + idx, p.getX());
                output.putInt("ay" + idx, p.getY());
                output.putInt("az" + idx, p.getZ());
                idx++;
            }

            output.putInt("pc", managedProjects.size());
            for (int i = 0; i < managedProjects.size(); i++)
            {
                DataResult<Tag> result = ProjectBase.CODEC.encodeStart(NbtOps.INSTANCE, managedProjects.get(i));
                Tag tag = result.getOrThrow();
                output.putString("pj" + i, tag.toString());
            }
        }
    }
}
