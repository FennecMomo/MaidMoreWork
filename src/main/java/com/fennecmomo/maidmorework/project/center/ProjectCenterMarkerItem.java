package com.fennecmomo.maidmorework.project.center;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

public class ProjectCenterMarkerItem extends Item
{
    public ProjectCenterMarkerItem(Properties properties)
    {
        super(properties);
    }

    public static boolean isBound(ItemStack stack)
    {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains("regionIdMost");
    }

    public static UUID getBoundId(ItemStack stack)
    {
        if (!isBound(stack)) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return new UUID(tag.getLong("regionIdMost").orElse(0L),
                        tag.getLong("regionIdLeast").orElse(0L));
    }

    public static void bind(ItemStack stack, UUID id)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag ->
        {
            tag.putLong("regionIdMost", id.getMostSignificantBits());
            tag.putLong("regionIdLeast", id.getLeastSignificantBits());
        });
    }

    public static void unbind(ItemStack stack)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag ->
        {
            tag.remove("regionIdMost");
            tag.remove("regionIdLeast");
        });
    }
}
