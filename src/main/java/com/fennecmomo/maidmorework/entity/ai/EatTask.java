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

// 居民吃东西任务（IMaidTask 实现）
//
// 女仆饥饿时自动从背包里拿食物吃，填充饥饿值
// 饥饿度范围：0~100，低于 20 时触发吃东西行为，吃到 80 时停止
//
// 行为流程：
//   1. checkExtraStartConditions: 饥饿度 < 20 且背包有食物 → 启动
//   2. tick: 找食物 → 拿出一个 → 开始吃 → 吃完加饥饿度 → 循环
//   3. 被打断时未吃完的食物退回背包
//   4. 吃饱 (hunger >= 80) 或没食物时停止
//
// 注入优先级：0（最高），确保吃东西优先于其他工作行为
public class EatTask implements IMaidTask
{

    // 任务唯一标识，用于注册和查找
    public static final Identifier UID = Identifier.fromNamespaceAndPath("maidmorework", "eat");

    // 返回任务 UID
    @Override
    public Identifier getUid()
    {
        return UID;
    }

    // 任务图标，显示在任务列表 UI 中
    @Override
    public ItemStack getIcon()
    {
        return Items.BREAD.getDefaultInstance();
    }

    // 执行任务时的环境音效
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid)
    {
        return SoundEvents.PLAYER_BURP;
    }

    // 将吃东西行为注入女仆 Brain，优先级 0（最高）
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid)
    {
        return List.of(Pair.of(0, (BehaviorControl) new EatFoodBehavior()));
    }

    // ===================== 吃东西行为 =====================

    // 吃东西行为（Behavior<EntityMaid>）
    // 状态机：找食物 → 拿出一个 → 开始吃 → 吃完加饥饿度 → 找下一个
    // 被打断时未吃完的食物退回背包（先堆叠同种物品，再找空格）
    public static class EatFoodBehavior extends Behavior<EntityMaid>
    {

        private static final Logger LOG = LoggerFactory.getLogger("maidmorework:eat");

        private boolean eating;        // 正在嚼（startUsingItem 后为 true）
        private float pendingGain;     // 提前算好的营养值（食物被 MC 正常消耗后加到饥饿度）
        private boolean shouldStop;    // 吃饱或没食物时置 true，让 canStillUse 返回 false

        // 无内存需求，永不超时
        public EatFoodBehavior()
        {
            super(Map.of(), Integer.MAX_VALUE);
        }

        // 启动条件：饥饿值低于 20 且背包有食物
        // 低于 20 表示女仆比较饿，需要吃东西
        @Override
        protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
        {
            if (((IMaidmoreHost) maid).getHunger() >= 20) return false; // 不饿
            return hasFood(maid);
        }

        // 遍历背包 27 格，检查是否有可食用的食物
        // 通过 DataComponents.FOOD 判断是否为食物
        private boolean hasFood(EntityMaid maid)
        {
            for (int i = 0; i < 27; i++)
            {
                ItemStack s = ((IMaidmoreHost) maid).getInvItem(i);
                if (!s.isEmpty() && s.has(DataComponents.FOOD)) return true;
            }
            return false;
        }

        // 持续使用条件：未被标记停止
        @Override
        protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime)
        {
            return !shouldStop;
        }

        // 行为启动：重置状态，停止移动
        // 吃东西时女仆应该站在原地不动
        @Override
        protected void start(ServerLevel level, EntityMaid maid, long gameTime)
        {
            shouldStop = false;
            eating = false;
            maid.getNavigation().stop();
            LOG.info("[EAT] start() id={} hunger={}", maid.getId(), ((IMaidmoreHost) maid).getHunger());
        }

        // 每 tick 执行：检查饱食 → 嚼东西 → 找食物
        // 三个阶段循环：
        //   1. 检查饱食度是否 >= 80，是则停止
        //   2. 正在嚼：等待 MC 消耗食物，完成后加饥饿度
        //   3. 找食物：从背包拿出一个食物开始吃
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
                }
                else
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

        // 行为停止：如果还在吃东西则中断并归还食物
        // 可能在吃东西过程中被其他行为打断（如被攻击）
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

        // 把未吃完的食物放回背包，优先堆叠到同种物品，再找空格
        // 被打断时调用，确保食物不会丢失
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
