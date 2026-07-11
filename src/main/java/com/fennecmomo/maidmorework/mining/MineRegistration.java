package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
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

    public static final DeferredHolder<Block, MineBlock> MINE_BLOCK =
            BLOCKS.registerBlock("mine_block",
                    properties -> new MineBlock(properties),
                    p -> p.noOcclusion()
                            .noCollision()
                            .instabreak()
                            .strength(-1.0F)
                            .noLootTable()
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MineBlockEntity>> MINE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("mine_block",
                    () -> new BlockEntityType<>(MineBlockEntity::new,
                            MINE_BLOCK.get()));

    public static final DeferredHolder<Item, MineMarkerItem> MINE_MARKER =
            ITEMS.register("mine_marker",
                    id -> new MineMarkerItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(1)));
}
