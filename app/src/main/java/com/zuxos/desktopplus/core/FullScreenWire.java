package com.zuxos.desktopplus.core;

/**
 * What ZUX Home and SystemUI tell each other about the monitor's full-screen windows.
 *
 * <p>ZUX Home keeps which windows are full screen: its taskbar is the one that gets out of their
 * way. SystemUI has the window "…" menu that offers Full screen, and the title bars that hide
 * for those windows. So SystemUI asks ZUX Home to toggle a window, and ZUX Home tells SystemUI
 * every change. Each side registers for the other's messages with {@link #SENDER}, a permission
 * the system grants only to apps signed with the platform's key: both of these hold it, and no
 * app from a store can.
 */
public final class FullScreenWire {

    /** What a sender must hold: ZUX Home and SystemUI both do. */
    public static final String SENDER = "android.permission.STATUS_BAR_SERVICE";

    public static final String ZUX_HOME = "com.zui.launcher";
    public static final String SYSTEMUI = "com.android.systemui";

    /** SystemUI to ZUX Home: full screen on or off for {@link #EXTRA_TASK}. */
    public static final String ACTION_TOGGLE = "com.zuxos.desktopplus.FULL_SCREEN_TOGGLE";
    /** SystemUI to ZUX Home: {@link #EXTRA_TASK} is no longer full screen. */
    public static final String ACTION_LEFT = "com.zuxos.desktopplus.FULL_SCREEN_LEFT";
    /** SystemUI to ZUX Home: send me the state. */
    public static final String ACTION_ASK = "com.zuxos.desktopplus.FULL_SCREEN_ASK";
    /** ZUX Home to SystemUI: the full-screen windows, {@link #EXTRA_TASKS}. */
    public static final String ACTION_STATE = "com.zuxos.desktopplus.FULL_SCREEN_STATE";

    /** The menus' words: the same in ZUI's window menu and in the taskbar's. */
    public static final String ENTER = "Full screen";
    public static final String EXIT = "Exit full screen";

    public static final String EXTRA_TASK = "task";
    public static final String EXTRA_DISPLAY = "display";
    public static final String EXTRA_TASKS = "tasks";

    private FullScreenWire() {
    }
}
