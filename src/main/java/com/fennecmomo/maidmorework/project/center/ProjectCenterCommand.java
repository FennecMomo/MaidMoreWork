package com.fennecmomo.maidmorework.project.center;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.fennecmomo.maidmorework.lib.projecttype.ProjectTypeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.UUID;

// 工程中心命令：全部通过 ProjectCenterManager 按 id 直接操作中心实例
// 半径/基准点/类型修改后同步方块实体身份，保证客户端边界渲染一致
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
                        .then(Commands.literal("name")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    String idStr = StringArgumentType.getString(ctx, "id");
                                                    String name = StringArgumentType.getString(ctx, "name");
                                                    return setName(ctx.getSource(), idStr, name);
                                                }))))
                ));
    }

    private static ProjectCenterInstance resolve(CommandSourceStack source, String idStr)
    {
        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return null; }
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) return null;
        return ProjectCenterManager.get(serverLevel, id);
    }

    private static void syncIdentity(Level level, ProjectCenterInstance inst)
    {
        if (level.getBlockEntity(inst.getBlockPos()) instanceof ProjectCenterBlockEntity be)
        {
            be.updateIdentity(inst.getRadius(), inst.getAnchor(),
                    inst.getProjectTypeId(), inst.isBoundaryVisible());
        }
    }

    private static int deleteCenter(CommandSourceStack source, String idStr)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        ProjectCenterInstance inst = resolve(source, idStr);
        if (inst == null)
        {
            player.sendSystemMessage(Component.literal("§c未找到工程中心"));
            return 0;
        }

        if (source.getLevel() instanceof ServerLevel serverLevel)
        {
            ProjectCenterManager.deleteById(serverLevel, inst.getId());
        }
        source.getLevel().removeBlock(inst.getBlockPos(), false);
        player.sendSystemMessage(Component.literal("§c工程中心已删除"));
        return 1;
    }

    private static int setRadius(CommandSourceStack source, String idStr, int value)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        ProjectCenterInstance inst = resolve(source, idStr);
        if (inst == null)
        {
            player.sendSystemMessage(Component.literal("§c未找到工程中心"));
            return 0;
        }

        inst.setRadius(value);
        syncIdentity(source.getLevel(), inst);
        player.sendSystemMessage(Component.literal("§a半径已更新为: " + value));
        return 1;
    }

    private static int setAnchor(CommandSourceStack source, String idStr, String mode)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        int anchor;
        switch (mode)
        {
            case "top" -> anchor = 0;
            case "center" -> anchor = 1;
            case "bottom" -> anchor = 2;
            default -> { return 0; }
        }

        ProjectCenterInstance inst = resolve(source, idStr);
        if (inst == null)
        {
            player.sendSystemMessage(Component.literal("§c未找到工程中心"));
            return 0;
        }

        inst.setAnchor(anchor);
        syncIdentity(source.getLevel(), inst);
        player.sendSystemMessage(Component.literal("§a基准点已更新为: " + mode));
        return 1;
    }

    private static int setType(CommandSourceStack source, String idStr, String typeId)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        if (ProjectTypeRegistry.get(typeId) == null)
        {
            player.sendSystemMessage(Component.literal("§c未知的工程类型: " + typeId));
            return 0;
        }

        ProjectCenterInstance inst = resolve(source, idStr);
        if (inst == null)
        {
            player.sendSystemMessage(Component.literal("§c未找到工程中心"));
            return 0;
        }

        inst.setProjectTypeId(typeId);
        inst.setBoundaryVisible(true);
        syncIdentity(source.getLevel(), inst);
        player.sendSystemMessage(Component.literal("§a工程类型已设置为: " + typeId));
        return 1;
    }

    private static int setName(CommandSourceStack source, String idStr, String name)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        ProjectCenterInstance inst = resolve(source, idStr);
        if (inst == null)
        {
            player.sendSystemMessage(Component.literal("§c未找到工程中心"));
            return 0;
        }

        inst.setName(name);
        player.sendSystemMessage(Component.literal("§a工程中心已命名为: " + inst.getName()));
        return 1;
    }
}
