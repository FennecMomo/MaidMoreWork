package com.fennecmomo.maidmorework.project;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public final class ProjectHudRenderer
{
    private static final Identifier WHITE_TEX =
            Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "textures/gui/white.png");

    private static final RenderType PANEL_BG = RenderTypes.entityTranslucent(WHITE_TEX);

    private ProjectHudRenderer() {}

    @SubscribeEvent
    public static void onAfterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event)
    {
        var data = ProjectClientHelper.DATA;
        if (data.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        int maxDistSq = MaidMoreWorkConfig.HUD_RENDER_DISTANCE * MaidMoreWorkConfig.HUD_RENDER_DISTANCE;

        List<ProjectHudPayload.Entry> sorted = new ArrayList<>(data.values());
        sorted.sort((a, b) -> Double.compare(
                b.position().distToCenterSqr(camPos),
                a.position().distToCenterSqr(camPos)
        ));

        for (ProjectHudPayload.Entry e : sorted)
        {
            var pos = e.position();
            if (pos == null) continue;
            if (pos.distToCenterSqr(camPos) > maxDistSq) continue;

            Vec3 worldPos = pos.getCenter().add(0, MaidMoreWorkConfig.PANEL_Y_OFFSET, 0);

            ps.pushPose();
            ps.translate(worldPos.x - camPos.x, worldPos.y - camPos.y, worldPos.z - camPos.z);
            ps.mulPose(camera.rotation());
            ps.scale(MaidMoreWorkConfig.PANEL_SCALE, -MaidMoreWorkConfig.PANEL_SCALE, MaidMoreWorkConfig.PANEL_SCALE);
            ps.translate(0, 0, MaidMoreWorkConfig.PANEL_TOWARD_PLAYER_OFFSET);

            renderPanel(ps, buf, mc, e);

            ps.popPose();
        }

        buf.endBatch(PANEL_BG);
    }

    private static void renderPanel(PoseStack ps, MultiBufferSource.BufferSource buf,
                                     Minecraft mc, ProjectHudPayload.Entry e)
    {
        Font font = mc.font;

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(e.type()));

        int completed = (int) Math.round(e.progress() * e.workload());
        lines.add(Component.literal(completed + "/" + e.workload()));

        if (e.participantCount() > 0)
        {
            lines.add(Component.literal(e.participantCount() + "人参与"));
        }

        int lineHeight = font.lineHeight;
        int maxW = 0;
        for (Component line : lines)
        {
            int w = font.width(line);
            if (w > maxW) maxW = w;
        }

        float pad = 4;
        float bgW = maxW + pad * 2;
        float bgH = lines.size() * lineHeight + pad * 2;
        float bgX = -bgW / 2;
        float bgY = -bgH / 2;
        float z = 0;

        Matrix4f matrix = ps.last().pose();
        int light = MaidMoreWorkConfig.PANEL_SEE_THROUGH_LIGHT;

        // Background: semi-transparent dark rect
        VertexConsumer vc = buf.getBuffer(PANEL_BG);
        float a = 0.6f;
        vc.addVertex(matrix, bgX, bgY + bgH, z).setColor(0, 0, 0, a).setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(matrix, bgX + bgW, bgY + bgH, z).setColor(0, 0, 0, a).setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(matrix, bgX + bgW, bgY, z).setColor(0, 0, 0, a).setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(matrix, bgX, bgY, z).setColor(0, 0, 0, a).setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);

        // Text lines
        float textY = bgY + pad;
        for (int i = 0; i < lines.size(); i++)
        {
            Component line = lines.get(i);
            int textW = font.width(line);
            float textX = -textW / 2f;
            int color = i == 0 ? 0xFFFFAA00 : 0xFFFFFFFF;
            font.drawInBatch(line, textX, textY, color, false, matrix, buf,
                    Font.DisplayMode.SEE_THROUGH, 0, light);
            textY += lineHeight;
        }
    }
}
