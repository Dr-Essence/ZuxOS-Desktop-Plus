package com.zuxos.desktopplus.hook.taskbar;

/**
 * Names of ZUX Home's taskbar code that this package reaches, in one place: when ZUX Home
 * updates, these are the ones to check against its decompiled code. Read from ZUX Home
 * 18.2.0.0375, whose taskbar keeps these names through its obfuscation. Each constant is its
 * name in capitals. The package's older files still hold their own; each moves its names here
 * when it is next worked on.
 */
final class TaskbarNames {

    // The bar's window and its controllers.
    static final String M_CONTROLLERS = "mControllers";
    static final String IS_TASKBAR_WINDOW_FULLSCREEN = "isTaskbarWindowFullscreen";
    static final String GET_DRAG_LAYER = "getDragLayer";

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

    private TaskbarNames() {
    }
}
