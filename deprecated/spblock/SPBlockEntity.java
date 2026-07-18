package com.fennecmomo.maidmorework.spblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.model.data.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

// SPBlock 的数据容器（每个 SPBlock 对应一个 SPBlockEntity）
//
// 存储三样东西：
// 1. 原始方块的 BlockState（还原时用，由 SPBlockManager.replaceBlocks 设置）
// 2. 绑定实体的 UUID（女仆 UUID，实体消失时 onLoad 自动还原）
// 3. 方块状态：SOLID = 实体外观（渲染成原始方块），BLUEPRINT = 蓝图虚影
//
// 客户端同步：
//   setBlockState2() 修改状态时手动发包给跟踪此区块的玩家
//   onDataPacket/handleUpdateTag 接收后触发 requestModelDataUpdate 刷新渲染
//
// 生命周期：
//   onLoad 时检查 game time < 100（世界刚启动），若是则自动还原为原始方块
//   这用于清理服务器崩溃后残留的 SPBlock
public class SPBlockEntity extends BlockEntity
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 方块状态枚举（通用，不绑定具体业务）
    // SOLID: 渲染成原始方块样子（委托 SPDynamicModel.collectParts）
    // BLUEPRINT: 渲染为天蓝色半透明（SPDynamicModel 返回 glassModelPart）
    public enum State
    {
        SOLID,      // 实体外观，渲染成原始方块样子，有碰撞
        BLUEPRINT   // 蓝图虚影，半透明蓝色，无碰撞
    }

    // 原始方块状态，默认空气
    private BlockState originalState = Blocks.AIR.defaultBlockState();
    // 绑定实体的UUID
    private UUID ownerUuid = null;
    // 当前状态
    private State state = State.SOLID;

    public SPBlockEntity(BlockPos pos, BlockState blockState)
    {
        super(SPRegistration.SP_BLOCK_ENTITY.get(), pos, blockState);
    }

    public BlockState getOriginalState()
    {
        return originalState;
    }

    public void setOriginalState(BlockState original)
    {
        this.originalState = original;
        setChanged();
    }

    public UUID getOwnerUuid()
    {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID uuid)
    {
        this.ownerUuid = uuid;
        setChanged();
    }

    public State getBlockState2()
    {
        return state;
    }

    // 设置方块状态（SOLID/BLUEPRINT）
    // 修改后手动发包给客户端，因为 sendBlockUpdated 只发 BlockState 更新
    // BlockState 没变时不会触发 BlockEntity 同步，需要手动发 getUpdatePacket
    public void setBlockState2(State newState)
    {
        this.state = newState;
        setChanged();
        // 通知客户端BlockEntity数据变化
        // sendBlockUpdated只发BlockState更新，BlockState没变不会触发BlockEntity同步
        // 需要手动发getUpdatePacket给跟踪这个区块的玩家
        if (level != null && !level.isClientSide() && level instanceof net.minecraft.server.level.ServerLevel serverLevel)
        {
            var packet = getUpdatePacket();
            if (packet != null)
            {
                serverLevel.getChunkSource().chunkMap
                        .getPlayers(new net.minecraft.world.level.ChunkPos(getBlockPos().getX() >> 4, getBlockPos().getZ() >> 4), false)
                        .forEach(player -> player.connection.send(packet));
            }
        }
    }

    // 覆写 onDataPacket：客户端收到 BlockEntity 数据包后，
    // load 完数据要手动触发渲染刷新，否则 SPDynamicModel 不会重新 collectParts
    @Override
    public void onDataPacket(net.minecraft.network.Connection net,
                             net.minecraft.world.level.storage.ValueInput input)
    {
        super.onDataPacket(net, input);
        requestModelDataUpdate();
        triggerRenderUpdate();
    }

    // 覆写 handleUpdateTag：chunk 加载时首次收到 BlockEntity 数据，
    // 也需要触发渲染刷新，否则方块显示为空白
    @Override
    public void handleUpdateTag(net.minecraft.world.level.storage.ValueInput input)
    {
        super.handleUpdateTag(input);
        requestModelDataUpdate();
        triggerRenderUpdate();
    }

    // 触发客户端区块重新渲染
    // markAndNotifyBlock 强制让客户端重新请求模型数据并刷新渲染
    private void triggerRenderUpdate()
    {
        if (level != null && level.isClientSide())
        {
            level.markAndNotifyBlock(getBlockPos(), level.getChunkAt(getBlockPos()),
                    getBlockState(), getBlockState(), 3, 0);
        }
    }

    // 提供 ModelData 给客户端渲染用
    // 包含原始方块的 BlockState 和当前状态 (SOLID/BLUEPRINT)
    // SPDynamicModel.collectParts 读取这些数据决定渲染什么外观
    @Override
    public ModelData getModelData()
    {
        return ModelData.builder()
                .with(SPDynamicModel.ORIGINAL_STATE, originalState)
                .with(SPDynamicModel.BLOCK_STATE, state)
                .build();
    }

    // 方块实体加载时检查：
    // - 服务端：game time < 100 表示世界刚启动，自动还原残留的 SPBlock
    //   （运行时创建的 SPBlock 时 game time 肯定大于 100，不会被误还原）
    // - 客户端：触发渲染刷新
    @Override
    public void onLoad()
    {
        super.onLoad();
        // 世界刚启动时清理残留SPBlock
        // 运行时创建SPBlock时gameTime肯定大于100，不会被误还原
        if (level != null && !level.isClientSide() && level.getGameTime() < 100)
        {
            level.setBlockAndUpdate(getBlockPos(), originalState);
        }
        // 客户端加载后刷新渲染
        if (level != null && level.isClientSide())
        {
            requestModelDataUpdate();
        }
    }

    // 检查绑定的实体是否还存在
    // 服务端通过 ServerLevel.getEntity 查找，找不到返回 false
    // ChopBehavior.stop() 中如果女仆消失，SPBlock 可通过此方法检测并还原
    public boolean isOwnerAlive()
    {
        if (ownerUuid == null)
        {
            return false;
        }
        if (level == null || level.isClientSide())
        {
            return true;
        }
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel)
        {
            return serverLevel.getEntity(ownerUuid) != null;
        }
        return true;
    }

    // 获取更新包（BlockEntity 数据同步给客户端）
    // MC 用这个包同步服务端数据到客户端
    @Override
    public Packet getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // 获取更新 Tag（chunk 加载时的初始数据同步）
    // 用 TagValueOutput 保存数据，然后构建 CompoundTag 发给客户端
    @Override
    public CompoundTag getUpdateTag(
            HolderLookup.Provider registries)
    {
        // 用TagValueOutput保存数据，然后构建CompoundTag发给客户端
        TagValueOutput output =
                TagValueOutput.createWithContext(
                        new ProblemReporter.Collector(), registries);
        saveAdditional(output);
        return output.buildResult();
    }

    // 保存到磁盘：原始方块状态 + 方块状态枚举 + 绑定实体 UUID
    // UUID 拆分为 Most/Least 两个 long 存储
    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        output.store("original_state", BlockState.CODEC, originalState);
        output.putString("block_state", state.name());
        if (ownerUuid != null)
        {
            output.putLong("owner_uuid_most", ownerUuid.getMostSignificantBits());
            output.putLong("owner_uuid_least", ownerUuid.getLeastSignificantBits());
        }
    }

    // 从磁盘加载：还原原始方块状态、方块状态枚举和绑定实体 UUID
    // 缺少字段时用默认值（空气/SOLID/null）
    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        originalState = input.read("original_state", BlockState.CODEC)
                .orElse(Blocks.AIR.defaultBlockState());
        state = State.valueOf(input.getStringOr("block_state", State.SOLID.name()));
        long most = input.getLongOr("owner_uuid_most", 0L);
        long least = input.getLongOr("owner_uuid_least", 0L);
        if (most != 0L || least != 0L)
        {
            ownerUuid = new UUID(most, least);
        }
    }
}
