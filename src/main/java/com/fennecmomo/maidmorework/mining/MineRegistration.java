package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.item.FluidBottleItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

// 矿井相关注册中心：方块、方块实体、物品、菜单
// 所有注册项通过 DeferredRegister 延迟注册，在 NeoForge 的注册事件中统一提交
//
// 注册项列表：
//   MINE_BLOCK        — 矿井方块（不可破坏、无碰撞、无掉落）
//   MINE_BLOCK_ITEM   — 矿井方块的 BlockItem（MC 26.x 必须显式注册）
//   MINE_BLOCK_ENTITY — 矿井方块实体类型
//   MINE_MARKER       — 矿井标记工具（不可堆叠）
//   FLUID_BOTTLE      — 液体瓶（最多堆叠 16）
//   MINE_STORAGE_MENU — 矿井方块储物容器 MenuType
public class MineRegistration
{
    // 方块注册表（DeferredRegister.Blocks 提供方块专用注册 API）
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(MaidMoreWork.MODID);

    // 方块实体注册表（BlockEntityType 用于关联方块和 BlockEntity 类）
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MaidMoreWork.MODID);

    // 物品注册表（DeferredRegister.Items 提供物品专用注册 API）
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MaidMoreWork.MODID);


    // 矿井方块（不可破坏、无碰撞、无掉落）
    // strength(-1.0F): 不可破坏，等同基岩
    // noCollision: 默认无碰撞（MineBlock 动态计算）
    // noLootTable: 不掉落任何物品
    public static final DeferredHolder<Block, MineBlock> MINE_BLOCK =
            BLOCKS.registerBlock("mine_block",
                    properties -> new MineBlock(properties),
                    p -> p.noOcclusion()
                            .noCollision()
                            .instabreak()
                            .strength(-1.0F)
                            .noLootTable()
            );

    // 矿井方块的 BlockItem（MC 26.x 必须显式注册）
    public static final DeferredHolder<Item, BlockItem> MINE_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem(MINE_BLOCK);

    // 矿井方块实体类型
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MineBlockEntity>> MINE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("mine_block",
                    () -> new BlockEntityType<>(MineBlockEntity::new,
                            MINE_BLOCK.get()));

    // 矿井标记工具（不可堆叠）
    public static final DeferredHolder<Item, MineMarkerItem> MINE_MARKER =
            ITEMS.register("mine_marker",
                    id -> new MineMarkerItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(1)));

    // 液体瓶（最多堆叠 16）
    public static final DeferredHolder<Item, FluidBottleItem> FLUID_BOTTLE =
            ITEMS.register("fluid_bottle",
                    id -> new FluidBottleItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(16)));

    // 矿井方块储物容器 MenuType
    // 使用 IMenuTypeExtension.create 支持额外数据传递（RegistryFriendlyByteBuf）
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MaidMoreWork.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<MineStorageMenu>> MINE_STORAGE_MENU =
            MENUS.register("mine_storage",
                    () -> IMenuTypeExtension.create(MineStorageMenu::new));
}
