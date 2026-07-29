package com.fennecmomo.maidmorework.lib.projecttype;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public interface IProjectType
{
    String id();
    Component displayName();
    ItemStack icon();
    Component description();
    Identifier taskUid();
}
