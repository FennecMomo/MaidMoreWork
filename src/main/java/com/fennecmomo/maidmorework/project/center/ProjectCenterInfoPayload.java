package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

// 工程中心信息网络包（每 20t 广播一次，中心上方面板渲染用）
// missingTools：当前缺什么工具的描述列表（2026-09-04 拍板：矿井缺工具时在面板显示"缺少：xxx"）
public record ProjectCenterInfoPayload(BlockPos centerPos, String typeName, int projectCount, int maidCount,
                                       int radius, String name, List<String> missingTools,
                                       boolean exhausted) implements CustomPacketPayload
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
                    ByteBufCodecs.STRING_UTF8, ProjectCenterInfoPayload::name,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), ProjectCenterInfoPayload::missingTools,
                    ByteBufCodecs.BOOL, ProjectCenterInfoPayload::exhausted,
                    ProjectCenterInfoPayload::new
            );
}
