package com.fennecmomo.maidmorework.project.center;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

// 矿井仓库界面渲染（2026-09-04，Tom's Storage 风格）：
// 原版 6 行箱子底图 + 顶部工具条（真按钮翻页 + 页码文本，盖掉原控制行槽位）
// + 内容格 k/M/B 总数角标（0.5x 字号自绘，悬停 tooltip 附精确总数）
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu>
{
    private static final Identifier TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int ROWS = 6;   // 内容 5 行 + 原控制行位置（现为工具条）

    // 工具条：盖住纹理第一行槽位（17..34 行），左右各留 1px 高光/阴影边
    private static final int PANEL_INSET = 2;
    private static final int TOOLBAR_TOP = 17;
    private static final int TOOLBAR_BOTTOM = 35;
    private static final int PANEL_COLOR = 0xFFC6C6C6;          // 面板底色（取自 generic_54）
    private static final int PAGE_TEXT_COLOR = 0xFF404040;      // 同标题色
    private static final int PAGE_TEXT_Y = 21;
    private static final int PAGE_BUTTON_WIDTH = 50;
    private static final int PAGE_BUTTON_HEIGHT = 16;
    private static final int PAGE_BUTTON_MARGIN = 8;
    private static final int PAGE_BUTTON_Y = 18;

    private static final float COUNT_SCALE = 0.5F;          // 角标字号（原版堆数量的一半）
    private static final int GLYPH_HEIGHT = 7;              // 原版字形可视高度（数字/字母占 9px 行盒顶部 7px）
    private static final int UNIT_K = 1_000;
    private static final int UNIT_M = 1_000_000;
    private static final int UNIT_B = 1_000_000_000;
    private static final int DECIMAL_K_MAX = 10_000;        // 小于该值才带一位小数（1.2k）
    private static final int DECIMAL_M_MAX = 10_000_000;    // 同上（1.2M）

    private Button prevButton;
    private Button nextButton;

    public WarehouseScreen(WarehouseMenu menu, Inventory playerInv, Component title)
    {
        super(menu, playerInv, title, 176, 114 + ROWS * 18);
        this.inventoryLabelY = this.imageHeight - 94;
    }

    // ===================== 工具条按钮 =====================

    @Override
    protected void init()
    {
        super.init();
        this.prevButton = this.addRenderableWidget(Button.builder(
                        Component.literal("上一页"),
                        button -> clickPageButton(WarehouseMenu.BUTTON_PREV))
                .bounds(this.leftPos + PAGE_BUTTON_MARGIN, this.topPos + PAGE_BUTTON_Y,
                        PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)
                .build());
        this.nextButton = this.addRenderableWidget(Button.builder(
                        Component.literal("下一页"),
                        button -> clickPageButton(WarehouseMenu.BUTTON_NEXT))
                .bounds(this.leftPos + this.imageWidth - PAGE_BUTTON_MARGIN - PAGE_BUTTON_WIDTH,
                        this.topPos + PAGE_BUTTON_Y, PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT)
                .build());
        updatePageButtons();
    }

    @Override
    protected void containerTick()
    {
        updatePageButtons();
    }

    // 按钮可用性跟随服务端页状态（数据槽同步，客户端不持页状态）
    private void updatePageButtons()
    {
        if (this.prevButton == null || this.nextButton == null) return;
        this.prevButton.active = this.menu.currentPage() > 0;
        this.nextButton.active = this.menu.currentPage() + 1 < this.menu.totalPages();
    }

    private void clickPageButton(int buttonId)
    {
        if (this.minecraft.gameMode != null)
        {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
        }
    }

    // ===================== 绘制 =====================

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
        // 工具条底：把原控制行槽位盖成干净面板，供按钮与页码文本落座
        graphics.fill(xo + PANEL_INSET, yo + TOOLBAR_TOP,
                xo + this.imageWidth - PANEL_INSET, yo + TOOLBAR_BOTTOM, PANEL_COLOR);
    }

    // 页码文本（extractLabels 已预平移，直接用面板局部坐标）
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym)
    {
        super.extractLabels(graphics, xm, ym);
        String pageText = (this.menu.currentPage() + 1) + " / " + this.menu.totalPages();
        graphics.centeredText(this.font, pageText, this.imageWidth / 2, PAGE_TEXT_Y, PAGE_TEXT_COLOR);
    }

    @Override
    protected void renderSlotContents(GuiGraphicsExtractor graphics, ItemStack itemStack, Slot slot, String itemCount)
    {
        if (!itemStack.isEmpty() && isContentSlot(slot))
        {
            int total = this.menu.totalAt(slot.index - WarehouseMenu.CONTENT_START);
            if (total > 0)
            {
                // 空串抑制原版堆数量，角标改为缩小字号自绘
                super.renderSlotContents(graphics, itemStack, slot, "");
                renderCount(graphics, itemStack, slot, formatTotal(total));
                return;
            }
        }
        super.renderSlotContents(graphics, itemStack, slot, itemCount);
    }

    // 悬停内容槽时附精确总数（角标为缩写）
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack itemStack)
    {
        List<Component> tooltip = new ArrayList<>(super.getTooltipFromContainerItem(itemStack));
        Slot hovered = this.hoveredSlot;
        if (!itemStack.isEmpty() && hovered != null && isContentSlot(hovered))
        {
            int total = this.menu.totalAt(hovered.index - WarehouseMenu.CONTENT_START);
            if (total > 0)
            {
                tooltip.add(Component.literal("总计 " + total).withStyle(ChatFormatting.GRAY));
            }
        }
        return tooltip;
    }

    private static boolean isContentSlot(Slot slot)
    {
        return slot.index >= WarehouseMenu.CONTENT_START && slot.index < WarehouseMenu.CONTENT_END;
    }

    // ===================== 数量角标 =====================

    // 角标：数字底边贴槽底（与 vanilla 堆数量同底，vanilla 数字底边 = y+16），右对齐，0.5x 字号，不透明白 + 阴影
    private void renderCount(GuiGraphicsExtractor graphics, ItemStack itemStack, Slot slot, String text)
    {
        Font font = IClientItemExtensions.of(itemStack)
                .getFont(itemStack, IClientItemExtensions.FontContext.ITEM_COUNT);
        if (font == null)
        {
            font = this.font;
        }
        int rightX = slot.x + 19 - 2;
        int bottomY = slot.y + 16;
        graphics.pose().pushMatrix();
        graphics.pose().translate(rightX, bottomY);
        graphics.pose().scale(COUNT_SCALE, COUNT_SCALE);
        graphics.text(font, text, -font.width(text), -GLYPH_HEIGHT, -1, true);
        graphics.pose().popMatrix();
    }

    // 总数缩写（整数运算截断不进位，最长 4 字形）：999 / 1.2k / 12k / 1.2M / 12M / 2.1B
    private static String formatTotal(int total)
    {
        if (total < UNIT_K)
        {
            return String.valueOf(total);
        }
        if (total < UNIT_M)
        {
            return total < DECIMAL_K_MAX ? decimal(total, 100) + "k" : (total / UNIT_K) + "k";
        }
        if (total < UNIT_B)
        {
            return total < DECIMAL_M_MAX ? decimal(total, 100_000) + "M" : (total / UNIT_M) + "M";
        }
        return decimal(total, 100_000_000) + "B";
    }

    // 一位小数（整数截断，如 total=1999,divisor=100 → "1.9"）
    private static String decimal(int total, int divisor)
    {
        int tenths = total / divisor;
        return (tenths / 10) + "." + (tenths % 10);
    }
}
