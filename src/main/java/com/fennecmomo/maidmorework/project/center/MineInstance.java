package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.project.ProjectBase;
import com.fennecmomo.maidmorework.project.mine.MineCenterRegistration;
import com.fennecmomo.maidmorework.project.mine.MineShaftProject;
import com.fennecmomo.maidmorework.project.mine.MineTask;
import com.fennecmomo.maidmorework.project.mine.MineTunnelProject;
import com.fennecmomo.maidmorework.project.mine.SpiralMinePlanner;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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

    // 控制方块封存记录（2026-09-04 拍板：带周期/层/边，矿道工程跨周期恢复靠它）
    public record ControlEntry(BlockPos pos, int cycle, int layerY, int edge)
    {
        public static final Codec<ControlEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(ControlEntry::pos),
                Codec.INT.fieldOf("cycle").forGetter(ControlEntry::cycle),
                Codec.INT.fieldOf("layerY").forGetter(ControlEntry::layerY),
                Codec.INT.fieldOf("edge").forGetter(ControlEntry::edge)
        ).apply(inst, ControlEntry::new));
    }

    // 当前周期控制位元数据（层 Y → 控制方块位置 + 边号）
    private record ControlMeta(BlockPos pos, int edge) { }

    // 矿道几何：挖格（两层）+ 地板维护格 + 地面火把位
    private record TunnelPlan(Set<BlockPos> dig, Set<BlockPos> floor, Set<BlockPos> torches) { }
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
            BlockPos.CODEC.listOf().optionalFieldOf("sealedFloorLights", List.of())
                    .forGetter(c -> new ArrayList<>(c.sealedFloorLights)),
            ControlEntry.CODEC.listOf().optionalFieldOf("sealedControlRefs", List.of())
                    .forGetter(c -> new ArrayList<>(c.sealedControls)),
            Codec.STRING.listOf().optionalFieldOf("missingTools", List.of())
                    .forGetter(c -> new ArrayList<>(c.missingToolNotes))
        ).apply(inst, MineInstance::fromCodec));

    // Codec 工厂方法：基类字段整体解码后包装成矿井实例
    private static MineInstance fromCodec(ProjectCenterInstance base,
                                          BlockPos shaftCornerNW, BlockPos shaftCornerSE,
                                          boolean exhausted, int cycle, int layerIndex,
                                          List<MineTask> mineHardTasks,
                                          List<BlockPos> sealedAir, List<BlockPos> sealedSolid,
                                          List<BlockPos> sealedLights, List<BlockPos> sealedFloorLights,
                                          List<ControlEntry> sealedControls,
                                          List<String> missingToolNotes)
    {
        return new MineInstance(base, shaftCornerNW, shaftCornerSE, exhausted, cycle, layerIndex,
                mineHardTasks, sealedAir, sealedSolid, sealedLights, sealedFloorLights,
                sealedControls, missingToolNotes);
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
    private final List<Integer> nextCycleLayerYs = new ArrayList<>();      // 下一周期的层（跨周期窗口用）
    private final Set<Integer> computedCycles = new HashSet<>();           // 已计算过层数据的周期
    private final Map<Integer, Set<BlockPos>> cycleKeeps = new HashMap<>();
    private final Map<Integer, Set<BlockPos>> cycleLights = new HashMap<>();
    private final Map<Integer, ControlMeta> cycleControls = new HashMap<>();   // 层 Y → 该层矿道控制方块位

    // 当前层运行时状态（不持久化，实时方块状态即进度真相）
    // 2026-09-04：改为按层各存一份（竖井同开三层，层之间不能互相污染）
    private final Map<Integer, Set<BlockPos>> layerProcessed = new HashMap<>();   // 层Y → 已处理
    private final Map<Integer, List<BlockPos>> layerHardList = new HashMap<>();    // 层Y → 层困难表
    private final Map<UUID, MineTask> layerSubtasks = new HashMap<>();
    private final Map<Integer, Long> settleWaitUntil = new HashMap<>();            // 层Y → 2s 复核倒计时
    private final Set<Integer> layerPaused = new HashSet<>();                      // 暂停中的层Y

    // ===================== 困难表 / 封存列表（D1 终版） =====================

    // 矿井自有"带类型"困难表（§7 调整：条目=坐标+动作类型，FILL 也能入表，持久化）
    // 来源：层结算上缴（基岩类）/行为侧七步工具流程判定不可挖掘/10s 记录位置检查违规
    private final List<MineTask> mineHardTasks = new ArrayList<>();

    // 封存列表（2026-09-04 拍板）：女仆完成一格即按动作分类封存
    //   空置封存 = 记录为空的位置不能有方块；实体封存 = 记录为实体的位置不能是空的；
    //   光源位封存 = 记录为灯位的位置不能是空的（灯被打掉 → 重派 SETLIGHT，而非补垫脚）
    //   控制位封存 = 记录为矿道层控制方块的位置（方块被挖掉 → 重派 PLACE_CONTROL；随矿井中心销毁）
    private final List<BlockPos> sealedAir = new ArrayList<>();
    private final List<BlockPos> sealedSolid = new ArrayList<>();
    private final List<BlockPos> sealedLights = new ArrayList<>();
    private final List<BlockPos> sealedFloorLights = new ArrayList<>();
    private final List<ControlEntry> sealedControls = new ArrayList<>();

    // 封存按层索引（运行时，2026-09-04 拍板"每帧扫一层"）：层 Y → 该层四类封存集合，
    // sealAs/unseal 同步维护，读档后按平铺列表重建；每 tick 检查一层，全矿一轮约 2~3 秒
    private static final class LayerSeals
    {
        final Set<BlockPos> air = new HashSet<>();
        final Set<BlockPos> solid = new HashSet<>();
        final Set<BlockPos> lights = new HashSet<>();
        final Set<BlockPos> floorLights = new HashSet<>();
    }

    private final Map<Integer, LayerSeals> sealsByLayer = new HashMap<>();
    private List<Integer> sealLayerOrder = new ArrayList<>();
    private boolean sealOrderDirty = true;
    private boolean sealIndexBuilt = false;
    private int sealLayerCursor = 0;
    private int openAirCursor = 0;      // 露天列清理轮询游标（每 10 秒一条矿道）

    // 矿道完工缓存（控制方块位置 → 已挖完；避免每次找活重复扫描已完成矿道）
    private final Set<BlockPos> tunnelDone = new HashSet<>();

    // 10s 周期维护（分片轮询已改为"每 tick 扫一层"，见 checkSealsOneLayer）
    private static final int REFRESH_INTERVAL_TICKS = 200;  // 10 秒
    private long lastRefreshGameTime = 0;

    // 卡死熔断采样（2026-09-04 拍板：由矿井侧承担——只有矿井方块的状态绝对稳定，5 秒一次）
    // 女仆距目标无进展累计 10 秒 → 下发一次性通知，行为侧消费后传送至矿井方块旁并重置导航状态
    private static final int STUCK_SAMPLE_TICKS = 100;      // 采样间隔（5 秒）
    private static final int STUCK_LIMIT_TICKS = 200;       // 无进展累计上限（10 秒）
    private static final double STUCK_GOAL_REACH_SQ = 16;   // 目标/中心豁免距离平方（4 格）
    private final Map<UUID, BlockPos> stuckGoal = new HashMap<>();     // 当前目标（绑定子任务，无则中心）
    private final Map<UUID, Double> stuckBestDist = new HashMap<>();   // 距目标历史最近距离平方
    private final Map<UUID, BlockPos> stuckLastPos = new HashMap<>();  // 上次采样位置（陆地有位移算进展）
    private final Map<UUID, BlockPos> travelGoals = new HashMap<>();   // 女仆上报的目的地（无=原地待命，不判卡死）
    private final Map<UUID, Integer> stuckTicks = new HashMap<>();
    private final Set<UUID> stuckRecovery = new HashSet<>();
    private final Map<UUID, Integer> absentSamples = new HashMap<>();  // 绑定女仆失联采样计数（活跃任务熔断兜底）
    private long lastStuckSampleTime = 0;

    // 层结算复核等待（2 秒，2026-09-04 拍板：层清空后等掉落物落网再推进）
    private static final long SETTLE_WAIT_TICKS = 40;

    // 层暂停（2026-09-04 拍板）：结算复核后不可处理方块超过该层总格数 10% → 暂停，
    // 等待补货后自动恢复（临时状态，重载后由结算流程重新推导）
    private static final float PAUSE_RATIO = 0.10f;

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
                         List<BlockPos> sealedLights, List<BlockPos> sealedFloorLights,
                         List<ControlEntry> sealedControls,
                         List<String> missingToolNotes)
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
        this.sealedFloorLights.addAll(sealedFloorLights);
        this.sealedControls.addAll(sealedControls);
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

    // 层数据懒计算驱动（2026-09-04 跨周期窗口）：当前周期 + 需要时预计算下一周期
    // （竖井窗口=当前层+往下两层，跨周期无缝；周期只是内部计算概念，对玩家无感知）
    private void ensureLayersComputed(ServerLevel level)
    {
        if (cycleLayerYs.isEmpty())
        {
            computeCycle(cycle, level);
        }
        if (layerIndex + 2 >= cycleLayerYs.size() && nextCycleLayerYs.isEmpty())
        {
            computeCycle(cycle + 1, level);
        }
    }

    // 计算指定周期的任务数据：保留区/光源，按 Y 层分组（懒计算，重载后重建）
    // 结构沿用旧版 computeCycle：螺旋保留区 + 矿井方块保护 + 入口清理 + 支撑层 + 围墙 + 光源 + 栏杆
    private void computeCycle(int c, ServerLevel level)
    {
        if (!computedCycles.add(c)) return;

        SpiralMinePlanner p = planner();

        // 收集本周期所有 (n, idx) 的保留区，按 Y 层合并
        Map<Integer, Set<BlockPos>> keepsByY = new HashMap<>();
        int n = 0;
        int idx = 0;
        int limit = edgeStepLimit(n);
        while (true)
        {
            for (BlockPos k : p.getKeepBlocks(c, n, idx))
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
        if (c == 0)
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

            // 顶层平台两侧挡墙（2026-09-04 拍板）：矿井方块所在支撑带两侧各一道 2 格高墙，两端留口
            int cz0 = getBlockPos().getZ();
            Set<BlockPos> wallY = keepsByY.computeIfAbsent(mineY, key -> new HashSet<>());
            Set<BlockPos> wallY1 = keepsByY.computeIfAbsent(mineY + 1, key -> new HashSet<>());
            for (int x = p.getMinX(); x <= p.getMaxX(); x++)
            {
                for (int dz : new int[]{-2, 2})
                {
                    BlockPos w1 = new BlockPos(x, mineY, cz0 + dz);
                    BlockPos w2 = new BlockPos(x, mineY + 1, cz0 + dz);
                    wallY.add(w1);
                    wallY1.add(w2);
                    if (!sealedSolid.contains(w1)) sealAs(w1, sealedSolid);
                    if (!sealedSolid.contains(w2)) sealAs(w2, sealedSolid);
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

        // 走道内侧栏杆（2026-09-04 拍板）：每层保留格朝中心扩 1 格成栏杆位（地板延展）+ 往上 2 格实心；
        // 转角 2×2 平台不加（会挡转弯）；首层（矿井方块正下方那层 = 周期 0 第一层）例外
        addRailings(keepsByY, c, c == 0 ? mineY - 1 : Integer.MIN_VALUE);
        // 历史周期补栏杆（幂等，仅当前周期计算时跑一次）：已挖过的层靠 10s 实体封存检查把栏杆补砌起来
        if (c == cycle)
        {
            for (int pc = 0; pc < cycle; pc++)
            {
                Map<Integer, Set<BlockPos>> past = new HashMap<>();
                int pn = 0;
                int pidx = 0;
                int plimit = edgeStepLimit(pn);
                while (true)
                {
                    for (BlockPos k : p.getKeepBlocks(pc, pn, pidx))
                    {
                        past.computeIfAbsent(k.getY(), key -> new HashSet<>()).add(k.immutable());
                    }
                    pidx++;
                    if (pidx > plimit)
                    {
                        pn++;
                        if (pn > 3) break;
                        pidx = 0;
                        plimit = edgeStepLimit(pn);
                    }
                }
                addRailings(past, pc, Integer.MIN_VALUE);
            }
        }

        // 光源（2026-09-04 拍板，同日修正）：只在每条边中段的 3×2 平台处放，每平台 1 盏（灯2）：
        //   灯2 = 平台端柱上方三格(y+3)，贴边界围墙内面（围墙=保留区，支撑永久；高于墙区的层跳过）
        //   灯1（平台内侧那根）已取消——位置被走道内侧栏杆占用
        // 旧方案（每层过道行放灯）作废：灯踩着的支撑方块属于下层挖掘区，下层一挖灯就掉
        int cx = getBlockPos().getX();
        int cz = getBlockPos().getZ();
        for (int edge = 0; edge <= 3; edge++)
        {
            int half = (edge % 2 == 0) ? (p.getL() - 1) / 2 : (p.getW() - 1) / 2;
            List<BlockPos> platformKeeps = p.getKeepBlocks(c, edge, half);
            if (platformKeeps.isEmpty()) continue;
            int h = platformKeeps.get(0).getY();
            Set<BlockPos> lights = cycleLights.computeIfAbsent(h, key -> new HashSet<>());
            // 矿道层控制方块位（2026-09-04 拍板）：平台"中心列 × 贴墙行"，即灯2 正下方 3 格
            switch (edge)
            {
                case 0 -> cycleControls.put(h, new ControlMeta(new BlockPos(cx, h, p.getMinZ()), 0));
                case 1 -> cycleControls.put(h, new ControlMeta(new BlockPos(p.getMinX(), h, cz), 1));
                case 2 -> cycleControls.put(h, new ControlMeta(new BlockPos(cx, h, p.getMaxZ()), 2));
                case 3 -> cycleControls.put(h, new ControlMeta(new BlockPos(p.getMaxX(), h, cz), 3));
            }
            switch (edge)
            {
                case 0 -> // 北边：平台行 z=minZ..minZ+1，墙在 minZ-1
                {
                    if (h + 3 < mineY)
                    {
                        lights.add(new BlockPos(cx, h + 3, p.getMinZ()));
                    }
                }
                case 2 -> // 南边：平台行 z=maxZ-1..maxZ，墙在 maxZ+1
                {
                    if (h + 3 < mineY)
                    {
                        lights.add(new BlockPos(cx, h + 3, p.getMaxZ()));
                    }
                }
                case 1 -> // 西侧（planner case1：列 x=minX..minX+1），墙在 minX-1，沿 z 行进
                {
                    if (h + 3 < mineY)
                    {
                        lights.add(new BlockPos(p.getMinX(), h + 3, cz));
                    }
                }
                case 3 -> // 东侧（planner case3：列 x=maxX-1..maxX），墙在 maxX+1
                {
                    if (h + 3 < mineY)
                    {
                        lights.add(new BlockPos(p.getMaxX(), h + 3, cz));
                    }
                }
            }
        }

        cycleKeeps.putAll(keepsByY);
        if (c == cycle)
        {
            cycleLayerYs.addAll(keepsByY.keySet());
            cycleLayerYs.sort(Comparator.reverseOrder());
            // 重载恢复等场景的越界保护
            if (layerIndex >= cycleLayerYs.size()) layerIndex = 0;
        }
        else
        {
            nextCycleLayerYs.addAll(keepsByY.keySet());
            nextCycleLayerYs.sort(Comparator.reverseOrder());
        }
    }

    // 竖井窗口（2026-09-04 拍板）：当前层 + 往下两层，跨周期无缝；i=0 为当前层
    // 返回 [cycle, layerY]，越界或低于深度底返回 null（2026-09-04 修正：预挖层不得超出矿井深度范围）
    private int[] windowLayer(int i)
    {
        int idx = layerIndex + i;
        if (idx < cycleLayerYs.size())
        {
            int y = cycleLayerYs.get(idx);
            return y < depthBottomY() ? null : new int[]{cycle, y};
        }
        int rem = idx - cycleLayerYs.size();
        if (rem < nextCycleLayerYs.size())
        {
            int y = nextCycleLayerYs.get(rem);
            return y < depthBottomY() ? null : new int[]{cycle + 1, y};
        }
        return null;
    }

    // 走道内侧栏杆（2026-09-04 拍板）：每层保留格朝中心扩 1 格成栏杆位（地板延展），
    // 该格并入保留区并往上再放 2 格实心（2 格高墙，防女仆被挤进竖井）；
    // 楼梯交汇的 2×2 平台（矿井四角）不加（会挡转弯），首层（firstLayerY）例外；
    // 同时清理旧"灯1"封存（灯1 已取消，位置被栏杆占用）
    private void addRailings(Map<Integer, Set<BlockPos>> keepsByY, int cycleIndex, int firstLayerY)
    {
        int cx = getBlockPos().getX();
        int cz = getBlockPos().getZ();
        SpiralMinePlanner p = planner();
        for (int edge = 0; edge <= 3; edge++)
        {
            int limit = (edge % 2 == 0)
                    ? Math.max(0, p.getL() - 3)
                    : Math.max(0, p.getW() - 3);
            for (int idx = 0; idx <= limit; idx++)
            {
                for (BlockPos cell : p.getKeepBlocks(cycleIndex, edge, idx))
                {
                    int y = cell.getY();
                    if (y != firstLayerY && isCornerCell(cell, cx, cz, p)) continue;
                    BlockPos rail = railCell(cell, edge);
                    if (rail == null) continue;
                    Set<BlockPos> sameLayer = keepsByY.getOrDefault(y, Set.of());
                    if (sameLayer.contains(rail)) continue;      // 内侧紧邻仍是保留格（走道内排）→ 不是外缘
                    for (int dy = 0; dy <= 2; dy++)
                    {
                        BlockPos pos = new BlockPos(rail.getX(), y + dy, rail.getZ());
                        keepsByY.computeIfAbsent(pos.getY(), key -> new HashSet<>()).add(pos);
                        if (!sealedSolid.contains(pos)) sealAs(pos, sealedSolid);   // 已处理层靠 10s 检查补砌
                        sealedLights.remove(pos);                  // 旧灯1 封存清理
                        sealedFloorLights.remove(pos);
                    }
                }
            }
        }
    }

    // 该格是否落在矿井四角的 2×2 楼梯交汇平台内
    private static boolean isCornerCell(BlockPos cell, int cx, int cz, SpiralMinePlanner p)
    {
        int halfL = (p.getL() - 1) / 2;
        int halfW = (p.getW() - 1) / 2;
        return Math.abs(cell.getX() - cx) >= halfL - 1 && Math.abs(cell.getZ() - cz) >= halfW - 1;
    }

    // 朝矿井中心方向扩一格（按边定方向，2026-09-04 修正：按轴推在角上会推错方向；只有第一层有角平台栏杆，
    // 按边定后首层角平台自然得到 2 格宽、与同边其余栏杆方向一致的墙体）
    private static BlockPos railCell(BlockPos cell, int edge)
    {
        return switch (edge)
        {
            case 0 -> cell.offset(0, 0, 1);     // 北边：往 +Z（中心方向）
            case 1 -> cell.offset(1, 0, 0);     // 西边：往 +X
            case 2 -> cell.offset(0, 0, -1);    // 南边：往 -Z
            case 3 -> cell.offset(-1, 0, 0);    // 东边：往 -X
            default -> null;
        };
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

    // ===================== 工程制派活（2026-09-04 拍板：竖井层/矿道都是工程） =====================

    // 工程解析：恢复手头工程；否则在"可加入/可新建"的候选里随机挑一个
    // 候选 = 当前层竖井工程（不存在则可新建，y 非法时关闭） + 现有未完成矿道工程（有空位）
    //        + 未挖完且无在飞工程的矿道（可新建）
    private ProjectBase resolveProject(ServerLevel level, EntityMaid maid)
    {
        UUID maidUuid = maid.getUUID();

        // 1. 恢复手头工程
        UUID assignedId = getAssignedProjectId(maidUuid);
        if (assignedId != null)
        {
            ProjectBase p = ProjectCenterManager.findProject(assignedId, ProjectBase.class);
            if (p != null && !p.isCompleted() && getManagedProjects().contains(p))
            {
                return p;
            }
            unassignProject(maidUuid);
        }

        // 2. 收集候选：竖井（窗口内三层各一个"加入/新建"选项）+ 矿道（可加入/可新建）
        List<int[]> shaftOptions = new ArrayList<>();       // [cycle, layerY]
        List<ProjectBase> joinable = new ArrayList<>();
        Set<BlockPos> tunnelHaveProject = new HashSet<>();
        for (ProjectBase p : getManagedProjects())
        {
            if (p.isCompleted()) continue;
            if (p instanceof MineShaftProject shaft)
            {
                if (!inShaftWindow(shaft.getCycle(), shaft.getLayerY()))
                {
                    shaft.markCompleted();      // 窗口外残留工程：标记完成，等基类移除
                }
                continue;
            }
            if (p instanceof MineTunnelProject tunnel)
            {
                tunnelHaveProject.add(tunnel.getPosition());
                if (tunnel.hasAvailableSlot()) joinable.add(tunnel);
            }
        }
        if (!exhausted)
        {
            for (int i = 0; i < 3; i++)
            {
                int[] w = windowLayer(i);
                if (w != null) shaftOptions.add(w);
            }
        }
        List<ControlEntry> creatableTunnels = new ArrayList<>();
        for (ControlEntry e : sealedControls)
        {
            if (tunnelDone.contains(e.pos())) continue;
            if (tunnelHaveProject.contains(e.pos())) continue;
            creatableTunnels.add(e);
        }
        int optionCount = shaftOptions.size() + joinable.size() + creatableTunnels.size();
        if (optionCount == 0) return null;
        int r = level.getRandom().nextInt(optionCount);

        if (r < shaftOptions.size())
        {
            int[] w = shaftOptions.get(r);
            return joinOrCreateShaft(level, maid, w[0], w[1]);
        }
        r -= shaftOptions.size();
        if (r < joinable.size())
        {
            ProjectBase p = joinable.get(r);
            if (p.claim(maidUuid))
            {
                assignProject(maidUuid, p.getId());
                return p;
            }
            return null;
        }
        r -= joinable.size();
        return createTunnelProject(level, maid, creatableTunnels.get(r));
    }

    // 该（周期,层）是否在竖井开放窗口内（当前层 + 往下两层，跨周期）
    private boolean inShaftWindow(int c, int layerY)
    {
        for (int i = 0; i < 3; i++)
        {
            int[] w = windowLayer(i);
            if (w != null && w[0] == c && w[1] == layerY) return true;
        }
        return false;
    }

    // 加入或新建指定（周期,层）的竖井工程（容量不限）
    private MineShaftProject joinOrCreateShaft(ServerLevel level, EntityMaid maid, int c, int y)
    {
        UUID maidUuid = maid.getUUID();
        for (ProjectBase p : getManagedProjects())
        {
            if (p instanceof MineShaftProject shaft && !shaft.isCompleted()
                    && shaft.getCycle() == c && shaft.getLayerY() == y)
            {
                if (shaft.claim(maidUuid))
                {
                    assignProject(maidUuid, shaft.getId());
                    return shaft;
                }
                return null;
            }
        }
        MineShaftProject created = new MineShaftProject(
                new BlockPos(getBlockPos().getX(), y, getBlockPos().getZ()), c, y);
        created.setDimension(level.dimension());
        if (!created.claim(maidUuid)) return null;
        claimTargets(List.of(), created);
        assignProject(maidUuid, created.getId());
        LOGGER.info("[MineDebug] 新建竖井层工程 周期C{} Y={} 女仆={}", c, y, shortId(maidUuid));
        return created;
    }

    // 新建矿道工程（容量 3）：同时把该矿道地板封存（实体位，10s 检查补洞）
    private MineTunnelProject createTunnelProject(ServerLevel level, EntityMaid maid, ControlEntry ref)
    {
        MineTunnelProject created = new MineTunnelProject(ref.pos(), ref.cycle(), ref.layerY(), ref.edge());
        created.setDimension(level.dimension());
        if (!created.claim(maid.getUUID())) return null;
        claimTargets(List.of(), created);
        assignProject(maid.getUUID(), created.getId());
        TunnelPlan plan = buildTunnelPlan(level, ref.cycle(), ref.layerY(), ref.edge(), true);
        if (plan != null)
        {
            for (BlockPos pos : plan.floor())
            {
                sealAs(pos, sealedSolid);
            }
        }
        cleanupOpenAirTunnel(level, ref);
        LOGGER.info("[MineDebug] 新建矿道工程 周期C{} Y={} 边={} 控制方块={} 女仆={}",
                ref.cycle(), ref.layerY(), ref.edge(), ref.pos().toShortString(), shortId(maid.getUUID()));
        return created;
    }

    // 是否还有未完工的矿道（挖尽后成员释放的闸门：矿道全清才放人）
    public boolean hasPendingTunnels()
    {
        for (ControlEntry e : sealedControls)
        {
            if (!tunnelDone.contains(e.pos())) return true;
        }
        return false;
    }

    // ===================== 矿道几何（2026-09-04 拍板） =====================
    //   纵道 = 3 格宽（平台中心列），从平台贴墙行外侧一格挖到范围边缘；
    //   横道 = 1 格宽，位于纵道往外第 2、5、8…格（首条隔 1 格，其后每条隔 2 格），贯通范围宽度；
    //   高度 2：挖格在层高 h+1/h+2；地板在 h（纳入实体封存，10s 检查补洞）
    //   露天跳过（2026-09-04 修正）：地表高度 ≤ 地板层的列不挖/不封/不放灯（矿道挖出地面会往空气里垫垫脚石）
    private TunnelPlan buildTunnelPlan(ServerLevel level, int cycle, int layerY, int edge, boolean skipOpenAir)
    {
        if (edge < 0 || edge > 3) return null;
        SpiralMinePlanner p = planner();
        int half = (edge % 2 == 0) ? (p.getL() - 1) / 2 : (p.getW() - 1) / 2;
        List<BlockPos> platform = p.getKeepBlocks(cycle, edge, half);
        if (platform.isEmpty()) return null;
        int h = platform.get(0).getY();
        if (h != layerY) return null;
        int cx = getBlockPos().getX();
        int cz = getBlockPos().getZ();
        int rMinX = getMinCorner().getX();
        int rMaxX = getMaxCorner().getX();
        int rMinZ = getMinCorner().getZ();
        int rMaxZ = getMaxCorner().getZ();
        Set<BlockPos> dig = new HashSet<>();
        Set<BlockPos> floor = new HashSet<>();
        Set<BlockPos> torches = new HashSet<>();
        switch (edge)
        {
            case 0 ->  // 北：主道往 -Z
            {
                for (int z = p.getMinZ() - 1; z >= rMinZ; z--)
                {
                    for (int x = cx - 1; x <= cx + 1; x++) addTunnelColumn(level, skipOpenAir, dig, floor, x, z, h);
                }
                for (int z = p.getMinZ() - 2; z >= rMinZ; z -= 3)
                {
                    for (int x = rMinX; x <= rMaxX; x++) addTunnelColumn(level, skipOpenAir, dig, floor, x, z, h);
                    addLaneTorches(level, skipOpenAir, torches, true, z, cx, rMinX, rMaxX, h);
                }
            }
            case 1 ->  // 西：主道往 -X
            {
                for (int x = p.getMinX() - 1; x >= rMinX; x--)
                {
                    for (int z = cz - 1; z <= cz + 1; z++) addTunnelColumn(level, skipOpenAir, dig, floor, x, z, h);
                }
                for (int x = p.getMinX() - 2; x >= rMinX; x -= 3)
                {
                    for (int z = rMinZ; z <= rMaxZ; z++) addTunnelColumn(level, skipOpenAir, dig, floor, x, z, h);
                    addLaneTorches(level, skipOpenAir, torches, false, x, cz, rMinZ, rMaxZ, h);
                }
            }
            case 2 ->  // 南：主道往 +Z
            {
                for (int z = p.getMaxZ() + 1; z <= rMaxZ; z++)
                {
                    for (int x = cx - 1; x <= cx + 1; x++) addTunnelColumn(level, skipOpenAir, dig, floor, x, z, h);
                }
                for (int z = p.getMaxZ() + 2; z <= rMaxZ; z += 3)
                {
                    for (int x = rMinX; x <= rMaxX; x++) addTunnelColumn(level, skipOpenAir, dig, floor, x, z, h);
                    addLaneTorches(level, skipOpenAir, torches, true, z, cx, rMinX, rMaxX, h);
                }
            }
            case 3 ->  // 东：主道往 +X
            {
                for (int x = p.getMaxX() + 1; x <= rMaxX; x++)
                {
                    for (int z = cz - 1; z <= cz + 1; z++) addTunnelColumn(level, skipOpenAir, dig, floor, x, z, h);
                }
                for (int x = p.getMaxX() + 2; x <= rMaxX; x += 3)
                {
                    for (int z = rMinZ; z <= rMaxZ; z++) addTunnelColumn(level, skipOpenAir, dig, floor, x, z, h);
                    addLaneTorches(level, skipOpenAir, torches, false, x, cz, rMinZ, rMaxZ, h);
                }
            }
        }
        return new TunnelPlan(dig, floor, torches);
    }

    // 该列在矿道地板层是否露天（地表高度 ≤ 地板层 h）
    private static boolean isOpenColumn(ServerLevel level, int x, int z, int h)
    {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) <= h;
    }

    private static void addTunnelColumn(ServerLevel level, boolean skipOpenAir, Set<BlockPos> dig,
                                        Set<BlockPos> floor, int x, int z, int h)
    {
        if (skipOpenAir && isOpenColumn(level, x, z, h)) return;
        dig.add(new BlockPos(x, h + 1, z));
        dig.add(new BlockPos(x, h + 2, z));
        floor.add(new BlockPos(x, h, z));
    }

    // 一条横道的火把位（2026-09-04 拍板：主道中心交叉点 1 根，之后往两侧每 6 格 1 根（两灯之间空 5 格），插在地板上）
    //   alongX=true：火把行固定 fixedCoord=z，中心 centerCoord=cx，范围 from..to = 范围 X
    //   alongX=false：火把列固定 fixedCoord=x，中心 centerCoord=cz，范围 from..to = 范围 Z
    private static void addLaneTorches(ServerLevel level, boolean skipOpenAir, Set<BlockPos> torches, boolean alongX,
                                       int fixedCoord, int centerCoord, int from, int to, int h)
    {
        if (alongX)
        {
            if (!skipOpenAir || !isOpenColumn(level, centerCoord, fixedCoord, h))
            {
                torches.add(new BlockPos(centerCoord, h + 1, fixedCoord));
            }
            for (int k = 6; centerCoord - k >= from; k += 6)
            {
                if (skipOpenAir && isOpenColumn(level, centerCoord - k, fixedCoord, h)) continue;
                torches.add(new BlockPos(centerCoord - k, h + 1, fixedCoord));
            }
            for (int k = 6; centerCoord + k <= to; k += 6)
            {
                if (skipOpenAir && isOpenColumn(level, centerCoord + k, fixedCoord, h)) continue;
                torches.add(new BlockPos(centerCoord + k, h + 1, fixedCoord));
            }
        }
        else
        {
            if (!skipOpenAir || !isOpenColumn(level, fixedCoord, centerCoord, h))
            {
                torches.add(new BlockPos(fixedCoord, h + 1, centerCoord));
            }
            for (int k = 6; centerCoord - k >= from; k += 6)
            {
                if (skipOpenAir && isOpenColumn(level, fixedCoord, centerCoord - k, h)) continue;
                torches.add(new BlockPos(fixedCoord, h + 1, centerCoord - k));
            }
            for (int k = 6; centerCoord + k <= to; k += 6)
            {
                if (skipOpenAir && isOpenColumn(level, fixedCoord, centerCoord + k, h)) continue;
                torches.add(new BlockPos(fixedCoord, h + 1, centerCoord + k));
            }
        }
    }

    // 矿道派活：先挖格（最近优先；空气跳过、流动流体跳过、源流体→REPLACE、实体→按现有分类与可行性）；
    // 挖格全清 → 标记完工（火把工序后续接入）
    private MineTask nextTunnelTask(ServerLevel level, EntityMaid maid, MineTunnelProject tunnel)
    {
        TunnelPlan plan = buildTunnelPlan(level, tunnel.getCycle(), tunnel.getLayerY(), tunnel.getEdge(), true);
        if (plan == null)
        {
            tunnel.markCompleted();
            tunnelDone.add(tunnel.getPosition());
            return null;
        }
        BlockPos best = null;
        MineTask.Type bestType = null;
        double bestDist = Double.MAX_VALUE;
        boolean anyRemaining = false;
        for (BlockPos pos : plan.dig())
        {
            if (isClaimed(pos))
            {
                anyRemaining = true;
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.getLightEmission() > 0) continue;         // 已放好的火把/光源 → 视为已处理（防挖灯循环）
            if (!state.getFluidState().isEmpty() && !state.getFluidState().isSource())
            {
                continue;                                   // 流动流体：等源清完自然干涸
            }
            anyRemaining = true;
            MineTask.Type type;
            if (!state.getFluidState().isEmpty())
            {
                type = MineTask.Type.REPLACE;               // 源流体：清掉+收瓶+塞子
            }
            else
            {
                type = MineTask.Type.DESTROY;
            }
            double dist = pos.distSqr(maid.blockPosition());
            if (dist >= bestDist) continue;                 // 更远的先不看（限制可行性检查次数）
            if (type == MineTask.Type.DESTROY && !isProcessableBy(level, pos, state, maid))
            {
                addHardTask(new MineTask(pos.immutable(), MineTask.Type.DESTROY));   // 暂不可处理 → 困难表
                continue;
            }
            bestDist = dist;
            best = pos;
            bestType = type;
        }
        // 2) 火把候选（2026-09-04 拍板：火把并入派发、谁近做谁——矿道不露天，挖到哪亮到哪防刷怪；
        //    主道中心交叉点 1 根，之后往两侧每 6 格 1 根（两灯之间空 5 格），插在地板上）
        BlockPos bestTorch = null;
        MineTask.Type bestTorchType = null;
        double bestTorchDist = Double.MAX_VALUE;
        boolean anyTorchRemaining = false;
        for (BlockPos pos : plan.torches())
        {
            if (isClaimed(pos))
            {
                anyTorchRemaining = true;
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getLightEmission() > 0) continue;      // 已有光源 → 视为已放
            if (!state.getFluidState().isEmpty() && !state.getFluidState().isSource())
            {
                continue;                                    // 流动流体：等源清完自然干涸
            }
            anyTorchRemaining = true;
            MineTask.Type type;
            if (state.isAir())
            {
                if (!hasLightSource(maid))
                {
                    MineTask fetch = new MineTask(getBlockPos(), MineTask.Type.FETCH_LIGHT);
                    layerSubtasks.put(maid.getUUID(), fetch);
                    LOGGER.info("[MineDebug] 女仆={} 派发 FETCH_LIGHT(矿道火把) @ {}",
                            shortId(maid.getUUID()), getBlockPos().toShortString());
                    return fetch;                            // 去矿井方块取火把
                }
                type = MineTask.Type.SETLIGHT_FLOOR;
            }
            else if (!state.getFluidState().isEmpty())
            {
                type = MineTask.Type.REPLACE;                // 源流体：清掉+塞子
            }
            else
            {
                type = MineTask.Type.DESTROY;                // 有阻碍先挖开
            }
            double dist = pos.distSqr(maid.blockPosition());
            if (dist >= bestTorchDist) continue;
            if (type == MineTask.Type.DESTROY && !isProcessableBy(level, pos, state, maid))
            {
                addHardTask(new MineTask(pos.immutable(), MineTask.Type.DESTROY));
                continue;
            }
            bestTorchDist = dist;
            bestTorch = pos;
            bestTorchType = type;
        }

        // 3) 派发：挖格与火把谁近做谁（会先挖通火把位，挖通后立即点亮）
        if (best != null && (bestTorch == null || bestDist <= bestTorchDist))
        {
            MineTask task = new MineTask(best, bestType);
            layerSubtasks.put(maid.getUUID(), task);
            LOGGER.info("[MineDebug] 女仆={} 派发 {}(矿道 周期C{} Y={} 边={}) @ {}",
                    shortId(maid.getUUID()), task.type(), tunnel.getCycle(), tunnel.getLayerY(),
                    tunnel.getEdge(), best.toShortString());
            return task;
        }
        if (bestTorch != null)
        {
            MineTask task = new MineTask(bestTorch, bestTorchType);
            layerSubtasks.put(maid.getUUID(), task);
            LOGGER.info("[MineDebug] 女仆={} 派发 {}(矿道火把 周期C{} Y={} 边={}) @ {}",
                    shortId(maid.getUUID()), task.type(), tunnel.getCycle(), tunnel.getLayerY(),
                    tunnel.getEdge(), bestTorch.toShortString());
            return task;
        }
        if (anyRemaining || anyTorchRemaining) return null;   // 有活但本轮派不出去（被认领/不可处理）→ 等待

        // 4) 挖格全清 + 火把全亮 → 完工
        tunnel.markCompleted();
        tunnelDone.add(tunnel.getPosition());
        // 隧道空气格封存（2026-09-04 拍板）：把"本来就空气"的格子也封上，掉东西/回填能被检查发现
        ensureSealIndex();
        for (BlockPos pos : plan.dig())
        {
            LayerSeals ls = sealsByLayer.get(pos.getY());
            if (ls != null && ls.air.contains(pos)) continue;
            sealAs(pos, sealedAir);
        }
        LOGGER.info("[MineDebug] 矿道完工 周期C{} Y={} 边={} 控制方块={}",
                tunnel.getCycle(), tunnel.getLayerY(), tunnel.getEdge(),
                tunnel.getPosition().toShortString());
        return null;
    }

    // 层结算推进时标记对应竖井层工程完成（基类 doProjectTick 下一 tick 移除并清空分配 → 女仆随机再分配）
    private void markShaftProjectComplete(int finCycle, int finLayerY)
    {
        for (ProjectBase p : getManagedProjects())
        {
            if (p instanceof MineShaftProject shaft && !shaft.isCompleted()
                    && shaft.getCycle() == finCycle && shaft.getLayerY() == finLayerY)
            {
                shaft.markCompleted();
                LOGGER.info("[MineDebug] 竖井层工程完工 周期C{} Y={}", finCycle, finLayerY);
            }
        }
    }

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

        // 挖尽 = 不推进新层；封存检查发现的维护任务 + 未完工矿道工程继续派发（2026-09-04：
        // 矿道是并行工程，挖尽只代表竖井到底，矿道全清后行为侧才释放成员）
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
            ProjectBase tunnelProject = resolveProject(level, maid);
            if (tunnelProject instanceof MineTunnelProject tunnel)
            {
                return nextTunnelTask(level, maid, tunnel);
            }
            return null;
        }

        ensureLayersComputed(level);
        if (layerIndex >= cycleLayerYs.size()) return null;
        int currentY = cycleLayerYs.get(layerIndex);
        if (currentY < depthBottomY())
        {
            markExhausted(level);
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

        // 0.5 工程解析（2026-09-04 拍板：竖井层/矿道都是工程，完工后随机再分配；竖井窗口=三层）
        ProjectBase project = resolveProject(level, maid);
        if (project == null) return null;
        if (project instanceof MineTunnelProject tunnel)
        {
            return nextTunnelTask(level, maid, tunnel);
        }
        MineShaftProject shaft = (MineShaftProject) project;
        int y = shaft.getLayerY();

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
                if (processedOf(y).contains(pos) || lights.contains(pos) || isClaimed(pos)) continue;
                // 层困难表内坐标不重复派发（2026-09-04 拍板：先检查再分配，表内由复核机制统一重判）
                if (hardListOf(y).contains(pos)) continue;

                BlockState state = level.getBlockState(pos);
                // 灯位保护（2026-09-04 拍板）：发光且非流体的方块一格永不进挖掘池
                // （防任意层级的误挖，含支撑方块被挖导致灯掉落的场景）
                if (state.getLightEmission() > 0 && state.getFluidState().isEmpty())
                {
                    processedOf(y).add(pos);
                    continue;
                }
                MineTask.Type type;
                if (!state.getFluidState().isEmpty())
                {
                    // 流体统一处理（2026-09-04 修正：非保留位水此前被派 DESTROY——水硬度 100 慢挖
                    // 且邻水回流，形成"永远挖不完"的死循环）：
                    //   源流体 → REPLACE（任意位置：清掉+收流体瓶；保留位再垫脚）
                    //   流动流体 → 保留位 FILL（垫脚顶掉）；非保留位（隧道）跳过，源清完后自然干涸
                    if (state.getFluidState().isSource())
                    {
                        type = MineTask.Type.REPLACE;
                    }
                    else if (keeps.contains(pos))
                    {
                        type = MineTask.Type.FILL;
                    }
                    else
                    {
                        processedOf(y).add(pos);
                        continue;
                    }
                }
                else if (keeps.contains(pos))
                {
                    if (state.isAir())
                    {
                        type = MineTask.Type.FILL;                       // 空洞 → 补（§5）
                    }
                    else if (isScaffoldState(state) || !state.is(Tags.Blocks.ORES))
                    {
                        // 垫脚完好 → 丢已处理；非矿物实体（砂岩/石头等）→ 无需替换（2026-09-04 拍板）
                        processedOf(y).add(pos);
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
                        processedOf(y).add(pos);                         // 无方块 → 丢已处理
                        continue;
                    }
                    type = isProcessableBy(level, pos, state, maid) ? MineTask.Type.DESTROY : null;
                }

                if (type == null)
                {
                    // 可行性不足（§8 派发侧五级检查全不成立）→ 层困难表 + 缺工具记录，不分配
                    if (!hardListOf(y).contains(pos))
                    {
                        hardListOf(y).add(pos.immutable());
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

        // 2.5 矿道层控制方块（2026-09-04 拍板）：光源工序完成后，女仆空手放置该层控制方块
        MineTask controlTask = nearestControlTask(level, y);
        if (controlTask != null)
        {
            layerSubtasks.put(maid.getUUID(), controlTask);
            LOGGER.info("[MineDebug] 女仆={} 派发 {} @ {}", shortId(maid.getUUID()),
                    controlTask.type(), controlTask.pos().toShortString());
            return controlTask;
        }

        // 3. 层完成结算（2026-09-04 拍板：结算推进只发生在当前层；预挖层工作做完即工程完工、女仆转场）
        if (y != currentY)
        {
            shaft.markCompleted();
            LOGGER.info("[MineDebug] 竖井预挖层工作完成 周期C{} Y={}（等轮到当前层再走结算）", cycle, y);
            return null;
        }
        // 当前层：他人在干 → 让位等待（§3）；无人干活 → 等 2 秒复核（D1 终版：
        // 掉落物落网期间派发扫描实时重判，2 秒后池子仍空才推进下一层）
        // 2026-09-04 修正：只算本层（竖井）任务，矿道工程的在飞任务不算"他人在干"
        for (MineTask boundTask : layerSubtasks.values())
        {
            if (isLayerTask(boundTask, currentY)) return null;
        }
        long now = level.getGameTime();
        Long waitUntil = settleWaitUntil.get(currentY);
        if (waitUntil == null || waitUntil == 0)
        {
            settleWaitUntil.put(currentY, now + SETTLE_WAIT_TICKS);
            LOGGER.info("[MineDebug] 层池清空，2s复核等待 周期C{} 层#{} Y={}",
                    cycle, layerIndex, currentY);
            return null;
        }
        if (now < waitUntil) return null;
        settleWaitUntil.remove(currentY);
        // 2s 复核后：层困难表逐条重判（可行域=仓库+在场女仆）——可处理的回池，本轮继续派发
        if (rejudgeLayerHardList(level, currentY)) return null;
        // 10% 终判：不可处理超过该层总格数 10% → 层暂停（等补货，10s 周期自动重判恢复）
        int total = layerTotalCells();
        if (hardListOf(currentY).size() > total * PAUSE_RATIO)
        {
            if (layerPaused.add(currentY))
            {
                LOGGER.info("[MineDebug] 层暂停：不可处理 {}/{} 超过10% 周期C{} 层#{} 缺工具:{}",
                        hardListOf(currentY).size(), total, cycle, layerIndex, missingToolNotes);
            }
            return null;
        }
        layerPaused.remove(currentY);
        // 剩余上缴带类型困难表，推进下一层
        for (BlockPos pos : hardListOf(currentY))
        {
            addHardTask(new MineTask(pos.immutable(), MineTask.Type.DESTROY));
        }
        hardListOf(currentY).clear();
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
        // 层结算倒计时只由本层（竖井）任务完成重置；矿道任务完成不重置（2026-09-04 修正：
        // 矿道火把反复完成会不断重置 2s 复核，导致竖井层永远结算不了；2026-09-04 三层化：按层各存）
        if (layerIndex < cycleLayerYs.size())
        {
            int curY = cycleLayerYs.get(layerIndex);
            if (isLayerTask(task, curY)) settleWaitUntil.remove(curY);
        }
        if (task.type() == MineTask.Type.FETCH_LIGHT) return;   // 取灯非坐标任务
        // 完成的坐标记入本层已处理（防止"临时塞子"等完成后被本层扫描立即重复派发）
        processedOf(task.pos().getY()).add(task.pos());
        switch (task.type())
        {
            case DESTROY -> sealAs(task.pos(), sealedAir);
            case FILL -> sealAs(task.pos(), sealedSolid);
            case REPLACE ->
            {
                // 按保留位分类封存（2026-09-04 拍板：非保留位"塞子"封"应空"，
                // 交由 10s 周期核查滞后挖除——那时邻位水源已清完，不会回流；
                // 保留位垫脚封"实体"永久保留）
                sealAs(task.pos(), isKeepPosition(task.pos()) ? sealedSolid : sealedAir);
            }
            case SETLIGHT -> sealAs(task.pos(), sealedLights);
            case SETLIGHT_FLOOR -> sealAs(task.pos(), sealedFloorLights);
            case PLACE_CONTROL -> sealControl(task.pos());
            default -> { }
        }
        // 完成的坐标从带类型困难表移除（取表派发时不移除，完成才算解决）
        mineHardTasks.removeIf(t -> t.pos().equals(task.pos()));
    }

    // 封存坐标：先清各张 BlockPos 封存列表 + 控制封存记录中的同格，再按类别入表（同步层索引）
    private void sealAs(BlockPos pos, List<BlockPos> target)
    {
        BlockPos immutable = pos.immutable();
        sealedAir.remove(immutable);
        sealedSolid.remove(immutable);
        sealedLights.remove(immutable);
        sealedFloorLights.remove(immutable);
        sealedControls.removeIf(e -> e.pos().equals(immutable));
        target.add(immutable);
        LayerSeals ls = sealsByLayer.get(immutable.getY());
        if (ls == null)
        {
            ls = new LayerSeals();
            sealsByLayer.put(immutable.getY(), ls);
            sealOrderDirty = true;
        }
        ls.air.remove(immutable);
        ls.solid.remove(immutable);
        ls.lights.remove(immutable);
        ls.floorLights.remove(immutable);
        if (target == sealedAir) ls.air.add(immutable);
        else if (target == sealedSolid) ls.solid.add(immutable);
        else if (target == sealedLights) ls.lights.add(immutable);
        else if (target == sealedFloorLights) ls.floorLights.add(immutable);
    }

    // 控制位封存（带周期/层/边）：完成/重核时调用；已有记录沿用其元数据（跨周期补放不丢周期信息）
    private void sealControl(BlockPos pos)
    {
        BlockPos immutable = pos.immutable();
        ControlEntry old = findControlEntry(immutable);
        int c = cycle;
        int ly = immutable.getY();
        int e = -1;
        if (old != null)
        {
            c = old.cycle();
            ly = old.layerY();
            e = old.edge();
        }
        else
        {
            for (var en : cycleControls.entrySet())
            {
                if (en.getValue().pos().equals(immutable))
                {
                    ly = en.getKey();
                    e = en.getValue().edge();
                    break;
                }
            }
        }
        sealAs(immutable, new ArrayList<>());   // 清其余列表（含旧控制记录）
        sealedControls.add(new ControlEntry(immutable, c, ly, e));
    }

    // 查控制封存记录（无则 null）
    private ControlEntry findControlEntry(BlockPos pos)
    {
        for (ControlEntry e : sealedControls)
        {
            if (e.pos().equals(pos)) return e;
        }
        return null;
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

    // 按层取的运行时集合（2026-09-04：竖井同开三层，层状态各存一份）
    private Set<BlockPos> processedOf(int y)
    {
        return layerProcessed.computeIfAbsent(y, k -> new HashSet<>());
    }

    private List<BlockPos> hardListOf(int y)
    {
        return layerHardList.computeIfAbsent(y, k -> new ArrayList<>());
    }

    // 该任务是否属于指定层的竖井工作（挖格/火把/控制方块）：
    // 用于层结算"他人在干"判定与层解绑范围——矿道任务（层高 h+1/h+2 或范围外）不干扰竖井层结算
    private boolean isLayerTask(MineTask task, int y)
    {
        BlockPos pos = task.pos();
        ControlMeta control = cycleControls.get(y);
        if (control != null && pos.equals(control.pos())) return true;
        if (cycleLights.getOrDefault(y, Set.of()).contains(pos)) return true;
        if (pos.getY() != y) return false;
        SpiralMinePlanner p = planner();
        return pos.getX() >= p.getMinX() - 1 && pos.getX() <= p.getMaxX() + 1
                && pos.getZ() >= p.getMinZ() - 1 && pos.getZ() <= p.getMaxZ() + 1;
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
            if (isClaimed(pos)) continue;      // 已被认领的分给别人会形成"车队"（2026-09-04 修正）
            BlockState state = level.getBlockState(pos);
            // 已解决判定按任务类型：DESTROY→空气；FILL→非空气；SETLIGHT→该格有光源；
            // PLACE_CONTROL→该格是控制方块
            // （2026-09-04 修正：SETLIGHT 的空气恰恰是"未解决"，沿用 default 会把任务秒删、永不派发）
            boolean solved = switch (task.type())
            {
                case FILL -> !state.isAir();
                case SETLIGHT -> state.getLightEmission() > 0;
                case SETLIGHT_FLOOR -> state.getLightEmission() > 0;
                case PLACE_CONTROL -> state.getBlock() == MineCenterRegistration.MINE_LAYER_CONTROL_BLOCK.get();
                default -> state.isAir();
            };
            if (solved)
            {
                satisfied.add(task);
                continue;
            }
            // 基岩类 DESTROY 永远无法派发，留表（FILL/PLACE_CONTROL/SETLIGHT_FLOOR 非挖掘任务不受此限）
            if (task.type() != MineTask.Type.FILL && task.type() != MineTask.Type.PLACE_CONTROL
                    && task.type() != MineTask.Type.SETLIGHT_FLOOR
                    && state.getDestroySpeed(level, pos) < 0) continue;
            // 控制方块/地面火把为空手放置或放置类任务，无工具可行性要求
            if (task.type() != MineTask.Type.PLACE_CONTROL && task.type() != MineTask.Type.SETLIGHT_FLOOR
                    && !isProcessableBy(level, pos, state, maid)) continue;
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
            if (processedOf(y).contains(pos) || isClaimed(pos)) continue;
            if (level.getBlockState(pos).getLightEmission() > 0)
            {
                processedOf(y).add(pos);                             // 已有光源 → 视为已放
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

    // 矿道层控制方块工序（2026-09-04 拍板）：该层有控制位且格上还不是控制方块 → PLACE_CONTROL
    // （空手生成放置：无物品、无取货、不存在缺货）
    private MineTask nearestControlTask(ServerLevel level, int y)
    {
        ControlMeta meta = cycleControls.get(y);
        if (meta == null) return null;
        BlockPos pos = meta.pos();
        if (!level.isLoaded(pos)) return null;
        if (isClaimed(pos)) return null;
        if (level.getBlockState(pos).getBlock() == MineCenterRegistration.MINE_LAYER_CONTROL_BLOCK.get()) return null;
        return new MineTask(pos, MineTask.Type.PLACE_CONTROL);
    }

    // ===================== 矿道控制器传送（2026-09-04 拍板） =====================

    // 已放置控制方块里的最高 Y（"第一个矿道控制器"；无则 null）
    public Integer controlTopY()
    {
        Integer top = null;
        for (ControlEntry e : sealedControls)
        {
            if (top == null || e.layerY() > top) top = e.layerY();
        }
        return top;
    }

    // 离给定点最近的控制方块位置（无则 null）
    public BlockPos controlNearest(BlockPos from)
    {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (ControlEntry e : sealedControls)
        {
            double d = e.pos().distSqr(from);
            if (d < bestDist)
            {
                bestDist = d;
                best = e.pos();
            }
        }
        return best;
    }

    // 范围调整后清矿道完工缓存（2026-09-04 拍板）：已完工矿道按新范围重新清查，
    // 多出来的格子重新变回可接工作；新格子的地板封存随工程重建
    @Override
    public void setRadius(int radius)
    {
        super.setRadius(radius);
        tunnelDone.clear();
        LOGGER.info("[MineDebug] 矿井范围调整为 {}，矿道完工缓存已清（矿道将按新范围重查）", radius);
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
        int finishedY = cycleLayerYs.get(layerIndex);
        // 竖井层工程完工标记（2026-09-04 拍板：层=工程，完工后基类清分配，女仆随机再分配）
        markShaftProjectComplete(cycle, finishedY);
        for (BlockPos pos : hardListOf(finishedY))
        {
            addHardTask(new MineTask(pos.immutable(), MineTask.Type.DESTROY));
        }
        layerHardList.remove(finishedY);
        layerPaused.remove(finishedY);
        // 只解绑本层任务；矿道工程的在飞任务保持绑定（2026-09-04 修正）
        layerSubtasks.entrySet().removeIf(e -> isLayerTask(e.getValue(), finishedY));
        layerProcessed.remove(finishedY);
        settleWaitUntil.remove(finishedY);

        layerIndex++;
        if (layerIndex >= cycleLayerYs.size())
        {
            cycle++;
            layerIndex = 0;
            cycleLayerYs.clear();
            nextCycleLayerYs.clear();
            computedCycles.clear();
            cycleKeeps.clear();
            cycleLights.clear();
            cycleControls.clear();
            ensureLayersComputed(level);
        }
    }

    // 结算封存对齐（2026-09-04 拍板）：以当前周期规划器的保留区为真相，
    // 重核本层区域所有格子的封存类别（控制位→控制封存、灯位→光源封存、保留格→实体封存、非保留格→空置封存），
    // 修正历史误分类（如楼梯格被标"应空"导致 10s 检查误拆楼梯）
    private void reconcileLayerSeals()
    {
        SpiralMinePlanner p = planner();
        int y = cycleLayerYs.get(layerIndex);
        Set<BlockPos> keeps = cycleKeeps.getOrDefault(y, Set.of());
        Set<BlockPos> lights = cycleLights.getOrDefault(y, Set.of());
        ControlMeta control = cycleControls.get(y);
        for (int x = p.getMinX() - 1; x <= p.getMaxX() + 1; x++)
        {
            for (int z = p.getMinZ() - 1; z <= p.getMaxZ() + 1; z++)
            {
                BlockPos pos = new BlockPos(x, y, z);
                if (control != null && pos.equals(control.pos())) sealControl(pos);
                else if (lights.contains(pos)) sealAs(pos, sealedLights);
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
        rescueBuriedMaids(level);
        // 封存检查（每 tick 一层，2026-09-04 改版：分摊负载，全矿一轮约 2~3 秒）
        checkSealsOneLayer(level);
        if (lastStuckSampleTime == 0) lastStuckSampleTime = now;
        if (now - lastStuckSampleTime >= STUCK_SAMPLE_TICKS)
        {
            lastStuckSampleTime = now;
            sampleStuckMaids(level);
            sweepLostBoundMaids(level);
        }
        if (lastRefreshGameTime == 0)
        {
            lastRefreshGameTime = now;
            return;
        }
        if (now - lastRefreshGameTime < REFRESH_INTERVAL_TICKS) return;
        lastRefreshGameTime = now;
        if (!layerPaused.isEmpty()) tryResumePausedLayers(level);
        refreshTunnelUpkeep(level);
    }

    // 行为侧每 tick 上报"当前要去的地方"（#23 修正 2026-09-04）：null = 原地待命/等待，
    // 不参与卡死判定（等待中的女仆不再被误传送）；有目的地才按接近/位移口径计卡死
    public void reportTravelGoal(UUID maidUuid, BlockPos goal)
    {
        if (goal == null)
        {
            travelGoals.remove(maidUuid);
        }
        else
        {
            travelGoals.put(maidUuid, goal.immutable());
        }
    }

    // 卡死采样（5 秒一次，2026-09-04 修正：位移判定会被水中漂移/浮动反复清零 → 改为"距目标无进展"判定）：
    //   目标 = 绑定子任务坐标，无绑定则矿井方块；
    //   离目标 ≤4 格（在施工）或离中心 ≤4 格（存取/待机）→ 正常清零；
    //   比历史最近距离更近 → 有进展，清零并更新最近值；否则累计，满 10 秒 → 标记一次性通知
    private void sampleStuckMaids(ServerLevel level)
    {
        for (UUID uuid : getMemberIds())
        {
            if (!(level.getEntity(uuid) instanceof EntityMaid maid) || maid.isRemoved())
            {
                clearStuckSample(uuid);
                continue;
            }
            BlockPos pos = maid.blockPosition();
            BlockPos goal = travelGoals.get(uuid);       // 行为侧上报的目的地（#23：无目的地不判卡死）
            if (goal == null)
            {
                clearStuckSample(uuid);
                continue;
            }
            double d = pos.distSqr(goal);
            BlockPos prevGoal = stuckGoal.put(uuid, goal);
            // 陆地位移也算有进展（2026-09-04 修正：长距离绕路/爬螺旋时"接近目标"不单调，
            // 只看接近会把正常走路误判成卡死；水里仍只看接近，水漂移必须能抓出来）
            BlockPos lastPos = stuckLastPos.put(uuid, pos.immutable());
            boolean movedOnLand = lastPos != null && !pos.equals(lastPos) && !maid.isInWater();
            if (!goal.equals(prevGoal) || d <= STUCK_GOAL_REACH_SQ
                    || pos.distSqr(getBlockPos()) <= STUCK_GOAL_REACH_SQ)
            {
                stuckBestDist.put(uuid, d);
                stuckTicks.remove(uuid);
                continue;
            }
            double best = stuckBestDist.getOrDefault(uuid, Double.MAX_VALUE);
            if (d < best)
            {
                stuckBestDist.put(uuid, d);
                stuckTicks.remove(uuid);
                continue;
            }
            if (movedOnLand)
            {
                stuckTicks.remove(uuid);
                continue;
            }
            int t = stuckTicks.merge(uuid, STUCK_SAMPLE_TICKS, Integer::sum);
            if (t == STUCK_SAMPLE_TICKS)
            {
                LOGGER.info("[MineDebug] 卡死采样：女仆={} 5秒无进展(距目标{}格, 目标{})",
                        shortId(uuid), (int) Math.sqrt(d), goal.toShortString());
            }
            if (t >= STUCK_LIMIT_TICKS)
            {
                clearStuckSample(uuid);
                stuckRecovery.add(uuid);
                LOGGER.info("[MineDebug] 女仆={} 卡死熔断(10秒无进展)→通知传送至矿井方块旁", shortId(uuid));
            }
        }
    }

    private void clearStuckSample(UUID uuid)
    {
        stuckGoal.remove(uuid);
        stuckBestDist.remove(uuid);
        stuckLastPos.remove(uuid);
        stuckTicks.remove(uuid);
    }

    // ===================== 活跃任务熔断（2026-09-04 拍板） =====================

    // 成员死亡处理（死亡事件入口）：归还绑定任务 + 释放工程席位；成员资格由调用方 leaveCenter 处理
    public void handleMemberLost(EntityMaid maid)
    {
        UUID uuid = maid.getUUID();
        MineTask task = releaseBoundTask(uuid);
        releaseAssignment(maid);
        LOGGER.info("[MineDebug] 任务归还：女仆={} 死亡 任务={}", shortId(uuid),
                task == null ? "无" : task.type() + " @ " + task.pos().toShortString());
    }

    // 兜底巡检（每 5 秒，随卡死采样）：绑定女仆实体不存在（区块卸载/被移除）→ 连续 2 次采样（10 秒）
    // 归还任务；实体在场但已死 → 立即归还并移出成员表。失联只归还任务不除名（可能只是区块卸载）
    private void sweepLostBoundMaids(ServerLevel level)
    {
        if (layerSubtasks.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(layerSubtasks.keySet()))
        {
            if (level.getEntity(uuid) instanceof EntityMaid maid && !maid.isRemoved())
            {
                absentSamples.remove(uuid);
                if (!maid.isAlive())
                {
                    MineTask task = releaseBoundTask(uuid);
                    forgetMember(uuid);
                    LOGGER.info("[MineDebug] 任务归还：女仆={} 已死亡 任务={}", shortId(uuid),
                            task == null ? "无" : task.type() + " @ " + task.pos().toShortString());
                }
                continue;
            }
            if (absentSamples.merge(uuid, 1, Integer::sum) >= 2)
            {
                MineTask task = releaseBoundTask(uuid);
                LOGGER.info("[MineDebug] 任务归还：女仆={} 失联(实体不存在) 任务={}", shortId(uuid),
                        task == null ? "无" : task.type() + " @ " + task.pos().toShortString());
            }
        }
    }

    // 解绑单个任务：困难表来源的坐标任务回表（取灯任务除外，坐标池会重新派生）；
    // 同时清理该女仆的采样/通知/失联计数
    private MineTask releaseBoundTask(UUID uuid)
    {
        MineTask task = layerSubtasks.remove(uuid);
        if (task != null && task.type() != MineTask.Type.FETCH_LIGHT)
        {
            addHardTask(task);
        }
        clearStuckSample(uuid);
        stuckRecovery.remove(uuid);
        absentSamples.remove(uuid);
        return task;
    }

    // 行为侧消费卡死通知（一次性）
    public boolean consumeStuckRecovery(UUID uuid)
    {
        return stuckRecovery.remove(uuid);
    }

    // ===================== 被埋救援（2026-09-04 拍板） =====================

    // 女仆被方块埋住 → 原版每 tick 扣窒息伤害，几秒即死；矿井侧每 tick 检测（状态绝对稳定），
    // 发现即就近传送到安全落点（找不到退矿井方块旁安全点）
    private void rescueBuriedMaids(ServerLevel level)
    {
        if (getMemberIds().isEmpty()) return;
        for (UUID uuid : getMemberIds())
        {
            if (!(level.getEntity(uuid) instanceof EntityMaid maid) || maid.isRemoved() || !maid.isAlive())
            {
                continue;
            }
            if (!maid.isInWall()) continue;
            BlockPos safe = findSafeSpot(level, maid.blockPosition());
            if (safe == null) safe = mineSafeSpot(level);
            if (safe == null) continue;
            maid.getNavigation().stop();
            maid.teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
            LOGGER.info("[MineDebug] 被埋救援：女仆={} → {}", shortId(uuid), safe.toShortString());
        }
    }

    // 就近安全落点：半径 3 内找"可站立"（空气 + 下方实体）格；找不到返回 null
    private static BlockPos findSafeSpot(ServerLevel level, BlockPos center)
    {
        for (int r = 1; r <= 3; r++)
        {
            for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -1, -r), center.offset(r, r + 1, r)))
            {
                if (!level.getBlockState(p).isAir()) continue;
                if (!level.getBlockState(p.below()).isSolid()) continue;
                return p.immutable();
            }
        }
        return null;
    }

    // 矿井方块旁安全落点（口径同行为侧 teleportNearMine）
    private BlockPos mineSafeSpot(ServerLevel level)
    {
        BlockPos base = getBlockPos();
        for (BlockPos cand : new BlockPos[]{
                base.above(), base.above(2),
                base.north(), base.south(), base.east(), base.west()})
        {
            if (level.getBlockState(cand).isAir() && level.getBlockState(cand.below()).isSolid())
            {
                return cand;
            }
        }
        return base.above();
    }

    // 暂停层 10s 重判（2026-09-04 三层化：逐个暂停层处理）：可处理比例回落到阈值内 → 自动恢复
    private void tryResumePausedLayers(ServerLevel level)
    {
        for (int y : new ArrayList<>(layerPaused))
        {
            rejudgeLayerHardList(level, y);
            int total = layerTotalCells();
            if (hardListOf(y).size() <= total * PAUSE_RATIO)
            {
                layerPaused.remove(y);
                LOGGER.info("[MineDebug] 层暂停解除 周期C{} Y={} 困难表残留{}",
                        cycle, y, hardListOf(y).size());
            }
        }
    }

    // 层困难表重判（可行域=仓库+在场女仆随身工具）：可处理的移出层困难表回池派发
    // 返回 true = 本轮有条目恢复可处理
    private boolean rejudgeLayerHardList(ServerLevel level, int y)
    {
        List<BlockPos> hard = hardListOf(y);
        if (hard.isEmpty()) return false;
        List<EntityMaid> maids = new ArrayList<>();
        for (UUID uuid : getMemberIds())
        {
            if (level.getEntity(uuid) instanceof EntityMaid m) maids.add(m);
        }
        List<BlockPos> recovered = new ArrayList<>();
        for (BlockPos pos : hard)
        {
            if (!level.isLoaded(pos)) continue;
            if (isProcessableByContext(level, pos, level.getBlockState(pos), maids))
            {
                recovered.add(pos);
            }
        }
        hard.removeAll(recovered);
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

    // 封存检查改版（2026-09-04 拍板）：每 tick 检查一个层，全矿一轮约 2~3 秒（原先 10s 只扫 256 格，
    // 八千多条要 5 分钟）；挖尽且矿道全完工后暂停（玩家手动扩半径 → 矿道重新激活 → 自动恢复）
    private void checkSealsOneLayer(ServerLevel level)
    {
        if (exhausted && !hasPendingTunnels()) return;
        ensureSealIndex();
        if (sealOrderDirty)
        {
            sealOrderDirty = false;
            sealLayerOrder = new ArrayList<>(sealsByLayer.keySet());
            sealLayerOrder.sort(Comparator.reverseOrder());
            if (sealLayerCursor >= sealLayerOrder.size()) sealLayerCursor = 0;
        }
        if (sealLayerOrder.isEmpty()) return;
        if (sealLayerCursor >= sealLayerOrder.size()) sealLayerCursor = 0;
        int y = sealLayerOrder.get(sealLayerCursor);
        sealLayerCursor++;
        LayerSeals ls = sealsByLayer.get(y);
        if (ls == null) return;
        checkLayerSeals(level, ls.air, MineTask.Type.DESTROY, false);
        checkLayerSeals(level, ls.solid, MineTask.Type.FILL, true);
        checkLayerSeals(level, ls.lights, MineTask.Type.SETLIGHT, true);
        checkLayerSeals(level, ls.floorLights, MineTask.Type.SETLIGHT_FLOOR, true);
        checkControlSealsOfLayer(level, y);
    }

    // 读档后按平铺列表重建层索引（幂等，仅一次）
    private void ensureSealIndex()
    {
        if (sealIndexBuilt) return;
        sealIndexBuilt = true;
        for (BlockPos p : sealedAir) indexAdd(p, sealedAir);
        for (BlockPos p : sealedSolid) indexAdd(p, sealedSolid);
        for (BlockPos p : sealedLights) indexAdd(p, sealedLights);
        for (BlockPos p : sealedFloorLights) indexAdd(p, sealedFloorLights);
        sealOrderDirty = true;
    }

    private void indexAdd(BlockPos pos, List<BlockPos> target)
    {
        LayerSeals ls = sealsByLayer.computeIfAbsent(pos.getY(), k -> new LayerSeals());
        if (target == sealedSolid) ls.solid.add(pos);
        else if (target == sealedLights) ls.lights.add(pos);
        else if (target == sealedFloorLights) ls.floorLights.add(pos);
        else ls.air.add(pos);
    }

    // 隧道周期维护（每 10 秒一条矿道）：露天列清理；
    // 挖尽且矿道全完工后暂停，玩家手动扩半径（清 tunnelDone）后自动恢复
    private void refreshTunnelUpkeep(ServerLevel level)
    {
        if (sealedControls.isEmpty()) return;
        if (exhausted && !hasPendingTunnels()) return;
        if (openAirCursor >= sealedControls.size()) openAirCursor = 0;
        cleanupOpenAirTunnel(level, sealedControls.get(openAirCursor));
        openAirCursor++;
    }

    // 露天列清理（2026-09-04 修正）：矿道露天部分此前被封"实体地板"并被女仆垫了垫脚石，
    // 这里解除封存并把露天列地板层上多出来的方块（只可能是垫脚石）派 DESTROY 挖掉
    private void cleanupOpenAirTunnel(ServerLevel level, ControlEntry ref)
    {
        TunnelPlan full = buildTunnelPlan(level, ref.cycle(), ref.layerY(), ref.edge(), false);
        if (full == null) return;
        int h = ref.layerY();
        for (BlockPos floorPos : full.floor())
        {
            if (!isOpenColumn(level, floorPos.getX(), floorPos.getZ(), h)) continue;
            unseal(floorPos);
            unseal(floorPos.above());
            unseal(floorPos.above(2));
            if (!level.isLoaded(floorPos)) continue;
            BlockState floorState = level.getBlockState(floorPos);
            if (!floorState.isAir() && floorState.getFluidState().isEmpty())
            {
                addHardTask(new MineTask(floorPos.immutable(), MineTask.Type.DESTROY));
            }
        }
    }

    // 解除单格封存（不动控制方块记录；同步层索引）
    private void unseal(BlockPos pos)
    {
        BlockPos immutable = pos.immutable();
        sealedAir.remove(immutable);
        sealedSolid.remove(immutable);
        sealedLights.remove(immutable);
        sealedFloorLights.remove(immutable);
        LayerSeals ls = sealsByLayer.get(immutable.getY());
        if (ls != null)
        {
            ls.air.remove(immutable);
            ls.solid.remove(immutable);
            ls.lights.remove(immutable);
            ls.floorLights.remove(immutable);
        }
    }

    // 控制位验证（随层检查）：该层格上不是矿道层控制方块 → 重派 PLACE_CONTROL
    // （常规状态不存在被挖；覆盖创造模式挖除与意外破坏）
    private void checkControlSealsOfLayer(ServerLevel level, int y)
    {
        for (ControlEntry entry : sealedControls)
        {
            BlockPos pos = entry.pos();
            if (pos.getY() != y) continue;
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() == MineCenterRegistration.MINE_LAYER_CONTROL_BLOCK.get()) continue;
            LOGGER.info("[MineDebug] 封存检查违规 PLACE_CONTROL @ {} 实际={}", pos.toShortString(),
                    state.isAir() ? "空气" : state.getBlock().getName().getString());
            addHardTask(new MineTask(pos.immutable(), MineTask.Type.PLACE_CONTROL));
        }
    }

    // 矿井中心移除时销毁所有绑定的矿道层控制方块（2026-09-04 拍板；在实例注销前由 deleteById 调用）
    public void destroyControlBlocks(ServerLevel level)
    {
        int removed = 0;
        for (ControlEntry entry : sealedControls)
        {
            BlockPos pos = entry.pos();
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockState(pos).getBlock() != MineCenterRegistration.MINE_LAYER_CONTROL_BLOCK.get()) continue;
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            removed++;
        }
        LOGGER.info("[MineDebug] 矿井中心移除：销毁 {} 个矿道层控制方块（记录 {} 条）",
                removed, sealedControls.size());
    }

    // 检查一层的封存集合；satisfiedWhenEmpty=true → "为空"算违规（实体/灯位）；false → "非空"算违规（空置）
    private void checkLayerSeals(ServerLevel level, Set<BlockPos> set,
                                 MineTask.Type violationType, boolean satisfiedWhenEmpty)
    {
        for (BlockPos pos : set)
        {
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            MineTask.Type vType = violationType;
            boolean violated;
            if (satisfiedWhenEmpty)
            {
                violated = state.isAir();
            }
            else if (state.isAir())
            {
                // 空置位空气 = 正常（2026-09-04 修正：此前漏判——空气格被当违规反复派发 DESTROY，
                // 刷屏灌困难表；塞子/被埋方块等"非空气"仍照常违规派发）
                continue;
            }
            else if (!state.getFluidState().isEmpty())
            {
                // 空置位流体特判（2026-09-04 修正）：流动流体等源清完后自然干涸，不算违规；
                // 源流体按 REPLACE（清掉收瓶）派发——绝不能派 DESTROY（水慢挖+回流死循环）
                if (!state.getFluidState().isSource())
                {
                    continue;
                }
                violated = true;
                vType = MineTask.Type.REPLACE;
            }
            else
            {
                violated = true;
            }
            if (violated)
            {
                MineTask task = new MineTask(pos.immutable(), vType);
                LOGGER.info("[MineDebug] 封存检查违规 {} @ {} 实际={}", vType,
                        pos.toShortString(),
                        state.isAir() ? "空气" : state.getBlock().getName().getString());
                addHardTask(task);
            }
        }
    }

    // ===================== 挖尽状态（D2） =====================

    public boolean isExhausted()
    {
        return exhausted;
    }

    public boolean isLayerPaused()
    {
        return layerIndex < cycleLayerYs.size() && layerPaused.contains(cycleLayerYs.get(layerIndex));
    }

    // 挖尽后释放所有在线成员，但不删除矿井实例/方块（D2：实例与仓库保留）
    public void releaseAllMembers(ServerLevel level)
    {
        for (UUID uuid : new ArrayList<>(getMemberIds()))
        {
            if (level.getEntity(uuid) instanceof EntityMaid maid)
            {
                releaseWork(maid);
                releaseAssignment(maid);
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

    // 面板"已挖尽"状态（2026-09-04 拍板）
    @Override
    protected boolean infoExhausted()
    {
        return exhausted;
    }

    // 该坐标是否保留位（当前周期缓存命中，或位于围墙环上）——流体处理（是否补垫脚）用
    public boolean isKeepPosition(BlockPos pos)
    {
        Set<BlockPos> keeps = cycleKeeps.get(pos.getY());
        if (keeps != null && keeps.contains(pos)) return true;
        SpiralMinePlanner p = planner();
        if (pos.getY() < getBlockPos().getY())
        {
            return pos.getX() == p.getMinX() - 1 || pos.getX() == p.getMaxX() + 1
                    || pos.getZ() == p.getMinZ() - 1 || pos.getZ() == p.getMaxZ() + 1;
        }
        return false;
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

    public void markExhausted(ServerLevel level)
    {
        if (exhausted) return;
        this.exhausted = true;
        layerSubtasks.clear();
        LOGGER.info("[MineDebug] 矿井已挖尽 周期C{} 层#{} 困难表{} 空置封存{} 实体封存{}",
                cycle, layerIndex, mineHardTasks.size(), sealedAir.size(), sealedSolid.size());
        // 挖尽提示（2026-09-04 拍板）：给所有者 + 附近玩家发一条聊天消息（只发一次）
        Component msg = Component.literal("§e[矿井] §f矿井已挖尽，女仆已完成全部竖井挖掘。");
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(getOwner());
        if (owner != null) owner.sendSystemMessage(msg);
        for (ServerPlayer player : level.players())
        {
            if (player != owner && player.blockPosition().distSqr(getBlockPos()) <= 64 * 64)
            {
                player.sendSystemMessage(msg);
            }
        }
    }

    // 缺工具摘要（气泡用，2026-09-04）：把"镐 示例: xxx"这类备注压成简短清单
    public String missingToolsSummary()
    {
        java.util.LinkedHashSet<String> kinds = new java.util.LinkedHashSet<>();
        for (String note : missingToolNotes)
        {
            int sp = note.indexOf(' ');
            kinds.add(sp > 0 ? note.substring(0, sp) : note);
        }
        return kinds.isEmpty() ? "工具" : String.join("、", kinds);
    }

    // ===================== 女仆自行退出矿井（API，2026-09-04 预留） =====================
    // 供后续女仆 AI 逻辑调用：行为侧消费后先做最终存货，再退出矿井；
    // 玩家更改任务/收起/死亡等路径不走这里（stop 会丢弃未消费的请求）

    private final Set<UUID> leaveRequests = new HashSet<>();

    public void requestLeave(EntityMaid maid)
    {
        leaveRequests.add(maid.getUUID());
    }

    public boolean consumeLeaveRequest(UUID maidUuid)
    {
        return leaveRequests.remove(maidUuid);
    }

    // ===================== 调试 =====================

    // 当前周期/层状态快照（调试命令用，触发周期数据懒计算）
    public String debugLayerInfo(ServerLevel level)
    {
        ensureLayersComputed(level);
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
                keeps.size(), lights.size(), layerSubtasks.size(), hardListOf(y).size(),
                isLayerPaused() ? "[层暂停] " : "",
                mineHardTasks.size(), sealedAir.size(), sealedSolid.size());
    }
}
