package com.fennecmomo.maidmorework;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Optional;

// 自定义 Memory 类型注册（伐木/挖矿行为共用）
// Memory 是运行时数据，不序列化（不存盘），只在行为执行期间使用
// 持久化由 ModAttachments 负责，世界重进时从 Attachment 恢复
// 通过 LoggingExtraBrain/MiningExtraBrain 注册到 TLM Brain
public class ModMemories
{
    // Memory 类型延迟注册表
    public static final DeferredRegister<MemoryModuleType<?>> MEMORY_MODULE_TYPES =
            DeferredRegister.create(Registries.MEMORY_MODULE_TYPE, MaidMoreWork.MODID);

    // BFS 出的原木方块列表（只含原木，抵达后逐个砍伐）
    // SearchBehavior 找到目标后写入，ChopBehavior/MiningBehavior 启动时读取
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<List<BlockPos>>> LOG_BLOCKS =
            MEMORY_MODULE_TYPES.register("log_blocks", () -> new MemoryModuleType<>(Optional.empty()));

    // BFS 出的树叶方块列表（整棵树原木砍完后同时标记为蓝图并收集）
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<List<BlockPos>>> LEAVES_BLOCKS =
            MEMORY_MODULE_TYPES.register("leaves_blocks", () -> new MemoryModuleType<>(Optional.empty()));

    // 垫脚方块列表（女仆够不到高处原木时放置，整棵树砍完后依次破坏回收）
    // 当前版本暂未使用，预留字段
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<List<BlockPos>>> SCAFFOLDING_BLOCKS =
            MEMORY_MODULE_TYPES.register("scaffolding_blocks", () -> new MemoryModuleType<>(Optional.empty()));

    // 当前目标方块（正在走向或砍伐的方块）
    // 当前版本暂未使用，预留字段
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> LOG_TARGET =
            MEMORY_MODULE_TYPES.register("log_target", () -> new MemoryModuleType<>(Optional.empty()));

    // 砍伐计时器（控制砍伐节奏，到阈值才破坏方块）
    // 当前版本暂未使用，ChopBehavior 内部自己维护计时
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Integer>> CHOP_TIMER =
            MEMORY_MODULE_TYPES.register("chop_timer", () -> new MemoryModuleType<>(Optional.empty()));

    // 初始化标记（抵达树脚后排序标记）
    // 当前版本暂未使用，预留字段
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<Boolean>> LOG_INITIALIZED =
            MEMORY_MODULE_TYPES.register("log_initialized", () -> new MemoryModuleType<>(Optional.empty()));

    // 当前工作行为描述（如“砍树”、“挖矿”）
    // 供 SearchBehavior 拼气泡文案：“无法砍树，请开启Home模式”
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<String>> WORK_ACTION =
            MEMORY_MODULE_TYPES.register("work_action", () -> new MemoryModuleType<>(Optional.empty()));

    // 当前工作目标描述（如“原木”、“矿石”）
    // 供 SearchBehavior 拼气泡文案：“家园范围内没有可用的原木”
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<String>> WORK_TARGET =
            MEMORY_MODULE_TYPES.register("work_target", () -> new MemoryModuleType<>(Optional.empty()));
}
