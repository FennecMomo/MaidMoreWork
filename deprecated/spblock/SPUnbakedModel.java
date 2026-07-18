package com.fennecmomo.maidmorework.spblock;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;

// SPBlock 的 Unbaked 模型（模型烘焙入口）
//
// 在 MC 的模型加载管线中被调用：
//   JSON 模型加载 → SPUnbakedModel → bake() → SPDynamicModel
//
// bake() 创建 SPDynamicModel 并初始化玻璃模型
// 然后通过 SPBlockClientRegistration 注册到 MC 的模型系统
//
// MAP_CODEC: 用于从 JSON 反序列化（这里是无参构造，所以用 MapCodec.unit）
public class SPUnbakedModel implements CustomUnbakedBlockStateModel
{
    // 无参构造的 MapCodec，JSON 中无需任何字段
    public static final MapCodec<SPUnbakedModel> MAP_CODEC = MapCodec.unit(new SPUnbakedModel());

    // 烘焙模型：创建 SPDynamicModel 并预加载玻璃模型
    // 返回的 SPDynamicModel 会被 MC 渲染器用于每个 SPBlock 位置
    @Override
    public BlockStateModel bake(ModelBaker baker)
    {
        SPDynamicModel model = new SPDynamicModel();
        model.initGlassModel(baker);
        return model;
    }

    // 解析依赖：无额外依赖（玻璃模型在 initGlassModel 中直接加载）
    @Override
    public void resolveDependencies(Resolver resolver)
    {
    }

    // 返回 Codec，用于 JSON 反序列化时识别此模型类型
    @Override
    public MapCodec<? extends CustomUnbakedBlockStateModel> codec()
    {
        return MAP_CODEC;
    }
}
