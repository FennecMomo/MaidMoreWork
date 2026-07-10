package com.fennecmomo.maidmorework.entity.ai;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.fennecmomo.maidmorework.api.IMaidmoreHost;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

// 居民吃东西任务。从背包里拿食物吃，填充饥饿值。
// 如果没有食物 → 暂且什么都不做（后续配合居所/餐厅逻辑）。
public class EatTask implements IMaidTask
{

    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "eat");

    @Override
    public Identifier getUid()
    {
        return UID;
    }

    @Override
    public ItemStack getIcon()
    {
        return Items.BREAD.getDefaultInstance();
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid)
    {
        return SoundEvents.PLAYER_BURP;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        return List.of(Pair.of(0, (BehaviorControl) new EatFoodBehavior()));
    }

    // ======== 吃东西行为 ========

    public static class EatFoodBehavior extends Behavior<EntityMaid>
    {

        private static final Logger LOG = LoggerFactory.getLogger("maidmorework:eat");

        private boolean eating;        // 正在嚼
        private float pendingGain;     // 提前算好的营养值
        private boolean shouldStop;

        public EatFoodBehavior()
        {
            super(Map.of(), Integer.MAX_VALUE);
        }

        @Override
        protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
        {
            if (((IMaidmoreHost) maid).getHunger() >= 20) return false; // 不饿
            return hasFood(maid);
        }

        private boolean hasFood(EntityMaid maid)
        {
            for (int i = 0; i < 27; i++)
            {
                ItemStack s = ((IMaidmoreHost) maid).getInvItem(i);
                if (!s.isEmpty() && s.has(DataComponents.FOOD)) return true;
            }
            return false;
        }

        @Override
        protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime)
        {
            return !shouldStop;
        }

        @Override
        protected void start(ServerLevel level, EntityMaid maid, long gameTime)
        {
            shouldStop = false;
            eating = false;
            maid.getNavigation().stop();
            LOG.info("[EAT] start() id={} hunger={}", maid.getId(), ((IMaidmoreHost) maid).getHunger());
        }

        @Override
        protected void tick(ServerLevel level, EntityMaid maid, long gameTime)
        {

            // 吃饱了
            if (((IMaidmoreHost) maid).getHunger() >= 80)
            {
                shouldStop = true;
                return;
            }

            if (eating)
            {
                // 嚼东西时不移动
                maid.getNavigation().stop();
                // 还在嚼
                if (maid.isUsingItem()) return;

                // 嚼完了：检查食物是被 MC 正常消耗还是被打断
                ItemStack hand = maid.getItemInHand(InteractionHand.MAIN_HAND);
                if (hand.isEmpty() || !hand.has(DataComponents.FOOD))
                {
                    // MC 正常消耗了
                    ((IMaidmoreHost) maid).modifyHunger((int) pendingGain);
                    LOG.info("[EAT] ate id={} gain={} hunger_now={}", maid.getId(), pendingGain, ((IMaidmoreHost) maid).getHunger());
                } else
                {
                    // 被打断，退回背包
                    LOG.info("[EAT] interrupted! id={} hand={}", maid.getId(), hand.getItem());
                    returnToInventory(maid, hand);
                    maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                }
                eating = false;
                return;
            }

            // 找食物吃
            IMaidmoreHost host = (IMaidmoreHost) maid;
            for (int i = 0; i < 27; i++)
            {
                ItemStack stack = host.getInvItem(i);
                if (!stack.isEmpty() && stack.has(DataComponents.FOOD))
                {
                    ItemStack oneFood = stack.split(1);
                    host.setInvItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);

                    var food = oneFood.get(DataComponents.FOOD);
                    pendingGain = food != null ? food.nutrition() * 5f : 10f;

                    maid.setItemInHand(InteractionHand.MAIN_HAND, oneFood);
                    maid.startUsingItem(InteractionHand.MAIN_HAND);
                    eating = true;
                    return;
                }
            }

            // 没食物了
            shouldStop = true;
        }

        @Override
        protected void stop(ServerLevel level, EntityMaid maid, long gameTime)
        {
            if (maid.isUsingItem())
            {
                maid.stopUsingItem();
                ItemStack hand = maid.getItemInHand(InteractionHand.MAIN_HAND);
                if (!hand.isEmpty())
                {
                    returnToInventory(maid, hand);
                    maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                }
            }
            eating = false;
            shouldStop = false;
        }

        private void returnToInventory(EntityMaid maid, ItemStack stack)
        {
            IMaidmoreHost host = (IMaidmoreHost) maid;
            // 先尝试堆叠到已有同种物品
            for (int i = 0; i < 27; i++)
            {
                ItemStack exist = host.getInvItem(i);
                if (ItemStack.isSameItemSameComponents(exist, stack)
                        && exist.getCount() < exist.getMaxStackSize())
                {
                    int add = Math.min(exist.getMaxStackSize() - exist.getCount(), stack.getCount());
                    exist.grow(add);
                    host.setInvItem(i, exist);
                    stack.shrink(add);
                    if (stack.isEmpty()) return;
                }
            }
            // 堆不下再找空格
            if (!stack.isEmpty())
            {
                for (int i = 0; i < 27; i++)
                {
                    if (host.getInvItem(i).isEmpty())
                    {
                        host.setInvItem(i, stack.copy());
                        return;
                    }
                }
            }
        }
    }
}
