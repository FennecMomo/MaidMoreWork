package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.project.center.ProjectCenterBlockEntity;
import com.fennecmomo.maidmorework.project.center.ProjectCenterManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

// 矿井中心方块实体（工程中心方块实体的子类，MINE_REDESIGN §1）
//
// 在基类身份桥（id/owner/半径/基准点/边界）之上额外持久化矿井特有数据：
//   - 竖井两角点（标记工具框定，随 BE 持久化，供 SavedData 丢失时按身份重建矿井实例）
// 加载时通过覆写 ensureManagerInstance 重建 MineInstance（而非基类的普通中心实例）
public class MineCenterBlockEntity extends ProjectCenterBlockEntity
{
    private BlockPos shaftCornerNW = BlockPos.ZERO;
    private BlockPos shaftCornerSE = BlockPos.ZERO;

    public MineCenterBlockEntity(BlockPos pos, BlockState state)
    {
        super(MineCenterRegistration.MINE_CENTER_BLOCK_ENTITY.get(), pos, state);
    }

    // ===================== 矿井身份数据 =====================

    public BlockPos getShaftCornerNW()
    {
        return shaftCornerNW;
    }

    public BlockPos getShaftCornerSE()
    {
        return shaftCornerSE;
    }

    // 竖井边长 L（南北向，由两角点 X 差推导，奇数）
    public int getShaftLength()
    {
        return Math.abs(shaftCornerSE.getX() - shaftCornerNW.getX()) + 1;
    }

    // 竖井边长 W（东西向，由两角点 Z 差推导，奇数）
    public int getShaftWidth()
    {
        return Math.abs(shaftCornerSE.getZ() - shaftCornerNW.getZ()) + 1;
    }

    // 矿井是否已挖尽（从实例读取，实例缺失视为否）
    public boolean isExhausted()
    {
        if (level == null || level.isClientSide()) return false;
        var inst = ProjectCenterManager.get((ServerLevel) level, getId());
        return inst instanceof com.fennecmomo.maidmorework.project.center.MineInstance mine && mine.isExhausted();
    }

    // 确认创建时写入完整矿井身份（基类 setInstanceData 负责通用字段与客户端推送）
    public void setMineIdentity(UUID id, UUID owner, int radius, int anchor,
                                BlockPos shaftCornerNW, BlockPos shaftCornerSE)
    {
        setInstanceData(id, owner, radius, anchor);
        this.shaftCornerNW = shaftCornerNW.immutable();
        this.shaftCornerSE = shaftCornerSE.immutable();
        setChanged();
    }

    // ===================== 生命周期 =====================

    @Override
    protected void ensureManagerInstance(ServerLevel level)
    {
        // SavedData 意外丢失时按身份重建矿井实例（含竖井两角点）
        ProjectCenterManager.ensureMineFromIdentity(level, this, shaftCornerNW, shaftCornerSE);
    }

    // ===================== NBT =====================

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        shaftCornerNW = new BlockPos(
                input.getIntOr("shaftNWx", 0),
                input.getIntOr("shaftNWy", 0),
                input.getIntOr("shaftNWz", 0));
        shaftCornerSE = new BlockPos(
                input.getIntOr("shaftSEx", 0),
                input.getIntOr("shaftSEy", 0),
                input.getIntOr("shaftSEz", 0));
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        if (hasInstance())
        {
            output.putInt("shaftNWx", shaftCornerNW.getX());
            output.putInt("shaftNWy", shaftCornerNW.getY());
            output.putInt("shaftNWz", shaftCornerNW.getZ());
            output.putInt("shaftSEx", shaftCornerSE.getX());
            output.putInt("shaftSEy", shaftCornerSE.getY());
            output.putInt("shaftSEz", shaftCornerSE.getZ());
        }
    }
}
