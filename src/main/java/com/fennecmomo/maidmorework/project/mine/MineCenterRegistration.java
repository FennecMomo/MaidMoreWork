package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

// 矿井中心注册表（A1 拍板后的新矿井创建流，替代旧 mining 包，见 MINE_REDESIGN §1）
// 旧 mining 包的 mine_block/mine_marker 注册仍被遗留代码占用，故新注册使用独立 ID：
//   mine_center          — 矿井中心方块（工程中心方块的子类实现）
//   mine_center_marker   — 矿井标记工具（两角点框选竖井范围）
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

    // 矿井标记工具（不可堆叠）
    public static final DeferredHolder<Item, MineCenterMarkerItem> MINE_CENTER_MARKER =
            ITEMS.register("mine_center_marker",
                    id -> new MineCenterMarkerItem(new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))
                            .stacksTo(1)));
}
