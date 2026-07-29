package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ProjectCenterScanPayload(BlockPos centerPos, int cursor, int total) implements CustomPacketPayload
{
    public static final Type<ProjectCenterScanPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "project_center_scan"));

    @Override
    public Type<ProjectCenterScanPayload> type() { return TYPE; }

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectCenterScanPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ProjectCenterScanPayload::centerPos,
                    ByteBufCodecs.INT, ProjectCenterScanPayload::cursor,
                    ByteBufCodecs.INT, ProjectCenterScanPayload::total,
                    ProjectCenterScanPayload::new
            );
}
