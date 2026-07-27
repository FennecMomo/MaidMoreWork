package com.fennecmomo.maidmorework.search;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fennecmomo.maidmorework.MaidBubbleHelper;
import com.fennecmomo.maidmorework.MaidMoreWorkConfig;
import com.fennecmomo.maidmorework.ModMemories;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

// 通用螺旋搜索行为（伐木/挖矿共用）
//
// 从女仆当前位置出发，按切比雪夫距离逐层向外螺旋遍历坐标点
// 每点调用一次 scanAction，找到后写 Memory 并返回 true
//
// 两种搜索模式：
//   useHomeRestriction=true  — 搜索范围限定在家园范围内（Y±16），耗尽后静默等待
//   useHomeRestriction=false — 搜索耗尽后随机游荡到远处换地方搜
//
// 零实例业务状态，所有协调通过 Memory 完成
// 在 IMaidTask.createBrainTasks() 中与具体业务行为一起注册到 Brain
//
// 生命周期：
//   checkExtraStartConditions → 门控：跟随拦截 + Memory 非空跳过 + 冷却跳过
//   start → 重置螺旋状态，以女仆位置为原点
//   tick  → 批量处理螺旋点 → 耗尽时游荡或静默
//   stop  → 清空螺旋状态和导航
public class SearchBehavior extends Behavior<EntityMaid>
{
    // ===================== 常量 =====================

    // 家园模式下螺旋耗尽后的静默 tick 数


    // ===================== 配置字段 =====================

    private final ISearchAction scanAction;       // 单点检索回调，判断当前点是否为目标
    private final ISearchAction preSearch;        // 前置检索：搜索前尝试查找孤儿工程（可为 null）
    private final ISearchAction onFound;          // 找到目标后的回调（可为 null）
    private final MemoryModuleType<?> targetMemory; // 目标 Memory，为空时搜索，有值时结束
    private final int scanHalfXZ;                  // 非家园模式 XZ 半径
    private final int scanYDown;                   // 非家园模式 Y 向下范围
    private final int scanYUp;                     // 非家园模式 Y 向上范围
    private final boolean useHomeRestriction;      // 是否受家园范围限制

    // 最近找到的坐标点（scanAction 返回 true 时记录，供 onFound 回调使用）
    private BlockPos lastFoundPoint = null;

    // ===================== 运行时状态 =====================

    // 螺旋状态：原点、待访问队列、已访问集合
    // 每次 start() 或螺旋耗尽时重置
    private BlockPos spiralOrigin = null;
    private Deque<BlockPos> spiralQueue = null;
    private Set<BlockPos> spiralVisited = null;
    private int spiralHitCount = 0;    // 本螺旋周期内扫描的点数
    private int spiralFilteredCount = 0; // 被 isWithinHome 过滤掉的点数
    private int spiralTotalEstimate = 0; // 本螺旋周期的预估总点数（非限制=15376，限制≈球体体积）

    // 静默倒计时（螺旋耗尽后等待一段时间再试）
    private int silenceTicks = 0;

    // ===================== 构造器 =====================

    // scanAction: 单点检索方法，判断当前点是否为目标，返回 true/false
    // targetMemory: 目标 Memory，为空时搜索行为可启动，有值时搜索行为结束
    // scanHalfXZ/scanYDown/scanYUp: 螺旋搜索范围（以女仆位置为原点，非家园限制时使用）
    // useHomeRestriction: 是否受家园范围限制，默认true
    public SearchBehavior(ISearchAction scanAction, MemoryModuleType<?> targetMemory,
                          int scanHalfXZ, int scanYDown, int scanYUp, boolean useHomeRestriction)
    {
        this(scanAction, null, null, targetMemory, scanHalfXZ, scanYDown, scanYUp, useHomeRestriction);
    }

    // 带前置检索的构造器：preSearch 在螺旋搜索前执行，用于优先接取孤儿工程
    public SearchBehavior(ISearchAction scanAction, ISearchAction preSearch,
                          MemoryModuleType<?> targetMemory,
                          int scanHalfXZ, int scanYDown, int scanYUp, boolean useHomeRestriction)
    {
        this(scanAction, preSearch, null, targetMemory, scanHalfXZ, scanYDown, scanYUp, useHomeRestriction);
    }

    // 完整构造器：支持 preSearch + onFound 回调
    // onFound: 找到目标后在 tick 中调用，负责 BFS、工程创建、Memory 写入等后续操作
    public SearchBehavior(ISearchAction scanAction, ISearchAction preSearch, ISearchAction onFound,
                          MemoryModuleType<?> targetMemory,
                          int scanHalfXZ, int scanYDown, int scanYUp, boolean useHomeRestriction)
    {
        super(Map.of(), Integer.MAX_VALUE);
        this.scanAction = scanAction;
        this.preSearch = preSearch;
        this.onFound = onFound;
        this.targetMemory = targetMemory;
        this.scanHalfXZ = scanHalfXZ;
        this.scanYDown = scanYDown;
        this.scanYUp = scanYUp;
        this.useHomeRestriction = useHomeRestriction;
    }

    // ===================== 启动/继续条件 =====================

    // 启动门控：三层拦截依次判断
    // 1. 静默冷却中 → 不启动
    // 2. 跟随模式 → 不启动（弹气泡提示）
    // 3. 目标 Memory 已有值 → 不启动（说明业务行为正在使用搜索结果）
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        if (isInSilence())
        {
            return false;
        }
        if (isFollowModeBlocking(maid))
        {
            clearTarget(maid);
            return false;
        }
        if (isTargetMemoryOccupied(maid))
        {
            return false;
        }
        return true;
    }

    // 继续条件：目标 Memory 仍为空（还没找到）
    // 一旦业务行为把结果写入 Memory，canStillUse 返回 false，搜索自动结束
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        return maid.getBrain().getMemory(targetMemory).isEmpty();
    }

    // ===================== 生命周期 =====================

    // 行为启动：重置静默计时、尝试前置检索、初始化螺旋原点
    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        MaidBubbleHelper.get(maid).clearAll();

        // 前置检索：优先查找孤儿工程（如无人接手的砍树工程）
        if (preSearch != null && preSearch.search(level, maid.blockPosition(), maid))
        {

            return;
        }

        initSpiral(maid.blockPosition(), maid);
    }

    // 每 tick 驱动：静默检查 → 导航等待 → 批量搜索 → 耗尽处理
    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        // 静默倒计时中，跳过本 tick
        if (isInSilence())
        {
            return;
        }

        // 正在导航中（随机游荡），等到了再搜
        if (maid.getNavigation().isInProgress())
        {
            return;
        }

        // 螺旋搜索：初始化队列 + 批量处理螺旋点
        boolean found = searchSpiralBatch(level, maid);
        if (found)
        {
            // 找到目标后调用 onFound 回调（负责 BFS、工程创建、Memory 写入等）
            if (onFound != null && lastFoundPoint != null)
            {
                onFound.search(level, lastFoundPoint, maid);
                lastFoundPoint = null;
            }
            return;
        }

        // 搜索进行中：每 tick 刷新进度气泡
        if (spiralQueue != null && !spiralQueue.isEmpty() && spiralTotalEstimate > 0)
        {
            String target = maid.getBrain().getMemory(ModMemories.WORK_TARGET.get()).orElse("目标");
            String progress = "进度:[" + spiralHitCount + "/" + spiralTotalEstimate + "]";
            MaidBubbleHelper.get(maid).setFloor("搜索" + target + "中..." + progress);
        }

        // 螺旋范围耗尽，根据模式选择后续策略
        if (spiralQueue != null && spiralQueue.isEmpty())
        {
            handleSpiralExhausted(maid);
        }
    }

    // 行为停止：清空所有运行时状态，确保下次 start 时重新初始化
    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        maid.getNavigation().stop();
        resetState();
    }

    // ===================== 启动条件子方法 =====================

    // 静默冷却判断：每 tick 递减，冷却中返回 true
    private boolean isInSilence()
    {
        if (silenceTicks > 0)
        {
            silenceTicks--;
            return true;
        }
        return false;
    }

    // 跟随模式拦截：女仆跟随主人时不工作，弹气泡提示后进静默
    private boolean isFollowModeBlocking(EntityMaid maid)
    {
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            MaidBubbleHelper.get(maid).setFollowWarn(maid);
            silenceTicks = MaidMoreWorkConfig.SILENCE_TICKS;
            return true;
        }
        return false;
    }

    // 目标 Memory 已被占用：业务行为正在使用搜索结果时不启动搜索
    private boolean isTargetMemoryOccupied(EntityMaid maid)
    {
        return maid.getBrain().getMemory(targetMemory).isPresent();
    }

    // ===================== tick 子方法 =====================

    // 螺旋搜索：确保队列初始化 + 批量处理 SPIRAL_BATCH 个螺旋点
    // 队列为空时以女仆当前位置初始化新的螺旋起点
    // 每个点调用 scanAction.search()，找到目标立即返回 true
    // 未命中的点向 26 方向扩展邻居入队，下个 tick 继续
    private boolean searchSpiralBatch(ServerLevel level, EntityMaid maid)
    {
        if (spiralQueue == null)
        {
            initSpiral(maid.blockPosition(), maid);
        }

        for (int i = 0; i < MaidMoreWorkConfig.SPIRAL_BATCH && !spiralQueue.isEmpty(); i++)
        {
            BlockPos point = spiralQueue.poll();
            spiralHitCount++;
            if (scanAction.search(level, point, maid))
            {
                lastFoundPoint = point;
                return true;
            }
            expandSpiral(point, maid);
        }
        return false;
    }

    // 螺旋耗尽处理：清空螺旋状态，根据模式选择后续策略
    private void handleSpiralExhausted(EntityMaid maid)
    {
        int savedScanned = spiralHitCount;
        int savedFiltered = spiralFilteredCount;
        spiralQueue = null;
        spiralVisited = null;
        spiralOrigin = null;
        spiralHitCount = 0;
        spiralFilteredCount = 0;
        spiralTotalEstimate = 0;

        if (useHomeRestriction && maid.hasHome())
        {
            enterSilenceWithHint(maid, savedScanned, savedFiltered);
        }
        else
        {
            pickRandomAndMove(maid);
        }
    }

    // ===================== 螺旋管理 =====================

    // 以原点初始化螺旋队列，清空已访问集合
    // 螺旋遍历从原点开始向 26 个方向逐步扩展
    private void initSpiral(BlockPos origin, EntityMaid maid)
    {
        spiralOrigin = origin;
        spiralQueue = new ArrayDeque<>();
        spiralVisited = new HashSet<>();
        spiralQueue.add(origin);
        spiralVisited.add(origin);
        spiralHitCount = 0;
        spiralFilteredCount = 0;

        boolean hasHome = maid.hasHome();
        boolean originWithinHome = hasHome && maid.isWithinHome(origin);
        if (useHomeRestriction && originWithinHome)
        {
            int r = maid.getHomeRadius();
            spiralTotalEstimate = (int) (4.0 / 3.0 * Math.PI * r * r * r);
        }
        else
        {
            spiralTotalEstimate = (2 * scanHalfXZ + 1) * (2 * scanHalfXZ + 1) * (scanYDown + scanYUp + 1);
        }
    }

    // 从当前点向 26 方向扩展螺旋（3x3x3 立方体的所有偏移）
    // 家居模式下用 isWithinHome 判定搜索边界
    // 非家居模式下用固定 scanHalfXZ/scanYDown/scanYUp 作为边界
    private void expandSpiral(BlockPos point, EntityMaid maid)
    {
        boolean hasHome = maid.hasHome();
        // 只在螺旋原点位于家园范围内时才启用家园限制
        // 否则用固定范围，避免原点在家外时螺旋一帧耗尽
        boolean restricted = useHomeRestriction && hasHome
                && spiralOrigin != null && maid.isWithinHome(spiralOrigin);

        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos nb = point.offset(dx, dy, dz);
                    if (spiralVisited.add(nb))
                    {
                        if (restricted)
                        {
                            if (maid.isWithinHome(nb))
                            {
                                spiralQueue.add(nb);
                            }
                            else
                            {
                                spiralFilteredCount++;
                            }
                        }
                        else
                        {
                            int relX = Math.abs(nb.getX() - spiralOrigin.getX());
                            int relY = nb.getY() - spiralOrigin.getY();
                            int relZ = Math.abs(nb.getZ() - spiralOrigin.getZ());
                            if (relX <= scanHalfXZ && relZ <= scanHalfXZ
                                    && relY >= -scanYDown && relY <= scanYUp)
                            {
                                spiralQueue.add(nb);
                            }
                        }
                    }
                }
            }
        }
    }

    // ===================== 耗尽后策略 =====================

    // 家园限制模式：进入静默等待，弹气泡提示家园范围内无资源
    private void enterSilenceWithHint(EntityMaid maid, int scanned, int filtered)
    {
        silenceTicks = MaidMoreWorkConfig.SILENCE_TICKS;
        String target = maid.getBrain().getMemory(ModMemories.WORK_TARGET.get()).orElse("目标");
        MaidBubbleHelper helper = MaidBubbleHelper.get(maid);
        helper.clearFloor();
        helper.set("家园没有可用" + target, -999);
    }

    // 非限制模式：选随机方向导航到 20~35 格外的某个点
    // 螺旋耗尽后女仆走到新位置再重新初始化螺旋，覆盖更多区域
    // Y 取目标 XZ 的地表高度，避免 here.getY() 导致走进墙里或悬空
    private void pickRandomAndMove(EntityMaid maid)
    {
        BlockPos here = maid.blockPosition();
        double angle = maid.getRandom().nextDouble() * Math.PI * 2;
        double dist = MaidMoreWorkConfig.SEARCH_ROAM_MIN_DIST + maid.getRandom().nextDouble() * (MaidMoreWorkConfig.SEARCH_ROAM_MAX_DIST - MaidMoreWorkConfig.SEARCH_ROAM_MIN_DIST);
        int x = here.getX() + (int) Math.round(Math.cos(angle) * dist);
        int z = here.getZ() + (int) Math.round(Math.sin(angle) * dist);
        ServerLevel level = (ServerLevel) maid.level();
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        maid.getNavigation().moveTo(x, groundY, z, MaidMoreWorkConfig.SEARCH_ROAM_WALK_SPEED);
    }

    // ===================== 目标清理 =====================

    // 清空搜索目标：只清除目标 Memory
    // 女仆被判定无法行动时调用（如跟随模式），避免残留目标与后续搜索产生冲突
    // 导航停止和螺旋状态重置由 canStillUse=false 触发的 stop() 自然完成
    private void clearTarget(EntityMaid maid)
    {
        maid.getBrain().eraseMemory(targetMemory);
    }

    // ===================== 状态清理 =====================

    // 清空所有运行时状态（stop 时调用）
    private void resetState()
    {
        spiralQueue = null;
        spiralVisited = null;
        spiralOrigin = null;
        spiralTotalEstimate = 0;
        silenceTicks = 0;
    }
}
