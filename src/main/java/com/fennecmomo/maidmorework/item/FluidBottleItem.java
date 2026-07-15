package com.fennecmomo.maidmorework.item;

import com.fennecmomo.maidmorework.mining.MineRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

// 液体瓶物品（动态名字）
//
// 用于女仆工作中的液体存储（如矿井中的水/岩浆处理）
// 通过 DataComponent (FLUID_TYPE) 存储装的是哪种原版液体
//
// 动态名字：
//   空瓶 → "空瓶"
//   装水 → "水瓶"
//   装岩浆 → "岩浆瓶"
//   名字根据 DataComponent 中的液体类型动态生成
//
// TODO: 后续做成能直接塞到容器（如流体储罐）里交互，右键取水/放水暂不实现
public class FluidBottleItem extends Item
{
    public FluidBottleItem(Properties properties)
    {
        super(properties);
    }

    // 动态名字：根据 DataComponent 中的液体类型生成名字
    // 无液体或未知液体时显示 "空瓶"
    // 有液体时显示 "液体名 + 瓶"（如 "水瓶"、"岩浆瓶"）
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

    // 设置液体类型：写入 DataComponent
    // MiningBehavior 中女仆装液体时调用
    public static void setFluid(ItemStack stack, ResourceKey<Fluid> key)
    {
        stack.set(MineRegistration.FLUID_TYPE.get(), key);
    }

    // 获取液体类型：从 DataComponent 读取
    // 返回 null 表示空瓶
    public static ResourceKey<Fluid> getFluid(ItemStack stack)
    {
        return stack.get(MineRegistration.FLUID_TYPE.get());
    }
}
