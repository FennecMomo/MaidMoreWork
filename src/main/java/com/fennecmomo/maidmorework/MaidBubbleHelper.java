package com.fennecmomo.maidmorework;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleDataCollection;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

// 女仆气泡管理器（每女仆一个实例，全局静态 Map 管理）
//
// 文本优先级：textMap 最后插入项 > floorText
// textMap 中 -999 tick = 永久有效，不会被自动清除
// 生命周期完全由外部控制，tick() 仅驱动渲染同步
//
// 核心流程（tick）：
//   ① 遍历 textMap 递减时长（-999 跳过），过期删除
//   ② textMap 最后项 → floorText → 销毁气泡
//   ③ 无气泡 → TextChatBubbleData.create(Integer.MAX_VALUE) 创建
//   ③ 有气泡 → setText + forceUpdateChatBubble 改字
public final class MaidBubbleHelper
{
    private static final Map<UUID, MaidBubbleHelper> INSTANCES = new HashMap<>();

    // ===================== 静态 API =====================

    public static MaidBubbleHelper get(EntityMaid maid)
    {
        return INSTANCES.computeIfAbsent(maid.getUUID(), k -> new MaidBubbleHelper());
    }

    public static void onMaidTick(EntityMaid maid)
    {
        MaidBubbleHelper helper = INSTANCES.get(maid.getUUID());
        if (helper != null)
        {
            helper.tick(maid);
        }
    }

    // ===================== 实例状态 =====================

    private final LinkedHashMap<String, Integer> textMap = new LinkedHashMap<>();
    private String floorText = null;
    private long bubbleId = -1;

    private MaidBubbleHelper()
    {
    }

    // ===================== 核心 API =====================

    public void set(String text, int duration)
    {
        textMap.put(text, duration);
    }

    public void clear(String text)
    {
        textMap.remove(text);
    }

    public void clearAll()
    {
        textMap.clear();
    }

    public void setFloor(String text)
    {
        this.floorText = text;
    }

    public void clearFloor()
    {
        this.floorText = null;
    }

    // ===================== 便利 API =====================

    public void setFollowWarn(EntityMaid maid)
    {
        String action = maid.getBrain().getMemory(ModMemories.WORK_ACTION.get()).orElse("工作");
        set("跟随模式下无法" + action + "，请开启Home模式", MaidMoreWorkConfig.BUBBLE_DURATION_TICKS);
    }

    // ===================== tick =====================

    private void tick(EntityMaid maid)
    {
        if (!(maid.level() instanceof ServerLevel)) return;

        // ① 遍历递减，移除过期
        var iter = textMap.entrySet().iterator();
        while (iter.hasNext())
        {
            Map.Entry<String, Integer> entry = iter.next();
            int duration = entry.getValue();
            if (duration != -999)
            {
                duration--;
                if (duration <= 0)
                {
                    iter.remove();
                    continue;
                }
                entry.setValue(duration);
            }
        }

        // ② 确定展示文本
        String displayText = null;
        if (!textMap.isEmpty())
        {
            String lastKey = null;
            for (String key : textMap.keySet())
            {
                lastKey = key;
            }
            displayText = lastKey;
        }
        else if (floorText != null)
        {
            displayText = floorText;
        }

        if (displayText == null)
        {
            if (bubbleId >= 0)
            {
                var bubbles = maid.getChatBubbleManager().getChatBubbleDataCollection();
                if (bubbles.containsKey(bubbleId))
                {
                    maid.getChatBubbleManager().removeChatBubble(bubbleId);
                }
                bubbleId = -1;
            }
            return;
        }

        // ③ 气泡生命周期维护
        ChatBubbleDataCollection bubbles = maid.getChatBubbleManager().getChatBubbleDataCollection();
        if (bubbleId < 0 || !bubbles.containsKey(bubbleId))
        {
            IChatBubbleData data = TextChatBubbleData.create(
                    600,
                    Component.literal(displayText),
                    IChatBubbleData.TYPE_2,
                    IChatBubbleData.DEFAULT_PRIORITY);
            bubbleId = maid.getChatBubbleManager().addChatBubble(data);
        }
        else
        {
            IChatBubbleData data = bubbles.get(bubbleId);
            if (data instanceof TextChatBubbleData textBubble)
            {
                textBubble.setText(Component.literal(displayText));
                maid.getChatBubbleManager().forceUpdateChatBubble();
            }
        }
    }
}
