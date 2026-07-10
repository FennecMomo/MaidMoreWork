package com.fennecmomo.maidmorework.spblock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.model.data.ModelData;

import java.util.List;

// SPBlock的动态方块模型
// 在区块渲染线程被调用，通过level+pos读取BlockEntity的ModelData
// ModelData里包含：原始方块的BlockState + 当前状态(SOLID/BLUEPRINT)
// SOLID -> 委托原始方块的BlockStateModel渲染（完美还原外观）
// BLUEPRINT -> 渲染玻璃模型
public class SPDynamicModel implements DynamicBlockStateModel
{
    // ModelData里存的原始BlockState
    public static final net.neoforged.neoforge.model.data.ModelProperty<BlockState> ORIGINAL_STATE =
            new net.neoforged.neoforge.model.data.ModelProperty<>();

    // ModelData里存的方块状态(SOLID/BLUEPRINT)
    public static final net.neoforged.neoforge.model.data.ModelProperty<SPBlockEntity.State> BLOCK_STATE =
            new net.neoforged.neoforge.model.data.ModelProperty<>();

    // 玻璃模型ID
    static final Identifier GLASS_MODEL_ID =
            Identifier.fromNamespaceAndPath("minecraft", "block/glass");

    // 玻璃模型的BlockStateModelPart，bake时初始化
    BlockStateModelPart glassModelPart;

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
        if (originalState == null || originalState.isAir()
                || originalState.getBlock() == SPRegistration.SP_BLOCK.get())
        {
            return;
        }

        BlockStateModelSet modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel originalModel = modelSet.get(originalState);
        originalModel.collectParts(level, pos, originalState, random, parts);
    }

    @Override
    public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial()
    {
        BlockState fallback = net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
        BlockStateModelSet modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        BlockStateModel model = modelSet.get(fallback);
        return model.particleMaterial();
    }

    @Override
    public int materialFlags()
    {
        return 0;
    }

    // bake时初始化玻璃模型，避免每次collectParts重复load
    // 由SPUnbakedModel.bake()或SPModelDefinition的UnbakedRoot调用
    public void initGlassModel(ModelBaker baker)
    {
        // 创建一个新的Variant指向玻璃模型，bake后得到BlockStateModelPart
        Variant glassVariant = new Variant(GLASS_MODEL_ID);
        glassModelPart = glassVariant.bake(baker);
    }
}
