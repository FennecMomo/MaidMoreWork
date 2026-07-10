package com.fennecmomo.maidmorework.entity.ai;

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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 伐木行为包（TLM 工作模式）
// 同一时间只能激活一个工作，切换任务时由 TLM 刷新 Brain
// createBrainTasks 返回搜索行为和砍伐行为，两者通过 Memory 协调
// 搜索行为找到树后写方块列表到 Memory，砍伐行为读 Memory 走向并砍伐
public class LoggingTask implements IMaidTask
{
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "logging");

    @Override
    public Identifier getUid()
    {
        return UID;
    }

    @Override
    public ItemStack getIcon()
    {
        return Items.IRON_AXE.getDefaultInstance();
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid)
    {
        return SoundEvents.WOOD_BREAK;
    }

    // 关闭 TLM 自带的闲逛行为，避免砍树时乱跑
    // SearchBehavior 自带游荡逻辑，不依赖 TLM 的 RandomStroll
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid)
    {
        return false;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        // 必须返回可变列表，TLM 会往里追加额外行为（更新活动/祈求/工作餐等）
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = new ArrayList<>();
        // 搜索行为：游荡扫描区域，找到树后写 Memory
        tasks.add(Pair.of(5, new SearchBehavior(this::scanForTree, ModMemories.LOG_BLOCKS.get(), 15, 1, 14)));
        // 砍伐行为：读 Memory 走向并砍伐
        tasks.add(Pair.of(6, new ChopBehavior()));
        return tasks;
    }

    // 伐木检索方法：扫描区域找带树叶的原木，BFS 整棵树，写 Memory
    // 由 SearchBehavior 每 20 tick 调用一次
    // 返回 true 表示找到目标（已写 Memory），false 表示没找到
    private boolean scanForTree(ServerLevel level, BlockPos center, int halfXZ, int yDown, int yUp, EntityMaid maid)
    {
        var scan = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - halfXZ; x <= center.getX() + halfXZ; x++)
        {
            for (int z = center.getZ() - halfXZ; z <= center.getZ() + halfXZ; z++)
            {
                for (int y = center.getY() - yDown; y <= center.getY() + yUp; y++)
                {
                    scan.set(x, y, z);
                    if (!level.isLoaded(scan)) continue;
                    BlockState state = level.getBlockState(scan);
                    // 找带相邻树叶的原木（确认是树不是孤立原木）
                    if (state.is(BlockTags.LOGS) && hasAdjacentLeaves(level, scan))
                    {
                        BlockPos treeBase = scan.immutable();
                        // BFS 分离原木和树叶
        List<BlockPos> logs = new ArrayList<>();
        List<BlockPos> leaves = new ArrayList<>();
                        bfsTree(level, treeBase, logs, leaves);
                        if (!logs.isEmpty())
                        {
                            // 分别写原木列表和树叶列表到 Memory
                            maid.getBrain().setMemory(ModMemories.LOG_BLOCKS.get(), logs);
                            maid.getBrain().setMemory(ModMemories.LEAVES_BLOCKS.get(), leaves);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // 检查原木方块是否有相邻树叶
    private boolean hasAdjacentLeaves(ServerLevel level, BlockPos log)
    {
        for (Direction d : Direction.values())
        {
            if (level.getBlockState(log.relative(d)).is(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    // BFS 遍历整棵树，分别收集原木和树叶到各自列表
    private void bfsTree(ServerLevel level, BlockPos start, List<BlockPos> logs, List<BlockPos> leaves)
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
            }
            else if (state.is(BlockTags.LEAVES))
            {
                leaves.add(p);
            }
            else
            {
                continue;
            }
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
}
