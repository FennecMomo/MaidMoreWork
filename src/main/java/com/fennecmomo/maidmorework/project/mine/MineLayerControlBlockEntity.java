package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.project.center.ProjectCenterManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

// 矿道层控制方块实体（2026-09-04 拍板：该层矿道控制中心）
//
//   身份绑定：只记录所属矿井中心 UUID（放置时由矿井侧写入）；
//   层/周期/边号可由坐标按螺旋公式反推（延伸矿道阶段再做，本阶段不落库）
//   兜底自检：每 100 tick 校验绑定矿井是否存在，不存在（中心已移除但区块未加载导致销毁未同步）→ 自毁
public class MineLayerControlBlockEntity extends BlockEntity
{
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(MineLayerControlBlockEntity.class);

    private UUID mineId = null;

    public MineLayerControlBlockEntity(BlockPos pos, BlockState state)
    {
        super(MineCenterRegistration.MINE_LAYER_CONTROL_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getMineId()
    {
        return mineId;
    }

    public void bind(UUID id)
    {
        mineId = id;
        setChanged();
    }

    // 服务端低频率自检（每 100 tick 由方块 ticker 调用）
    public static void serverTick(Level level, BlockPos pos, BlockState state, MineLayerControlBlockEntity be)
    {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getGameTime() % 100 != 0) return;
        if (be.mineId == null) return;
        if (ProjectCenterManager.get(serverLevel, be.mineId) != null) return;
        LOGGER.info("[MineDebug] 控制方块自毁（绑定矿井不存在） @ {}", pos.toShortString());
        serverLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        long most = input.getLongOr("mineMost", 0L);
        long least = input.getLongOr("mineLeast", 0L);
        mineId = (most == 0L && least == 0L) ? null : new UUID(most, least);
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        if (mineId != null)
        {
            output.putLong("mineMost", mineId.getMostSignificantBits());
            output.putLong("mineLeast", mineId.getLeastSignificantBits());
        }
    }
}
