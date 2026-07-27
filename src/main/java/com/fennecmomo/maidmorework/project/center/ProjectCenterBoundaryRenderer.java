package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.UUID;

@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public final class ProjectCenterBoundaryRenderer
{
    private static final double RENDER_DIST_SQ = 64.0 * 64.0;

    private static final float[] COLOR_DEFAULT = { 0.3f, 0.8f, 1.0f, 0.5f };
    private static final float[] COLOR_BOUND = { 1.0f, 0.8f, 0.2f, 0.7f };

    private ProjectCenterBoundaryRenderer() {}

    @SubscribeEvent
    public static void onAfterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!isHoldingMarker(mc.player.getMainHandItem())
                && !isHoldingMarker(mc.player.getOffhandItem()))
        {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        PoseStack ps = event.getPoseStack();

        UUID boundId = getBoundMarkerId(mc.player.getMainHandItem());
        if (boundId == null) boundId = getBoundMarkerId(mc.player.getOffhandItem());

        var instances = ProjectCenterInstanceManager.allComplete(mc.level);
        if (instances.isEmpty()) return;

        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        var renderType = RenderTypes.LINES_TRANSLUCENT;
        VertexConsumer vc = buf.getBuffer(renderType);

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = ps.last().pose();

        for (ProjectCenterInstance inst : instances)
        {
            double cx = inst.center().getX() + 0.5;
            double cy = inst.center().getY() + 0.5;
            double cz = inst.center().getZ() + 0.5;
            if ((cx - cam.x) * (cx - cam.x) + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z) > RENDER_DIST_SQ) continue;

            float x0 = inst.minX();
            float y0 = inst.minY();
            float z0 = inst.minZ();
            float x1 = inst.maxX() + 1.0f;
            float y1 = inst.maxY() + 1.0f;
            float z1 = inst.maxZ() + 1.0f;

            float[] c = (boundId != null && boundId.equals(inst.getId())) ? COLOR_BOUND : COLOR_DEFAULT;

            renderWireframeBox(vc, m, x0, y0, z0, x1, y1, z1, c[0], c[1], c[2], c[3]);
        }

        buf.endBatch(renderType);
        ps.popPose();
    }

    private static void renderWireframeBox(VertexConsumer vc, Matrix4f m,
                                            float x0, float y0, float z0,
                                            float x1, float y1, float z1,
                                            float r, float g, float b, float a)
    {
        // Bottom face (y = y0)
        line(vc, m, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, m, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, m, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, m, x0, y0, z1, x0, y0, z0, r, g, b, a);

        // Top face (y = y1)
        line(vc, m, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, m, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, m, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, m, x0, y1, z1, x0, y1, z0, r, g, b, a);

        // Vertical pillars
        line(vc, m, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, m, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, m, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, m, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(VertexConsumer vc, Matrix4f m,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float r, float g, float b, float a)
    {
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a).setLineWidth(5).setNormal(0, 1, 0);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setLineWidth(5).setNormal(0, 1, 0);
    }

    private static boolean isHoldingMarker(ItemStack stack)
    {
        return stack.getItem() instanceof ProjectCenterMarkerItem;
    }

    private static UUID getBoundMarkerId(ItemStack stack)
    {
        if (stack.getItem() instanceof ProjectCenterMarkerItem && ProjectCenterMarkerItem.isBound(stack))
        {
            return ProjectCenterMarkerItem.getBoundId(stack);
        }
        return null;
    }
}
