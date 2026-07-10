package com.fennecmomo.maidmorework.spblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.model.data.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

// SPBlock的数据容器
// 存三样东西：
// 1. 原始方块的BlockState（还原时用）
// 2. 绑定实体的UUID（实体消失时自动还原）
// 3. 方块状态：SOLID=实体外观，BLUEPRINT=蓝图虚影
public class SPBlockEntity extends BlockEntity
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 方块状态枚举（通用，不绑定具体业务）
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

    // 覆写onDataPacket：客户端收到BlockEntity数据包后，
    // load完数据要手动触发渲染刷新，否则DynamicBlockStateModel不会重新collectParts
    @Override
    public void onDataPacket(net.minecraft.network.Connection net,
                             net.minecraft.world.level.storage.ValueInput input)
    {
        super.onDataPacket(net, input);
        requestModelDataUpdate();
        triggerRenderUpdate();
    }

    // 覆写handleUpdateTag：chunk加载时首次收到BlockEntity数据，
    // 也需要触发渲染刷新
    @Override
    public void handleUpdateTag(net.minecraft.world.level.storage.ValueInput input)
    {
        super.handleUpdateTag(input);
        requestModelDataUpdate();
        triggerRenderUpdate();
    }

    // 触发客户端区块重新渲染
    private void triggerRenderUpdate()
    {
        if (level != null && level.isClientSide())
        {
            level.markAndNotifyBlock(getBlockPos(), level.getChunkAt(getBlockPos()),
                    getBlockState(), getBlockState(), 3, 0);
        }
    }

    // 提供ModelData给客户端渲染用
    // 包含原始方块的BlockState和当前状态(SOLID/BLUEPRINT)
    @Override
    public ModelData getModelData()
    {
        return ModelData.builder()
                .with(SPDynamicModel.ORIGINAL_STATE, originalState)
                .with(SPDynamicModel.BLOCK_STATE, state)
                .build();
    }

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

    @Override
    public net.minecraft.network.protocol.Packet getUpdatePacket()
    {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(
            net.minecraft.core.HolderLookup.Provider registries)
    {
        // 用TagValueOutput保存数据，然后构建CompoundTag发给客户端
        net.minecraft.world.level.storage.TagValueOutput output =
                net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                        new net.minecraft.util.ProblemReporter.Collector(), registries);
        saveAdditional(output);
        return output.buildResult();
    }

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
