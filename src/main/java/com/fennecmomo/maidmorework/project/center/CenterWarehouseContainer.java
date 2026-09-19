package com.fennecmomo.maidmorework.project.center;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// 工程中心仓库容器（2026-09-04）：把实例的仓库列表包成 54 格箱子式容器，供矿井方块右键的 UI 读写。
// 仓库本身"不限格数"，界面固定显示前 54 格：打开前先压缩合并同类堆叠；第 54 格之后的条目原样保留，
// 写入时按需把列表补到对应下标。仓库由管理器每 tick 快照同步到 SavedData，故 setChanged 无需额外动作。
public class CenterWarehouseContainer implements Container
{
    public static final int SLOTS = 54;

    private final ProjectCenterInstance center;

    public CenterWarehouseContainer(ProjectCenterInstance center)
    {
        this.center = center;
    }

    private List<ItemStack> warehouse()
    {
        return center.getWarehouse();
    }

    // 打开前压缩：同型同组件堆叠合并（尽量塞进 54 格；多余的条目保留在列表尾部）
    public void compact()
    {
        List<ItemStack> warehouse = warehouse();
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : warehouse)
        {
            if (stack.isEmpty()) continue;
            ItemStack remain = stack;
            for (ItemStack target : merged)
            {
                if (!ItemStack.isSameItemSameComponents(target, remain)) continue;
                int move = Math.min(remain.getCount(), target.getMaxStackSize() - target.getCount());
                if (move <= 0) continue;
                target.grow(move);
                remain.shrink(move);
                if (remain.isEmpty()) break;
            }
            if (!remain.isEmpty()) merged.add(remain);
        }
        warehouse.clear();
        warehouse.addAll(merged);
    }

    // 当前仓库条目数（UI 打开前用于提示是否超出显示格数）
    public int entryCount()
    {
        int count = 0;
        for (ItemStack stack : warehouse())
        {
            if (!stack.isEmpty()) count++;
        }
        return count;
    }

    private void ensureIndex(int slot)
    {
        List<ItemStack> warehouse = warehouse();
        while (warehouse.size() <= slot)
        {
            warehouse.add(ItemStack.EMPTY);
        }
    }

    // ===================== Container =====================

    @Override
    public int getContainerSize()
    {
        return SLOTS;
    }

    @Override
    public boolean isEmpty()
    {
        for (ItemStack stack : warehouse())
        {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot)
    {
        List<ItemStack> warehouse = warehouse();
        return slot >= 0 && slot < warehouse.size() ? warehouse.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count)
    {
        ensureIndex(slot);
        ItemStack stack = warehouse().get(slot);
        if (stack.isEmpty() || count <= 0) return ItemStack.EMPTY;
        return stack.split(count);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot)
    {
        ensureIndex(slot);
        return warehouse().set(slot, ItemStack.EMPTY);
    }

    @Override
    public void setItem(int slot, ItemStack itemStack)
    {
        ensureIndex(slot);
        warehouse().set(slot, itemStack);
    }

    @Override
    public void setChanged()
    {
        // 仓库由 ProjectCenterManager 每 tick 快照同步到 SavedData，无需额外动作
    }

    @Override
    public void clearContent()
    {
        warehouse().clear();
    }

    @Override
    public boolean stillValid(Player player)
    {
        return player.isWithinBlockInteractionRange(center.getBlockPos(), 8.0F);
    }
}
