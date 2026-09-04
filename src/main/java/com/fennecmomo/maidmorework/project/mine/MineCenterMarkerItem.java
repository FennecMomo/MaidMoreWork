package com.fennecmomo.maidmorework.project.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

// 矿井标记工具（MINE_REDESIGN §1 创建流）
//
// 两角点直接存物品 CustomData（跨存档/重登不丢失，区别于旧版的内存 MineInstance）：
//   c1x/c1y/c1z — 角点1（左键方块设置）
//   c2x/c2y/c2z — 角点2（右键方块设置）
// 两角点齐全后右键空气弹确认窗，确认命令带六坐标参数，服务端无状态校验
public class MineCenterMarkerItem extends Item
{
    public MineCenterMarkerItem(Properties properties)
    {
        super(properties);
    }

    private static CompoundTag tag(ItemStack stack)
    {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    // ===================== 角点1 =====================

    public static boolean hasCorner1(ItemStack stack)
    {
        return tag(stack).contains("c1x");
    }

    public static BlockPos getCorner1(ItemStack stack)
    {
        CompoundTag t = tag(stack);
        return new BlockPos(t.getInt("c1x").orElse(0), t.getInt("c1y").orElse(0), t.getInt("c1z").orElse(0));
    }

    public static void setCorner1(ItemStack stack, BlockPos pos)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t ->
        {
            t.putInt("c1x", pos.getX());
            t.putInt("c1y", pos.getY());
            t.putInt("c1z", pos.getZ());
        });
    }

    // ===================== 角点2 =====================

    public static boolean hasCorner2(ItemStack stack)
    {
        return tag(stack).contains("c2x");
    }

    public static BlockPos getCorner2(ItemStack stack)
    {
        CompoundTag t = tag(stack);
        return new BlockPos(t.getInt("c2x").orElse(0), t.getInt("c2y").orElse(0), t.getInt("c2z").orElse(0));
    }

    public static void setCorner2(ItemStack stack, BlockPos pos)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t ->
        {
            t.putInt("c2x", pos.getX());
            t.putInt("c2y", pos.getY());
            t.putInt("c2z", pos.getZ());
        });
    }

    // ===================== 清理 =====================

    // 确认创建或取消后清空两角点
    public static void clearCorners(ItemStack stack)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t ->
        {
            t.remove("c1x");
            t.remove("c1y");
            t.remove("c1z");
            t.remove("c2x");
            t.remove("c2y");
            t.remove("c2z");
        });
    }
}
