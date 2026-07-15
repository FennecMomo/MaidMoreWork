package com.fennecmomo.maidmorework.entity.ai;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.mining.MineBlock;
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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 挖矿行为包（TLM 工作模式）
public class MiningTask implements IMaidTask
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "mining");

    @Override
    public Identifier getUid() { return UID; }

    @Override
    public ItemStack getIcon() { return Items.IRON_PICKAXE.getDefaultInstance(); }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) { return SoundEvents.STONE_BREAK; }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) { return false; }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        // 写入工作关键词，供 SearchBehavior 拼气泡文案
        maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), "挖矿");
        maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), "矿井");
        // 同步写 Attachment，退出重进后恢复
        maid.setData(ModAttachments.WORK_ACTION_SAVED, "挖矿");
        maid.setData(ModAttachments.WORK_TARGET_SAVED, "矿井");

        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = new ArrayList<>();
        // SearchBehavior 负责螺旋遍历坐标，找到矿井方块后停止
        tasks.add(Pair.of(5, new SearchBehavior(this::scanForOre, ModMemories.LOG_BLOCKS.get(), 15, 1, 14, true)));
        tasks.add(Pair.of(6, new MiningBehavior()));
        return tasks;
    }

    // SearchBehavior 每推进一个螺旋点就调用一次
    // 找到矿井方块（MineBlock）后写入目标 Memory 并返回 true
    private boolean scanForOre(ServerLevel level, BlockPos point, EntityMaid maid)
    {
        if (!level.isLoaded(point)) return false;

        // 检查该坐标是否为矿井方块
        if (level.getBlockState(point).getBlock() instanceof MineBlock)
        {
            LOGGER.info("MiningTask: found mine block at {} maid={}", point, maid.getId());
            // 写入目标 Memory，SearchBehavior 检测到后停止
            // TODO: 后续换为挖矿专用 Memory
            maid.getBrain().setMemory(ModMemories.LOG_BLOCKS.get(), List.of(point));
            maid.setData(ModAttachments.LOG_BLOCKS_SAVED, new ArrayList<>(List.of(point)));
            return true;
        }
        return false;
    }
}
