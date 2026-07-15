package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;

import java.util.UUID;

// 矿井实例数据
// posNW和posSE：计算后的西北角和东南角坐标
public class MineInstance
{
    private final UUID id;
    private final UUID owner;
    private BlockPos posNW;
    private BlockPos posSE;

    public MineInstance(UUID id, UUID owner, BlockPos nw, BlockPos se)
    {
        this.id = id;
        this.owner = owner;
        this.posNW = nw;
        this.posSE = se;
    }

    public UUID getId() { return id; }
    public UUID getOwner() { return owner; }

    public BlockPos getPosNW() { return posNW; }
    public BlockPos getPosSE() { return posSE; }

    // 设一个点击点，两点击点都设好后自动算posNW/posSE
    private BlockPos clicked1 = null;
    private BlockPos clicked2 = null;

    public void addClickPoint(BlockPos pos)
    {
        if (clicked1 == null) clicked1 = pos;
        else if (clicked2 == null) clicked2 = pos;
        else clicked1 = pos; // 满了覆盖第一个
        recomputeCorners();
    }

    public void setClickPoint1(BlockPos pos) { clicked1 = pos; recomputeCorners(); }
    public void setClickPoint2(BlockPos pos) { clicked2 = pos; recomputeCorners(); }

    // 更新两点击点的Y坐标为玩家高度
    public void updateHeights(int playerY)
    {
        if (clicked1 != null) clicked1 = new BlockPos(clicked1.getX(), playerY, clicked1.getZ());
        if (clicked2 != null) clicked2 = new BlockPos(clicked2.getX(), playerY, clicked2.getZ());
        recomputeCorners();
    }

    public boolean isComplete()
    {
        return posNW != null && posSE != null;
    }

    // 已设了几个点击点
    public int clickCount()
    {
        int n = 0;
        if (clicked1 != null) n++;
        if (clicked2 != null) n++;
        return n;
    }

    // 从点击点计算posNW/posSE（单数边长化）
    private void recomputeCorners()
    {
        if (clicked1 == null || clicked2 == null)
        {
            posNW = null;
            posSE = null;
            return;
        }
        int minX = Math.min(clicked1.getX(), clicked2.getX());
        int maxX = minX + odd(clicked1.getX(), clicked2.getX());
        int centerY = (clicked1.getY() + clicked2.getY()) / 2;
        int minZ = Math.min(clicked1.getZ(), clicked2.getZ());
        int maxZ = minZ + odd(clicked1.getZ(), clicked2.getZ());
        this.posNW = new BlockPos(minX, centerY, minZ);
        this.posSE = new BlockPos(maxX, centerY, maxZ);
    }

    // 取偶数距离，保证 mineL = 奇数（planner 需要奇数边长才能对称覆盖）
    private static int odd(int a, int b)
    {
        int d = Math.abs(a - b);
        return (d % 2 == 0) ? d : d - 1;
    }

    // 范围坐标
    public int minX() { return posNW.getX(); }
    public int maxX() { return posSE.getX(); }
    public int minY() { return posNW.getY(); }
    public int maxY() { return posSE.getY(); }
    public int minZ() { return posNW.getZ(); }
    public int maxZ() { return posSE.getZ(); }

    // 范围中心坐标
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
