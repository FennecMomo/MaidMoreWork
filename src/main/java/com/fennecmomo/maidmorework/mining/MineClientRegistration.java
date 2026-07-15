package com.fennecmomo.maidmorework.mining;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

// 矿井客户端注册：将储物容器 Screen 绑定到 MenuType
// 只在客户端加载（Dist.CLIENT），服务端不会执行
// 当玩家打开矿井储物容器时，MC 根据这里的绑定关系创建对应的 Screen
@EventBusSubscriber(modid = com.fennecmomo.maidmorework.MaidMoreWork.MODID, value = Dist.CLIENT)
public class MineClientRegistration
{
    // 注册矿井储物容器 Screen
    // 当服务端发送 MINE_STORAGE_MENU 的打开包时，客户端用 MineStorageScreen 渲染
    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event)
    {
        event.register(MineRegistration.MINE_STORAGE_MENU.get(), MineStorageScreen::new);
    }
}
