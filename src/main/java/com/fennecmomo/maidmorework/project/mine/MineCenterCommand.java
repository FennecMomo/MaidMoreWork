package com.fennecmomo.maidmorework.project.mine;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
                                }))));
    }

    private static int cancel(ServerPlayer player)
    {
        if (!(player.getMainHandItem().getItem() instanceof MineCenterMarkerItem))
        {
            player.sendSystemMessage(Component.literal("§c请手持矿井标记工具"));
            return 0;
        }
        MineCenterMarkerItem.clearCorners(player.getMainHandItem());
        player.sendSystemMessage(Component.literal("§e已取消矿井创建，角点已清空"));
        return 1;
    }
}
