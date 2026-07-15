package com.fennecmomo.maidmorework.spblock;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.neoforged.neoforge.client.model.block.CustomBlockModelDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

// SPBlock 的自定义 blockstate 定义（模型加载管线入口）
//
// 接管 blockstate JSON 加载，所有 BlockState 都返回 SPDynamicModelRoot
// SPDynamicModelRoot.bake() 创建 SPDynamicModel，渲染时动态读取 ModelData 切换外观
//
// 注册流程：
//   SPBlockClientRegistration.onRegisterBlockStateModels() 注册 MAP_CODEC
//   → MC 加载方块时调用 instantiate() 为每个 BlockState 创建 UnbakedRoot
//   → bake() 创建 SPDynamicModel 并初始化玻璃模型
public class SPModelDefinition implements CustomBlockModelDefinition
{
    // 无参构造的 MapCodec，JSON 中无需任何字段
    public static final MapCodec<SPModelDefinition> MAP_CODEC = MapCodec.unit(new SPModelDefinition());

    // 为每个 BlockState 创建对应的 UnbakedRoot
    // 所有 BlockState 共用同一个 SPDynamicModelRoot（因为外观由 ModelData 动态决定）
    @Override
    public Map<BlockState, BlockStateModel.UnbakedRoot> instantiate(
            StateDefinition<Block, BlockState> states, Supplier<String> sourceSupplier)
    {
        Map<BlockState, BlockStateModel.UnbakedRoot> map = new HashMap<>();
        for (BlockState state : states.getPossibleStates())
        {
            map.put(state, new SPDynamicModelRoot());
        }
        return map;
    }

    // 返回 Codec，用于 JSON 反序列化时识别此模型定义类型
    @Override
    public MapCodec<? extends CustomBlockModelDefinition> codec()
    {
        return MAP_CODEC;
    }

    // 所有 BlockState 共用的 UnbakedRoot
    // bake 成 SPDynamicModel，渲染时动态读取 ModelData 切换外观
    // 无论原始方块是什么，都用同一个 SPDynamicModel 处理
    static class SPDynamicModelRoot implements BlockStateModel.UnbakedRoot
    {
        // 烘焙：创建 SPDynamicModel 并预加载玻璃模型
        @Override
        public BlockStateModel bake(BlockState state, ModelBaker baker)
        {
            SPDynamicModel model = new SPDynamicModel();
            model.initGlassModel(baker);
            return model;
        }

        // 解析依赖：无额外依赖
        @Override
        public void resolveDependencies(Resolver resolver)
        {
        }

        // 视觉等价组：所有 SPBlock 状态视觉等价（因为外观由 ModelData 动态决定）
        @Override
        public Object visualEqualityGroup(BlockState state)
        {
            return this;
        }
    }
}
