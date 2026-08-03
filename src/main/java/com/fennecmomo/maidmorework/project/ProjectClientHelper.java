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

    public static BlockPos infoCenterPos = null;
    public static String infoTypeName = "";
    public static int infoProjectCount = 0;
    public static int infoMaidCount = 0;
    public static int infoRadius = 0;

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
        infoCenterPos = null;
        infoTypeName = "";
        infoProjectCount = 0;
        infoMaidCount = 0;
        infoRadius = 0;
    }
}
