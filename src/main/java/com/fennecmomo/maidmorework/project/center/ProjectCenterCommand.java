package com.fennecmomo.maidmorework.project.center;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.fennecmomo.maidmorework.lib.projecttype.ProjectTypeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class ProjectCenterCommand
{
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("maidmorework")
                .then(Commands.literal("center")
                        .then(Commands.literal("delete")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String idStr = StringArgumentType.getString(ctx, "id");
                                            return deleteCenter(ctx.getSource(), idStr);
                                        })))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    String idStr = StringArgumentType.getString(ctx, "id");
                                                    int value = IntegerArgumentType.getInteger(ctx, "value");
                                                    return setRadius(ctx.getSource(), idStr, value);
                                                }))))
                        .then(Commands.literal("anchor")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String idStr = StringArgumentType.getString(ctx, "id");
                                                    String mode = StringArgumentType.getString(ctx, "mode");
                                                    return setAnchor(ctx.getSource(), idStr, mode);
                                                }))))
                        .then(Commands.literal("type")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("typeId", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String idStr = StringArgumentType.getString(ctx, "id");
                                                    String typeId = StringArgumentType.getString(ctx, "typeId");
                                                    return setType(ctx.getSource(), idStr, typeId);
                                                }))))
                ));
    }

    private static int deleteCenter(CommandSourceStack source, String idStr)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        Level level = source.getLevel();
        ProjectCenterInstance inst = ProjectCenterInstanceManager.get(level, id);
        if (inst == null) return 0;

        BlockPos center = inst.center();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx++)
        {
            for (int dy = -16; dy <= 16; dy++)
            {
                for (int dz = -16; dz <= 16; dz++)
                {
                    mp.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.getBlockEntity(mp) instanceof ProjectCenterBlockEntity cbe
                            && cbe.getId() != null && cbe.getId().equals(id))
                    {
                        level.removeBlock(mp, false);
                    }
                }
            }
        }

        ProjectCenterInstanceManager.remove(level, id);
        player.sendSystemMessage(Component.literal("§c工程中心已删除"));
        return 1;
    }

    private static int setRadius(CommandSourceStack source, String idStr, int value)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        Level level = source.getLevel();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx++)
        {
            for (int dy = -16; dy <= 16; dy++)
            {
                for (int dz = -16; dz <= 16; dz++)
                {
                    mp.set(player.blockPosition().getX() + dx, player.blockPosition().getY() + dy, player.blockPosition().getZ() + dz);
                    if (level.getBlockEntity(mp) instanceof ProjectCenterBlockEntity cbe
                            && cbe.getId() != null && cbe.getId().equals(id))
                    {
                        cbe.setRadius(value);
                        player.sendSystemMessage(Component.literal("§a半径已更新为: " + value));
                        return 1;
                    }
                }
            }
        }

        player.sendSystemMessage(Component.literal("§c未找到工程中心"));
        return 0;
    }

    private static int setAnchor(CommandSourceStack source, String idStr, String mode)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        int anchor;
        switch (mode)
        {
            case "top" -> anchor = 0;
            case "center" -> anchor = 1;
            case "bottom" -> anchor = 2;
            default -> { return 0; }
        }

        Level level = source.getLevel();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx++)
        {
            for (int dy = -16; dy <= 16; dy++)
            {
                for (int dz = -16; dz <= 16; dz++)
                {
                    mp.set(player.blockPosition().getX() + dx, player.blockPosition().getY() + dy, player.blockPosition().getZ() + dz);
                    if (level.getBlockEntity(mp) instanceof ProjectCenterBlockEntity cbe
                            && cbe.getId() != null && cbe.getId().equals(id))
                    {
                        cbe.setAnchor(anchor);
                        player.sendSystemMessage(Component.literal("§a基准点已更新为: " + mode));
                        return 1;
                    }
                }
            }
        }

        player.sendSystemMessage(Component.literal("§c未找到工程中心"));
        return 0;
    }

    private static int setType(CommandSourceStack source, String idStr, String typeId)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        if (ProjectTypeRegistry.get(typeId) == null)
        {
            player.sendSystemMessage(Component.literal("§c未知的工程类型: " + typeId));
            return 0;
        }

        Level level = source.getLevel();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx++)
        {
            for (int dy = -16; dy <= 16; dy++)
            {
                for (int dz = -16; dz <= 16; dz++)
                {
                    mp.set(player.blockPosition().getX() + dx, player.blockPosition().getY() + dy, player.blockPosition().getZ() + dz);
                    if (level.getBlockEntity(mp) instanceof ProjectCenterBlockEntity cbe
                            && cbe.getId() != null && cbe.getId().equals(id))
                    {
                        cbe.setProjectTypeId(typeId);
                        cbe.setBoundaryVisible(true);
                        player.sendSystemMessage(Component.literal("§a工程类型已设置为: " + typeId));
                        return 1;
                    }
                }
            }
        }

        player.sendSystemMessage(Component.literal("§c未找到工程中心"));
        return 0;
    }
}
