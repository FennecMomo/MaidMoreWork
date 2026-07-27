package com.fennecmomo.maidmorework;

// 可配置常量集中管理
// 后期接入玩家配置文件时，从此类读取
public final class MaidMoreWorkConfig
{
    private MaidMoreWorkConfig() {}

    // ===================== SearchBehavior =====================

    public static final int SILENCE_TICKS = 10;
    public static final int BUBBLE_DURATION_TICKS = 40;
    // 螺旋耗尽后随机游荡的距离范围
    public static final double SEARCH_ROAM_MIN_DIST = 20.0;
    public static final double SEARCH_ROAM_MAX_DIST = 35.0;
    // 游荡速度倍率，TLM 的 MaidMoveControl 会乘以 MOVEMENT_SPEED 属性再 *3
    public static final double SEARCH_ROAM_WALK_SPEED = 0.3;
    // 每 tick 处理的螺旋点数
    public static final int SPIRAL_BATCH = 100;

    // ===================== ChopBehavior =====================

    public static final double WALK_REACH_SQ = 4.0;
    public static final double CLOSE_ENOUGH_SQ = 25.0;
    public static final double WALK_SPEED = 0.6;
    public static final int CHOP_INTERVAL = 10;
    public static final double ROAM_MIN_DIST = 10.0;
    public static final double ROAM_MAX_RANGE = 10.0;
    public static final double ROAM_SPEED = 0.3;

    // ===================== LoggingTask =====================

    public static final int SEARCH_BEHAVIOR_PRIORITY = 5;
    public static final int CHOP_BEHAVIOR_PRIORITY = 6;
    public static final int SEARCH_HALF_XZ = 15;
    public static final int SEARCH_Y_DOWN = 1;
    public static final int SEARCH_Y_UP = 14;
    public static final int ORPHAN_SEARCH_RANGE_SQ = 900;

    // ===================== ProjectManager =====================

    public static final int PROJECT_CHECK_INTERVAL = 60;
    public static final int HUD_SYNC_INTERVAL = 10;

    // ===================== ProjectHudRenderer =====================

    public static final float PANEL_Y_OFFSET = 2.0f;
    public static final float PANEL_SCALE = 0.025f;
    public static final double PANEL_TOWARD_PLAYER_OFFSET = 1.0;
    public static final int PANEL_SEE_THROUGH_LIGHT = 0xF000F0;
    // 工程 HUD 显示距离（格），玩家超过此距离不渲染面板
    public static final int HUD_RENDER_DISTANCE = 6;
}
