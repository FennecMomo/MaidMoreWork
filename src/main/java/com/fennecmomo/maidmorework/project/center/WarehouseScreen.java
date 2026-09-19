package com.fennecmomo.maidmorework.project.center;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

// 矿井仓库界面渲染（2026-09-04，Tom's Storage 风格）：
// 原版 6 行箱子底图 + 内容格右下角总数角标（>64 时用底色盖住原版堆数量再画总数）
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu>
{
    private static final Identifier TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int ROWS = 6;   // 控制行 + 5 内容行

    public WarehouseScreen(WarehouseMenu menu, Inventory playerInv, Component title)
    {
        super(menu, playerInv, title, 176, 114 + ROWS * 18);
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a)
    {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int xo = (this.width - this.imageWidth) / 2;
        int yo = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, xo, yo, 0.0F, 0.0F,
                this.imageWidth, ROWS * 18 + 17, 256, 256);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, xo, yo + ROWS * 18 + 17, 0.0F, 126.0F,
                this.imageWidth, 96, 256, 256);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
    {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        renderCounts(graphics);
    }

    // 数量角标：>64 时用底色盖住原版"64"再画总数
    private void renderCounts(GuiGraphicsExtractor graphics)
    {
        for (int i = 0; i < WarehouseMenu.PAGE_SLOTS; i++)
        {
            int total = this.menu.totalAt(i);
            if (total <= 64) continue;
            Slot slot = this.menu.slots.get(WarehouseMenu.CONTENT_START + i);
            if (!slot.hasItem()) continue;
            String text = total >= 100000 ? (total / 1000) + "k" : String.valueOf(total);
            int rightX = this.leftPos + slot.x + 17;
            int textY = this.topPos + slot.y + 9;
            int textX = rightX - this.font.width(text);
            graphics.fill(textX - 1, textY - 1, rightX + 1, textY + 9, 0x80000000);
            graphics.text(this.font, text, textX, textY, 0xFFFFFF, true);
        }
    }
}
