package com.fennecmomo.maidmorework.spblock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.model.data.ModelData;

// SPBlock 的 tint source 委托器（客户端渲染辅助）
//
// 问题背景：
// SPBlock 替换了原始方块后，MC 渲染管线查的是 SPBlock 的 tint source
// 而不是原始方块的。这导致树叶等有生物群系着色的方块显示白色
//
// 解决方案：
// 这里读 ModelData 拿原始 BlockState，再委托原始方块的 tint source 着色
// 在 SPBlockClientRegistration 中注册两个实例（tintIndex 0 和 1）
//
// tintIndex：模型中的 tint 索引（大部分方块只有 0，树叶等可能用到多个）
public class SPDelegateTintSource implements BlockTintSource
{
    // 模型中的 tint 索引，对应 BlockStateModel 中的 tint 层
    private final int tintIndex;

    public SPDelegateTintSource(int tintIndex)
    {
        this.tintIndex = tintIndex;
    }

    // 获取世界内颜色：读 ModelData 的原始 BlockState，委托原始方块的 tint source
    // 返回 0xFFFFFFFF 表示不着色（白色默认）
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

    // 获取物品形态颜色（不用于世界渲染，只用于物品栏显示）
    // SPBlock 不作为物品使用，所以始终返回白色
    @Override
    public int color(BlockState state)
    {
        return 0xFFFFFFFF;
    }

    // 获取地形粒子颜色：委托给 colorInWorld
    // 当方块被破坏时（不应该发生）的粒子颜色
    @Override
    public int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level,
                                      BlockPos pos)
    {
        return colorInWorld(state, level, pos);
    }
}
