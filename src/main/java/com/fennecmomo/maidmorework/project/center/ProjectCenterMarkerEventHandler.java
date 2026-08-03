package com.fennecmomo.maidmorework.project.center;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

@EventBusSubscriber(modid = com.fennecmomo.maidmorework.MaidMoreWork.MODID)
public class ProjectCenterMarkerEventHandler
{
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (event.getLevel().isClientSide()) return;

        Player player = event.getEntity();
        ItemStack stack = player.getMainHandItem();

        if (event.getLevel().getBlockEntity(event.getPos()) instanceof ProjectCenterBlockEntity be
                && be.hasInstance()
                && stack.getItem() instanceof ProjectCenterMarkerItem
                && player.isCrouching())
        {
            if (event instanceof ICancellableEvent c)
            {
                c.setCanceled(true);
            }
            handleCenterBind(player, be);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        Player player = event.getEntity();
        Level level = player.level();
        if (level.isClientSide()) return;

        ItemStack stack = player.getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof ProjectCenterMarkerItem))
        {
            return;
        }

        if (!ProjectCenterMarkerItem.isBound(stack))
        {
            player.sendSystemMessage(Component.literal(
                    "§c未绑定工程中心，请蹲下+右键工程中心方块进行绑定"));
            return;
        }

        UUID boundId = ProjectCenterMarkerItem.getBoundId(stack);
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        ProjectCenterInstance center = ProjectCenterManager.get(serverLevel, boundId);
        if (center == null)
        {
            player.sendSystemMessage(Component.literal(
                    "§c绑定的管理器已失效，请重新绑定"));
            return;
        }

        if (player instanceof ServerPlayer sp)
        {
            var payload = new ProjectCenterEditPayload(
                    center.getId(), center.getBlockPos(), center.getRadius(),
                    center.getAnchor(), center.getProjectTypeId());
            PacketDistributor.sendToPlayer(sp, payload);
        }
    }

    private static void handleCenterBind(Player player, ProjectCenterBlockEntity be)
    {
        Level level = player.level();
        if (level.isClientSide()) return;

        ItemStack stack = player.getMainHandItem();
        if (ProjectCenterMarkerItem.isBound(stack))
        {
            ProjectCenterMarkerItem.unbind(stack);
        }
        ProjectCenterMarkerItem.bind(stack, be.getId());
        player.sendSystemMessage(Component.literal("§a已绑定工程中心"));
        level.playSound(null, be.getBlockPos(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 0.8f, 1.2f);
    }
}
