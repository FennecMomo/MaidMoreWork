package com.fennecmomo.maidmorework.mining;

import java.util.ArrayList;
import java.util.List;

import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.search.SearchBehavior;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// 挖矿行为包（IMaidTask 实现，TLM 工作模式）
//
// 组装两个行为到女仆 Brain：
//   SearchBehavior（优先级 5） — 螺旋遍历坐标，找到矿井方块后停止
//   MiningBehavior（优先级 6） — 到达矿井后执行挖矿任务（DUG/FILL/LIGHT/REPLACE）
//
// 工作流程：
//   1. SearchBehavior 从女仆位置开始螺旋搜索，找到 MineBlock 时写入 Memory
//   2. MiningBehavior 检测到 Memory 有值，导航到矿井方块开始挖矿
//   3. 挖完一个周期后清空 Memory，回到步骤 1 重新搜索
//
// 与 LoggingTask 共用 SearchBehavior，区别在于 scanForOre 查找的是 MineBlock
// 而 LoggingTask 的 scanAction 查找的是原木方块
public class MiningTask implements IMaidTask
{
    // 任务唯一标识，用于注册和查找
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "mining");

    // 返回任务 UID
    @Override
    public Identifier getUid() { return UID; }

    // 任务图标，显示在任务列表 UI 中
    @Override
    public ItemStack getIcon() { return Items.IRON_PICKAXE.getDefaultInstance(); }

    // 执行任务时的环境音效
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) { return SoundEvents.STONE_BREAK; }

    // 禁用默认闲逛和随机转头，由 SearchBehavior 控制移动
    // 避免女仆在搜索过程中被原生闲逛行为干扰
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) { return false; }

    // 组装行为列表：SearchBehavior(找矿井) + MiningBehavior(挖矿)
    // 写入工作关键词到 Memory 和 Attachment，供气泡框显示和重启恢复
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        // 写入工作关键词，供 SearchBehavior 拼气泡文案（如 "家园范围内没有可用的矿井"）
        maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), "挖矿");
        maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), "矿井");

        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = new ArrayList<>();
        // SearchBehavior 负责螺旋遍历坐标，找到矿井方块后停止
        // 参数：scanAction=scanForOre, 搜索范围=15格XZ/1格下/14格上, 受家园限制
        tasks.add(Pair.of(5, new SearchBehavior(this::scanForOre, ModMemories.LOG_BLOCKS.get(), 15, 1, 14, true)));
        // MiningBehavior 负责到达矿井后执行挖矿任务
        tasks.add(Pair.of(6, new MiningBehavior()));
        return tasks;
    }

    // SearchBehavior 每推进一个螺旋点就调用一次
    // 检查该坐标是否为矿井方块 (MineBlock)
    // 找到后写入目标 Memory 和 Attachment，SearchBehavior 检测到后停止
    // TODO: 后续换为挖矿专用 Memory（目前复用 LOG_BLOCKS）
    private boolean scanForOre(ServerLevel level, BlockPos point, EntityMaid maid)
    {
        if (!level.isLoaded(point)) return false;

        // 检查该坐标是否为矿井方块
        if (level.getBlockState(point).getBlock() instanceof MineBlock)
        {
            // 写入目标 Memory，SearchBehavior 检测到后停止
            // TODO: 后续换为挖矿专用 Memory
            maid.getBrain().setMemory(ModMemories.LOG_BLOCKS.get(), List.of(point));
            return true;
        }
        return false;
    }
}
