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

// 居民默认空闲任务（IMaidTask 实现）
//
// 当女仆没有分配具体工作任务（伐木/挖矿）时使用此任务
// 不做任何事，靠 WORK activity 的闲逛行为自然溜达
//
// createBrainTasks 返回空列表，表示不注入任何行为到 Brain
// 女仆会保持默认行为（闲逛、跟随、吃食物等）
//
// INSTANCE: 单例，由 MaidMoreWork 注册到 TLM TaskManager
public class IdleTask implements IMaidTask
{
    // 单例实例
    public static final IdleTask INSTANCE = new IdleTask();
    // 任务唯一标识，用于注册和查找
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "idle");

    // 返回任务 UID
    @Override
    public Identifier getUid()
    {
        return UID;
    }

    // 任务图标：空气（空闲任务不显示图标）
    @Override
    public ItemStack getIcon()
    {
        return Items.AIR.getDefaultInstance();
    }

    // 环境音效：洞穴环境音（空闲时播放）
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid)
    {
        return SoundEvents.AMBIENT_CAVE.value();
    }

    // 返回空列表：空闲任务不注入任何行为到 Brain
    // 女仆保持默认行为（闲逛、跟随等）
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        return Collections.emptyList();
    }
}
