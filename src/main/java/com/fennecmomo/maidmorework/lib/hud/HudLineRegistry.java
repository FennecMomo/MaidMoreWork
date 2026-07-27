package com.fennecmomo.maidmorework.lib.hud;

import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

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
            return Component.literal("—");
        }
    }
}
