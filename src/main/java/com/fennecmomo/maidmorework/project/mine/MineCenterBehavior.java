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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
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
    private static final double WALK_REACH_SQ = 16.0;   // 到达判定距离平方（4格）
    private static final double WALK_SPEED = 0.6;       // 导航速度倍率
    private static final int MAX_NAV_FAIL = 3;          // 导航失败次数上限，超过后强制到达
    private static final int FETCH_GROUP = 10;          // B2/B3：取消耗品一次一组（10个）
    private static final int WAIT_TICKS = 40;           // 阻塞等待节流（B2 仓库无货）
    private static final int BUBBLE_COOLDOWN = 120;     // 气泡冷却（tick）
    private static final float DIG_DIVISOR = 30f;       // 真挖掘：可正确掉落的分母（原版公式）
    private static final float DIG_WRONG_TOOL_DIVISOR = 100f; // 真挖掘：工具不正确的分母
    private static final long SCAFFOLD_KEY = 9540L;     // 垫脚气泡冷却 key
    private static final long LIGHT_KEY = 9541L;        // 光源气泡冷却 key
    private static final long PICKAXE_KEY = 9542L;      // 镐子气泡冷却 key

    private UUID mineId = null;             // 所属矿井实例 ID
    private MineTask currentTask = null;    // 当前任务
    private boolean reachedTarget = false;
    private int navFailCount = 0;
    private int waitTicks = 0;              // 阻塞等待倒计时（取不到物资时）
    private boolean finished = false;       // 矿井挖尽/失联 → 结束行为
    private boolean pendingDeposit = false; // B3：背包满（产物入包后检测）→ 待去仓库存放
    private float digProgress = 0f;         // 真挖掘进度（2026-09-04 拍板：原版公式逐 tick 累计）
    private BlockPos digPos = null;         // 正在挖的坐标（换目标即重置进度与裂纹）
    private FetchKind fetchKind = FetchKind.NONE; // 取货支线（缺垫脚/缺镐）
    private final Map<Long, Long> bubbleLastTick = new HashMap<>();

    private enum FetchKind
    {
        NONE, SCAFFOLD, PICKAXE
    }

    // 入库扫描项（B3 保留规则用）
    private record SlotRef(int slot, ItemResource res, int amount, ItemStack probe, Category cat) {}

    private enum Category
    {
        SCAFFOLD, LIGHT, OTHER
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
        waitTicks = 0;
        finished = false;
        pendingDeposit = false;
        digProgress = 0f;
        digPos = null;
        fetchKind = FetchKind.NONE;
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

        // B3：背包满（产物入包后检测）→ 去仓库存放
        if (pendingDeposit)
        {
            handleDeposit(level, maid, mine);
            return;
        }

        // FILL/REPLACE 缺垫脚 / DESTROY 缺镐 → 取货支线
        if (fetchKind == FetchKind.SCAFFOLD)
        {
            handleScaffoldFetch(level, maid, mine);
            return;
        }
        if (fetchKind == FetchKind.PICKAXE)
        {
            handlePickaxeFetch(level, maid, mine);
            return;
        }

        // 领任务
        if (currentTask == null)
        {
            MineTask task = mine.requestWork(maid);
            if (task == null) return;
            currentTask = task;
            reachedTarget = false;
            digProgress = 0f;
            digPos = null;
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
            // === DESTROY：真挖掘（原版公式），完成扣耐久 + 掉落进背包 ===
            case DESTROY ->
            {
                // 挥镐前校验主手（2026-09-04 拍板：徒手不挖）——背包没有则去仓库取
                if (!maid.getMainHandItem().is(ItemTags.PICKAXES))
                {
                    equipPickaxe(maid);
                    if (!maid.getMainHandItem().is(ItemTags.PICKAXES))
                    {
                        releaseCurrent(mine, maid);
                        fetchKind = FetchKind.PICKAXE;
                        return;
                    }
                }
                if (state.isAir())
                {
                    finishTask(mine, maid, task);
                    return;
                }
                maid.swing(maid.getUsedItemHand());
                if (progressDig(level, maid, target, state))
                {
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
                    fetchKind = FetchKind.SCAFFOLD;
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
                        fetchKind = FetchKind.SCAFFOLD;
                    }
                }
                else
                {
                    // 非垫脚实体块 → 真挖掘，挖完不交任务，下一 tick 走垫脚阶段
                    maid.swing(maid.getUsedItemHand());
                    progressDig(level, maid, target, state);
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
                    // 位上有方块 → 真挖掘，挖完下一 tick 走放置
                    maid.swing(maid.getUsedItemHand());
                    progressDig(level, maid, target, state);
                    return;
                }
                if (tryPlaceLight(level, maid, target))
                {
                    finishTask(mine, maid, task);
                }
                else if (!hasLightInInv(maid))
                {
                    // 包里灯用完了 → 释放，requestWork 会重新给出 FETCH_LIGHT（B2）
                    releaseCurrent(mine, maid);
                }
                else
                {
                    // 有灯但该位置放不了（无支撑面）→ 放弃该灯位，不无限重试
                    finishTask(mine, maid, task);
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
            fetchKind = FetchKind.SCAFFOLD;
        }
    }

    // 完成任务：解绑 + 记已处理，重置单任务状态
    // B3 拍板流程：干完这格活 → 产物已入包 → 检查无空格 → 待去仓库存放（不夹任何中间步骤）
    private void finishTask(MineInstance mine, EntityMaid maid, MineTask task)
    {
        mine.completeWork(maid, task);
        currentTask = null;
        reachedTarget = false;
        if (freeSlots(maid) == 0)
        {
            pendingDeposit = true;
        }
    }

    // ===================== 存取支线 =====================

    // B3 存矿：就近矿井方块，黑名单外全部入仓
    // 寻路连续失败 → 气泡提示 + 放弃本次存货继续干活（多余掉落自然落地），不死等
    private void handleDeposit(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        if (isNear(maid, mine.getBlockPos()))
        {
            depositToWarehouse(mine, maid, level);
            pendingDeposit = false;
            return;
        }
        if (!maid.getNavigation().isInProgress())
        {
            BlockPos walkTarget = findWalkTarget(level, mine.getBlockPos());
            boolean moved = walkTarget != null && maid.getNavigation().moveTo(
                    walkTarget.getX() + 0.5, walkTarget.getY(), walkTarget.getZ() + 0.5, WALK_SPEED);
            if (!moved && ++navFailCount >= MAX_NAV_FAIL)
            {
                showBubbleWithCooldown(maid, "无法返回仓库，继续干活", LIGHT_KEY);
                pendingDeposit = false;
                navFailCount = 0;
            }
        }
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
            fetchKind = FetchKind.NONE;
            return;
        }
        navigate(level, maid, mine.getBlockPos());
    }

    // B3 取镐：就近矿井方块，从仓库取 1 把镐并装备；仓库无镐 → 气泡 + 阻塞等待
    private void handlePickaxeFetch(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        if (isNear(maid, mine.getBlockPos()))
        {
            List<ItemStack> taken = mine.takeFromWarehouse(MineCenterBehavior::isPickaxeItem, 1);
            if (taken.isEmpty())
            {
                showBubbleWithCooldown(maid, "仓库缺镐子", PICKAXE_KEY);
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
            equipPickaxe(maid);
            fetchKind = FetchKind.NONE;
            return;
        }
        navigate(level, maid, mine.getBlockPos());
    }

    private static boolean isPickaxeItem(ItemStack stack)
    {
        return !stack.isEmpty() && stack.is(ItemTags.PICKAXES);
    }

    // 存矿（B3 拍板 2026-09-04 二次修正：保留规则）
    //   镐（工具）不存；垫脚类/光源类"每类随机保留一种、最多保留一格"，其余整类入仓
    //   （泥土/圆石既是垫脚也是挖矿产物，不再被黑名单整类误伤）；其余产物（含流体瓶）全部入库
    // 统一走合并容器读写（2026-09-04 修正：之前遍历基础背包，产在扩展背包里等于什么都没看见 → 站桩）
    private void depositToWarehouse(MineInstance mine, EntityMaid maid, ServerLevel level)
    {
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        List<SlotRef> entries = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack probe = new ItemStack(res.getItem(), 1);
            if (probe.is(ItemTags.PICKAXES)) continue;      // 工具不存
            Category cat = isScaffoldItem(probe) ? Category.SCAFFOLD
                    : isLightItem(probe) ? Category.LIGHT : Category.OTHER;
            entries.add(new SlotRef(i, res, (int) inv.getAmountAsLong(i), probe, cat));
        }
        // 每类随机保留一种、最多保留一格（B3 拍板修正）
        java.util.Set<Integer> keepSlots = new java.util.HashSet<>();
        for (Category cat : List.of(Category.SCAFFOLD, Category.LIGHT))
        {
            List<SlotRef> catSlots = new ArrayList<>();
            for (SlotRef e : entries)
            {
                if (e.cat() == cat) catSlots.add(e);
            }
            if (catSlots.isEmpty()) continue;
            List<net.minecraft.world.item.Item> types = new ArrayList<>();
            for (SlotRef e : catSlots)
            {
                if (!types.contains(e.probe().getItem())) types.add(e.probe().getItem());
            }
            net.minecraft.world.item.Item keepType = types.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(types.size()));
            for (SlotRef e : catSlots)
            {
                if (e.probe().getItem() == keepType)
                {
                    keepSlots.add(e.slot());                // 该类型只保留第一格
                    break;
                }
            }
        }
        // 待入库 = 全部扫描项 - 保留项
        List<ItemStack> toDeposit = new ArrayList<>();
        List<Integer> dSlots = new ArrayList<>();
        List<ItemResource> dRes = new ArrayList<>();
        List<Integer> dAmounts = new ArrayList<>();
        for (SlotRef e : entries)
        {
            if (keepSlots.contains(e.slot())) continue;
            toDeposit.add(new ItemStack(e.probe().getItem(), e.amount()));
            dSlots.add(e.slot());
            dRes.add(e.res());
            dAmounts.add(e.amount());
        }
        if (toDeposit.isEmpty()) return;
        mine.depositToWarehouse(toDeposit);
        for (int k = 0; k < dSlots.size(); k++)
        {
            try (Transaction tx = Transaction.openRoot())
            {
                inv.extract(dSlots.get(k), dRes.get(k), dAmounts.get(k), tx);
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

    // 放置光源（2026-09-04 崩溃修复：不再走需要 Player 的 BlockPlaceContext，手动放置）
    //   下方有实体 → 任意光源物品放默认状态（落地火把/灯笼/萤石）
    //   下方无实体但水平侧面有墙且为火把 → 放贴墙火把（WallTorchBlock.FACING 按支撑面设置）
    //   都不满足 → false（调用方放弃该灯位）
    private boolean tryPlaceLight(ServerLevel level, EntityMaid maid, BlockPos target)
    {
        BlockState below = level.getBlockState(target.below());
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack stack = new ItemStack(res.getItem(), 1);
            if (!isLightItem(stack)) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;

            BlockState placeState;
            if (below.isSolid())
            {
                placeState = bi.getBlock().defaultBlockState();
            }
            else
            {
                if (!stack.is(Items.TORCH)) continue;
                Direction facing = findWallFace(level, target);
                if (facing == null) continue;
                placeState = Blocks.WALL_TORCH.defaultBlockState()
                        .setValue(WallTorchBlock.FACING, facing);
            }
            level.setBlock(target, placeState, 3);
            try (Transaction tx = Transaction.openRoot())
            {
                inv.extract(i, res, 1, tx);
                tx.commit();
            }
            return true;
        }
        return false;
    }

    // 找水平方向上的实体墙（火把贴墙面），返回"从墙指向目标格"的朝向
    private static Direction findWallFace(ServerLevel level, BlockPos target)
    {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
        {
            BlockPos neighbor = target.relative(d);
            BlockState state = level.getBlockState(neighbor);
            if (!state.isAir() && state.isSolid())
            {
                return d.getOpposite();
            }
        }
        return null;
    }

    // 女仆背包（基础背包）里是否还有光源物品（SETLIGHT 放置失败时区分"没灯"与"放不了"）
    private static boolean hasLightInInv(EntityMaid maid)
    {
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            if (isLightItem(new ItemStack(res.getItem(), 1))) return true;
        }
        return false;
    }

    // 真挖掘（2026-09-04 拍板）：原版公式逐 tick 推进，速度自然关联工具与方块
    //   每 tick 进度 += 工具速度 ÷ 方块硬度 ÷ (可正确掉落 ? 30 : 100)
    //   破坏裂纹实时渲染（destroyBlockProgress 0~9），完成时扣 1 点耐久 + 收集掉落物
    // 返回 true = 挖掘完成（已破坏并收集）
    private boolean progressDig(ServerLevel level, EntityMaid maid, BlockPos pos, BlockState state)
    {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0)
        {
            return false;   // 基岩类（派发侧困难表兜底）
        }
        if (!pos.equals(digPos))
        {
            digPos = pos;
            digProgress = 0f;
        }
        ItemStack tool = maid.getMainHandItem();
        float speed = tool.getDestroySpeed(state);
        boolean canHarvest = tool.isCorrectToolForDrops(state);
        digProgress += speed / hardness / (canHarvest ? DIG_DIVISOR : DIG_WRONG_TOOL_DIVISOR);
        level.destroyBlockProgress(maid.getId(), pos, Math.min(9, (int) (digProgress * 10f)));
        if (digProgress < 1f) return false;

        // 完成：清除裂纹 → 收集掉落 → 扣 1 点耐久
        level.destroyBlockProgress(maid.getId(), pos, -1);
        mineAndCollect(level, maid, pos);
        maid.getMainHandItem().hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
        digProgress = 0f;
        digPos = null;
        return true;
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

    // 垫脚物品（§5 垫脚集合；2026-09-04 拍板：草方块显式计入）
    private static boolean isScaffoldItem(ItemStack stack)
    {
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        var block = bi.getBlock();
        return block == Blocks.GRASS_BLOCK
                || block.builtInRegistryHolder().is(BlockTags.DIRT)
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
        return block == Blocks.GRASS_BLOCK
                || block.builtInRegistryHolder().is(BlockTags.DIRT)
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

    // 装备镐子（§8 拍板：装备最好的镐）：对比对石头的挖掘速度选最快镐换到主手
    // 用 Transaction 保证背包操作的原子性（取出镐子+放入旧物品）
    private void equipPickaxe(EntityMaid maid)
    {
        BlockState stone = Blocks.STONE.defaultBlockState();
        ItemStack current = maid.getMainHandItem();
        float bestSpeed = current.is(ItemTags.PICKAXES) ? current.getDestroySpeed(stone) : 0f;
        int bestSlot = -1;
        ItemResource bestRes = null;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack probe = new ItemStack(res.getItem(), 1);
            if (!probe.is(ItemTags.PICKAXES)) continue;
            float speed = probe.getDestroySpeed(stone);
            if (speed > bestSpeed)
            {
                bestSpeed = speed;
                bestSlot = i;
                bestRes = res;
            }
        }
        if (bestSlot < 0 || bestRes == null) return;
        ItemStack oldHand = maid.getMainHandItem();
        try (Transaction tx = Transaction.openRoot())
        {
            inv.extract(bestSlot, bestRes, 1, tx);
            if (!oldHand.isEmpty())
            {
                inv.insert(bestSlot, ItemResource.of(oldHand), oldHand.getCount(), tx);
            }
            tx.commit();
        }
        maid.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(bestRes.getItem(), 1));
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

    // 任务未完成释放：解绑子任务（坐标回池子）+ 清除破坏裂纹
    private void releaseCurrent(MineInstance mine, EntityMaid maid)
    {
        if (currentTask != null)
        {
            mine.releaseWork(maid);
            if (digPos != null)
            {
                maid.level().destroyBlockProgress(maid.getId(), digPos, -1);
            }
        }
        currentTask = null;
        digProgress = 0f;
        digPos = null;
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
        waitTicks = 0;
        finished = false;
        pendingDeposit = false;
        digProgress = 0f;
        digPos = null;
        fetchKind = FetchKind.NONE;
    }
}
