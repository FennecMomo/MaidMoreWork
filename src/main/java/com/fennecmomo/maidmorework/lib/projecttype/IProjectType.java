package com.fennecmomo.maidmorework.lib.projecttype;

import com.fennecmomo.maidmorework.project.ProjectBase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public interface IProjectType
{
    String id();
    Component displayName();
    ItemStack icon();
    Component description();
    Identifier taskUid();

    default boolean isValidTarget(ServerLevel level, BlockPos pos)
    {
        return false;
    }

    default Set<BlockPos> bfs(ServerLevel level, BlockPos start)
    {
        return Collections.emptySet();
    }

    ProjectBase createProject(UUID id, BlockPos rootPos, List<BlockPos> targets);

    default Predicate<BlockState> stateFilter()
    {
        return state -> false;
    }
}
