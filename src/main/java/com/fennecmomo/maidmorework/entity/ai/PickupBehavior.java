package com.fennecmomo.maidmorework.entity.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.fennecmomo.maidmorework.api.IMaidmoreHost;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

// 居民拾取行为（Behavior<EntityMaid>）
//
// 女仆在 3 格范围内扫描掉落物 → 走过去 → 捡起 → 放入背包 → 链式继续直到清空
//
// 状态机：
//   IDLE → 扫描附近掉落物，锁定最近的，导航过去
//   MOVING → 持续追踪目标，够近时捡起来（挥动手臂动画）
//   ANIMATING → 执行入库逻辑（先堆叠同种物品，再找空格）
//
// 与其他行为的协调：
//   饥饿时 (hunger < 20) 且背包有食物 → canStillUse 返回 false，让 EatTask 优先执行
//   超时时间 60 tick，防止无限卡住
public class PickupBehavior extends Behavior<EntityMaid>
{

    private static final Logger LOG = LoggerFactory.getLogger("maidmorework:pickup");

    // 拾取状态机：空闲 → 移动中 → 拾取动画
    private enum State { IDLE, MOVING, ANIMATING }

    // 当前锁定的掉落物目标（状态机切换时保持引用）
    private ItemEntity target;
    // 当前状态机阶段（IDLE → MOVING → ANIMATING → IDLE）
    private State state = State.IDLE;

    // 无内存需求，60 tick 超时
    public PickupBehavior()
    {
        super(Map.of(), 60);
    }

    // ===================== 条件 =====================

    // 启动条件：3 格内有掉落物（快速扫描，不锁定具体目标）
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        return scanTarget(maid) != null;
    }

    // 以女仆为中心 3 格扫描存活掉落物，返回最近的一个
    // 用于 checkExtraStartConditions 和 tick 中的 IDLE 状态
    private ItemEntity scanTarget(EntityMaid maid)
    {
        AABB area = maid.getBoundingBox().inflate(3.0);
        List<ItemEntity> items = maid.level().getEntities(EntityTypeTest.forClass(ItemEntity.class), area, e -> e.isAlive());
        return items.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(maid)))
                .orElse(null);
    }

    // ===================== 生命周期 =====================

    // 检查背包中是否有食物（饥饿时让出拾取机会给吃东西行为）
    // 在 canStillUse 中使用，避免女仆饿着还一直捡东西
    private boolean hasFood(EntityMaid maid)
    {
        for (int i = 0; i < 27; i++)
        {
            ItemStack s = ((IMaidmoreHost) maid).getInvItem(i);
            if (!s.isEmpty() && s.has(DataComponents.FOOD)) return true;
        }
        return false;
    }

    // 行为启动：重置状态
    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime)
    {
        state = State.IDLE;
        target = null;
    }

    // 状态机驱动：IDLE→扫描锁定+导航 / MOVING→持续追踪+捡 / ANIMATING→入库
    // 每个 tick 根据当前状态执行对应逻辑
    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime)
    {
        switch (state)
        {
            case IDLE ->
            {
                // 初始：扫描并走过去
                ItemEntity found = scanTarget(maid);
                if (found == null)
                {
                    return;
                }
                target = found;
                maid.getNavigation().moveTo(target, 1.2);
                state = State.MOVING;
            }

            case MOVING ->
            {
                // 目标消失
                if (target == null || !target.isAlive())
                {
                    state = State.IDLE;
                    return;
                }
                // 每帧持续追（导航可能提前停）
                maid.getNavigation().moveTo(target, 1.2);
                // 够近了 → 捡
                if (maid.distanceToSqr(target) <= 1.0)
                {
                    maid.swing(InteractionHand.MAIN_HAND);
                    state = State.ANIMATING;
                }
            }

            case ANIMATING ->
            {
                // 捡一个就走，交给 Brain 调度下一个
                if (target != null && target.isAlive())
                {
                    doPickup(level, maid, target);
                }
                target = null;
            }
        }
    }

    // 持续使用条件：
    //   目标丢失且不在 IDLE 状态 → 停止
    //   饥饿且背包有食物 → 停止（让 EatTask 优先执行）
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime)
    {
        if (target == null && state != State.IDLE) return false;
        if (((IMaidmoreHost) maid).getHunger() < 20 && hasFood(maid)) return false;
        return true;
    }

    // 行为停止：清空目标和导航
    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime)
    {
        target = null;
        state = State.IDLE;
        maid.getNavigation().stop();
    }

    // ===================== 捡起逻辑 =====================

    // 捡起掉落物并放入背包：先尝试堆叠到同种物品，再找空格
    // 成功时 kill 掉落物，失败时保留掉落物并记日志
    private void doPickup(ServerLevel level, EntityMaid maid, ItemEntity item)
    {
        ItemStack stack = item.getItem();
        LOG.info("[PICKUP] attempting: item={} count={}", stack.getItem(), stack.getCount());

        IMaidmoreHost host = (IMaidmoreHost) maid;

        // 先尝试堆叠到已有物品
        for (int i = 0; i < 27; i++)
        {
            ItemStack exist = host.getInvItem(i);
            if (ItemStack.isSameItemSameComponents(exist, stack) && exist.getCount() < exist.getMaxStackSize())
            {
                int add = Math.min(exist.getMaxStackSize() - exist.getCount(), stack.getCount());
                exist.grow(add);
                host.setInvItem(i, exist);
                stack.shrink(add);
                if (stack.isEmpty()) break;
            }
        }
        // 放进空格子
        if (!stack.isEmpty())
        {
            for (int i = 0; i < 27; i++)
            {
                if (host.getInvItem(i).isEmpty())
                {
                    host.setInvItem(i, stack.copy());
                    stack.setCount(0);
                    break;
                }
            }
        }

        if (stack.isEmpty())
        {
            item.kill(level);
            LOG.info("[PICKUP] success");
        }
        else
        {
            LOG.info("[PICKUP] FAILED: remaining={}", stack.getCount());
        }
    }
}
