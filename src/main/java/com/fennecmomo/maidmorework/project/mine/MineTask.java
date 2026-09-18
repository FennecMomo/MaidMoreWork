package com.fennecmomo.maidmorework.project.mine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;

// 矿井派发任务（MINE_REDESIGN §4 四型 + B2 索光源指令）
//
// DESTROY / FILL / REPLACE / SETLIGHT 为四类真实派发的坐标任务；
// FETCH_LIGHT 不是坐标任务：pos 指向矿井方块，Behavior 驱动女仆前去仓库取光源
// （B2 拍板：领到 SETLIGHT 而背包无光源时先取灯）
public record MineTask(BlockPos pos, Type type)
{
    // 带类型困难表（§7 矿井自有表）持久化用
    public static final Codec<MineTask> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(MineTask::pos),
            Codec.STRING.xmap(Type::valueOf, Type::name).fieldOf("type").forGetter(MineTask::type)
    ).apply(inst, MineTask::new));

    public enum Type
    {
        DESTROY,
        FILL,
        REPLACE,
        SETLIGHT,
        FETCH_LIGHT,
        PLACE_CONTROL,
        SETLIGHT_FLOOR
    }
}
