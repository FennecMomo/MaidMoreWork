package com.fennecmomo.maidmorework;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

// 女仆气泡显示通用 API（静态工具类）
//
// 所有气泡显示逻辑统一通过本类调起，业务代码不直接操作 ChatBubbleManager
//
// 目前提供两个 API：
//   showBubble     — 通用气泡：传入纯文本，调起气泡显示
//   showFollowWarn — 跟随模式警告：无需参数，自动读取关键词并显示"跟随模式下无法XX"
public final class MaidBubbleHelper
{
    // 跟随模式警告气泡冷却 key
    private static final long FOLLOW_WARN_KEY = 9527L;

    private MaidBubbleHelper()
    {
    }

    // ===================== 通用气泡 =====================

    // 显示一条纯文本气泡，由 ChatBubbleManager 管理冷却
    // text: 要显示的气泡文本内容（如 "家园范围内没有可用的原木"）
    public static void showBubble(EntityMaid maid, String text)
    {
        maid.getChatBubbleManager().addTextChatBubbleIfTimeout(text, FOLLOW_WARN_KEY);
    }

    // ===================== 跟随模式警告 =====================

    // 跟随模式专用警告气泡
    // 自动从 WORK_ACTION Memory 读取关键词（默认"工作"），拼接为"跟随模式下无法XX，请开启Home模式"
    // 无需调用方传入任何文本参数
    public static void showFollowWarn(EntityMaid maid)
    {
        String action = maid.getBrain().getMemory(ModMemories.WORK_ACTION.get()).orElse("工作");
        showBubble(maid, "跟随模式下无法" + action + "，请开启Home模式");
    }
}
