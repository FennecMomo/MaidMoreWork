package com.fennecmomo.maidmorework.entity.ai;

import com.fennecmomo.maidmorework.ModMemories;
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
// useHomeRestriction=true 时搜索范围限定在家园范围内（Y±16），耗尽后静默等待
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
    private static final long NO_RESOURCE_KEY = 9528L;
    // 每 tick 处理的螺旋点数
    private static final int SPIRAL_BATCH = 100;
    // 家园模式下螺旋耗尽后的静默 tick 数
    private static final int SILENCE_TICKS = 2 * 20;
    // 家园模式 Y 轴扩展范围
    private static final int HOME_Y_RANGE = 16;

    private final ISearchAction scanAction;
    private final MemoryModuleType<?> targetMemory;
    private final int scanHalfXZ;
    private final int scanYDown;
    private final int scanYUp;
    private final boolean useHomeRestriction;

    // 螺旋状态
    private BlockPos spiralOrigin = null;
    private Deque<BlockPos> spiralQueue = null;
    private Set<BlockPos> spiralVisited = null;
    // 家园限制下的静默倒计时
    private int silenceTicks = 0;

    // scanAction: 单点检索方法，找到目标后写 Memory 并返回 true
    // targetMemory: 目标 Memory，为空时搜索行为可启动，有值时搜索行为结束
    // scanHalfXZ/scanYDown/scanYUp: 螺旋搜索范围（以女仆位置为原点，非家园限制时使用）
    // useHomeRestriction: 是否受家园范围限制，默认true
    public SearchBehavior(ISearchAction scanAction, MemoryModuleType<?> targetMemory,
                          int scanHalfXZ, int scanYDown, int scanYUp, boolean useHomeRestriction)
    {
        super(Map.of(), Integer.MAX_VALUE);
        this.scanAction = scanAction;
        this.targetMemory = targetMemory;
        this.scanHalfXZ = scanHalfXZ;
        this.scanYDown = scanYDown;
        this.scanYUp = scanYUp;
        this.useHomeRestriction = useHomeRestriction;
    }

    // 启动条件：目标 Memory 为空、不处于跟随状态、不在静默中
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid)
    {
        // 静默中不启动（每 tick 递减）
        if (silenceTicks > 0)
        {
            silenceTicks--;
            return false;
        }
        // 跟随状态下不执行工作，气泡框提示后进静默
        if (!maid.isHomeModeEnable() && maid.canBrainMoving())
        {
            String action = maid.getBrain().getMemory(ModMemories.WORK_ACTION.get()).orElse("工作");
            maid.getChatBubbleManager().addTextChatBubbleIfTimeout(
                    "跟随模式下无法" + action + "，请开启Home模式", FOLLOW_WARN_KEY);
            silenceTicks = SILENCE_TICKS;
            return false;
        }
        // 如果目标Memory非空（可能刚从Attachment恢复），不启动
        if (maid.getBrain().getMemory(targetMemory).isPresent())
        {
            return false;
        }
        return true;
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
        silenceTicks = 0;
        resetSpiral(maid.blockPosition());
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        // 静默倒计时中，递减并跳过
        if (silenceTicks > 0)
        {
            silenceTicks--;
            return;
        }

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
            expandSpiral(point, maid);
        }

        // 螺旋范围耗尽
        if (spiralQueue.isEmpty())
        {
            LOGGER.info("SearchBehavior: spiral exhausted at {}, maid={}",
                    spiralOrigin, maid.getId());
            spiralQueue = null;
            spiralVisited = null;
            spiralOrigin = null;

            // 家园限制模式：静默等待，不随机游荡
            if (useHomeRestriction && maid.hasHome())
            {
                silenceTicks = SILENCE_TICKS;
                String target = maid.getBrain().getMemory(ModMemories.WORK_TARGET.get()).orElse("目标");
                maid.getChatBubbleManager().addTextChatBubbleIfTimeout(
                        "家园范围内没有可用的" + target, NO_RESOURCE_KEY);
                LOGGER.info("SearchBehavior: home range exhausted, silencing for {} ticks maid={}",
                        SILENCE_TICKS, maid.getId());
            }
            else
            {
                // 非限制模式：随机游荡到远处换地方搜
                pickRandomAndMove(maid);
            }
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
        silenceTicks = 0;
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

    // 从当前点向 26 方向扩展螺旋
    // 家园限制模式下检查 isWithinRestriction + Y±16；否则用固定 scanHalfXZ/scanYDown/scanYUp
    private void expandSpiral(BlockPos point, EntityMaid maid)
    {
        boolean restricted = useHomeRestriction && maid.hasHome();
        int centerY = restricted ? maid.getHomePosition().getY() : 0;

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
                            // 家园限制：检查是否在范围内 + Y±16
                            if (maid.isWithinHome(nb)
                                    && nb.getY() >= centerY - HOME_Y_RANGE
                                    && nb.getY() <= centerY + HOME_Y_RANGE)
                            {
                                spiralQueue.add(nb);
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

    // 选随机方向导航（非家园限制模式用）
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
