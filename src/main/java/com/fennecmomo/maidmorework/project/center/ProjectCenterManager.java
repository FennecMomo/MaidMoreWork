package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.project.ProjectBase;
import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 工程中心总管理器（全局后台调度）
//
// 与游戏生命周期绑定而非区块生命周期：
//   - 每维度持有中心实例，ServerTickEvent.Post 全局驱动（中心永不冻结）
//   - ProjectCenterData (SavedData) 持久化，世界加载即恢复
//   - 方块/方块实体只是与实例交互的桥梁，其加载与否不影响实例运作
//
// 职责：
//   - 中心的创建/删除/查询（create / deleteById / get / findNearestActive）
//   - 全局 tick 调度（扫描、工程推进、延迟销毁、HUD payload）
//   - 工程注册表（ALL_PROJECTS，跨维度按 UUID 索引）
//   - 女仆拾取中断协议（releaseMaid：释放工程 + 脱离中心）
public final class ProjectCenterManager
{
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(ProjectCenterManager.class);

    // 每维度：SavedData + 中心索引
    private record Entry(ProjectCenterData data, Map<UUID, ProjectCenterInstance> byId) {}

    private static final Map<ResourceKey<Level>, Entry> ENTRIES = new ConcurrentHashMap<>();

    // 工程注册表（跨维度，UUID 索引）
    private static final Map<UUID, ProjectBase> ALL_PROJECTS = new ConcurrentHashMap<>();

    // 孤儿清扫节流（每 200 tick 一次）
    private static int sweepCounter = 0;

    // 关服标志：服务器停止、维度卸载时置位，方块实体的 setRemoved 不再触发 deleteById
    // （退出序列中 BE.setRemoved 会被触发，若先于保存编码执行会清空内存数据导致空档）
    private static volatile boolean shuttingDown = false;

    private ProjectCenterManager() {}

    // ===================== 维度入口 =====================

    // 惰性加载：首次访问时从 SavedData 恢复该维度的全部中心
    // 由 tick 在服务器首个心跳触发，保证中心先于任何区块加载存在
    // 同时把各中心恢复出的工程注册进 ALL_PROJECTS（女仆重进后凭 UUID 找回工程）
    // 注意：data 必须来自 computeIfAbsent（存储缓存对象），绝不能自行 new 一个孤儿对象，
    // 否则保存时编码的是存储缓存里的另一个对象，管理器改动全部丢失（进度清零的根因）
    private static Entry entry(ServerLevel level)
    {
        return ENTRIES.computeIfAbsent(level.dimension(), k ->
        {
            ProjectCenterData data = level.getDataStorage().computeIfAbsent(ProjectCenterData.TYPE);
            Map<UUID, ProjectCenterInstance> byId = new ConcurrentHashMap<>();
            for (ProjectCenterInstance center : data.getCenters())
            {
                byId.put(center.getId(), center);
                for (ProjectBase p : center.getManagedProjects())
                {
                    registerProject(p);
                }
            }
            return new Entry(data, byId);
        });
    }

    // ===================== 全局 tick =====================

    public static void tick(ServerLevel level)
    {
        Entry e = entry(level);
        if (e.byId().isEmpty()) return;

        // 自我锚定：确认管理器持有的 data 与存储缓存是同一对象，断连则重建索引并同步
        e = anchor(level, e);

        for (ProjectCenterInstance center : e.byId().values())
        {
            center.tick(level);
        }
        // 快照式同步：byId 是唯一真相，data 只是持久化投影，保存内容永远来自管理器
        e.data().sync(e.byId());

        // 孤儿清扫：每 200 tick 清理无人认领的个人工程（centerId == null），
        // 防止 chunk 卸载/切任务时行为实例被丢弃导致残留刷屏
        if (++sweepCounter % 200 == 0)
        {
            sweepOrphanProjects(level);
        }
    }

    // 清理当前维度内已失联的个人工程：
    //   owner（参与者第一个）实体不存在，或其 PROJECT_UUID memory 不再指向该工程 → 注销
    private static void sweepOrphanProjects(ServerLevel level)
    {
        List<UUID> toRemove = new ArrayList<>();
        for (ProjectBase p : ALL_PROJECTS.values())
        {
            if (p.getCenterId() != null) continue;
            if (p.getDimension() == null || !p.getDimension().equals(level.dimension())) continue;
            if (p.getParticipants().isEmpty())
            {
                toRemove.add(p.getId());
                continue;
            }
            EntityMaid owner = level.getEntity(p.getParticipants().get(0)) instanceof EntityMaid m ? m : null;
            if (owner == null)
            {
                toRemove.add(p.getId());
                continue;
            }
            UUID memory = owner.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
            if (!p.getId().equals(memory))
            {
                toRemove.add(p.getId());
            }
        }
        for (UUID id : toRemove)
        {
            unregisterProject(id);
        }
    }

    // 自我锚定：每次 tick 重新取存储缓存对象，与 Entry.data 比对
    // 不一致（断连）时采用存储对象，把管理器 byId 的真相合并进去并重建 Entry
    private static Entry anchor(ServerLevel level, Entry e)
    {
        ProjectCenterData cached = level.getDataStorage().computeIfAbsent(ProjectCenterData.TYPE);
        if (cached == e.data())
        {
            return e;
        }
        LOGGER.warn("[CenterData] 数据对象断连，重新锚定: manager={} storage={}",
                System.identityHashCode(e.data()), System.identityHashCode(cached));
        Map<UUID, ProjectCenterInstance> byId = new ConcurrentHashMap<>();
        for (ProjectCenterInstance c : cached.getCenters())
        {
            byId.put(c.getId(), c);
            for (ProjectBase p : c.getManagedProjects())
            {
                registerProject(p);
            }
        }
        for (ProjectCenterInstance c : e.byId().values())
        {
            if (!byId.containsKey(c.getId()))
            {
                byId.put(c.getId(), c);
            }
        }
        Entry newEntry = new Entry(cached, byId);
        ENTRIES.put(level.dimension(), newEntry);
        return newEntry;
    }

    // ===================== CRUD =====================

    // 放置方块时创建中心（ProjectCenterBlock.setPlacedBy 调用）
    // data 列表由 tick 末尾的快照同步负责对齐
    public static void create(ServerLevel level, UUID id, UUID owner, BlockPos pos, int radius, int anchor)
    {
        Entry e = entry(level);
        ProjectCenterInstance inst = new ProjectCenterInstance(id, owner, pos, radius, anchor);
        e.byId().put(id, inst);
    }

    // 删除中心（方块拆除时由 BE.setRemoved 调用，幂等；关服流程中不执行）
    public static void deleteById(ServerLevel level, UUID id)
    {
        Entry e = entry(level);
        ProjectCenterInstance removed = e.byId().remove(id);
        if (removed == null) return;

        for (ProjectBase p : new ArrayList<>(removed.getManagedProjects()))
        {
            unregisterProject(p.getId());
        }
        // 客户端清空信息面板
        PacketDistributor.sendToAllPlayers(
                new ProjectCenterInfoPayload(removed.getBlockPos(), "", 0, 0, 0, ""));
    }

    // 方块实体加载时确保实例存在（SavedData 意外丢失时按身份重建）
    public static void ensureFromIdentity(ServerLevel level, ProjectCenterBlockEntity be)
    {
        UUID id = be.getId();
        if (id == null) return;
        Entry e = entry(level);
        if (e.byId().containsKey(id)) return;

        ProjectCenterInstance inst = new ProjectCenterInstance(id, be.getOwner(), be.getBlockPos(),
                be.getRadius(), be.getAnchor());
        inst.setProjectTypeId(be.getProjectTypeId());
        inst.setBoundaryVisible(be.isBoundaryVisible());
        e.byId().put(id, inst);
    }

    public static ProjectCenterInstance get(ServerLevel level, UUID id)
    {
        if (id == null) return null;
        return entry(level).byId().get(id);
    }

    // 女仆所属中心（memory → attachment 依次解析）
    public static ProjectCenterInstance getCenterOf(EntityMaid maid)
    {
        if (!(maid.level() instanceof ServerLevel level)) return null;
        UUID uuid = maid.getBrain().getMemory(ModMemories.PROJECT_CENTER_UUID.get()).orElse(null);
        if (uuid == null)
        {
            uuid = maid.getData(ModAttachments.PROJECT_CENTER_UUID_SAVED).orElse(null);
        }
        return get(level, uuid);
    }

    // 距离女仆最近的、在她家园范围内的已激活中心
    // 距离约束：女仆的工作范围 = 家园范围（getHomeRadius），超出即"附近没有中心"
    public static ProjectCenterInstance findNearestActive(EntityMaid maid)
    {
        if (!(maid.level() instanceof ServerLevel level)) return null;
        ProjectCenterInstance best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos maidPos = maid.blockPosition();
        for (ProjectCenterInstance c : entry(level).byId().values())
        {
            if (c.getProjectTypeId().isEmpty()) continue;
            if (!isWithinMaidRange(maid, c.getBlockPos())) continue;
            double dist = maidPos.distSqr(c.getBlockPos());
            if (dist < bestDist)
            {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    // 女仆当前位置与中心位置的 XZ 距离是否在她家园范围内（工作范围 = 家园范围）
    // 下限 8 防呆（防 0 半径导致永远判离开）
    public static boolean isWithinMaidRange(EntityMaid maid, BlockPos centerPos)
    {
        int range = Math.max(maid.getHomeRadius(), 8);
        BlockPos maidPos = maid.blockPosition();
        double dx = maidPos.getX() - centerPos.getX();
        double dz = maidPos.getZ() - centerPos.getZ();
        return dx * dx + dz * dz <= (double) range * range;
    }

    // ===================== 工程注册表 =====================

    public static void registerProject(ProjectBase project)
    {
        ALL_PROJECTS.put(project.getId(), project);
    }

    public static void unregisterProject(UUID projectId)
    {
        ALL_PROJECTS.remove(projectId);
    }

    @SuppressWarnings("unchecked")
    public static <T extends ProjectBase> T findProject(UUID projectId, Class<T> clazz)
    {
        if (projectId == null) return null;
        ProjectBase p = ALL_PROJECTS.get(projectId);
        if (clazz.isInstance(p)) return (T) p;
        return null;
    }

    public static Collection<ProjectBase> getAllProjects()
    {
        return ALL_PROJECTS.values();
    }

    // ===================== 占用快照 =====================

    // 收集当前维度所有"已被占用"的目标块：中心进行中工程（activeTargets）∪ 个人工程（targetBlocks）
    // 即时快照，用完即弃，不持久化、不常驻——永远反映当下状态，工程完工/放弃后自动消失
    // 供个人检索跳过、整树校验、中心扫描跳过、分配兜底共用
    public static Set<BlockPos> collectOccupied(ServerLevel level)
    {
        Set<BlockPos> out = new HashSet<>();
        Entry e = ENTRIES.get(level.dimension());
        if (e != null)
        {
            for (ProjectCenterInstance center : e.byId().values())
            {
                out.addAll(center.getActiveTargets());
            }
        }
        for (ProjectBase p : ALL_PROJECTS.values())
        {
            if (p.getCenterId() == null && p.getDimension() != null
                    && p.getDimension().equals(level.dimension()))
            {
                out.addAll(p.getTargetBlocks());
            }
        }
        return out;
    }

    public static void removeByProject(ServerLevel level, ProjectBase project, boolean completed)
    {
        unregisterProject(project.getId());
        if (project.getCenterId() != null)
        {
            ProjectCenterInstance center = get(level, project.getCenterId());
            if (center != null)
            {
                center.removeProject(project, completed);
            }
        }
    }

    // ===================== 拾取中断协议 =====================

    // 女仆被收起/拾取：释放工程席位 + 完整脱离中心（还家/退名单/清印记）
    public static void releaseMaid(EntityMaid maid)
    {
        ProjectCenterInstance center = getCenterOf(maid);
        if (center != null)
        {
            center.releaseAssignment(maid);
            center.leaveCenter(maid);
        }
        maid.getBrain().eraseMemory(ModMemories.PROJECT_UUID.get());
    }

    // ===================== HUD 查询 =====================

    public static ProjectHudPayload getNearby(ServerPlayer player, int range)
    {
        List<ProjectHudPayload.Entry> list = new ArrayList<>();
        int rangeSq = range * range;
        BlockPos playerPos = player.blockPosition();
        for (ProjectBase project : ALL_PROJECTS.values())
        {
            if (project.isCompleted()) continue;
            if (playerPos.distSqr(project.getPosition()) <= rangeSq)
            {
                list.add(ProjectHudPayload.Entry.from(project));
            }
        }
        return new ProjectHudPayload(list);
    }

    // ===================== 生命周期 =====================

    public static boolean isShuttingDown()
    {
        return shuttingDown;
    }

    // 维度卸载/关服：丢弃内存索引（SavedData 已由存档系统保存）+ 置位关服标志
    public static void onLevelUnload(Level level)
    {
        shuttingDown = true;
        ENTRIES.remove(level.dimension());
    }

    // 服务器停止：复位标志并清空全部静态注册表，避免跨世界残留
    public static void clearAll()
    {
        shuttingDown = false;
        ENTRIES.clear();
        ALL_PROJECTS.clear();
    }
}
