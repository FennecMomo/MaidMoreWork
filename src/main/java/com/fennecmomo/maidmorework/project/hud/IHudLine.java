package com.fennecmomo.maidmorework.project.hud;

import net.minecraft.network.chat.Component;

// 工程悬浮面板的模板行接口
// 每行 = id（唯一标识） + label（显示名） + 从工程数据提取文本的 format 方法
// 注册到 HudLineRegistry 后即可加入面板，玩家可通过配置选择显示哪些行
public interface IHudLine
{
    // 唯一标识，如 "progress" / "participants" / "type"
    String id();

    // 面板上显示的行标签，如 "进度"
    Component label();

    // 从同步数据提取显示文本
    Component format(ProjectHudPayload.Entry entry);

    // 默认是否启用
    boolean defaultEnabled();
}
