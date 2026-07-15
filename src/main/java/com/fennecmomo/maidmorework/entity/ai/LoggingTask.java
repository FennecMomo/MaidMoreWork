package com.fennecmomo.maidmorework.entity.ai;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 伐木行为包（TLM 工作模式）
// 组装 SearchBehavior（找树） + ChopBehavior（砍树）两个行为
// 找树流程：螺旋遍历坐标 → scanForTree 检查 → BFS 整棵树 → 聚类分离 → 选最近那簇
// 砍树流程：由 ChopBehavior 负责，导航 + 替换 + 标记 + 收集
public class LoggingTask implements IMaidTask
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");
    // 任务唯一标识
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "logging");

    // 返回任务 UID
    @Override
    public Identifier getUid() { return UID; }

    // 任务图标，显示在任务列表 UI 中
    @Override
    public ItemStack getIcon() { return Items.IRON_AXE.getDefaultInstance(); }

    // 执行任务时的环境音效
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) { return SoundEvents.WOOD_BREAK; }

    // 禁用默认闲逛和随机转头，由 SearchBehavior 控制移动
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) { return false; }

    // 组装行为列表：SearchBehavior(找树) + ChopBehavior(砍树)
    // 优先级：SearchBehavior=5, ChopBehavior=6（搜索优先级高于砍伐）
    // 写入工作关键词“砍树”“原木”，供 SearchBehavior 拼气泡文案和 Attachment 恢复
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        // 写入工作关键词，供 SearchBehavior 拼气泡文案
        maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), "砍树");
        maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), "原木");
        // 同步写 Attachment，退出重进后恢复
        maid.setData(ModAttachments.WORK_ACTION_SAVED, "砍树");
        maid.setData(ModAttachments.WORK_TARGET_SAVED, "原木");

        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = new ArrayList<>();
        // SearchBehavior 负责螺旋遍历坐标，每个坐标点调用 scanForTree 检查
        // 15: XZ 半径, 1: Y 向下, 14: Y 向上
        tasks.add(Pair.of(5, new SearchBehavior(this::scanForTree, ModMemories.LOG_BLOCKS.get(), 15, 1, 14, true)));
        tasks.add(Pair.of(6, new ChopBehavior()));
        return tasks;
    }

    // ===================== 检索逻辑 =====================

    // SearchBehavior 每推进一个螺旋点就调用一次
    // 检查该点是否有邻叶的原木，有就 BFS 整棵树 → 聚类 → 选离女仆最近的那簇
    // 找到后写入 Memory（LOG_BLOCKS + LEAVES_BLOCKS）和 Attachment（持久化）
    private boolean scanForTree(ServerLevel level, BlockPos point, EntityMaid maid)
    {
        if (!level.isLoaded(point)) return false;
        BlockState state = level.getBlockState(point);
        if (!state.is(BlockTags.LOGS) || !hasAdjacentLeaves(level, point)) return false;

        // BFS 搜整棵连通树（原木+树叶）
        List<BlockPos> allLogs = new ArrayList<>();
        List<BlockPos> allLeaves = new ArrayList<>();
        bfsAll(level, point, allLogs, allLeaves);

        if (allLogs.isEmpty()) return false;

        LOGGER.info("LoggingTask: BFS found {} logs, {} leaves from {}", allLogs.size(), allLeaves.size(), point);

        // 聚类分离（树叶相连的密林会把多棵树连一起）
        List<List<BlockPos>> clusters = clusterLogs(allLogs);
        List<List<BlockPos>> leafClusters = assignLeavesToNearestLog(allLogs, allLeaves, clusters);

        // 选离女仆最近的那簇
        List<BlockPos> bestLogs = null;
        List<BlockPos> bestLeaves = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < clusters.size(); i++)
        {
            List<BlockPos> clusterLogs = clusters.get(i);
            double minDist = Double.MAX_VALUE;
            for (BlockPos log : clusterLogs)
            {
                double d = log.distToCenterSqr(maid.getX(), maid.getY(), maid.getZ());
                if (d < minDist) minDist = d;
            }
            if (minDist < bestDist)
            {
                bestDist = minDist;
                bestLogs = clusterLogs;
                bestLeaves = leafClusters.get(i);
            }
        }

        if (bestLogs != null && !bestLogs.isEmpty())
        {
            maid.getBrain().setMemory(ModMemories.LOG_BLOCKS.get(), bestLogs);
            maid.getBrain().setMemory(ModMemories.LEAVES_BLOCKS.get(), bestLeaves);
            // 同步写到 Attachment，退出重进后恢复
            maid.setData(ModAttachments.LOG_BLOCKS_SAVED, new ArrayList<>(bestLogs));
            maid.setData(ModAttachments.LEAVES_BLOCKS_SAVED, new ArrayList<>(bestLeaves));
            LOGGER.info("LoggingTask: selected cluster ({} logs) at dist={} ({})",
                    bestLogs.size(), Math.sqrt(bestDist), bestLogs.get(0));
            return true;
        }
        return false;
    }

    // 检查原木周围是否有树叶（排除孤立的木头）
    private boolean hasAdjacentLeaves(ServerLevel level, BlockPos log)
    {
        for (Direction d : Direction.values())
        {
            if (level.getBlockState(log.relative(d)).is(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    // BFS 搜索整棵连通树（原木+树叶）
    // 从单点出发，向 6 方向扩展，把连通的原木和树叶都收集起来
    private void bfsAll(ServerLevel level, BlockPos start, List<BlockPos> logs, List<BlockPos> leaves)
    {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty())
        {
            BlockPos p = queue.poll();
            BlockState state = level.getBlockState(p);
            if (state.is(BlockTags.LOGS)) { logs.add(p); }
            else if (state.is(BlockTags.LEAVES)) { leaves.add(p); }
            else { continue; }
            for (Direction d : Direction.values())
            {
                BlockPos nb = p.relative(d);
                if (!visited.contains(nb))
                {
                    BlockState ns = level.getBlockState(nb);
                    if (ns.is(BlockTags.LOGS) || ns.is(BlockTags.LEAVES))
                    {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
        }
    }

    // ===================== 聚类 =====================

    // 按水平+垂直相邻性聚类（分离密林中相连的多棵树）
    // 水平相邻 = 8 方向，垂直相邻 = 上下 2 方向
    // 聚类后每簇对应一棵独立的树
    private List<List<BlockPos>> clusterLogs(List<BlockPos> allLogs)
    {
        List<List<BlockPos>> clusters = new ArrayList<>();
        Set<BlockPos> unassigned = new HashSet<>(allLogs);
        while (!unassigned.isEmpty())
        {
            BlockPos seed = unassigned.iterator().next();
            unassigned.remove(seed);
            List<BlockPos> cluster = new ArrayList<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty())
            {
                BlockPos p = queue.poll();
                cluster.add(p);
                // 水平相邻
                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        if (dx == 0 && dz == 0) continue;
                        BlockPos nb = new BlockPos(p.getX() + dx, p.getY(), p.getZ() + dz);
                        if (unassigned.remove(nb)) { queue.add(nb); }
                    }
                }
                // 垂直相邻
                for (int dy = -1; dy <= 1; dy += 2)
                {
                    BlockPos nb = new BlockPos(p.getX(), p.getY() + dy, p.getZ());
                    if (unassigned.remove(nb)) { queue.add(nb); }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    // 每个树叶归入最近原木所在的聚类
    // 确保砍树时树叶不会张冠李戴（A 树的叶子不会分配给 B 树）
    private List<List<BlockPos>> assignLeavesToNearestLog(
            List<BlockPos> allLogs, List<BlockPos> allLeaves, List<List<BlockPos>> clusters)
    {
        Map<BlockPos, Integer> logToCluster = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++)
            for (BlockPos log : clusters.get(i))
                logToCluster.put(log, i);

        List<List<BlockPos>> leafClusters = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++)
            leafClusters.add(new ArrayList<>());

        for (BlockPos leaf : allLeaves)
        {
            BlockPos nearestLog = null;
            double nearestDist = Double.MAX_VALUE;
            for (BlockPos log : allLogs)
            {
                double d = leaf.distSqr(log);
                if (d < nearestDist) { nearestDist = d; nearestLog = log; }
            }
            if (nearestLog != null)
            {
                Integer idx = logToCluster.get(nearestLog);
                if (idx != null) leafClusters.get(idx).add(leaf);
            }
        }
        return leafClusters;
    }
}
