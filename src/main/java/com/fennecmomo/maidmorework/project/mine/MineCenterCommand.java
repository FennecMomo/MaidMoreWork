package com.fennecmomo.maidmorework.project.mine;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import com.fennecmomo.maidmorework.project.center.ProjectCenterManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

// 矿井中心命令：确认创建 / 取消创建（确认窗按钮走命令，服务端无状态校验）
//
//   /maidmorework minecenter confirm <c1x> <c1y> <c1z> <c2x> <c2y> <c2z>
//   /maidmorework minecenter cancel
public class MineCenterCommand
{
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("maidmorework")
                .then(Commands.literal("minecenter")
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("c1x", IntegerArgumentType.integer())
                                        .then(Commands.argument("c1y", IntegerArgumentType.integer())
                                                .then(Commands.argument("c1z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("c2x", IntegerArgumentType.integer())
                                                                .then(Commands.argument("c2y", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("c2z", IntegerArgumentType.integer())
                                                                                .executes(ctx -> {
                                                                                    ServerPlayer player = ctx.getSource().getPlayer();
                                                                                    if (player == null) return 0;
                                                                                    BlockPos c1 = new BlockPos(
                                                                                            IntegerArgumentType.getInteger(ctx, "c1x"),
                                                                                            IntegerArgumentType.getInteger(ctx, "c1y"),
                                                                                            IntegerArgumentType.getInteger(ctx, "c1z"));
                                                                                    BlockPos c2 = new BlockPos(
                                                                                            IntegerArgumentType.getInteger(ctx, "c2x"),
                                                                                            IntegerArgumentType.getInteger(ctx, "c2y"),
                                                                                            IntegerArgumentType.getInteger(ctx, "c2z"));
                                                                                    return MineCenterMarkerEventHandler.confirmCreate(player, c1, c2) ? 1 : 0;
                                                                                }))))))))
                        .then(Commands.literal("cancel")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    if (player == null) return 0;
                                    return cancel(player);
                                }))
                        .then(Commands.literal("debug")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            String idStr = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
                                            return debug(ctx.getSource(), idStr);
                                        })))
                        .then(Commands.literal("stock")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            String idStr = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
                                            return stock(ctx.getSource(), idStr);
                                        })))));
    }

    private static int cancel(ServerPlayer player)
    {
        if (!(player.getMainHandItem().getItem() instanceof MineCenterMarkerItem))
        {
            player.sendSystemMessage(Component.literal("§c请手持矿井标记工具"));
            return 0;
        }
        MineCenterMarkerEventHandler.clearPendingCorners(player);
        player.sendSystemMessage(Component.literal("§e已取消矿井创建，角点已清空"));
        return 1;
    }

    // 调试：打印矿井当前周期/层状态（螺旋分层数据验证）
    // id 支持完整 UUID 或前缀（右键矿井方块显示的 8 位短 ID 即可）
    private static int debug(CommandSourceStack source, String idStr)
    {
        if (!(source.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return 0;
        com.fennecmomo.maidmorework.project.center.MineInstance mine = resolveMine(level, idStr);
        if (mine == null)
        {
            source.sendFailure(Component.literal("§c未找到矿井实例（可用右键矿井方块显示的 8 位短 ID）"));
            return 0;
        }
        final com.fennecmomo.maidmorework.project.center.MineInstance target = mine;
        source.sendSuccess(() -> Component.literal("§b[矿井调试] §f" + target.debugLayerInfo(level)), false);
        return 1;
    }

    // 临时供货命令：把玩家主手物品 64 个存入矿井仓库
    // （玩家存取 UI 属 B3 拍板的 UI 阶段，此命令仅作测试期供货通道）
    private static int stock(CommandSourceStack source, String idStr)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        if (!(source.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return 0;

        com.fennecmomo.maidmorework.project.center.MineInstance mine = resolveMine(level, idStr);
        if (mine == null)
        {
            source.sendFailure(Component.literal("§c未找到矿井实例（可用右键矿井方块显示的 8 位短 ID）"));
            return 0;
        }
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty())
        {
            source.sendFailure(Component.literal("§c请手持要入库的物品（将存入 64 个）"));
            return 0;
        }
        ItemStack deposit = hand.copy();
        deposit.setCount(64);
        String itemName = hand.getHoverName().getString();
        mine.depositToWarehouse(java.util.List.of(deposit));
        hand.shrink(64);
        source.sendSuccess(() -> Component.literal("§a已入库 64 个 §f" + itemName), false);
        return 1;
    }

    // 按完整 UUID 或短 ID 前缀解析矿井实例（debug/stock 共用）
    private static com.fennecmomo.maidmorework.project.center.MineInstance resolveMine(
            net.minecraft.server.level.ServerLevel level, String idStr)
    {
        for (var center : ProjectCenterManager.allCenters(level))
        {
            if (!(center instanceof com.fennecmomo.maidmorework.project.center.MineInstance m)) continue;
            String full = m.getId().toString();
            if (full.equals(idStr) || full.startsWith(idStr))
            {
                return m;
            }
        }
        return null;
    }
}
