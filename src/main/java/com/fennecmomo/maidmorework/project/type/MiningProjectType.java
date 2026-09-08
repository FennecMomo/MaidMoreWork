package com.fennecmomo.maidmorework.project.type;

import com.fennecmomo.maidmorework.lib.projecttype.IProjectType;
import com.fennecmomo.maidmorework.lib.projecttype.RegProjectType;
import com.fennecmomo.maidmorework.project.mine.MiningTask;
import com.fennecmomo.maidmorework.project.ProjectBase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

// 矿井工程类型（2026-09-04 拍板：矿井创建即定型"采矿"类型）
//
// 矿井的派发不走工程类型接口（MineInstance.requestWork 自管层任务池），
// 本类型仅承担：类型注册/展示（信息面板、配置页）/任务指向（TLM"采矿"任务）。
// 因此 isValidTarget/bfs/stateFilter 均为空实现，createProject 禁用。
@RegProjectType
public class MiningProjectType implements IProjectType
{
    public static final String ID = "mining";

    @Override
    public String id() { return ID; }

    @Override
    public Component displayName()
    {
        return Component.translatable("projecttype.maidmorework.mining");
    }

    @Override
    public ItemStack icon() { return Items.IRON_PICKAXE.getDefaultInstance(); }

    @Override
    public Component description()
    {
        return Component.translatable("projecttype.maidmorework.mining.desc");
    }

    @Override
    public Identifier taskUid() { return MiningTask.UID; }

    @Override
    public boolean isValidTarget(ServerLevel level, BlockPos pos)
    {
        return false;   // 矿井目标由螺旋规划器生成，不经类型扫描
    }

    @Override
    public Set<BlockPos> bfs(ServerLevel level, BlockPos start)
    {
        return Collections.emptySet();
    }

    @Override
    public ProjectBase createProject(UUID id, BlockPos rootPos, List<BlockPos> targets)
    {
        throw new UnsupportedOperationException("矿井不经工程类型接口创建工程（MineInstance 自管层任务）");
    }

    @Override
    public Predicate<BlockState> stateFilter()
    {
        return state -> false;   // 矿井不做方块扫描
    }
}
