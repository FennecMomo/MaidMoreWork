package com.fennecmomo.maidmorework.project.center;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProjectCenterInstanceManager
{
    private static final Map<String, Map<UUID, ProjectCenterInstance>> worldInstances = new ConcurrentHashMap<>();

    private static String worldKey(Level level)
    {
        return level.dimension().toString();
    }

    private static Map<UUID, ProjectCenterInstance> getMap(Level level)
    {
        return worldInstances.computeIfAbsent(worldKey(level), k -> new ConcurrentHashMap<>());
    }

    public static ProjectCenterInstance create(Level level, UUID owner, BlockPos pos)
    {
        UUID id = UUID.randomUUID();
        ProjectCenterInstance inst = new ProjectCenterInstance(id, owner, null, null);
        inst.setClickPoint1(pos);
        getMap(level).put(id, inst);
        return inst;
    }

    public static ProjectCenterInstance get(Level level, UUID id)
    {
        return getMap(level).get(id);
    }

    public static void put(Level level, ProjectCenterInstance inst)
    {
        getMap(level).put(inst.getId(), inst);
    }

    public static void remove(Level level, UUID id)
    {
        getMap(level).remove(id);
    }

    public static ProjectCenterInstance findByPos(Level level, BlockPos pos)
    {
        for (ProjectCenterInstance inst : getMap(level).values())
        {
            if (inst.isComplete() && inst.contains(pos))
            {
                return inst;
            }
        }
        return null;
    }

    public static List<ProjectCenterInstance> allComplete(Level level)
    {
        List<ProjectCenterInstance> list = new ArrayList<>();
        for (ProjectCenterInstance inst : getMap(level).values())
        {
            if (inst.isComplete()) list.add(inst);
        }
        return list;
    }

    public static void clear(Level level)
    {
        worldInstances.remove(worldKey(level));
    }
}
