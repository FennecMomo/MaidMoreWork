package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.project.ProjectBase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.UUID;

// 竖井层工程（2026-09-04 拍板：一层竖井 = 一个工程，与矿道工程并行）
//
//   容量不限；范围 = 该层挖格 + 该层火把 + 该层控制方块（由 MineInstance 按当前层流程派发）
//   完成判定：层结算推进时由矿井标记（不是自算），不参与通用扫描/重建（tick 空实现）
//   目标方块列表不落库（矿井按世界方块状态推进，重载后无需恢复计划）
public class MineShaftProject extends ProjectBase
{
    public static final MapCodec<MineShaftProject> MAP_CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(MineShaftProject::getId),
            BlockPos.CODEC.fieldOf("rootPos").forGetter(MineShaftProject::getPosition),
            Codec.INT.fieldOf("cycle").forGetter(MineShaftProject::getCycle),
            Codec.INT.fieldOf("layerY").forGetter(MineShaftProject::getLayerY),
            Codec.BOOL.optionalFieldOf("completed", false).forGetter(MineShaftProject::isCompleted),
            UUIDUtil.CODEC.listOf().optionalFieldOf("participants", List.of())
                    .forGetter(MineShaftProject::getParticipants),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(MineShaftProject::getDimension)
    ).apply(inst, MineShaftProject::fromCodec));

    public static final Codec<MineShaftProject> CODEC = MAP_CODEC.codec();

    private static MineShaftProject fromCodec(UUID id, BlockPos rootPos, int cycle, int layerY,
                                              boolean completed, List<UUID> participants,
                                              ResourceKey<Level> dimension)
    {
        return new MineShaftProject(id, rootPos, cycle, layerY, completed, participants, dimension);
    }

    @Override
    public String type()
    {
        return "mine_shaft";
    }

    // ===================== 字段 =====================

    private final BlockPos rootPos;     // 该层锚点（矿井方块 x/z + 层高）
    private final int cycle;            // 所属周期
    private final int layerY;           // 层高
    private boolean completed = false;

    // 新建
    public MineShaftProject(BlockPos rootPos, int cycle, int layerY)
    {
        super(Integer.MAX_VALUE);       // 容量不限
        this.rootPos = rootPos;
        this.cycle = cycle;
        this.layerY = layerY;
    }

    // Codec 反序列化
    private MineShaftProject(UUID id, BlockPos rootPos, int cycle, int layerY, boolean completed,
                             List<UUID> participants, ResourceKey<Level> dimension)
    {
        super(id, Integer.MAX_VALUE, dimension, participants, List.of());
        this.rootPos = rootPos;
        this.cycle = cycle;
        this.layerY = layerY;
        this.completed = completed;
    }

    // ===================== 数据访问 =====================

    public int getCycle()
    {
        return cycle;
    }

    public int getLayerY()
    {
        return layerY;
    }

    // 层结算推进时由矿井标记完成（基类 doProjectTick 下一 tick 移除并清空分配）
    public void markCompleted()
    {
        completed = true;
    }

    // ===================== ProjectBase 实现 =====================

    @Override
    public boolean isCompleted()
    {
        return completed;
    }

    @Override
    public void onComplete(ServerLevel level)
    {
        // 矿井自行处理完工（推进下一层在 MineInstance.advanceLayer）
    }

    @Override
    public BlockPos getPosition()
    {
        return rootPos;
    }

    @Override
    protected boolean isValidTarget(ServerLevel level, BlockPos pos)
    {
        return true;    // 不参与通用目标校验（矿井自管）
    }

    @Override
    protected boolean rebuild(ServerLevel level)
    {
        return false;   // 不参与通用重建
    }

    @Override
    public void tick(ServerLevel level)
    {
        // 矿井自管进度（不做每 tick 全量扫描）
    }
}
