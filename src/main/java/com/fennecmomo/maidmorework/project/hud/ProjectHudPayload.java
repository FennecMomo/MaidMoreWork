package com.fennecmomo.maidmorework.project.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.fennecmomo.maidmorework.project.CountingProject;
import com.fennecmomo.maidmorework.project.ProjectBase;
import com.fennecmomo.maidmorework.project.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 工程 HUD 的网络数据包
// 服务端 ProjectManager.syncHudToPlayers 打包所有活跃工程 → 通过网络发给客户端
// 客户端 ProjectHudRenderer.sync 接收并缓存数据，在 RenderGuiLayerEvent.Post 渲染面板
//
// Entry 是一条工程的摘要信息：UUID、位置、类型、进度、工作量、参与人数、是否完成
// buildAll 从 ProjectManager 遍历全部工程生成 payload
public record ProjectHudPayload(List<Entry> entries) implements CustomPacketPayload
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 网络包类型标识：maidmorework:project_hud
    public static final Type<ProjectHudPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "project_hud"));

    @Override
    public Type<ProjectHudPayload> type() { return TYPE; }

    // 单条工程摘要数据
    public record Entry(UUID projectId, BlockPos position, String type,
                        double progress, int workload, int participantCount, boolean completed)
    {
        // 网络序列化/反序列化器
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, Entry::projectId,
                        BlockPos.STREAM_CODEC, Entry::position,
                        ByteBufCodecs.STRING_UTF8, Entry::type,
                        ByteBufCodecs.DOUBLE, Entry::progress,
                        ByteBufCodecs.INT, Entry::workload,
                        ByteBufCodecs.INT, Entry::participantCount,
                        ByteBufCodecs.BOOL, Entry::completed,
                        Entry::new
                );

        // 从内存中的工程实例提取摘要数据
        public static Entry from(ProjectBase project)
        {
            double progress = 0.0;
            int workload = 0;
            if (project instanceof CountingProject cp)
            {
                progress = cp.getProgress();
                workload = cp.getWorkload();
            }
            LOGGER.info("ProjectHudPayload: entry project={} participants={} progress={}/{}",
                    project.getId(), project.getParticipants().size(), progress, workload);
            return new Entry(
                    project.getId(), project.getPosition(), project.type(),
                    progress, workload, project.getParticipants().size(), project.isCompleted()
            );
        }
    }

    // 遍历所有活跃工程，构建完整 payload
    public static ProjectHudPayload buildAll()
    {
        List<Entry> list = new ArrayList<>();
        for (ProjectBase p : ProjectManager.getAllProjects().values())
        {
            list.add(Entry.from(p));
        }
        return new ProjectHudPayload(list);
    }

    // payload 自身的网络序列化器：Entry 列表 → 网络字节流
    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectHudPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.collection(ArrayList::new, Entry.STREAM_CODEC),
                    ProjectHudPayload::entries,
                    ProjectHudPayload::new
            );
}
