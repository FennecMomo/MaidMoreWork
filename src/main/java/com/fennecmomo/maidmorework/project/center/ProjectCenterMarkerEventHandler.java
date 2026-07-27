package com.fennecmomo.maidmorework.project.center;

import com.fennecmomo.momolib.template.Data.ConfirmPopupMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.UUID;

@EventBusSubscriber(modid = com.fennecmomo.maidmorework.MaidMoreWork.MODID)
public class ProjectCenterMarkerEventHandler
{
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event)
    {
        handleCornerClick(event, event.getEntity(), event.getPos(), true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        Player player = event.getEntity();
        ItemStack stack = player.getMainHandItem();

        if (event.getLevel().getBlockEntity(event.getPos()) instanceof ProjectCenterBlockEntity)
        {
            if (stack.getItem() instanceof ProjectCenterMarkerItem)
            {
                if (event instanceof ICancellableEvent c)
                {
                    c.setCanceled(true);
                }
                handleCenterBlockClick(player, event.getPos());
            }
            return;
        }

        if (!(stack.getItem() instanceof ProjectCenterMarkerItem))
        {
            return;
        }
        handleCornerClick(event, player, event.getPos(), false);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        Player player = event.getEntity();
        ItemStack stack = player.getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof ProjectCenterMarkerItem))
        {
            return;
        }
        if (!ProjectCenterMarkerItem.isBound(stack))
        {
            return;
        }

        Level level = event.getLevel();
        if (level.isClientSide())
        {
            return;
        }

        UUID id = ProjectCenterMarkerItem.getBoundId(stack);
        ProjectCenterInstance inst = ProjectCenterInstanceManager.get(level, id);
        if (inst == null || !inst.isComplete())
        {
            return;
        }

        if (player instanceof ServerPlayer sp)
        {
            openConfirmScreen(sp, inst, stack);
        }
    }

    private static void handleCenterBlockClick(Player player, BlockPos pos)
    {
        Level level = player.level();
        if (level.isClientSide() || !(player instanceof ServerPlayer sp))
        {
            return;
        }

        if (level.getBlockEntity(pos) instanceof ProjectCenterBlockEntity be && be.hasInstance())
        {
            UUID id = be.getInstanceId();
            sendManageMessage(sp, id);
        }
    }

    private static void sendManageMessage(ServerPlayer sp, UUID id)
    {
        sp.openMenu(
                new SimpleMenuProvider(
                        (containerId, inv, player)
                        -> new ConfirmPopupMenu(containerId, inv,
                                "工程中心管理", "",
                                "删除", "maidmorework center delete " + id,
                                "编辑", "maidmorework center edit " + id),
                        Component.literal("工程中心管理")),
                buf ->
                {
                    buf.writeUtf("工程中心管理");
                    buf.writeUtf("");
                    buf.writeUtf("删除");
                    buf.writeUtf("maidmorework center delete " + id);
                    buf.writeUtf("编辑");
                    buf.writeUtf("maidmorework center edit " + id);
                });
    }

    private static void handleCornerClick(PlayerInteractEvent event, Player player,
            BlockPos pos, boolean isLeft)
    {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ProjectCenterMarkerItem))
        {
            return;
        }

        Level level = event.getLevel();
        if (level.isClientSide())
        {
            return;
        }

        if (event instanceof ICancellableEvent c)
        {
            c.setCanceled(true);
        }

        UUID owner = player.getUUID();
        boolean bound = ProjectCenterMarkerItem.isBound(stack);

        if (!bound)
        {
            ProjectCenterInstance inst = ProjectCenterInstanceManager.create(level, owner, pos);
            ProjectCenterMarkerItem.bind(stack, inst.getId());
            player.sendSystemMessage(Component.literal("§a角1已设置: " + pos.toShortString()));
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 0.5f, 1.0f);
            return;
        }

        UUID id = ProjectCenterMarkerItem.getBoundId(stack);
        ProjectCenterInstance inst = ProjectCenterInstanceManager.get(level, id);
        if (inst == null)
        {
            inst = ProjectCenterInstanceManager.create(level, owner, pos);
            ProjectCenterMarkerItem.bind(stack, inst.getId());
            player.sendSystemMessage(Component.literal("§e工程中心实例已丢失，已重新创建"));
            return;
        }

        if (!inst.isComplete())
        {
            inst.setClickPoint2(pos);
            player.sendSystemMessage(Component.literal("§a角2已设置: " + pos.toShortString()));
        }
        else
        {
            if (isLeft)
            {
                inst.setClickPoint1(pos);
            }
            else
            {
                inst.setClickPoint2(pos);
            }
            player.sendSystemMessage(Component.literal("§e边角已更新"));
        }

        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 0.5f, 1.0f);
    }

    private static void openConfirmScreen(ServerPlayer player, ProjectCenterInstance inst, ItemStack marker)
    {
        BlockPos center = inst.center();
        Level level = player.level();

        BlockState centerState = level.getBlockState(center);
        boolean blocked = !centerState.isAir()
                && !(level.getBlockEntity(center) instanceof ProjectCenterBlockEntity cbe
                && cbe.getInstanceId().equals(inst.getId()));

        int sizeX = inst.maxX() - inst.minX() + 1;
        int sizeZ = inst.maxZ() - inst.minZ() + 1;
        if (sizeX < 3 || sizeZ < 3)
        {
            player.sendSystemMessage(Component.literal(
                    String.format("§c工程范围过小(%dx%d)，最小需要3x3", sizeX, sizeZ)));
            return;
        }

        String info = blocked ? "中心位置有方块冲突" : "";
        player.openMenu(
                new SimpleMenuProvider(
                        (containerId, inv, p)
                        -> new ConfirmPopupMenu(containerId, inv,
                                "确认创建工程中心？", info,
                                "确认", "maidmorework center confirm " + inst.getId() + " " + blocked,
                                "取消", "maidmorework center cancel " + inst.getId()),
                        Component.literal("确认创建工程中心")),
                buf ->
                {
                    buf.writeUtf("确认创建工程中心？");
                    buf.writeUtf(info);
                    buf.writeUtf("确认");
                    buf.writeUtf("maidmorework center confirm " + inst.getId() + " " + blocked);
                    buf.writeUtf("取消");
                    buf.writeUtf("maidmorework center cancel " + inst.getId());
                });
    }
}
