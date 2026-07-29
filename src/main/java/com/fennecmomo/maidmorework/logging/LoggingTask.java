package com.fennecmomo.maidmorework.logging;

import java.util.ArrayList;
import java.util.List;

import com.fennecmomo.maidmorework.ModMemories;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class LoggingTask implements IMaidTask
{
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "logging");

    @Override
    public Identifier getUid() { return UID; }

    @Override
    public ItemStack getIcon() { return Items.IRON_AXE.getDefaultInstance(); }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) { return SoundEvents.WOOD_BREAK; }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) { return false; }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = new ArrayList<>();
        tasks.add(Pair.of(6, new ChopBehavior()));
        return tasks;
    }
}
