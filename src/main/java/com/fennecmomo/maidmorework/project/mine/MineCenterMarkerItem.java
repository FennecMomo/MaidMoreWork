package com.fennecmomo.maidmorework.project.mine;

import net.minecraft.world.item.Item;

// 矿井标记工具（MINE_REDESIGN §1 创建流）
//
// 两角点为临时会话缓存（2026-09-04 拍板：玩家重登必然重新框选，不做跨进程保留），
// 由 MineCenterMarkerEventHandler 按玩家 UUID 记录，本类仅作为物品标识
public class MineCenterMarkerItem extends Item
{
    public MineCenterMarkerItem(Properties properties)
    {
        super(properties);
    }
}
