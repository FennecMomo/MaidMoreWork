package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ProjectCenterInfoPayload(BlockPos centerPos, String typeName, int projectCount, int maidCount, int radius) implements CustomPacketPayload
{
    public static final Type<ProjectCenterInfoPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "project_center_info"));

    @Override
    public Type<ProjectCenterInfoPayload> type() { return TYPE; }

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectCenterInfoPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ProjectCenterInfoPayload::centerPos,
                    ByteBufCodecs.STRING_UTF8, ProjectCenterInfoPayload::typeName,
                    ByteBufCodecs.INT, ProjectCenterInfoPayload::projectCount,
                    ByteBufCodecs.INT, ProjectCenterInfoPayload::maidCount,
                    ByteBufCodecs.INT, ProjectCenterInfoPayload::radius,
                    ProjectCenterInfoPayload::new
            );
}
