package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.project.ProjectBase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.UUID;

// 矿道工程（2026-09-04 拍板：一条横向矿道 = 一个工程，容量 3，与竖井层工程并行）
//
//   范围 = 该矿道的挖格 + 该矿道火把 + 地板维护（由 MineInstance 按矿道几何派发）
//   可用前提：该层控制方块已放置；跨周期保留（未挖完一直可接）
//   完成判定：矿井周期校验（挖格全部为空气 + 火把全部放好）后标记
public class MineTunnelProject extends ProjectBase
{
    public static final int MAX_PARTICIPANTS = 3;

    public static final MapCodec<MineTunnelProject> MAP_CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(MineTunnelProject::getId),
            BlockPos.CODEC.fieldOf("rootPos").forGetter(MineTunnelProject::getPosition),
            Codec.INT.fieldOf("cycle").forGetter(MineTunnelProject::getCycle),
            Codec.INT.fieldOf("layerY").forGetter(MineTunnelProject::getLayerY),
            Codec.INT.fieldOf("edge").forGetter(MineTunnelProject::getEdge),
            Codec.BOOL.optionalFieldOf("completed", false).forGetter(MineTunnelProject::isCompleted),
            UUIDUtil.CODEC.listOf().optionalFieldOf("participants", List.of())
                    .forGetter(MineTunnelProject::getParticipants),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(MineTunnelProject::getDimension)
    ).apply(inst, MineTunnelProject::fromCodec));

    public static final Codec<MineTunnelProject> CODEC = MAP_CODEC.codec();

    private static MineTunnelProject fromCodec(UUID id, BlockPos rootPos, int cycle, int layerY, int edge,
                                               boolean completed, List<UUID> participants,
                                               ResourceKey<Level> dimension)
    {
        return new MineTunnelProject(id, rootPos, cycle, layerY, edge, completed, participants, dimension);
    }

    @Override
    public String type()
    {
        return "mine_tunnel";
    }

    // ===================== 字段 =====================

    private final BlockPos rootPos;     // 控制方块位置（矿道锚点）
    private final int cycle;            // 所属周期
    private final int layerY;           // 平台层高
    private final int edge;             // 边号 0=北 1=西 2=南 3=东
    private boolean completed = false;

    // 新建
    public MineTunnelProject(BlockPos rootPos, int cycle, int layerY, int edge)
    {
        super(MAX_PARTICIPANTS);
        this.rootPos = rootPos;
        this.cycle = cycle;
        this.layerY = layerY;
        this.edge = edge;
    }

    // Codec 反序列化
    private MineTunnelProject(UUID id, BlockPos rootPos, int cycle, int layerY, int edge, boolean completed,
                              List<UUID> participants, ResourceKey<Level> dimension)
    {
        super(id, MAX_PARTICIPANTS, dimension, participants, List.of());
        this.rootPos = rootPos;
        this.cycle = cycle;
        this.layerY = layerY;
        this.edge = edge;
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

    public int getEdge()
    {
        return edge;
    }

    // 矿道完工校验通过后由矿井标记
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
        // 矿井自行处理完工
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
