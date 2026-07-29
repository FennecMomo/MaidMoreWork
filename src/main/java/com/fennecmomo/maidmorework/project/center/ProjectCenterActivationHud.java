package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.lib.projecttype.IProjectType;
import com.fennecmomo.maidmorework.lib.projecttype.ProjectTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

public class ProjectCenterActivationHud extends Screen
{
    private final UUID centerId;
    private List<IProjectType> types;
    private int selectedIndex = 0;

    public static void open(ProjectCenterEditPayload payload)
    {
        Minecraft.getInstance().setScreen(new ProjectCenterActivationHud(payload.centerId()));
    }

    public ProjectCenterActivationHud(UUID centerId)
    {
        super(Component.empty());
        this.centerId = centerId;
    }

    @Override
    protected void init()
    {
        types = ProjectTypeRegistry.getAll();
        if (types.isEmpty())
        {
            return;
        }

        int cx = width / 2;

        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycle(-1))
                .pos(cx - 52, 28).size(20, 20).build());

        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycle(1))
                .pos(cx + 32, 28).size(20, 20).build());

        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .pos(cx - 55, 130).size(50, 20).build());

        addRenderableWidget(Button.builder(Component.literal("确认"), b -> onConfirm())
                .pos(cx + 5, 130).size(50, 20).build());
    }

    private void cycle(int delta)
    {
        if (types.isEmpty()) return;
        selectedIndex = (selectedIndex + delta + types.size()) % types.size();
    }

    private void onConfirm()
    {
        if (types.isEmpty()) return;
        IProjectType type = types.get(selectedIndex);
        var conn = minecraft.player.connection;
        conn.sendCommand("maidmorework center type " + centerId + " " + type.id());
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt)
    {
        super.extractRenderState(g, mx, my, pt);
        if (types.isEmpty())
        {
            int cx = width / 2;
            g.centeredText(font, Component.literal("暂无可用工程类型"), cx, 50, 0xFF5555);
            return;
        }

        IProjectType type = types.get(selectedIndex);
        int cx = width / 2;

        g.centeredText(font, type.displayName(), cx, 35, 0xFFFFFF);
        g.centeredText(font, type.description(), cx, 55, 0xAAAAAA);
    }

    @Override
    public void onClose()
    {
        super.onClose();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {}
    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor g, int x, int y, int w, int h) {}
    @Override
    public void extractTransparentBackground(GuiGraphicsExtractor g) {}
    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor g) {}
    @Override
    public boolean isPauseScreen() { return false; }
}
