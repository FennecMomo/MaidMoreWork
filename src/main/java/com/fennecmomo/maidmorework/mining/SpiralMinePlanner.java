package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;

import java.util.*;

// 矿井螺旋楼梯规划器
public class SpiralMinePlanner
{
    private final int cx, cy, cz;
    private final int L, W;
    private final int halfL, halfW;
    private final int hofNS, hofWE;
    private final int posNW_x, posNW_z;
    private final int posSE_x, posSE_z;

    public SpiralMinePlanner(int centerX, int centerY, int centerZ, int length, int width)
    {
        this.cx = centerX;
        this.cy = centerY;
        this.cz = centerZ;
        this.L = length;
        this.W = width;
        this.halfL = (L - 1) / 2;
        this.halfW = (W - 1) / 2;
        this.hofNS = L - 5;
        this.hofWE = W - 5;
        this.posNW_x = cx - halfL;
        this.posNW_z = cz - halfW;
        this.posSE_x = cx + halfL;
        this.posSE_z = cz + halfW;
    }

    // 周期内高度偏移 h
    // 每条边：2 个 idx 的转角，然后逐步下降到中间 3 个 idx 的水平平台，
    // 之后再逐步下降，最后 1 格落到下一边转角
    public int getH(int n, int idx)
    {
        if (n % 2 == 0)
        {
            if (idx == 0)
                return idx;
            else if (idx >= 1 && idx <= halfL - 1)
                return idx - 1;
            else if (idx == halfL)
                return idx - 2;
            else
                return idx - 3;
        }
        else
        {
            if (idx == 0)
                return idx;
            else if (idx >= 1 && idx <= halfW - 1)
                return idx - 1;
            else if (idx == halfW)
                return idx - 2;
            else
                return idx - 3;
        }
    }

    // 实际坐标 H
    private int calcH(int C, int n, int idx)
    {
        int H = cy - (hofNS + hofWE) * 2 * C - getH(n, idx);
        if (n >= 1) H -= hofNS;
        if (n >= 2) H -= hofWE;
        if (n == 3) H -= hofNS;
        return H;
    }

    // 矿区范围
    public int getMinX() { return posNW_x; }
    public int getMaxX() { return posSE_x; }
    public int getMinZ() { return posNW_z; }
    public int getMaxZ() { return posSE_z; }
    public int getL() { return L; }
    public int getW() { return W; }

    // 填充矩形区域
    private static void fill(List<BlockPos> result, int x1, int z1, int x2, int z2, int y)
    {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        for (int x = minX; x <= maxX; x++)
        {
            for (int z = minZ; z <= maxZ; z++)
            {
                result.add(new BlockPos(x, y, z));
            }
        }
    }

    // 获取保留方块
    public List<BlockPos> getKeepBlocks(int C, int n, int idx)
    {
        int H = calcH(C, n, idx);
        List<BlockPos> result = new ArrayList<>();

        switch (n)
        {
            case 0:
                if (idx == 0)
                {
                    fill(result, posSE_x, posNW_z, posSE_x, posNW_z + 1, H);
                }
                else
                {
                    fill(result, posSE_x - idx, posNW_z, posSE_x - idx, posNW_z + 1, H);
                }
                break;
            case 1:
                if (idx == 0)
                {
                    fill(result, posNW_x, posNW_z, posNW_x + 1, posNW_z, H);
                }
                else
                {
                    fill(result, posNW_x, posNW_z + idx, posNW_x + 1, posNW_z + idx, H);
                }
                break;
            case 2:
                if (idx == 0)
                {
                    fill(result, posNW_x, posSE_z, posNW_x, posSE_z - 1, H);
                }
                else
                {
                    fill(result, posNW_x + idx, posSE_z, posNW_x + idx, posSE_z - 1, H);
                }
                break;
            case 3:
                if (idx == 0)
                {
                    fill(result, posSE_x, posSE_z, posSE_x - 1, posSE_z, H);
                }
                else
                {
                    fill(result, posSE_x, posSE_z - idx, posSE_x - 1, posSE_z - idx, H);
                }
                break;
        }
        return result;
    }
}
