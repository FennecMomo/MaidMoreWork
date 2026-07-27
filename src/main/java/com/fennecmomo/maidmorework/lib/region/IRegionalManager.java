package com.fennecmomo.maidmorework.lib.region;

import net.minecraft.core.BlockPos;

import java.util.UUID;

public interface IRegionalManager
{
    UUID getId();

    BlockPos getMinCorner();

    BlockPos getMaxCorner();

    default boolean contains(BlockPos pos)
    {
        return pos.getX() >= getMinCorner().getX() && pos.getX() <= getMaxCorner().getX()
                && pos.getY() >= getMinCorner().getY() && pos.getY() <= getMaxCorner().getY()
                && pos.getZ() >= getMinCorner().getZ() && pos.getZ() <= getMaxCorner().getZ();
    }

    void setBoundaryVisible(boolean visible);

    boolean isBoundaryVisible();
}
