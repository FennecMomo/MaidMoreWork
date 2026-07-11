package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

// 矿井实体方块的BlockEntity
// 存储矿井完整数据（ID+主人+两角坐标），世界重进后自动恢复实例到内存
public class MineBlockEntity extends BlockEntity
{
    private long idMost = 0L;
    private long idLeast = 0L;
    private long ownerMost = 0L;
    private long ownerLeast = 0L;
    // 两个角的坐标
    private long c1x, c1y, c1z;
    private long c2x, c2y, c2z;
    private boolean hasC1 = false;
    private boolean hasC2 = false;

    public MineBlockEntity(BlockPos pos, BlockState state)
    {
        super(MineRegistration.MINE_BLOCK_ENTITY.get(), pos, state);
    }

    public void setInstanceData(UUID id, UUID owner, BlockPos c1, BlockPos c2)
    {
        this.idMost = id.getMostSignificantBits();
        this.idLeast = id.getLeastSignificantBits();
        this.ownerMost = owner.getMostSignificantBits();
        this.ownerLeast = owner.getLeastSignificantBits();
        if (c1 != null) { c1x = c1.getX(); c1y = c1.getY(); c1z = c1.getZ(); hasC1 = true; }
        if (c2 != null) { c2x = c2.getX(); c2y = c2.getY(); c2z = c2.getZ(); hasC2 = true; }
        setChanged();
    }

    public UUID getInstanceId()
    {
        return new UUID(idMost, idLeast);
    }

    public UUID getOwner()
    {
        return new UUID(ownerMost, ownerLeast);
    }

    public boolean hasInstance()
    {
        return idMost != 0L || idLeast != 0L;
    }

    // 世界重进时从BlockEntity恢复矿井实例
    @Override
    public void onLoad()
    {
        super.onLoad();
        if (level != null && !level.isClientSide() && idMost != 0L)
        {
            UUID id = getInstanceId();
            if (MineInstanceManager.get(level, id) == null)
            {
                BlockPos c1 = hasC1 ? new BlockPos((int) c1x, (int) c1y, (int) c1z) : null;
                BlockPos c2 = hasC2 ? new BlockPos((int) c2x, (int) c2y, (int) c2z) : null;
                MineInstance inst = new MineInstance(id, getOwner(), c1, c2);
                MineInstanceManager.put(level, inst);
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        output.putLong("idMost", idMost);
        output.putLong("idLeast", idLeast);
        output.putLong("ownerMost", ownerMost);
        output.putLong("ownerLeast", ownerLeast);
        output.putBoolean("hasC1", hasC1);
        output.putBoolean("hasC2", hasC2);
        if (hasC1) { output.putLong("c1x", c1x); output.putLong("c1y", c1y); output.putLong("c1z", c1z); }
        if (hasC2) { output.putLong("c2x", c2x); output.putLong("c2y", c2y); output.putLong("c2z", c2z); }
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        idMost = input.getLongOr("idMost", 0L);
        idLeast = input.getLongOr("idLeast", 0L);
        ownerMost = input.getLongOr("ownerMost", 0L);
        ownerLeast = input.getLongOr("ownerLeast", 0L);
        hasC1 = input.getBooleanOr("hasC1", false);
        hasC2 = input.getBooleanOr("hasC2", false);
        if (hasC1) { c1x = input.getLongOr("c1x", 0); c1y = input.getLongOr("c1y", 0); c1z = input.getLongOr("c1z", 0); }
        if (hasC2) { c2x = input.getLongOr("c2x", 0); c2y = input.getLongOr("c2y", 0); c2z = input.getLongOr("c2z", 0); }
    }
}
