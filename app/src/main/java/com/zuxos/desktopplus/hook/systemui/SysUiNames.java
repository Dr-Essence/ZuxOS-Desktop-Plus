package com.zuxos.desktopplus.hook.systemui;

/**
 * Every name of ZUI's SystemUI that this package reaches, in one place: when SystemUI updates,
 * these are the ones to check against its decompiled code. Read from ZUI's System UI 16, whose
 * window shell keeps its source names. Each constant is its name in capitals; Android's own
 * framework names stay where they are used.
 */
final class SysUiNames {

    // The window shell's title bars and the model that makes them.
    static final String WINDOW_DECORATION = "com.android.wm.shell.windowdecor.WindowDecoration";
    static final String DESKTOP_MODE_WINDOW_DECORATION =
            "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration";
    static final String CAPTION_WINDOW_DECORATION =
            "com.android.wm.shell.windowdecor.CaptionWindowDecoration";
    static final String DESKTOP_MODE_WINDOW_DECOR_VIEW_MODEL =
            "com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel";
    static final String RELAYOUT = "relayout";
    static final String M_IS_STATUS_BAR_VISIBLE = "mIsStatusBarVisible";
    static final String M_CONTEXT = "mContext";
    static final String M_TASK_INFO = "mTaskInfo";
    static final String M_HAS_GLOBAL_FOCUS = "mHasGlobalFocus";
    static final String M_EXCLUSION_REGION = "mExclusionRegion";
    static final String DESTROY_WINDOW_DECORATION = "destroyWindowDecoration";
    static final String CREATE_WINDOW_DECORATION = "createWindowDecoration";
    static final String SHOULD_SHOW_WINDOW_DECOR = "shouldShowWindowDecor";
    static final String ON_TASK_CHANGING = "onTaskChanging";
    static final String ON_TASK_INFO_CHANGED = "onTaskInfoChanged";
    static final String M_WINDOW_DECOR_BY_TASK_ID = "mWindowDecorByTaskId";
    static final String M_TASK_ORGANIZER = "mTaskOrganizer";
    static final String GET_RUNNING_TASK_LEASH = "getRunningTaskLeash";

    // Its loader of a title bar's name and icons, and what it says for a closed one.
    static final String DESKTOP_MODE_WINDOW_DECORATION_LOADER =
            DESKTOP_MODE_WINDOW_DECORATION + "$$ExternalSyntheticLambda0";
    static final String NON_EXISTENT_DECORATION = "non-existent decoration";

    // The window menu ("…"), its sizes, its rows and its resources.
    static final String OV_HANDLE_MENU = "com.android.wm.shell.windowdecor.OvHandleMenu";
    static final String OV_ON_ASSIST_CONTENT_RECEIVED = "ovOnAssistContentReceived";
    static final String CLOSE_HANDLE_MENU = "closeHandleMenu";
    static final String M_OV_HANDLE_MENU = "mOvHandleMenu";
    static final String TASK_INFO = "taskInfo";
    static final String CONTEXT = "context";
    static final String MENU_GROUP2_HEIGHT = "menuGroup2Height";
    static final String MENU_WINDOW_HEIGHT = "menuWindowHeight";
    static final String HANDLE_MENU_VIEW = "handleMenuView";
    static final String HANDLE_MENU_ROOT = "handleMenuRoot";
    static final String OVC_WD_HANDLE_MENU_GROUP2_ITEM_HEIGHT_PAD =
            "ovc_wd_handle_menu_group2_item_height_pad";
    static final String OVC_WD_HANDLE_MENU_GROUP2_EMPTY_BAR_HEIGHT_PAD =
            "ovc_wd_handle_menu_group2_empty_bar_height_pad";
    static final String OVC_WD_HANDLE_MENU_GROUPS_SPACE_PAD = "ovc_wd_handle_menu_groups_space_pad";
    static final String OV_DESKTOP_MODE_WINDOW_DECOR_HANDLE_MENU_PAD_GROUP_TWO =
            "ov_desktop_mode_window_decor_handle_menu_pad_group_two";
    static final String OVC_HANDLE_MENU_GROUP2_LAYOUT = "ovc_handle_menu_group2_layout";
    static final String OVC_HANDLE_MENU_GROUP2_LAYOUT_SELECT_BG =
            "ovc_handle_menu_group2_layout_select_bg";
    static final String OVC_WD_HANDLE_MENU_GROUP2_EMPTY_BAR1 =
            "ovc_wd_handle_menu_group2_empty_bar1";
    static final String OVC_WD_HANDLE_MENU_GROUP2_EMPTY_BAR2 =
            "ovc_wd_handle_menu_group2_empty_bar2";
    static final String OVC_WD_HANDLE_MENU_ITEM_REPLACE_APP =
            "ovc_wd_handle_menu_item_replace_app";
    static final String OVC_WD_HANDLE_MENU_IV_REPLACE_APP = "ovc_wd_handle_menu_iv_replace_app";

    // ZUI's watcher of its title-bar settings, which holds the model.
    static final String OV_CAPTION_BAR_OBSERVER =
            "com.android.wm.shell.windowdecor.OvCaptionBarObserver";
    static final String M_INSTANCE = "mInstance";
    static final String M_DESKTOP_MODE_WINDOW_DECOR_VIEW_MODEL =
            "mDesktopModeWindowDecorViewModel";

    // The relayout's decision for a title bar, and what it made.
    static final String UPDATE_RELAYOUT_PARAMS = "updateRelayoutParams";
    static final String M_IS_CAPTION_VISIBLE = "mIsCaptionVisible";
    static final String M_IS_INSET_SOURCE = "mIsInsetSource";
    static final String M_LAYOUT_RES_ID = "mLayoutResId";
    static final String M_CAPTION_WIDTH_ID = "mCaptionWidthId";
    static final String M_CAPTION_HEIGHT_ID = "mCaptionHeightId";
    /** The parts of a title bar that keep their touches over an app's own header. */
    static final String M_OCCLUDING_CAPTION_ELEMENTS = "mOccludingCaptionElements";
    static final String OCCLUDING_CAPTION_ELEMENT =
            "com.android.wm.shell.windowdecor.WindowDecoration$RelayoutParams"
                    + "$OccludingCaptionElement";
    static final String OCCLUDING_CAPTION_ALIGNMENT = OCCLUDING_CAPTION_ELEMENT + "$Alignment";
    static final String M_RECT = "mRect";
    static final String M_ALIGNMENT = "mAlignment";
    static final String CUSTOMIZE = "CUSTOMIZE";
    static final String OV_DESKTOP_MODE_WINDOW_DECOR_PAD = "ov_desktop_mode_window_decor_pad";
    /** How far ZUI's resize corner reaches into a window. */
    static final String OVF_CORNER_SIZE_INNER = "ovf_cornerSize_Inner";
    /** A task's bounds: {@code TaskInfo.getConfiguration().windowConfiguration.getBounds()}. */
    static final String GET_CONFIGURATION = "getConfiguration";
    static final String WINDOW_CONFIGURATION = "windowConfiguration";
    static final String GET_BOUNDS = "getBounds";
    static final String GET_WINDOWING_MODE = "getWindowingMode";

    // The monitor's title bar (ZUI's pill), and the "…" menu items its buttons are.
    static final String OV_DESKTOP_MODE_WINDOW_DECORATION_VIEW_HOLDER =
            "com.android.wm.shell.windowdecor.viewholder.OvDesktopModeWindowDecorationViewHolder";
    static final String BIND_DATA = "bindData";
    static final String CAPTION_VIEW = "captionView";
    static final String CAPTION_HANDLE = "captionHandle";
    static final String OVC_WD_HANDLE_MENU_ITEM_MINIMIZE = "ovc_wd_handle_menu_item_minimize";
    static final String OVC_WD_HANDLE_MENU_ITEM_FULLSCREEN = "ovc_wd_handle_menu_item_fullscreen";
    static final String OVC_WD_HANDLE_MENU_ITEM_FREEFORM = "ovc_wd_handle_menu_item_freeform";
    static final String OVC_WD_HANDLE_MENU_ITEM_CLOSE = "ovc_wd_handle_menu_item_close";
    /** ZUI's desktop-mode title bar buttons: a shade's prefix, then the button. */
    static final String ZUI_DESKTOPMODE_NORMAL = "zui_desktopmode_normal_";
    static final String ZUI_DESKTOPMODE_DARK = "zui_desktopmode_dark_";
    static final String PICTURE_MINIMIZE = "minimize";
    static final String PICTURE_MAX = "max";
    static final String PICTURE_FREEFORM = "freeform";
    static final String PICTURE_CLOSE = "close";

    // The monitor's status bar.
    static final String ZUI_DP_MODE_MANAGER = "com.android.systemui.dpmode.ZuiDpModeManager";
    static final String CREATE_STATUS_BAR = "createStatusBar";

    // Drag and drop.
    static final String DRAG_AND_DROP_CONTROLLER =
            "com.android.wm.shell.draganddrop.DragAndDropController";
    static final String ON_DRAG = "onDrag";
    static final String M_IS_ZUI_SPLIT = "mIsZuiSplit";
    /** ZUI's {@code DragAndDropConstants.EXTRA_DISALLOW_HIT_REGION}. */
    static final String DISALLOW_HIT_REGION = "DISALLOW_HIT_REGION";

    // The shell's copy of each display's layout.
    static final String DISPLAY_LAYOUT = "com.android.wm.shell.common.DisplayLayout";
    static final String RECALC_INSETS = "recalcInsets";
    static final String M_INSETS_STATE = "mInsetsState";
    static final String M_STABLE_INSETS = "mStableInsets";
    static final String M_CUTOUT = "mCutout";
    static final String M_NAV_BAR_FRAME_HEIGHT = "mNavBarFrameHeight";
    static final String M_TASKBAR_FRAME_HEIGHT = "mTaskbarFrameHeight";

    private SysUiNames() {
    }
}
