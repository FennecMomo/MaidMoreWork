package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.momolib.template.C_Container;
import com.fennecmomo.momolib.template.S_Container;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

// 矿井方块储物容器 Screen
// 用 momoLib S_Container 渲染背景和标题
public class MineStorageScreen extends AbstractContainerScreen<MineStorageMenu>
{
    public MineStorageScreen(MineStorageMenu menu, Inventory inv, Component title)
    {
        super(menu, inv, title,
                C_Container.PAD + C_Container.PLAYER_COLS * C_Container.SLOT + C_Container.PAD,
                C_Container.PAD + C_Container.TITLE_H + 3 * C_Container.SLOT
                        + C_Container.GAP + C_Container.PLAYER_ROWS * C_Container.SLOT
                        + C_Container.HOTBAR_ROWS * C_Container.SLOT + C_Container.PAD);
    }

    @Override
    protected void init()
    {
        super.init();
        S_Container.initTitle(this, leftPos, topPos, this.font, this::addRenderableWidget);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick)
    {
        S_Container.renderBg(this, g, leftPos, topPos, imageWidth, imageHeight);

        // 渲染前：记录每个槽位的真实数量，临时改为 1（阻止原生显示 "64"）
        int[] realCounts = new int[27];
        for (int i = 0; i < 27; i++)
        {
            Slot slot = this.menu.getSlot(i);
            ItemStack stack = slot.getItem();
            realCounts[i] = stack.getCount();
            if (realCounts[i] > 64)
            {
                stack.setCount(1);
            }
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        // 渲染后：恢复真实数量，并画自定义数量文字
        for (int i = 0; i < 27; i++)
        {
            Slot slot = this.menu.getSlot(i);
            ItemStack stack = slot.getItem();
            stack.setCount(realCounts[i]);

            if (realCounts[i] > 64)
            {
                int sx = leftPos + slot.x;
                int sy = topPos + slot.y;
                String text = String.valueOf(realCounts[i]);
                int textWidth = this.font.width(text);
                g.text(this.font, net.minecraft.network.chat.Component.literal(text),
                        sx + 17 - textWidth, sy + 10, 0xFFFFFFFF, true);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY)
    {
        // 标题由 S_Container.initTitle 处理，跳过默认标签
    }
}
