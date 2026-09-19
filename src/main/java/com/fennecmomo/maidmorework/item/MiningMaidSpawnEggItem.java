package com.fennecmomo.maidmorework.item;

import com.fennecmomo.maidmorework.project.mine.MiningTask;
import com.github.tartaricacid.touhoulittlemaid.entity.backpack.BackpackManager;
import com.github.tartaricacid.touhoulittlemaid.entity.backpack.SmallBackpack;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

// 测试用刷怪蛋（2026-09-04 拍板）：右键生成一只"自带小型女仆背包 + 生成点开启 Home 模式 + 工作选定挖矿"
// 的女仆，省去测试时手动配置的步骤；外观沿用 TLM 的女仆刷怪蛋贴图
public class MiningMaidSpawnEggItem extends Item
{
    private static final int HOME_RADIUS = 32;

    public MiningMaidSpawnEggItem(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context)
    {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel))
        {
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        EntityType<EntityMaid> type = InitEntities.MAID.get();
        EntityMaid maid = type.spawn(serverLevel, pos, EntitySpawnReason.SPAWN_ITEM_USE);
        if (maid == null)
        {
            return InteractionResult.FAIL;
        }

        // 归属生成者 + 永不自然消失（测试环境方便）
        Player player = context.getPlayer();
        if (player != null)
        {
            maid.setOwnerReference(EntityReference.of(player));
            maid.setTame(true, true);
        }
        maid.setPersistenceRequired();

        // 生成位置开启 Home 模式并把家设在这里
        maid.getConfigManager().setHomeModeEnable(true);
        maid.setHomeTo(pos, HOME_RADIUS);

        // 自带小型女仆背包
        BackpackManager.findBackpack(SmallBackpack.ID).ifPresent(backpack ->
        {
            maid.getBackpackManager().setMaidBackpackType(backpack);
            maid.getBackpackManager().setBackpackDelay();
        });

        // 工作选定挖矿
        TaskManager.findTask(MiningTask.UID).ifPresent(task -> maid.getTaskManager().setTask(task));

        serverLevel.playSound(null, maid.getX(), maid.getY(), maid.getZ(),
                SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.6F, 1.0F);

        ItemStack stack = context.getItemInHand();
        if (player == null || !player.getAbilities().instabuild)
        {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
