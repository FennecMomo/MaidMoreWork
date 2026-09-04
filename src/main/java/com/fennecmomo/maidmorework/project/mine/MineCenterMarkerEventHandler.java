package com.fennecmomo.maidmorework.project.mine;

import com.fennecmomo.maidmorework.project.center.ProjectCenterManager;
import com.fennecmomo.maidmorework.project.center.MineInstance;
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

// 矿井标记工具事件处理器（新创建流，MINE_REDESIGN §1）
//
// 交互：左键方块 → 设角点1；右键方块 → 设角点2（点矿井方块则显示矿井信息）；
//       右键空气（两角点齐全）→ 弹确认窗 → 确认后 createMine + 放置矿井中心方块
// 两角点存标记工具 CustomData（见 MineCenterMarkerItem），确认/取消命令带角点参数，
// 服务端无状态校验（不依赖任何内存 pending 结构）
@EventBusSubscriber(modid = com.fennecmomo.maidmorework.MaidMoreWork.MODID)
public class MineCenterMarkerEventHandler
{
    // ===================== 事件入口 =====================

    // 左键方块：设角点1
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event)
    {
        if (!(event.getEntity().getMainHandItem().getItem() instanceof MineCenterMarkerItem)) return;
        if (event instanceof ICancellableEvent c) c.setCanceled(true);
        if (event.getLevel().isClientSide()) return;

        setCorner(event.getEntity(), event.getPos(), true);
    }

    // 右键方块：矿井方块 → 信息；普通方块 → 设角点2
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (!(event.getEntity().getMainHandItem().getItem() instanceof MineCenterMarkerItem)) return;
        if (event.getLevel().isClientSide()) return;

        if (event.getLevel().getBlockEntity(event.getPos()) instanceof MineCenterBlockEntity)
        {
            if (event instanceof ICancellableEvent c) c.setCanceled(true);
            showMineInfo(event.getEntity(), event.getPos());
            return;
        }

        if (event instanceof ICancellableEvent c) c.setCanceled(true);
        setCorner(event.getEntity(), event.getPos(), false);
    }

    // 右键空气：两角点齐全 → 弹确认创建窗口
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        Player player = event.getEntity();
        ItemStack stack = player.getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof MineCenterMarkerItem)) return;
        if (player.level().isClientSide()) return;
        if (!MineCenterMarkerItem.hasCorner1(stack) || !MineCenterMarkerItem.hasCorner2(stack)) return;

        if (player instanceof ServerPlayer sp)
        {
            openConfirmScreen(sp, stack,
                    MineCenterMarkerItem.getCorner1(stack),
                    MineCenterMarkerItem.getCorner2(stack));
        }
    }

    // ===================== 角点设置 =====================

    private static void setCorner(Player player, BlockPos pos, boolean isCorner1)
    {
        ItemStack stack = player.getMainHandItem();
        Level level = player.level();

        if (isCorner1)
        {
            MineCenterMarkerItem.setCorner1(stack, pos);
            player.sendSystemMessage(Component.literal("§a角1已设置: " + pos.toShortString()));
        }
        else
        {
            MineCenterMarkerItem.setCorner2(stack, pos);
            player.sendSystemMessage(Component.literal("§a角2已设置: " + pos.toShortString()
                    + "§7（右键空气打开确认窗）"));
        }
        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 0.5f, 1.0f);
    }

    private static void showMineInfo(Player player, BlockPos pos)
    {
        Level level = player.level();
        if (level.getBlockEntity(pos) instanceof MineCenterBlockEntity be && be.hasInstance())
        {
            player.sendSystemMessage(Component.literal(
                    "§b矿井中心 | §f" + be.getShaftLength() + "x" + be.getShaftWidth()
                    + "§b, 边界半径: §f" + be.getRadius()
                    + (be.isExhausted() ? "§c, 已挖尽" : "")));
        }
    }

    // ===================== 创建确认 =====================

    // 由角点推导奇数边长（MINE_REDESIGN §2：奇数、≥7 下限；与旧 SpiralMinePlanner 同一约束）
    // 距离取偶数化：|a-b| 为奇数时减一，使边长 = 偶数距离 + 1 = 奇数
    private static int oddDistance(int a, int b)
    {
        int d = Math.abs(a - b);
        return (d % 2 == 0) ? d : d - 1;
    }

    private static void openConfirmScreen(ServerPlayer player, ItemStack marker,
                                          BlockPos c1, BlockPos c2)
    {
        int minX = Math.min(c1.getX(), c2.getX());
        int maxX = minX + oddDistance(c1.getX(), c2.getX());
        int minZ = Math.min(c1.getZ(), c2.getZ());
        int maxZ = minZ + oddDistance(c1.getZ(), c2.getZ());
        int length = maxX - minX + 1;
        int width = maxZ - minZ + 1;

        if (length < 7 || width < 7)
        {
            player.sendSystemMessage(Component.literal(
                    String.format("§c矿井范围过小(%dx%d)，最小需要7x7", length, width)));
            return;
        }

        player.openMenu(
                new SimpleMenuProvider(
                        (containerId, inv, p)
                        -> new ConfirmPopupMenu(containerId, inv,
                                "确认创建矿井？", String.format("竖井范围 %dx%d", length, width),
                                "确认", confirmCommand(c1, c2),
                                "取消", "maidmorework minecenter cancel"),
                        Component.literal("确认创建矿井")),
                buf ->
                {
                    buf.writeUtf("确认创建矿井？");
                    buf.writeUtf(String.format("竖井范围 %dx%d", length, width));
                    buf.writeUtf("确认");
                    buf.writeUtf(confirmCommand(c1, c2));
                    buf.writeUtf("取消");
                    buf.writeUtf("maidmorework minecenter cancel");
                });
    }

    private static String confirmCommand(BlockPos c1, BlockPos c2)
    {
        return "maidmorework minecenter confirm "
                + c1.getX() + " " + c1.getY() + " " + c1.getZ() + " "
                + c2.getX() + " " + c2.getY() + " " + c2.getZ();
    }

    // 确认创建：奇数化角点 → 计算中心/半径 → createMine + 放置矿井方块 + 写入 BE 身份
    // 由 MineCenterCommand.confirm 调用（确认窗按钮走命令）
    static boolean confirmCreate(ServerPlayer player, BlockPos c1, BlockPos c2)
    {
        ServerLevel level = (ServerLevel) player.level();
        ItemStack marker = player.getMainHandItem();
        if (!(marker.getItem() instanceof MineCenterMarkerItem))
        {
            player.sendSystemMessage(Component.literal("§c请手持矿井标记工具确认"));
            return false;
        }

        int minX = Math.min(c1.getX(), c2.getX());
        int maxX = minX + oddDistance(c1.getX(), c2.getX());
        int minZ = Math.min(c1.getZ(), c2.getZ());
        int maxZ = minZ + oddDistance(c1.getZ(), c2.getZ());
        int length = maxX - minX + 1;
        int width = maxZ - minZ + 1;

        if (length < 7 || width < 7)
        {
            player.sendSystemMessage(Component.literal(
                    String.format("§c矿井范围过小(%dx%d)，最小需要7x7", length, width)));
            return false;
        }

        // 竖井平面 Y 取玩家当前脚上一格（螺旋自该层向下，同旧版语义）
        BlockPos center = new BlockPos((minX + maxX) / 2,
                player.blockPosition().above().getY(), (minZ + maxZ) / 2);

        BlockState centerState = level.getBlockState(center);
        if (!centerState.isAir() && !centerState.canBeReplaced())
        {
            player.sendSystemMessage(Component.literal("§c矿井中心位置有方块冲突，请先清空"));
            return false;
        }

        // 干活边界半径 = 竖井半边 + 鱼骨预留（§2：实际边长 = 矿井边长 + 两边鱼骨矿道最长距离，
        // 鱼骨未实现前先给 4 格余量，可在确认后通过边界编辑调整）
        int radius = (Math.max(length, width) - 1) / 2 + 4;

        java.util.UUID id = java.util.UUID.randomUUID();
        java.util.UUID owner = player.getUUID();
        BlockPos shaftNW = new BlockPos(minX, center.getY(), minZ);
        BlockPos shaftSE = new BlockPos(maxX, center.getY(), maxZ);

        level.setBlockAndUpdate(center, MineCenterRegistration.MINE_CENTER_BLOCK.get().defaultBlockState());
        if (level.getBlockEntity(center) instanceof MineCenterBlockEntity be)
        {
            be.setMineIdentity(id, owner, radius, 0, shaftNW, shaftSE);
        }
        ProjectCenterManager.createMine(level, id, owner, center, radius, 0, shaftNW, shaftSE);

        MineCenterMarkerItem.clearCorners(marker);

        player.sendSystemMessage(Component.literal(
                "§a矿井已创建！中心: " + center.toShortString()
                + "§a, 竖井: §f" + length + "x" + width
                + "§a, 边界半径: §f" + radius));
        level.playSound(null, center, SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 0.8f, 1.2f);
        return true;
    }
}
