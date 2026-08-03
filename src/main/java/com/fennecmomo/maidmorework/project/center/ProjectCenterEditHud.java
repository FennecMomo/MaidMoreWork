package com.fennecmomo.maidmorework.project.center;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class ProjectCenterEditHud extends Screen
{
    private final UUID centerId;
    private final BlockPos centerPos;
    private final int initialRadius;
    private final String initialName;
    private EditBox radiusInput;
    private EditBox nameInput;
    private int anchor;

    public static void open(ProjectCenterEditPayload payload)
    {
        Minecraft.getInstance().setScreen(new ProjectCenterEditHud(
                payload.centerId(), payload.centerPos(), payload.radius(), payload.anchor(), payload.name()));
    }

    public ProjectCenterEditHud(UUID centerId, BlockPos centerPos, int radius, int anchor, String name)
    {
        super(Component.empty());
        this.centerId = centerId;
        this.centerPos = centerPos;
        this.initialRadius = radius;
        this.initialName = name;
        this.anchor = anchor;
    }

    @Override
    protected void init()
    {
        int cx = this.width / 2;
        int y = 5;

        this.nameInput = new EditBox(this.font, cx - 80, y, 160, 20, Component.empty());
        this.nameInput.setValue(initialName);
        this.addRenderableWidget(this.nameInput);

        this.radiusInput = new EditBox(this.font, cx + 10, y + 25, 50, 20, Component.empty());
        this.radiusInput.setValue(String.valueOf(initialRadius));
        this.radiusInput.setResponder(this::onValueChanged);
        this.addRenderableWidget(this.radiusInput);

        int radioY = y + 50;
        this.addRenderableWidget(Button.builder(Component.literal("顶部"), b -> setAnchor(0))
                .pos(cx - 80, radioY).size(50, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("中心"), b -> setAnchor(1))
                .pos(cx - 25, radioY).size(50, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("底部"), b -> setAnchor(2))
                .pos(cx + 30, radioY).size(50, 20).build());

        int btnY = radioY + 28;
        this.addRenderableWidget(Button.builder(Component.literal("确认"), b -> onConfirm())
                .pos(cx - 55, btnY).size(50, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .pos(cx + 5, btnY).size(50, 20).build());
    }

    private void setAnchor(int value)
    {
        this.anchor = value;
        onValueChanged(radiusInput.getValue());
    }

    private void onValueChanged(String text)
    {
        try
        {
            int r = Integer.parseInt(text);
            if (r > 0)
            {
                ProjectCenterBoundaryRenderer.setPreview(centerPos, r, anchor);
            }
        }
        catch (NumberFormatException ignored) {}
    }

    private void onConfirm()
    {
        String radiusText = radiusInput.getValue();
        String anchorText = switch (anchor)
        {
            case 0 -> "top";
            case 1 -> "center";
            default -> "bottom";
        };
        String id = centerId.toString();
        var conn = this.minecraft.player.connection;
        conn.sendCommand("maidmorework center radius " + id + " " + radiusText);
        conn.sendCommand("maidmorework center anchor " + id + " " + anchorText);
        String nameText = nameInput.getValue();
        if (!nameText.isEmpty())
        {
            conn.sendCommand("maidmorework center name " + id + " " + nameText);
        }
        ProjectCenterBoundaryRenderer.clearPreview();
        this.onClose();
    }

    @Override
    public void onClose()
    {
        ProjectCenterBoundaryRenderer.clearPreview();
        super.onClose();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt)
    {
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor g, int x, int y, int w, int h)
    {
    }

    @Override
    public void extractTransparentBackground(GuiGraphicsExtractor g)
    {
    }

    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor g)
    {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt)
    {
        super.extractRenderState(g, mx, my, pt);

        int cx = this.width / 2;
        g.centeredText(this.font, Component.literal("名字："), cx - 115, 10, 0xFFFFFF);
        g.centeredText(this.font, Component.literal("显示半径："), cx - 36, 35, 0xFFFFFF);
        g.centeredText(this.font, Component.literal("基准点位置："), cx, 60, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
