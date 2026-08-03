package com.fennecmomo.maidmorework;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;
import java.util.UUID;

// 女仆持久化 Attachment 类型（NeoForge Attachment 系统）
// 用于退出重进后恢复女仆与中心的关联
// 数据随女仆实体一起序列化到磁盘
//
// 词条归属原则（一词条只一边记）：
//   女仆侧只持久化 PROJECT_CENTER_UUID_SAVED（她属于哪个中心）
//   工程归属（女仆↔工程）的持久化真相在中心的 assignments 表（SavedData），
//   女仆不再记工程 UUID，重进后向中心请求恢复/重新分配
public class ModAttachments
{
    // Attachment 类型延迟注册表
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MaidMoreWork.MODID);

    // 中心 UUID 引用持久化（女仆所属的工程中心实例标识）
    // 世界重进后通过 ProjectCenterManager.getCenterOf 重新关联中心
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Optional<UUID>>> PROJECT_CENTER_UUID_SAVED =
            ATTACHMENT_TYPES.register("project_center_uuid_saved", () ->
                    AttachmentType.<Optional<UUID>>builder(Optional::empty)
                            .serialize(new IAttachmentSerializer<Optional<UUID>>()
                            {
                                @Override
                                public Optional<UUID> read(IAttachmentHolder holder, ValueInput input)
                                {
                                    return input.getStringOr("uuid", "")
                                            .isEmpty() ? Optional.empty()
                                            : Optional.of(UUID.fromString(input.getStringOr("uuid", "")));
                                }

                                @Override
                                public boolean write(Optional<UUID> value, ValueOutput output)
                                {
                                    if (value.isPresent())
                                    {
                                        output.putString("uuid", value.get().toString());
                                        return true;
                                    }
                                    return false;
                                }
                            })
                            .build());
}
