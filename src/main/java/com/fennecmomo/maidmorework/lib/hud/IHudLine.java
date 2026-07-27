package com.fennecmomo.maidmorework.lib.hud;

import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import net.minecraft.network.chat.Component;

public interface IHudLine
{
    String id();

    Component label();

    Component format(ProjectHudPayload.Entry entry);

    boolean defaultEnabled();
}
