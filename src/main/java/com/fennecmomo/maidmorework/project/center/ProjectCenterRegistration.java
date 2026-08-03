package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ProjectCenterRegistration
{
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(MaidMoreWork.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MaidMoreWork.MODID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MaidMoreWork.MODID);

    public static final DeferredHolder<Block, ProjectCenterBlock> PROJECT_CENTER_BLOCK =
            BLOCKS.registerBlock("project_center",
                    properties -> new ProjectCenterBlock(properties),
                    p -> p.noOcclusion()
                            .instabreak()
                            .strength(-1.0F)
                            .noLootTable()
                            // 禁止活塞推动：中心实例与方块位置绑定，移动会导致实例失联
                            .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
            );

    public static final DeferredHolder<Item, BlockItem> PROJECT_CENTER_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem(PROJECT_CENTER_BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProjectCenterBlockEntity>> PROJECT_CENTER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("project_center",
                    () -> new BlockEntityType<>(ProjectCenterBlockEntity::new,
                            PROJECT_CENTER_BLOCK.get()));

    public static final DeferredHolder<Item, ProjectCenterMarkerItem> PROJECT_CENTER_MARKER =
            ITEMS.register("project_center_marker",
                    id -> new ProjectCenterMarkerItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(1)));
}
