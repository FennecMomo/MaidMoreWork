package com.fennecmomo.maidmorework.project.type;

import com.fennecmomo.maidmorework.lib.projecttype.IProjectType;
import com.fennecmomo.maidmorework.lib.projecttype.RegProjectType;
import com.fennecmomo.maidmorework.logging.LoggingTask;
import com.fennecmomo.maidmorework.project.ChoppingProject;
import com.fennecmomo.maidmorework.project.ProjectBase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegProjectType
public class ChoppingProjectType implements IProjectType
{
    @Override
    public String id() { return "chopping"; }

    @Override
    public Component displayName()
    {
        return Component.translatable("projecttype.maidmorework.chopping");
    }

    @Override
    public ItemStack icon() { return Items.IRON_AXE.getDefaultInstance(); }

    @Override
    public Component description()
    {
        return Component.translatable("projecttype.maidmorework.chopping.desc");
    }

    @Override
    public Identifier taskUid() { return LoggingTask.UID; }

    @Override
    public boolean isValidTarget(ServerLevel level, BlockPos pos)
    {
        return level.getBlockState(pos).is(BlockTags.LOGS);
    }

    @Override
    public Set<BlockPos> bfs(ServerLevel level, BlockPos start)
    {
        Set<BlockPos> result = new HashSet<>();
        List<BlockPos> list = new ArrayList<>();
        ChoppingProject.bfsLogs(level, start, list);
        result.addAll(list);
        return result;
    }

    @Override
    public ProjectBase createProject(UUID id, BlockPos rootPos, List<BlockPos> targets)
    {
        return new ChoppingProject(rootPos, targets);
    }
}
