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

// 女仆持久化 Attachment 类型（NeoForge Attachment 系统）
// 用于退出重进后恢复伐木/挖矿进度，数据随女仆实体一起序列化到磁盘
// 与 ModMemories 的关系：Memory 是运行时数据，Attachment 是持久化数据
// 世界重进时 MaidMoreWork.onEntityJoinLevel 把 Attachment 恢复到 Memory
public class ModAttachments
{
    // Attachment 类型延迟注册表
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MaidMoreWork.MODID);

    // BlockPos 列表的 Codec（用于序列化/反序列化原木/树叶列表）
    private static final Codec<List<BlockPos>> BLOCKPOS_LIST_CODEC = BlockPos.CODEC.listOf();

    // 原木列表持久化（伐木进度恢复用）
    // 序列化到女仆实体的 NBT，读取时返回空列表作为默认值
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

    // 树叶列表持久化（伐木进度恢复用）
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

    // 工作行为描述持久化（如“砍树”、“挖矿”）
    // 用于 SearchBehavior 拼气泡框文案，以及恢复时重建 Memory
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> WORK_ACTION_SAVED =
            ATTACHMENT_TYPES.register("work_action_saved", () ->
                    AttachmentType.builder(() -> "")
                            .serialize(new IAttachmentSerializer<String>()
                            {
                                @Override
                                public String read(IAttachmentHolder holder, ValueInput input)
                                {
                                    return input.getStringOr("action", "");
                                }

                                @Override
                                public boolean write(String data, ValueOutput output)
                                {
                                    output.putString("action", data);
                                    return true;
                                }
                            })
                            .build());

    // 工作目标描述持久化（如“原木”、“矿石”）
    // 与 WORK_ACTION_SAVED 配合，组成完整的文案“无法砍树，请开启Home模式”
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> WORK_TARGET_SAVED =
            ATTACHMENT_TYPES.register("work_target_saved", () ->
                    AttachmentType.builder(() -> "")
                            .serialize(new IAttachmentSerializer<String>()
                            {
                                @Override
                                public String read(IAttachmentHolder holder, ValueInput input)
                                {
                                    return input.getStringOr("target", "");
                                }

                                @Override
                                public boolean write(String data, ValueOutput output)
                                {
                                    output.putString("target", data);
                                    return true;
                                }
                            })
                            .build());
}
