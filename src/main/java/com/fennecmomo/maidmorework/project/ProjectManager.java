package com.fennecmomo.maidmorework.project;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 全局工程管理器（静态单例）
//
// 统一管理所有工程实例的全生命周期：
//   - 注册/注销工程
//   - 类型安全的工程获取（getProject / getAvailableProject）
//   - 每 60 tick 遍历检查：loaded 状态、缓存空洞、isActive 参与者清理、isCompleted 自动移除
//   - 与 ProjectData (SavedData) 同步，实现工程数据持久化
//
// 由 MaidMoreWork 构造函数中注册 MaidTickEvent 驱动 tick
public final class ProjectManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 检查间隔：60 tick = 3 秒
    private static final int CHECK_INTERVAL = 60;

    // 全部工程实例：projectId → project
    private static final Map<UUID, ProjectBase> PROJECTS = new HashMap<>();

    // tick 计数器
    private static int tickCounter = 0;

    // 持久化脏标记：工程注册/注销/完成时置 true，下次 tick 同步到 SavedData
    private static boolean dataDirty = false;

    private ProjectManager() {}

    // ===================== 工程注册 =====================

    // 注册新工程到管理器
    public static void register(ProjectBase project)
    {
        PROJECTS.put(project.getId(), project);
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
        // 懒加载：首次 tick 时从 SavedData 恢复工程
        if (!dataLoaded)
        {
            ensureLoaded(level);
        }

        tickCounter++;
        if (tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        LOGGER.info("ProjectManager: tick checking {} projects", PROJECTS.size());

        Iterator<Map.Entry<UUID, ProjectBase>> it = PROJECTS.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<UUID, ProjectBase> entry = it.next();
            ProjectBase project = entry.getValue();

            if (!project.isLoaded())
            {
                continue;
            }

            // 统一调度缓存空洞检测
            project.tick(level);

            if (checkCompleted(level, project, it)) continue;

            cleanInactiveParticipants(level, project);
        }

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

    // 检查工程是否已完成：是则调用 onComplete + 从迭代器移除
    // 返回 true 表示工程已移除，外层应 continue
    private static boolean checkCompleted(ServerLevel level, ProjectBase project,
                                          Iterator<Map.Entry<UUID, ProjectBase>> it)
    {
        if (!project.isCompleted()) return false;

        project.onComplete(level);
        it.remove();
        dataDirty = true;
        LOGGER.info("ProjectManager: project {} completed and removed", project.getId());
        return true;
    }

    // 清理不活跃的参与者，参与者为空时记录孤儿日志
    private static void cleanInactiveParticipants(ServerLevel level, ProjectBase project)
    {
        List<UUID> toRemove = new ArrayList<>();
        for (UUID maidUuid : project.getParticipants())
        {
            if (!project.isActive(level, maidUuid))
            {
                toRemove.add(maidUuid);
            }
        }

        for (UUID maidUuid : toRemove)
        {
            LOGGER.info("ProjectManager: releasing inactive maid {} from project {}",
                    maidUuid, project.getId());
            project.release(maidUuid);
        }

        if (project.getParticipants().isEmpty())
        {
            LOGGER.info("ProjectManager: project {} is now orphaned", project.getId());
        }
    }
}
