package com.fennecmomo.maidmorework.mining;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 矿井实体方块的BlockEntity
// 存储矿井完整数据（ID+主人+矿区尺寸+女仆列表），世界重进后自动恢复实例到内存
// 同时实现 Container 提供 27 格储物空间（每格最大 640 堆叠）
public class MineBlockEntity extends BlockEntity implements Container
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");
    private long idMost = 0L;
    private long idLeast = 0L;
    private long ownerMost = 0L;
    private long ownerLeast = 0L;
    // 矿区尺寸
    private int mineL = 0;
    private int mineW = 0;
    // 在此矿井工作的女仆UUID列表
    private final List<Long> maidIdMosts = new ArrayList<>();
    private final List<Long> maidIdLeasts = new ArrayList<>();

    // 螺旋规划器
    private SpiralMinePlanner planner = null;

    // 当前周期进度（用于女仆挖矿任务流）
    private int currentCycle = 0;
    // 当前正在处理的 Y 层索引（在 sortedY 里，0=最高层）
    private int currentLayerIndex = 0;
    // 当前周期内所有 Y 层，从高到低排序
    private final List<Integer> sortedY = new ArrayList<>();
    // 当前周期内按 Y 层组织的保留区
    private final Map<Integer, Set<BlockPos>> currentCycleKeeps = new HashMap<>();
    // 当前周期内按 Y 层组织的光源位置
    private final Map<Integer, Set<BlockPos>> currentCycleLights = new HashMap<>();
    // 当前周期内按 Y 层组织的替换位置
    private final Map<Integer, Set<BlockPos>> currentCycleReplaces = new HashMap<>();
    // 已分配给女仆但尚未完成的目标，防止多女仆抢同一方块
    private final Set<BlockPos> assignedTargets = new HashSet<>();
    // 女仆加入矿井前的原始 Home 位置，离开时恢复
    private final Map<UUID, BlockPos> maidHomes = new HashMap<>();

    // 矿井是否已停机（遇到基岩等不可破坏方块）
    private boolean shutdown = false;

    // === 储物空间 ===
    public static final int CONTAINER_SIZE = 27;
    public static final int MAX_STACK = 640;
    private final NonNullList<ItemStack> items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);

    public MineBlockEntity(BlockPos pos, BlockState state)
    {
        super(MineRegistration.MINE_BLOCK_ENTITY.get(), pos, state);
    }

    public void setInstanceData(UUID id, UUID owner, BlockPos c1, BlockPos c2)
    {
        this.idMost = id.getMostSignificantBits();
        this.idLeast = id.getLeastSignificantBits();
        this.ownerMost = owner.getMostSignificantBits();
        this.ownerLeast = owner.getLeastSignificantBits();
        if (c1 != null && c2 != null)
        {
            int minX = Math.min(c1.getX(), c2.getX());
            int maxX = Math.max(c1.getX(), c2.getX());
            int minZ = Math.min(c1.getZ(), c2.getZ());
            int maxZ = Math.max(c1.getZ(), c2.getZ());
            this.mineL = maxX - minX + 1;
            this.mineW = maxZ - minZ + 1;
        }
        initPlanner();
        setChanged();
        LOGGER.info("MineBlockEntity created at {}, L={} W={} plannerCenterY={}",
                getBlockPos().toShortString(), mineL, mineW, getBlockPos().getY() - 1);
    }

    public UUID getInstanceId()
    {
        return new UUID(idMost, idLeast);
    }

    public UUID getOwner()
    {
        return new UUID(ownerMost, ownerLeast);
    }

    public boolean hasInstance()
    {
        return idMost != 0L || idLeast != 0L;
    }

    // 女仆加入矿井
    public void joinMine(EntityMaid maid)
    {
        UUID uuid = maid.getUUID();
        for (int i = 0; i < maidIdMosts.size(); i++)
        {
            if (maidIdMosts.get(i) == uuid.getMostSignificantBits()
                    && maidIdLeasts.get(i) == uuid.getLeastSignificantBits())
            {
                return;
            }
        }
        maidIdMosts.add(uuid.getMostSignificantBits());
        maidIdLeasts.add(uuid.getLeastSignificantBits());

        // 保存女仆原 Home，然后把 Home 设到矿井方块
        if (maid.hasHome())
        {
            maidHomes.put(uuid, maid.getHomePosition());
        }
        maid.setHomeTo(getBlockPos(), 1024);
        setChanged();
    }

    // 女仆离开矿井
    public void leaveMine(EntityMaid maid)
    {
        UUID uuid = maid.getUUID();
        for (int i = 0; i < maidIdMosts.size(); i++)
        {
            if (maidIdMosts.get(i) == uuid.getMostSignificantBits()
                    && maidIdLeasts.get(i) == uuid.getLeastSignificantBits())
            {
                maidIdMosts.remove(i);
                maidIdLeasts.remove(i);

                // 恢复女仆原 Home
                BlockPos originalHome = maidHomes.remove(uuid);
                if (originalHome != null)
                {
                    maid.setHomeTo(originalHome, 1024);
                }
                setChanged();
                return;
            }
        }
    }

    public boolean isMaidInMine(EntityMaid maid)
    {
        UUID uuid = maid.getUUID();
        for (int i = 0; i < maidIdMosts.size(); i++)
        {
            if (maidIdMosts.get(i) == uuid.getMostSignificantBits()
                    && maidIdLeasts.get(i) == uuid.getLeastSignificantBits())
            {
                return true;
            }
        }
        return false;
    }

    public int getPlannerL()
    {
        return mineL;
    }

    public int getPlannerW()
    {
        return mineW;
    }

    // 初始化螺旋规划器
    private void initPlanner()
    {
        if (mineL == 0 || mineW == 0) return;
        int centerX = getBlockPos().getX();
        int centerZ = getBlockPos().getZ();
        int centerY = getBlockPos().getY() - 1;
        planner = new SpiralMinePlanner(centerX, centerY, centerZ, mineL, mineW);
    }

    // 给定(C,n,idx)，返回该步下一个待挖方块（调试/外部调用用）
    public BlockPos tryGetNextBlock(int C, int n, int idx)
    {
        if (planner == null || level == null) return null;
        List<BlockPos> keep = planner.getKeepBlocks(C, n, idx);
        if (keep.isEmpty()) return null;
        int y = keep.get(0).getY();
        Set<BlockPos> keepSet = new HashSet<>(keep);
        for (int x = planner.getMinX(); x <= planner.getMaxX(); x++)
        {
            for (int z = planner.getMinZ(); z <= planner.getMaxZ(); z++)
            {
                BlockPos pos = new BlockPos(x, y, z);
                if (!keepSet.contains(pos) && !level.getBlockState(pos).isAir())
                {
                    return pos;
                }
            }
        }
        return null;
    }

    // 女仆请求下一个任务：返回挖/补/空，空表示当前矿井已无活可干
    public DigTask requestNextTask(EntityMaid maid)
    {
        return requestNextTask(maid.blockPosition());
    }

    // 女仆请求下一个任务：同层内就近分配，一层完成才推进下一层
    public DigTask requestNextTask(BlockPos maidPos)
    {
        // 已停机的矿井不再分配任务
        if (shutdown) return null;
        if (planner == null || level == null) return null;

        ensureCycleLoaded();

        // 在当前 Y 层内就近查找未处理方块
        DigTask task = findNearestTaskInCurrentLayer(maidPos);
        if (task != null)
        {
            LOGGER.info("MineBlockEntity: assigned task {} at {} (maidY={}, layer={}/{})",
                    task.type(), task.pos().toShortString(), maidPos.getY(),
                    currentLayerIndex, sortedY.size());
            return task;
        }

        // 当前层没有可分配的任务
        // 清理过期的已分配目标（方块状态已变化说明已完成或被其他女仆处理了）
        if (!assignedTargets.isEmpty())
        {
            if (currentLayerIndex < sortedY.size())
            {
                int y = sortedY.get(currentLayerIndex);
                Set<BlockPos> keeps = currentCycleKeeps.get(y);
                java.util.Iterator<BlockPos> it = assignedTargets.iterator();
                while (it.hasNext())
                {
                    BlockPos assigned = it.next();
                    if (assigned.getY() != y) { it.remove(); continue; }
                    BlockState st = level.getBlockState(assigned);
                    boolean air = st.isAir();
                    boolean isKeep = keeps != null && keeps.contains(assigned);
                    // DIG 目标变成空气 = 已挖完; FILL 目标变成固体 = 已填充
                    if ((isKeep && !air && st.isSolid()) || (!isKeep && air))
                    {
                        LOGGER.info("MineBlockEntity: stale target removed {} (keep={}, air={})",
                                assigned.toShortString(), isKeep, air);
                        it.remove();
                    }
                }
            }
            else
            {
                assignedTargets.clear();
            }
        }
        // 如果还有女仆正在工作，等待她们完成
        if (!assignedTargets.isEmpty())
        {
            LOGGER.info("MineBlockEntity: layer {} no new tasks, waiting for {} assigned",
                    currentLayerIndex, assignedTargets.size());
            return null;
        }

        // 当前层真正完成，推进到下一层
        currentLayerIndex++;
        assignedTargets.clear();

        if (currentLayerIndex < sortedY.size())
        {
            LOGGER.info("MineBlockEntity: layer done, advancing to layer {}/{} (Y={})",
                    currentLayerIndex, sortedY.size(), sortedY.get(currentLayerIndex));
            setChanged();
            return null;
        }

        // 所有层完成，推进周期
        LOGGER.info("MineBlockEntity: all layers done, cycle {} complete", currentCycle);
        advanceCycle();
        return null;
    }

    private void ensureCycleLoaded()
    {
        if (sortedY.isEmpty())
        {
            computeCycle(currentCycle);
        }
    }

    private void advanceCycle()
    {
        currentCycle++;
        currentLayerIndex = 0;
        sortedY.clear();
        currentCycleKeeps.clear();
        currentCycleLights.clear();
        currentCycleReplaces.clear();
        assignedTargets.clear();
        setChanged();
    }

    // 在当前 Y 层内找离女仆最近的未处理方块
    private DigTask findNearestTaskInCurrentLayer(BlockPos maidPos)
    {
        if (currentLayerIndex >= sortedY.size()) return null;

        int y = sortedY.get(currentLayerIndex);
        Set<BlockPos> keeps = currentCycleKeeps.get(y);
        Set<BlockPos> lights = currentCycleLights.getOrDefault(y, java.util.Collections.emptySet());
        Set<BlockPos> replaces = currentCycleReplaces.getOrDefault(y, java.util.Collections.emptySet());

        DigTask bestTask = null;
        double bestDist = Double.MAX_VALUE;

        // 扫描范围向外扩展一圈，覆盖边界墙壁
        for (int x = planner.getMinX() - 1; x <= planner.getMaxX() + 1; x++)
        {
            for (int z = planner.getMinZ() - 1; z <= planner.getMaxZ() + 1; z++)
            {
                BlockPos pos = new BlockPos(x, y, z);
                // 跳过已分配给其他女仆的目标
                if (assignedTargets.contains(pos)) continue;

                boolean isKeep = keeps != null && keeps.contains(pos);
                boolean isLight = lights.contains(pos);
                boolean isReplace = replaces.contains(pos);
                BlockState state = level.getBlockState(pos);
                boolean isAir = state.isAir();
                DigTask candidate = null;

                // 优先级：光源 > 替换 > 保留 > 挖掘
                // [TEMP] LIGHT/REPLACE 逻辑暂时禁用
                if (isKeep && isAir)
                {
                    candidate = new DigTask(pos, DigTask.Type.FILL);
                }
                else if (isKeep && !isAir && !state.isSolid())
                {
                    candidate = new DigTask(pos, DigTask.Type.FILL);
                }
                else if (!isKeep && !isLight && !isReplace && !isAir)
                {
                    candidate = new DigTask(pos, DigTask.Type.DIG);
                }

                if (candidate != null)
                {
                    double dist = pos.distSqr(maidPos);
                    if (dist < bestDist)
                    {
                        bestDist = dist;
                        bestTask = candidate;
                    }
                }
            }
        }

        if (bestTask != null)
        {
            assignedTargets.add(bestTask.pos());
        }
        return bestTask;
    }

    // 女仆放弃目标（完成失败时调用），把目标还回池子
    public void releaseTarget(BlockPos pos)
    {
        assignedTargets.remove(pos);
    }

    // 女仆完成任务后调用，释放目标并通知周期完成检查
    public void completeTarget(BlockPos pos)
    {
        assignedTargets.remove(pos);
        setChanged();
    }

    private void computeCycle(int C)
    {
        sortedY.clear();
        currentCycleKeeps.clear();
        currentLayerIndex = 0;
        if (planner == null) return;

        // 收集该周期所有 (n, idx) 的保留区，按 Y 层合并
        Map<Integer, Set<BlockPos>> keepsByY = new LinkedHashMap<>();
        int n = 0;
        int idx = 0;
        int limitN = (n % 2 == 0)
                ? Math.max(0, planner.getL() - 3)
                : Math.max(0, planner.getW() - 3);

        while (true)
        {
            List<BlockPos> keep = planner.getKeepBlocks(C, n, idx);
            for (BlockPos k : keep)
            {
                keepsByY.computeIfAbsent(k.getY(), yk -> new HashSet<>()).add(k);
            }
            idx++;
            if (idx > limitN)
            {
                n++;
                if (n > 3) break;
                idx = 0;
                limitN = (n % 2 == 0)
                        ? Math.max(0, planner.getL() - 3)
                        : Math.max(0, planner.getW() - 3);
            }
        }

        // 矿井方块自身加入保留区，防止被挖
        BlockPos minePos = getBlockPos();
        int mineY = minePos.getY();
        keepsByY.computeIfAbsent(mineY, yk -> new HashSet<>()).add(minePos);

        // 入口清理 mineY+1 ~ mineY+2（保留区为空，全挖）
        for (int dy = 1; dy <= 2; dy++)
        {
            keepsByY.computeIfAbsent(mineY + dy, yk -> new HashSet<>());
        }

        currentCycleKeeps.putAll(keepsByY);
        sortedY.addAll(keepsByY.keySet());
        java.util.Collections.sort(sortedY, java.util.Collections.reverseOrder());

        // 矿井下方1层（spiral 第一层）加入支撑保留区
        // 只保留 Z 偏差≤1 的（X 方向不保留，避免挡住楼梯）
        int supportY = mineY - 1;
        Set<BlockPos> supportKeeps = currentCycleKeeps.computeIfAbsent(supportY, yk -> new HashSet<>());
        for (int x = planner.getMinX(); x <= planner.getMaxX(); x++)
        {
            for (int z = planner.getMinZ(); z <= planner.getMaxZ(); z++)
            {
                if (Math.abs(z - minePos.getZ()) <= 1)
                {
                    supportKeeps.add(new BlockPos(x, supportY, z));
                }
            }
        }

        // 边界扩展：只对 mineY 以下的 Y 层向外加一圈保留区，形成矿井围墙
        // mineY 及以上是入口区域，保持开放
        int mineY_boundary = getBlockPos().getY();
        for (int y : new ArrayList<>(currentCycleKeeps.keySet()))
        {
            if (y < mineY_boundary)
            {
                addBoundaryKeeps(currentCycleKeeps.get(y), y);
            }
        }

        // 光源位置：每个 Y 层内部，沿 X 方向每隔 6 格放置一个火把
        currentCycleLights.clear();
        for (int y : sortedY)
        {
            if (y >= mineY) continue;  // 入口层不放光源
            Set<BlockPos> lights = new HashSet<>();
            for (int x = planner.getMinX() + 3; x <= planner.getMaxX() - 3; x += 6)
            {
                lights.add(new BlockPos(x, y, planner.getMinZ() + 1));
                lights.add(new BlockPos(x, y, planner.getMaxZ() - 1));
            }
            currentCycleLights.put(y, lights);
        }

        // 替换位置：每个 Y 层内部边界墙内侧，非 keep 非 light 的位置
        currentCycleReplaces.clear();
        for (int y : sortedY)
        {
            if (y >= mineY) continue;
            Set<BlockPos> keeps = currentCycleKeeps.getOrDefault(y, java.util.Collections.emptySet());
            Set<BlockPos> lights = currentCycleLights.getOrDefault(y, java.util.Collections.emptySet());
            Set<BlockPos> replaces = new HashSet<>();
            for (int x = planner.getMinX(); x <= planner.getMaxX(); x++)
            {
                for (int z = planner.getMinZ(); z <= planner.getMaxZ(); z++)
                {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!keeps.contains(pos) && !lights.contains(pos))
                    {
                        // 只在边界墙内侧一格（女仆行走区域）
                        if (x == planner.getMinX() || x == planner.getMaxX()
                                || z == planner.getMinZ() || z == planner.getMaxZ())
                        {
                            replaces.add(pos);
                        }
                    }
                }
            }
            currentCycleReplaces.put(y, replaces);
        }

        // 调试日志：输出每层 keeps/lights/replaces 数量
        for (int y : sortedY)
        {
            Set<BlockPos> k = currentCycleKeeps.get(y);
            Set<BlockPos> l = currentCycleLights.get(y);
            Set<BlockPos> r = currentCycleReplaces.get(y);
            LOGGER.info("MineBlockEntity cycle layer Y={} keeps={} lights={} replaces={} mineY={}",
                    y, k == null ? 0 : k.size(), l == null ? 0 : l.size(), r == null ? 0 : r.size(), mineY);
        }

        LOGGER.info("MineBlockEntity computed cycle C={}: Y layers={}", C, sortedY);
    }

    // 在矿区边界向外加一圈保留方块，形成围墙
    private void addBoundaryKeeps(Set<BlockPos> keeps, int y)
    {
        int minX = planner.getMinX() - 1;
        int maxX = planner.getMaxX() + 1;
        int minZ = planner.getMinZ() - 1;
        int maxZ = planner.getMaxZ() + 1;
        // 南墙
        for (int x = minX; x <= maxX; x++) keeps.add(new BlockPos(x, y, minZ));
        // 北墙
        for (int x = minX; x <= maxX; x++) keeps.add(new BlockPos(x, y, maxZ));
        // 西墙
        for (int z = minZ; z <= maxZ; z++) keeps.add(new BlockPos(minX, y, z));
        // 东墙
        for (int z = minZ; z <= maxZ; z++) keeps.add(new BlockPos(maxX, y, z));
    }

    // 判断方块状态是否为垫脚方块类型（泥土/木板/圆石/石头）
    private boolean isScaffoldState(BlockState state)
    {
        net.minecraft.world.level.block.Block block = state.getBlock();
        if (block.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.DIRT)) return true;
        if (block.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.PLANKS)) return true;
        if (block.builtInRegistryHolder().is(net.neoforged.neoforge.common.Tags.Blocks.COBBLESTONES)) return true;
        if (block.builtInRegistryHolder().is(net.neoforged.neoforge.common.Tags.Blocks.STONES)) return true;
        return false;
    }

    // 停机：遇到不可破坏方块时调用，停止分配任务并召回所有女仆
    public void shutdownMine(ServerLevel serverLevel)
    {
        shutdown = true;
        assignedTargets.clear();
        LOGGER.info("MineBlockEntity: SHUTDOWN at {}", getBlockPos().toShortString());

        // 召回所有关联女仆
        for (int i = 0; i < maidIdMosts.size(); i++)
        {
            UUID uuid = new UUID(maidIdMosts.get(i), maidIdLeasts.get(i));
            var entity = serverLevel.getEntity(uuid);
            if (entity instanceof EntityMaid maid)
            {
                // 恢复 Home
                BlockPos home = maidHomes.remove(uuid);
                if (home != null)
                {
                    maid.setHomeTo(home, 1024);
                }
                // 清除女仆挖矿记忆，让 MiningBehavior 自然停止
                maid.getBrain().eraseMemory(com.fennecmomo.maidmorework.ModMemories.LOG_BLOCKS.get());
                maid.removeData(com.fennecmomo.maidmorework.ModAttachments.LOG_BLOCKS_SAVED);
            }
        }
        maidIdMosts.clear();
        maidIdLeasts.clear();
        maidHomes.clear();
        setChanged();
    }

    public boolean isShutdown() { return shutdown; }

    // 获取一个完整周期的所有待挖方块（C=0，n=0~3，idx递增）
    public List<BlockPos> getAllDigBlocksForCycle()
    {
        List<BlockPos> result = new ArrayList<>();
        if (planner == null) return result;

        Map<Integer, Set<BlockPos>> keepsByY = new LinkedHashMap<>();
        int n = 0;
        int idx = 0;
        int limitN = (n % 2 == 0) ? (planner.getL() - 3) : (planner.getW() - 3);

        while (true)
        {
            List<BlockPos> keep = planner.getKeepBlocks(0, n, idx);
            for (BlockPos k : keep)
            {
                keepsByY.computeIfAbsent(k.getY(), yk -> new HashSet<>()).add(k);
            }
            idx++;
            if (idx > limitN)
            {
                n++;
                if (n > 3) break;
                idx = 0;
                limitN = (n % 2 == 0) ? (planner.getL() - 3) : (planner.getW() - 3);
            }
        }
        for (Map.Entry<Integer, Set<BlockPos>> entry : keepsByY.entrySet())
        {
            int y = entry.getKey();
            Set<BlockPos> keeps = entry.getValue();

            for (int x = planner.getMinX(); x <= planner.getMaxX(); x++)
            {
                for (int z = planner.getMinZ(); z <= planner.getMaxZ(); z++)
                {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!keeps.contains(pos))
                    {
                        result.add(pos);
                    }
                }
            }
        }

        return result;
    }

    // 获取指定Y层的挖区方块（layerIndex: 0-based，周期内第几层Y）
    public List<BlockPos> getDigBlocksForLayer(int layerIndex)
    {
        List<BlockPos> result = new ArrayList<>();
        if (planner == null) return result;

        // 收集所有Y层保留区并集
        Map<Integer, Set<BlockPos>> keepsByY = new LinkedHashMap<>();
        int n = 0;
        int idx = 0;
        int limitN = (n % 2 == 0) ? (planner.getL() - 3) : (planner.getW() - 3);

        while (true)
        {
            List<BlockPos> keep = planner.getKeepBlocks(0, n, idx);
            for (BlockPos k : keep)
            {
                keepsByY.computeIfAbsent(k.getY(), yk -> new HashSet<>()).add(k);
            }
            idx++;
            if (idx > limitN)
            {
                n++;
                if (n > 3) break;
                idx = 0;
                limitN = (n % 2 == 0) ? (planner.getL() - 3) : (planner.getW() - 3);
            }
        }

        // 取第layerIndex个Y层
        List<Integer> sortedY = new ArrayList<>(keepsByY.keySet());
        java.util.Collections.sort(sortedY, java.util.Collections.reverseOrder());
        if (layerIndex < 0 || layerIndex >= sortedY.size()) return result;
        int y = sortedY.get(layerIndex);
        Set<BlockPos> keeps = keepsByY.get(y);
        LOGGER.info("getDigBlocksForLayer layer={} Y={} keeps={} sortedYLayers={}",
                layerIndex, y, keeps.size(), sortedY);

        for (int x = planner.getMinX(); x <= planner.getMaxX(); x++)
        {
            for (int z = planner.getMinZ(); z <= planner.getMaxZ(); z++)
            {
                BlockPos pos = new BlockPos(x, y, z);
                if (!keeps.contains(pos))
                {
                    result.add(pos);
                }
            }
        }
        LOGGER.info("getDigBlocksForLayer returning {} blocks, range X=[{},{}] Z=[{},{}]",
                result.size(), planner.getMinX(), planner.getMaxX(), planner.getMinZ(), planner.getMaxZ());
        return result;
    }

    @Override
    public void onLoad()
    {
        super.onLoad();
        if (level != null && !level.isClientSide() && idMost != 0L)
        {
            UUID id = getInstanceId();
            if (MineInstanceManager.get(level, id) == null)
            {
                BlockPos center = getBlockPos().below();
                int halfL = (mineL - 1) / 2;
                int halfW = (mineW - 1) / 2;
                BlockPos c1 = new BlockPos(center.getX() - halfL, center.getY(), center.getZ() - halfW);
                BlockPos c2 = new BlockPos(center.getX() + halfL, center.getY(), center.getZ() + halfW);
                MineInstance inst = new MineInstance(id, getOwner(), c1, c2);
                MineInstanceManager.put(level, inst);
            }
            initPlanner();
            // 世界重进后恢复当前周期缓存
            computeCycle(currentCycle);
        }
    }

    // === Container 接口实现 ===

    @Override
    public int getContainerSize() { return CONTAINER_SIZE; }

    @Override
    public boolean isEmpty()
    {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount)
    {
        return ContainerHelper.removeItem(items, slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot)
    {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack)
    {
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public int getMaxStackSize() { return MAX_STACK; }

    @Override
    public boolean stillValid(Player player)
    {
        if (level == null) return false;
        BlockPos pos = getBlockPos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent()
    {
        items.clear();
        setChanged();
    }

    // 自定义 addItem：支持 640 堆叠（绕过原版 64 限制）
    // 返回剩余未放入的物品
    public ItemStack addItem(ItemStack stack)
    {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        // 先尝试合并到已有同类堆
        for (int i = 0; i < CONTAINER_SIZE; i++)
        {
            ItemStack existing = items.get(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack))
            {
                int canAdd = MAX_STACK - existing.getCount();
                if (canAdd > 0)
                {
                    int toAdd = Math.min(canAdd, stack.getCount());
                    existing.grow(toAdd);
                    stack.shrink(toAdd);
                    if (stack.isEmpty()) { setChanged(); return ItemStack.EMPTY; }
                }
            }
        }
        // 再放到空格
        for (int i = 0; i < CONTAINER_SIZE; i++)
        {
            if (items.get(i).isEmpty())
            {
                int toAdd = Math.min(MAX_STACK, stack.getCount());
                items.set(i, stack.copyWithCount(toAdd));
                stack.shrink(toAdd);
                if (stack.isEmpty()) { setChanged(); return ItemStack.EMPTY; }
            }
        }
        setChanged();
        return stack;
    }

    @Override
    public void setChanged()
    {
        super.setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        output.putLong("idMost", idMost);
        output.putLong("idLeast", idLeast);
        output.putLong("ownerMost", ownerMost);
        output.putLong("ownerLeast", ownerLeast);
        output.putInt("mineL", mineL);
        output.putInt("mineW", mineW);
        output.putInt("currentCycle", currentCycle);
        output.putInt("currentLayerIndex", currentLayerIndex);
        output.putBoolean("shutdown", shutdown);
        output.putInt("maidCount", maidIdMosts.size());
        for (int i = 0; i < maidIdMosts.size(); i++)
        {
            output.putLong("maidMost" + i, maidIdMosts.get(i));
            output.putLong("maidLeast" + i, maidIdLeasts.get(i));
        }
        ContainerHelper.saveAllItems(output, items);
    }

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        idMost = input.getLongOr("idMost", 0L);
        idLeast = input.getLongOr("idLeast", 0L);
        ownerMost = input.getLongOr("ownerMost", 0L);
        ownerLeast = input.getLongOr("ownerLeast", 0L);
        mineL = input.getIntOr("mineL", 0);
        mineW = input.getIntOr("mineW", 0);
        shutdown = input.getBooleanOr("shutdown", false);
        currentCycle = input.getIntOr("currentCycle", 0);
        currentLayerIndex = input.getIntOr("currentLayerIndex", 0);
        sortedY.clear();
        currentCycleKeeps.clear();
        maidIdMosts.clear();
        maidIdLeasts.clear();
        int count = input.getIntOr("maidCount", 0);
        for (int i = 0; i < count; i++)
        {
            maidIdMosts.add(input.getLongOr("maidMost" + i, 0L));
            maidIdLeasts.add(input.getLongOr("maidLeast" + i, 0L));
        }
        ContainerHelper.loadAllItems(input, items);
    }
}
