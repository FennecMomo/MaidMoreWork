package com.fennecmomo.maidmorework.project;

import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.ModMemories;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// 砍树工程（计数型）：CountingProject 提供通用计数推进 + 贡献分配
// 本类只包含砍树特有业务：树脚坐标、BFS 搜索、原木标签判定、isActive 砍树任务链
//
// 树叶由原版自然腐栏机制清理，工程不管理
//
// isActive 判定链：
//   1. shouldRetainParticipant（基类）：实体存在性 + 区块卸载保护
//   2. 女仆是否仍在砍树任务中
//   3. 女仆的目标工程是否是自己
public class ChoppingProject extends CountingProject
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 砍树工程只允许一人参与
    private static final int MAX_PARTICIPANTS = 1;

    // ===================== 多态序列化 =====================

    @Override
    public String type()
    {
        return "chopping";
    }

    // Codec 字段映射：声明所有需要序列化的字段（包括继承自基类的）
    public static final MapCodec<ChoppingProject> MAP_CODEC =
        RecordCodecBuilder.mapCodec(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(ChoppingProject::getId),
            BlockPos.CODEC.fieldOf("rootPos").forGetter(ChoppingProject::getPosition),
            BlockPos.CODEC.listOf().fieldOf("logs").forGetter(ChoppingProject::getTargetBlocks),
            Codec.INT.fieldOf("workload").forGetter(ChoppingProject::getWorkload),
            Codec.DOUBLE.fieldOf("progress").forGetter(ChoppingProject::getProgress),
            Codec.BOOL.optionalFieldOf("completed", false).forGetter(ChoppingProject::getCompleted),
            UUIDUtil.CODEC.listOf().optionalFieldOf("participants", List.of()).forGetter(ChoppingProject::getParticipants),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ChoppingProject::getDimension)
        ).apply(inst, ChoppingProject::fromCodec));

    public static final Codec<ChoppingProject> CODEC = MAP_CODEC.codec();

    // Codec 工厂方法：从反序列化字段构建工程实例
    private static ChoppingProject fromCodec(
        UUID id, BlockPos rootPos, List<BlockPos> logs,
        int workload, double progress, boolean completed, List<UUID> participants,
        ResourceKey<Level> dimension)
    {
        ChoppingProject project = new ChoppingProject(id, rootPos, workload, progress, completed, logs, participants, dimension);
        return project;
    }

    // ===================== 树数据 =====================

    private final BlockPos rootPos;         // 树脚位置（Y 最低的原木）

    // ===================== 构造 =====================

    // 新建工程：BFS 扫描出的原木坐标列表
    public ChoppingProject(BlockPos rootPos, List<BlockPos> logs)
    {
        super(MAX_PARTICIPANTS, logs.size());
        this.rootPos = rootPos;
        this.targetBlocks = new ArrayList<>(logs);
    }

    // Codec 反序列化构造：指定全部字段
    private ChoppingProject(UUID id, BlockPos rootPos, int workload,
                            double progress, boolean completed,
                            List<BlockPos> logs, List<UUID> participants,
                            ResourceKey<Level> dimension)
    {
        super(id, MAX_PARTICIPANTS, workload, progress, completed, dimension, participants, logs);
        this.rootPos = rootPos;
    }

    // ===================== 基类抽象实现 =====================

    @Override
    public BlockPos getPosition()
    {
        return rootPos;
    }

    // 单点目标判定：检查是否为原木
    @Override
    protected boolean isValidTarget(ServerLevel level, BlockPos pos)
    {
        return level.getBlockState(pos).is(BlockTags.LOGS);
    }

    // 斧子加速：手持物品对原木的破坏速度作为进度增量
    @Override
    protected double getProgressIncrement(ServerLevel level, BlockPos pos, EntityMaid maid)
    {
        BlockState state = level.getBlockState(pos);
        float destroySpeed = maid.getMainHandItem().getDestroySpeed(state);
        float hardness = state.getDestroySpeed(level, pos);
        // 原版每 tick 进度 = destroySpeed / (hardness * 30)
        return destroySpeed * MaidMoreWorkConfig.CHOP_INTERVAL / (hardness * 30);
    }

    // 目标列表重建：BFS 重扫连通原木
    // 更新 targetBlocks + workload，progress 保持不变
    // 返回 false 表示已无有效原木（树消失）
    @Override
    protected boolean rebuild(ServerLevel level)
    {
        // 优先从 rootPos 开始 BFS
        BlockPos start;
        if (level.getBlockState(rootPos).is(BlockTags.LOGS))
        {
            start = rootPos;
        }
        else
        {
            // rootPos 上的原木已消失 → 找距离 rootPos 最近的一个有效原木
            start = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos pos : targetBlocks)
            {
                if (level.getBlockState(pos).is(BlockTags.LOGS))
                {
                    double dist = pos.distSqr(rootPos);
                    if (dist < bestDist)
                    {
                        bestDist = dist;
                        start = pos;
                    }
                }
            }
        }
        if (start == null)
        {
            LOGGER.info("ChoppingProject: no valid log remaining, project={}", getId());
            return false;
        }

        // BFS 重扫连通原木
        List<BlockPos> newLogs = new ArrayList<>();
        bfsLogs(level, start, newLogs);

        LOGGER.info("ChoppingProject: rebuild found {} logs project={}",
                newLogs.size(), getId());

        // progress 保持不变，仅更新 targetBlocks 和 workload
        this.targetBlocks = newLogs;
        this.workload = newLogs.size();

        return !newLogs.isEmpty();
    }

    // ===================== isActive 判定 =====================

    @Override
    public boolean isActive(ServerLevel level, UUID maidUuid)
    {
        if (!shouldRetainParticipant(level, maidUuid))
        {
            return false;
        }

        EntityMaid maid = (EntityMaid) level.getEntity(maidUuid);
        if (maid == null)
        {
            return true;
        }

        String action = maid.getBrain().getMemory(ModMemories.WORK_ACTION.get()).orElse("");
        if (!ModMemories.WORK_ACTION_CHOPPING.equals(action))
        {
            return false;
        }

        UUID projectUuid = maid.getBrain().getMemory(ModMemories.PROJECT_UUID.get()).orElse(null);
        return getId().equals(projectUuid);
    }

    // ===================== 完成回调 =====================

    // 工程完成时的清理逻辑（由 ProjectServerHelper 调用）
    // 原木已在 CountingProject.completeTargets 中批量破坏，树叶由原版自然腐栏机制清理
    @Override
    public void onComplete(ServerLevel level)
    {
        LOGGER.info("ChoppingProject: project completed, id={}", getId());
    }

    // ===================== BFS 工具 =====================

    // BFS 搜索连通原木（仅收集原木，不沿树叶爬以保持树与树的边界）
    // 从单点出发，向 6 方向扩展，只沿原木方块连通
    public static void bfsLogs(ServerLevel level, BlockPos start, List<BlockPos> logs)
    {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty())
        {
            BlockPos p = queue.poll();
            BlockState state = level.getBlockState(p);
            if (state.is(BlockTags.LOGS))
            {
                logs.add(p);
                for (Direction d : Direction.values())
                {
                    BlockPos nb = p.relative(d);
                    if (!visited.contains(nb) && level.getBlockState(nb).is(BlockTags.LOGS))
                    {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
        }
    }
}
