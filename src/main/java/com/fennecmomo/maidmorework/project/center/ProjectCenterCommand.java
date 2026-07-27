package com.fennecmomo.maidmorework.project.center;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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
                        .then(Commands.literal("edit")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String idStr = StringArgumentType.getString(ctx, "id");
                                            return editCenter(ctx.getSource(), idStr);
                                        })))
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("blocked", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String idStr = StringArgumentType.getString(ctx, "id");
                                                    String blockedStr = StringArgumentType.getString(ctx, "blocked");
                                                    return confirmCreate(ctx.getSource(), idStr, Boolean.parseBoolean(blockedStr));
                                                }))))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String idStr = StringArgumentType.getString(ctx, "id");
                                            return cancelCreate(ctx.getSource(), idStr);
                                        })))
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
                            && cbe.getInstanceId() != null && cbe.getInstanceId().equals(id))
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

    private static int editCenter(CommandSourceStack source, String idStr)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        ServerLevel level = source.getLevel();
        ProjectCenterInstance inst = ProjectCenterInstanceManager.get(level, id);
        if (inst == null) return 0;

        BlockPos oldCenter = inst.center();
        if (level.getBlockEntity(oldCenter) instanceof ProjectCenterBlockEntity cbe
                && cbe.getInstanceId() != null && cbe.getInstanceId().equals(id))
        {
            level.removeBlock(oldCenter, false);
        }

        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        ItemStack marker = null;

        if (mainHand.getItem() instanceof ProjectCenterMarkerItem) marker = mainHand;
        else if (offHand.getItem() instanceof ProjectCenterMarkerItem) marker = offHand;

        if (marker == null)
        {
            player.sendSystemMessage(Component.literal("§c请手持标记工具再点击编辑"));
            return 0;
        }

        if (ProjectCenterMarkerItem.isBound(marker))
        {
            ProjectCenterMarkerItem.unbind(marker);
        }
        ProjectCenterMarkerItem.bind(marker, id);

        player.sendSystemMessage(Component.literal("§a已进入编辑模式，左键/右键重设边角"));
        level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 0.5f, 2.0f);
        return 1;
    }

    private static int confirmCreate(CommandSourceStack source, String idStr, boolean blocked)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        ServerLevel level = source.getLevel();
        ProjectCenterInstance inst = ProjectCenterInstanceManager.get(level, id);
        if (inst == null || !inst.isComplete()) return 0;

        BlockPos center = inst.center();

        if (blocked && !level.getBlockState(center).isAir())
        {
            level.destroyBlock(center, true);
        }

        BlockState centerState = ProjectCenterRegistration.PROJECT_CENTER_BLOCK.get().defaultBlockState();
        level.setBlockAndUpdate(center, centerState);
        if (level.getBlockEntity(center) instanceof ProjectCenterBlockEntity cbe)
        {
            cbe.setInstanceData(inst.getId(), inst.getOwner(), inst.getPosNW(), inst.getPosSE());
        }

        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof ProjectCenterMarkerItem)
        {
            ProjectCenterMarkerItem.unbind(mainHand);
        }

        player.sendSystemMessage(Component.literal("§a工程中心已创建！中心: " + center.toShortString()));
        level.playSound(null, center, SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 0.8f, 1.2f);
        return 1;
    }

    private static int cancelCreate(CommandSourceStack source, String idStr)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        ServerLevel level = source.getLevel();
        ProjectCenterInstanceManager.remove(level, id);

        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof ProjectCenterMarkerItem)
        {
            ProjectCenterMarkerItem.unbind(mainHand);
        }

        player.sendSystemMessage(Component.literal("§c已取消工程中心创建"));
        return 1;
    }
}
