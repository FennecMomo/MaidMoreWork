package com.fennecmomo.maidmorework.mining;

import com.fennecmomo.maidmorework.MaidMoreWork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.UUID;

@EventBusSubscriber(modid = MaidMoreWork.MODID)
public class MineMarkerEventHandler
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

        // 先检查是不是mine_block（管理交互）
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof MineBlockEntity)
        {
            // 不阻止，让MineBlock的自然交互走（但Block没use方法了，走事件处理）
            if (stack.getItem() instanceof MineMarkerItem)
            {
                if (event instanceof net.neoforged.bus.api.ICancellableEvent c)
        {
            c.setCanceled(true);
        }
                handleMineBlockClick(player, event.getPos());
            }
            return;
        }

        if (!(stack.getItem() instanceof MineMarkerItem)) return;
        handleCornerClick(event, player, event.getPos(), false);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        Player player = event.getEntity();
        ItemStack stack = player.getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof MineMarkerItem)) return;
        if (!MineMarkerItem.isBound(stack)) return;

        Level level = event.getLevel();
        if (level.isClientSide()) return;

        UUID id = MineMarkerItem.getBoundId(stack);
        MineInstance inst = MineInstanceManager.get(level, id);
        if (inst == null || !inst.isComplete()) return;

        // 完整矿井 + 右键空气 → 弹确认窗
        if (player instanceof ServerPlayer sp)
        {
            openConfirmScreen(sp, inst, stack);
        }
    }

    private static void handleMineBlockClick(Player player, BlockPos pos)
    {
        Level level = player.level();
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return;

        if (level.getBlockEntity(pos) instanceof MineBlockEntity be && be.hasInstance())
        {
            UUID id = be.getInstanceId();
            sendManageMessage(sp, id);
        }
    }

    private static void sendManageMessage(ServerPlayer sp, UUID id)
    {
        net.minecraft.network.chat.Style style = net.minecraft.network.chat.Style.EMPTY
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(
                        "/maidmorework mine delete " + id))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                        Component.literal("删除此矿井")));
        Component deleteBtn = Component.literal("§c[删除]").withStyle(style);

        net.minecraft.network.chat.Style style2 = net.minecraft.network.chat.Style.EMPTY
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(
                        "/maidmorework mine edit " + id))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                        Component.literal("重新绑定到标记工具")));
        Component editBtn = Component.literal("§e[编辑]").withStyle(style2);

        sp.sendSystemMessage(Component.literal("§6=== 矿井管理 ===  ")
                .append(deleteBtn).append("  ").append(editBtn));
    }

    private static void handleCornerClick(PlayerInteractEvent event, Player player,
                                           BlockPos pos, boolean isLeft)
    {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof MineMarkerItem)) return;

        Level level = event.getLevel();
        if (level.isClientSide()) return;

        if (event instanceof net.neoforged.bus.api.ICancellableEvent c)
        {
            c.setCanceled(true);
        }

        UUID owner = player.getUUID();
        boolean bound = MineMarkerItem.isBound(stack);

        if (!bound)
        {
            // 未绑定 → 创建矿井实例
            MineInstance inst = MineInstanceManager.create(level, owner, pos);
            inst.updateAllHeights(player.blockPosition().getY());
            MineMarkerItem.bind(stack, inst.getId());
            player.sendSystemMessage(Component.literal("§a角1已设置: " + pos.toShortString()));
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 0.5f, 1.0f);
            return;
        }

        UUID id = MineMarkerItem.getBoundId(stack);
        MineInstance inst = MineInstanceManager.get(level, id);
        if (inst == null)
        {
            inst = MineInstanceManager.create(level, owner, pos);
            MineMarkerItem.bind(stack, inst.getId());
            inst.updateAllHeights(player.blockPosition().getY());
            player.sendSystemMessage(Component.literal("§e矿井实例已丢失，已重新创建"));
            return;
        }

        // 更新高度
        inst.updateAllHeights(player.blockPosition().getY());

        if (!inst.isComplete())
        {
            inst.setCorner2(pos);
            player.sendSystemMessage(Component.literal("§a角2已设置: " + pos.toShortString()));
        }
        else
        {
            if (isLeft) inst.setCorner1(pos);
            else inst.setCorner2(pos);
            player.sendSystemMessage(Component.literal("§e边角已更新"));
        }

        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 0.5f, 1.0f);
    }

    private static void openConfirmScreen(ServerPlayer player, MineInstance inst, ItemStack marker)
    {
        BlockPos center = inst.center();
        Level level = player.level();
        ServerLevel serverLevel = (ServerLevel) level;

        BlockState centerState = level.getBlockState(center);
        boolean blocked = !centerState.isAir()
                && !(level.getBlockEntity(center) instanceof MineBlockEntity mbe
                      && mbe.getInstanceId().equals(inst.getId()));

        // 检查最小范围 3x3x3
        int sizeX = inst.maxX() - inst.minX() + 1;
        int sizeY = inst.maxY() - inst.minY() + 1;
        int sizeZ = inst.maxZ() - inst.minZ() + 1;
        if (sizeX < 3 || sizeY < 3 || sizeZ < 3)
        {
            player.sendSystemMessage(Component.literal(
                    String.format("§c矿井范围过小(%dx%dx%d)，最小需要3x3x3", sizeX, sizeY, sizeZ)));
            return;
        }

        UUID id = inst.getId();
        Component confirmBtn = Component.literal("§a[确认]").withStyle(s -> s
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(
                        "/maidmorework mine confirm " + id + " " + blocked))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                        Component.literal("确认创建矿井"))));

        Component cancelBtn = Component.literal("§c[取消]").withStyle(s -> s
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(
                        "/maidmorework mine cancel " + id))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                        Component.literal("取消"))));

        String info = String.format("§e中心: %s  范围: %dx%dx%d",
                center.toShortString(),
                inst.maxX() - inst.minX() + 1,
                inst.maxY() - inst.minY() + 1,
                inst.maxZ() - inst.minZ() + 1);

        if (blocked)
        {
            info += " §c⚠中心有方块，确认将破坏";
        }

        player.sendSystemMessage(Component.literal(info).append("  ").append(confirmBtn).append("  ").append(cancelBtn));
    }

    private static void confirmAndPlace(ServerPlayer player, MineInstance inst,
                                         BlockPos center, ItemStack marker,
                                         ServerLevel level, boolean replaceBlock)
    {
        if (replaceBlock && !level.getBlockState(center).isAir())
        {
            level.destroyBlock(center, true);
        }

        BlockState mineState = MineRegistration.MINE_BLOCK.get().defaultBlockState();
        level.setBlockAndUpdate(center, mineState);
        if (level.getBlockEntity(center) instanceof MineBlockEntity mbe)
        {
            mbe.setInstanceData(inst.getId(), inst.getOwner(), inst.getCorner1(), inst.getCorner2());
        }

        MineMarkerItem.unbind(marker);

        player.sendSystemMessage(Component.literal("§a矿井已创建！中心: " + center.toShortString()));
        level.playSound(null, center, SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 0.8f, 1.2f);
    }
}
