package com.fennecmomo.maidmorework.lib.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

public final class WireframeBoxRenderer
{
    private WireframeBoxRenderer() {}

    public static void render(VertexConsumer vc, Matrix4f matrix,
                               double x0, double y0, double z0,
                               double x1, double y1, double z1,
                               float r, float g, float b, float a)
    {
        float fx0 = (float) x0, fy0 = (float) y0, fz0 = (float) z0;
        float fx1 = (float) x1, fy1 = (float) y1, fz1 = (float) z1;

        // Bottom face
        line(vc, matrix, fx0, fy0, fz0, fx1, fy0, fz0, r, g, b, a);
        line(vc, matrix, fx1, fy0, fz0, fx1, fy0, fz1, r, g, b, a);
        line(vc, matrix, fx1, fy0, fz1, fx0, fy0, fz1, r, g, b, a);
        line(vc, matrix, fx0, fy0, fz1, fx0, fy0, fz0, r, g, b, a);

        // Top face
        line(vc, matrix, fx0, fy1, fz0, fx1, fy1, fz0, r, g, b, a);
        line(vc, matrix, fx1, fy1, fz0, fx1, fy1, fz1, r, g, b, a);
        line(vc, matrix, fx1, fy1, fz1, fx0, fy1, fz1, r, g, b, a);
        line(vc, matrix, fx0, fy1, fz1, fx0, fy1, fz0, r, g, b, a);

        // Vertical pillars
        line(vc, matrix, fx0, fy0, fz0, fx0, fy1, fz0, r, g, b, a);
        line(vc, matrix, fx1, fy0, fz0, fx1, fy1, fz0, r, g, b, a);
        line(vc, matrix, fx1, fy0, fz1, fx1, fy1, fz1, r, g, b, a);
        line(vc, matrix, fx0, fy0, fz1, fx0, fy1, fz1, r, g, b, a);
    }

    private static void line(VertexConsumer vc, Matrix4f m,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float r, float g, float b, float a)
    {
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a).setLineWidth(5).setNormal(0, 1, 0);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setLineWidth(5).setNormal(0, 1, 0);
    }
}
