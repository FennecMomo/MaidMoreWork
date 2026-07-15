package com.fennecmomo.maidmorework.mining;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = com.fennecmomo.maidmorework.MaidMoreWork.MODID, value = Dist.CLIENT)
public class MineClientRegistration
{
    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event)
    {
        event.register(MineRegistration.MINE_STORAGE_MENU.get(), MineStorageScreen::new);
    }
}
