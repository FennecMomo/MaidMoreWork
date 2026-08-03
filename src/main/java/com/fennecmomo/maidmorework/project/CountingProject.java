package com.fennecmomo.maidmorework.project;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fennecmomo.maidmorework.project.center.ProjectCenterInstance;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

// 计数型工程基类：抽象计数 + 批量破坏 + 按贡献分配掉落物
//
// 执行过程仅递增计数器，不对实际方块产生影响
// 计数达到任务量（workload）时一次性破坏所有缓存方块并按贡献比例返还掉落物
// 每次执行后通过 isCacheStale（ProjectBase）验证缓存，发现空洞调用 rebuild 重建
//
// 子类只需实现 ProjectBase 中定义的抽象方法：
//   - isValidTarget()  单点目标有效性判定
//   - rebuild()        目标列表重建
//   - onComplete()     完成回调
//   - getPosition()    工程绑定位置
public abstract class CountingProject extends ProjectBase
{
    // ===================== 计数型进度 =====================

    protected double progress = 0.0;   // 进度（支持小数，如斧子破坏速度）
    protected int workload;            // 目标总次数
    protected boolean completed = false;

    // UUID → 累计工具磨损（小数，>= 1.0 时扣除耐久）
    private final Map<UUID, Double> toolWear = new HashMap<>();

    // ===================== 构造 =====================

    protected CountingProject(int maxParticipants, int workload)
    {
        super(maxParticipants);
        this.workload = workload;
    }

    // Codec 反序列化构造：指定全部字段
    protected CountingProject(UUID projectId, int maxParticipants, int workload,
                              double progress, boolean completed,
                              ResourceKey<Level> dimension,
                              List<UUID> participants, List<BlockPos> targetBlocks)
    {
        super(projectId, maxParticipants, dimension, participants, targetBlocks);
        this.workload = workload;
        this.progress = progress;
        this.completed = completed;
    }

    // ===================== 数据访问 =====================

    public double getProgress()
    {
        return progress;
    }

    public int getWorkload()
    {
        return workload;
    }

    @Override
    public boolean isCompleted()
    {
        return completed;
    }

    // Codec getter
    public boolean getCompleted()
    {
        return completed;
    }

    // ===================== 计数型执行 =====================

    // 执行一次计数推进：
    // 1. 通过可覆写方法获取本次进度增量和贡献增量
    // 2. 进度累加，贡献累加
    // 3. 计数达到任务量 → 批量破坏 + 按贡献分配掉落物 → 标记完成
    // 返回 true = 正常推进，返回 false = 无有效目标或已完成
    public boolean execute(ServerLevel level, UUID maidUuid)
    {
        if (targetBlocks.isEmpty())
        {
            completed = true;
            return false;
        }

        Entity entity = level.getEntity(maidUuid);
        if (!(entity instanceof EntityMaid maid))
        {
            return false;
        }

        BlockPos refPos = getPosition();
        double progressInc = getProgressIncrement(level, refPos, maid);
        int contribInc = getContributionIncrement(level, refPos, maid);

        progress += progressInc;
        addContribution(maidUuid, contribInc);

        if (progress >= workload)
        {
            completeTargets(level);
            return false;
        }

        return true;
    }

    // ===================== 增量覆写 =====================

    // 周期检查覆写：rebuild 返回 false 时标记完成（已无有效目标）
    @Override
    public void tick(ServerLevel level)
    {
        if (completed)
        {
            return;
        }
        if (isCacheStale(level))
        {
            ProjectCenterInstance center = findCenter(level);
            if (center != null)
            {
                center.onProjectCacheStale(this);
            }
            if (!rebuild(level))
            {
                completed = true;
                return;
            }
            if (center != null)
            {
                center.onProjectRebuilt(this, targetBlocks);
            }
        }
    }

    // 本次执行的进度增量，子类可覆写（如受工具/buff 影响）
    // 默认返回 1
    protected double getProgressIncrement(ServerLevel level, BlockPos pos, EntityMaid maid)
    {
        return 1;
    }

    // 本次执行的贡献增量，子类可覆写（如受工具/buff 影响）
    // 默认返回 1
    protected int getContributionIncrement(ServerLevel level, BlockPos pos, EntityMaid maid)
    {
        return 1;
    }

    // ===================== 工具磨损 =====================

    // 累计工具磨损值（与进度增量等量），>= 1.0 时扣除耐久，余额继续累积
    protected void accumulateToolWear(EntityMaid maid, double amount)
    {
        double wear = toolWear.getOrDefault(maid.getUUID(), 0.0) + amount;
        while (wear >= 1.0)
        {
            maid.getMainHandItem().hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
            wear -= 1.0;
        }
        toolWear.put(maid.getUUID(), wear);
    }

    @Override
    public void release(UUID maidUuid)
    {
        super.release(maidUuid);
        toolWear.remove(maidUuid);
    }

    // ===================== 按贡献分配掉落物 =====================

    // 一次性破坏所有缓存方块，按贡献比例分配给各在线参与者
    // 进入时先剔除离线参与者（execute 驱动者必然在线，不会全员离线）
    protected void completeTargets(ServerLevel level)
    {
        ProjectCenterInstance center = findCenter(level);

        for (BlockPos pos : targetBlocks)
        {
            if (!level.isLoaded(pos))
            {
                if (center != null)
                {
                    UUID firstMaid = null;
                    if (!getParticipants().isEmpty()) firstMaid = getParticipants().get(0);
                    center.addPendingDestroy(pos, firstMaid != null ? firstMaid : new UUID(0, 0));
                }
                continue;
            }

            EntityMaid collector = null;
            for (UUID uuid : getParticipants())
            {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof EntityMaid maid)
                {
                    collector = maid;
                    break;
                }
            }

            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;

            if (collector != null)
            {
                destroyAndCollect(level, collector, pos);
            }
            else
            {
                level.destroyBlock(pos, true);
            }
        }

        completed = true;

        if (center != null)
        {
            center.onProjectCompleted(this);
        }
    }

    // 破坏单个方块并收集掉落物到女仆背包
    protected void destroyAndCollect(ServerLevel level, EntityMaid maid, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        if (state.isAir())
        {
            return;
        }
        maid.getItemManager().dropResourcesToMaidInv(
                state, level, pos,
                level.getBlockEntity(pos), maid.getMainHandItem());
        level.destroyBlock(pos, false, maid);
    }
}
