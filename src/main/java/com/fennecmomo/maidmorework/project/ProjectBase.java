package com.fennecmomo.maidmorework.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

// 工程基类：统一管理女仆工作工程的基础设施
//
// 所有工程共享以下能力：
//   - UUID 唯一标识
//   - targetBlocks 目标方块坐标缓存（所有工程共用）
//   - participants 参与女仆列表（支持多人协作）
//   - maxParticipants 人数上限（由子类设定）
//   - loaded 加载状态（区块卸载时休眠，避免无效检查）
//   - contributions 贡献记录（用于掉落物分配）
//   - isCacheStale() 缓存空洞检测（遍历 targetBlocks + isValidTarget）
//   - shouldRetainParticipant() 参与者保留判定（实体存在性 + 区块卸载保护）
//
// 子类通过实现以下抽象方法定义具体行为：
//   - isActive()       判断女仆是否仍在进行本工程
//   - isCompleted()    判断工程是否完成
//   - onComplete()     工程完成时的清理/分配逻辑
//   - getPosition()    工程绑定位置（用于距离判断、区块卸载检查）
//   - isValidTarget()  单点目标有效性判定（供 isCacheStale 调用）
//   - rebuild()        目标列表重建（缓存空洞时调用）
public abstract class ProjectBase
{
    // ===================== 多态序列化 =====================

    // 基类 Codec：通过 type 字段分派到对应子类的 MAP_CODEC
    public static final Codec<ProjectBase> CODEC = Codec.STRING.dispatch(
        ProjectBase::type,
        type -> switch (type)
        {
            case "chopping" -> ChoppingProject.MAP_CODEC;
            default -> throw new IllegalArgumentException("Unknown project type: " + type);
        });

    // 子类返回自己的类型标签，用于序列化分派
    public abstract String type();

    // ===================== 核心标识 =====================

    private UUID projectId = UUID.randomUUID();
    private ResourceKey<Level> dimension;   // 工程所在维度

    // ===================== 参与者管理 =====================

    private final List<UUID> participants = new ArrayList<>();
    private final int maxParticipants;

    // ===================== 加载状态 =====================

    // loaded=true 表示工程处于活跃状态，ProjectServerHelper 正常检查
    // loaded=false 表示工程所在区块已卸载，跳过检查（休眠）
    // 女仆调用 API 或获取工程时自动重新标记为 true（唤醒）
    private boolean loaded = true;

    // ===================== 目标方块缓存 =====================

    // 缓存的目标方块坐标列表，所有工程共用
    // 计数型：完成时批量破坏；执行型：逐个处理并标记完成
    protected List<BlockPos> targetBlocks = new ArrayList<>();

    // ===================== 贡献记录 =====================

    // UUID → 贡献次数，用于完成时按比例分配掉落物
    private final Map<UUID, Integer> contributions = new HashMap<>();

    // ===================== 构造 =====================

    protected ProjectBase(int maxParticipants)
    {
        this.maxParticipants = maxParticipants;
    }

    protected ProjectBase(UUID projectId, int maxParticipants)
    {
        this.projectId = projectId;
        this.maxParticipants = maxParticipants;
    }

    // Codec 反序列化构造：指定 UUID + 参与者 + 目标方块
    protected ProjectBase(UUID projectId, int maxParticipants,
                          ResourceKey<Level> dimension,
                          List<UUID> participants, List<BlockPos> targetBlocks)
    {
        this.projectId = projectId;
        this.maxParticipants = maxParticipants;
        this.dimension = dimension;
        this.participants.addAll(participants);
        this.targetBlocks = new ArrayList<>(targetBlocks);
    }

    // ===================== 标识 =====================

    public UUID getId()
    {
        return projectId;
    }

    public ResourceKey<Level> getDimension()
    {
        return dimension;
    }

    // 供 LoggingTask 等调用方在构造后设置维度（不通过 codec 走的新建工程）
    public void setDimension(ResourceKey<Level> dimension)
    {
        this.dimension = dimension;
    }

    // ===================== 加载状态 =====================

    public boolean isLoaded()
    {
        return loaded;
    }

    public void setLoaded(boolean loaded)
    {
        this.loaded = loaded;
    }

    // ===================== 参与者 =====================

    public List<UUID> getParticipants()
    {
        return participants;
    }

    public int getMaxParticipants()
    {
        return maxParticipants;
    }

    // 是否有空位供新女仆加入
    public boolean hasAvailableSlot()
    {
        return participants.size() < maxParticipants;
    }

    // 加入工程：添加到参与者列表，初始化贡献
    // 返回 true 表示成功加入，返回 false 表示已满员
    public boolean claim(UUID maidUuid)
    {
        if (participants.contains(maidUuid))
        {
            return true;
        }
        if (!hasAvailableSlot())
        {
            return false;
        }
        participants.add(maidUuid);
        contributions.put(maidUuid, 0);
        return true;
    }

    // 离开工程：从参与者移除，贡献清零
    public void release(UUID maidUuid)
    {
        participants.remove(maidUuid);
        contributions.remove(maidUuid);
    }

    // ===================== 目标方块缓存 =====================

    public List<BlockPos> getTargetBlocks()
    {
        return targetBlocks;
    }

    // ===================== 周期检查 =====================

    // 由 ProjectServerHelper.tick 统一调度，每 60 tick 调用一次
    // 检查缓存空洞：遍历 targetBlocks 委托 isValidTarget 判定
    // 发现空洞则调用 rebuild 重建目标列表
    // rebuild 返回 false 表示已无有效目标，由子类 tick 覆写决定后续处理
    public void tick(ServerLevel level)
    {
        if (isCompleted())
        {
            return;
        }
        if (isCacheStale(level))
        {
            if (!rebuild(level))
            {
            }
        }
    }

    // 根据工程自身维度解析 ServerLevel，用于 isActive/isCacheStale 等需要世界状态的检查
    protected ServerLevel resolveLevel(MinecraftServer server)
    {
        if (dimension == null || server == null) return null;
        return server.getLevel(dimension);
    }

    // ===================== 缓存验证 =====================

    // 遍历 targetBlocks 检查是否存在空洞（某个坐标已不是有效目标）
    // 委托抽象方法 isValidTarget 做单点判定，子类覆写具体判定逻辑
    // 返回 true 表示存在空洞，需调用 rebuild 重建
    protected boolean isCacheStale(ServerLevel level)
    {
        for (BlockPos pos : targetBlocks)
        {
            if (!isValidTarget(level, pos))
            {
                return true;
            }
        }
        return false;
    }

    // ===================== 参与者保留判定 =====================

    // 判断是否允许保留女仆在工程的参与者席位上
    // 供 isActive 调用，封装所有子类共用的前置判定：
    //   - 实体存在 → 允许保留，由子类继续检查任务是否匹配
    //   - 实体不存在但区块已卸载 → 允许保留（等待区块重载），副作用：setLoaded(false)
    //   - 实体不存在且区块已加载 → 不允许保留（女仆真的不在了）
    // 返回 true = 允许保留席位，返回 false = 应从参与者列表移除
    protected boolean shouldRetainParticipant(ServerLevel level, UUID maidUuid)
    {
        Entity entity = level.getEntity(maidUuid);
        if (entity != null)
        {
            return true;
        }

        // 实体不在 → 检查工程位置区块是否已卸载
        if (!level.isLoaded(getPosition()))
        {
            setLoaded(false);
            return true;
        }

        return false;
    }



    // ===================== 贡献 =====================

    // 增加指定女仆的贡献值
    // amount 为本次贡献增量，可由子类根据工具/buff/debuff 动态决定
    protected void addContribution(UUID maidUuid, int amount)
    {
        contributions.merge(maidUuid, amount, Integer::sum);
    }

    public Map<UUID, Integer> getContributions()
    {
        return contributions;
    }

    // ===================== 抽象方法 =====================

    // 判断指定女仆是否仍在进行本工程
    // 由子类实现具体判定逻辑（如砍树工程检查女仆是否在砍树任务中且目标是自己）
    public abstract boolean isActive(ServerLevel level, UUID maidUuid);

    // 判断工程是否完成
    // 计数型：进度达到任务量；执行型：无剩余可操作目标
    public abstract boolean isCompleted();

    // 工程完成时的清理/分配逻辑
    // 如批量破坏方块、按贡献分配掉落物等
    public abstract void onComplete(ServerLevel level);

    // 工程绑定的世界位置（用于距离判断、区块卸载检查）
    public abstract BlockPos getPosition();

    // 判定单个坐标是否仍是有效目标（供 isCacheStale 调用）
    // 砍树覆写为 BlockTags.LOGS 检查，挖矿覆写为特定矿石检查
    protected abstract boolean isValidTarget(ServerLevel level, BlockPos pos);

    // 重建目标坐标列表（缓存空洞时调用）
    // 砍树覆写为 reBfsTree，挖矿覆写为区域重扫
    // 返回 false 表示已无有效目标（工程应标记完成）
    protected abstract boolean rebuild(ServerLevel level);
}
