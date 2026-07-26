package com.fennecmomo.maidmorework.project.hud;

import com.fennecmomo.maidmorework.MaidMoreWork;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 客户端 → 服务端 HUD 查询请求包
// 客户端定时发送，服务端收到后根据玩家坐标筛选附近工程返回 ProjectHudPayload
public record ProjectHudQueryPayload() implements CustomPacketPayload
{
    public static final Type<ProjectHudQueryPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "project_hud_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectHudQueryPayload> STREAM_CODEC =
            StreamCodec.unit(new ProjectHudQueryPayload());

    @Override
    public Type<ProjectHudQueryPayload> type()
    {
        return TYPE;
    }
}
