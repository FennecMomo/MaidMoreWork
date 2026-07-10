package com.fennecmomo.maidmorework.spblock;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;

// SPBlock的Unbaked模型
// bake时创建SPDynamicModel并初始化玻璃模型
public class SPUnbakedModel implements CustomUnbakedBlockStateModel
{
    public static final MapCodec<SPUnbakedModel> MAP_CODEC = MapCodec.unit(new SPUnbakedModel());

    @Override
    public BlockStateModel bake(ModelBaker baker)
    {
        SPDynamicModel model = new SPDynamicModel();
        model.initGlassModel(baker);
        return model;
    }

    @Override
    public void resolveDependencies(Resolver resolver)
    {
    }

    @Override
    public MapCodec<? extends CustomUnbakedBlockStateModel> codec()
    {
        return MAP_CODEC;
    }
}
