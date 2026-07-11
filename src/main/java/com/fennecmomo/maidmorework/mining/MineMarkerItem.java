package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

// 矿井标记工具
// 通过custom_data NBT存储绑定的mineInstanceId
// 所有交互逻辑在MineMarkerEventHandler中处理
public class MineMarkerItem extends Item
{
    public MineMarkerItem(Properties properties)
    {
        super(properties);
    }

    // 工具是否绑定了矿井
    public static boolean isBound(ItemStack stack)
    {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains("mineIdMost");
    }

    // 获取绑定的矿井ID
    public static UUID getBoundId(ItemStack stack)
    {
        if (!isBound(stack)) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return new UUID(tag.getLong("mineIdMost").orElse(0L),
                        tag.getLong("mineIdLeast").orElse(0L));
    }

    // 绑定矿井ID到物品
    public static void bind(ItemStack stack, UUID id)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag ->
        {
            tag.putLong("mineIdMost", id.getMostSignificantBits());
            tag.putLong("mineIdLeast", id.getLeastSignificantBits());
        });
    }

    // 解绑
    public static void unbind(ItemStack stack)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag ->
        {
            tag.remove("mineIdMost");
            tag.remove("mineIdLeast");
        });
    }
}
