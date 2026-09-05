package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.project.mine.MineTask;
import com.fennecmomo.maidmorework.project.mine.SpiralMinePlanner;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 矿井后台实例（A1 拍板：矿井 = 工程中心的子类，与女仆挖矿行为耦合）
//
// 基类保持通用解耦（区域/存档/派活/仓库/困难表），本类承载矿井特有部分：
//   - 竖井形状参数：标记工具两角点框定竖井范围，螺旋 L/W 由此推导
//     （半径/锚点仍是基类语义的"干活边界"，与伐木中心同义，见 MINE_REDESIGN §1/A1）
//   - 螺旋分层状态机：周期(C) → 层(Y) 逐层下挖，深度限制 = 干活边界盒底（§2）
//   - 派活口：§6 派发树（困难表优先 → 最近坐标分类派发 → 光源收尾 → 结算推进）
//   - 已挖尽标记（D2）
//
// 持久化：通过 ProjectCenterInstance.CODEC 的 kind 分派写入/读出（kind = "mine"）。
// Codec 采用扁平组合：forGetter 展开基类 MAP_CODEC 的全部字段（同层不嵌套），
// 再追加本类特有字段——绕开 DFU group 的 16 参数上限。
// 周期/层索引持久化；层池数据（保留/光源/子任务/已处理）不持久化，重载后按周期重算
// （与旧版 computeCycle 同策略，实时方块状态即进度真相）
public class MineInstance extends ProjectCenterInstance
{
    // ===================== 多态序列化 =====================

    public static final MapCodec<MineInstance> MAP_CODEC =
        RecordCodecBuilder.mapCodec(inst -> inst.group(
            ProjectCenterInstance.MAP_CODEC.forGetter(MineInstance::asBase),
            BlockPos.CODEC.fieldOf("shaftCornerNW").forGetter(MineInstance::getShaftCornerNW),
            BlockPos.CODEC.fieldOf("shaftCornerSE").forGetter(MineInstance::getShaftCornerSE),
            Codec.BOOL.optionalFieldOf("exhausted", false).forGetter(MineInstance::isExhausted),
            Codec.INT.optionalFieldOf("cycle", 0).forGetter(MineInstance::getCycle),
            Codec.INT.optionalFieldOf("layerIndex", 0).forGetter(MineInstance::getLayerIndex)
        ).apply(inst, MineInstance::fromCodec));

    // Codec 工厂方法：基类字段整体解码后包装成矿井实例
    private static MineInstance fromCodec(ProjectCenterInstance base,
                                          BlockPos shaftCornerNW, BlockPos shaftCornerSE,
                                          boolean exhausted, int cycle, int layerIndex)
    {
        return new MineInstance(base, shaftCornerNW, shaftCornerSE, exhausted, cycle, layerIndex);
    }

    // 扁平组合的 forGetter 引：把矿井实例视作基类交给基类 Codec 编解码
    private static ProjectCenterInstance asBase(MineInstance mine)
    {
        return mine;
    }

    // ===================== 矿井特有状态 =====================

    // 竖井两角点（标记工具框定，奇数/正方形校验在创建流程做）
    private final BlockPos shaftCornerNW;
    private final BlockPos shaftCornerSE;

    // 已挖尽（D2）：无新层可派时置位，实例与仓库保留，拆方块才删
    private boolean exhausted;

    // 当前周期与层索引（持久化；层池重载后按周期重算）
    private int cycle;
    private int layerIndex;

    // 螺旋规划器（公式原样保留，仅搬迁包路径）
    private SpiralMinePlanner planner;

    // 当前周期内 Y 层（高→低）与保留区/光源（懒计算）
    private final List<Integer> cycleLayerYs = new ArrayList<>();
    private final Map<Integer, Set<BlockPos>> cycleKeeps = new HashMap<>();
    private final Map<Integer, Set<BlockPos>> cycleLights = new HashMap<>();

    // 当前层运行时状态（不持久化，实时方块状态即进度真相）
    private final Set<BlockPos> layerProcessed = new HashSet<>();
    private final Map<UUID, MineTask> layerSubtasks = new HashMap<>();
    private final List<BlockPos> layerHardList = new ArrayList<>();

    // ===================== 构造 =====================

    // 新建矿井：标记工具两角点框定竖井范围后由 ProjectCenterManager.createMine 调用
    public MineInstance(UUID id, UUID owner, BlockPos blockPos, int radius, int anchor,
                        BlockPos shaftCornerNW, BlockPos shaftCornerSE)
    {
        super(id, owner, blockPos, radius, anchor);
        this.shaftCornerNW = shaftCornerNW.immutable();
        this.shaftCornerSE = shaftCornerSE.immutable();
        this.exhausted = false;
        this.cycle = 0;
        this.layerIndex = 0;
    }

    // Codec 反序列化构造：基类字段经拷贝构造接管 + 矿井特有字段
    private MineInstance(ProjectCenterInstance base,
                         BlockPos shaftCornerNW, BlockPos shaftCornerSE,
                         boolean exhausted, int cycle, int layerIndex)
    {
        super(base);
        this.shaftCornerNW = shaftCornerNW;
        this.shaftCornerSE = shaftCornerSE;
        this.exhausted = exhausted;
        this.cycle = cycle;
        this.layerIndex = layerIndex;
    }

    // ===================== 多态标签 =====================

    @Override
    public String kind()
    {
        return KIND_MINE;
    }

    // ===================== 竖井几何 =====================

    public BlockPos getShaftCornerNW()
    {
        return shaftCornerNW;
    }

    public BlockPos getShaftCornerSE()
    {
        return shaftCornerSE;
    }

    // 螺旋边长 L（南北向，由两角点 X 差推导）；奇数/正方形校验在创建流程完成
    public int getShaftLength()
    {
        return Math.abs(shaftCornerSE.getX() - shaftCornerNW.getX()) + 1;
    }

    // 螺旋边长 W（东西向，由两角点 Z 差推导）
    public int getShaftWidth()
    {
        return Math.abs(shaftCornerSE.getZ() - shaftCornerNW.getZ()) + 1;
    }

    // ===================== 螺旋分层（周期/层状态机） =====================

    public int getCycle()
    {
        return cycle;
    }

    public int getLayerIndex()
    {
        return layerIndex;
    }

    // 螺旋规划器 Y 起点 = 矿井方块所在层（玩家脚下，2026-09-04 修正后语义）
    private SpiralMinePlanner planner()
    {
        if (planner == null)
        {
            planner = new SpiralMinePlanner(getBlockPos().getX(), getBlockPos().getY(),
                    getBlockPos().getZ(), getShaftLength(), getShaftWidth());
        }
        return planner;
    }

    // 深度限制 = 干活边界盒底（§2：基准点=顶部时 y - 2r）
    private int depthBottomY()
    {
        return getMinCorner().getY();
    }

    // 周期内第 n 条边的步数上限（与旧版遍历序一致）
    private int edgeStepLimit(int n)
    {
        return (n % 2 == 0)
                ? Math.max(0, planner().getL() - 3)
                : Math.max(0, planner().getW() - 3);
    }

    // 计算当前周期的任务数据：保留区/光源，按 Y 层分组（懒计算，重载后重建）
    // 结构沿用旧版 computeCycle：螺旋保留区 + 矿井方块保护 + 入口清理 + 支撑层 + 围墙 + 光源
    private void ensureCycleComputed(ServerLevel level)
    {
        if (!cycleLayerYs.isEmpty()) return;

        SpiralMinePlanner p = planner();

        // 收集本周期所有 (n, idx) 的保留区，按 Y 层合并
        Map<Integer, Set<BlockPos>> keepsByY = new HashMap<>();
        int n = 0;
        int idx = 0;
        int limit = edgeStepLimit(n);
        while (true)
        {
            for (BlockPos k : p.getKeepBlocks(cycle, n, idx))
            {
                keepsByY.computeIfAbsent(k.getY(), key -> new HashSet<>()).add(k.immutable());
            }
            idx++;
            if (idx > limit)
            {
                n++;
                if (n > 3) break;
                idx = 0;
                limit = edgeStepLimit(n);
            }
        }

        int mineY = getBlockPos().getY();
        // 矿井方块自身加入保留区，防止被挖
        keepsByY.computeIfAbsent(mineY, key -> new HashSet<>()).add(getBlockPos());
        // 入口清理 mineY+1 ~ mineY+2（保留区为空，全挖）
        keepsByY.computeIfAbsent(mineY + 1, key -> new HashSet<>());
        keepsByY.computeIfAbsent(mineY + 2, key -> new HashSet<>());

        // 矿井下方一层支撑保留区（只保留 |z-中心|≤1，避免挡住楼梯）
        Set<BlockPos> support = keepsByY.computeIfAbsent(mineY - 1, key -> new HashSet<>());
        for (int x = p.getMinX(); x <= p.getMaxX(); x++)
        {
            for (int z = p.getMinZ(); z <= p.getMaxZ(); z++)
            {
                if (Math.abs(z - getBlockPos().getZ()) <= 1)
                {
                    support.add(new BlockPos(x, mineY - 1, z));
                }
            }
        }

        // 边界围墙：mineY 以下每层向外一圈（入口区保持开放）
        for (int y : new ArrayList<>(keepsByY.keySet()))
        {
            if (y < mineY)
            {
                addBoundaryKeeps(keepsByY.get(y), y, p);
            }
        }

        // 光源：每个 Y 层沿 X 每 6 格一个（入口层不放，§10 层末尾 SETLIGHT 阶段派发）
        for (int y : keepsByY.keySet())
        {
            if (y >= mineY) continue;
            Set<BlockPos> lights = new HashSet<>();
            for (int x = p.getMinX() + 3; x <= p.getMaxX() - 3; x += 6)
            {
                lights.add(new BlockPos(x, y, p.getMinZ() + 1));
                lights.add(new BlockPos(x, y, p.getMaxZ() - 1));
            }
            cycleLights.put(y, lights);
        }

        cycleKeeps.putAll(keepsByY);
        cycleLayerYs.addAll(keepsByY.keySet());
        cycleLayerYs.sort(Comparator.reverseOrder());

        // 重载恢复等场景的越界保护
        if (layerIndex >= cycleLayerYs.size()) layerIndex = 0;
    }

    // 在矿区边界向外加一圈保留方块形成围墙
    private static void addBoundaryKeeps(Set<BlockPos> keeps, int y, SpiralMinePlanner p)
    {
        int minX = p.getMinX() - 1;
        int maxX = p.getMaxX() + 1;
        int minZ = p.getMinZ() - 1;
        int maxZ = p.getMaxZ() + 1;
        for (int x = minX; x <= maxX; x++)
        {
            keeps.add(new BlockPos(x, y, minZ));
            keeps.add(new BlockPos(x, y, maxZ));
        }
        for (int z = minZ; z <= maxZ; z++)
        {
            keeps.add(new BlockPos(minX, y, z));
            keeps.add(new BlockPos(maxX, y, z));
        }
    }

    // ===================== 派活口（B1 拍板：中心决定"下一个坐标"） =====================

    // 女仆请求推进（§6 派发树）：
    //   已绑定子任务 → 原样返回（恢复）
    //   0. 矿井困难表有能处理的 → 取走（§7：先于一切）
    //   1. 当前层最近未处理坐标 → 保留位：空气 FILL / 垫脚丢已处理 / 非垫脚 REPLACE；
    //      普通位：非空气 DESTROY / 空气丢已处理。无法处理 → 进层困难表
    //   2. 坐标池空 → 光源：有灯 SETLIGHT，无灯 FETCH_LIGHT（去矿井方块取，B2）
    //   3. 层全部完成 → 有他人在干则让位等待，否则结算推进
    //   返回 null = 暂无可派任务（等待/空闲/已挖尽）
    public MineTask requestWork(EntityMaid maid)
    {
        if (exhausted) return null;
        if (!(maid.level() instanceof ServerLevel level)) return null;

        ensureCycleComputed(level);
        if (layerIndex >= cycleLayerYs.size()) return null;
        int y = cycleLayerYs.get(layerIndex);
        if (y < depthBottomY())
        {
            markExhausted();
            return null;
        }

        // 恢复：已绑定子任务原样返回
        MineTask bound = layerSubtasks.get(maid.getUUID());
        if (bound != null) return bound;

        // 0. 矿井困难表优先
        MineTask hard = takeHardTableTask(level, maid);
        if (hard != null)
        {
            layerSubtasks.put(maid.getUUID(), hard);
            return hard;
        }

        Set<BlockPos> keeps = cycleKeeps.getOrDefault(y, Set.of());
        Set<BlockPos> lights = cycleLights.getOrDefault(y, Set.of());
        BlockPos maidPos = maid.blockPosition();
        SpiralMinePlanner p = planner();

        // 1. 最近未处理坐标（区域 ±1 圈）
        MineTask best = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = p.getMinX() - 1; x <= p.getMaxX() + 1; x++)
        {
            for (int z = p.getMinZ() - 1; z <= p.getMaxZ() + 1; z++)
            {
                BlockPos pos = new BlockPos(x, y, z);
                if (layerProcessed.contains(pos) || lights.contains(pos) || isClaimed(pos)) continue;

                BlockState state = level.getBlockState(pos);
                MineTask.Type type;
                if (keeps.contains(pos))
                {
                    if (state.isAir())
                    {
                        type = MineTask.Type.FILL;                       // 空洞 → 补（§5）
                    }
                    else if (isScaffoldState(state))
                    {
                        layerProcessed.add(pos);                         // 垫脚完好 → 丢已处理
                        continue;
                    }
                    else
                    {
                        type = canProcess(level, pos, state)             // 非垫脚实体 → 换（§5）
                                ? MineTask.Type.REPLACE : null;
                    }
                }
                else
                {
                    if (state.isAir())
                    {
                        layerProcessed.add(pos);                         // 无方块 → 丢已处理
                        continue;
                    }
                    type = canProcess(level, pos, state) ? MineTask.Type.DESTROY : null;
                }

                if (type == null)
                {
                    if (!layerHardList.contains(pos))
                    {
                        layerHardList.add(pos.immutable());              // 无法处理 → 层困难表
                    }
                    continue;
                }

                double dist = pos.distSqr(maidPos);
                if (dist < bestDist)
                {
                    bestDist = dist;
                    best = new MineTask(pos, type);
                }
            }
        }
        if (best != null)
        {
            layerSubtasks.put(maid.getUUID(), best);
            return best;
        }

        // 2. 坐标池空 → 光源阶段（§10）
        MineTask lightTask = nearestLightTask(level, maid, y);
        if (lightTask != null)
        {
            layerSubtasks.put(maid.getUUID(), lightTask);
            return lightTask;
        }

        // 3. 层完成结算：他人在干 → 让位等待（§3）
        if (!layerSubtasks.isEmpty()) return null;
        advanceLayer(level);
        return null;
    }

    // 女仆完成子任务：解绑 + 坐标记入已处理（取灯指令不记坐标）
    public void completeWork(EntityMaid maid, MineTask task)
    {
        layerSubtasks.remove(maid.getUUID());
        if (task.type() == MineTask.Type.FETCH_LIGHT) return;
        layerProcessed.add(task.pos());
    }

    // 女仆放弃子任务：仅解绑，坐标回池子（§3 离场协议）
    public void releaseWork(EntityMaid maid)
    {
        layerSubtasks.remove(maid.getUUID());
    }

    // ===================== 派发辅助 =====================

    private boolean isClaimed(BlockPos pos)
    {
        for (MineTask task : layerSubtasks.values())
        {
            if (task.pos().equals(pos)) return true;
        }
        return false;
    }

    // 矿井困难表取任务（§6 步骤0）：取距女仆最近且当前能处理的坐标，出表派发
    // 已消失（空气）的坐标直接出表；仍不能处理的留在表内
    private MineTask takeHardTableTask(ServerLevel level, EntityMaid maid)
    {
        if (getDifficultyTable().isEmpty()) return null;
        BlockPos best = null;
        MineTask.Type bestType = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : getDifficultyTable())
        {
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir())
            {
                removeDifficulty(pos);
                continue;
            }
            if (!canProcess(level, pos, state)) continue;
            double dist = pos.distSqr(maid.blockPosition());
            if (dist < bestDist)
            {
                bestDist = dist;
                best = pos;
                bestType = MineTask.Type.DESTROY;
            }
        }
        if (best == null) return null;
        removeDifficulty(best);
        return new MineTask(best.immutable(), bestType);
    }

    // 光源阶段（§10/§6 步骤2）：层内未放的光源位，有灯 → SETLIGHT，无灯 → FETCH_LIGHT
    private MineTask nearestLightTask(ServerLevel level, EntityMaid maid, int y)
    {
        Set<BlockPos> lights = cycleLights.getOrDefault(y, Set.of());
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : lights)
        {
            if (layerProcessed.contains(pos) || isClaimed(pos)) continue;
            if (level.getBlockState(pos).getLightEmission() > 0)
            {
                layerProcessed.add(pos);                             // 已有光源 → 视为已放
                continue;
            }
            double dist = pos.distSqr(maid.blockPosition());
            if (dist < bestDist)
            {
                bestDist = dist;
                best = pos;
            }
        }
        if (best == null) return null;
        return new MineTask(hasLightSource(maid) ? best : getBlockPos(),
                hasLightSource(maid) ? MineTask.Type.SETLIGHT : MineTask.Type.FETCH_LIGHT);
    }

    // 光源判定（B2 拍板）：主手或背包中存在"可放置且发光"的方块物品
    private static boolean hasLightSource(EntityMaid maid)
    {
        if (isLightStack(maid.getMainHandItem())) return true;
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            if (res.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock().defaultBlockState().getLightEmission() > 0)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isLightStack(ItemStack stack)
    {
        if (stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        return blockItem.getBlock().defaultBlockState().getLightEmission() > 0;
    }

    // 难度判定（§8 纯布尔，方块侧）：基岩类（破坏耗时 < 0）永远不能；
    // 工具等级部分（requiresCorrectToolForDrops + 主手判定）随女仆行为批次接入
    private static boolean canProcess(ServerLevel level, BlockPos pos, BlockState state)
    {
        return state.getDestroySpeed(level, pos) >= 0;
    }

    // 垫脚方块判定（§5）：泥土/木板/圆石/石头可作保留位垫脚
    private static boolean isScaffoldState(BlockState state)
    {
        var block = state.getBlock();
        return block.builtInRegistryHolder().is(BlockTags.DIRT)
                || block.builtInRegistryHolder().is(BlockTags.PLANKS)
                || block.builtInRegistryHolder().is(Tags.Blocks.COBBLESTONES)
                || block.builtInRegistryHolder().is(Tags.Blocks.STONES);
    }

    // ===================== 结算推进 =====================

    // 层结算（§6 步骤3）：层困难表上缴中心（§7），推进下一层；周期耗尽进下一周期；
    // 新层低于深度限制时在下次 requestWork 头部判为已挖尽（D2）
    private void advanceLayer(ServerLevel level)
    {
        for (BlockPos pos : layerHardList)
        {
            addDifficulty(pos);
        }
        layerHardList.clear();
        layerSubtasks.clear();
        layerProcessed.clear();

        layerIndex++;
        if (layerIndex >= cycleLayerYs.size())
        {
            cycle++;
            layerIndex = 0;
            cycleLayerYs.clear();
            cycleKeeps.clear();
            cycleLights.clear();
            ensureCycleComputed(level);
        }
    }

    // ===================== 挖尽状态（D2） =====================

    public boolean isExhausted()
    {
        return exhausted;
    }

    public void markExhausted()
    {
        this.exhausted = true;
        layerSubtasks.clear();
    }

    // ===================== 调试 =====================

    // 当前周期/层状态快照（调试命令用，触发周期数据懒计算）
    public String debugLayerInfo(ServerLevel level)
    {
        ensureCycleComputed(level);
        if (exhausted)
        {
            return String.format("已挖尽 | 周期C%d 层#%d", cycle, layerIndex);
        }
        if (layerIndex >= cycleLayerYs.size())
        {
            return String.format("周期C%d 无层可派(异常)", cycle);
        }
        int y = cycleLayerYs.get(layerIndex);
        Set<BlockPos> keeps = cycleKeeps.getOrDefault(y, Set.of());
        Set<BlockPos> lights = cycleLights.getOrDefault(y, Set.of());
        return String.format("周期C%d 层#%d/%d Y=%d(限%d) 保留%d 光源%d 已处理%d 子任务%d 层困难%d",
                cycle, layerIndex, cycleLayerYs.size(), y, depthBottomY(),
                keeps.size(), lights.size(), layerProcessed.size(), layerSubtasks.size(), layerHardList.size());
    }
}
