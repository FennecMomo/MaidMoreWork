package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.momolib.template.UI.GenericContainerScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

// 矿井方块储物容器 Screen（仅客户端）
// 继承 GenericContainerScreen 自动处理背景和标题渲染
// 自定义渲染逻辑：超过 64 堆叠的物品不显示原生数字
// 而是渲染前临时改为 1（阻止原生显示 "64"），渲染后画自定义数量文字
public class MineStorageScreen extends GenericContainerScreen<MineStorageMenu>
{
    public MineStorageScreen(MineStorageMenu menu, Inventory inv, Component title)
    {
        super(menu, inv, title);
    }

    // 提取渲染状态（MC 26.x 新渲染管线）
    // 渲染前记录每个槽位的真实数量，临时改为 1（阻止原生显示 "64"）
    // 渲染后恢复真实数量，并画自定义数量文字（右下角显示实际堆叠数）
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick)
    {
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
                int sx = this.leftPos + slot.x;
                int sy = this.topPos + slot.y;
                String text = String.valueOf(realCounts[i]);
                int textWidth = this.font.width(text);
                g.text(this.font, Component.literal(text),
                        sx + 17 - textWidth, sy + 10, 0xFFFFFFFF, true);
            }
        }
    }
}
