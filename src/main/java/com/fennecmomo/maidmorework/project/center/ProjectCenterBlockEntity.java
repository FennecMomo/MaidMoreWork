package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.lib.region.IRegionalManager;
import com.fennecmomo.maidmorework.lib.region.RegionalManagerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

public class ProjectCenterBlockEntity extends BlockEntity implements IRegionalManager
{
    private long idMost = 0L;
    private long idLeast = 0L;
    private long ownerMost = 0L;
    private long ownerLeast = 0L;

    private BlockPos cornerNW = BlockPos.ZERO;
    private BlockPos cornerSE = BlockPos.ZERO;
    private int radius = 5;
    private int anchor = 1; // 0=top, 1=center, 2=bottom

    private boolean boundaryVisible = true;

    private String projectTypeId = "";

    public ProjectCenterBlockEntity(BlockPos pos, BlockState state)
    {
        super(ProjectCenterRegistration.PROJECT_CENTER_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean hasInstance()
    {
        return idMost != 0L || idLeast != 0L;
    }

    @Override
    public UUID getId()
    {
        if (!hasInstance()) return null;
        return new UUID(idMost, idLeast);
    }

    public UUID getOwner()
    {
        return new UUID(ownerMost, ownerLeast);
    }

    @Override
    public BlockPos getMinCorner() { return cornerNW; }

    @Override
    public BlockPos getMaxCorner() { return cornerSE; }

    @Override
    public void setBoundaryVisible(boolean visible)
    {
        this.boundaryVisible = visible;
        setChanged();
    }

    @Override
    public boolean isBoundaryVisible()
    {
        return boundaryVisible;
    }

    public void setInstanceData(UUID id, UUID owner, int radius, BlockPos nw, BlockPos se)
    {
        this.idMost = id.getMostSignificantBits();
        this.idLeast = id.getLeastSignificantBits();
        this.ownerMost = owner.getMostSignificantBits();
        this.ownerLeast = owner.getLeastSignificantBits();
        this.radius = radius;
        this.cornerNW = nw;
        this.cornerSE = se;
        this.boundaryVisible = false;
        this.projectTypeId = "";
        setChanged();
    }

    public int getRadius() { return radius; }
    public int getAnchor() { return anchor; }
    public String getProjectTypeId() { return projectTypeId; }

    public void setRadius(int radius)
    {
        this.radius = radius;
        recalcCorners();
        setChanged();
    }

    public void setAnchor(int anchor)
    {
        this.anchor = anchor;
        recalcCorners();
        setChanged();
    }

    public void setProjectTypeId(String projectTypeId)
    {
        this.projectTypeId = projectTypeId;
        setChanged();
    }

    private void recalcCorners()
    {
        BlockPos center = getBlockPos();
        int r = radius;
        int minY = switch (anchor)
        {
            case 0 -> center.getY() - 2 * r;  // top: block at top, area extends downward
            case 2 -> center.getY();           // bottom: block at bottom, area extends upward
            default -> center.getY() - r;       // center
        };
        int maxY = switch (anchor)
        {
            case 0 -> center.getY();           // top
            case 2 -> center.getY() + 2 * r;   // bottom
            default -> center.getY() + r;       // center
        };
        this.cornerNW = new BlockPos(center.getX() - r, minY, center.getZ() - r);
        this.cornerSE = new BlockPos(center.getX() + r, maxY, center.getZ() + r);
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
        boundaryVisible = input.getBooleanOr("boundaryVisible", true);
        radius = input.getIntOr("radius", 5);
        anchor = input.getIntOr("anchor", 1);
        projectTypeId = input.getStringOr("projectTypeId", "");
    }

    @Override
    public void onLoad()
    {
        super.onLoad();
        if (hasInstance())
        {
            RegionalManagerRegistry.register(this);
            if (level != null && !level.isClientSide())
            {
                UUID id = getId();
                if (ProjectCenterInstanceManager.get(level, id) == null)
                {
                    ProjectCenterInstance inst = new ProjectCenterInstance(id, getOwner(), cornerNW, cornerSE);
                    ProjectCenterInstanceManager.put(level, inst);
                }
            }
        }
    }

    @Override
    public void setRemoved()
    {
        super.setRemoved();
        if (hasInstance() && level != null && !level.isClientSide())
        {
            RegionalManagerRegistry.unregister(getId());
            ProjectCenterInstanceManager.remove(level, getId());
        }
    }

    @Override
    public void onChunkUnloaded()
    {
        super.onChunkUnloaded();
        if (hasInstance())
        {
            RegionalManagerRegistry.unregister(getId());
        }
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
            output.putBoolean("boundaryVisible", boundaryVisible);
            output.putInt("radius", radius);
            output.putInt("anchor", anchor);
            output.putString("projectTypeId", projectTypeId);
        }
    }
}
