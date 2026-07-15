package com.fennecmomo.maidmorework.mining;

import net.minecraft.core.BlockPos;

import java.util.*;

// 矿井螺旋楼梯规划器：给定矿区尺寸，按周期→边→步计算保留区位置
//
// 螺旋楼梯原理：从矿区顶部开始，四条边（东南西北）逐层向下螺旋
//   - 周期 C：每轮完整螺旋（四条边走完为一周期，下降 4 层）
//   - 边 n(0~3)：0=北边 1=东边 2=南边 3=西边
//   - 步 idx：每条边内从转角开始的逐步偏移
//
// 保留区 getKeepBlocks()：根据 (C, n, idx) 返回应保留的方块坐标列表
//   这些方块形成螺旋楼梯的踏步支撑，女仆沿着踏步往下挖
//
// 尺寸要求：边长必须为奇数（planner 需要奇数才能对称覆盖）
// MineInstance.recomputeCorners() 已保证这点
public class SpiralMinePlanner
{
    // 中心点坐标（矿井方块所在位置）
    private final int cx, cy, cz;
    // 矿区尺寸：L=南北方向长度，W=东西方向宽度
    private final int L, W;
    // 半边长（用于偏移计算，(L-1)/2 表示从中心到边界的距离）
    private final int halfL, halfW;
    // 每条边的水平步数（边长减 5，因为转角占 2 格 + 平台 3 格）
    private final int hofNS, hofWE;
    // 矿区西北/东南角坐标（从 MineInstance 传入）
    private final int posNW_x, posNW_z;
    private final int posSE_x, posSE_z;

    // 构造：以中心点和矿区尺寸初始化
    // centerX/Y/Z: 矿井方块位置（也是螺旋起点）
    // length/width: 矿区尺寸（必须为奇数，由 MineInstance 保证）
    public SpiralMinePlanner(int centerX, int centerY, int centerZ, int length, int width)
    {
        this.cx = centerX;
        this.cy = centerY;
        this.cz = centerZ;
        this.L = length;
        this.W = width;
        // 半边长：从中心到边界的距离
        this.halfL = (L - 1) / 2;
        this.halfW = (W - 1) / 2;
        // 水平步数：边长减 5（转角 2 格 + 平台 3 格）
        this.hofNS = L - 5;
        this.hofWE = W - 5;
        // 矿区四角坐标
        this.posNW_x = cx - halfL;
        this.posNW_z = cz - halfW;
        this.posSE_x = cx + halfL;
        this.posSE_z = cz + halfW;
    }

    // 周期内高度偏移 h（相对于当前周期起始 Y）
    // 每条边内的高度变化规律：
    //   idx=0: 转角起点（h=0）
    //   idx=1~half-1: 逐步下降（每步降 1 格）
    //   idx=half: 平台中间（暂停下降）
    //   idx=half+1~: 继续下降直到下一个转角
    // n 为偶数时按 halfL 计算，奇数时按 halfW 计算
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

    // 实际 Y 坐标计算：基于周期、边、步的累计下降
    // 公式：cy - (每周期总下降) * C - 当前边已下降 - 当前步偏移
    // 每周期总下降 = (hofNS + hofWE) * 2（四条边各下降一次）
    // n>=1 时已走过北边，n>=2 时已走过北+东，n==3 时已走过北+东+南
    private int calcH(int C, int n, int idx)
    {
        int H = cy - (hofNS + hofWE) * 2 * C - getH(n, idx);
        if (n >= 1) H -= hofNS;
        if (n >= 2) H -= hofWE;
        if (n == 3) H -= hofNS;
        return H;
    }

    // ===================== 矿区范围 =====================

    // 矿区最小 X
    public int getMinX() { return posNW_x; }
    public int getMaxX() { return posSE_x; }
    public int getMinZ() { return posNW_z; }
    public int getMaxZ() { return posSE_z; }
    public int getL() { return L; }
    public int getW() { return W; }

    // 填充矩形区域（用于生成保留区方块列表）
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

    // ===================== 保留区计算 =====================

    // 获取保留方块：根据周期 C、边 n、步 idx 计算应保留的方块位置
    // 保留区形成螺旋楼梯的踏步支撑（2 格宽的实心区域）
    // 女仆挖掉非保留区的方块，保留区则放置垫脚方块
    // 返回的坐标列表由 MineBlockEntity 转换为 DigTask 分配给女仆
    public List<BlockPos> getKeepBlocks(int C, int n, int idx)
    {
        int H = calcH(C, n, idx);
        List<BlockPos> result = new ArrayList<>();

        // 根据边方向决定保留区位置和朝向
        // 每条边保留 2 格宽的方块（踏步支撑）
        switch (n)
        {
            case 0: // 北边：从东南角向西延伸
                if (idx == 0)
                {
                    fill(result, posSE_x, posNW_z, posSE_x, posNW_z + 1, H);
                }
                else
                {
                    fill(result, posSE_x - idx, posNW_z, posSE_x - idx, posNW_z + 1, H);
                }
                break;
            case 1: // 东边：从西北角向南延伸
                if (idx == 0)
                {
                    fill(result, posNW_x, posNW_z, posNW_x + 1, posNW_z, H);
                }
                else
                {
                    fill(result, posNW_x, posNW_z + idx, posNW_x + 1, posNW_z + idx, H);
                }
                break;
            case 2: // 南边：从西北角向东延伸
                if (idx == 0)
                {
                    fill(result, posNW_x, posSE_z, posNW_x, posSE_z - 1, H);
                }
                else
                {
                    fill(result, posNW_x + idx, posSE_z, posNW_x + idx, posSE_z - 1, H);
                }
                break;
            case 3: // 西边：从东南角向北延伸
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
