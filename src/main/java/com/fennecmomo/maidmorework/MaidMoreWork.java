package com.fennecmomo.maidmorework;

import com.fennecmomo.maidmorework.spblock.SPRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

// maidmorework 模组主类
// 负责注册自定义 MemoryModuleType 和 SPBlock 方块
@Mod(MaidMoreWork.MODID)
public class MaidMoreWork
{
    public static final String MODID = "maidmorework";

    public MaidMoreWork(IEventBus modBus)
    {
        ModMemories.MEMORY_MODULE_TYPES.register(modBus);
        SPRegistration.BLOCKS.register(modBus);
        SPRegistration.BLOCK_ENTITIES.register(modBus);
    }
}
