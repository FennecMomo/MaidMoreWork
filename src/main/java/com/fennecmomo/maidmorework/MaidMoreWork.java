package com.fennecmomo.maidmorework;

import com.fennecmomo.maidmorework.item.FluidBottleItem;
import com.fennecmomo.maidmorework.mining.MineCommand;
import com.fennecmomo.maidmorework.mining.MineRegistration;
import com.fennecmomo.maidmorework.lib.projecttype.ProjectTypeRegistry;
import com.fennecmomo.maidmorework.project.ProjectClientHelper;
import com.fennecmomo.maidmorework.project.center.ProjectCenterCommand;
import com.fennecmomo.maidmorework.project.center.ProjectCenterActivationHud;
import com.fennecmomo.maidmorework.project.center.ProjectCenterBlockEntity;
import com.fennecmomo.maidmorework.project.center.ProjectCenterEditHud;
import com.fennecmomo.maidmorework.project.center.ProjectCenterEditPayload;
import com.fennecmomo.maidmorework.project.center.ProjectCenterInfoPayload;
import com.fennecmomo.maidmorework.project.center.ProjectCenterRegistration;
import com.fennecmomo.maidmorework.project.center.ProjectCenterScanPayload;
import com.fennecmomo.maidmorework.project.hud.ProjectHudPayload;
import com.fennecmomo.maidmorework.project.hud.ProjectHudQueryPayload;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidAndItemTransformEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredRegister;
// maidmorework 模组主类（@Mod 入口）
// 负责：
// 1. 注册所有 DeferredRegister（Memory、Attachment、MineBlock、菜单、数据组件、创造模式标签）
// 2. 注册命令和事件监听（MaidTickEvent 驱动 ProjectServerHelper + MaidBubbleHelper）
@Mod(MaidMoreWork.MODID)
public class MaidMoreWork
{
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
                    output.accept(ProjectCenterRegistration.PROJECT_CENTER_BLOCK.get());
                    output.accept(ProjectCenterRegistration.PROJECT_CENTER_MARKER.get());
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
        ProjectCenterRegistration.BLOCKS.register(modBus);
        ProjectCenterRegistration.BLOCK_ENTITIES.register(modBus);
        ProjectCenterRegistration.ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);

        ProjectTypeRegistry.discover();

        // 网络包注册
        modBus.addListener((RegisterPayloadHandlersEvent event) ->
        {
            PayloadRegistrar registrar = event.registrar("1");
            registrar.playToClient(
                    ProjectHudPayload.TYPE,
                    ProjectHudPayload.STREAM_CODEC,
                    (payload, context) -> ProjectClientHelper.sync(payload)
            );
            registrar.playToClient(
                    ProjectCenterEditPayload.TYPE,
                    ProjectCenterEditPayload.STREAM_CODEC,
                    (payload, context) ->
                    {
                        if (payload.projectTypeId().isEmpty() && ProjectTypeRegistry.size() > 0)
                        {
                            ProjectCenterActivationHud.open(payload);
                        }
                        else
                        {
                            ProjectCenterEditHud.open(payload);
                        }
                    }
            );
            registrar.playToClient(
                    ProjectCenterScanPayload.TYPE,
                    ProjectCenterScanPayload.STREAM_CODEC,
                    (payload, context) -> {
                        ProjectClientHelper.scanCenterPos = payload.centerPos();
                        ProjectClientHelper.scanCursor = payload.cursor();
                        ProjectClientHelper.scanTotal = payload.total();
                    }
            );
            registrar.playToClient(
                    ProjectCenterInfoPayload.TYPE,
                    ProjectCenterInfoPayload.STREAM_CODEC,
                    (payload, context) -> {
                        ProjectClientHelper.infoCenterPos = payload.centerPos();
                        ProjectClientHelper.infoTypeName = payload.typeName();
                        ProjectClientHelper.infoProjectCount = payload.projectCount();
                        ProjectClientHelper.infoMaidCount = payload.maidCount();
                    }
            );
            registrar.playToServer(
                    ProjectHudQueryPayload.TYPE,
                    ProjectHudQueryPayload.STREAM_CODEC,
                    (payload, context) -> {
                        ServerPlayer player = (ServerPlayer) context.player();
                        ProjectHudPayload response = ProjectCenterBlockEntity.getNearby(player, 32);
                        PacketDistributor.sendToPlayer(player, response);
                    }
            );
        });

        NeoForge.EVENT_BUS.addListener(MineCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ProjectCenterCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener((MaidTickEvent e) -> MaidBubbleHelper.onMaidTick(e.getMaid()));
        // 女仆被拾取/收起时自动退出当前工程，避免参与者残留
        NeoForge.EVENT_BUS.addListener((MaidAndItemTransformEvent.ToItem e) -> {
                ProjectCenterBlockEntity.releaseMaid(e.getMaid());
        });
    }

}
