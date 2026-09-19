package com.fennecmomo.maidmorework.project.center;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

// 工程中心仓库聚合视图（2026-09-04，Tom's Storage/AE 风格）：
// 把仓库列表按"同型同组件"聚合成条目（条目 = 模板 + 总数），界面一型一格、右下角显示总数；
// 写入走基类 depositToWarehouse（自动合并/开新堆），取出按条目模板跨堆分摊。
public final class WarehouseStorage
{
    // 聚合条目：template 的 count 恒为 1，total 为该类型的总数
    public record Entry(ItemStack template, int total) { }

    private final ProjectCenterInstance center;

    public WarehouseStorage(ProjectCenterInstance center)
    {
        this.center = center;
    }

    // 聚合当前仓库（空堆忽略；同型同组件求和）
    public List<Entry> aggregate()
    {
        List<ItemStack> templates = new ArrayList<>();
        List<Long> totals = new ArrayList<>();
        for (ItemStack stack : center.getWarehouse())
        {
            if (stack.isEmpty()) continue;
            int idx = -1;
            for (int i = 0; i < templates.size(); i++)
            {
                if (ItemStack.isSameItemSameComponents(templates.get(i), stack))
                {
                    idx = i;
                    break;
                }
            }
            if (idx < 0)
            {
                templates.add(stack.copyWithCount(1));
                totals.add((long) stack.getCount());
            }
            else
            {
                totals.set(idx, totals.get(idx) + stack.getCount());
            }
        }
        List<Entry> entries = new ArrayList<>(templates.size());
        for (int i = 0; i < templates.size(); i++)
        {
            long total = totals.get(i);
            entries.add(new Entry(templates.get(i), (int) Math.min(total, Integer.MAX_VALUE)));
        }
        return entries;
    }

    // 取出：从仓库里按模板分摊移除最多 amount 个，返回实际取出的堆
    public ItemStack take(ItemStack template, int amount)
    {
        int remaining = Math.min(amount, 64);
        if (remaining <= 0) return ItemStack.EMPTY;
        ItemStack result = template.copyWithCount(0);
        List<ItemStack> warehouse = center.getWarehouse();
        for (int i = warehouse.size() - 1; i >= 0 && remaining > 0; i--)
        {
            ItemStack stack = warehouse.get(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) continue;
            int moved = Math.min(remaining, stack.getCount());
            stack.shrink(moved);
            result.grow(moved);
            remaining -= moved;
            if (stack.isEmpty())
            {
                warehouse.remove(i);
            }
        }
        return result.isEmpty() ? ItemStack.EMPTY : result;
    }

    // 存入：交给基类仓库逻辑（合并同类/开新堆），仓库不限格数 → 不会放不下
    public ItemStack insert(ItemStack stack)
    {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        center.depositToWarehouse(List.of(stack.copy()));
        return ItemStack.EMPTY;
    }
}
