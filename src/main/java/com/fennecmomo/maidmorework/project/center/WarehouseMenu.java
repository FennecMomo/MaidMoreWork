package com.fennecmomo.maidmorework.project.center;

import java.util.ArrayList;
import java.util.List;

import com.fennecmomo.maidmorework.project.mine.MineCenterRegistration;

import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// 矿井仓库界面（2026-09-04 拍板，Tom's Storage/AE 风格）：
//   布局 = 1 行控制位（上一页 / 页码 / 下一页）+ 5 行内容（每页 45 个聚合条目）+ 玩家背包；
//   一型一格：同型同组件聚合成一个条目，右下角显示总数（>64 时由屏幕端画角标）；
//   全部条目分页翻看，不再"只显示一部分"。请求处理：内容槽全部由本菜单接管（点击取一组、
//   右键取半组、拿着物品点击存入、Shift 从背包整组存入、Shift 从仓库取一组进背包）。
public class WarehouseMenu extends AbstractContainerMenu
{
    public static final int CONTROL_SLOTS = 9;                  // 控制行
    public static final int PAGE_ROWS = 5;                      // 内容行
    public static final int PAGE_SLOTS = PAGE_ROWS * 9;         // 每页 45
    public static final int CONTENT_START = CONTROL_SLOTS;      // 9
    public static final int CONTENT_END = CONTENT_START + PAGE_SLOTS;   // 54

    private static final int DATA_COUNTS = 0;                   // [0,45) 每格总数
    private static final int DATA_PAGE = PAGE_SLOTS;            // 当前页（0 基）
    private static final int DATA_PAGES = PAGE_SLOTS + 1;       // 总页数
    private static final int DATA_SIZE = PAGE_SLOTS + 2;

    private static final int BTN_PREV = 0;
    private static final int LABEL_PAGE = 4;
    private static final int BTN_NEXT = 8;

    private static final int MAX_PER_ACTION = 64;               // 单次点击最多取一组

    private final ProjectCenterInstance center;                 // 客户端为 null
    private final WarehouseStorage storage;                     // 客户端为 null
    private final Container display;                            // 内容槽渲染容器（服务端=聚合视图）
    private final SimpleContainer controls = new SimpleContainer(CONTROL_SLOTS);
    private final SimpleContainerData data = new SimpleContainerData(DATA_SIZE);
    private List<WarehouseStorage.Entry> entries = List.of();
    private int page = 0;
    private int pages = 1;

    // 客户端工厂（IMenuTypeExtension）
    public WarehouseMenu(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf)
    {
        this(containerId, playerInv, (ProjectCenterInstance) null);
    }

    public WarehouseMenu(int containerId, Inventory playerInv, ProjectCenterInstance center)
    {
        super(MineCenterRegistration.WAREHOUSE_MENU.get(), containerId);
        this.center = center;
        this.storage = center != null ? new WarehouseStorage(center) : null;
        this.display = center != null ? new AggregateDisplay() : new SimpleContainer(PAGE_SLOTS);

        for (int i = 0; i < CONTROL_SLOTS; i++)
        {
            this.addSlot(new LockedSlot(this.controls, i, 8 + i * 18, 18));
        }
        for (int row = 0; row < PAGE_ROWS; row++)
        {
            for (int col = 0; col < 9; col++)
            {
                this.addSlot(new Slot(this.display, row * 9 + col, 8 + col * 18, 36 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++)
        {
            for (int col = 0; col < 9; col++)
            {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 139 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++)
        {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 197));
        }
        this.addDataSlots(this.data);
        if (center != null) refreshPage();
    }

    // ===================== 页刷新 =====================

    private void refreshPage()
    {
        if (storage == null) return;
        entries = storage.aggregate();
        pages = Math.max(1, (entries.size() + PAGE_SLOTS - 1) / PAGE_SLOTS);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        int base = page * PAGE_SLOTS;
        for (int i = 0; i < PAGE_SLOTS; i++)
        {
            int idx = base + i;
            data.set(DATA_COUNTS + i, idx < entries.size() ? Math.min(entries.get(idx).total(), Integer.MAX_VALUE) : 0);
        }
        data.set(DATA_PAGE, page);
        data.set(DATA_PAGES, pages);
        updateControls();
    }

    private void updateControls()
    {
        for (int i = 0; i < CONTROL_SLOTS; i++) controls.setItem(i, ItemStack.EMPTY);
        if (page > 0)
        {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§e上一页"));
            controls.setItem(BTN_PREV, prev);
        }
        if (page + 1 < pages)
        {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§e下一页"));
            controls.setItem(BTN_NEXT, next);
        }
        ItemStack label = new ItemStack(Items.PAPER);
        label.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("§f第 §e" + (page + 1) + " §f/ §e" + pages + " §f页"));
        controls.setItem(LABEL_PAGE, label);
    }

    // ===================== 交互 =====================

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player)
    {
        if (slotIndex >= CONTENT_START && slotIndex < CONTENT_END)
        {
            if (center != null) handleContentClick(slotIndex - CONTENT_START, buttonNum, input, player);
            return;
        }
        if (slotIndex >= 0 && slotIndex < CONTROL_SLOTS)
        {
            if (center != null) handleControlClick(slotIndex, player);
            return;
        }
        super.clicked(slotIndex, buttonNum, input, player);
    }

    private void handleControlClick(int controlIndex, Player player)
    {
        if (controlIndex == BTN_PREV && page > 0) page--;
        else if (controlIndex == BTN_NEXT && page + 1 < pages) page++;
        else return;
        refreshPage();
        broadcastChanges();
    }

    private void handleContentClick(int pageIndex, int buttonNum, ContainerInput input, Player player)
    {
        WarehouseStorage.Entry entry = entryAt(pageIndex);
        if (entry == null) return;
        if (input == ContainerInput.QUICK_CRAFT) return;                 // 拖拽暂不支持（点击/Shift 已够用）
        ItemStack carried = this.getCarried();

        if (input == ContainerInput.QUICK_MOVE)
        {
            ItemStack taken = storage.take(entry.template(), MAX_PER_ACTION);
            if (!taken.isEmpty() && !player.getInventory().add(taken)) player.drop(taken, false);
            refreshPage();
            broadcastChanges();
            return;
        }
        if (input == ContainerInput.SWAP)
        {
            if (buttonNum < 0 || buttonNum >= 9) return;
            ItemStack hotbar = player.getInventory().getItem(buttonNum);
            if (!hotbar.isEmpty() && !ItemStack.isSameItemSameComponents(hotbar, entry.template())) return;
            ItemStack taken = storage.take(entry.template(), Math.min(entry.total(), MAX_PER_ACTION));
            if (taken.isEmpty()) return;
            if (hotbar.isEmpty()) player.getInventory().setItem(buttonNum, taken);
            else hotbar.grow(taken.getCount());
            refreshPage();
            broadcastChanges();
            return;
        }
        if (input != ContainerInput.PICKUP) return;

        boolean secondary = buttonNum == 1;
        if (carried.isEmpty())
        {
            // 空手：左键取一组 / 右键取半组（半组按"一组的一半"）
            int want = secondary
                    ? Math.max(1, Math.min(MAX_PER_ACTION, (Math.min(entry.total(), MAX_PER_ACTION) + 1) / 2))
                    : Math.min(entry.total(), MAX_PER_ACTION);
            ItemStack taken = storage.take(entry.template(), want);
            if (!taken.isEmpty()) this.setCarried(taken);
        }
        else
        {
            // 拿着物品：左键整组存入 / 右键存一个
            ItemStack toInsert = secondary ? carried.copyWithCount(1) : carried.copy();
            int moved = toInsert.getCount();
            storage.insert(toInsert);
            carried.shrink(moved);
            if (carried.isEmpty()) this.setCarried(ItemStack.EMPTY);
        }
        refreshPage();
        broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex)
    {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) return ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        if (slotIndex >= CONTENT_START && slotIndex < CONTENT_END)
        {
            // 仓库 → 玩家背包（一次一组）
            WarehouseStorage.Entry entry = entryAt(slotIndex - CONTENT_START);
            if (entry != null && center != null)
            {
                ItemStack taken = storage.take(entry.template(), MAX_PER_ACTION);
                if (!taken.isEmpty() && !player.getInventory().add(taken)) player.drop(taken, false);
                refreshPage();
                broadcastChanges();
            }
            return ItemStack.EMPTY;
        }
        if (slotIndex < CONTROL_SLOTS) return ItemStack.EMPTY;

        // 玩家背包 → 仓库（整组）
        if (center != null)
        {
            ItemStack stack = slot.getItem().copy();
            storage.insert(stack);
            slot.setByPlayer(ItemStack.EMPTY);
            refreshPage();
            broadcastChanges();
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player)
    {
        if (center == null) return true;
        return player.isWithinBlockInteractionRange(center.getBlockPos(), 8.0F);
    }

    // ===================== 屏幕端读取 =====================

    // 第 pageIndex 格的总数（来自数据槽同步；0 = 空）
    public int totalAt(int pageIndex)
    {
        return data.get(DATA_COUNTS + pageIndex);
    }

    public int currentPage()
    {
        return data.get(DATA_PAGE);
    }

    public int totalPages()
    {
        return data.get(DATA_PAGES);
    }

    private WarehouseStorage.Entry entryAt(int pageIndex)
    {
        int idx = page * PAGE_SLOTS + pageIndex;
        return idx >= 0 && idx < entries.size() ? entries.get(idx) : null;
    }

    // ===================== 内部类 =====================

    // 控制行槽位：只显示、不可拿放
    private static class LockedSlot extends Slot
    {
        LockedSlot(Container container, int index, int x, int y)
        {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPickup(Player player) { return false; }

        @Override
        public boolean mayPlace(ItemStack stack) { return false; }

        @Override
        public boolean isHighlightable() { return false; }
    }

    // 服务端内容槽容器：只读聚合视图（显示堆数量上限 64 便于原版渲染，真实总数走数据槽）
    private class AggregateDisplay implements Container
    {
        @Override
        public int getContainerSize() { return PAGE_SLOTS; }

        @Override
        public boolean isEmpty() { return entries.isEmpty(); }

        @Override
        public ItemStack getItem(int slot)
        {
            WarehouseStorage.Entry entry = entryAt(slot);
            if (entry == null) return ItemStack.EMPTY;
            return entry.template().copyWithCount(Math.min(entry.total(), 64));
        }

        @Override
        public ItemStack removeItem(int slot, int amount) { return ItemStack.EMPTY; }

        @Override
        public ItemStack removeItemNoUpdate(int slot) { return ItemStack.EMPTY; }

        @Override
        public void setItem(int slot, ItemStack stack) { }

        @Override
        public void setChanged() { }

        @Override
        public boolean stillValid(Player player) { return true; }

        @Override
        public void clearContent() { }
    }
}
