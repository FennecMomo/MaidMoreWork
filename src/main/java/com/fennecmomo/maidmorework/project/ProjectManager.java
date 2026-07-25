package com.fennecmomo.maidmorework.project;

import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 全局工程管理器（静态单例）
//
// 统一管理所有工程实例的全生命周期：
//   - 注册/注销工程
//   - 类型安全的工程获取（getProject / getAvailableProject）
//   - 每 60 tick 遍历检查：loaded 状态、缓存空洞、isActive 参与者清理、isCompleted 自动移除
//   - 与 ProjectData (SavedData) 同步，实现工程数据持久化
//   - 维度+坐标 → 工程的三维映射表：供女仆螺旋检索时查询某坐标是否已被已有工程占用
//
// 由 MaidMoreWork 构造函数中注册 MaidTickEvent 驱动 tick
public final class ProjectManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 检查间隔：60 tick = 3 秒
    private static final int CHECK_INTERVAL = 60;

    // 全部工程实例：projectId → project
    private static final Map<UUID, ProjectBase> PROJECTS = new HashMap<>();

    // 维度 → (坐标 → 工程 UUID) 映射表：供女仆检索时快速判断某坐标是否已被工程占用
    private static final Map<ResourceKey<Level>, Map<BlockPos, UUID>> DIMENSION_POS_TO_PROJECT = new ConcurrentHashMap<>();

    // tick 计数器
    private static int tickCounter = 0;
    private static int hudSyncCounter = 0;
    private static boolean hudForceSync = false;

    public static void requestHudSync()
    {
        hudForceSync = true;
    }

    // 持久化脏标记：工程注册/注销/完成时置 true，下次 tick 同步到 SavedData
    private static boolean dataDirty = false;

    private ProjectManager() {}

    // ===================== 工程注册 =====================

    // 注册新工程到管理器
    public static void register(ProjectBase project)
    {
        PROJECTS.put(project.getId(), project);
        claimPositions(project);
        dataDirty = true;
        LOGGER.info("ProjectManager: registered project {} (type={}, pos={})",
                project.getId(), project.getClass().getSimpleName(), project.getPosition());
    }

    // 从管理器注销工程
    public static void remove(UUID projectId)
    {
        ProjectBase removed = PROJECTS.remove(projectId);
        if (removed != null)
        {
            releasePositions(removed);
            dataDirty = true;
            LOGGER.info("ProjectManager: removed project {}", projectId);
        }
    }

    // 按 UUID + 类型安全获取工程，类型不匹配返回 null
    @SuppressWarnings("unchecked")
    public static <T extends ProjectBase> T getProject(UUID projectId, Class<T> projectClass)
    {
        if (projectId == null) return null;
        ProjectBase project = PROJECTS.get(projectId);
        if (projectClass.isInstance(project))
        {
            return (T) project;
        }
        return null;
    }

    // 获取未完成的指定类型工程（用于 Behavior 启动条件检查）
    public static <T extends ProjectBase> T getAvailableProject(UUID projectId, Class<T> projectClass)
    {
        T project = getProject(projectId, projectClass);
        if (project != null && !project.isCompleted())
        {
            return project;
        }
        return null;
    }

    // 获取所有工程（只读视图，供遍历）
    public static Map<UUID, ProjectBase> getAllProjects()
    {
        return PROJECTS;
    }

    // ===================== 查找可用工程 =====================

    // 在指定范围内查找可用工程（无人或未满员）
    // rangeSq: 搜索距离平方（以女仆位置为原点）
    // projectClass: 工程类型过滤（如 ChoppingProject.class）
    // 优先返回距离最近的可用工程，无则返回 null
    public static <T extends ProjectBase> T findAvailableProject(
            EntityMaid maid, int rangeSq, Class<T> projectClass)
    {
        T best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos maidPos = maid.blockPosition();

        for (ProjectBase project : PROJECTS.values())
        {
            if (!projectClass.isInstance(project)) continue;
            if (!project.hasAvailableSlot()) continue;
            // 防御性检查：tick 每 60 tick 清理一次，两次 tick 之间可能有工程刚完成
            // 此处拦截避免返回一个即将被移除的工程
            if (project.isCompleted()) continue;

            T typed = projectClass.cast(project);
            double dist = maidPos.distSqr(project.getPosition());
            if (dist <= rangeSq && dist < bestDist)
            {
                bestDist = dist;
                best = typed;
            }
        }
        return best;
    }

    // ===================== 周期检查 =====================

    // 每 tick 调用（由 MaidTickEvent 驱动），内部按 CHECK_INTERVAL 实际执行
    // 检查内容：懒加载 SavedData → loaded 跳过 → 空洞检测 → 完成移除 → 参与者清理
    public static void tick(ServerLevel level)
    {
        // ====== 1. 懒加载 SavedData ======
        if (!dataLoaded)
        {
            ensureLoaded(level);
        }

        // ====== 2. HUD 同步（每 tick 检查） ======
        // 定期：HUD_SYNC_INTERVAL(10) tick / 强制：execute() 用 requestHudSync() 标记
        hudSyncCounter++;
        if (hudSyncCounter >= MaidMoreWorkConfig.HUD_SYNC_INTERVAL || hudForceSync)
        {
            hudSyncCounter = 0;
            hudForceSync = false;
            syncHudToPlayers(level);
        }

        // ====== 3. 工程定期检查（每 60 tick 一次） ======
        tickCounter++;
        if (tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        LOGGER.info("ProjectManager: tick checking {} projects", PROJECTS.size());

        // ====== 4. 遍历工程：空洞检测 → 完成移除 → 参与者清理 ======
        Iterator<Map.Entry<UUID, ProjectBase>> it = PROJECTS.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<UUID, ProjectBase> entry = it.next();
            ProjectBase project = entry.getValue();

            // 4a. 休眠工程跳过（所在区块卸载中）
            if (!project.isLoaded())
            {
                continue;
            }

            // 4b. 缓存空洞检测（CountingProject.tick 中调用 rebuild）
            project.tick(level);

            // 4c. 已完成 → onComplete + 移除
            if (checkCompleted(level, project, it)) continue;

            // 4d. 参与者活跃检查 → 清理不活跃的参与者，全部清空则移除工程
            cleanInactiveParticipants(level, project, it);
        }

        // ====== 5. 遍历后收尾 ======
        // 本轮可能清理了参与者，标记下轮 HUD 立即同步
        requestHudSync();

        // 脏数据同步到 SavedData
        if (dataDirty)
        {
            syncToData(level);
            dataDirty = false;
        }
    }

    // ===================== 持久化集成 =====================

    // 是否已从 SavedData 加载过
    private static boolean dataLoaded = false;

    // 懒加载：从 SavedData 恢复工程到内存缓存
    private static void ensureLoaded(ServerLevel level)
    {
        if (dataLoaded) return;
        dataLoaded = true;
        try
        {
            ProjectData data = level.getDataStorage().computeIfAbsent(ProjectData.TYPE);
            data.load();
            LOGGER.info("ProjectManager: loaded from SavedData, {} projects", PROJECTS.size());
        }
        catch (Exception e)
        {
            LOGGER.error("ProjectManager: failed to load from SavedData", e);
        }
    }

    // 从 ProjectData 加载工程（由 ProjectData.load() 调用）
    public static void loadFromData(ProjectData data)
    {
        PROJECTS.clear();
        for (ProjectBase project : data.getProjects())
        {
            PROJECTS.put(project.getId(), project);
        }
    }

    // 将内存工程同步到 SavedData 并标记脏
    private static void syncToData(ServerLevel level)
    {
        try
        {
            ProjectData data = level.getDataStorage().computeIfAbsent(ProjectData.TYPE);
            data.sync(PROJECTS);
        }
        catch (Exception e)
        {
            LOGGER.error("ProjectManager: failed to sync to SavedData", e);
        }
    }

    // ===================== 内部辅助 =====================

    // 检查工程是否已完成：是则调用 onComplete + 移除工程
    // 返回 true 表示工程已移除，外层应 continue
    private static boolean checkCompleted(ServerLevel level, ProjectBase project,
                                          Iterator<Map.Entry<UUID, ProjectBase>> it)
    {
        if (!project.isCompleted()) return false;

        project.onComplete(level);
        removeProject(project, it);
        return true;
    }

    // 遍历参与者，委托 isActive 逐一判定是否仍在工程中
    // 不活跃的清除后，参与者为空则移除工程
    private static void cleanInactiveParticipants(ServerLevel level, ProjectBase project,
                                                   Iterator<Map.Entry<UUID, ProjectBase>> it)
    {
        // 1. 收集不活跃的参与者
        List<UUID> toRemove = new ArrayList<>();
        for (UUID maidUuid : project.getParticipants())
        {
            if (!project.isActive(level, maidUuid))
            {
                toRemove.add(maidUuid);
            }
        }

        // 2. 逐一清退
        for (UUID maidUuid : toRemove)
        {
            LOGGER.info("ProjectManager: releasing inactive maid {} from project {}",
                    maidUuid, project.getId());
            project.release(maidUuid);
        }

        // 3. 参与者全部清空 → 移除孤儿工程
        if (project.getParticipants().isEmpty())
        {
            removeProject(project, it);
        }
    }

    // 释放坐标映射 + 从工程列表中移除（checkCompleted / cleanInactiveParticipants 共用）
    private static void removeProject(ProjectBase project,
                                       Iterator<Map.Entry<UUID, ProjectBase>> it)
    {
        releasePositions(project);
        it.remove();
        dataDirty = true;
        hudForceSync = true;
        LOGGER.info("ProjectManager: project {} removed", project.getId());
    }

    // ===================== 维度+坐标映射表 =====================

    // 查询某维度某坐标是否已被已有工程占用
    public static boolean isPositionClaimed(ServerLevel level, BlockPos pos)
    {
        Map<BlockPos, UUID> posMap = DIMENSION_POS_TO_PROJECT.get(level.dimension());
        return posMap != null && posMap.containsKey(pos);
    }

    // 工程注册时将涉及的所有坐标填入映射表
    private static void claimPositions(ProjectBase project)
    {
        ResourceKey<Level> dim = project.getDimension();
        if (dim == null) return;
        Map<BlockPos, UUID> posMap = DIMENSION_POS_TO_PROJECT.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        for (BlockPos pos : project.getTargetBlocks())
        {
            posMap.put(pos, project.getId());
        }
    }

    // 工程移除时将所有涉及的坐标从映射表中清除
    private static void releasePositions(ProjectBase project)
    {
        ResourceKey<Level> dim = project.getDimension();
        if (dim == null) return;
        Map<BlockPos, UUID> posMap = DIMENSION_POS_TO_PROJECT.get(dim);
        if (posMap == null) return;
        for (BlockPos pos : project.getTargetBlocks())
        {
            posMap.remove(pos, project.getId());
        }
    }

    private static void syncHudToPlayers(ServerLevel level)
    {
        ProjectHudPayload payload = ProjectHudPayload.buildAll();
        for (ServerPlayer player : level.players())
        {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
