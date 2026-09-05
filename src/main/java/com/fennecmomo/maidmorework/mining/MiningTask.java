package com.fennecmomo.maidmorework.mining;

import java.util.ArrayList;
import java.util.List;

import com.fennecmomo.maidmorework.project.mine.MineCenterBehavior;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// 挖矿行为包（IMaidTask 实现，TLM 工作模式）
//
// 2026-09-04 起接入新矿井框架（MINE_REDESIGN）：
//   MineCenterBehavior 自行解析就近矿井中心（Home 模式），无需 SearchBehavior 找矿
//   循环 = 领任务(MineInstance.requestWork) → 导航 → 执行 → 交活（B1 拍板）
public class MiningTask implements IMaidTask
{
    // 任务唯一标识，用于注册和查找
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "mining");

    // 返回任务 UID
    @Override
    public Identifier getUid() { return UID; }

    // 任务图标，显示在任务列表 UI 中
    @Override
    public ItemStack getIcon() { return Items.IRON_PICKAXE.getDefaultInstance(); }

    // 执行任务时的环境音效
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) { return SoundEvents.STONE_BREAK; }

    // 禁用默认闲逛和随机转头，由 MineCenterBehavior 控制移动
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) { return false; }

    // 组装行为列表：MineCenterBehavior（新矿井框架派发循环）
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = new ArrayList<>();
        tasks.add(Pair.of(6, new MineCenterBehavior()));
        return tasks;
    }
}
