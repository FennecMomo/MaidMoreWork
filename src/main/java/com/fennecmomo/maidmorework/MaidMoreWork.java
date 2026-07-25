package com.fennecmomo.maidmorework;

import com.fennecmomo.maidmorework.item.FluidBottleItem;
import com.fennecmomo.maidmorework.mining.MineCommand;
import com.fennecmomo.maidmorework.mining.MineRegistration;
import com.fennecmomo.maidmorework.project.ProjectManager;
import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import com.fennecmomo.maidmorework.project.hud.ProjectHudRenderer;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// maidmorework 模组主类（@Mod 入口）
// 负责：
// 1. 注册所有 DeferredRegister（Memory、Attachment、MineBlock、菜单、数据组件、创造模式标签）
// 2. 注册命令和事件监听（MaidTickEvent 驱动 ProjectManager + MaidBubbleHelper）
@Mod(MaidMoreWork.MODID)
public class MaidMoreWork
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");
    public static final String MODID = "maidmorework";

    // 创造模式标签页注册
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final ResourceKey<CreativeModeTab> TAB_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(MODID, "tab"));

    // 静态初始化块：注册创造模式标签页，添加矿井方块/标记工具/流体瓶
    static
    {
        CREATIVE_TABS.register("tab", () -> CreativeModeTab.builder()
                .title(Component.literal("MaidMoreWork"))
                .icon(() -> new ItemStack(MineRegistration.MINE_MARKER.get()))
                .displayItems((params, output) ->
                {
                    output.accept(MineRegistration.MINE_BLOCK.get());
                    output.accept(MineRegistration.MINE_MARKER.get());
                    output.accept(MineRegistration.FLUID_BOTTLE.get());
                })
                .build());
    }

    // 模组构造函数：注册所有 DeferredRegister 到 mod 事件总线
    // 注册游戏事件监听（女仆加入世界、命令注册）
    public MaidMoreWork(IEventBus modBus)
    {
        ModMemories.MEMORY_MODULE_TYPES.register(modBus);
        ModAttachments.ATTACHMENT_TYPES.register(modBus);
        MineRegistration.BLOCKS.register(modBus);
        MineRegistration.BLOCK_ENTITIES.register(modBus);
        MineRegistration.ITEMS.register(modBus);
        FluidBottleItem.DATA_COMPONENTS.register(modBus);
        MineRegistration.MENUS.register(modBus);
        CREATIVE_TABS.register(modBus);

        // 网络包注册
        modBus.addListener((RegisterPayloadHandlersEvent event) ->
        {
            PayloadRegistrar registrar = event.registrar("1");
            registrar.playToClient(
                    ProjectHudPayload.TYPE,
                    ProjectHudPayload.STREAM_CODEC,
                    (payload, context) -> ProjectHudRenderer.sync(payload)
            );
        });

        NeoForge.EVENT_BUS.addListener(MineCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener((MaidTickEvent e) -> MaidBubbleHelper.onMaidTick(e.getMaid()));
        NeoForge.EVENT_BUS.addListener((MaidTickEvent e) ->
        {
            if (e.getMaid().level() instanceof ServerLevel serverLevel)
            {
                ProjectManager.tick(serverLevel);
            }
        });
        // 砍树时阻断拾取，避免女仆一直跑去捡树叶掉落的树苗
        NeoForge.EVENT_BUS.addListener((MaidPickupEvent.ItemResultPre e) ->
        {
            if (e.getMaid().getBrain().getMemory(ModMemories.PROJECT_UUID.get()).isPresent())
            {
                e.setCanPickup(false);
            }
            else
            {
                // 诊断拾取打断：PROJECT_UUID 不存在但仍处于砍树任务中时，说明 H1 导致记忆被错误清除
                boolean inChopTask = ModMemories.WORK_ACTION_CHOPPING.equals(
                        e.getMaid().getBrain().getMemory(ModMemories.WORK_ACTION.get()).orElse(""));
                if (inChopTask)
                {
                    LOGGER.info("MaidMoreWork: PICKUP_DIAG maid={} PROJECT_UUID=absent WORK_ACTION=chopping -> pickup NOT blocked",
                            e.getMaid().getId());
                }
            }
        });
    }

}
