package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record ProjectCenterEditPayload(UUID centerId, BlockPos centerPos, int radius, int anchor, String projectTypeId) implements CustomPacketPayload
{
    public static final Type<ProjectCenterEditPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "project_center_edit"));

    @Override
    public Type<ProjectCenterEditPayload> type() { return TYPE; }

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectCenterEditPayload> STREAM_CODEC =
            StreamCodec.composite(
                    net.minecraft.core.UUIDUtil.STREAM_CODEC, ProjectCenterEditPayload::centerId,
                    BlockPos.STREAM_CODEC, ProjectCenterEditPayload::centerPos,
                    ByteBufCodecs.INT, ProjectCenterEditPayload::radius,
                    ByteBufCodecs.INT, ProjectCenterEditPayload::anchor,
                    ByteBufCodecs.STRING_UTF8, ProjectCenterEditPayload::projectTypeId,
                    ProjectCenterEditPayload::new
            );
}
