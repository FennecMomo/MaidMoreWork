package com.fennecmomo.maidmorework;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

// 自定义 Memory 类型注册（伐木/挖矿行为共用）
// Memory 是运行时数据，不序列化（不存盘），只在行为执行期间使用
//
// 词条归属原则（一词条只一边记）：
//   女仆侧只记与自身相关的事，持久化只靠 Attachment（见 ModAttachments）
//   Memory 是运行时缓存：PROJECT_CENTER_UUID 指向所属中心（唯一持久化词条），
//   PROJECT_UUID 是中心授予的运行时工程句柄（不持久化，重进后由中心重新授予）
//   LOG_BLOCKS 是挖矿专用目标列表（挖矿系统尚未接入新版工程框架，暂保留）
public class ModMemories
{
    // ===================== Memory 类型注册 =====================
    // Memory 类型延迟注册表
    public static final DeferredRegister<MemoryModuleType<?>> MEMORY_MODULE_TYPES =
            DeferredRegister.create(Registries.MEMORY_MODULE_TYPE, MaidMoreWork.MODID);

    // 挖矿目标方块列表（挖矿 SearchBehavior 找到矿井后写入）
    // 挖矿系统未接入新版工程框架前暂用此词条，后续重构为挖矿专用
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<List<BlockPos>>> LOG_BLOCKS =
            MEMORY_MODULE_TYPES.register("log_blocks", () -> new MemoryModuleType<>(Optional.empty()));

    // 当前工作工程 UUID（中心授予的运行时句柄）
    // 中心通过 assignments 表管理归属（唯一真相），本词条仅作运行期快速查询
    // 重进世界后为空，女仆向中心请求工程时重新授予
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<UUID>> PROJECT_UUID =
            MEMORY_MODULE_TYPES.register("project_uuid", () -> new MemoryModuleType<>(Optional.empty()));

    // 女仆所属中心 UUID（唯一持久化词条，对应 ModAttachments.PROJECT_CENTER_UUID_SAVED）
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<UUID>> PROJECT_CENTER_UUID =
            MEMORY_MODULE_TYPES.register("project_center_uuid", () -> new MemoryModuleType<>(Optional.empty()));
}
