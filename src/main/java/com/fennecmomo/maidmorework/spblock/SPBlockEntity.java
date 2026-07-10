package com.fennecmomo.maidmorework.spblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

// SPBlock的数据容器
// 回退到稳定版本，防止无限递归崩溃
public class SPBlockEntity extends BlockEntity
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 原始方块状态，默认空气
    private BlockState originalState = Blocks.AIR.defaultBlockState();
    // 绑定实体的UUID
    private UUID ownerUuid = null;

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

    @Override
    public void onLoad()
    {
        super.onLoad();
        // 世界刚启动时清理残留SPBlock
        if (level != null && !level.isClientSide() && level.getGameTime() < 100)
        {
            level.setBlockAndUpdate(getBlockPos(), originalState);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        output.store("original_state", BlockState.CODEC, originalState);
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
        long most = input.getLongOr("owner_uuid_most", 0L);
        long least = input.getLongOr("owner_uuid_least", 0L);
        if (most != 0L || least != 0L)
        {
            ownerUuid = new UUID(most, least);
        }
    }
}