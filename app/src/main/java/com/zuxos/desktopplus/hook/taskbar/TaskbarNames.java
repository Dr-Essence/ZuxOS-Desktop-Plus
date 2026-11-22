package com.zuxos.desktopplus.hook.taskbar;

/**
 * Names of ZUX Home's taskbar code, and of the framework's hidden API, that this package reaches,
 * in one place: when ZUX Home or ZuxOS updates, these are the ones to check against its
 * decompiled code. Read from ZUX Home
 * 18.2.0.0375, whose taskbar keeps these names through its obfuscation. Each constant is its
 * name in capitals. The package's older files still hold their own; each moves its names here
 * when it is next worked on.
 */
final class TaskbarNames {

    // The bar's window and its controllers.
    static final String TASKBAR_ACTIVITY_CONTEXT =
            "com.android.launcher3.taskbar.TaskbarActivityContext";
    static final String M_CONTROLLERS = "mControllers";
    static final String IS_TASKBAR_WINDOW_FULLSCREEN = "isTaskbarWindowFullscreen";
    static final String GET_DRAG_LAYER = "getDragLayer";

    // The bar's drawer: every way it opens (ZUI's button, ours, the Windows key, a controller's
    // Home) ends at its controller's toggle or toggleSearch.
    static final String TASKBAR_ALL_APPS_CONTROLLER_CLASS =
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsController";
    static final String TASKBAR_ALL_APPS_CONTROLLER = "taskbarAllAppsController";
    static final String TOGGLE = "toggle";
    static final String TOGGLE_SEARCH = "toggleSearch";
    static final String IS_OPEN = "isOpen";

    // A tap on an app of the bar's drawer or its folders: ZUI's launch, and its launch from an
    // app (startItemInfoActivity, obfuscated).
    static final String LAUNCH_FROM_TASKBAR = "launchFromTaskbar";
    static final String START_ITEM_INFO_ACTIVITY = "w1";
    static final String IS_IN_DESKTOP_MODE = "isInDesktopMode";

    // ZUI's game mode: the bar reports a 1 px inset, so apps take the screen under it.
    static final String ENTER_GAME_MODE = "enterGameMode";
    static final String EXIT_GAME_MODE = "exitGameMode";
    static final String GET_GAME_MODE = "getGameMode";

    // The insets controller: what the bar keeps clear for apps, and where it takes touches.
    static final String TASKBAR_INSETS_CONTROLLER_CLASS =
            "com.android.launcher3.taskbar.TaskbarInsetsController";
    static final String TASKBAR_INSETS_CONTROLLER = "taskbarInsetsController";
    static final String ON_TASKBAR_OR_BUBBLEBAR_WINDOW_HEIGHT_OR_INSETS_CHANGED =
            "onTaskbarOrBubblebarWindowHeightOrInsetsChanged";
    static final String UPDATE_INSETS_TOUCHABILITY = "updateInsetsTouchability";
    static final String GET_CONTEXT = "getContext";
    /** Sizes one of the bar's inset providers, with its overrides for keyboards (obfuscated). */
    static final String SIZE_PROVIDER = "k";

    // The framework's inset providers (hidden API): what the bar keeps clear, per window type.
    static final String INSETS_FRAME_PROVIDER = "android.view.InsetsFrameProvider";
    static final String GET_TYPE = "getType";
    static final String GET_INSETS_SIZE = "getInsetsSize";
    static final String GET_INSETS_SIZE_OVERRIDES = "getInsetsSizeOverrides";
    static final String SET_INSETS_SIZE_OVERRIDES = "setInsetsSizeOverrides";
    static final String GET_WINDOW_TYPE = "getWindowType";

    // The framework's task service (hidden API): the screen the system has focused.
    static final String ACTIVITY_TASK_MANAGER = "android.app.ActivityTaskManager";
    static final String GET_SERVICE = "getService";
    static final String GET_FOCUSED_ROOT_TASK_INFO = "getFocusedRootTaskInfo";

    private TaskbarNames() {
    }
}
