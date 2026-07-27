package com.fennecmomo.maidmorework.project.center;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

public class ProjectCenterBlockEntity extends BlockEntity
{
    private long idMost = 0L;
    private long idLeast = 0L;
    private long ownerMost = 0L;
    private long ownerLeast = 0L;

    private BlockPos cornerNW = BlockPos.ZERO;
    private BlockPos cornerSE = BlockPos.ZERO;

    public ProjectCenterBlockEntity(BlockPos pos, BlockState state)
    {
        super(ProjectCenterRegistration.PROJECT_CENTER_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean hasInstance()
    {
        return idMost != 0L || idLeast != 0L;
    }

    public UUID getInstanceId()
    {
        if (!hasInstance()) return null;
        return new UUID(idMost, idLeast);
    }

    public UUID getOwner()
    {
        return new UUID(ownerMost, ownerLeast);
    }

    public BlockPos getCornerNW() { return cornerNW; }
    public BlockPos getCornerSE() { return cornerSE; }

    public boolean contains(BlockPos pos)
    {
        return pos.getX() >= cornerNW.getX() && pos.getX() <= cornerSE.getX()
                && pos.getY() >= cornerNW.getY() && pos.getY() <= cornerSE.getY()
                && pos.getZ() >= cornerNW.getZ() && pos.getZ() <= cornerSE.getZ();
    }

    public void setInstanceData(UUID id, UUID owner, BlockPos nw, BlockPos se)
    {
        this.idMost = id.getMostSignificantBits();
        this.idLeast = id.getLeastSignificantBits();
        this.ownerMost = owner.getMostSignificantBits();
        this.ownerLeast = owner.getLeastSignificantBits();
        this.cornerNW = nw;
        this.cornerSE = se;
        setChanged();
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        idMost = input.getLongOr("idMost", 0L);
        idLeast = input.getLongOr("idLeast", 0L);
        ownerMost = input.getLongOr("ownerMost", 0L);
        ownerLeast = input.getLongOr("ownerLeast", 0L);
        cornerNW = new BlockPos(
                input.getIntOr("nwX", 0),
                input.getIntOr("nwY", 0),
                input.getIntOr("nwZ", 0));
        cornerSE = new BlockPos(
                input.getIntOr("seX", 0),
                input.getIntOr("seY", 0),
                input.getIntOr("seZ", 0));
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        if (hasInstance())
        {
            output.putLong("idMost", idMost);
            output.putLong("idLeast", idLeast);
            output.putLong("ownerMost", ownerMost);
            output.putLong("ownerLeast", ownerLeast);
            output.putInt("nwX", cornerNW.getX());
            output.putInt("nwY", cornerNW.getY());
            output.putInt("nwZ", cornerNW.getZ());
            output.putInt("seX", cornerSE.getX());
            output.putInt("seY", cornerSE.getY());
            output.putInt("seZ", cornerSE.getZ());
        }
    }
}
