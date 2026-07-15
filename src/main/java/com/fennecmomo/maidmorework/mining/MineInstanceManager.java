package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// 矿井实例管理器 — 服务端内存存储（纯静态工具类）
// 按世界维度分桶存储矿井实例，使用 ConcurrentHashMap 保证线程安全
// 持久化由 MineBlockEntity 的 NBT 负责，本类只维护运行时缓存
// 世界重进时 MineBlockEntity.onLoad() 把实例恢复到这里
public class MineInstanceManager
{
    // 世界维度字符串 → 矿井实例映射表
    private static final Map<String, Map<UUID, MineInstance>> worldInstances = new ConcurrentHashMap<>();

    // 根据世界对象获取维度字符串 key
    private static String worldKey(Level level)
    {
        return level.dimension().toString();
    }

    // 获取指定世界的实例映射表，不存在则创建
    private static Map<UUID, MineInstance> getMap(Level level)
    {
        return worldInstances.computeIfAbsent(worldKey(level), k -> new ConcurrentHashMap<>());
    }

    // 创建新矿井实例：生成 UUID，设第一个点击点，存入内存
    // 返回新创建的实例（未完成的，还需要设置第二个点击点）
    public static MineInstance create(Level level, UUID owner, BlockPos pos)
    {
        UUID id = UUID.randomUUID();
        MineInstance inst = new MineInstance(id, owner, null, null);
        inst.setClickPoint1(pos);
        getMap(level).put(id, inst);
        return inst;
    }

    // 获取实例（可能返回 null，如世界重进后尚未恢复）
    public static MineInstance get(Level level, UUID id)
    {
        return getMap(level).get(id);
    }

    // 直接放入实例（用于从 BlockEntity 恢复时调用）
    public static void put(Level level, MineInstance inst)
    {
        getMap(level).put(inst.getId(), inst);
    }

    // 删除实例（删除矿井或取消创建时调用）
    public static void remove(Level level, UUID id)
    {
        getMap(level).remove(id);
    }

    // 找某个位置的矿井实例（用于判断女仆的工作范围归属）
    // 遍历所有完整矿井，检查是否包含该坐标
    public static MineInstance findByPos(Level level, BlockPos pos)
    {
        for (MineInstance inst : getMap(level).values())
        {
            if (inst.isComplete() && inst.contains(pos))
            {
                return inst;
            }
        }
        return null;
    }

    // 获取指定世界的所有完整矿井（两角点已设置）
    public static List<MineInstance> allComplete(Level level)
    {
        List<MineInstance> list = new ArrayList<>();
        for (MineInstance inst : getMap(level).values())
        {
            if (inst.isComplete()) list.add(inst);
        }
        return list;
    }

    // 世界卸载清理（释放内存，避免内存泄漏）
    public static void clear(Level level)
    {
        worldInstances.remove(worldKey(level));
    }
}
