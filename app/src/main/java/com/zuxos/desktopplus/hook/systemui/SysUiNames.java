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
    static final String M_LIMIT_TOUCH_REGION_TO_SYSTEM_AREAS = "mLimitTouchRegionToSystemAreas";
    /** {@code TaskInfo}'s: the window in front with the focus. */
    static final String IS_FOCUSED = "isFocused";
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
    /** {@code WindowConfiguration}'s: whether the window is kept above all others. */
    static final String IS_ALWAYS_ON_TOP = "isAlwaysOnTop";
    /** {@code TaskInfo}'s: the task's own window container, what a transaction acts on. */
    static final String TOKEN = "token";
    /** The shell's own change of windows, and how it is applied. */
    static final String WINDOW_CONTAINER_TRANSACTION = "android.window.WindowContainerTransaction";
    static final String SET_ALWAYS_ON_TOP = "setAlwaysOnTop";
    static final String APPLY_TRANSACTION = "applyTransaction";

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
    /** ZUI's desktop-mode title bar colours, for a window focused or not. */
    static final String ZUI_DESKTOP_MODE_CAPTION_BAR_NORMAL_FOCUS =
            "zui_desktop_mode_caption_bar_normal_focus";
    static final String ZUI_DESKTOP_MODE_CAPTION_BAR_DARK_FOCUS =
            "zui_desktop_mode_caption_bar_dark_focus";
    static final String ZUI_DESKTOP_MODE_CAPTION_BAR_NORMAL_NO_FOCUS =
            "zui_desktop_mode_caption_bar_normal_no_focus";
    static final String ZUI_DESKTOP_MODE_CAPTION_BAR_DARK_NO_FOCUS =
            "zui_desktop_mode_caption_bar_dark_no_focus";
    /** An app's own top, from its {@code TaskDescription}: hidden from the SDK. */
    static final String GET_SYSTEM_BARS_APPEARANCE = "getSystemBarsAppearance";
    static final String GET_STATUS_BAR_COLOR = "getStatusBarColor";
    /**
     * A window's own pixels under its bar: the decoration's task surface and its own surfaces,
     * left out of the capture, and the framework's layer capture.
     */
    static final String M_TASK_SURFACE = "mTaskSurface";
    static final String M_DECORATION_CONTAINER_SURFACE = "mDecorationContainerSurface";
    static final String M_VIEW_HOST = "mViewHost";
    static final String GET_SURFACE_CONTROL = "getSurfaceControl";
    static final String SCREEN_CAPTURE = "android.window.ScreenCapture";
    static final String CAPTURE_LAYERS_EXCLUDING = "captureLayersExcluding";
    static final String AS_BITMAP = "asBitmap";
    static final String GET_HARDWARE_BUFFER = "getHardwareBuffer";

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

    // The lock screen on other screens: ZUI's choice of it for each screen, and Motorola's Smart
    // Connect one, with a password entry, which ZUI keeps for Motorola's desktops.
    static final String KEYGUARD_DISPLAY_MANAGER = "com.android.keyguard.KeyguardDisplayManager";
    static final String SHOW_PRESENTATION = "showPresentation";
    static final String M_PRESENTATIONS = "mPresentations";
    /** Whether the lock screen is up, and ZUI's own pass over every screen with it. */
    static final String M_SHOWING = "mShowing";
    static final String UPDATE_DISPLAYS = "updateDisplays";
    static final String MOTO_KEYGUARD_PRESENTATION =
            "com.android.keyguard.smartconnect.MotoKeyguardPresentation";
    /** ZUI's monitor desktop, in {@code Settings.System}: 1 while it is on. */
    static final String DP_DISPLAY_PC_MODE = "zui_dp_display_pc_mode";
    /**
     * The lock screen in the tablet's look: Motorola's clock and root, ZUI's own lock screen for
     * other screens made by its factory, and that one's clock from ZUI's clock registry.
     */
    static final String ON_CREATE = "onCreate";
    static final String ON_ATTACHED_TO_WINDOW = "onAttachedToWindow";
    static final String ON_DETACHED_FROM_WINDOW = "onDetachedFromWindow";
    static final String M_KEYGUARD_CLOCK_CONTAINER = "mKeyguardClockContainer";
    static final String M_ROOT_VIEW = "mRootView";
    static final String M_CONNECTED_DISPLAY_FACTORY =
            "mConnectedDisplayKeyguardPresentationFactory";
    static final String CREATE = "create";
    static final String CLOCK_REGISTRY = "clockRegistry";
    static final String CREATE_CURRENT_CLOCK = "createCurrentClock";
    static final String ROOT_VIEW = "rootView";
    static final String SET_CLOCK = "setClock";
    /** ZUI's mirror check: whether the window manager shows decorations on a screen. */
    static final String WINDOW_MANAGER_PROXY = "android.view.IWindowManager$Stub$Proxy";
    static final String SHOULD_SHOW_SYSTEM_DECORS = "shouldShowSystemDecors";
    /** {@code Display}'s, hidden from the SDK: internal, external, virtual. */
    static final String GET_TYPE = "getType";
    static final String SYSTEMUI_FEATURES = "com.android.systemui.feature.SystemUIFeatures";
    static final String GET_INSTANCE = "getInstance";
    static final String IS_ENABLED = "isEnabled";
    static final String SMART_CONNECT_KEYGUARD = "feature_zui_smart_connect_keyguard";
    static final String MOTO_DESKTOP_MANAGER = "com.motorola.internal.app.MotoDesktopManager";
    static final String IS_DESKTOP_SUPPORTED = "isDesktopSupported";
    static final String IS_DESKTOP_OR_MOBILE_UI_MODE = "isDesktopOrMobileUiMode";
    static final String RDP_UNLOCK_CONTROLLER =
            "com.android.keyguard.smartconnect.RDPUnlockController";

    private SysUiNames() {
    }
}
