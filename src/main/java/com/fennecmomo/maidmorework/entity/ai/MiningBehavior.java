package com.fennecmomo.maidmorework.entity.ai;

import com.fennecmomo.maidmorework.ModAttachments;
import com.fennecmomo.maidmorework.ModMemories;
import com.fennecmomo.maidmorework.mining.DigTask;
import com.fennecmomo.maidmorework.mining.MineBlockEntity;
import com.fennecmomo.maidmorework.mining.MineRegistration;
import com.fennecmomo.maidmorework.item.FluidBottleItem;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MiningBehavior extends Behavior<EntityMaid>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    private static final int MINE_INTERVAL = 10;
    private static final double WALK_REACH_SQ = 16.0;
    private static final double WALK_SPEED = 0.6;
    private static final int MAX_NAV_FAIL = 3;
    private static final int PREP_WAIT = 40;
    private static final int BUBBLE_COOLDOWN = 120; // 气泡冷却时间（tick）
    private static final long SCAFFOLD_KEY = 9530L;
    private static final long TORCH_KEY = 9531L;

    private int mineTimer = 0;
    private boolean reachedTarget = false;
    private int navFailCount = 0;
    private BlockPos mineBlockPos = null;
    // 当前任务（挖或补），从矿井方块 requestNextTask() 获取
    private DigTask currentTask = null;

    // 准备工作就绪标志（垫脚+火把+...），false时走checkPreparation
    private boolean preparationReady = false;
    private int preparationWaitTicks = 0;

    // Home 距离诊断日志用
    private int homeLogTick = 0;

    public MiningBehavior()
    {
        super(Map.of(), Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        // 跟随模式下不启动挖矿
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            return false;
        }
        Optional<List<BlockPos>> blocks = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        if (blocks.isEmpty() || blocks.get().isEmpty())
        {
            List<BlockPos> saved = maid.getData(ModAttachments.LOG_BLOCKS_SAVED);
            if (saved != null && !saved.isEmpty())
            {
                LOGGER.info("MiningBehavior: restored {} targets from attachment maid={}", saved.size(), maid.getId());
                maid.getBrain().setMemory(ModMemories.LOG_BLOCKS.get(), new ArrayList<>(saved));
                String savedAction = maid.getData(ModAttachments.WORK_ACTION_SAVED);
                if (savedAction != null && !savedAction.isEmpty())
                    maid.getBrain().setMemory(ModMemories.WORK_ACTION.get(), savedAction);
                String savedTarget = maid.getData(ModAttachments.WORK_TARGET_SAVED);
                if (savedTarget != null && !savedTarget.isEmpty())
                    maid.getBrain().setMemory(ModMemories.WORK_TARGET.get(), savedTarget);
                blocks = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
            }
        }
        return blocks.isPresent() && !blocks.get().isEmpty();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        // 跟随模式下停止挖矿
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            return false;
        }
        Optional<List<BlockPos>> blocks = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        return blocks.isPresent() && !blocks.get().isEmpty();
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("MiningBehavior START maid={}", maid.getId());
        equipPickaxe(maid);
        mineTimer = 0;
        reachedTarget = false;
        navFailCount = 0;
        currentTask = null;
        preparationReady = false;
        preparationWaitTicks = 0;

        Optional<List<BlockPos>> blocksOpt = maid.getBrain().getMemory(ModMemories.LOG_BLOCKS.get());
        if (blocksOpt.isEmpty() || blocksOpt.get().isEmpty()) return;

        List<BlockPos> blocks = blocksOpt.get();
        BlockPos firstBlock = blocks.get(0);
        if (level.getBlockEntity(firstBlock) instanceof MineBlockEntity be && be.hasInstance())
        {
            mineBlockPos = firstBlock;
            if (!be.isMaidInMine(maid))
            {
                be.joinMine(maid);
            }
            // 诊断日志：Home 设置后状态
            LOGGER.info("MiningBehavior: joinMine done maid={} homePos={} homeMode={} homeRadius={}",
                    maid.getId(), maid.getHomePosition(), maid.isHomeModeEnable(),
                    maid.hasHome() ? "set" : "none");
        }

        if (currentTask != null && !level.isLoaded(currentTask.pos()))
        {
            LOGGER.info("MiningBehavior: target {} not loaded, discarding maid={}", currentTask.pos(), maid.getId());
            clearAll(maid);
        }
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        // 每隔 100 tick 打印一次 Home 距离诊断
        homeLogTick++;
        if (homeLogTick >= 100)
        {
            homeLogTick = 0;
            BlockPos maidPos = maid.blockPosition();
            BlockPos homePos = maid.getHomePosition();
            double dist = maidPos.distSqr(homePos);
            LOGGER.info("MiningBehavior: HOME_DIAG maid={} pos={} home={} distSq={} homeMode={}",
                    maid.getId(), maidPos.toShortString(), homePos.toShortString(),
                    dist, maid.isHomeModeEnable());
        }

        // 准备阶段：顺序检查垫脚→火把，不过就等待
        if (!preparationReady)
        {
            checkPreparation(level, maid);
            return;
        }

        if (currentTask == null)
        {
            requestNextTask(level, maid);
            if (currentTask == null)
            {
                LOGGER.info("MiningBehavior: no more tasks, finishing maid={}", maid.getId());
                finishMining(level, maid);
                return;
            }
            LOGGER.info("MiningBehavior: new task {} at {} maidY={} maid={}",
                    currentTask.type(), currentTask.pos().toShortString(),
                    maid.blockPosition().getY(), maid.getId());
        }

        BlockPos targetPos = currentTask.pos();

        if (!level.isLoaded(targetPos))
        {
            LOGGER.info("MiningBehavior: target {} not loaded, skipping maid={}", targetPos, maid.getId());
            releaseCurrentTarget(level);
            return;
        }

        // 任务可能已被外部改变，重新校验有效性
        BlockState state = level.getBlockState(targetPos);
        if (currentTask.type() == DigTask.Type.DIG && state.isAir())
        {
            // 方块已被挖，视为完成
            notifyTaskComplete(level, targetPos);
            currentTask = null;
            reachedTarget = false;
            mineTimer = 0;
            return;
        }
        if (currentTask.type() == DigTask.Type.FILL && state.isSolid())
        {
            // 已被填充（固体方块），视为完成
            notifyTaskComplete(level, targetPos);
            currentTask = null;
            reachedTarget = false;
            mineTimer = 0;
            return;
        }

        if (!reachedTarget)
        {
            double distSq = maid.distanceToSqr(
                    targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

            if (distSq <= WALK_REACH_SQ)
            {
                reachedTarget = true;
                navFailCount = 0;
                return;
            }

            if (!maid.getNavigation().isInProgress())
            {
                BlockPos walkTarget = findWalkTarget(level, targetPos);
                if (walkTarget == null)
                {
                    LOGGER.info("MiningBehavior: no walk target near {} maid={}", targetPos, maid.getId());
                    releaseCurrentTarget(level);
                    reachedTarget = false;
                    navFailCount = 0;
                    return;
                }

                boolean moved = maid.getNavigation().moveTo(
                        walkTarget.getX() + 0.5, walkTarget.getY(),
                        walkTarget.getZ() + 0.5, WALK_SPEED);

                if (!moved)
                {
                    navFailCount++;
                    if (navFailCount >= MAX_NAV_FAIL || distSq < 25.0)
                    {
                        reachedTarget = true;
                        navFailCount = 0;
                    }
                }
                else
                {
                    navFailCount = 0;
                }
            }
            return;
        }

        maid.getLookControl().setLookAt(
                targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
                30f, 30f);

        if (currentTask.type() == DigTask.Type.DIG)
        {
            mineTimer++;
            maid.swing(maid.getUsedItemHand());

            if (mineTimer >= MINE_INTERVAL)
            {
                // 直接获取掉落物加入女仆背包，不产生掉落物实体
                BlockState blockState = level.getBlockState(targetPos);
                BlockEntity blockEntityAt = level.getBlockEntity(targetPos);
                List<ItemStack> drops = Block.getDrops(blockState, level,
                        targetPos, blockEntityAt, maid, maid.getMainHandItem());
                level.destroyBlock(targetPos, false, maid);

                for (ItemStack drop : drops)
                {
                    if (drop.isEmpty()) continue;
                    ItemStack leftover = addToInventory(maid, drop);
                    if (!leftover.isEmpty())
                    {
                        Block.popResource(level, targetPos, leftover);
                    }
                }

                LOGGER.info("MiningBehavior: mined {} maid={}", targetPos, maid.getId());

                // 通知矿井任务已完成
                notifyTaskComplete(level, targetPos);
                checkBelowSafety(level, maid, targetPos);

                currentTask = null;
                reachedTarget = false;
                mineTimer = 0;

                // 背包没有空格了 → 触发回矿井方块存东西
                boolean full = isInventoryFull(maid);
                LOGGER.info("MiningBehavior: inventory full={} maid={}", full, maid.getId());
                if (full)
                {
                    preparationReady = false;
                }
            }
        }
        else if (currentTask.type() == DigTask.Type.FILL)
        {
            if (tryPlaceScaffold(level, maid, targetPos))
            {
                LOGGER.info("MiningBehavior: filled scaffold {} maid={}", targetPos, maid.getId());

                // 通知矿井任务已完成
                notifyTaskComplete(level, targetPos);

                currentTask = null;
                reachedTarget = false;
                mineTimer = 0;
            }
            else
            {
                // 没方块可放了，提示一下等下次再试
                showBubbleWithCooldown(maid, "需要垫脚方块", 9542L);
                preparationReady = false;
                releaseCurrentTarget(level);
                reachedTarget = false;
            }
        }
        else if (currentTask.type() == DigTask.Type.LIGHT)
        {
            BlockState lightState = level.getBlockState(targetPos);
            if (!lightState.isAir())
            {
                // 有方块 → 挖掉（掉落物进背包），不置空 currentTask
                mineTimer++;
                maid.swing(maid.getUsedItemHand());
                if (mineTimer >= MINE_INTERVAL)
                {
                    mineAndCollect(level, maid, targetPos);
                    mineTimer = 0;
                }
            }
            else
            {
                // 空气 → 放火把
                if (tryPlaceTorch(level, maid, targetPos))
                {
                    LOGGER.info("MiningBehavior: placed torch at {} maid={}", targetPos, maid.getId());
                    notifyTaskComplete(level, targetPos);
                    currentTask = null;
                    reachedTarget = false;
                    mineTimer = 0;
                }
                else
                {
                    requestItemFromMineBlock(level, maid, this::isTorchItem,
                            9541L, "需要火把来照明");
                    releaseCurrentTarget(level);
                    reachedTarget = false;
                }
            }
        }
        else if (currentTask.type() == DigTask.Type.REPLACE)
        {
            BlockState replaceState = level.getBlockState(targetPos);
            if (!replaceState.isAir() && !isScaffoldItem(new ItemStack(replaceState.getBlock())))
            {
                // 非垫脚方块 → 挖掉（掉落物进背包）
                mineTimer++;
                maid.swing(maid.getUsedItemHand());
                if (mineTimer >= MINE_INTERVAL)
                {
                    mineAndCollect(level, maid, targetPos);
                    mineTimer = 0;
                }
            }
            else
            {
                // 空气或非固体 → 放垫脚方块
                if (tryPlaceScaffold(level, maid, targetPos))
                {
                    LOGGER.info("MiningBehavior: replaced with scaffold at {} maid={}", targetPos, maid.getId());
                    notifyTaskComplete(level, targetPos);
                    currentTask = null;
                    reachedTarget = false;
                    mineTimer = 0;
                }
                else
                {
                    requestItemFromMineBlock(level, maid, this::isScaffoldItem,
                            9540L, "需要垫脚方块");
                    releaseCurrentTarget(level);
                    reachedTarget = false;
                }
            }
        }
    }

    private void checkPreparation(ServerLevel level, EntityMaid maid)
    {
        if (preparationWaitTicks > 0)
        {
            preparationWaitTicks--;
            return;
        }

        // 1. 背包满了 → 回矿井方块存东西
        if (isInventoryFull(maid))
        {
            LOGGER.info("MiningBehavior: inventory full, nearMine={} maid={}", isNearMineBlock(maid), maid.getId());
            if (isNearMineBlock(maid))
            {
                depositToMineBlock(level, maid);
                preparationReady = true;
            }
            else
            {
                goToMineBlock(level, maid);
            }
            return;
        }

        // 2. 缺垫脚方块 → 通用请求
        boolean hasScaffold = hasScaffoldBlock(maid);
        LOGGER.info("MiningBehavior: hasScaffold={} maid={}", hasScaffold, maid.getId());
        if (!hasScaffold)
        {
            if (requestItemFromMineBlock(level, maid, this::isScaffoldItem,
                    9540L, "需要垫脚方块（泥土/木板/圆石/石头等）"))
            {
                preparationReady = true;
            }
            return;
        }

        // 3. 缺火把 → 通用请求
        if (!hasTorchBlock(maid))
        {
            if (requestItemFromMineBlock(level, maid, this::isTorchItem,
                    9541L, "需要火把或灯笼来照明"))
            {
                preparationReady = true;
            }
            return;
        }

        preparationReady = true;
    }

    // 气泡冷却追踪（key → 上次添加时的 gameTick）
    private final Map<Long, Long> bubbleLastTick = new HashMap<>();

    // 带冷却的气泡显示，防止累积
    private void showBubbleWithCooldown(EntityMaid maid, String text, long key)
    {
        long currentTick = maid.level().getGameTime();
        Long lastTick = bubbleLastTick.get(key);
        if (lastTick != null && currentTick - lastTick < BUBBLE_COOLDOWN)
        {
            return;
        }
        maid.getChatBubbleManager().addTextChatBubble(text);
        bubbleLastTick.put(key, currentTick);
    }

    // 通用物资请求：检查→导航→取物→提示
    // predicate: 判断物品是否匹配（如 isScaffoldItem、isTorchItem）
    // bubbleKey: 气泡冷却 key（避免重复添加）
    // bubbleText: 气泡提示文本
    private boolean requestItemFromMineBlock(
            ServerLevel level, EntityMaid maid,
            java.util.function.Predicate<ItemStack> predicate,
            long bubbleKey, String bubbleText)
    {
        if (isNearMineBlock(maid))
        {
            if (tryRetrieveFromMineBlock(level, maid, predicate))
            {
                return true;
            }
            // 矿井里没有该物资，显示气泡提示
            showBubbleWithCooldown(maid, bubbleText, bubbleKey);
            preparationWaitTicks = PREP_WAIT;
            return false;
        }
        else
        {
            goToMineBlock(level, maid);
            return false;
        }
    }

    // 检查女仆背包是否已满（所有可用格子非空）
    private boolean isInventoryFull(EntityMaid maid)
    {
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        int totalSlots = inv.size();
        int emptyCount = 0;
        for (int i = 0; i < totalSlots; i++)
        {
            if (inv.getResource(i).isEmpty()) emptyCount++;
        }
        LOGGER.info("MiningBehavior: inventory check total={} empty={} maid={}", totalSlots, emptyCount, maid.getId());
        return emptyCount == 0;
    }

    // 检查女仆是否在矿井方块附近
    private boolean isNearMineBlock(EntityMaid maid)
    {
        if (mineBlockPos == null) return false;
        return maid.distanceToSqr(
                mineBlockPos.getX() + 0.5, mineBlockPos.getY() + 0.5, mineBlockPos.getZ() + 0.5) <= WALK_REACH_SQ;
    }

    // 把物品插入女仆背包，返回塞不下的部分
    private ItemStack addToInventory(EntityMaid maid, ItemStack stack)
    {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        ItemResource res = ItemResource.of(stack);
        int remaining = stack.getCount();

        // 先试图合并到已有同类堆
        for (int i = 0; i < inv.size() && remaining > 0; i++)
        {
            ItemResource existing = inv.getResource(i);
            if (existing.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(new ItemStack(res.getItem(), 1), new ItemStack(existing.getItem(), 1))) continue;
            try (Transaction tx = Transaction.openRoot())
            {
                int inserted = inv.insert(i, res, remaining, tx);
                if (inserted > 0) { tx.commit(); remaining -= inserted; }
            }
        }
        // 再放入空格
        for (int i = 0; i < inv.size() && remaining > 0; i++)
        {
            if (!inv.getResource(i).isEmpty()) continue;
            try (Transaction tx = Transaction.openRoot())
            {
                int inserted = inv.insert(i, res, remaining, tx);
                if (inserted > 0) { tx.commit(); remaining -= inserted; }
            }
        }
        return remaining > 0 ? new ItemStack(res.getItem(), remaining) : ItemStack.EMPTY;
    }

    // 把女仆背包里的东西存入矿井方块容器
    private void depositToMineBlock(ServerLevel level, EntityMaid maid)
    {
        if (mineBlockPos == null || !(level.getBlockEntity(mineBlockPos) instanceof MineBlockEntity be)) return;

        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            int amount = (int) inv.getAmountAsLong(i);
            // 创建 ItemStack 加入容器
            ItemStack stack = new ItemStack(res.getItem(), amount);
            ItemStack leftover = be.addItem(stack);
            // 从女仆背包提取已存入的数量
            int deposited = amount - leftover.getCount();
            if (deposited > 0)
            {
                try (Transaction tx = Transaction.openRoot())
                {
                    inv.extract(i, res, deposited, tx);
                    tx.commit();
                }
            }
        }
        LOGGER.info("MiningBehavior: deposited items to mine block maid={}", maid.getId());
    }

    // 尝试从矿井方块容器中取出一个匹配的物品给女仆
    private boolean tryRetrieveFromMineBlock(ServerLevel level, EntityMaid maid,
                                              java.util.function.Predicate<ItemStack> predicate)
    {
        if (mineBlockPos == null || !(level.getBlockEntity(mineBlockPos) instanceof MineBlockEntity be)) return false;

        for (int i = 0; i < be.getContainerSize(); i++)
        {
            ItemStack stack = be.getItem(i);
            if (stack.isEmpty() || !predicate.test(stack)) continue;

            // 从容器取一个
            ItemStack taken = be.removeItem(i, 1);
            if (taken.isEmpty()) continue;

            // 尝试插入女仆背包
            CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
            ItemResource res = ItemResource.of(taken);
            for (int j = 0; j < inv.size(); j++)
            {
                try (Transaction tx = Transaction.openRoot())
                {
                    int inserted = inv.insert(j, res, taken.getCount(), tx);
                    if (inserted > 0)
                    {
                        tx.commit();
                        LOGGER.info("MiningBehavior: retrieved {} from mine block maid={}", taken.getItem(), maid.getId());
                        return true;
                    }
                }
            }
            // 插不进去，放回容器
            be.addItem(taken);
            return false;
        }
        return false;
    }

    private void goToMineBlock(ServerLevel level, EntityMaid maid)
    {
        if (mineBlockPos != null && !maid.getNavigation().isInProgress())
        {
            BlockPos walkTarget = findWalkTarget(level, mineBlockPos);
            if (walkTarget != null)
            {
                maid.getNavigation().moveTo(
                        walkTarget.getX() + 0.5, walkTarget.getY(),
                        walkTarget.getZ() + 0.5, WALK_SPEED);
            }
        }
    }

    // 从矿井方块申请下一个任务（挖或补）
    private void requestNextTask(ServerLevel level, EntityMaid maid)
    {
        if (mineBlockPos == null || !(level.getBlockEntity(mineBlockPos) instanceof MineBlockEntity be) || !be.hasInstance())
        {
            currentTask = null;
            return;
        }

        currentTask = be.requestNextTask(maid);
        if (currentTask != null)
        {
            LOGGER.info("MiningBehavior: next task {} {} maid={}",
                    currentTask.type(), currentTask.pos(), maid.getId());
        }
    }

    private boolean hasScaffoldBlock(EntityMaid maid)
    {
        if (isScaffoldItem(maid.getMainHandItem())) return true;
        if (isScaffoldItem(maid.getOffhandItem())) return true;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            if (isScaffoldItem(new ItemStack(res.getItem(), 1))) return true;
        }
        return false;
    }

    private boolean isScaffoldItem(ItemStack stack)
    {
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        Block block = bi.getBlock();
        if (block.builtInRegistryHolder().is(BlockTags.DIRT)) return true;
        if (block.builtInRegistryHolder().is(BlockTags.PLANKS)) return true;
        if (block.builtInRegistryHolder().is(Tags.Blocks.COBBLESTONES)) return true;
        if (block.builtInRegistryHolder().is(Tags.Blocks.STONES)) return true;
        return false;
    }

    private boolean hasTorchBlock(EntityMaid maid)
    {
        if (isTorchItem(maid.getMainHandItem())) return true;
        if (isTorchItem(maid.getOffhandItem())) return true;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            if (isTorchItem(new ItemStack(res.getItem(), 1))) return true;
        }
        return false;
    }

    private boolean isTorchItem(ItemStack stack)
    {
        return stack.getItem() == Items.TORCH
                || stack.getItem() == Items.LANTERN;
    }

    // 在目标位置放置一块垫脚方块，成功返回 true
    private boolean tryPlaceScaffold(ServerLevel level, EntityMaid maid, BlockPos target)
    {
        BlockState targetState = level.getBlockState(target);
        // 允许在空气或可替换方块（流体、草丛等）中放置
        if (targetState.isSolid()) return false;

        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack stack = new ItemStack(res.getItem(), 1);
            if (!isScaffoldItem(stack)) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;

            BlockState stateToPlace = bi.getBlock().defaultBlockState();
            level.setBlock(target, stateToPlace, 3);

            try (Transaction tx = Transaction.openRoot())
            {
                inv.extract(i, res, 1, tx);
                tx.commit();
            }
            return true;
        }
        return false;
    }

    // 在目标位置放置火把，成功返回 true
    private boolean tryPlaceTorch(ServerLevel level, EntityMaid maid, BlockPos target)
    {
        if (!level.getBlockState(target).isAir()) return false;

        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack stack = new ItemStack(res.getItem(), 1);
            if (!isTorchItem(stack)) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;

            BlockState stateToPlace = bi.getBlock().defaultBlockState();
            level.setBlock(target, stateToPlace, 3);

            try (Transaction tx = Transaction.openRoot())
            {
                inv.extract(i, res, 1, tx);
                tx.commit();
            }
            return true;
        }
        return false;
    }

    // 挖掉方块并将掉落物直接收入背包（用于 LIGHT/REPLACE 任务）
    private void mineAndCollect(ServerLevel level, EntityMaid maid, BlockPos pos)
    {
        BlockState blockState = level.getBlockState(pos);
        BlockEntity blockEntityAt = level.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(blockState, level, pos, blockEntityAt, maid, maid.getMainHandItem());
        level.destroyBlock(pos, false, maid);

        for (ItemStack drop : drops)
        {
            if (drop.isEmpty()) continue;
            ItemStack leftover = addToInventory(maid, drop);
            if (!leftover.isEmpty())
            {
                Block.popResource(level, pos, leftover);
            }
        }
        LOGGER.info("MiningBehavior: mineAndCollect {} maid={}", pos, maid.getId());
    }

    private void finishMining(ServerLevel level, EntityMaid maid)
    {
        LOGGER.info("MiningBehavior: mining finished maid={}", maid.getId());
        clearAll(maid);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("MiningBehavior STOP maid={}", maid.getId());
        MineBlockEntity be = null;
        if (mineBlockPos != null)
        {
            var raw = level.getBlockEntity(mineBlockPos);
            if (raw instanceof MineBlockEntity mbe) be = mbe;
        }

        // 被打断时直接完成当前任务
        if (currentTask != null && be != null)
        {
            BlockPos pos = currentTask.pos();
            if (currentTask.type() == DigTask.Type.DIG)
            {
                BlockState blockState = level.getBlockState(pos);
                BlockEntity blockEntityAt = level.getBlockEntity(pos);
                List<ItemStack> drops = Block.getDrops(blockState, level, pos, blockEntityAt, maid, maid.getMainHandItem());
                level.destroyBlock(pos, false, maid);
                for (ItemStack drop : drops)
                {
                    if (drop.isEmpty()) continue;
                    ItemStack leftover = addToInventory(maid, drop);
                    if (!leftover.isEmpty()) Block.popResource(level, pos, leftover);
                }
                LOGGER.info("MiningBehavior: stop-completed DIG {} maid={}", pos, maid.getId());
                be.completeTarget(pos);
            }
            else if (currentTask.type() == DigTask.Type.FILL)
            {
                if (tryPlaceScaffold(level, maid, pos))
                {
                    LOGGER.info("MiningBehavior: stop-completed FILL {} maid={}", pos, maid.getId());
                    be.completeTarget(pos);
                }
                else
                {
                    be.releaseTarget(pos);
                }
            }
        }

        if (be != null) be.leaveMine(maid);
        maid.getNavigation().stop();
        mineTimer = 0;
        reachedTarget = false;
        navFailCount = 0;
        mineBlockPos = null;
        currentTask = null;
        preparationReady = false;
        preparationWaitTicks = 0;
    }

    // 破坏方块后检查下方是否安全，不安全则生成对应任务
    private void checkBelowSafety(ServerLevel level, EntityMaid maid, BlockPos brokenPos)
    {
        BlockPos below = brokenPos.below();
        BlockState belowState = level.getBlockState(below);

        // 下方是实心方块 → 安全，不需要处理
        if (belowState.isSolid())
        {
            return;
        }

        // 下方是流体（水/岩浆等）→ 消除流体，给女仆一个流体瓶
        if (isFluidSource(belowState))
        {
            Fluid fluid = getFluidFromBlock(belowState);
            ResourceKey<Fluid> fluidKey = null;
            if (fluid != null)
            {
                fluidKey = BuiltInRegistries.FLUID
                        .getResourceKey(fluid).orElse(null);
            }

            level.setBlock(below, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);

            if (fluidKey != null)
            {
                ItemStack bottle = new ItemStack(MineRegistration.FLUID_BOTTLE.get(), 1);
                FluidBottleItem.setFluid(bottle, fluidKey);
                ItemStack leftover = addToInventory(maid, bottle);
                if (!leftover.isEmpty())
                {
                    Block.popResource(level, brokenPos, leftover);
                }
            }
            LOGGER.info("MiningBehavior: fluid removed at {} maid={}", below, maid.getId());
            return;
        }

        // 下方是不可破坏方块（基岩等）→ 停机
        if (belowState.getDestroySpeed(level, below) < 0)
        {
            LOGGER.info("MiningBehavior: unbreakable at {}, SHUTDOWN maid={}", below, maid.getId());
            if (mineBlockPos != null
                    && level.getBlockEntity(mineBlockPos) instanceof MineBlockEntity mbe)
            {
                mbe.shutdownMine(level);
            }
            return;
        }

        // 其他情况（空气、可穿越方块等）→ 填充垫脚方块
        if (!tryFillBelow(level, maid, below))
        {
            // 女仆没有垫脚方块，创建 FILL 任务去矿井取
            LOGGER.info("MiningBehavior: fill needed at {}, no scaffold maid={}", below, maid.getId());
            if (mineBlockPos != null
                    && level.getBlockEntity(mineBlockPos) instanceof MineBlockEntity be
                    && be.hasInstance())
            {
                currentTask = new DigTask(below, DigTask.Type.FILL);
            }
        }
    }

    // 尝试用背包里的垫脚方块填充指定位置
    private boolean tryFillBelow(ServerLevel level, EntityMaid maid, BlockPos target)
    {
        if (!level.getBlockState(target).isAir()) return false;

        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack stack = new ItemStack(res.getItem(), 1);
            if (!isScaffoldItem(stack)) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;

            BlockState stateToPlace = bi.getBlock().defaultBlockState();
            level.setBlock(target, stateToPlace, 3);

            try (Transaction tx = Transaction.openRoot())
            {
                inv.extract(i, res, 1, tx);
                tx.commit();
            }
            return true;
        }
        return false;
    }

    // 判断方块是否为流体源方块
    private boolean isFluidSource(BlockState state)
    {
        Block block = state.getBlock();
        return block == net.minecraft.world.level.block.Blocks.WATER
                || block == net.minecraft.world.level.block.Blocks.LAVA;
    }

    // 从方块状态获取对应的流体类型
    private Fluid getFluidFromBlock(BlockState state)
    {
        Block block = state.getBlock();
        if (block == net.minecraft.world.level.block.Blocks.WATER) return Fluids.WATER;
        if (block == net.minecraft.world.level.block.Blocks.LAVA) return Fluids.LAVA;
        return null;
    }

    // 通知矿井某个任务已完成（从 assignedTargets 中释放）
    private void notifyTaskComplete(ServerLevel level, BlockPos pos)
    {
        if (mineBlockPos != null && level.getBlockEntity(mineBlockPos) instanceof MineBlockEntity be)
        {
            be.completeTarget(pos);
        }
    }

    // 释放当前任务目标并清空 currentTask（任务未完成时调用）
    private void releaseCurrentTarget(ServerLevel level)
    {
        if (currentTask != null && mineBlockPos != null
                && level.getBlockEntity(mineBlockPos) instanceof MineBlockEntity be)
        {
            be.releaseTarget(currentTask.pos());
        }
        currentTask = null;
    }

    private void clearAll(EntityMaid maid)
    {
        maid.getBrain().eraseMemory(ModMemories.LOG_BLOCKS.get());
        maid.removeData(ModAttachments.LOG_BLOCKS_SAVED);
        mineTimer = 0;
        reachedTarget = false;
        navFailCount = 0;
        mineBlockPos = null;
        currentTask = null;
        preparationReady = false;
        preparationWaitTicks = 0;
    }

    private void equipPickaxe(EntityMaid maid)
    {
        if (maid.getMainHandItem().is(ItemTags.PICKAXES)) return;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.getItem().builtInRegistryHolder().is(ItemTags.PICKAXES))
            {
                ItemStack oldHand = maid.getMainHandItem();
                try (Transaction tx = Transaction.openRoot())
                {
                    inv.extract(i, res, 1, tx);
                    if (!oldHand.isEmpty())
                    {
                        inv.insert(i, ItemResource.of(oldHand), oldHand.getCount(), tx);
                    }
                    tx.commit();
                }
                maid.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(res.getItem(), 1));
                return;
            }
        }
    }

    private BlockPos findWalkTarget(ServerLevel level, BlockPos target)
    {
        // 先找同高度的相邻站立点
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
        {
            BlockPos p = target.relative(d);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolid())
            {
                return p;
            }
        }
        // 同高度找不到时，找相邻方块的上方（站在上一层空间往下挖）
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
        {
            BlockPos adjacent = target.relative(d);
            BlockPos stand = adjacent.above();
            if (level.getBlockState(stand).isAir() && level.getBlockState(adjacent).isSolid())
            {
                return stand;
            }
        }
        return null;
    }
}
