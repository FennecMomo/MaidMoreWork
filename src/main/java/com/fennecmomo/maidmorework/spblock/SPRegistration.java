package com.fennecmomo.maidmorework.spblock;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.UnaryOperator;

// SPBlock方案的注册中心
// 负责注册方块本体和方块实体类型
public class SPRegistration
{
    // 方块注册表
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(MaidMoreWork.MODID);

    // 方块实体注册表
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MaidMoreWork.MODID);

    // 物品注册表（用于 BlockItem）
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MaidMoreWork.MODID);

    // SPBlock方块本体
    // 不可破坏（strength(-1)等同于基岩），无掉落表，不阻挡光线
    public static final DeferredHolder<Block, SPBlock> SP_BLOCK =
            BLOCKS.registerBlock("sp_block",
                    properties -> new SPBlock(properties),
                    p -> p.strength(-1.0F)       // 不可破坏，等同基岩
                            .noLootTable()          // 无掉落表
                            .noOcclusion()          // 不阻挡光线（光照遮挡按方块状态缓存，无法按位置动态变化）
                            .dynamicShape()         // 碰撞箱动态计算（根据BlockEntity里的原始方块决定）
                            .pushReaction(PushReaction.BLOCK) // 被活塞推时方块不移动
            );

    // SPBlock 的 BlockItem（MC 26.x 必须显式注册）
    public static final DeferredHolder<Item, BlockItem> SP_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem(SP_BLOCK);

    // SPBlock对应的BlockEntity类型
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SPBlockEntity>> SP_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("sp_block",
                    () -> new BlockEntityType<>(SPBlockEntity::new,
                            SP_BLOCK.get()));
}
