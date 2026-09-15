package com.fennecmomo.maidmorework.project;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import com.fennecmomo.maidmorework.project.hud.ProjectHudQueryPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public final class ProjectClientHelper
{
    public static final Map<UUID, ProjectHudPayload.Entry> DATA = new ConcurrentHashMap<>();

    // 每个工程中心的信息缓存（按中心方块坐标索引）
    // 2026-09-04 修正：原实现是全局单份信息，多中心每 20t 轮流广播会互相覆盖——
    // 远处中心的数据覆盖进来时面板因距离门限被隐藏，下一包换回近处中心又出现，表现为闪烁。
    // 改为按中心缓存 + 各自渲染面板，彻底消除覆盖问题。
    public record CenterInfo(String typeName, int projectCount, int maidCount, int radius, String name,
                             java.util.List<String> missingTools) {}

    public static final Map<BlockPos, CenterInfo> CENTERS = new ConcurrentHashMap<>();

    private static int queryTimer = 0;

    private ProjectClientHelper() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        queryTimer++;
        if (queryTimer >= 10)
        {
            queryTimer = 0;
            var conn = Minecraft.getInstance().getConnection();
            if (conn != null)
            {
                conn.send(new ProjectHudQueryPayload());
            }
        }
    }

    public static void sync(ProjectHudPayload payload)
    {
        DATA.clear();
        for (ProjectHudPayload.Entry e : payload.entries())
        {
            if (!e.completed())
            {
                DATA.put(e.projectId(), e);
            }
        }
    }

    public static void clear()
    {
        DATA.clear();
        CENTERS.clear();
    }
}
