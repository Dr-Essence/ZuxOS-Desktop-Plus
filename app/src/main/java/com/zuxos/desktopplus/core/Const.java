package com.zuxos.desktopplus.core;

/** Names and preference keys shared between the settings app and the hooked process. */
public final class Const {

    public static final String MODULE_PKG = "com.zuxos.desktopplus";
    public static final String PREFS = "zux_desktop_plus";
    public static final String TAG = "ZuxDesktopPlus";

    /** Directory created inside the hooked launcher's private data dir. */
    public static final String DATA_DIR = "zux_desktop_plus";

    // --- General ---------------------------------------------------------
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_DEBUG = "debug_log";
    public static final String KEY_PROBE = "probe_dump";
    public static final String KEY_EXTRA_TARGETS = "extra_targets";

    /** Which desktop mode the surface attaches to: see {@link #DISPLAY_EXTERNAL} etc. */
    public static final String KEY_DISPLAY_MODE = "display_mode";
    public static final int DISPLAY_EXTERNAL = 0;
    public static final int DISPLAY_INTERNAL = 1;
    public static final int DISPLAY_BOTH = 2;

    /** How much of the stock home screen is replaced: see {@link #TAKEOVER_NONE} etc. */
    public static final String KEY_TAKEOVER = "takeover";
    public static final int TAKEOVER_NONE = 0;
    public static final int TAKEOVER_GRID = 1;
    public static final int TAKEOVER_ALL = 2;

    /**
     * Sent by the settings app to the launcher when any setting changes: it takes it at once, or
     * restarts itself for one it reads only as it starts.
     */
    public static final String ACTION_SETTINGS_CHANGED = "com.zuxos.desktopplus.SETTINGS_CHANGED";

    /** How our UI paints itself on the monitor: {@code Theme.GLASS_ID} or {@code RETRO_ID}. */
    public static final String KEY_THEME = "theme";

    /** Which edge of the monitor its taskbar sits on: see {@link #EDGE_BOTTOM} etc. */
    public static final String KEY_TASKBAR_EDGE = "taskbar_edge";
    public static final int EDGE_BOTTOM = 0;
    public static final int EDGE_TOP = 1;

    /** ZUI's status bar on the monitor's desktop not built: the tray and its panels stand in. */
    public static final String KEY_HIDE_MONITOR_STATUS_BAR = "hide_monitor_status_bar";

    /** Attach to every activity of the target package, not just detected home activities. */
    public static final String KEY_ATTACH_ANY = "attach_any_activity";

    // --- Desktop surface -------------------------------------------------
    public static final String KEY_CELL_SIZE = "cell_size_dp";
    public static final String KEY_ICON_SIZE = "icon_size_dp";
    public static final String KEY_SHOW_LABELS = "show_labels";
    public static final String KEY_WIDGETS_ENABLED = "widgets_enabled";
    public static final String KEY_FOLDERS_ENABLED = "folders_enabled";
    public static final String KEY_DRAWER_BUTTON = "drawer_button";
    public static final String KEY_CATCH_PINS = "catch_pinned_shortcuts";
    public static final String KEY_PAGES = "pages_enabled";
    public static final String KEY_PAGE_DOTS = "page_dots";
    public static final String KEY_GLASS = "glass_style";
    public static final String KEY_ANIMATIONS = "animations";
    public static final String KEY_LABEL_SHADOW = "label_shadow";

    // --- Drawer ----------------------------------------------------------
    public static final String KEY_DRAWER_SORT = "drawer_sort";
    /** Apply the module's folders and order to the launcher's own taskbar drawer too. */
    public static final String KEY_NATIVE_DRAWER = "native_drawer";
    public static final int SORT_ALPHA = 0;
    public static final int SORT_CUSTOM = 1;
    public static final int SORT_RECENT = 2;

    // --- Taskbar ---------------------------------------------------------
    /** Network, battery and a clock inside the launcher's own taskbar. */
    public static final String KEY_TASKBAR_TRAY = "taskbar_tray";
    /** CPU, GPU and battery temperatures alongside them. */
    public static final String KEY_TASKBAR_TEMPS = "taskbar_temps";
    /** Hold or right-click the taskbar for a menu. */
    public static final String KEY_TASKBAR_MENU = "taskbar_menu";
    /** Dark tray text, for a light taskbar. Superseded by {@link #KEY_TASKBAR_TEXT_MODE}. */
    public static final String KEY_TASKBAR_DARK_TEXT = "taskbar_dark_text";
    /** Black, white, or decided from the background. See {@code core/Tone.java}. */
    public static final String KEY_TASKBAR_TEXT_MODE = "taskbar_text_mode";
    /** Replace the taskbar's own opaque bar with a translucent, blurred one. */
    public static final String KEY_TASKBAR_GLASS = "taskbar_glass";
    /** Show only apps that are open in the stock taskbar, instead of its predictions. */
    public static final String KEY_TASKBAR_RUNNING_ONLY = "taskbar_running_only";
    /** Hold a taskbar icon for open, close, app info and the app's own shortcuts. */
    public static final String KEY_TASKBAR_APP_MENU = "taskbar_app_menu";
    /** An app's own shortcut icons in ZUI's shape, as ZUI shapes its app icons. */
    public static final String KEY_SHORTCUT_SHAPES = "shortcut_shapes";
    /** An app dragged out of ZUI's taskbar drawer and let go on the taskbar is pinned there. */
    public static final String KEY_BAR_DROP = "bar_drop";
    /** Select in a home-screen app's menu: ZUI's edit mode, that app selected and shaking. */
    public static final String KEY_HOME_SELECT = "home_select";
    /** The tablet bar's navigation keys flush with its right edge while nothing shows there. */
    public static final String KEY_NAV_KEYS_EDGE = "nav_keys_edge";
    /** The start button's robot falls asleep after 30 seconds with nothing pressed. */
    public static final String KEY_ROBOT_SLEEP = "robot_sleep";
    /** The start button's robot breathes: gently awake, slower and deeper asleep. */
    public static final String KEY_ROBOT_BREATH = "robot_breath";
    /** A folder of ours in ZUX Home's drawer drags onto the home screen as ZUI's own folder. */
    public static final String KEY_DRAWER_FOLDER_HOME = "drawer_folder_home";
    /** Dragging in ZUX Home's drawer rearranges it, folders made and filled, as ZUI's home. */
    public static final String KEY_DRAWER_REARRANGE = "drawer_rearrange";
    /** An app off the taskbar - its drawer, or a pin on the tablet - lands on the home screen. */
    public static final String KEY_HOME_DROP = "home_drop";
    /**
     * The home grid chosen in the module's old setting, before the sizes moved into ZUI's own
     * grid choice ({@code HomeGrids}): read once, to hand it over.
     */
    public static final String KEY_HOME_GRID = "home_grid";
    /** The taskbar's drawer rearranges under a drag, as ZUX Home's does. */
    public static final String KEY_TASKBAR_DRAWER_REARRANGE = "taskbar_drawer_rearrange";
    /** One of our folders in a drawer opens under a dragged app resting on it, as on ZUI's home. */
    public static final String KEY_DRAWER_FOLDER_SPRING = "drawer_folder_spring";
    /** Several apps of a drawer chosen to move together, from Select in an app's menu. */
    public static final String KEY_DRAWER_SELECT = "drawer_select";
    /** Icons fit their cells on fine home grids, and widgets keep their size. Start-only. */
    public static final String KEY_GRID_FIT = "grid_fit";
    /** The band ZUX Home keeps for its dock above the tablet's bar is home screen. Start-only. */
    public static final String KEY_HOME_BAND = "home_band";
    /** The home screen also takes the band at its top and the page dots' band. Start-only. */
    public static final String KEY_HOME_EDGES = "home_edges";
    /** Remove folder in the menu of a folder on ZUI's home screen. */
    public static final String KEY_HOME_FOLDER_REMOVE = "home_folder_remove";
    /** ZUI's sidebar skips its wait for ZUI's AI service on each drag when that is not there. */
    public static final String KEY_SIDEBAR_AI_WAIT = "sidebar_ai_wait";
    /** A tap on a drawer app counts wherever it lands on the icon or its name. */
    public static final String KEY_DRAWER_TAPS = "drawer_taps";
    /** A game controller's Home button opens and closes the app drawer. */
    public static final String KEY_GAMEPAD_DRAWER = "gamepad_drawer";
    /** A game controller's Triangle opens the focused icon's menu; menus follow the controller. */
    public static final String KEY_GAMEPAD_MENUS = "gamepad_menus";
    /** A game controller's D-pad goes down onto the tablet's taskbar and moves along it. */
    public static final String KEY_GAMEPAD_BAR = "gamepad_bar";
    /** A game controller's L1 and R1 move along the tablet's taskbar, but in full-screen apps. */
    public static final String KEY_GAMEPAD_SHOULDERS = "gamepad_shoulders";
    /** A game controller's right stick scrolls the drawers. */
    public static final String KEY_GAMEPAD_STICK = "gamepad_stick";
    /** A game controller types on HeliBoard, the keyboard, once it is ticked in LSPosed. */
    public static final String KEY_GAMEPAD_KEYBOARD = "gamepad_keyboard";
    /** An app or folder dragged from ZUI's home screen onto the taskbar moves there. */
    public static final String KEY_HOME_TO_BAR = "home_to_bar";
    /** ZUI's loader waits a second at most for an idle home screen before loading the app list. */
    public static final String KEY_LOADER_NO_WAIT = "loader_no_wait";
    /** Translucent glass behind the launcher's own app drawer. */
    public static final String KEY_DRAWER_GLASS = "native_drawer_glass";
    /** Hold an app in the stock drawer to drag it onto the desktop or the taskbar. */
    public static final String KEY_DRAWER_DRAG = "native_drawer_drag";
    /** A mark under every taskbar icon whose app is open. */
    public static final String KEY_RUNNING_MARKS = "taskbar_running_marks";
    /** Open an app on the screen its icon was tapped on, where the launcher names no display. */
    public static final String KEY_LAUNCH_DISPLAY = "launch_on_tapped_display";
    /** Move the launcher's drawer button to the left of the bar, beside the navigation keys. */
    public static final String KEY_START_LEFT = "start_button_left";
    public static final String KEY_START_ROBOT = "start_button_robot";
    /** Keep ZUI's overview down during the moment an app is opening. */
    public static final String KEY_HIDE_RECENTS_FLASH = "hide_recents_flash";
    public static final String KEY_NAV_OWN_SCREEN = "nav_keys_own_screen";
    /** Recents on the screen whose button was pressed, full screen and in front. */
    public static final String KEY_RECENTS_ROUTE = "recents_route";
    /** Apps open on the monitor are never killed by the system, only by the user. */
    public static final String KEY_KEEP_ALIVE = "keep_alive";
    /** The monitor's touchscreen acts on the monitor, tied to it at every plug. */
    public static final String KEY_MONITOR_TOUCH = "monitor_touch";
    /** A window's title bar and "…" menu follow the window to the screen it moved to. */
    public static final String KEY_CAPTION_FOLLOW = "caption_follow";
    /** The tablet taskbar drawer's app list ends at the bar instead of under its icons. */
    public static final String KEY_DRAWER_ABOVE_BAR = "drawer_above_bar";
    /** Show notifications in the quick panel, through the module's own listener. */
    public static final String KEY_NOTIFICATIONS = "notifications";
    /** A new notification pops up on the monitor, above the tray. */
    public static final String KEY_NOTIFY_POPUPS = "notify_popups";
    /** How long a pop-up stays, in seconds. */
    public static final String KEY_POPUP_SECONDS = "popup_seconds";
    /** A pop-up card's width, in dp. */
    public static final String KEY_POPUP_WIDTH = "popup_width_dp";
    /** Which corner the pop-ups come in at: see {@link #SIDE_RIGHT}. */
    public static final String KEY_POPUP_SIDE = "popup_side";
    public static final int SIDE_RIGHT = 0;
    public static final int SIDE_LEFT = 1;
    /** Apps whose notifications never pop up, as package names joined by commas. */
    public static final String KEY_POPUP_MUTED = "popup_muted";
    /** Hold or pencil in the quick panel to reorder, add and remove its tiles. */
    public static final String KEY_TILE_EDIT = "quick_tile_edit";
    /** Each monitor keeps a desktop of its own, told apart by the display's unique id. */
    public static final String KEY_MONITOR_LAYOUTS = "monitor_layouts";
    /** The start animation on the monitor as its desktop comes up. */
    public static final String KEY_START_ANIMATION = "start_animation";
    /** The launcher tells the settings app which of its features found what they need. */
    public static final String KEY_HEALTH = "feature_health";
    /** Allow shell commands as root for the things a launcher may not do itself. */
    public static final String KEY_USE_ROOT = "use_root";

    // --- Stock-launcher unlocking hooks ----------------------------------
    public static final String KEY_UNLOCK_STOCK = "unlock_stock";
    public static final String KEY_UNLOCK_AGGRESSIVE = "unlock_aggressive";

    /** Files inside {@link #DATA_DIR}. */
    public static final String FILE_DESKTOP = "desktop.json";
    public static final String FILE_DRAWER = "drawer.json";
    /** Apps pinned to the taskbar by this module - never the launcher's own hotseat. */
    public static final String FILE_TASKBAR_PINS = "taskbar.json";
    public static final String FILE_PROBE = "probe.txt";

    /** AppWidgetHost id owned by this module. Deliberately far away from launcher host ids. */
    public static final int WIDGET_HOST_ID = 0x5A78;

    public static final int REQ_BIND_WIDGET = 0x5A70;
    public static final int REQ_CONFIGURE_WIDGET = 0x5A71;

    private Const() {
    }
}
