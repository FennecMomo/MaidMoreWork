package com.fennecmomo.maidmorework.logging;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.project.ChoppingProject;
import com.fennecmomo.maidmorework.project.ProjectServerHelper;
import com.fennecmomo.maidmorework.search.SearchBehavior;
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
    // 写入工作关键词，供 SearchBehavior 拼气泡文案和 Attachment 恢复
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = new ArrayList<>();
        // SearchBehavior 负责螺旋遍历坐标，每个坐标点调用 scanForTree 检查
        // onTreeFound 负责找到树后的 BFS + 工程创建 + Memory 写入
        // preSearch 优先查找孤儿砍树工程，无孤儿时才启动螺旋搜索
        // 15: XZ 半径, 1: Y 向下, 14: Y 向上
        tasks.add(Pair.of(5, new SearchBehavior(this::scanForTree, this::searchOrphanProject,
                this::onTreeFound, ModMemories.PROJECT_UUID.get(), 15, 1, 14, true)));
        tasks.add(Pair.of(6, new ChopBehavior()));
        return tasks;
    }

    // ===================== 检索逻辑 =====================

    // SearchBehavior 每推进一个螺旋点就调用一次
    // 纯检查：该点是否是带邻叶的原木（即一棵树的起点）
    // 不包含 BFS、工程创建、Memory 写入等后续逻辑
    private boolean scanForTree(ServerLevel level, BlockPos point, EntityMaid maid)
    {
        maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), ModMemories.WORK_TARGET_LOG);
        if (!level.isLoaded(point)) return false;
        if (ProjectServerHelper.isPositionClaimed(level, point)) return false;
        BlockState state = level.getBlockState(point);
        if (!state.is(BlockTags.LOGS)) return false;
        return hasAdjacentLeaves(level, point);
    }

    // SearchBehavior 的 onFound 回调：在确认找到树后执行后续处理
    // 职责：BFS 收集连通原木 → 创建工程 → 注册 → claim → 写 Memory + Attachment
    private boolean onTreeFound(ServerLevel level, BlockPos point, EntityMaid maid)
    {
        // BFS 收集连通原木（穿过树叶找连通的原木）
        List<BlockPos> logs = new ArrayList<>();
        ChoppingProject.bfsLogs(level, point, logs);

        if (logs.isEmpty()) return false;

        LOGGER.info("LoggingTask: BFS found {} logs from {}", logs.size(), point);

        // 创建砍树工程并注册到 ProjectServerHelper
        BlockPos rootPos = findTreeBase(logs);
        ChoppingProject project = new ChoppingProject(rootPos, logs);
        project.setDimension(level.dimension());
        project.claim(maid.getUUID());
        ProjectServerHelper.register(project);

        // 写入 PROJECT_UUID Memory
        maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), project.getId());

        // 同步写 Attachment（世界重进后恢复引用）
        maid.setData(ModAttachments.PROJECT_UUID_SAVED, java.util.Optional.of(project.getId()));

        LOGGER.info("LoggingTask: created ChoppingProject {} ({} logs) maid={}",
                project.getId(), logs.size(), maid.getId());
        return true;
    }

    // ===================== 孤儿工程查找 =====================

    // SearchBehavior 的 preSearch 回调：在螺旋搜索前查找范围内可用的砍树工程
    // 找到后 claim 并写入 PROJECT_UUID Memory，跳过螺旋搜索
    private boolean searchOrphanProject(ServerLevel level, BlockPos point, EntityMaid maid)
    {
        // 搜索范围 30 格（与家园范围大致匹配）
        ChoppingProject orphan = ProjectServerHelper.findAvailableProject(maid, 30 * 30, ChoppingProject.class);
        if (orphan != null && orphan.claim(maid.getUUID()))
        {
            maid.getBrain().setMemory(ModMemories.PROJECT_UUID.get(), orphan.getId());
            LOGGER.info("LoggingTask: maid {} claimed orphan project {}", maid.getId(), orphan.getId());
            return true;
        }
        return false;
    }

    // 找到树脚（原木列表中 Y 最低的方块）
    private BlockPos findTreeBase(List<BlockPos> logs)
    {
        BlockPos base = logs.get(0);
        for (BlockPos p : logs)
        {
            if (p.getY() < base.getY()) base = p;
        }
        return base;
    }

    // ===================== 辅助方法 =====================

    // 检查原木周围是否有树叶（排除孤立的木头）
    private boolean hasAdjacentLeaves(ServerLevel level, BlockPos log)
    {
        for (Direction d : Direction.values())
        {
            if (level.getBlockState(log.relative(d)).is(BlockTags.LEAVES)) return true;
        }
        return false;
    }
}
