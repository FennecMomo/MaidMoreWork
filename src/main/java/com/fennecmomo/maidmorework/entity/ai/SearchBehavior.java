package com.fennecmomo.maidmorework.entity.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

// 通用螺旋搜索行为
// 从女仆当前位置出发，按切比雪夫距离逐层向外螺旋遍历坐标点
// 每点调用一次 scanAction，找到后写 Memory 并返回 true
// 螺旋范围耗尽后随机游荡到远处重新开始
// 零实例业务状态，所有协调通过 Memory 完成
public class SearchBehavior extends Behavior<EntityMaid>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");
    private static final double MIN_DIST = 20;
    private static final double MAX_DIST = 35;
    // 速度倍率，TLM 的 MaidMoveControl 会乘以 MOVEMENT_SPEED 属性再 *3
    // 0.3 是 TLM 随机闲逛的倍率
    private static final double WALK_SPEED = 0.3;
    // 气泡框冷却 key，防止反复刷屏
    private static final long FOLLOW_WARN_KEY = 9527L;
    // 每 tick 处理的螺旋点数
    private static final int SPIRAL_BATCH = 100;

    private final ISearchAction scanAction;
    private final MemoryModuleType<?> targetMemory;
    private final int scanHalfXZ;
    private final int scanYDown;
    private final int scanYUp;

    // 螺旋状态
    private BlockPos spiralOrigin = null;
    private Deque<BlockPos> spiralQueue = null;
    private Set<BlockPos> spiralVisited = null;

    // scanAction: 单点检索方法，找到目标后写 Memory 并返回 true
    // targetMemory: 目标 Memory，为空时搜索行为可启动，有值时搜索行为结束
    // scanHalfXZ/scanYDown/scanYUp: 螺旋搜索范围（以女仆位置为原点）
    public SearchBehavior(ISearchAction scanAction, MemoryModuleType<?> targetMemory,
                          int scanHalfXZ, int scanYDown, int scanYUp)
    {
        super(Map.of(), Integer.MAX_VALUE);
        this.scanAction = scanAction;
        this.targetMemory = targetMemory;
        this.scanHalfXZ = scanHalfXZ;
        this.scanYDown = scanYDown;
        this.scanYUp = scanYUp;
    }

    // 启动条件：目标 Memory 为空且不处于跟随状态
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        // 跟随状态下不执行伐木，气泡框提示
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            maid.getChatBubbleManager().addTextChatBubbleIfTimeout(
                    "跟随模式下无法伐木，请切换到待机或家园模式", FOLLOW_WARN_KEY);
            return false;
        }
        return maid.getBrain().getMemory(targetMemory).isEmpty();
    }

    // 继续条件：目标 Memory 仍为空（还没找到）
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        return maid.getBrain().getMemory(targetMemory).isEmpty();
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("SearchBehavior START maid={}", maid.getId());
        resetSpiral(maid.blockPosition());
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        // 正在导航中，等到了再搜
        if (maid.getNavigation().isInProgress()) return;

        // 螺旋队列为空（可能是初次或上次耗尽了），在当前女仆位置重新初始化
        if (spiralQueue == null)
        {
            resetSpiral(maid.blockPosition());
        }

        // 批量处理螺旋点
        for (int i = 0; i < SPIRAL_BATCH && !spiralQueue.isEmpty(); i++)
        {
            BlockPos point = spiralQueue.poll();
            if (scanAction.search(level, point, maid))
            {
                LOGGER.info("SearchBehavior: found target at {} maid={}", point, maid.getId());
                return;
            }
            expandSpiral(point);
        }

        // 螺旋范围耗尽，随机游荡到远处换地方搜
        if (spiralQueue.isEmpty())
        {
            LOGGER.info("SearchBehavior: spiral exhausted at {}, moving maid={}",
                    spiralOrigin, maid.getId());
            spiralQueue = null;
            spiralVisited = null;
            spiralOrigin = null;
            pickRandomAndMove(maid);
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("SearchBehavior STOP maid={}", maid.getId());
        maid.getNavigation().stop();
        spiralQueue = null;
        spiralVisited = null;
        spiralOrigin = null;
    }

    // 以原点初始化螺旋队列
    private void resetSpiral(BlockPos origin)
    {
        spiralOrigin = origin;
        spiralQueue = new ArrayDeque<>();
        spiralVisited = new HashSet<>();
        spiralQueue.add(origin);
        spiralVisited.add(origin);
    }

    // 从当前点向 26 方向扩展螺旋，限制在搜索范围内
    private void expandSpiral(BlockPos point)
    {
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

    // 选随机方向导航
    private void pickRandomAndMove(EntityMaid maid)
    {
        BlockPos here = maid.blockPosition();
        double angle = maid.getRandom().nextDouble() * Math.PI * 2;
        double dist = MIN_DIST + maid.getRandom().nextDouble() * (MAX_DIST - MIN_DIST);
        int x = here.getX() + (int) Math.round(Math.cos(angle) * dist);
        int z = here.getZ() + (int) Math.round(Math.sin(angle) * dist);
        maid.getNavigation().moveTo(x, here.getY(), z, WALK_SPEED);
    }
}
