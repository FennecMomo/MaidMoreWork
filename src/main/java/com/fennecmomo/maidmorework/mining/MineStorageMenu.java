package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.momolib.template.Data.GenericContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

// 矿井方块储物容器 Menu（服务端 + 客户端共用）
// 3排×9列=27格容器 + 玩家背包，继承 GenericContainerMenu 自动布局
// 女仆挖到的矿物通过 TLM 掉落物收集自动塞进这个容器
// 容器满了会从方块位置爆出掉地上
//
// 服务端构造：直接传 MineBlockEntity，读写实际容器数据
// 客户端构造：用 SimpleContainer(27) 作为占位，布局硬编码
public class MineStorageMenu extends GenericContainerMenu
{
    private final MineBlockEntity blockEntity;

    // 服务端构造：直接传 MineBlockEntity，数据从方块实体读取
    public MineStorageMenu(int containerId, Inventory inv, MineBlockEntity be)
    {
        super(MineRegistration.MINE_STORAGE_MENU.get(), containerId, 9, 3,
                be, inv);
        this.blockEntity = be;
    }

    // 客户端构造：布局硬编码，不需要 MineBlockEntity
    // 客户端用 SimpleContainer(27) 作为占位容器，服务端同步数据过来
    public MineStorageMenu(int containerId, Inventory inv, net.minecraft.network.RegistryFriendlyByteBuf buf)
    {
        super(MineRegistration.MINE_STORAGE_MENU.get(), containerId, 9, 3,
                new SimpleContainer(27), inv);
        this.blockEntity = null;
    }

    // 自定义槽位创建：使用 MineSlot 支持 640 堆叠
    @Override
    protected Slot createContainerSlot(Container container, int index, int x, int y)
    {
        return new MineSlot(container, index, x, y);
    }

    // 容器是否仍然可用：客户端 blockEntity 为 null 时始终可用
    // 服务端检查玩家与方块的距离
    @Override
    public boolean stillValid(Player player)
    {
        return blockEntity == null || blockEntity.stillValid(player);
    }

    // 自定义 Slot：允许 640 堆叠（MineBlockEntity.MAX_STACK）
    // 普通容器最多 64，矿井容器允许更多堆叠以容纳大量矿物
    private static class MineSlot extends Slot
    {
        public MineSlot(Container c, int idx, int x, int y)
        {
            super(c, idx, x, y);
        }

        @Override
        public int getMaxStackSize()
        {
            return MineBlockEntity.MAX_STACK;
        }
    }
}
