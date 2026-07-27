package com.fennecmomo.maidmorework.project;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.lib.render.BillboardRenderer;
import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public final class ProjectHudRenderer
{
    private static final Identifier WHITE_TEX =
            Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "textures/gui/white.png");

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

            List<Component> lines = buildLines(e, mc.font);
            BillboardRenderer.render(buf, ps, mc.font, lines,
                    MaidMoreWorkConfig.PANEL_SEE_THROUGH_LIGHT, 0.6f);

            ps.popPose();
        }

        buf.endBatch(RenderTypes.entityTranslucent(WHITE_TEX));
    }

    private static List<Component> buildLines(ProjectHudPayload.Entry e, Font font)
    {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(e.type()));

        int completed = (int) Math.round(e.progress());
        lines.add(Component.literal(completed + "/" + e.workload()));

        if (e.participantCount() > 0)
        {
            lines.add(Component.literal(e.participantCount() + "人参与"));
        }
        return lines;
    }
}
