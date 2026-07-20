package com.fennecmomo.maidmorework.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

// 工程持久化数据（SavedData）
//
// 通过 Codec 自动序列化/反序列化，无需手写 NBT 读写
// 由 SavedDataType 注册到 Minecraft 的存档系统
// 通过 level.getDataStorage().computeIfAbsent(ProjectData.TYPE) 访问
//
// 内部持有工程列表，与 ProjectManager 的内存缓存同步：
//   - 世界加载时：从磁盘反序列化 → load() 填充 ProjectManager
//   - 运行时：ProjectManager 修改后调用 setDirty() 标记脏
//   - 世界保存时：存档系统自动序列化到磁盘
public class ProjectData extends SavedData
{
    // ===================== 持久化定义 =====================

    public static final Codec<ProjectData> CODEC =
        ProjectBase.CODEC.listOf().xmap(
            ProjectData::fromList,
            data -> new ArrayList<>(data.projects)
        );

    public static final SavedDataType<ProjectData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("maidmorework", "projects"),
        ProjectData::new,
        CODEC
    );

    // ===================== 数据 =====================

    private final List<ProjectBase> projects;

    // ===================== 构造 =====================

    // 新建空数据（首次创建时使用）
    public ProjectData()
    {
        this.projects = new ArrayList<>();
    }

    // 从列表构造（Codec 反序列化时使用）
    private ProjectData(List<ProjectBase> projects)
    {
        this.projects = new ArrayList<>(projects);
    }

    // Codec 工厂方法
    private static ProjectData fromList(List<ProjectBase> projects)
    {
        return new ProjectData(projects);
    }

    // ===================== 数据访问 =====================

    public List<ProjectBase> getProjects()
    {
        return projects;
    }

    // ===================== 与 ProjectManager 同步 =====================

    // 从磁盘加载后，将所有工程填充到 ProjectManager 的内存缓存
    public void load()
    {
        ProjectManager.loadFromData(this);
    }

    // 将 ProjectManager 的当前工程同步到此数据对象
    // 调用后应 setDirty() 触发存档系统写盘
    public void sync(Map<UUID, ProjectBase> projectMap)
    {
        projects.clear();
        projects.addAll(projectMap.values());
        setDirty();
    }
}
