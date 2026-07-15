package com.fennecmomo.maidmorework.item;

import com.fennecmomo.maidmorework.mining.MineRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

// 液体瓶：动态名字，装水/岩浆/空
// TODO: 后续做成能直接塞到容器（如流体储罐）里交互，右键取水/放水暂不实现
public class FluidBottleItem extends Item
{
    public FluidBottleItem(Properties properties)
    {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack)
    {
        ResourceKey<Fluid> key = stack.get(MineRegistration.FLUID_TYPE.get());
        if (key == null) return Component.literal("空瓶");
        Fluid fluid = BuiltInRegistries.FLUID.get(key)
                .map(ref -> (Fluid) ref.value())
                .orElse(null);
        if (fluid == null) return Component.literal("空瓶");
        return Component.translatable(fluid.getFluidType().getDescriptionId()).append("瓶");
    }

    public static void setFluid(ItemStack stack, ResourceKey<Fluid> key)
    {
        stack.set(MineRegistration.FLUID_TYPE.get(), key);
    }

    public static ResourceKey<Fluid> getFluid(ItemStack stack)
    {
        return stack.get(MineRegistration.FLUID_TYPE.get());
    }
}
