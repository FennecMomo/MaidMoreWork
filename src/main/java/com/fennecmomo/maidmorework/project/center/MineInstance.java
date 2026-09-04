package com.fennecmomo.maidmorework.project.center;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;

import java.util.UUID;

// 矿井后台实例（A1 拍板：矿井 = 工程中心的子类，与女仆挖矿行为耦合）
//
// 基类保持通用解耦（区域/存档/派活/仓库/困难表），本类只承载矿井特有部分：
//   - 竖井形状参数：标记工具两角点框定竖井范围，螺旋 L/W 由此推导
//     （半径/锚点仍是基类语义的"干活边界"，与伐木中心同义，见 MINE_REDESIGN §1/A1）
//   - 已挖尽标记（D2 拍板字段，随实例持久化）
//   - 螺旋分层/四类坐标任务/结算推进/索光源中介：后续步骤实现
//
// 持久化：通过 ProjectCenterInstance.CODEC 的 kind 分派写入/读出（kind = "mine"）
// Codec 采用扁平组合：forGetter 展开基类 MAP_CODEC 的全部字段（同层不嵌套），
// 再追加本类特有字段——绕开 DFU group 的 16 参数上限，字段名与基类保持一致
public class MineInstance extends ProjectCenterInstance
{
    // ===================== 多态序列化 =====================

    public static final MapCodec<MineInstance> MAP_CODEC =
        RecordCodecBuilder.mapCodec(inst -> inst.group(
            ProjectCenterInstance.MAP_CODEC.forGetter(MineInstance::asBase),
            BlockPos.CODEC.fieldOf("shaftCornerNW").forGetter(MineInstance::getShaftCornerNW),
            BlockPos.CODEC.fieldOf("shaftCornerSE").forGetter(MineInstance::getShaftCornerSE),
            Codec.BOOL.optionalFieldOf("exhausted", false).forGetter(MineInstance::isExhausted)
        ).apply(inst, MineInstance::fromCodec));

    // Codec 工厂方法：基类字段整体解码后包装成矿井实例
    private static MineInstance fromCodec(ProjectCenterInstance base,
                                          BlockPos shaftCornerNW, BlockPos shaftCornerSE,
                                          boolean exhausted)
    {
        return new MineInstance(base, shaftCornerNW, shaftCornerSE, exhausted);
    }

    // 扁平组合的 forGetter 引：把矿井实例视作基类交给基类 Codec 编解码
    private static ProjectCenterInstance asBase(MineInstance mine)
    {
        return mine;
    }

    // ===================== 矿井特有状态 =====================

    // 竖井两角点（标记工具框定，奇数边长校验在创建流程做）
    private final BlockPos shaftCornerNW;
    private final BlockPos shaftCornerSE;

    // 已挖尽（D2）：无新层可派时置位，实例与仓库保留，拆方块才删
    private boolean exhausted;

    // ===================== 构造 =====================

    // 新建矿井：标记工具两角点框定竖井范围后由 ProjectCenterManager.createMine 调用
    public MineInstance(UUID id, UUID owner, BlockPos blockPos, int radius, int anchor,
                        BlockPos shaftCornerNW, BlockPos shaftCornerSE)
    {
        super(id, owner, blockPos, radius, anchor);
        this.shaftCornerNW = shaftCornerNW.immutable();
        this.shaftCornerSE = shaftCornerSE.immutable();
        this.exhausted = false;
    }

    // Codec 反序列化构造：基类字段经拷贝构造接管 + 矿井特有字段
    private MineInstance(ProjectCenterInstance base,
                         BlockPos shaftCornerNW, BlockPos shaftCornerSE, boolean exhausted)
    {
        super(base);
        this.shaftCornerNW = shaftCornerNW;
        this.shaftCornerSE = shaftCornerSE;
        this.exhausted = exhausted;
    }

    // ===================== 多态标签 =====================

    @Override
    public String kind()
    {
        return KIND_MINE;
    }

    // ===================== 竖井几何 =====================

    public BlockPos getShaftCornerNW()
    {
        return shaftCornerNW;
    }

    public BlockPos getShaftCornerSE()
    {
        return shaftCornerSE;
    }

    // 螺旋边长 L（南北向，由两角点 X 差推导）；奇数/下限校验在创建流程完成
    public int getShaftLength()
    {
        return Math.abs(shaftCornerSE.getX() - shaftCornerNW.getX()) + 1;
    }

    // 螺旋边长 W（东西向，由两角点 Z 差推导）
    public int getShaftWidth()
    {
        return Math.abs(shaftCornerSE.getZ() - shaftCornerNW.getZ()) + 1;
    }

    // ===================== 挖尽状态（D2） =====================

    public boolean isExhausted()
    {
        return exhausted;
    }

    public void markExhausted()
    {
        this.exhausted = true;
    }
}
