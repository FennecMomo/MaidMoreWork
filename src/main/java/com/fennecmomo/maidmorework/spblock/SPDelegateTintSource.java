package com.fennecmomo.maidmorework.spblock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.model.data.ModelData;

// tint source委托器
// SPBlock替换后渲染管线查的是SPBlock的tint source，不是原始方块的
// 这里读ModelData拿原始BlockState，再委托原始方块的tint source着色
// 这样树叶等有生物群系着色的方块不会显示白色
public class SPDelegateTintSource implements BlockTintSource
{
    private final int tintIndex;

    public SPDelegateTintSource(int tintIndex)
    {
        this.tintIndex = tintIndex;
    }

    @Override
    public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos)
    {
        ModelData data = level.getModelData(pos);
        BlockState originalState = data.get(SPDynamicModel.ORIGINAL_STATE);
        if (originalState == null || originalState.isAir())
        {
            return 0xFFFFFFFF; // 白色默认值
        }
        // 委托原始方块的tint source
        var tintSources = Minecraft.getInstance().getBlockColors()
                .getTintSources(originalState);
        if (tintSources != null && tintIndex < tintSources.size())
        {
            return tintSources.get(tintIndex)
                    .colorInWorld(originalState, level, pos);
        }
        return 0xFFFFFFFF;
    }

    @Override
    public int color(BlockState state)
    {
        return 0xFFFFFFFF;
    }

    @Override
    public int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level,
                                      BlockPos pos)
    {
        return colorInWorld(state, level, pos);
    }
}
