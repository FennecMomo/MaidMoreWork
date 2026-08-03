package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.maidmorework.lib.region.IRegionalManager;
import com.fennecmomo.maidmorework.lib.region.RegionalManagerRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

// 工程中心方块实体（桥）
//
// 只负责：
//   - 身份数据 NBT 存储（id/owner/半径/基准点/类型/边界可见性）
//     客户端边界渲染读此 NBT，服务端 onLoad 时与 ProjectCenterManager 对接
//   - 生命周期事件转发：加载时确保实例存在；拆除时通知管理器删除中心
//
// 业务逻辑（目标/工程/分配/扫描/持久化）全部在 ProjectCenterInstance，
// 由 ProjectCenterManager 全局驱动，与方块实体生命周期解耦
public class ProjectCenterBlockEntity extends BlockEntity implements IRegionalManager
{
    private long idMost = 0L;
    private long idLeast = 0L;
    private long ownerMost = 0L;
    private long ownerLeast = 0L;

    private BlockPos cornerNW = BlockPos.ZERO;
    private BlockPos cornerSE = BlockPos.ZERO;
    private int radius = 5;
    private int anchor = 1;
    private boolean boundaryVisible = true;
    private String projectTypeId = "";

    public ProjectCenterBlockEntity(BlockPos pos, BlockState state)
    {
        super(ProjectCenterRegistration.PROJECT_CENTER_BLOCK_ENTITY.get(), pos, state);
    }

    // ===================== 身份数据 =====================

    public boolean hasInstance()
    {
        return idMost != 0L || idLeast != 0L;
    }

    @Override
    public UUID getId()
    {
        if (!hasInstance()) return null;
        return new UUID(idMost, idLeast);
    }

    public UUID getOwner()
    {
        return new UUID(ownerMost, ownerLeast);
    }

    public int getRadius() { return radius; }
    public int getAnchor() { return anchor; }
    public String getProjectTypeId() { return projectTypeId; }

    @Override
    public BlockPos getMinCorner() { return cornerNW; }

    @Override
    public BlockPos getMaxCorner() { return cornerSE; }

    @Override
    public void setBoundaryVisible(boolean visible)
    {
        this.boundaryVisible = visible;
        setChanged();
    }

    @Override
    public boolean isBoundaryVisible()
    {
        return boundaryVisible;
    }

    // 放置时写入身份（ProjectCenterBlock.setPlacedBy 调用）
    public void setInstanceData(UUID id, UUID owner, int radius, int anchor)
    {
        this.idMost = id.getMostSignificantBits();
        this.idLeast = id.getLeastSignificantBits();
        this.ownerMost = owner.getMostSignificantBits();
        this.ownerLeast = owner.getLeastSignificantBits();
        this.radius = radius;
        this.anchor = anchor;
        this.projectTypeId = "";
        this.boundaryVisible = false;
        recalcCorners();
        setChanged();
        // setPlacedBy 在 setBlock 之后执行，初始更新包里的身份尚未写入 → 主动推送一次
        if (level != null && !level.isClientSide())
        {
            BlockState state = getBlockState();
            level.sendBlockUpdated(getBlockPos(), state, state, 2);
        }
    }

    // 命令修改半径/基准点/类型后同步身份并推送到客户端
    public void updateIdentity(int radius, int anchor, String projectTypeId, boolean boundaryVisible)
    {
        this.radius = radius;
        this.anchor = anchor;
        this.projectTypeId = projectTypeId;
        this.boundaryVisible = boundaryVisible;
        recalcCorners();
        setChanged();
        if (level != null && !level.isClientSide())
        {
            BlockState state = getBlockState();
            level.sendBlockUpdated(getBlockPos(), state, state, 2);
        }
    }

    private void recalcCorners()
    {
        BlockPos center = getBlockPos();
        int r = radius;
        int minY = switch (anchor)
        {
            case 0 -> center.getY() - 2 * r;
            case 2 -> center.getY();
            default -> center.getY() - r;
        };
        int maxY = switch (anchor)
        {
            case 0 -> center.getY();
            case 2 -> center.getY() + 2 * r;
            default -> center.getY() + r;
        };
        this.cornerNW = new BlockPos(center.getX() - r, minY, center.getZ() - r);
        this.cornerSE = new BlockPos(center.getX() + r, maxY, center.getZ() + r);
    }

    // ===================== 客户端同步 =====================

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries)
    {
        return this.saveWithFullMetadata(registries);
    }

    // ===================== 生命周期 =====================

    @Override
    public void onLoad()
    {
        super.onLoad();
        if (!hasInstance() || level == null) return;
        if (!level.isClientSide())
        {
            // 服务端：确保全局管理器有对应实例（SavedData 意外丢失时按身份重建）
            ProjectCenterManager.ensureFromIdentity((ServerLevel) level, this);
        }
        // 双端注册渲染视图：客户端渲染边界框读方块实体实时数据；
        // 服务端注册供集成服务器（客户端与服务端同 JVM）直接读取，保证边界渲染始终有数据
        RegionalManagerRegistry.register(this);
    }

    @Override
    public void onChunkUnloaded()
    {
        super.onChunkUnloaded();
        if (hasInstance())
        {
            RegionalManagerRegistry.unregister(getId());
        }
    }

    // 仅方块被移除/替换时触发（chunk 卸载与世界关闭都不会调用）
    // → 安全的"删除中心"信号
    // 例外：退出序列中 LevelChunk 的清理路径会触发 setRemoved（已由日志证实），
    // 此时走关服标志跳过删除，避免清空内存数据导致空档存档
    @Override
    public void setRemoved()
    {
        super.setRemoved();
        if (!hasInstance() || level == null) return;
        if (!level.isClientSide())
        {
            if (!ProjectCenterManager.isShuttingDown())
            {
                ProjectCenterManager.deleteById((ServerLevel) level, getId());
            }
        }
        RegionalManagerRegistry.unregister(getId());
    }

    // ===================== NBT =====================

    @Override
    protected void loadAdditional(ValueInput input)
    {
        super.loadAdditional(input);
        idMost = input.getLongOr("idMost", 0L);
        idLeast = input.getLongOr("idLeast", 0L);
        ownerMost = input.getLongOr("ownerMost", 0L);
        ownerLeast = input.getLongOr("ownerLeast", 0L);
        radius = input.getIntOr("radius", 5);
        anchor = input.getIntOr("anchor", 1);
        projectTypeId = input.getStringOr("projectTypeId", "");
        boundaryVisible = input.getBooleanOr("boundaryVisible", false);
        recalcCorners();
    }

    @Override
    protected void saveAdditional(ValueOutput output)
    {
        super.saveAdditional(output);
        if (hasInstance())
        {
            output.putLong("idMost", idMost);
            output.putLong("idLeast", idLeast);
            output.putLong("ownerMost", ownerMost);
            output.putLong("ownerLeast", ownerLeast);
            output.putInt("radius", radius);
            output.putInt("anchor", anchor);
            output.putString("projectTypeId", projectTypeId);
            output.putBoolean("boundaryVisible", boundaryVisible);
        }
    }
}
