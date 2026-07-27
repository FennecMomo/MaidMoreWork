package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.lib.region.IRegionalManager;
import com.fennecmomo.maidmorework.lib.region.RegionalManagerRegistry;
import com.fennecmomo.maidmorework.lib.render.WireframeBoxRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
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

    private static final float[] COLOR_BOUND = { 1.0f, 0.8f, 0.2f, 0.7f };
    private static final float[] COLOR_PREVIEW = { 0.6f, 1.0f, 0.6f, 0.9f };

    private static volatile BlockPos previewCenterPos;
    private static volatile int previewRadius;
    private static volatile int previewAnchor;
    private static volatile boolean previewActive;

    private ProjectCenterBoundaryRenderer() {}

    public static void setPreview(BlockPos centerPos, int radius, int anchor)
    {
        previewCenterPos = centerPos;
        previewRadius = radius;
        previewAnchor = anchor;
        previewActive = true;
    }

    public static void clearPreview()
    {
        previewActive = false;
        previewCenterPos = null;
    }

    @SubscribeEvent
    public static void onAfterTranslucentBlocks(RenderLevelStageEvent.AfterTranslucentBlocks event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (previewActive && previewCenterPos != null)
        {
            renderPreviewBox(mc, event);
        }
        else
        {
            renderBoundBox(mc, event);
        }
    }

    private static void renderPreviewBox(Minecraft mc, RenderLevelStageEvent event)
    {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();

        double cx = previewCenterPos.getX() + 0.5;
        double cy = previewCenterPos.getY() + 0.5;
        double cz = previewCenterPos.getZ() + 0.5;
        if ((cx - cam.x) * (cx - cam.x) + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z) > RENDER_DIST_SQ) return;

        int r = previewRadius;
        double minY, maxY;
        switch (previewAnchor)
        {
            case 0 -> { minY = previewCenterPos.getY() - 2 * r; maxY = previewCenterPos.getY(); }
            case 2 -> { minY = previewCenterPos.getY(); maxY = previewCenterPos.getY() + 2 * r; }
            default -> { minY = previewCenterPos.getY() - r; maxY = previewCenterPos.getY() + r; }
        }
        double x0 = previewCenterPos.getX() - r;
        double z0 = previewCenterPos.getZ() - r;
        double x1 = previewCenterPos.getX() + r + 1.0;
        double y1 = maxY + 1.0;
        double z1 = previewCenterPos.getZ() + r + 1.0;

        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        var renderType = RenderTypes.LINES_TRANSLUCENT;
        VertexConsumer vc = buf.getBuffer(renderType);

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = ps.last().pose();

        WireframeBoxRenderer.render(vc, m, x0, minY, z0, x1, y1, z1,
                COLOR_PREVIEW[0], COLOR_PREVIEW[1], COLOR_PREVIEW[2], COLOR_PREVIEW[3]);

        buf.endBatch(renderType);
        ps.popPose();
    }

    private static void renderBoundBox(Minecraft mc, RenderLevelStageEvent event)
    {
        UUID boundId = getBoundMarkerId(mc.player.getMainHandItem());
        if (boundId == null) boundId = getBoundMarkerId(mc.player.getOffhandItem());
        if (boundId == null) return;

        IRegionalManager manager = RegionalManagerRegistry.get(boundId);
        if (manager == null || !manager.isBoundaryVisible()) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        PoseStack ps = event.getPoseStack();

        double cx = (manager.getMinCorner().getX() + manager.getMaxCorner().getX()) / 2.0 + 0.5;
        double cy = (manager.getMinCorner().getY() + manager.getMaxCorner().getY()) / 2.0 + 0.5;
        double cz = (manager.getMinCorner().getZ() + manager.getMaxCorner().getZ()) / 2.0 + 0.5;
        if ((cx - cam.x) * (cx - cam.x) + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z) > RENDER_DIST_SQ) return;

        double x0 = manager.getMinCorner().getX();
        double y0 = manager.getMinCorner().getY();
        double z0 = manager.getMinCorner().getZ();
        double x1 = manager.getMaxCorner().getX() + 1.0;
        double y1 = manager.getMaxCorner().getY() + 1.0;
        double z1 = manager.getMaxCorner().getZ() + 1.0;

        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        var renderType = RenderTypes.LINES_TRANSLUCENT;
        VertexConsumer vc = buf.getBuffer(renderType);

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = ps.last().pose();

        WireframeBoxRenderer.render(vc, m, x0, y0, z0, x1, y1, z1,
                COLOR_BOUND[0], COLOR_BOUND[1], COLOR_BOUND[2], COLOR_BOUND[3]);

        buf.endBatch(renderType);
        ps.popPose();
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
