package com.fennecmomo.maidmorework;

import com.fennecmomo.maidmorework.spblock.SPRegistration;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.List;

// maidmorework 模组主类
// 负责注册自定义 MemoryModuleType、AttachmentType 和 SPBlock 方块
@Mod(MaidMoreWork.MODID)
public class MaidMoreWork
{
    public static final String MODID = "maidmorework";

    public MaidMoreWork(IEventBus modBus)
    {
        ModMemories.MEMORY_MODULE_TYPES.register(modBus);
        ModAttachments.ATTACHMENT_TYPES.register(modBus);
        SPRegistration.BLOCKS.register(modBus);
        SPRegistration.BLOCK_ENTITIES.register(modBus);

        NeoForge.EVENT_BUS.addListener(this::onEntityJoinLevel);
    }

    // 女仆加入世界时，从 Attachment 恢复 LOG_BLOCKS / LEAVES_BLOCKS 到 Memory
    // 仅当 Memory 为空时恢复（女仆正在执行其他任务或没活干时才恢复）
    private void onEntityJoinLevel(EntityJoinLevelEvent event)
    {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof EntityMaid maid)) return;

        // Memory 为空才恢复，避免覆盖正在执行的任务
        if (maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get()).isEmpty())
        {
            List<BlockPos> saved = maid.getData(ModAttachments.LOG_BLOCKS_SAVED);
            if (saved != null && !saved.isEmpty())
            {
                maid.getBrain().setMemory(ModMemories.LOG_BLOCKS.get(), saved);
                List<BlockPos> savedLeaves = maid.getData(ModAttachments.LEAVES_BLOCKS_SAVED);
                if (savedLeaves != null && !savedLeaves.isEmpty())
                {
                    maid.getBrain().setMemory(ModMemories.LEAVES_BLOCKS.get(), savedLeaves);
                }
            }
        }
    }
}
