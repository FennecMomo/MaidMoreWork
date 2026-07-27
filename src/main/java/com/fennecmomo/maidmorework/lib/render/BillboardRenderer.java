package com.fennecmomo.maidmorework.lib.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.util.List;

public final class BillboardRenderer
{
    private static final Identifier WHITE_TEX =
            Identifier.fromNamespaceAndPath("maidmorework", "textures/gui/white.png");

    private static final RenderType PANEL_BG = RenderTypes.entityTranslucent(WHITE_TEX);

    private BillboardRenderer() {}

    public static void render(MultiBufferSource.BufferSource buf, PoseStack ps,
                               Font font, List<Component> lines, int light, float bgAlpha)
    {
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

        VertexConsumer vc = buf.getBuffer(PANEL_BG);
        vc.addVertex(matrix, bgX, bgY + bgH, z).setColor(0, 0, 0, bgAlpha).setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(matrix, bgX + bgW, bgY + bgH, z).setColor(0, 0, 0, bgAlpha).setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(matrix, bgX + bgW, bgY, z).setColor(0, 0, 0, bgAlpha).setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);
        vc.addVertex(matrix, bgX, bgY, z).setColor(0, 0, 0, bgAlpha).setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0, 0, 1);

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
