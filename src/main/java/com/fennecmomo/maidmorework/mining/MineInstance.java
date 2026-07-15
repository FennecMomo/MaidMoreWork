package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;

import java.util.UUID;

// 矿井实例数据（内存中的临时对象，不直接序列化）
// 存储矿井的 ID、主人、两个角点坐标和计算后的西北/东南角
// 角点由玩家用标记工具点击设置，两个点击点确定矿区范围
// 边长强制为奇数（planner 需要奇数边长才能对称覆盖）
public class MineInstance
{
    private final UUID id;      // 矿井唯一标识
    private final UUID owner;   // 矿井主人（放置矿井的玩家）
    private BlockPos posNW;     // 计算后的西北角坐标
    private BlockPos posSE;     // 计算后的东南角坐标

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

    // 设置点击点，两点击点都设好后自动算 posNW/posSE
    // 第三个点击点会覆盖第一个
    private BlockPos clicked1 = null;
    private BlockPos clicked2 = null;

    // 添加点击点：第一次设 clicked1，第二次设 clicked2，之后覆盖 clicked1
    public void addClickPoint(BlockPos pos)
    {
        if (clicked1 == null) clicked1 = pos;
        else if (clicked2 == null) clicked2 = pos;
        else clicked1 = pos; // 满了覆盖第一个
        recomputeCorners();
    }

    public void setClickPoint1(BlockPos pos) { clicked1 = pos; recomputeCorners(); }
    public void setClickPoint2(BlockPos pos) { clicked2 = pos; recomputeCorners(); }

    // 更新两点击点的 Y 坐标为玩家高度（标记工具操作时调用）
    // 确保矿区范围的高度与玩家当前站位一致
    public void updateHeights(int playerY)
    {
        if (clicked1 != null) clicked1 = new BlockPos(clicked1.getX(), playerY, clicked1.getZ());
        if (clicked2 != null) clicked2 = new BlockPos(clicked2.getX(), playerY, clicked2.getZ());
        recomputeCorners();
    }

    // 两个角点都已设置
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

    // 从点击点计算 posNW/posSE（单数边长化）
    // 强制边长为偶数距离，确保 mineL/mineW = 奇数（planner 需要奇数边长对称覆盖）
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
    // 例：|a-b|=5 时返回 4，mineL = 4+1 = 5（奇数）
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
