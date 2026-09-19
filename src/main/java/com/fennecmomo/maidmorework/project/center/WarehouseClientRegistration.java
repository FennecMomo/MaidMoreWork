package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.project.mine.MineCenterRegistration;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

// 客户端菜单屏幕注册（2026-09-04）：把仓库菜单类型绑定到 WarehouseScreen
@EventBusSubscriber(modid = MaidMoreWork.MODID, value = Dist.CLIENT)
public class WarehouseClientRegistration
{
    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event)
    {
        event.register(MineCenterRegistration.WAREHOUSE_MENU.get(), WarehouseScreen::new);
    }
}
