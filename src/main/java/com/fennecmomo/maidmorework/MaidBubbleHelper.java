package com.fennecmomo.maidmorework;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleDataCollection;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

// 女仆气泡生命周期管理器（每女仆一个实例，静态 Map 管理）
//
// 调用方只管 setBubble / showFollowWarn，不需要关心气泡何时显示或消失
// MaidTickEvent 驱动 tick()，每 tick 自动管理气泡的显示与清除
//
// 核心流程（tick）：
//   ① 倒计时递减 → 到期清除持有文本 + 消除正在显示的气泡
//   ② 有持有文本时检查气泡环境：
//      - 无气泡 → 显示持有文本
//      - 1 个气泡且是我们的 → 已显示，跳过
//      - 1 个气泡且不是我们的 → 别人在显示，等待
//      - 多气泡冲突 → 移除我们的气泡
public final class MaidBubbleHelper
{
    private static final Logger LOGGER = LoggerFactory.getLogger("MaidMoreWork");

    // 全局实例池：UUID → helper（女仆首次请求气泡时自动创建）
    private static final Map<UUID, MaidBubbleHelper> INSTANCES = new HashMap<>();

    // ===================== 静态 API =====================

    // 显示一条纯文本气泡，持续 durationTicks 个 tick
    public static void showBubble(EntityMaid maid, String text, int durationTicks)
    {
        INSTANCES.computeIfAbsent(maid.getUUID(), k -> new MaidBubbleHelper())
                .setBubble(maid, text, durationTicks);
    }

    // 跟随模式专用警告气泡
    // 自动从 WORK_ACTION Memory 读取关键词（默认"工作"），拼接为"跟随模式下无法XX，请开启Home模式"
    public static void showFollowWarn(EntityMaid maid)
    {
        String action = maid.getBrain().getMemory(ModMemories.WORK_ACTION.get()).orElse("工作");
        showBubble(maid, "跟随模式下无法" + action + "，请开启Home模式", MaidMoreWorkConfig.BUBBLE_DURATION_TICKS);
    }

    // 立即清除当前女仆的所有持有气泡
    public static void clearBubble(EntityMaid maid)
    {
        MaidBubbleHelper helper = INSTANCES.get(maid.getUUID());
        if (helper != null)
        {
            helper.forceClear(maid);
        }
        // 兜底：遍历清空 ChatBubbleManager 中所有气泡
        var bubbles = maid.getChatBubbleManager().getChatBubbleDataCollection();
        long[] keys = bubbles.keySet().toLongArray();
        for (long key : keys)
        {
            maid.getChatBubbleManager().removeChatBubble(key);
        }
    }

    // MaidTickEvent 入口：驱动该女仆的气泡生命周期
    // 由 MaidMoreWork 注册到 NeoForge.EVENT_BUS
    public static void onMaidTick(EntityMaid maid)
    {
        MaidBubbleHelper helper = INSTANCES.get(maid.getUUID());
        if (helper != null)
        {
            helper.tick(maid);
        }
    }

    // ===================== 实例状态 =====================

    private String pendingText = null;  // 持有文本：当前需要显示的气泡内容
    private int remainTicks = 0;        // 剩余持续时间（递减到 0 时清除）
    private long bubbleId = -1;         // 我们通过 addTextChatBubble 创建的气泡 ID

    private MaidBubbleHelper()
    {
    }

    // ===================== 气泡设置 =====================

    // 设置持有文本并重置倒计时
    // 如果文本没变且气泡仍在显示，不打断（避免频繁设置导致 bubbleId 在 add 前被清为 -1）
    private void setBubble(EntityMaid maid, String text, int durationTicks)
    {
        if (text.equals(pendingText) && bubbleId >= 0
                && maid.getChatBubbleManager().getChatBubbleDataCollection().containsKey(bubbleId))
        {
            this.remainTicks = durationTicks;
            return;
        }
        if (maid.getChatBubbleManager().getChatBubbleDataCollection().containsKey(bubbleId))
        {
            maid.getChatBubbleManager().removeChatBubble(bubbleId);
        }
        this.pendingText = text;
        this.remainTicks = durationTicks;
        this.bubbleId = -1;
    }

    // ===================== 生命周期 tick =====================

    // 每 tick 执行：持有文本判空 → 倒计时 → 气泡环境调度
    private void tick(EntityMaid maid)
    {
        // 无持有文本 → 无需任何处理
        if (pendingText == null)
        {
            return;
        }

        ChatBubbleDataCollection bubbles = maid.getChatBubbleManager().getChatBubbleDataCollection();

        // 倒计时处理：递减 → 到期清除
        if (tickCountdown(maid, bubbles))
        {
            return;
        }

        // 气泡环境调度
        resolveDisplay(maid, bubbles);
    }

    // ===================== tick 子方法 =====================

    // 倒计时递减：到期时移除气泡并清空持有文本，返回 true 表示已到期
    private boolean tickCountdown(EntityMaid maid, ChatBubbleDataCollection bubbles)
    {
        if (remainTicks <= 0)
        {
            return false;
        }
        remainTicks--;
        if (remainTicks <= 0)
        {
            // 到期：移除正在显示的气泡 + 清空持有文本
            if (bubbles.containsKey(bubbleId))
            {
                maid.getChatBubbleManager().removeChatBubble(bubbleId);
            }
            bubbleId = -1;
            pendingText = null;
            return true;
        }
        return false;
    }

    // 气泡环境调度：无气泡时显示，有气泡时冲突处理
    private void resolveDisplay(EntityMaid maid, ChatBubbleDataCollection bubbles)
    {
        if (bubbles.isEmpty())
        {
            // 无气泡 → 显示持有文本
            bubbleId = maid.getChatBubbleManager().addTextChatBubble(pendingText);
        }
        else if (bubbles.containsKey(bubbleId))
        {
            // 我们的气泡在多个气泡中 → 移除避免叠加
            maid.getChatBubbleManager().removeChatBubble(bubbleId);
            bubbleId = -1;
        }
        // else: 其他气泡在显示，等待对方自然消失
    }

    // 强制清除：立刻移除气泡并清空状态
    private void forceClear(EntityMaid maid)
    {
        LOGGER.info("MaidBubbleHelper: forceClear called, bubbleId={} pendingText='{}' maid={}", bubbleId, pendingText, maid.getId());
        if (bubbleId >= 0)
        {
            var bubbles = maid.getChatBubbleManager().getChatBubbleDataCollection();
            if (bubbles.containsKey(bubbleId))
            {
                maid.getChatBubbleManager().removeChatBubble(bubbleId);
                LOGGER.info("MaidBubbleHelper: force cleared bubble maid={}", maid.getId());
            }
        }
        bubbleId = -1;
        pendingText = null;
        remainTicks = 0;
    }
}
