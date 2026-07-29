package com.fennecmomo.maidmorework.project.type;

import com.fennecmomo.maidmorework.lib.projecttype.IProjectType;
import com.fennecmomo.maidmorework.lib.projecttype.RegProjectType;
import com.fennecmomo.maidmorework.logging.LoggingTask;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@RegProjectType
public class ChoppingProjectType implements IProjectType
{
    @Override
    public String id() { return "chopping"; }

    @Override
    public Component displayName()
    {
        return Component.translatable("projecttype.maidmorework.chopping");
    }

    @Override
    public ItemStack icon() { return Items.IRON_AXE.getDefaultInstance(); }

    @Override
    public Component description()
    {
        return Component.translatable("projecttype.maidmorework.chopping.desc");
    }

    @Override
    public Identifier taskUid() { return LoggingTask.UID; }
}
