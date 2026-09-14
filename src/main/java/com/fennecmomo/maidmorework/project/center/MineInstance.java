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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

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
    // 诊断日志（2026-09-04 测试期临时接入，问题定位后移除）
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(MineInstance.class);

    private static String shortId(UUID uuid)
    {
        return uuid.toString().substring(0, 8);
    }
    // ===================== 多态序列化 =====================

    public static final MapCodec<MineInstance> MAP_CODEC =
        RecordCodecBuilder.mapCodec(inst -> inst.group(
            ProjectCenterInstance.MAP_CODEC.forGetter(MineInstance::asBase),
            BlockPos.CODEC.fieldOf("shaftCornerNW").forGetter(MineInstance::getShaftCornerNW),
            BlockPos.CODEC.fieldOf("shaftCornerSE").forGetter(MineInstance::getShaftCornerSE),
            Codec.BOOL.optionalFieldOf("exhausted", false).forGetter(MineInstance::isExhausted),
            Codec.INT.optionalFieldOf("cycle", 0).forGetter(MineInstance::getCycle),
            Codec.INT.optionalFieldOf("layerIndex", 0).forGetter(MineInstance::getLayerIndex),
            MineTask.CODEC.listOf().optionalFieldOf("mineHardTasks", List.of())
                    .forGetter(c -> new ArrayList<>(c.mineHardTasks)),
            BlockPos.CODEC.listOf().optionalFieldOf("sealedAir", List.of())
                    .forGetter(c -> new ArrayList<>(c.sealedAir)),
            BlockPos.CODEC.listOf().optionalFieldOf("sealedSolid", List.of())
                    .forGetter(c -> new ArrayList<>(c.sealedSolid)),
            BlockPos.CODEC.listOf().optionalFieldOf("sealedLights", List.of())
                    .forGetter(c -> new ArrayList<>(c.sealedLights)),
            Codec.STRING.listOf().optionalFieldOf("missingTools", List.of())
                    .forGetter(c -> new ArrayList<>(c.missingToolNotes))
        ).apply(inst, MineInstance::fromCodec));

    // Codec 工厂方法：基类字段整体解码后包装成矿井实例
    private static MineInstance fromCodec(ProjectCenterInstance base,
                                          BlockPos shaftCornerNW, BlockPos shaftCornerSE,
                                          boolean exhausted, int cycle, int layerIndex,
                                          List<MineTask> mineHardTasks,
                                          List<BlockPos> sealedAir, List<BlockPos> sealedSolid,
                                          List<BlockPos> sealedLights, List<String> missingToolNotes)
    {
        return new MineInstance(base, shaftCornerNW, shaftCornerSE, exhausted, cycle, layerIndex,
                mineHardTasks, sealedAir, sealedSolid, sealedLights, missingToolNotes);
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

    // ===================== 困难表 / 封存列表（D1 终版） =====================

    // 矿井自有"带类型"困难表（§7 调整：条目=坐标+动作类型，FILL 也能入表，持久化）
    // 来源：层结算上缴（基岩类）/行为侧七步工具流程判定不可挖掘/10s 记录位置检查违规
    private final List<MineTask> mineHardTasks = new ArrayList<>();

    // 封存列表（2026-09-04 拍板）：女仆完成一格即按动作分类封存
    //   空置封存 = 记录为空的位置不能有方块；实体封存 = 记录为实体的位置不能是空的；
    //   光源位封存 = 记录为灯位的位置不能是空的（灯被打掉 → 重派 SETLIGHT，而非补垫脚）
    private final List<BlockPos> sealedAir = new ArrayList<>();
    private final List<BlockPos> sealedSolid = new ArrayList<>();
    private final List<BlockPos> sealedLights = new ArrayList<>();

    // 10s 记录位置检查（分片轮询，游标不持久化）
    private static final int REFRESH_INTERVAL_TICKS = 200;  // 10 秒
    private static final int REFRESH_SLICE = 256;           // 每轮每列表检查分片
    private long lastRefreshGameTime = 0;
    private int airCursor = 0;
    private int solidCursor = 0;
    private int lightCursor = 0;

    // 层结算复核等待（2 秒，2026-09-04 拍板：层清空后等掉落物落网再推进）
    private static final long SETTLE_WAIT_TICKS = 40;
    private long settleWaitUntil = 0;

    // 层暂停（2026-09-04 拍板）：结算复核后不可处理方块超过该层总格数 10% → 暂停，
    // 等待补货后自动恢复（临时状态，重载后由结算流程重新推导）
    private static final float PAUSE_RATIO = 0.10f;
    private boolean layerPaused = false;

    // 缺工具数据记录（UI 阶段展示用，当前先持久化字符串描述）
    private final List<String> missingToolNotes = new ArrayList<>();

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
                         boolean exhausted, int cycle, int layerIndex,
                         List<MineTask> mineHardTasks,
                         List<BlockPos> sealedAir, List<BlockPos> sealedSolid,
                         List<BlockPos> sealedLights, List<String> missingToolNotes)
    {
        super(base);
        this.shaftCornerNW = shaftCornerNW;
        this.shaftCornerSE = shaftCornerSE;
        this.exhausted = exhausted;
        this.cycle = cycle;
        this.layerIndex = layerIndex;
        this.mineHardTasks.addAll(mineHardTasks);
        this.sealedAir.addAll(sealedAir);
        this.sealedSolid.addAll(sealedSolid);
        this.sealedLights.addAll(sealedLights);
        this.missingToolNotes.addAll(missingToolNotes);
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

    // 螺旋规划器 Y 起点 = 矿井方块下一层（2026-09-04 拍板修正：第一层=方块下面一层）
    private SpiralMinePlanner planner()
    {
        if (planner == null)
        {
            planner = new SpiralMinePlanner(getBlockPos().getX(), getBlockPos().getY() - 1,
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
        // 入口清理、矿井方块保护、初始支撑只属于 C0。
        // 之前每个周期都重复添加这些层，导致 C1 重新回到 mineY+1/mineY+2 顶部，
        // 把入口/首层平台再次当作待挖区域。
        if (cycle == 0)
        {
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
        }

        // 边界围墙：mineY 以下每层向外一圈（入口区保持开放）
        for (int y : new ArrayList<>(keepsByY.keySet()))
        {
            if (y < mineY)
            {
                addBoundaryKeeps(keepsByY.get(y), y, p);
            }
        }

        // 光源（2026-09-04 拍板）：只在每条边中段的 3×2 平台处放，每平台 2 盏（每周期 8 盏）
        // 灯1 = 平台中柱走廊侧外一格，贴平台侧面（平台=保留区，支撑永久）
        // 灯2 = 平台端柱上方三格(y+3)，贴边界围墙内面（围墙=保留区，支撑永久；高于墙区的层跳过）
        // 旧方案（每层过道行放灯）作废：灯踩着的支撑方块属于下层挖掘区，下层一挖灯就掉
        int cx = getBlockPos().getX();
        int cz = getBlockPos().getZ();
        for (int edge = 0; edge <= 3; edge++)
        {
            int half = (edge % 2 == 0) ? (p.getL() - 1) / 2 : (p.getW() - 1) / 2;
            List<BlockPos> platformKeeps = p.getKeepBlocks(cycle, edge, half);
            if (platformKeeps.isEmpty()) continue;
            int h = platformKeeps.get(0).getY();
            Set<BlockPos> lights = cycleLights.computeIfAbsent(h, key -> new HashSet<>());
            switch (edge)
            {
                case 0 -> // 北边：平台行 z=minZ..minZ+1，墙在 minZ-1
                {
                    lights.add(new BlockPos(cx, h, p.getMinZ() + 2));
                    if (h + 3 < mineY)
                    {
                        lights.add(new BlockPos(cx, h + 3, p.getMinZ()));
                    }
                }
                case 2 -> // 南边：平台行 z=maxZ-1..maxZ，墙在 maxZ+1
                {
                    lights.add(new BlockPos(cx, h, p.getMaxZ() - 2));
                    if (h + 3 < mineY)
                    {
                        lights.add(new BlockPos(cx, h + 3, p.getMaxZ()));
                    }
                }
                case 1 -> // 西侧（planner case1：列 x=minX..minX+1），墙在 minX-1，沿 z 行进
                {
                    lights.add(new BlockPos(p.getMinX() + 2, h, cz));
                    if (h + 3 < mineY)
                    {
                        lights.add(new BlockPos(p.getMinX(), h + 3, cz));
                    }
                }
                case 3 -> // 东侧（planner case3：列 x=maxX-1..maxX），墙在 maxX+1
                {
                    lights.add(new BlockPos(p.getMaxX() - 2, h, cz));
                    if (h + 3 < mineY)
                    {
                        lights.add(new BlockPos(p.getMaxX(), h + 3, cz));
                    }
                }
            }
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
        if (!(maid.level() instanceof ServerLevel level)) return null;

        // 挖尽 = 待机（2026-09-04 拍板）：不推进新层，但封存检查（10s 周期）发现的维护任务
        // （落入矿道的沙子/被打掉的灯等）仍会派发处理，处理完回待机
        if (exhausted)
        {
            MineTask maintenance = takeHardTableTask(level, maid);
            if (maintenance != null)
            {
                layerSubtasks.put(maid.getUUID(), maintenance);
                LOGGER.info("[MineDebug] 女仆={} 派发 {}(挖尽维护) @ {}", shortId(maid.getUUID()),
                        maintenance.type(), maintenance.pos().toShortString());
                return maintenance;
            }
            return null;
        }

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
            LOGGER.info("[MineDebug] 女仆={} 派发 {}(困难表) @ {}", shortId(maid.getUUID()),
                    hard.type(), hard.pos().toShortString());
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
                // 层困难表内坐标不重复派发（2026-09-04 拍板：先检查再分配，表内由复核机制统一重判）
                if (layerHardList.contains(pos)) continue;

                BlockState state = level.getBlockState(pos);
                // 灯位保护（2026-09-04 拍板）：发光且非流体的方块一格永不进挖掘池
                // （防任意层级的误挖，含支撑方块被挖导致灯掉落的场景）
                if (state.getLightEmission() > 0 && state.getFluidState().isEmpty())
                {
                    layerProcessed.add(pos);
                    continue;
                }
                MineTask.Type type;
                if (keeps.contains(pos))
                {
                    if (state.isAir())
                    {
                        type = MineTask.Type.FILL;                       // 空洞 → 补（§5）
                    }
                    else if (isScaffoldState(state) || !state.is(Tags.Blocks.ORES))
                    {
                        // 垫脚完好 → 丢已处理；非矿物实体（砂岩/石头等）→ 无需替换（2026-09-04 拍板）
                        layerProcessed.add(pos);
                        continue;
                    }
                    else
                    {
                        type = isProcessableBy(level, pos, state, maid)   // 保留位矿物 → 挖掉回收（§5）
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
                    type = isProcessableBy(level, pos, state, maid) ? MineTask.Type.DESTROY : null;
                }

                if (type == null)
                {
                    // 可行性不足（§8 派发侧五级检查全不成立）→ 层困难表 + 缺工具记录，不分配
                    if (!layerHardList.contains(pos))
                    {
                        layerHardList.add(pos.immutable());
                    }
                    recordMissingTool(state);
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
            LOGGER.info("[MineDebug] 女仆={} 派发 {} @ {}", shortId(maid.getUUID()),
                    best.type(), best.pos().toShortString());
            return best;
        }

        // 2. 坐标池空 → 光源阶段（§10）
        MineTask lightTask = nearestLightTask(level, maid, y);
        if (lightTask != null)
        {
            layerSubtasks.put(maid.getUUID(), lightTask);
            LOGGER.info("[MineDebug] 女仆={} 派发 {} @ {}", shortId(maid.getUUID()),
                    lightTask.type(), lightTask.pos().toShortString());
            return lightTask;
        }

        // 3. 层完成结算：他人在干 → 让位等待（§3）；无人干活 → 等 2 秒复核（D1 终版：
        //    掉落物落网期间派发扫描实时重判，2 秒后池子仍空才推进下一层）
        if (!layerSubtasks.isEmpty()) return null;
        long now = level.getGameTime();
        if (settleWaitUntil == 0)
        {
            settleWaitUntil = now + SETTLE_WAIT_TICKS;
            LOGGER.info("[MineDebug] 层池清空，2s复核等待 周期C{} 层#{} Y={}",
                    cycle, layerIndex, y);
            return null;
        }
        if (now < settleWaitUntil) return null;
        settleWaitUntil = 0;
        // 2s 复核后：层困难表逐条重判（可行域=仓库+在场女仆）——可处理的回池，本轮继续派发
        if (rejudgeLayerHardList(level)) return null;
        // 10% 终判：不可处理超过该层总格数 10% → 层暂停（等补货，10s 周期自动重判恢复）
        int total = layerTotalCells();
        if (layerHardList.size() > total * PAUSE_RATIO)
        {
            if (!layerPaused)
            {
                layerPaused = true;
                LOGGER.info("[MineDebug] 层暂停：不可处理 {}/{} 超过10% 周期C{} 层#{} 缺工具:{}",
                        layerHardList.size(), total, cycle, layerIndex, missingToolNotes);
            }
            return null;
        }
        layerPaused = false;
        // 剩余上缴带类型困难表，推进下一层
        for (BlockPos pos : layerHardList)
        {
            addHardTask(new MineTask(pos.immutable(), MineTask.Type.DESTROY));
        }
        layerHardList.clear();
        LOGGER.info("[MineDebug] 层结算推进 周期C{} 层#{} → 周期C{} 层#{}",
                cycle, layerIndex, cycleLayerYs.size() > layerIndex + 1 ? cycle : cycle + 1,
                layerIndex + 1 >= cycleLayerYs.size() ? 0 : layerIndex + 1);
        advanceLayer(level);
        return null;
    }

    // 女仆完成子任务：解绑 + 封存坐标 + 清除对应困难表条目 + 重置层复核计时
    // （2026-09-04 拍板：DESTROY → 空置封存；FILL/REPLACE → 实体封存；SETLIGHT → 光源位封存）
    public void completeWork(EntityMaid maid, MineTask task)
    {
        layerSubtasks.remove(maid.getUUID());
        settleWaitUntil = 0;
        switch (task.type())
        {
            case DESTROY -> sealAs(task.pos(), sealedAir);
            case FILL, REPLACE -> sealAs(task.pos(), sealedSolid);
            case SETLIGHT -> sealAs(task.pos(), sealedLights);
            default -> { }   // FETCH_LIGHT 非坐标任务
        }
        // 完成的坐标从带类型困难表移除（取表派发时不移除，完成才算解决）
        mineHardTasks.removeIf(t -> t.pos().equals(task.pos()));
    }

    // 封存坐标：先清三张封存列表中的旧记录（同一格重分类时移动），再按类别入表
    private void sealAs(BlockPos pos, List<BlockPos> target)
    {
        BlockPos immutable = pos.immutable();
        sealedAir.remove(immutable);
        sealedSolid.remove(immutable);
        sealedLights.remove(immutable);
        target.add(immutable);
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

    // 矿井带类型困难表取任务（§6 步骤0 终版）
    // 仅派出"当前可行域（请求女仆随身+仓库）下能处理"的条目——不可处理的留表，
    // 由 10s 封存检查/层结算复核统一重判；快照遍历防 ConcurrentModificationException
    private MineTask takeHardTableTask(ServerLevel level, EntityMaid maid)
    {
        if (mineHardTasks.isEmpty()) return null;
        MineTask best = null;
        double bestDist = Double.MAX_VALUE;
        List<MineTask> satisfied = new ArrayList<>();
        for (MineTask task : new ArrayList<>(mineHardTasks))
        {
            BlockPos pos = task.pos();
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            // 已解决判定按任务类型：DESTROY→空气；FILL→非空气；SETLIGHT→该格有光源
            // （2026-09-04 修正：SETLIGHT 的空气恰恰是"未解决"，沿用 default 会把任务秒删、永不派发）
            boolean solved = switch (task.type())
            {
                case FILL -> !state.isAir();
                case SETLIGHT -> state.getLightEmission() > 0;
                default -> state.isAir();
            };
            if (solved)
            {
                satisfied.add(task);
                continue;
            }
            // 基岩类 DESTROY 永远无法派发，留表
            if (task.type() != MineTask.Type.FILL && state.getDestroySpeed(level, pos) < 0) continue;
            if (!isProcessableBy(level, pos, state, maid)) continue;
            double dist = pos.distSqr(maid.blockPosition());
            if (dist < bestDist)
            {
                bestDist = dist;
                best = task;
            }
        }
        mineHardTasks.removeAll(satisfied);
        if (best != null)
        {
            LOGGER.info("[MineDebug] 困难表派出 {} @ {}", best.type(), best.pos().toShortString());
        }
        return best;
    }

    // 困难表入表（幂等，按坐标+类型去重）
    public void addHardTask(MineTask task)
    {
        for (MineTask t : mineHardTasks)
        {
            if (t.pos().equals(task.pos()) && t.type() == task.type()) return;
        }
        mineHardTasks.add(task);
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

    // 光源判定（B2 拍板 2026-09-04 修正：只认火把——灯笼无法贴墙挂放，待后续单独立项支持）
    private static boolean hasLightSource(EntityMaid maid)
    {
        if (isLightStack(maid.getMainHandItem())) return true;
        CombinedResourceHandler<ItemResource> inv = maid.getItemManager().getAvailableBackpackInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            if (res.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() == net.minecraft.world.level.block.Blocks.TORCH)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isLightStack(ItemStack stack)
    {
        return !stack.isEmpty() && stack.getItem() == net.minecraft.world.item.Items.TORCH;
    }

    // ===================== 可行性判定（§8 终版：先检查再分配） =====================

    // 派发侧五级检查（对请求女仆）：基岩类/全不成立 → false（进层困难表，不分配）；
    // ②自身推荐 ③仓库推荐 ④非必须空手 ⑤自身必要 ⑥仓库必要 → 任一成立 → true（可分配）
    private boolean isProcessableBy(ServerLevel level, BlockPos pos, BlockState state, EntityMaid maid)
    {
        if (state.getDestroySpeed(level, pos) < 0) return false;
        if (invHasRecommended(maid, state) || warehouseHasRecommended(state)) return true;
        if (!state.requiresCorrectToolForDrops()) return true;
        if (invHasNecessary(maid, state) || warehouseHasNecessary(state)) return true;
        return false;
    }

    // 复核可行域（层困难表重判用）：仓库 + 中心在场女仆的随身工具
    private boolean isProcessableByContext(ServerLevel level, BlockPos pos, BlockState state, List<EntityMaid> maids)
    {
        if (state.getDestroySpeed(level, pos) < 0) return false;
        if (warehouseHasRecommended(state)) return true;
        if (!state.requiresCorrectToolForDrops()) return true;
        if (warehouseHasNecessary(state)) return true;
        for (EntityMaid maid : maids)
        {
            if (invHasRecommended(maid, state) || invHasNecessary(maid, state)) return true;
        }
        return false;
    }

    // 自身（主手+背包）是否存在推荐工具（类型匹配且能正确掉落）
    private static boolean invHasRecommended(EntityMaid maid, BlockState state)
    {
        if (matchesType(maid.getMainHandItem(), state) && maid.getMainHandItem().isCorrectToolForDrops(state)) return true;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            ItemStack probe = res.toStack(1);
            if (matchesType(probe, state) && probe.isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    // 自身（主手+背包）是否存在必要工具（能正确掉落即可，不限类型）
    private static boolean invHasNecessary(EntityMaid maid, BlockState state)
    {
        if (maid.getMainHandItem().isCorrectToolForDrops(state)) return true;
        ItemStacksResourceHandler inv = maid.getItemManager().getMaidInv();
        for (int i = 0; i < inv.size(); i++)
        {
            ItemResource res = inv.getResource(i);
            if (res.isEmpty()) continue;
            if (res.toStack(1).isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    private boolean warehouseHasRecommended(BlockState state)
    {
        return countWarehouse(stack -> matchesType(stack, state)) > 0;
    }

    private boolean warehouseHasNecessary(BlockState state)
    {
        return countWarehouse(stack -> !stack.isEmpty() && stack.isCorrectToolForDrops(state)) > 0;
    }

    // 方块期望的工具类型（按原版"可挖掘"标签）：镐/锹/斧/锄
    private static boolean matchesType(ItemStack stack, BlockState state)
    {
        return (state.is(BlockTags.MINEABLE_WITH_PICKAXE) && stack.is(ItemTags.PICKAXES))
                || (state.is(BlockTags.MINEABLE_WITH_SHOVEL) && stack.is(ItemTags.SHOVELS))
                || (state.is(BlockTags.MINEABLE_WITH_AXE) && stack.is(ItemTags.AXES))
                || (state.is(BlockTags.MINEABLE_WITH_HOE) && stack.is(ItemTags.HOES));
    }

    // 缺工具数据记录（UI 阶段展示用，当前先持久化字符串描述，去重+上限16条）
    public void recordMissingTool(BlockState state)
    {
        String need;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) need = "镐";
        else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) need = "锹";
        else if (state.is(BlockTags.MINEABLE_WITH_AXE)) need = "斧";
        else if (state.is(BlockTags.MINEABLE_WITH_HOE)) need = "锄";
        else need = "任意工具";
        if (state.requiresCorrectToolForDrops()) need += "（需正确工具才掉落）";
        String note = need + " — 示例: " + state.getBlock().getName().getString();
        if (!missingToolNotes.contains(note))
        {
            missingToolNotes.add(note);
            if (missingToolNotes.size() > 16) missingToolNotes.remove(0);
        }
    }

    // 垫脚方块判定（§5）：草方块 + 泥土/木板/圆石/石头（2026-09-04 拍板：草方块显式计入，
    // 保留位遇到草方块直接跳过，不再走"挖掉再垫"的 REPLACE 流程）
    private static boolean isScaffoldState(BlockState state)
    {
        var block = state.getBlock();
        return block == net.minecraft.world.level.block.Blocks.GRASS_BLOCK
                || block.builtInRegistryHolder().is(BlockTags.DIRT)
                || block.builtInRegistryHolder().is(BlockTags.PLANKS)
                || block.builtInRegistryHolder().is(Tags.Blocks.COBBLESTONES)
                || block.builtInRegistryHolder().is(Tags.Blocks.STONES);
    }

    // ===================== 结算推进 =====================

    // 层结算（§6 步骤3）：层困难坐标上缴矿井带类型困难表（§7 调整），推进下一层；
    // 周期耗尽进下一周期；新层低于深度限制时在下次 requestWork 头部判为已挖尽（D2）
    private void advanceLayer(ServerLevel level)
    {
        reconcileLayerSeals();
        for (BlockPos pos : layerHardList)
        {
            addHardTask(new MineTask(pos.immutable(), MineTask.Type.DESTROY));
        }
        layerHardList.clear();
        layerSubtasks.clear();
        layerProcessed.clear();
        settleWaitUntil = 0;

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

    // 结算封存对齐（2026-09-04 拍板）：以当前周期规划器的保留区为真相，
    // 重核本层区域所有格子的封存类别（灯位→光源封存、保留格→实体封存、非保留格→空置封存），
    // 修正历史误分类（如楼梯格被标"应空"导致 10s 检查误拆楼梯）
    private void reconcileLayerSeals()
    {
        SpiralMinePlanner p = planner();
        int y = cycleLayerYs.get(layerIndex);
        Set<BlockPos> keeps = cycleKeeps.getOrDefault(y, Set.of());
        Set<BlockPos> lights = cycleLights.getOrDefault(y, Set.of());
        for (int x = p.getMinX() - 1; x <= p.getMaxX() + 1; x++)
        {
            for (int z = p.getMinZ() - 1; z <= p.getMaxZ() + 1; z++)
            {
                BlockPos pos = new BlockPos(x, y, z);
                if (lights.contains(pos)) sealAs(pos, sealedLights);
                else if (keeps.contains(pos)) sealAs(pos, sealedSolid);
                else sealAs(pos, sealedAir);
            }
        }
    }

    // ===================== 10 秒记录位置检查（D1 终版） =====================

    // 由 ProjectCenterManager 全局 tick 驱动；基类 tick 对矿井早退（projectTypeId 为空）无副作用
    @Override
    public void tick(ServerLevel level)
    {
        super.tick(level);
        long now = level.getGameTime();
        if (lastRefreshGameTime == 0)
        {
            lastRefreshGameTime = now;
            return;
        }
        if (now - lastRefreshGameTime < REFRESH_INTERVAL_TICKS) return;
        lastRefreshGameTime = now;
        refreshSealedLists(level);
        if (layerPaused) tryResumeLayer(level);
    }

    // 暂停层 10s 重判：可处理比例回落到阈值内 → 自动恢复（工具来源=仓库+在场女仆）
    private void tryResumeLayer(ServerLevel level)
    {
        rejudgeLayerHardList(level);
        int total = layerTotalCells();
        if (layerHardList.size() <= total * PAUSE_RATIO)
        {
            layerPaused = false;
            missingToolNotes.clear();
            LOGGER.info("[MineDebug] 层暂停解除 周期C{} 层#{} 困难表残留{}",
                    cycle, layerIndex, layerHardList.size());
        }
    }

    // 层困难表重判（可行域=仓库+在场女仆随身工具）：可处理的移出层困难表回池派发
    // 返回 true = 本轮有条目恢复可处理
    private boolean rejudgeLayerHardList(ServerLevel level)
    {
        if (layerHardList.isEmpty()) return false;
        List<EntityMaid> maids = new ArrayList<>();
        for (UUID uuid : getMemberIds())
        {
            if (level.getEntity(uuid) instanceof EntityMaid m) maids.add(m);
        }
        List<BlockPos> recovered = new ArrayList<>();
        for (BlockPos pos : layerHardList)
        {
            if (!level.isLoaded(pos)) continue;
            if (isProcessableByContext(level, pos, level.getBlockState(pos), maids))
            {
                recovered.add(pos);
            }
        }
        layerHardList.removeAll(recovered);
        if (!recovered.isEmpty())
        {
            LOGGER.info("[MineDebug] 层困难表复核：{} 个坐标恢复可处理", recovered.size());
        }
        return !recovered.isEmpty();
    }

    // 该层扫描区域总格数（(L+2)×(W+2)），10% 暂停阈值的分母
    private int layerTotalCells()
    {
        SpiralMinePlanner p = planner();
        return (p.getMaxX() - p.getMinX() + 3) * (p.getMaxZ() - p.getMinZ() + 3);
    }

    // 检查封存列表（2026-09-04 拍板：新增光源位封存——灯被打掉 → 重派 SETLIGHT 而非补垫脚）
    // 分片轮询（每轮每列表至多 REFRESH_SLICE 格），单 tick 成本恒定，游标跨轮持续推进
    private void refreshSealedLists(ServerLevel level)
    {
        int airEnd = checkSealedSlice(level, sealedAir, airCursor, MineTask.Type.DESTROY, false);
        airCursor = airEnd;
        int solidEnd = checkSealedSlice(level, sealedSolid, solidCursor, MineTask.Type.FILL, true);
        solidCursor = solidEnd;
        int lightEnd = checkSealedSlice(level, sealedLights, lightCursor, MineTask.Type.SETLIGHT, true);
        lightCursor = lightEnd;
    }

    // 检查一段封存列表并推进游标；satisfiedWhenEmpty=true → "为空"算违规（实体/灯位）；false → "非空"算违规（空置）
    // 返回推进后的游标
    private int checkSealedSlice(ServerLevel level, List<BlockPos> list, int cursor,
                                 MineTask.Type violationType, boolean satisfiedWhenEmpty)
    {
        if (list.isEmpty()) return 0;
        if (cursor >= list.size()) cursor = 0;
        int end = Math.min(list.size(), cursor + REFRESH_SLICE);
        for (int i = cursor; i < end; i++)
        {
            BlockPos pos = list.get(i);
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            boolean violated = satisfiedWhenEmpty ? state.isAir() : !state.isAir();
            if (violated)
            {
                MineTask task = new MineTask(pos.immutable(), violationType);
                LOGGER.info("[MineDebug] 10s检查违规 {} @ {} (期望非空实际{})", violationType,
                        pos.toShortString(),
                        violated && state.isAir() ? "空气" : state.getBlock().getName().getString());
                addHardTask(task);
            }
        }
        return end >= list.size() ? 0 : end;
    }

    // ===================== 挖尽状态（D2） =====================

    public boolean isExhausted()
    {
        return exhausted;
    }

    public boolean isLayerPaused()
    {
        return layerPaused;
    }

    // 挖尽后释放所有在线成员，但不删除矿井实例/方块（D2：实例与仓库保留）
    public void releaseAllMembers(ServerLevel level)
    {
        for (UUID uuid : new ArrayList<>(getMemberIds()))
        {
            if (level.getEntity(uuid) instanceof EntityMaid maid)
            {
                releaseWork(maid);
                ProjectCenterManager.releaseMaid(maid);
            }
        }
    }

    // 信息包附加数据（缺工具列表，中心面板显示"缺少：xxx"）
    @Override
    protected List<String> infoMissingTools()
    {
        return List.copyOf(missingToolNotes);
    }

    // 灯位支撑方向推算（2026-09-04 拍板）：灯位必靠某一面墙的内侧 1~2 格，
    // 支撑方向 = 四向中"到边界最短"的外侧（北墙→-z、南墙→+z、西墙→-x、东墙→+x）
    // 例：井宽 11，北边灯1 (cx, h, minZ+2) → 支撑 (cx, h, minZ+1)，方向 -z
    public net.minecraft.core.Direction supportDirection(BlockPos pos)
    {
        SpiralMinePlanner p = planner();
        int distWest = pos.getX() - p.getMinX();
        int distEast = p.getMaxX() - pos.getX();
        int distNorth = pos.getZ() - p.getMinZ();
        int distSouth = p.getMaxZ() - pos.getZ();
        int min = Math.min(Math.min(distWest, distEast), Math.min(distNorth, distSouth));
        if (min == distNorth) return net.minecraft.core.Direction.NORTH;
        if (min == distSouth) return net.minecraft.core.Direction.SOUTH;
        if (min == distWest) return net.minecraft.core.Direction.WEST;
        return net.minecraft.core.Direction.EAST;
    }

    public void markExhausted()
    {
        this.exhausted = true;
        layerSubtasks.clear();
        LOGGER.info("[MineDebug] 矿井已挖尽 周期C{} 层#{} 困难表{} 空置封存{} 实体封存{}",
                cycle, layerIndex, mineHardTasks.size(), sealedAir.size(), sealedSolid.size());
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
        return String.format("周期C%d 层#%d/%d Y=%d(限%d) 保留%d 光源%d 子任务%d 层困难%d %s| 困难表%d 空置封存%d 实体封存%d",
                cycle, layerIndex, cycleLayerYs.size(), y, depthBottomY(),
                keeps.size(), lights.size(), layerSubtasks.size(), layerHardList.size(),
                layerPaused ? "[层暂停] " : "",
                mineHardTasks.size(), sealedAir.size(), sealedSolid.size());
    }
}
