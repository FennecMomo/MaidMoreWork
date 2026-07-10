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

// SPBlock的自定义blockstate定义
// 接管blockstate JSON加载，所有BlockState都返回SPDynamicModel
// 渲染时由SPDynamicModel.collectParts读ModelData动态切换外观
public class SPModelDefinition implements CustomBlockModelDefinition
{
    public static final MapCodec<SPModelDefinition> MAP_CODEC = MapCodec.unit(new SPModelDefinition());

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

    @Override
    public MapCodec<? extends CustomBlockModelDefinition> codec()
    {
        return MAP_CODEC;
    }

    // 所有BlockState共用的UnbakedRoot
    // bake成SPDynamicModel，渲染时动态读取ModelData切换外观
    static class SPDynamicModelRoot implements BlockStateModel.UnbakedRoot
    {
        @Override
        public BlockStateModel bake(BlockState state, ModelBaker baker)
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
        public Object visualEqualityGroup(BlockState state)
        {
            return this;
        }
    }
}
