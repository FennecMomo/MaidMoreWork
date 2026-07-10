package com.fennecmomo.maidmorework.spblock;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

import java.util.List;

// 客户端注册：自定义模型定义 + tint source
@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public class SPBlockClientRegistration
{
    static final Identifier DEFINITION_ID =
            Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "sp_definition");
    static final Identifier MODEL_ID =
            Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "sp_model");

    @SubscribeEvent
    static void onRegisterBlockStateModels(RegisterBlockStateModels event)
    {
        event.registerDefinition(DEFINITION_ID, SPModelDefinition.MAP_CODEC);
        event.registerModel(MODEL_ID, SPUnbakedModel.MAP_CODEC);
    }

    @SubscribeEvent
    static void onRegisterBlockColors(RegisterColorHandlersEvent.BlockTintSources event)
    {
        event.register(
                List.of(new SPDelegateTintSource(0), new SPDelegateTintSource(1)),
                SPRegistration.SP_BLOCK.get()
        );
    }
}
