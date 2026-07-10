package com.fennecmomo.maidmorework;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

// 女仆持久化 Attachment 类型
// 用于退出重进后恢复伐木进度，LOG_BLOCKS 和 LEAVES_BLOCKS 存盘
public class ModAttachments
{
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MaidMoreWork.MODID);

    private static final Codec<List<BlockPos>> BLOCKPOS_LIST_CODEC = BlockPos.CODEC.listOf();

    // 原木列表持久化
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<BlockPos>>> LOG_BLOCKS_SAVED =
            ATTACHMENT_TYPES.register("log_blocks_saved", () ->
                    AttachmentType.builder(() -> (List<BlockPos>) List.<BlockPos>of())
                            .serialize(new IAttachmentSerializer<List<BlockPos>>()
                            {
                                @Override
                                public List<BlockPos> read(IAttachmentHolder holder, ValueInput input)
                                {
                                    return input.read("blocks", BLOCKPOS_LIST_CODEC).orElse(List.of());
                                }

                                @Override
                                public boolean write(List<BlockPos> list, ValueOutput output)
                                {
                                    output.store("blocks", BLOCKPOS_LIST_CODEC, list);
                                    return true;
                                }
                            })
                            .build());

    // 树叶列表持久化
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<BlockPos>>> LEAVES_BLOCKS_SAVED =
            ATTACHMENT_TYPES.register("leaves_blocks_saved", () ->
                    AttachmentType.builder(() -> (List<BlockPos>) List.<BlockPos>of())
                            .serialize(new IAttachmentSerializer<List<BlockPos>>()
                            {
                                @Override
                                public List<BlockPos> read(IAttachmentHolder holder, ValueInput input)
                                {
                                    return input.read("blocks", BLOCKPOS_LIST_CODEC).orElse(List.of());
                                }

                                @Override
                                public boolean write(List<BlockPos> list, ValueOutput output)
                                {
                                    output.store("blocks", BLOCKPOS_LIST_CODEC, list);
                                    return true;
                                }
                            })
                            .build());
}
