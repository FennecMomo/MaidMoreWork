package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

// 矿井标记工具（物品本体）
// 通过 custom_data NBT 存储绑定的 mineInstanceId（UUID 分拆为 Most/Least）
// 所有交互逻辑在 MineMarkerEventHandler 中处理，本类只提供绑定/解绑/查询方法
// 玩家流程：右键方块设角点1 → 右键方块设角点2 → 右键空气弹确认窗 → 确认创建
public class MineMarkerItem extends Item
{
    public MineMarkerItem(Properties properties)
    {
        super(properties);
    }

    // 工具是否绑定了矿井（检查 NBT 中是否有 mineIdMost 字段）
    public static boolean isBound(ItemStack stack)
    {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains("mineIdMost");
    }

    // 获取绑定的矿井 ID（未绑定时返回 null）
    public static UUID getBoundId(ItemStack stack)
    {
        if (!isBound(stack)) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return new UUID(tag.getLong("mineIdMost").orElse(0L),
                        tag.getLong("mineIdLeast").orElse(0L));
    }

    // 绑定矿井 ID 到物品（写入 NBT）
    public static void bind(ItemStack stack, UUID id)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag ->
        {
            tag.putLong("mineIdMost", id.getMostSignificantBits());
            tag.putLong("mineIdLeast", id.getLeastSignificantBits());
        });
    }

    // 解绑（从 NBT 移除 mineIdMost/mineIdLeast）
    public static void unbind(ItemStack stack)
    {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag ->
        {
            tag.remove("mineIdMost");
            tag.remove("mineIdLeast");
        });
    }
}
