package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.item.FluidBottleItem;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class MineRegistration
{
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(MaidMoreWork.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MaidMoreWork.MODID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MaidMoreWork.MODID);

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MaidMoreWork.MODID);

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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MineBlockEntity>> MINE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("mine_block",
                    () -> new BlockEntityType<>(MineBlockEntity::new,
                            MINE_BLOCK.get()));

    public static final DeferredHolder<Item, MineMarkerItem> MINE_MARKER =
            ITEMS.register("mine_marker",
                    id -> new MineMarkerItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(1)));

    public static final DeferredHolder<Item, FluidBottleItem> FLUID_BOTTLE =
            ITEMS.register("fluid_bottle",
                    id -> new FluidBottleItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(16)));

    // DataComponent：液体瓶装的是哪种原版液体
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceKey<Fluid>>> FLUID_TYPE =
            DATA_COMPONENTS.register("fluid_type",
                    () -> DataComponentType.<ResourceKey<Fluid>>builder()
                            .persistent(ResourceKey.codec(Registries.FLUID))
                            .networkSynchronized(ResourceKey.streamCodec(Registries.FLUID))
                            .build());

    // 矿井方块储物容器 MenuType
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MaidMoreWork.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<MineStorageMenu>> MINE_STORAGE_MENU =
            MENUS.register("mine_storage",
                    () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(MineStorageMenu::new));
}
