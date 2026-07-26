package com.fennecmomo.maidmorework.project;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.project.hud.HudLineRegistry;
import com.fennecmomo.maidmorework.project.hud.IHudLine;
import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import com.fennecmomo.maidmorework.project.hud.ProjectHudQueryPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

// 客户端工程数据管理与 HUD 渲染
// 负责定时向服务端查询附近工程、缓存数据、渲染右上角 HUD 面板
@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public final class ProjectClientHelper
{
    // 服务端回包缓存：projectId → 工程摘要
    private static final Map<UUID, ProjectHudPayload.Entry> DATA = new ConcurrentHashMap<>();

    // 面板样式
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BG_COLOR = 0xAA000000;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int VALUE_COLOR = 0xFFFFFFFF;

    // 查询间隔计数器
    private static int queryTimer = 0;

    private ProjectClientHelper() {}

    // ===================== 数据拉取 =====================

    // 每 10 tick 向服务端发起一次附近工程查询
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

    // ===================== 数据缓存 =====================

    // 接收服务端回包，清空缓存并存入非完成工程
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

    // ===================== HUD 渲染 =====================

    // 在聊天层之后（VanillaGuiLayers.CHAT）渲染半透明面板
    @SubscribeEvent
    public static void onRenderGui(RenderGuiLayerEvent.Post event)
    {
        if (!event.getName().equals(VanillaGuiLayers.CHAT)) return;
        if (DATA.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator()) return;
        GuiGraphicsExtractor g = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();

        int x = screenW - 5;
        int y = 5;

        for (ProjectHudPayload.Entry e : DATA.values())
        {
            var lines = HudLineRegistry.getEnabled();

            int panelW = 0;
            for (IHudLine line : lines)
            {
                Component text = Component.empty().append(line.label()).append(": ").append(line.format(e));
                int w = font.width(text);
                if (w > panelW) panelW = w;
            }
            panelW += PADDING * 2;
            int panelH = lines.size() * LINE_HEIGHT + PADDING * 2;
            int px = x - panelW;

            g.fill(px, y, px + panelW, y + panelH, BG_COLOR);

            int lineY = y + PADDING;
            for (IHudLine line : lines)
            {
                Component label = line.label().copy().withColor(LABEL_COLOR);
                Component value = line.format(e).copy().withColor(VALUE_COLOR);
                Component text = Component.empty().append(label).append(": ").append(value);
                g.text(font, text, px + PADDING, lineY, VALUE_COLOR);
                lineY += LINE_HEIGHT;
            }

            y += panelH + 2;
        }
    }
}
