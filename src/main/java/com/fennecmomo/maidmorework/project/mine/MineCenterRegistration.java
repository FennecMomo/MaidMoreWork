package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.item.FluidBottleItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

// 矿井中心注册表（A1 拍板后的新矿井创建流，替代旧 mining 包，见 MINE_REDESIGN §1）
// 旧 mining 包已整体删除，本表为矿井相关注册的唯一入口：
//   mine_center          — 矿井中心方块（工程中心方块的子类实现）
//   mine_center_marker   — 矿井标记工具（两角点框选竖井范围）
//   fluid_bottle         — 液体瓶（§9 源流体处理的产物容器）
// 资产层复用旧 mine_block 贴图，仅新增 JSON 指向
public class MineCenterRegistration
{
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(MaidMoreWork.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MaidMoreWork.MODID);

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MaidMoreWork.MODID);

    // 矿井中心方块（属性与工程中心方块一致：不可摧毁、无掉落、禁活塞）
    public static final DeferredHolder<Block, MineCenterBlock> MINE_CENTER_BLOCK =
            BLOCKS.registerBlock("mine_center",
                    properties -> new MineCenterBlock(properties),
                    p -> p.noOcclusion()
                            .instabreak()
                            .strength(-1.0F)
                            .noLootTable()
                            // 禁止活塞推动：中心实例与方块位置绑定，移动会导致实例失联
                            .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
            );

    public static final DeferredHolder<Item, BlockItem> MINE_CENTER_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem(MINE_CENTER_BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MineCenterBlockEntity>> MINE_CENTER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("mine_center",
                    () -> new BlockEntityType<>(MineCenterBlockEntity::new,
                            MINE_CENTER_BLOCK.get()));

    // 矿道层控制方块（2026-09-04 拍板）：表示该层矿道的控制中心
    //   非合成物品、无掉落、生存不可破坏（创造可破坏）；由女仆空手放置；随矿井中心移除而销毁
    public static final DeferredHolder<Block, MineLayerControlBlock> MINE_LAYER_CONTROL_BLOCK =
            BLOCKS.registerBlock("mine_layer_control",
                    properties -> new MineLayerControlBlock(properties),
                    p -> p.noOcclusion()
                            .instabreak()
                            .strength(-1.0F)
                            .noLootTable()
                            .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
            );

    public static final DeferredHolder<Item, BlockItem> MINE_LAYER_CONTROL_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem(MINE_LAYER_CONTROL_BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MineLayerControlBlockEntity>> MINE_LAYER_CONTROL_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("mine_layer_control",
                    () -> new BlockEntityType<>(MineLayerControlBlockEntity::new,
                            MINE_LAYER_CONTROL_BLOCK.get()));

    // 矿井标记工具（不可堆叠）
    public static final DeferredHolder<Item, MineCenterMarkerItem> MINE_CENTER_MARKER =
            ITEMS.register("mine_center_marker",
                    id -> new MineCenterMarkerItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(1)));

    // 液体瓶（最多堆叠 16，§9 源流体处理产物）
    public static final DeferredHolder<Item, FluidBottleItem> FLUID_BOTTLE =
            ITEMS.register("fluid_bottle",
                    id -> new FluidBottleItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(16)));

    // 测试用刷怪蛋（2026-09-04）：生成自带小型背包、Home 模式、挖矿工作的女仆
    public static final DeferredHolder<Item, com.fennecmomo.maidmorework.item.MiningMaidSpawnEggItem> MINING_MAID_SPAWN_EGG =
            ITEMS.register("mining_maid_spawn_egg",
                    id -> new com.fennecmomo.maidmorework.item.MiningMaidSpawnEggItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))));
}
