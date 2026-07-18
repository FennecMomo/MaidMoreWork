package com.fennecmomo.maidmorework.spblock;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

import java.util.List;

// SPBlock 客户端注册（仅客户端加载）
//
// 注册两个东西：
// 1. BlockStateModels：注册自定义模型定义 (SPModelDefinition) 和 Unbaked 模型 (SPUnbakedModel)
//    MC 加载方块模型时会查找这些注册项
// 2. BlockTintSources：注册 SPDelegateTintSource，解决 SPBlock 替换后树叶着色问题
//    注册两个实例（tintIndex 0 和 1）覆盖常见方块
//
// 注册时机：NeoForge 的 RegisterBlockStateModels 和 RegisterColorHandlersEvent
@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public class SPBlockClientRegistration
{
    // 自定义模型定义 ID，在资源包的 blockstate JSON 中引用
    static final Identifier DEFINITION_ID =
            Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "sp_definition");
    // Unbaked 模型 ID，在 blockstate JSON 中引用
    static final Identifier MODEL_ID =
            Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "sp_model");

    // 注册方块模型：自定义模型定义 + Unbaked 模型
    // 方块模型加载流程：JSON → Definition → Unbaked → bake → DynamicModel
    @SubscribeEvent
    static void onRegisterBlockStateModels(RegisterBlockStateModels event)
    {
        event.registerDefinition(DEFINITION_ID, SPModelDefinition.MAP_CODEC);
        event.registerModel(MODEL_ID, SPUnbakedModel.MAP_CODEC);
    }

    // 注册方块着色源：两个 SPDelegateTintSource 实例
    // tintIndex 0: 覆盖大部分方块的着色层
    // tintIndex 1: 覆盖树叶等双 tint 层方块的第二个着色层
    @SubscribeEvent
    static void onRegisterBlockColors(RegisterColorHandlersEvent.BlockTintSources event)
    {
        event.register(
                List.of(new SPDelegateTintSource(0), new SPDelegateTintSource(1)),
                SPRegistration.SP_BLOCK.get()
        );
    }
}
