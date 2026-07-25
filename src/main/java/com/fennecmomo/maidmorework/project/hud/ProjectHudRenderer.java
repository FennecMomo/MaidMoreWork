package com.fennecmomo.maidmorework.project.hud;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fennecmomo.maidmorework.MaidMoreWork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

// 工程 HUD 客户端渲染器
// 在聊天层之后（VanillaGuiLayers.CHAT）渲染半透明面板，显示当前维度的活跃工程列表
// 数据由服务端通过 ProjectHudPayload 网络包推送，static sync 方法接收并缓存
//
// 面板位于屏幕右上角，每条工程一个面板，每条面板包含 HudLineRegistry 中启用的行
// 行模板由 IHudLine 接口定义，注册见 HudLineRegistry
@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public final class ProjectHudRenderer
{
    // 服务端推送的工程数据缓存：projectId → 工程摘要
    private static final Map<UUID, ProjectHudPayload.Entry> DATA = new ConcurrentHashMap<>();

    // 面板样式常量
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BG_COLOR = 0xAA000000;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int VALUE_COLOR = 0xFFFFFFFF;

    // 接收服务端推送的数据包：清空缓存，按非完成过滤后存入 DATA
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

    // 渲染事件：在聊天层之后绘制 HUD 面板
    // 观察者模式跳过，无数据时跳过
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

        // 面板左上角锚点：屏幕右上角向内偏移 5px
        int x = screenW - 5;
        int y = 5;

        for (ProjectHudPayload.Entry e : DATA.values())
        {
            var lines = HudLineRegistry.getEnabled();

            // 两轮遍历：第一轮计算面板宽度，第二轮实际绘制
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

            // 半透明背景
            g.fill(px, y, px + panelW, y + panelH, BG_COLOR);

            // 逐行绘制标签: 值
            int lineY = y + PADDING;
            for (IHudLine line : lines)
            {
                Component label = line.label().copy().withColor(LABEL_COLOR);
                Component value = line.format(e).copy().withColor(VALUE_COLOR);
                Component text = Component.empty().append(label).append(": ").append(value);
                g.text(font, text, px + PADDING, lineY, VALUE_COLOR);
                lineY += LINE_HEIGHT;
            }

            // 下一个面板向下偏移
            y += panelH + 2;
        }
    }
}
