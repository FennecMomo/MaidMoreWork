package com.fennecmomo.maidmorework.entity.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.fennecmomo.maidmorework.api.IMaidmoreHost;
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

// 居民拾取行为：3格扫描掉落物 → 走过去 → 捡起 → 链式继续直到清空。
public class PickupBehavior extends Behavior<EntityMaid>
{

    private static final Logger LOG = LoggerFactory.getLogger("maidmorework:pickup");

    private enum State { IDLE, MOVING, ANIMATING }

    private ItemEntity target;
    private State state = State.IDLE;

    public PickupBehavior()
    {
        super(Map.of(), 60);
    }

    // ======== 条件 ========

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        return scanTarget(maid) != null;
    }

    private ItemEntity scanTarget(EntityMaid maid)
    {
        AABB area = maid.getBoundingBox().inflate(3.0);
        List<ItemEntity> items = maid.level().getEntities(EntityTypeTest.forClass(ItemEntity.class), area, e -> e.isAlive());
        return items.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(maid)))
                .orElse(null);
    }

    // ======== 生命周期 ========

    private boolean hasFood(EntityMaid maid)
    {
        for (int i = 0; i < 27; i++)
        {
            ItemStack s = ((IMaidmoreHost) maid).getInvItem(i);
            if (!s.isEmpty() && s.has(net.minecraft.core.component.DataComponents.FOOD)) return true;
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime)
    {
        state = State.IDLE;
        target = null;
    }

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

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime)
    {
        if (target == null && state != State.IDLE) return false;
        if (((IMaidmoreHost) maid).getHunger() < 20 && hasFood(maid)) return false;
        return true;
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime)
    {
        target = null;
        state = State.IDLE;
        maid.getNavigation().stop();
    }

    // ======== 捡起逻辑 ========

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
        } else
        {
            LOG.info("[PICKUP] FAILED: remaining={}", stack.getCount());
        }
    }
}
