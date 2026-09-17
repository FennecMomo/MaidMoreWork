package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.project.center.MineInstance;
import com.fennecmomo.maidmorework.project.center.ProjectCenterManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

// 矿井成员死亡处理（活跃任务熔断，2026-09-04 拍板）：
// 女仆死亡后行为不再 tick，绑定任务若只靠行为侧 stop() 释放会永久残留，
// 阻塞 requestWork 的"层结算"（layerSubtasks 非空 → 其余女仆全部空转）；
// 这里在死亡事件中立即归还任务并让女仆脱离中心（周期采样另有失联兜底）
@EventBusSubscriber(modid = com.fennecmomo.maidmorework.MaidMoreWork.MODID)
public class MineMemberEventHandler
{
    @SubscribeEvent
    public static void onMaidDeath(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof EntityMaid maid)) return;
        if (!(ProjectCenterManager.getCenterOf(maid) instanceof MineInstance mine)) return;
        mine.handleMemberLost(maid);
        mine.leaveCenter(maid);
    }
}
