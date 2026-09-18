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
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
    // 诊断日志（2026-09-04 测试期临时接入，问题定位后移除）
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(MineCenterBehavior.class);
    private static final double WALK_REACH_SQ = 16.0;   // 到达判定距离平方（4格）
    private static final double WALK_SPEED = 0.6;       // 导航速度倍率
    private static final int MAX_NAV_FAIL = 10;         // 导航失败次数上限（熔断：视为到达继续执行，不取消任务）
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
    private long waterLogTick = 0;          // 水中诊断：上次打印时间（2026-09-04 临时接入）
    private int navIssueWindow = 0;         // 水中诊断：窗口内 moveTo 下发次数
    private int navFailWindow = 0;          // 水中诊断：窗口内 moveTo 失败次数
    private int waitTicks = 0;              // 阻塞等待倒计时（取不到物资时）
    private boolean finished = false;       // 矿井失联 → 结束行为（挖尽为待机，不结束）
    private boolean exhaustedLogged = false; // 挖尽待机日志与放权只执行一次
    private boolean pendingDeposit = false; // B3：背包满（产物入包后检测）→ 待去仓库存放
    private float digProgress = 0f;         // 真挖掘进度（2026-09-04 拍板：原版公式逐 tick 累计）
    private BlockPos digPos = null;         // 正在挖的坐标（换目标即重置进度与裂纹）
    private FetchKind fetchKind = FetchKind.NONE; // 取货支线（缺垫脚/缺镐）
    private final Map<Long, Long> bubbleLastTick = new HashMap<>();

    private enum FetchKind
    {
        NONE, SCAFFOLD, TOOL
    }

    // 取工具支线规格（§8 派发侧已确认仓库有货，这里只管去取）
    private record ToolFetchSpec(BlockPos taskPos, boolean recommended) {}

    // 自身工具候选（主手 slot<0，背包槽位≥0）
    private record ToolCandidate(int slot, ItemResource res, ItemStack stack) {}

    private ToolFetchSpec toolFetchSpec = null;

    // 当前阶段名（阶段切换日志用）
    private String currentState = "未启动";
    private String lastDetail = "";

    // 阶段切换日志：女仆=id 旧阶段→新阶段 明细（2026-09-04 拍板：所有切换点全量打点）
    // 同阶段同明细的连续调用不重复打印（如暂停等待的逐 tick 进入）
    private void logTransition(EntityMaid maid, String to, String detail)
    {
        String from = currentState;
        if (from.equals(to) && lastDetail.equals(detail)) return;
        currentState = to;
        lastDetail = detail;
        LOGGER.info("[MineDebug] 女仆={} {}→{} {}", shortId(maid), from, to, detail);
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
        MineInstance mine = resolveMine(maid);
        return mine != null && !mine.isExhausted();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        if (finished) return false;
        if (!maid.isHomeModeEnable() && maid.canBrainMoving()) return false;
        MineInstance mine = resolveMine(maid);
        if (mine != null && mine.isExhausted())
        {
            mine.releaseAllMembers(level);
            return false;
        }
        return mine != null;
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
        logTransition(maid, "挖矿循环", "加入中心 id=" + mine.getId().toString().substring(0, 8));
        equipPickaxe(maid);
        mineId = mine.getId();
        mine.joinCenter(maid);
        currentTask = null;
        reachedTarget = false;
        navFailCount = 0;
        waitTicks = 0;
        finished = false;
        exhaustedLogged = false;
        pendingDeposit = false;
        digProgress = 0f;
        digPos = null;
        fetchKind = FetchKind.NONE;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        MineInstance mine = mineById(level);
        if (mine == null)
        {
            finished = true;
            logTransition(maid, "结束", "矿井失联");
            return;
        }

        // 水中抖动诊断（2026-09-04 临时接入：定位抖动源后移除）
        logWaterDiagnostic(level, maid);

        // 卡死熔断（2026-09-04 拍板：采样由矿井侧承担——每 5 秒一次，只有矿井方块状态绝对稳定）：
        // 矿井判定"10 秒无位移且未施工"后下发一次性通知，此处传送并重置导航状态，从中心重新寻路
        if (mine.consumeStuckRecovery(maid.getUUID()))
        {
            LOGGER.info("[MineDebug] 女仆={} 卡死熔断→传送至矿井方块旁", shortId(maid));
            teleportNearMine(level, maid, mine);
            currentTask = null;
            reachedTarget = false;
            navFailCount = 0;
            fetchKind = FetchKind.NONE;
            return;
        }

        if (mine.isExhausted() && !mine.hasPendingTunnels())
        {
            mine.releaseAllMembers(level);
            finished = true;
            logTransition(maid, "结束", "矿井已挖尽且矿道全完工，释放全部成员");
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
            logTransition(maid, "存矿", "背包满");
            handleDeposit(level, maid, mine);
            return;
        }

        // FILL/REPLACE 缺垫脚 / 工具流程需仓库供货 → 取货支线
        if (fetchKind == FetchKind.SCAFFOLD)
        {
            logTransition(maid, "取垫脚", "缺垫脚方块");
            handleScaffoldFetch(level, maid, mine);
            return;
        }
        if (fetchKind == FetchKind.TOOL)
        {
            logTransition(maid, "取工具", toolFetchSpec != null && toolFetchSpec.recommended() ? "取推荐工具" : "取必要工具");
            handleToolFetch(level, maid, mine);
            return;
        }

        // 层暂停（2026-09-04 拍板）：无可领任务时到工程中心旁等待，恢复后自动继续
        if (mine.isLayerPaused() && currentTask == null)
        {
            logTransition(maid, "暂停等待", "层不可处理方块超过10%，等待补货");
            if (!isNear(maid, mine.getBlockPos())) travelToMineBlock(level, maid, mine);
            return;
        }

        // 挖尽 = 待机（2026-09-04 拍板）：保留成员身份不轰出中心，走到矿井旁放宽移动范围闲逛；
        // 封存检查（10s 周期）发现的维护任务（落入矿道的沙子/被打掉的灯）仍会派发处理
        if (mine.isExhausted() && currentTask == null)
        {
            if (!exhaustedLogged)
            {
                logTransition(maid, "挖尽待机", "保留成员身份，放宽移动范围");
                maid.setHomeTo(mine.getBlockPos(), 32);
                exhaustedLogged = true;
            }
            if (!isNear(maid, mine.getBlockPos())) travelToMineBlock(level, maid, mine);
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
            logTransition(maid, "执行任务", task.type() + " @ " + task.pos().toShortString());
            // 控制器传送（2026-09-04 拍板）：从中心出发干活——已有控制方块且人在中心附近 →
            // 直接传送到离任务最近的控制器平台，再从那里走过去（任务在中心附近则跳过）
            if (!isNear(maid, task.pos()) && isNear(maid, mine.getBlockPos()))
            {
                BlockPos hub = mine.controlNearest(task.pos());
                if (hub != null)
                {
                    teleportNearHub(level, maid, hub);
                }
            }
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
                if (state.isAir())
                {
                    finishTask(mine, maid, task);
                    return;
                }
                if (!state.getFluidState().isEmpty())
                {
                    // 流体兜底（2026-09-04 修正：历史遗留的流体 DESTROY 条目，如困难表）→
                    // 走流体处理，绝不做慢挖（水硬度 100 且邻水回流，会永远挖不完）
                    handleFluidCell(level, maid, mine, task, target, state);
                    return;
                }
                if (progressDig(level, maid, mine, task, target, state))
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
                    // 缺垫脚 → 释放任务 + 取货支线（B3，静默执行，仓库无货才在取货处冒泡）
                    releaseCurrent(mine, maid);
                    fetchKind = FetchKind.SCAFFOLD;
                    reachedTarget = false;
                }
            }

            // === REPLACE：非垫脚实体块挖掉再垫（§5）；流体清掉（§9） ===
            case REPLACE ->
            {
                if (!state.getFluidState().isEmpty())
                {
                    handleFluidCell(level, maid, mine, task, target, state);
                    return;
                }
                if (state.isAir())
                {
                    if (tryPlaceScaffold(level, maid, target))
                    {
                        finishTask(mine, maid, task);
                    }
                    else
                    {
                        releaseCurrent(mine, maid);
                        fetchKind = FetchKind.SCAFFOLD;
                        reachedTarget = false;
                    }
                    return;
                }
                if (isScaffoldState(state) || !state.is(Tags.Blocks.ORES))
                {
                    // 已是垫脚（如被其他女仆先垫）/ 非矿物实体（砂岩/石头等无需替换，2026-09-04 拍板）→ 直接完成
                    finishTask(mine, maid, task);
                    return;
                }
                // 保留位矿物 → 真挖掘回收，挖完不交任务，下一 tick 走垫脚阶段
                progressDig(level, maid, mine, task, target, state);
            }

            // === SETLIGHT：位上有光源视为完成；有方块先挖；否则放置 ===
            case SETLIGHT ->
            {
                if (state.getLightEmission() > 0)
                {
                    finishTask(mine, maid, task);
                    return;
                }
                if (!state.getFluidState().isEmpty())
                {
                    // 光源位流体（2026-09-04 扩展：先前漏处理，光源位是水源时水永远清不掉）：
                    // 清掉（源→收瓶）后释放任务——下一轮 SETLIGHT 重派时格已空气即可放火把；
                    // 层末尾其他水源已清完，不会回流（若仍有渗流，下轮再清一次，即时操作无成本）
                    if (state.getFluidState().isSource())
                    {
                        clearFluidAndCollectBottle(level, maid, target, state);
                    }
                    else
                    {
                        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                    }
                    releaseCurrent(mine, maid);
                    return;
                }
                if (!state.isAir())
                {
                    // 位上有方块 → 真挖掘，挖完下一 tick 走放置
                    progressDig(level, maid, mine, task, target, state);
                    return;
                }
                if (tryPlaceLight(level, maid, mine, target))
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

            // === SETLIGHT_FLOOR：矿道地面火把（2026-09-04 拍板：站立火把插在地板上） ===
            //   流程同 SETLIGHT：已有光源→完成；有水→清；有方块→挖开；空气→放置；没灯→释放重派取货
            case SETLIGHT_FLOOR ->
            {
                if (state.getLightEmission() > 0)
                {
                    finishTask(mine, maid, task);
                    return;
                }
                if (!state.getFluidState().isEmpty())
                {
                    if (state.getFluidState().isSource())
                    {
                        clearFluidAndCollectBottle(level, maid, target, state);
                    }
                    else
                    {
                        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                    }
                    releaseCurrent(mine, maid);
                    return;
                }
                if (!state.isAir())
                {
                    progressDig(level, maid, mine, task, target, state);
                    return;
                }
                if (tryPlaceFloorTorch(level, maid, target))
                {
                    finishTask(mine, maid, task);
                }
                else
                {
                    // 没灯或脚下没支撑（等地板补洞）→ 释放重派
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
                    logTransition(maid, "等待", "仓库无光源");
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

            // === PLACE_CONTROL：空手生成矿道层控制方块（2026-09-04 拍板：非物品、无消耗） ===
            case PLACE_CONTROL ->
            {
                if (state.getBlock() == MineCenterRegistration.MINE_LAYER_CONTROL_BLOCK.get())
                {
                    finishTask(mine, maid, task);            // 已是控制方块（含创造模式挖除后的重派）
                    return;
                }
                placeLayerControl(level, maid, mine, target);
                finishTask(mine, maid, task);
            }
        }
    }

    // 清掉源流体并收集对应流体瓶（§9）：置空气 + 入包（塞不下落地）
    private void clearFluidAndCollectBottle(ServerLevel level, EntityMaid maid, BlockPos target, BlockState state)
    {
        Fluid fluid = getFluidFromBlock(state);
        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
        if (fluid == null) return;
        ResourceKey<Fluid> fluidKey = BuiltInRegistries.FLUID.getResourceKey(fluid).orElse(null);
        if (fluidKey == null) return;
        ItemStack bottle = new ItemStack(MineCenterRegistration.FLUID_BOTTLE.get(), 1);
        FluidBottleItem.setFluid(bottle, fluidKey);
        ItemStack leftover = addToInventory(maid, bottle);
        if (!leftover.isEmpty())
        {
            Block.popResource(level, target, leftover);
        }
    }

    // 流体处理（§9 修正 2026-09-04）：源流体 → 清掉+收流体瓶+原位放塞子；流动流体 → 直接完成
    private void handleFluidCell(ServerLevel level, EntityMaid maid, MineInstance mine,
                                 MineTask task, BlockPos target, BlockState state)
    {
        if (!state.getFluidState().isSource())
        {
            // 流动流体：无实体可处理，等源被清后自然干涸
            finishTask(mine, maid, task);
            return;
        }
        clearFluidAndCollectBottle(level, maid, target, state);
        // 无论保留位还是非保留位，取完水都立即放一个垫脚方块堵住（2026-09-04 拍板）：
        //   保留位 = 永久墙体；非保留位 = 临时塞子——防止邻水回流并在该格重新形成水源，
        //   矿井 10s 周期核查发现塞子（封存为"应空"）后会滞后把它挖掉，
        //   那时邻位水源已被清完，不会回流（无塞子时"取水→回流→再取"极不稳定）
        if (tryPlaceScaffold(level, maid, target))
        {
            finishTask(mine, maid, task);
        }
        else
        {
            releaseCurrent(mine, maid);
            fetchKind = FetchKind.SCAFFOLD;
            reachedTarget = false;
        }
    }

    // 完成任务：解绑 + 记已处理，重置单任务状态
    // B3 拍板流程：干完这格活 → 产物已入包 → 检查无空格 → 待去仓库存放（不夹任何中间步骤）
    private void finishTask(MineInstance mine, EntityMaid maid, MineTask task)
    {
        mine.completeWork(maid, task);
        currentTask = null;
        reachedTarget = false;
        logTransition(maid, "挖矿循环", "完成 " + task.type() + " @ " + task.pos().toShortString());
        if (freeSlots(maid) == 0)
        {
            pendingDeposit = true;
            reachedTarget = false;
            logTransition(maid, "存矿", "背包满");
        }
    }

    // ===================== 存取支线 =====================

    // B3 存矿：就近矿井方块，黑名单外全部入仓
    // 到达判定：isNear 或导航熔断（reachedTarget）——熔断后按"已到达"继续执行，不取消任务
    private void handleDeposit(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        if (isNear(maid, mine.getBlockPos()) || reachedTarget)
        {
            depositToWarehouse(mine, maid, level);
            pendingDeposit = false;
            reachedTarget = false;
            logTransition(maid, "挖矿循环", "存矿完成");
            return;
        }
        travelToMineBlock(level, maid, mine);
    }

    // B3 取垫脚：就近矿井方块，从仓库取一组垫脚方块（到达判定同存矿）
    private void handleScaffoldFetch(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        if (isNear(maid, mine.getBlockPos()) || reachedTarget)
        {
            List<ItemStack> taken = mine.takeFromWarehouse(MineCenterBehavior::isScaffoldItem, FETCH_GROUP);
            if (taken.isEmpty())
            {
                showBubbleWithCooldown(maid, "仓库缺垫脚方块", SCAFFOLD_KEY);
                logTransition(maid, "等待", "仓库无垫脚方块");
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
            reachedTarget = false;
            logTransition(maid, "挖矿循环", "取垫脚完成");
            return;
        }
        travelToMineBlock(level, maid, mine);
    }

    // B3 取工具（§8 七步流程步骤4/7）：就近矿井方块，按规格从仓库取 1 把并装备；无货 → 气泡 + 阻塞等待
    private void handleToolFetch(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        if (toolFetchSpec == null)
        {
            fetchKind = FetchKind.NONE;
            return;
        }
        if (isNear(maid, mine.getBlockPos()) || reachedTarget)
        {
            BlockState targetState = level.getBlockState(toolFetchSpec.taskPos());
            java.util.function.Predicate<ItemStack> predicate = toolFetchSpec.recommended()
                    ? stack -> matchesType(stack, targetState)
                    : stack -> !stack.isEmpty() && stack.isCorrectToolForDrops(targetState);
            List<ItemStack> taken = mine.takeFromWarehouse(predicate, 1);
            if (taken.isEmpty())
            {
                // 仓库竞态（派发时有、取时没了）→ 释放重派，派发侧可行性检查会把它送进层困难表
                LOGGER.info("[MineDebug] 取工具竞态落空 @ {}", toolFetchSpec.taskPos().toShortString());
                releaseCurrent(mine, maid);
                toolFetchSpec = null;
                fetchKind = FetchKind.NONE;
                reachedTarget = false;
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
            equipPickaxe(maid);     // 先粗装，下次挖掘前装备流程精调
            toolFetchSpec = null;
            fetchKind = FetchKind.NONE;
            reachedTarget = false;
            logTransition(maid, "挖矿循环", "取工具完成");
            return;
        }
        travelToMineBlock(level, maid, mine);
    }

    // ===================== §8 装备流程（2026-09-04 终版：可行性已在派发侧确认） =====================

    //   顺序：自身推荐 → 装备开挖 | 仓库推荐 → 取货支线 | 非必须 → 空手 | 自身必要 → 装备 | 仓库必要 → 取货支线
    //   （五级可行性在派发侧已确认任一成立，本流程只负责"用上合适的工具"）
    // 返回 true = 可以开挖；false = 已设取货支线（任务保持绑定，fetchKind=TOOL 接管）
    private boolean equipFor(ServerLevel level, EntityMaid maid, MineInstance mine,
                             MineTask task, BlockPos pos, BlockState state)
    {
        // 自身推荐
        ToolCandidate best = bestToolInInv(maid, state, true);
        if (best != null)
        {
            equipCandidate(maid, best);
            LOGGER.info("[MineDebug] 女仆={} 装备流程(自身推荐) 装备 {} @ {}",
                    shortId(maid), best.stack().getItem(), state.getBlock());
            return true;
        }
        // 仓库推荐
        if (mine.countWarehouse(stack -> matchesType(stack, state)) > 0)
        {
            toolFetchSpec = new ToolFetchSpec(pos, true);
            fetchKind = FetchKind.TOOL;
            reachedTarget = false;
            return false;
        }
        // 非必须 → 空手挖（主手收回背包）
        if (!state.requiresCorrectToolForDrops())
        {
            stashMainHand(maid);
            LOGGER.info("[MineDebug] 女仆={} 装备流程(非必须空手) {} @ {}",
                    shortId(maid), state.getBlock(), pos.toShortString());
            return true;
        }
        // 自身必要（能正确掉落即可，不限类型）
        best = bestToolInInv(maid, state, false);
        if (best != null)
        {
            equipCandidate(maid, best);
            LOGGER.info("[MineDebug] 女仆={} 装备流程(自身必要) 装备 {} @ {}",
                    shortId(maid), best.stack().getItem(), state.getBlock());
            return true;
        }
        // 仓库必要
        if (mine.countWarehouse(stack -> !stack.isEmpty() && stack.isCorrectToolForDrops(state)) > 0)
        {
            toolFetchSpec = new ToolFetchSpec(pos, false);
            fetchKind = FetchKind.TOOL;
            reachedTarget = false;
            return false;
        }
        // 五级全空（SETLIGHT 先挖段等未预检路径 / 仓库竞态）→ 真缺：气泡 + 上报困难表 + 缺工具记录
        LOGGER.info("[MineDebug] 女仆={} 装备流程(五级全空→困难表) {} @ {}",
                shortId(maid), state.getBlock(), pos.toShortString());
        showBubbleWithCooldown(maid, "缺少工具，无法继续挖掘", PICKAXE_KEY);
        mine.recordMissingTool(state);
        mine.addHardTask(new MineTask(pos, task.type()));
        releaseCurrent(mine, maid);
        return false;
    }

    private static String shortId(EntityMaid maid)
    {
        return maid.getUUID().toString().substring(0, 8);
    }

    // 自身（主手+背包）中满足判定的最优工具（对目标方块速度最高）
    // typeMatchedOnly=true → 类型标签匹配且能正确掉落（推荐级）；false → 能正确掉落即可（必要级）
    private ToolCandidate bestToolInInv(EntityMaid maid, BlockState state, boolean typeMatchedOnly)
    {
        ToolCandidate best = null;
        float bestSpeed = -1f;
        ItemStack main = maid.getMainHandItem();
        if (matchesTool(main, state, typeMatchedOnly))
        {
            bestSpeed = main.getDestroySpeed(state);
            best = new ToolCandidate(-1, null, main);
        }
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack probe = res.toStack(1);
            if (!matchesTool(probe, state, typeMatchedOnly)) continue;
            float speed = probe.getDestroySpeed(state);
            if (speed > bestSpeed)
            {
                bestSpeed = speed;
                best = new ToolCandidate(i, res, probe);
            }
        }
        return best;
    }

    private static boolean matchesTool(ItemStack stack, BlockState state, boolean typeMatchedOnly)
    {
        if (stack.isEmpty()) return false;
        if (typeMatchedOnly && !matchesType(stack, state)) return false;
        return stack.isCorrectToolForDrops(state);
    }

    // 方块期望的工具类型（按原版"可挖掘"标签）：镐/锹/斧/锄
    private static boolean matchesType(ItemStack stack, BlockState state)
    {
        return (state.is(BlockTags.MINEABLE_WITH_PICKAXE) && stack.is(ItemTags.PICKAXES))
                || (state.is(BlockTags.MINEABLE_WITH_SHOVEL) && stack.is(ItemTags.SHOVELS))
                || (state.is(BlockTags.MINEABLE_WITH_AXE) && stack.is(ItemTags.AXES))
                || (state.is(BlockTags.MINEABLE_WITH_HOE) && stack.is(ItemTags.HOES));
    }

    // 装备候选工具：主手已是最优则不动；背包工具换到主手（旧主手放回原槽，Transaction 原子）
    private void equipCandidate(EntityMaid maid, ToolCandidate candidate)
    {
        if (candidate.slot() < 0) return;   // 主手已是最优
        ItemStack oldHand = maid.getMainHandItem();
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        try (Transaction tx = Transaction.openRoot())
        {
            inv.extract(candidate.slot(), candidate.res(), 1, tx);
            if (!oldHand.isEmpty())
            {
                inv.insert(candidate.slot(), ItemResource.of(oldHand), oldHand.getCount(), tx);
            }
            tx.commit();
        }
        maid.setItemSlot(EquipmentSlot.MAINHAND, candidate.stack().copy());
    }

    // 空手挖掘（§8 步骤5）：主手工具收回背包
    private void stashMainHand(EntityMaid maid)
    {
        ItemStack main = maid.getMainHandItem();
        if (main.isEmpty()) return;
        ItemStack leftover = addToInventory(maid, main);
        maid.setItemSlot(EquipmentSlot.MAINHAND, leftover.isEmpty() ? ItemStack.EMPTY : leftover);
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
            navIssueWindow++;
            BlockPos walkTarget = findWalkTarget(level, target);
            if (walkTarget == null)
            {
                navFailCount++;
                navFailWindow++;
                if (navFailCount >= MAX_NAV_FAIL || distSq < 25.0)
                {
                    LOGGER.info("[MineDebug] 女仆={} 导航熔断→视为到达 @ {}",
                            shortId(maid), target.toShortString());
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
                navFailWindow++;
                if (navFailCount >= MAX_NAV_FAIL || distSq < 25.0)
                {
                    LOGGER.info("[MineDebug] 女仆={} 导航熔断→视为到达 @ {}",
                            shortId(maid), target.toShortString());
                    reachedTarget = true;
                    navFailCount = 0;
                }
            }
        }
    }

    // 水中抖动诊断（2026-09-04 临时接入：定位抖动源后移除）
    // 每秒一条：位置/三轴速度（抖幅）、水深/游泳/姿态、当前导航对象类名（看 TLM 是否在换导航）、
    // TLM 游泳系统状态（wantToSwim/isGoingToBreath/isReadyToLand/泳目标）、
    // 我方 moveTo 窗口统计（下发/失败次数）
    private void logWaterDiagnostic(ServerLevel level, EntityMaid maid)
    {
        if (!maid.isInWater())
        {
            navIssueWindow = 0;
            navFailWindow = 0;
            return;
        }
        long now = level.getGameTime();
        if (now - waterLogTick < 20) return;
        waterLogTick = now;
        var swim = maid.getSwimManager();
        Vec3 vel = maid.getDeltaMovement();
        LOGGER.info("[MineDebug] 水中诊断 女仆={} pos={} vel=({}, {}, {}) 水深={} 游泳={} pose={} "
                        + "导航={} 导航中={} 水面={} 泳态[want={} breath={} land={} target={}] "
                        + "moveTo窗口[下发={} 失败={}] 到达={} 任务={}",
                shortId(maid), maid.blockPosition().toShortString(),
                String.format("%.3f", vel.x), String.format("%.3f", vel.y), String.format("%.3f", vel.z),
                String.format("%.2f", maid.getFluidHeight(FluidTags.WATER)),
                maid.isSwimming(), maid.getPose(),
                maid.getNavigation().getClass().getSimpleName(), maid.getNavigation().isInProgress(),
                maid.getNavigationManager().isWaterSurface(maid.blockPosition()),
                swim.wantToSwim(), swim.isGoingToBreath(), swim.isReadyToLand(), swim.getSwimTarget(),
                navIssueWindow, navFailWindow, reachedTarget,
                currentTask == null ? "无" : currentTask.type() + "@" + currentTask.pos().toShortString());
        navIssueWindow = 0;
        navFailWindow = 0;
    }

    private boolean isNear(EntityMaid maid, BlockPos pos)
    {
        return maid.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= WALK_REACH_SQ;
    }

    // 传送到矿井方块旁的安全落点（优先正上方可站立处，其次四周相邻；兜底正上方）
    // 多人场景优先落"没被其他女仆占着"的格子分散开（2026-09-04），带低音量传送音效
    private static void teleportNearMine(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        BlockPos base = mine.getBlockPos();
        BlockPos target = null;
        for (BlockPos cand : new BlockPos[]{
                base.above(), base.above(2),
                base.north(), base.south(), base.east(), base.west()})
        {
            if (!level.getBlockState(cand).isAir() || !level.getBlockState(cand.below()).isSolid()) continue;
            if (target == null) target = cand;
            AABB cell = new AABB(cand.getX(), cand.getY(), cand.getZ(),
                    cand.getX() + 1, cand.getY() + 1, cand.getZ() + 1);
            if (level.getEntitiesOfClass(EntityMaid.class, cell, e -> e != maid).isEmpty())
            {
                target = cand;
                break;
            }
        }
        if (target == null)
        {
            target = base.above();
        }
        maid.getNavigation().stop();
        maid.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        level.playSound(null, maid.getX(), maid.getY(), maid.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.3F, 1.0F);
    }

    // 控制器传送：落到指定控制器平台上方一格（兜底：周围可站立格；再兜底正上方）
    private static void teleportNearHub(ServerLevel level, EntityMaid maid, BlockPos hub)
    {
        BlockPos target = null;
        if (level.getBlockState(hub.above()).isAir() && level.getBlockState(hub).isSolid())
        {
            target = hub.above();
        }
        else
        {
            for (BlockPos cand : new BlockPos[]{
                    hub.north(), hub.south(), hub.east(), hub.west(),
                    hub.above(2), hub.north().above(), hub.south().above(),
                    hub.east().above(), hub.west().above()})
            {
                if (level.getBlockState(cand).isAir() && level.getBlockState(cand.below()).isSolid())
                {
                    target = cand;
                    break;
                }
            }
        }
        if (target == null) target = hub.above();
        maid.getNavigation().stop();
        maid.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        level.playSound(null, maid.getX(), maid.getY(), maid.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.3F, 1.0F);
        LOGGER.info("[MineDebug] 控制器传送 女仆={} → 控制器 {} 平台", shortId(maid), hub.toShortString());
    }

    // 去矿井方块（存矿/取货/暂停等待/挖尽等待的统一入口，2026-09-04 拍板）：
    //   无控制方块 或 女仆 Y 高于最高控制方块 → 正常走路；
    //   否则先走到离她最近的控制方块旁，再传送到矿井方块旁
    // （传送是主动技、由状态门控触发，不设冷却）
    private void travelToMineBlock(ServerLevel level, EntityMaid maid, MineInstance mine)
    {
        Integer topY = mine.controlTopY();
        if (topY == null || maid.getY() > topY)
        {
            navigate(level, maid, mine.getBlockPos());
            return;
        }
        BlockPos hub = mine.controlNearest(maid.blockPosition());
        if (hub == null)
        {
            navigate(level, maid, mine.getBlockPos());
            return;
        }
        if (isNear(maid, hub))
        {
            LOGGER.info("[MineDebug] 控制器传送 女仆={} → 矿井方块", shortId(maid));
            teleportNearMine(level, maid, mine);
            reachedTarget = false;
            return;
        }
        navigate(level, maid, hub);
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

    // 破坏后下方安全检查（2026-09-04 精简：不再自动垫脚）
    //   下方垫脚是"挖4放4"瀑布的根源——下方格属于下层隧道/待挖区域，自动垫会污染封存
    //   并被下层重复挖掘（挖了白挖）；下层自会按规划处理该格，楼梯是官方通行路径。
    //   保留：源流体 → 清除 + 流体瓶（§9）；基岩 → 跳过
    private void checkBelowSafety(ServerLevel level, EntityMaid maid, BlockPos brokenPos)
    {
        BlockPos below = brokenPos.below();
        BlockState belowState = level.getBlockState(below);
        if (!isFluidSource(belowState)) return;
        Fluid fluid = getFluidFromBlock(belowState);
        level.setBlock(below, Blocks.AIR.defaultBlockState(), 3);
        if (fluid != null)
        {
            ResourceKey<Fluid> fluidKey = BuiltInRegistries.FLUID.getResourceKey(fluid).orElse(null);
            if (fluidKey != null)
            {
                ItemStack bottle = new ItemStack(MineCenterRegistration.FLUID_BOTTLE.get(), 1);
                FluidBottleItem.setFluid(bottle, fluidKey);
                ItemStack leftover = addToInventory(maid, bottle);
                if (!leftover.isEmpty())
                {
                    Block.popResource(level, brokenPos, leftover);
                }
            }
        }
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

    // 放置矿道层控制方块（2026-09-04 拍板：空手生成、无物品消耗）：
    // 覆盖原垫脚方块（掉落物回收进女仆背包，塞不下落地），并给方块实体写入所属矿井 UUID
    private void placeLayerControl(ServerLevel level, EntityMaid maid, MineInstance mine, BlockPos target)
    {
        BlockState old = level.getBlockState(target);
        if (!old.isAir())
        {
            List<ItemStack> drops = Block.getDrops(old, level, target,
                    level.getBlockEntity(target), maid, maid.getMainHandItem());
            for (ItemStack drop : drops)
            {
                if (drop.isEmpty()) continue;
                ItemStack leftover = addToInventory(maid, drop);
                if (!leftover.isEmpty())
                {
                    Block.popResource(level, target, leftover);
                }
            }
        }
        level.setBlock(target, MineCenterRegistration.MINE_LAYER_CONTROL_BLOCK.get().defaultBlockState(), 3);
        if (level.getBlockEntity(target) instanceof MineLayerControlBlockEntity be)
        {
            be.bind(mine.getId());
        }
        LOGGER.info("[MineDebug] 女仆={} 放置矿道层控制方块 @ {}", shortId(maid), target.toShortString());
    }

    // 放置矿道地面火把（2026-09-04 拍板：站立火把；放置前原版 canSurvive 校验脚下支撑）
    private boolean tryPlaceFloorTorch(ServerLevel level, EntityMaid maid, BlockPos target)
    {
        BlockState placeState = Blocks.TORCH.defaultBlockState();
        if (!placeState.canSurvive(level, target)) return false;
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack stack = new ItemStack(res.getItem(), 1);
            if (!stack.is(Items.TORCH)) continue;
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

    // 放置光源（2026-09-04 拍板：只认火把；朝向按灯位几何推算支撑墙）
    //   FACING = 支撑方向（火把格指向支撑墙），放置前原版 canSurvive 校验，
    //   不活则试反方向（语义翻转自动纠正），仍不活 → 放弃该灯位（不再强行 setBlock 插空气）
    private boolean tryPlaceLight(ServerLevel level, EntityMaid maid, MineInstance mine, BlockPos target)
    {
        Direction support = mine.supportDirection(target);
        // 统一走合并容器（2026-09-04 修正：火把常在扩展背包，扫基础背包找不到 → 永远放不上）
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack stack = new ItemStack(res.getItem(), 1);
            if (!stack.is(Items.TORCH)) continue;

            BlockState placeState = survivingWallState(level, target, support);
            if (placeState == null) return false;
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

    // FACING 推算（指向支撑墙）+ 原版 canSurvive 兜底：不活则试反方向（语义翻转自动纠正）
    private static BlockState survivingWallState(ServerLevel level, BlockPos target, Direction support)
    {
        BlockState primary = Blocks.WALL_TORCH.defaultBlockState()
                .setValue(WallTorchBlock.FACING, support);
        if (primary.canSurvive(level, target)) return primary;
        BlockState flipped = Blocks.WALL_TORCH.defaultBlockState()
                .setValue(WallTorchBlock.FACING, support.getOpposite());
        if (flipped.canSurvive(level, target)) return flipped;
        return null;
    }

    // 女仆背包里是否还有光源物品（SETLIGHT 放置失败时区分"没灯"与"放不了"；合并容器读写）
    private static boolean hasLightInInv(EntityMaid maid)
    {
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
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
    //   新目标第一 tick 先跑装备流程（未就绪则不动手，取货支线接管）
    // 返回 true = 挖掘完成（已破坏并收集）
    private boolean progressDig(ServerLevel level, EntityMaid maid, MineInstance mine, MineTask task,
                                BlockPos pos, BlockState state)
    {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0)
        {
            return false;   // 基岩类（派发侧困难表兜底）
        }
        if (!pos.equals(digPos))
        {
            // 可行性已在派发侧确认，这里只跑装备流程（自身/仓库取用）
            if (!equipFor(level, maid, mine, task, pos, state))
            {
                digPos = null;
                digProgress = 0f;
                return false;       // 已设取货支线（任务保持绑定）
            }
            digPos = pos;
            digProgress = 0f;
        }
        // 就绪后才挥手/累计进度
        maid.swing(maid.getUsedItemHand());
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

    // 光源物品（B2 判定 2026-09-04 修正：只认火把——灯笼无法贴墙挂放，待后续单独立项支持）
    private static boolean isLightItem(ItemStack stack)
    {
        return !stack.isEmpty() && stack.is(Items.TORCH);
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

    // 源流体判定（§9 修正：FluidState.isSource——流动流体不算源，走 FILL 顶掉）
    private static boolean isFluidSource(BlockState state)
    {
        return state.getFluidState().isSource();
    }

    // 取方块所含流体类型（§9 修正：从 FluidState 取，兼容含水方块）
    private static Fluid getFluidFromBlock(BlockState state)
    {
        var fluidState = state.getFluidState();
        return fluidState.isEmpty() ? null : fluidState.getType();
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
            if (m.isExhausted()) continue;
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
                mine.releaseAssignment(maid);
                mine.leaveCenter(maid);
            }
        }
        logTransition(maid, "结束", "行为停止");
        maid.getNavigation().stop();
        mineId = null;
        currentTask = null;
        reachedTarget = false;
        navFailCount = 0;
        waitTicks = 0;
        finished = false;
        exhaustedLogged = false;
        pendingDeposit = false;
        digProgress = 0f;
        digPos = null;
        fetchKind = FetchKind.NONE;
    }
}
