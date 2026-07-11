package com.fennecmomo.maidmorework;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Optional;

// 伐木行为包的自定义 Memory 类型
// 4 个运行时 Memory，不序列化（不存盘），只在行为执行期间使用
public class ModMemories
{
    public static final DeferredRegister<MemoryModuleType<?>> MEMORY_MODULE_TYPES =
            DeferredRegister.create(Registries.MEMORY_MODULE_TYPE, MaidMoreWork.MODID);

    // BFS 出的原木方块列表（只含原木，抵达后逐个砍伐）
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<List<BlockPos>>> LOG_BLOCKS =
            MEMORY_MODULE_TYPES.register("log_blocks", () -> new MemoryModuleType<>(Optional.empty()));

    // BFS 出的树叶方块列表（整棵树原木砍完后同时销毁，掉落物进背包）
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<List<BlockPos>>> LEAVES_BLOCKS =
            MEMORY_MODULE_TYPES.register("leaves_blocks", () -> new MemoryModuleType<>(Optional.empty()));

    // 垫脚方块列表（女仆够不到高处原木时放置，整棵树砍完后依次破坏回收）
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<List<BlockPos>>> SCAFFOLDING_BLOCKS =
            MEMORY_MODULE_TYPES.register("scaffolding_blocks", () -> new MemoryModuleType<>(Optional.empty()));

    // 当前目标方块（正在走向或砍伐的方块）
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> LOG_TARGET =
            MEMORY_MODULE_TYPES.register("log_target", () -> new MemoryModuleType<>(Optional.empty()));

    // 砍伐计时器（控制砍伐节奏，到阈值才破坏方块）
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Integer>> CHOP_TIMER =
            MEMORY_MODULE_TYPES.register("chop_timer", () -> new MemoryModuleType<>(Optional.empty()));

    // 初始化标记（抵达树脚后排序标记，false=未排序，true=已排序）
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> LOG_INITIALIZED =
            MEMORY_MODULE_TYPES.register("log_initialized", () -> new MemoryModuleType<>(Optional.empty()));

    // 当前工作行为描述（如"砍树"、"挖矿"），供通用 SearchBehavior 拼气泡文案
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<String>> WORK_ACTION =
            MEMORY_MODULE_TYPES.register("work_action", () -> new MemoryModuleType<>(Optional.empty()));

    // 当前工作目标描述（如"原木"、"矿石"），供通用 SearchBehavior 拼气泡文案
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<String>> WORK_TARGET =
            MEMORY_MODULE_TYPES.register("work_target", () -> new MemoryModuleType<>(Optional.empty()));
}
