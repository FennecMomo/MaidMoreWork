package com.fennecmomo.maidmorework.mining;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;
import java.util.List;

import net.minecraft.world.level.block.Blocks;

// 矿井管理命令
// /maidmorework mine delete <id> — 删除矿井
// /maidmorework mine edit <id>  — 重新绑定到标记工具
public class MineCommand
{
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("maidmorework")
                .then(Commands.literal("mine")
                        .then(Commands.literal("delete")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String idStr = StringArgumentType.getString(ctx, "id");
                                            return deleteMine(ctx.getSource(), idStr);
                                        })))
                        .then(Commands.literal("edit")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String idStr = StringArgumentType.getString(ctx, "id");
                                            return editMine(ctx.getSource(), idStr);
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
                        .then(Commands.literal("showdig")
                                .executes(ctx -> showdig(ctx.getSource())))
                        .then(Commands.literal("showstep")
                                .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            int index = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index");
                                            return showstep(ctx.getSource(), index);
                                        })))
                ));
    }

    private static int deleteMine(CommandSourceStack source, String idStr)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        Level level = source.getLevel();
        MineInstance inst = MineInstanceManager.get(level, id);
        if (inst == null) return 0;

        // 找到并销毁矿井实体方块
        // 以实例中心为原点搜索16格范围
        BlockPos center = inst.center();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx++)
        {
            for (int dy = -16; dy <= 16; dy++)
            {
                for (int dz = -16; dz <= 16; dz++)
                {
                    mp.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.getBlockEntity(mp) instanceof MineBlockEntity mbe
                            && mbe.getInstanceId().equals(id))
                    {
                        level.removeBlock(mp, false);
                    }
                }
            }
        }

        // 删实例
        MineInstanceManager.remove(level, id);

        player.sendSystemMessage(Component.literal("§c矿井已删除"));
        return 1;
    }

    private static int editMine(CommandSourceStack source, String idStr)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        UUID id;
        try { id = UUID.fromString(idStr); }
        catch (IllegalArgumentException e) { return 0; }

        ServerLevel level = source.getLevel();
        MineInstance inst = MineInstanceManager.get(level, id);
        if (inst == null) return 0;

        // 销毁旧的矿井方块（实际放置位置是 center.above()）
        BlockPos oldCenter = inst.center().above();
        if (level.getBlockEntity(oldCenter) instanceof MineBlockEntity mbe
                && mbe.getInstanceId().equals(id))
        {
            level.removeBlock(oldCenter, false);
        }

        // 找标记工具并绑定
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        ItemStack marker = null;

        if (mainHand.getItem() instanceof MineMarkerItem) marker = mainHand;
        else if (offHand.getItem() instanceof MineMarkerItem) marker = offHand;

        if (marker == null)
        {
            player.sendSystemMessage(Component.literal("§c请手持标记工具再点击编辑"));
            return 0;
        }

        if (MineMarkerItem.isBound(marker))
        {
            MineMarkerItem.unbind(marker);
        }
        MineMarkerItem.bind(marker, id);

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
        MineInstance inst = MineInstanceManager.get(level, id);
        if (inst == null || !inst.isComplete()) return 0;

        BlockPos center = inst.center();
        BlockPos displayCenter = inst.center().below();

        if (blocked && !level.getBlockState(center).isAir())
        {
            level.destroyBlock(center, true);
        }

        BlockState mineState = MineRegistration.MINE_BLOCK.get().defaultBlockState();
        level.setBlockAndUpdate(center, mineState);
        if (level.getBlockEntity(center) instanceof MineBlockEntity mbe)
        {
            mbe.setInstanceData(inst.getId(), inst.getOwner(), inst.getPosNW(), inst.getPosSE());
        }

        // 生成可见粒子提示位置
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                displayCenter.getX() + 0.5, displayCenter.getY() + 0.5, displayCenter.getZ() + 0.5,
                10, 0.5, 0.5, 0.5, 0.1);

        // 解绑标记工具
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof MineMarkerItem)
        {
            MineMarkerItem.unbind(mainHand);
        }

        player.sendSystemMessage(Component.literal("§a矿井已创建！中心: " + displayCenter.toShortString()));
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
        MineInstanceManager.remove(level, id);

        // 解绑标记工具
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof MineMarkerItem)
        {
            MineMarkerItem.unbind(mainHand);
        }

        player.sendSystemMessage(Component.literal("§c已取消矿井创建"));
        return 1;
    }

    // 调试命令：找到最近矿井，清空一个周期的挖区方块
    private static int showdig(CommandSourceStack source)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        ServerLevel level = source.getLevel();
        BlockPos playerPos = player.blockPosition();

        // 搜索32格内最近的矿井
        MineBlockEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -32; dx <= 32; dx++)
        {
            for (int dy = -32; dy <= 32; dy++)
            {
                for (int dz = -32; dz <= 32; dz++)
                {
                    mp.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    if (level.getBlockEntity(mp) instanceof MineBlockEntity mbe && mbe.hasInstance())
                    {
                        double dist = mp.distSqr(playerPos);
                        if (dist < nearestDist) { nearestDist = dist; nearest = mbe; }
                    }
                }
            }
        }
        if (nearest == null) { player.sendSystemMessage(Component.literal("§c32格内未找到矿井")); return 0; }

        List<BlockPos> digBlocks = nearest.getAllDigBlocksForCycle();
        int count = 0;
        for (BlockPos pos : digBlocks)
        {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            count++;
        }
        // 收集实际涉及的Y层用于调试
        java.util.Set<Integer> ys = new java.util.TreeSet<>();
        for (BlockPos pos : digBlocks) ys.add(pos.getY());
        player.sendSystemMessage(Component.literal("§a已清空 " + count + " 个挖区方块，涉及Y层：" + ys));
        return count;
    }

    // 调试命令：只挖周期内第layerIndex层Y（0-based）
    private static int showstep(CommandSourceStack source, int index)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        ServerLevel level = source.getLevel();
        BlockPos playerPos = player.blockPosition();

        MineBlockEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -32; dx <= 32; dx++)
        {
            for (int dy = -32; dy <= 32; dy++)
            {
                for (int dz = -32; dz <= 32; dz++)
                {
                    mp.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    if (level.getBlockEntity(mp) instanceof MineBlockEntity mbe && mbe.hasInstance())
                    {
                        double dist = mp.distSqr(playerPos);
                        if (dist < nearestDist) { nearestDist = dist; nearest = mbe; }
                    }
                }
            }
        }
        if (nearest == null) { player.sendSystemMessage(Component.literal("§c32格内未找到矿井")); return 0; }

        List<BlockPos> digBlocks = nearest.getDigBlocksForLayer(index);
        int y = digBlocks.isEmpty() ? Integer.MIN_VALUE : digBlocks.get(0).getY();
        int count = 0;
        for (BlockPos pos : digBlocks)
        {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            count++;
        }
        player.sendSystemMessage(Component.literal("§aY层 " + index + " (Y=" + y + ") 已清空 " + count + " 个挖区方块"));
        return count;
    }
}
