package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;

import java.util.UUID;

// 矿井实例数据
// 两个对角坐标 + 主人ID
public class MineInstance
{
    private final UUID id;
    private final UUID owner;
    private BlockPos corner1; // 第一个设的角（不区分AB，按设置顺序）
    private BlockPos corner2; // 第二个设的角

    public MineInstance(UUID id, UUID owner, BlockPos c1, BlockPos c2)
    {
        this.id = id;
        this.owner = owner;
        this.corner1 = c1;
        this.corner2 = c2;
    }

    public UUID getId() { return id; }
    public UUID getOwner() { return owner; }

    public BlockPos getCorner1() { return corner1; }
    public void setCorner1(BlockPos pos) { this.corner1 = pos; }

    public BlockPos getCorner2() { return corner2; }
    public void setCorner2(BlockPos pos) { this.corner2 = pos; }

    // 哪个角被设置了就设哪个，都不空就设corner1
    public void setNextCorner(BlockPos pos)
    {
        if (corner1 == null) corner1 = pos;
        else if (corner2 == null) corner2 = pos;
        else corner1 = pos; // 两角都满时覆盖第一个
    }

    // 更新角的Y坐标为玩家高度
    public void updateAllHeights(int playerY)
    {
        if (corner1 != null) corner1 = new BlockPos(corner1.getX(), playerY, corner1.getZ());
        if (corner2 != null) corner2 = new BlockPos(corner2.getX(), playerY, corner2.getZ());
    }

    public boolean isComplete()
    {
        return corner1 != null && corner2 != null;
    }

    // 已设了几个角
    public int cornerCount()
    {
        int n = 0;
        if (corner1 != null) n++;
        if (corner2 != null) n++;
        return n;
    }

    // 范围坐标（单数边长化）
    private int odd(int a, int b)
    {
        int d = Math.abs(a - b);
        return (d % 2 == 0) ? d - 1 : d;
    }

    public int minX() { return Math.min(corner1.getX(), corner2.getX()); }
    public int maxX() { return minX() + odd(corner1.getX(), corner2.getX()); }
    public int minY() { return Math.min(corner1.getY(), corner2.getY()); }
    public int maxY() { return minY() + odd(corner1.getY(), corner2.getY()); }
    public int minZ() { return Math.min(corner1.getZ(), corner2.getZ()); }
    public int maxZ() { return minZ() + odd(corner1.getZ(), corner2.getZ()); }

    // 范围中心坐标（整数）
    public BlockPos center()
    {
        return new BlockPos((minX() + maxX()) / 2, (minY() + maxY()) / 2, (minZ() + maxZ()) / 2);
    }

    public boolean contains(BlockPos pos)
    {
        return pos.getX() >= minX() && pos.getX() <= maxX()
                && pos.getY() >= minY() && pos.getY() <= maxY()
                && pos.getZ() >= minZ() && pos.getZ() <= maxZ();
    }
}
