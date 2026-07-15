package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.momolib.template.C_Container;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

// 矿井方块储物容器 Menu
// 3排×9列=27格容器 + 玩家背包，用 C_Container 布局
public class MineStorageMenu extends AbstractContainerMenu
{
    private final MineBlockEntity blockEntity;

    // 服务端构造：直接传 BlockEntity
    public MineStorageMenu(int containerId, Inventory inv, MineBlockEntity be)
    {
        super(MineRegistration.MINE_STORAGE_MENU.get(), containerId);
        this.blockEntity = be;
        // 容器格子：3排9列，用 C_Container 布局
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++)
                addSlot(new MineSlot(be, c + r * 9,
                        C_Container.PAD + c * C_Container.SLOT,
                        C_Container.containerYForRows(3) + r * C_Container.SLOT));
        // 玩家背包
        addPlayerSlots(inv);
    }

    // 客户端构造：布局硬编码，不需要 BlockEntity
    public MineStorageMenu(int containerId, Inventory inv, net.minecraft.network.RegistryFriendlyByteBuf buf)
    {
        super(MineRegistration.MINE_STORAGE_MENU.get(), containerId);
        this.blockEntity = null;
        // 容器格子：3排9列
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++)
                addSlot(new MineSlot(new net.minecraft.world.SimpleContainer(27), c + r * 9,
                        C_Container.PAD + c * C_Container.SLOT,
                        C_Container.containerYForRows(3) + r * C_Container.SLOT));
        // 玩家背包
        addPlayerSlots(inv);
    }

    private void addPlayerSlots(Inventory inv)
    {
        int playerY = C_Container.playerYForRows(3);
        // 主物品栏 3排9列
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++)
                addSlot(new Slot(inv, c + r * 9 + 9,
                        C_Container.PAD + c * C_Container.SLOT,
                        playerY + r * C_Container.SLOT));
        // 热键栏 1排9列
        int hotbarY = C_Container.hotbarYForRows(3);
        for (int c = 0; c < 9; c++)
            addSlot(new Slot(inv, c,
                    C_Container.PAD + c * C_Container.SLOT, hotbarY));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index)
    {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();
        // 0-26 是容器格，27-53 是玩家主物品栏，54-62 是热键栏
        if (index < 27)
        {
            // 容器→玩家
            if (!moveItemStackTo(original, 27, 63, true)) return ItemStack.EMPTY;
        }
        else
        {
            // 玩家→容器
            if (!moveItemStackTo(original, 0, 27, false)) return ItemStack.EMPTY;
        }
        if (original.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player)
    {
        return blockEntity == null || blockEntity.stillValid(player);
    }

    // 自定义 Slot：允许 640 堆叠
    private static class MineSlot extends Slot
    {
        public MineSlot(Container c, int idx, int x, int y) { super(c, idx, x, y); }
        @Override public int getMaxStackSize() { return MineBlockEntity.MAX_STACK; }
    }
}
