package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.MaidMoreWork;
import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 工程中心持久化数据（SavedData，每维度一份）
//
// 通过 Codec 自动序列化/反序列化，无需手写 NBT 读写
// 由 SavedDataType 注册到 Minecraft 的存档系统
// 通过 level.getDataStorage().computeIfAbsent(ProjectCenterData.TYPE) 访问
//
// 与 ProjectCenterManager 的关系：
//   - 世界加载时：从磁盘反序列化 → ProjectCenterManager 构建索引并全局驱动
//   - 运行时：ProjectCenterManager 直接持有本对象的 centers 列表（同一引用），
//     修改后 setDirty() 标记脏，存档保存时自动序列化最新状态
public class ProjectCenterData extends SavedData
{
    // ===================== 持久化定义 =====================

    public static final Codec<ProjectCenterData> CODEC =
        ProjectCenterInstance.CODEC.listOf().xmap(
            ProjectCenterData::new,
            data -> data.centers
        );

    public static final SavedDataType<ProjectCenterData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(MaidMoreWork.MODID, "centers"),
        ProjectCenterData::new,
        CODEC
    );

    // ===================== 数据 =====================

    private final List<ProjectCenterInstance> centers;

    // ===================== 构造 =====================

    // 新建空数据（首次创建时使用）
    public ProjectCenterData()
    {
        this.centers = new ArrayList<>();
    }

    // 从列表构造（Codec 反序列化时使用）
    private ProjectCenterData(List<ProjectCenterInstance> list)
    {
        this.centers = new ArrayList<>(list);
    }

    // ===================== 数据访问 =====================

    public List<ProjectCenterInstance> getCenters()
    {
        return centers;
    }

    // ===================== 快照式同步 =====================

    // 以 ProjectCenterManager 的 byId 为唯一真相，把本对象的中心列表对齐为快照
    // 保存时编码的就是这份快照，保证存档内容永远来自管理器的最新状态
    public void sync(Map<UUID, ProjectCenterInstance> byId)
    {
        centers.clear();
        centers.addAll(byId.values());
        setDirty();
    }
}
