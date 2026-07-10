package com.fennecmomo.maidmorework.entity.ai;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.List;

// 居民默认空闲任务。不做任何事，靠 WORK activity 的闲逛行为自然溜达。
public class IdleTask implements IMaidTask
{

    public static final IdleTask INSTANCE = new IdleTask();
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "idle");

    @Override
    public Identifier getUid()
    {
        return UID;
    }

    @Override
    public ItemStack getIcon()
    {
        return Items.AIR.getDefaultInstance();
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid)
    {
        return SoundEvents.AMBIENT_CAVE.value();
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        return Collections.emptyList();
    }
}
