package com.fennecmomo.maidmorework.project.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

// HUD 行注册中心：存储所有可用的面板行模板
// 玩家可在配置中选择启用哪些行（后续实现配置 UI）
public final class HudLineRegistry
{
    private static final List<IHudLine> LINES = new ArrayList<>();

    static
    {
        register(new ProgressLine());
        register(new ParticipantsLine());
        register(new TypeLine());
        register(new EfficiencyLine());
    }

    public static void register(IHudLine line)
    {
        LINES.add(line);
    }

    public static List<IHudLine> getAll()
    {
        return LINES;
    }

    public static List<IHudLine> getEnabled()
    {
        // 后续从配置读取，目前全默认启用
        List<IHudLine> enabled = new ArrayList<>();
        for (IHudLine line : LINES)
        {
            if (line.defaultEnabled())
            {
                enabled.add(line);
            }
        }
        return enabled;
    }

    // ===================== 内置实现 =====================

    public static class ProgressLine implements IHudLine
    {
        @Override public String id() { return "progress"; }
        @Override public Component label() { return Component.literal("进度"); }
        @Override public boolean defaultEnabled() { return true; }

        @Override
        public Component format(ProjectHudPayload.Entry e)
        {
            return Component.literal(String.format("%.2f / %d", e.progress(), e.workload()));
        }
    }

    public static class ParticipantsLine implements IHudLine
    {
        @Override public String id() { return "participants"; }
        @Override public Component label() { return Component.literal("参与"); }
        @Override public boolean defaultEnabled() { return true; }

        @Override
        public Component format(ProjectHudPayload.Entry e)
        {
            return Component.literal(e.participantCount() + " 人");
        }
    }

    public static class TypeLine implements IHudLine
    {
        @Override public String id() { return "type"; }
        @Override public Component label() { return Component.literal("类型"); }
        @Override public boolean defaultEnabled() { return true; }

        @Override
        public Component format(ProjectHudPayload.Entry e)
        {
            return Component.literal(switch (e.type())
            {
                case "chopping" -> "砍树";
                default -> e.type();
            });
        }
    }

    public static class EfficiencyLine implements IHudLine
    {
        @Override public String id() { return "efficiency"; }
        @Override public Component label() { return Component.literal("效率"); }
        @Override public boolean defaultEnabled() { return false; }

        @Override
        public Component format(ProjectHudPayload.Entry e)
        {
            return Component.literal("—");  // 后续接入实时速率计算
        }
    }
}
