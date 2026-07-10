package com.fennecmomo.maidmorework.entity.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

// 通用区域搜索行为
// 通过构造参数接收检索方法和目标 Memory 类型
// 游荡扫描区域，检索方法找到目标后写 Memory 并返回 true，搜索行为结束
// 不依赖实体接口，任何 TLM 行为包都能复用
// 零实例状态，所有协调通过 Memory 完成
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

    private final ISearchAction scanAction;
    private final MemoryModuleType<?> targetMemory;
    private final int scanHalfXZ;
    private final int scanYDown;
    private final int scanYUp;

    // scanAction: 检索方法，找到目标后写 Memory 并返回 true
    // targetMemory: 目标 Memory，为空时搜索行为可启动，有值时搜索行为结束
    // scanHalfXZ/scanYDown/scanYUp: 扫描范围
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
        boolean empty = maid.getBrain().getMemory(targetMemory).isEmpty();
        if (empty && maid.tickCount % 20 == 0)
        {
            LOGGER.info("SearchBehavior checkExtraStartConditions: targetMemory empty={}, maid={}", empty, maid.getId());
        }
        return empty;
    }

    // 继续条件：目标 Memory 仍为空（还没找到）
    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long time)
    {
        boolean empty = maid.getBrain().getMemory(targetMemory).isEmpty();
        return empty;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("SearchBehavior START maid={}", maid.getId());
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long time)
    {
        // 每 20 tick 扫描一次当前区域（导航中也扫，不阻断扫描）
        if (maid.tickCount % 20 == 0)
        {
            BlockPos center = maid.blockPosition();
            LOGGER.info("SearchBehavior tick: scanning at {} maid={}", center, maid.getId());
            if (scanAction.search(level, center, scanHalfXZ, scanYDown, scanYUp, maid))
            {
                LOGGER.info("SearchBehavior: found target! maid={}", maid.getId());
                return;
            }
            LOGGER.info("SearchBehavior: no target found maid={}", maid.getId());
        }

        // 没找到 -> 导航中就不重复设目标
        if (maid.getNavigation().isInProgress()) return;

        // 导航结束 -> 选随机方向继续走
        pickRandomAndMove(maid);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long time)
    {
        LOGGER.info("SearchBehavior STOP maid={}", maid.getId());
        maid.getNavigation().stop();
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
