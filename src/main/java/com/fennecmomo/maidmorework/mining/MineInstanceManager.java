package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// 矿井实例管理器 — 服务端内存存储
// 持久化由MineBlockEntity的NBT负责
public class MineInstanceManager
{
    private static final Map<String, Map<UUID, MineInstance>> worldInstances = new ConcurrentHashMap<>();

    private static String worldKey(Level level)
    {
        return level.dimension().toString();
    }

    private static Map<UUID, MineInstance> getMap(Level level)
    {
        return worldInstances.computeIfAbsent(worldKey(level), k -> new ConcurrentHashMap<>());
    }

    // 创建新实例
    public static MineInstance create(Level level, UUID owner, BlockPos pos)
    {
        UUID id = UUID.randomUUID();
        MineInstance inst = new MineInstance(id, owner, null, null);
        inst.setClickPoint1(pos);
        getMap(level).put(id, inst);
        return inst;
    }

    // 获取实例
    public static MineInstance get(Level level, UUID id)
    {
        return getMap(level).get(id);
    }

    // 直接放入实例（用于从BlockEntity恢复）
    public static void put(Level level, MineInstance inst)
    {
        getMap(level).put(inst.getId(), inst);
    }

    // 删除实例
    public static void remove(Level level, UUID id)
    {
        getMap(level).remove(id);
    }

    // 找某个位置的矿井实例（用于判断女仆的工作范围归属）
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

    // 获取所有完整矿井
    public static List<MineInstance> allComplete(Level level)
    {
        List<MineInstance> list = new ArrayList<>();
        for (MineInstance inst : getMap(level).values())
        {
            if (inst.isComplete()) list.add(inst);
        }
        return list;
    }

    // 世界卸载清理
    public static void clear(Level level)
    {
        worldInstances.remove(worldKey(level));
    }
}
