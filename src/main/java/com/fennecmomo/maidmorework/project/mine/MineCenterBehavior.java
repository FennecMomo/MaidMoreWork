package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.item.FluidBottleItem;
import com.fennecmomo.maidmorework.project.center.MineInstance;
import com.fennecmomo.maidmorework.project.center.ProjectCenterManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 女仆挖矿行为（B1 拍板：专用挖矿 Behavior，Home 模式下与矿井中心对接）
//
// 循环（§6 派发树由 MineInstance.requestWork 承担，本类只管"领活→干→交活"）：
//   领任务 → 导航 → 执行（DESTROY/FILL/REPLACE/SETLIGHT/FETCH_LIGHT）→ completeWork → 循环
// 附加协议：
//   B2 索灯：领到 FETCH_LIGHT → 走到矿井方块 → 仓库取一组光源（不足拿剩余，无货气泡+阻塞等待）
//   B3 存取：背包剩余格 < 3 → 走到矿井方块存矿（黑名单外全存）；FILL/REPLACE 缺垫脚 → 仓库取一组
//
// 矿井中心解析：优先中心归属（joinCenter 写入的记忆/附件），否则就近家园范围内矿井实例
// 装备：开工装备镐（复用旧 equipPickaxe 模式）
public class MineCenterBehavior extends Behavior<EntityMaid>
{
    private static final int MINE_INTERVAL = 10;        // 挖一个方块的 tick 间隔
    private static final double WALK_REACH_SQ = 16.0;   // 到达判定距离平方（4格）
    private static final double WALK_SPEED = 0.6;       // 导航速度倍率
    private static final int MAX_NAV_FAIL = 3;          // 导航失败次数上限，超过后强制到达
    private static final int FETCH_GROUP = 10;          // B2/B3：取消耗品一次一组（10个）
    private static final int DEPOSIT_FREE_SLOTS = 3;    // B3：剩余可用格阈值（默认3，可配置项后续接入）
    private static final int WAIT_TICKS = 40;           // 阻塞等待节流（B2 仓库无货）
    private static final int BUBBLE_COOLDOWN = 120;     // 气泡冷却（tick）
    private static final long SCAFFOLD_KEY = 9540L;     // 垫脚气泡冷却 key
    private static final long LIGHT_KEY = 9541L;        // 光源气泡冷却 key

    private UUID mineId = null;             // 所属矿井实例 ID
    private MineTask currentTask = null;    // 当前任务
    private boolean reachedTarget = false;
    private int navFailCount = 0;
    private int mineTimer = 0;
    private int waitTicks = 0;              // 阻塞等待倒计时（取不到物资时）
    private boolean finished = false;       // 矿井挖尽/失联 → 结束行为
    private ScaffoldFetch scaffoldFetch = ScaffoldFetch.NONE; // FILL/REPLACE 缺垫脚时的取货支线
    private final Map<Long, Long> bubbleLastTick = new HashMap<>();

    private enum ScaffoldFetch
    {
        NONE, FETCH
    }

    public MineCenterBehavior()
    {
        super(Map.of(), Integer.MAX_VALUE);
    }

    // ===================== 启动/继续条件 =====================

    // Home 模式 + 就近有矿井中心（或已有归属）
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        if (!maid.isHomeModeEnable() && maid.canBrainMoving()) return false;
        return resolveMine(maid) != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        if (finished) return false;
        if (!maid.isHomeModeEnable() && maid.canBrainMoving()) return false;
        return resolveMine(maid) != null;
    }

    // ===================== 生命周期 =====================

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        MineInstance mine = resolveMine(maid);
        if (mine == null)
        {
            finished = true;
            return;
        }
        equipPickaxe(maid);
        mineId = mine.getId();
        mine.joinCenter(maid);
        currentTask = null;
        reachedTarget = false;
        navFailCount = 0;
        mineTimer = 0;
        waitTicks = 0;
        finished = false;
        scaffoldFetch = ScaffoldFetch.NONE;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        MineInstance mine = mineById(level);
        if (mine == null || mine.isExhausted())
        {
            finished = true;
            return;
        }

        if (waitTicks > 0)
        {
            waitTicks--;
            return;
        }

        // B3：背包剩余格不足 → 去矿井方块存矿（黑名单外全存）
        if (freeSlots(maid) < DEPOSIT_FREE_SLOTS)
        {
            handleDeposit(level, maid, mine);
            return;
        }

        // FILL/REPLACE 缺垫脚 → 取货支线
        if (scaffoldFetch == ScaffoldFetch.FETCH)
        {
            handleScaffoldFetch(level, maid, mine);
            return;
        }

        // 领任务
        if (currentTask == null)
        {
            MineTask task = mine.requestWork(maid);
            if (task == null) return;
            currentTask = task;
            reachedTarget = false;
            mineTimer = 0;
        }

        MineTask task = currentTask;
        BlockPos target = task.pos();
        if (!level.isLoaded(target))
        {
            releaseCurrent(mine, maid);
            return;
        }

        // 导航
        if (!reachedTarget)
        {
            navigate(level, maid, target);
            return;
        }

        execute(level, maid, mine, task);
    }

    // ===================== 任务执行 =====================

    private void execute(ServerLevel level, EntityMaid maid, MineInstance mine, MineTask task)
    {
        BlockPos target = task.pos();
        BlockState state = level.getBlockState(target);
        maid.getLookControl().setLookAt(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30f, 30f);

        switch (task.type())
        {
            // === DESTROY：挥镐挖掘，掉落物直接进背包 ===
            case DESTROY ->
            {
                if (state.isAir())
                {
                    finishTask(mine, maid, task);
                    return;
                }
                mineTimer++;
                maid.swing(maid.getUsedItemHand());
                if (mineTimer >= MINE_INTERVAL)
                {
                    mineAndCollect(level, maid, target);
                    checkBelowSafety(level, maid, target);
                    finishTask(mine, maid, task);
                }
            }

            // === FILL：放置垫脚方块（§5 空洞补墙） ===
            case FILL ->
            {
                if (!state.isAir() && state.isSolid())
                {
                    finishTask(mine, maid, task);            // 已被别人填了
                    return;
                }
                if (tryPlaceScaffold(level, maid, target))
                {
                    finishTask(mine, maid, task);
                }
                else
                {
                    // 缺垫脚 → 释放任务 + 取货支线（B3）
                    showBubbleWithCooldown(maid, "需要垫脚方块（去仓库取）", SCAFFOLD_KEY);
                    releaseCurrent(mine, maid);
                    scaffoldFetch = ScaffoldFetch.FETCH;
                }
            }

            // === REPLACE：非垫脚实体块挖掉再垫（§5）；源流体清掉并收流体瓶（§9） ===
            case REPLACE ->
            {
                if (isFluidSource(state))
                {
                    handleSourceFluid(level, maid, mine, task, target);
                    return;
                }
                if (state.isAir() || isScaffoldState(state))
                {
                    if (tryPlaceScaffold(level, maid, target))
                    {
                        finishTask(mine, maid, task);
                    }
                    else
                    {
                        showBubbleWithCooldown(maid, "需要垫脚方块（去仓库取）", SCAFFOLD_KEY);
                        releaseCurrent(mine, maid);
                        scaffoldFetch = ScaffoldFetch.FETCH;
                    }
                }
                else
                {
                    mineTimer++;
                    maid.swing(maid.getUsedItemHand());
                    if (mineTimer >= MINE_INTERVAL)
                    {
                        mineAndCollect(level, maid, target);
                        mineTimer = 0;                       // 挖完不交任务，下一阶段垫脚
                    }
                }
            }

            // === SETLIGHT：位上有光源视为完成；有方块先挖；否则放置 ===
            case SETLIGHT ->
            {
                if (state.getLightEmission() > 0)
                {
                    finishTask(mine, maid, task);
                    return;
                }
                if (!state.isAir())
                {
                    mineTimer++;
                    maid.swing(maid.getUsedItemHand());
                    if (mineTimer >= MINE_INTERVAL)
                    {
                        mineAndCollect(level, maid, target);
                        mineTimer = 0;
                    }
                    return;
                }
                if (tryPlaceLight(level, maid, target))
                {
                    finishTask(mine, maid, task);
                }
                else
                {
                    // 包里灯用完了 → 释放，requestWork 会重新给出 FETCH_LIGHT（B2）
                    releaseCurrent(mine, maid);
                }
            }

            // === FETCH_LIGHT：走到矿井方块，从仓库取一组光源（B2 拍板） ===
            case FETCH_LIGHT ->
            {
                List<ItemStack> taken = mine.takeFromWarehouse(MineCenterBehavior::isLightItem, FETCH_GROUP);
                if (taken.isEmpty())
                {
                    // 仓库无光源：气泡提示 + 阻塞等待（请求机制属后续规划，暂以提示替代）
                    showBubbleWithCooldown(maid, "仓库缺光源，等待补充", LIGHT_KEY);
                    waitTicks = WAIT_TICKS;
                    return;
                }
                boolean allIn = true;
                for (ItemStack stack : taken)
                {
                    ItemStack leftover = addToInventory(maid, stack);
                    if (!leftover.isEmpty())
                    {
                        Block.popResource(level, target, leftover);
                        allIn = false;
                    }
                }
                if (allIn)
                {
                    finishTask(mine, maid, task);            // 取灯不记坐标
                }
                else
                {
                    waitTicks = WAIT_TICKS;                  // 背包也满了 → 缓一缓（下轮触发存矿）
                }
            }
        }
    }

    // 源流体处理（§9）：清掉流体 + 对应流体瓶塞 + 垫脚
    private void handleSourceFluid(ServerLevel level, EntityMaid maid, MineInstance mine,
                                   MineTask task, BlockPos target)
    {
        Fluid fluid = getFluidFromBlock(level.getBlockState(target));
        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
        if (fluid != null)
        {
            ResourceKey<Fluid> fluidKey = BuiltInRegistries.FLUID.getResourceKey(fluid).orElse(null);
            if (fluidKey != null)
            {
                ItemStack bottle = new ItemStack(com.fennecmomo.maidmorework.mining.MineRegistration.FLUID_BOTTLE.get(), 1);
                FluidBottleItem.setFluid(bottle, fluidKey);
                ItemStack leftover = addToInventory(maid, bottle);
                if (!leftover.isEmpty())
                {
                    Block.popResource(level, target, leftover);
                }
            }
        }
        if (tryPlaceScaffold(level, maid, target))
        {
            finishTask(mine, maid, task);
        }
        else
        {
            showBubbleWithCooldown(maid, "需要垫脚方块（去仓库取）", SCAFFOLD_KEY);
            releaseCurrent(mine, maid);
            scaffoldFetch = ScaffoldFetch.FETCH;
        }
    }

    // 完成任务：解绑 + 记已处理，重置单任务状态
    private void finishTask(MineInstance mine, EntityMaid maid, MineTask task)
    {
        mine.completeWork(maid, task);
        currentTask = null;
        reachedTarget = false;
        mineTimer = 0;
    }

    // ===================== 存取支线 =====================

    // B3 存矿：就近矿井方块，黑名单外全部入仓
    private void handleDeposit(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        if (isNear(maid, mine.getBlockPos()))
        {
            depositToWarehouse(mine, maid);
            return;
        }
        navigate(level, maid, mine.getBlockPos());
    }

    // B3 取垫脚：就近矿井方块，从仓库取一组垫脚方块
    private void handleScaffoldFetch(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        if (isNear(maid, mine.getBlockPos()))
        {
            List<ItemStack> taken = mine.takeFromWarehouse(MineCenterBehavior::isScaffoldItem, FETCH_GROUP);
            if (taken.isEmpty())
            {
                showBubbleWithCooldown(maid, "仓库缺垫脚方块", SCAFFOLD_KEY);
                waitTicks = WAIT_TICKS;
                return;
            }
            for (ItemStack stack : taken)
            {
                ItemStack leftover = addToInventory(maid, stack);
                if (!leftover.isEmpty())
                {
                    Block.popResource(level, mine.getBlockPos(), leftover);
                }
            }
            scaffoldFetch = ScaffoldFetch.NONE;
            return;
        }
        navigate(level, maid, mine.getBlockPos());
    }

    // 存矿（B3 拍板：黑名单外全部入仓）——黑名单：镐/垫脚/光源/流体瓶等自用物资
    private void depositToWarehouse(MineInstance mine, EntityMaid maid)
    {
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        List<ItemStack> toDeposit = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<ItemResource> resources = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack probe = new ItemStack(res.getItem(), 1);
            if (isDepositBlacklisted(probe)) continue;
            int amount = (int) inv.getAmountAsLong(i);
            toDeposit.add(new ItemStack(res.getItem(), amount));
            slots.add(i);
            resources.add(res);
            amounts.add(amount);
        }
        if (toDeposit.isEmpty()) return;
        mine.depositToWarehouse(toDeposit);
        for (int k = 0; k < slots.size(); k++)
        {
            try (Transaction tx = Transaction.openRoot())
            {
                inv.extract(slots.get(k), resources.get(k), amounts.get(k), tx);
                tx.commit();
            }
        }
    }

    // ===================== 导航 =====================

    private void navigate(ServerLevel level, EntityMaid maid, BlockPos target)
    {
        double distSq = maid.distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (distSq <= WALK_REACH_SQ)
        {
            reachedTarget = true;
            navFailCount = 0;
            return;
        }
        if (!maid.getNavigation().isInProgress())
        {
            BlockPos walkTarget = findWalkTarget(level, target);
            if (walkTarget == null)
            {
                navFailCount++;
                if (navFailCount >= MAX_NAV_FAIL || distSq < 25.0)
                {
                    reachedTarget = true;
                    navFailCount = 0;
                }
                return;
            }
            boolean moved = maid.getNavigation().moveTo(
                    walkTarget.getX() + 0.5, walkTarget.getY(), walkTarget.getZ() + 0.5, WALK_SPEED);
            if (moved)
            {
                navFailCount = 0;
            }
            else
            {
                navFailCount++;
                if (navFailCount >= MAX_NAV_FAIL || distSq < 25.0)
                {
                    reachedTarget = true;
                    navFailCount = 0;
                }
            }
        }
    }

    private boolean isNear(EntityMaid maid, BlockPos pos)
    {
        return maid.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= WALK_REACH_SQ;
    }

    // 在目标方块附近找可站立的行走点（先同高度相邻，再上一层俯身）
    private static BlockPos findWalkTarget(ServerLevel level, BlockPos target)
    {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
        {
            BlockPos p = target.relative(d);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolid())
            {
                return p;
            }
        }
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

    // ===================== 挖掘/放置 =====================

    // 挖掉方块并将掉落物直接收入背包（不产生掉落物实体）
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
    }

    // 破坏后下方安全检查：实心→安全；源流体→清掉+流体瓶；基岩→不管（派发侧困难表处理）；
    // 空洞→就地垫脚（无垫脚则作罢，属于下一层任务池）
    private void checkBelowSafety(ServerLevel level, EntityMaid maid, BlockPos brokenPos)
    {
        BlockPos below = brokenPos.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.isSolid()) return;
        if (isFluidSource(belowState))
        {
            Fluid fluid = getFluidFromBlock(belowState);
            level.setBlock(below, Blocks.AIR.defaultBlockState(), 3);
            if (fluid != null)
            {
                ResourceKey<Fluid> fluidKey = BuiltInRegistries.FLUID.getResourceKey(fluid).orElse(null);
                if (fluidKey != null)
                {
                    ItemStack bottle = new ItemStack(com.fennecmomo.maidmorework.mining.MineRegistration.FLUID_BOTTLE.get(), 1);
                    FluidBottleItem.setFluid(bottle, fluidKey);
                    ItemStack leftover = addToInventory(maid, bottle);
                    if (!leftover.isEmpty())
                    {
                        Block.popResource(level, brokenPos, leftover);
                    }
                }
            }
            return;
        }
        if (belowState.getDestroySpeed(level, below) < 0) return;
        tryPlaceScaffold(level, maid, below);
    }

    // 放置垫脚方块（从背包找第一个垫脚，Transaction 扣减）
    private boolean tryPlaceScaffold(ServerLevel level, EntityMaid maid, BlockPos target)
    {
        BlockState targetState = level.getBlockState(target);
        if (targetState.isSolid()) return false;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack stack = new ItemStack(res.getItem(), 1);
            if (!isScaffoldItem(stack)) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;
            level.setBlock(target, bi.getBlock().defaultBlockState(), 3);
            try (Transaction tx = Transaction.openRoot())
            {
                inv.extract(i, res, 1, tx);
                tx.commit();
            }
            return true;
        }
        return false;
    }

    // 放置光源（任意可放置且发光的方块物品，B2 判定）
    private boolean tryPlaceLight(ServerLevel level, EntityMaid maid, BlockPos target)
    {
        if (!level.getBlockState(target).isAir()) return false;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack stack = new ItemStack(res.getItem(), 1);
            if (!isLightItem(stack)) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;
            level.setBlock(target, bi.getBlock().defaultBlockState(), 3);
            try (Transaction tx = Transaction.openRoot())
            {
                inv.extract(i, res, 1, tx);
                tx.commit();
            }
            return true;
        }
        return false;
    }

    // 把物品插入女仆背包，返回塞不下的部分（优先同类合并，再空格）
    private ItemStack addToInventory(EntityMaid maid, ItemStack stack)
    {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        ItemResource res = ItemResource.of(stack);
        int remaining = stack.getCount();
        for (int i = 0; i < inv.size() && remaining > 0; i++)
        {
            ItemResource existing = inv.getResource(i);
            if (existing.isEmpty()) continue;
            if (existing.getItem() != res.getItem()) continue;
            try (Transaction tx = Transaction.openRoot())
            {
                int inserted = inv.insert(i, res, remaining, tx);
                if (inserted > 0)
                {
                    tx.commit();
                    remaining -= inserted;
                }
            }
        }
        for (int i = 0; i < inv.size() && remaining > 0; i++)
        {
            if (!inv.getResource(i).isEmpty()) continue;
            try (Transaction tx = Transaction.openRoot())
            {
                int inserted = inv.insert(i, res, remaining, tx);
                if (inserted > 0)
                {
                    tx.commit();
                    remaining -= inserted;
                }
            }
        }
        return remaining > 0 ? new ItemStack(res.getItem(), remaining) : ItemStack.EMPTY;
    }

    // ===================== 物品判定 =====================

    // 垫脚物品：泥土/木板/圆石/石头（§5 垫脚集合）
    private static boolean isScaffoldItem(ItemStack stack)
    {
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        var block = bi.getBlock();
        return block.builtInRegistryHolder().is(BlockTags.DIRT)
                || block.builtInRegistryHolder().is(BlockTags.PLANKS)
                || block.builtInRegistryHolder().is(Tags.Blocks.COBBLESTONES)
                || block.builtInRegistryHolder().is(Tags.Blocks.STONES);
    }

    // 光源物品（B2 判定）：可放置且发光
    private static boolean isLightItem(ItemStack stack)
    {
        if (stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        return bi.getBlock().defaultBlockState().getLightEmission() > 0;
    }

    private static boolean isScaffoldState(BlockState state)
    {
        var block = state.getBlock();
        return block.builtInRegistryHolder().is(BlockTags.DIRT)
                || block.builtInRegistryHolder().is(BlockTags.PLANKS)
                || block.builtInRegistryHolder().is(Tags.Blocks.COBBLESTONES)
                || block.builtInRegistryHolder().is(Tags.Blocks.STONES);
    }

    private static boolean isFluidSource(BlockState state)
    {
        return state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA;
    }

    private static Fluid getFluidFromBlock(BlockState state)
    {
        if (state.getBlock() == Blocks.WATER) return Fluids.WATER;
        if (state.getBlock() == Blocks.LAVA) return Fluids.LAVA;
        return null;
    }

    // 存矿黑名单（B3 拍板：自用工具与物资不存）
    private static boolean isDepositBlacklisted(ItemStack stack)
    {
        if (stack.isEmpty()) return false;
        if (stack.is(ItemTags.PICKAXES)) return true;
        if (isScaffoldItem(stack)) return true;
        if (isLightItem(stack)) return true;
        if (stack.getItem() instanceof FluidBottleItem) return true;
        return false;
    }

    // ===================== 库存/装备/气泡 =====================

    private static int freeSlots(EntityMaid maid)
    {
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        int empty = 0;
        for (int i = 0; i < inv.size(); i++)
        {
            if (inv.getResource(i).isEmpty()) empty++;
        }
        return empty;
    }

    // 装备镐子：背包有镐则换到主手（同槽位交换，Transaction 原子操作）
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

    // ===================== 任务解绑/矿井解析/收尾 =====================

    // 任务未完成释放：解绑子任务（坐标回池子）
    private void releaseCurrent(MineInstance mine, EntityMaid maid)
    {
        if (currentTask != null)
        {
            mine.releaseWork(maid);
        }
        currentTask = null;
    }

    private MineInstance mineById(ServerLevel level)
    {
        return ProjectCenterManager.get(level, mineId) instanceof MineInstance mine ? mine : null;
    }

    // 矿井中心解析：归属优先，其次就近家园范围内的矿井实例
    private static MineInstance resolveMine(EntityMaid maid)
    {
        if (!(maid.level() instanceof ServerLevel level)) return null;
        var center = ProjectCenterManager.getCenterOf(maid);
        if (center instanceof MineInstance mine) return mine;
        MineInstance best = null;
        double bestDist = Double.MAX_VALUE;
        for (var c : ProjectCenterManager.allCenters(level))
        {
            if (!(c instanceof MineInstance m)) continue;
            if (!ProjectCenterManager.isWithinMaidRange(maid, c.getBlockPos())) continue;
            double dist = maid.blockPosition().distSqr(c.getBlockPos());
            if (dist < bestDist)
            {
                bestDist = dist;
                best = m;
            }
        }
        return best;
    }

    // 行为停止：未完成的 DESTROY 强制收尾、FILL 尝试放置；脱离中心恢复 Home；清空状态
    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        MineInstance mine = mineById(level);
        if (mine != null)
        {
            if (currentTask != null)
            {
                BlockPos pos = currentTask.pos();
                if (currentTask.type() == MineTask.Type.DESTROY && !level.getBlockState(pos).isAir())
                {
                    mineAndCollect(level, maid, pos);
                    mine.completeWork(maid, currentTask);
                }
                else if (currentTask.type() == MineTask.Type.FILL && tryPlaceScaffold(level, maid, pos))
                {
                    mine.completeWork(maid, currentTask);
                }
                else
                {
                    mine.releaseWork(maid);
                }
            }
            if (ProjectCenterManager.getCenterOf(maid) == mine)
            {
                mine.leaveCenter(maid);
            }
        }
        maid.getNavigation().stop();
        mineId = null;
        currentTask = null;
        reachedTarget = false;
        navFailCount = 0;
        mineTimer = 0;
        waitTicks = 0;
        finished = false;
        scaffoldFetch = ScaffoldFetch.NONE;
    }
}
