package com.fennecmomo.maidmorework.spblock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.model.data.ModelData;

import java.util.List;

// SPBlock 的动态方块模型（客户端渲染核心）
//
// 在区块渲染线程被调用，通过 level + pos 读取 BlockEntity 的 ModelData
// ModelData 里包含：原始方块的 BlockState + 当前状态 (SOLID/BLUEPRINT)
//
// 渲染策略：
//   SOLID → 委托原始方块的 BlockStateModel 渲染（完美还原外观）
//     注意：必须防止递归，如果原始方块也是 SPBlock 则跳过
//   BLUEPRINT → 渲染天蓝色半透明玻璃模型（glassModelPart）
//
// 数据流：
//   SPBlockEntity.getModelData() → 提供 ORIGINAL_STATE + BLOCK_STATE
//   SPDynamicModel.collectParts() → 读取 ModelData 决定渲染什么
//
// 初始化：
//   bake 时通过 initGlassModel 预加载玻璃模型，避免每次 collectParts 重复加载
public class SPDynamicModel implements DynamicBlockStateModel
{
    // ModelData 里存的原始 BlockState（由 SPBlockEntity.getModelData 提供）
    // collectParts 读取后委托原始方块的模型渲染
    public static final net.neoforged.neoforge.model.data.ModelProperty<BlockState> ORIGINAL_STATE =
            new net.neoforged.neoforge.model.data.ModelProperty<>();

    // ModelData 里存的方块状态 (SOLID/BLUEPRINT)
    // collectParts 根据此值决定渲染原始外观还是玻璃模型
    public static final net.neoforged.neoforge.model.data.ModelProperty<SPBlockEntity.State> BLOCK_STATE =
            new net.neoforged.neoforge.model.data.ModelProperty<>();

    // SPBlock 模型 ID（天蓝色半透明方块，资源包中定义）
    static final Identifier SP_BLOCK_MODEL_ID =
            Identifier.fromNamespaceAndPath("maidmorework", "block/sp_block");

    // 玻璃模型的 BlockStateModelPart，bake 时初始化
    // BLUEPRINT 状态时直接把这个 part 加到渲染列表中
    BlockStateModelPart glassModelPart;

    // 收集渲染部分：每个 SPBlock 位置被渲染时调用
    // 从 ModelData 读取原始方块状态和当前状态，决定渲染什么
    // parts 列表会由 MC 渲染器继续处理
    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state,
                             RandomSource random, List<BlockStateModelPart> parts)
    {
        ModelData data = level.getModelData(pos);
        BlockState originalState = data.get(ORIGINAL_STATE);
        SPBlockEntity.State blockState = data.get(BLOCK_STATE);

        if (blockState == SPBlockEntity.State.BLUEPRINT)
        {
            // 蓝图状态：渲染玻璃模型
            if (glassModelPart != null)
            {
                parts.add(glassModelPart);
            }
            return;
        }

        // 实体状态：委托原始方块的模型渲染，但必须防止递归
        // 如果原始方块也是 SPBlock（不应该发生），则跳过渲染防止无限循环
        if (originalState == null || originalState.isAir()
                || originalState.getBlock() == SPRegistration.SP_BLOCK.get())
        {
            return;
        }

        BlockStateModelSet modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel originalModel = modelSet.get(originalState);
        originalModel.collectParts(level, pos, originalState, random, parts);
    }

    // 粒子贴图：默认使用橡木原木的贴图
    // 当玩家破坏 SPBlock 时（不应该发生，因为不可破坏）显示的粒子
    @Override
    public Material.Baked particleMaterial()
    {
        BlockState fallback = Blocks.OAK_LOG.defaultBlockState();
        BlockStateModelSet modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel model = modelSet.get(fallback);
        return model.particleMaterial();
    }

    // 材质标志：0 表示无特殊混合模式（不透明方块）
    @Override
    public int materialFlags()
    {
        return 0;
    }

    // bake 时初始化玻璃模型，避免每次 collectParts 重复加载
    // 创建一个新的 Variant 指向玻璃模型资源，bake 后得到 BlockStateModelPart
    // 由 SPUnbakedModel.bake() 或 SPModelDefinition 的 UnbakedRoot 调用
    public void initGlassModel(ModelBaker baker)
    {
        // 创建一个新的Variant指向玻璃模型，bake后得到BlockStateModelPart
        Variant glassVariant = new Variant(SP_BLOCK_MODEL_ID);
        glassModelPart = glassVariant.bake(baker);
    }
}
