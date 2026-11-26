package com.zuxos.desktopplus.hook.system;

/**
 * Every name of Android's and ZUI's that the system side reaches, in one place, by the class it
 * belongs to: when the firmware changes, these are the ones to check against it. Each constant is
 * its name in capitals; one used by two classes is listed under the first.
 */
final class SystemNames {

    static final String ZUX_HOME = "com.zui.launcher";

    // Window manager (com.android.server.wm).
    static final String ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord";
    /** Also {@code Task}'s. */
    static final String INTENT = "intent";
    static final String ACTIVITY_STARTER = "com.android.server.wm.ActivityStarter";
    static final String M_INTENT = "mIntent";
    static final String M_LAUNCH_MODE = "mLaunchMode";
    static final String M_LAUNCH_FLAGS = "mLaunchFlags";
    static final String SET_INITIAL_STATE = "setInitialState";
    static final String START_ACTIVITY_INNER = "startActivityInner";
    /** Also {@code LaunchParams}'s. */
    static final String M_PREFERRED_TASK_DISPLAY_AREA = "mPreferredTaskDisplayArea";
    static final String M_ROOT_WINDOW_CONTAINER = "mRootWindowContainer";
    static final String M_TARGET_TASK = "mTargetTask";
    /** {@code RootWindowContainer}'s, and the home it starts on a screen. */
    static final String ROOT_WINDOW_CONTAINER = "com.android.server.wm.RootWindowContainer";
    static final String GET_TOP_FOCUSED_DISPLAY_CONTENT = "getTopFocusedDisplayContent";
    static final String START_HOME_ON_TASK_DISPLAY_AREA = "startHomeOnTaskDisplayArea";
    static final String RESOLVE_HOME_ACTIVITY = "resolveHomeActivity";
    /** {@code ActivityRecord}'s. */
    static final String SHORT_COMPONENT_NAME = "shortComponentName";
    static final String LAUNCHED_FROM_PACKAGE = "launchedFromPackage";
    static final String PACKAGE_NAME = "packageName";
    static final String IS_ACTIVITY_TYPE_HOME = "isActivityTypeHome";
    static final String M_ACTIVITY_COMPONENT = "mActivityComponent";
    static final String GET_TASK = "getTask";
    /** {@code Task}'s. */
    static final String M_TASK_ID = "mTaskId";
    static final String REAL_ACTIVITY = "realActivity";
    static final String M_LAST_NON_FULLSCREEN_BOUNDS = "mLastNonFullscreenBounds";
    /** ZUI's memory of each app's last window, handed back at its next launch. */
    static final String LAUNCH_PARAMS_PERSISTER = "com.android.server.wm.LaunchParamsPersister";
    static final String SAVE_TASK = "saveTask";
    static final String GET_LAUNCH_PARAMS = "getLaunchParams";
    static final String GET_WINDOWING_MODE = "getWindowingMode";
    /** ZUI's "Association Launch App Prompt", asked before one app opens another. */
    static final String ZUI_WM_AUTO_RUN_MANAGER = "com.android.server.wm.ZuiWmAutoRunManager";
    static final String IS_ALLOW_RELATIVE_START = "isAllowRelativeStart";
    /** Its answer from ZUI's Security Center, per pair of apps: 0 ask, 1 allowed, 2 denied. */
    static final String ZUI_SECURITY_MANAGER = "android.app.ZuiSecurityManager";
    static final String GET_RELATIVE_APP_STATUS = "getRelativeAppStatus";
    /** ZUI's "two high-performance apps" prompt, held before a start. */
    static final String OV_GAME_TIP_DIALOG_MANAGER =
            "com.android.server.wm.OvGameTipDialogManager";
    static final String CAN_SHOW_OV_GAME_DIALOG = "canShowOvGameDialog";
    /** ZUI's monitor desktop, its "extended" mode. */
    static final String OV_DESKTOP_CONTROLLER = "com.android.server.wm.OVDesktopController";
    static final String IS_UNDER_EXTERNAL_DISPLAY_DESKTOP_MODE =
            "isUnderExternalDisplayDesktopMode";
    static final String GET_EXTERNAL_DISPLAY_DESKTOP_DISPLAY_ID =
            "getExternalDisplayDesktopDisplayId";
    /**
     * ZUI's "focus" while its monitor desktop is on - the top focused screen - which its package
     * manager reads to answer a query for a home with the monitor's launcher; the second is its
     * local service's door to the first.
     */
    static final String GET_CURRENT_FOCUS_DISLAY_ID_WHEN_UNDER_EXTEND_DISPLAY_INTERNAL =
            "getCurrentFocusDislayIdWhenUnderExtendDisplayInternal";
    static final String OV_DESKTOP_CONTROLLER_INTERNAL =
            "com.android.server.wm.OVDesktopController$OVDesktopControllerInternal";
    static final String GET_CURRENT_FOCUS_DISLAY_ID_WHEN_UNDER_EXTEND_DISPLAY =
            "getCurrentFocusDislayIdWhenUnderExtendDisplay";
    /**
     * Android's own default home - the home role holder's home activity among those there are -
     * and how the window manager prepares a home's activity for its user.
     */
    static final String LOCAL_SERVICES = "com.android.server.LocalServices";
    static final String PACKAGE_MANAGER_INTERNAL = "android.content.pm.PackageManagerInternal";
    static final String GET_HOME_ACTIVITIES_AS_USER = "getHomeActivitiesAsUser";
    static final String ACTIVITY_TASK_MANAGER_SERVICE =
            "com.android.server.wm.ActivityTaskManagerService";
    static final String GET_APP_INFO_FOR_USER = "getAppInfoForUser";
    /**
     * A launch into a window already open: the window's mode set to the launch's
     * ({@code TaskDisplayArea.getOrCreateRootTask}), and its bounds laid out again
     * ({@code LaunchParamsController.layoutTask}).
     */
    static final String TASK_DISPLAY_AREA = "com.android.server.wm.TaskDisplayArea";
    static final String GET_OR_CREATE_ROOT_TASK = "getOrCreateRootTask";
    static final String LAYOUT_TASK = "layoutTask";
    static final String M_HAS_BEEN_VISIBLE = "mHasBeenVisible";
    static final String IS_ROOT_TASK = "isRootTask";
    /** {@code ActivityOptions}'s, hidden from the SDK. */
    static final String GET_LAUNCH_WINDOWING_MODE = "getLaunchWindowingMode";
    static final String DRAG_STATE = "com.android.server.wm.DragState";
    static final String M_FLAGS = "mFlags";
    static final String M_UID = "mUid";
    static final String M_DATA_DESCRIPTION = "mDataDescription";
    static final String M_SERVICE = "mService";
    static final String IS_VALID_DROP_TARGET = "isValidDropTarget";
    static final String IS_WINDOW_NOTIFIED = "isWindowNotified";
    static final String REPORT_DROP_WINDOW_LOCK_INNER = "reportDropWindowLockInner";
    /** {@code WindowManagerService}'s, reached through the drag's service. */
    static final String M_INPUT_TO_WINDOW_MAP = "mInputToWindowMap";
    static final String LAUNCH_PARAMS_CONTROLLER = "com.android.server.wm.LaunchParamsController";
    /** The steps of a launch's calculation, each a {@code LaunchParamsModifier}. */
    static final String TASK_LAUNCH_PARAMS_MODIFIER =
            "com.android.server.wm.TaskLaunchParamsModifier";
    static final String DESKTOP_MODE_LAUNCH_PARAMS_MODIFIER =
            "com.android.server.wm.DesktopModeLaunchParamsModifier";
    static final String ON_CALCULATE = "onCalculate";
    static final String CALCULATE = "calculate";
    static final String LAUNCH_PARAMS =
            "com.android.server.wm.LaunchParamsController$LaunchParams";
    static final String M_WINDOWING_MODE = "mWindowingMode";
    static final String M_BOUNDS = "mBounds";
    static final String TASK = "com.android.server.wm.Task";
    static final String SET_WINDOWING_MODE = "setWindowingMode";
    static final String WINDOW_STATE = "com.android.server.wm.WindowState";
    static final String M_ATTRS = "mAttrs";
    static final String GET_ACTIVITY_TYPE = "getActivityType";
    static final String GET_INSETS_STATE = "getInsetsState";
    static final String GET_BOUNDS = "getBounds";
    static final String GET_OWNING_UID = "getOwningUid";
    static final String IS_VISIBLE = "isVisible";

    // The window policy (com.android.server.policy, com.android.server.wm.DisplayPolicy).
    static final String PHONE_WINDOW_MANAGER = "com.android.server.policy.PhoneWindowManager";
    static final String INTERCEPT_KEY_BEFORE_DISPATCHING = "interceptKeyBeforeDispatching";
    static final String M_TOP_FOCUSED_DISPLAY_ID = "mTopFocusedDisplayId";
    static final String M_DEFAULT_DISPLAY_POLICY = "mDefaultDisplayPolicy";
    static final String IS_KEYGUARD_SHOWING_AND_NOT_OCCLUDED = "isKeyguardShowingAndNotOccluded";
    static final String TOP_APP_HIDES_SYSTEM_BAR = "topAppHidesSystemBar";
    static final String M_DISPLAY_CONTENT = "mDisplayContent";
    static final String DISPLAY_POLICY = "com.android.server.wm.DisplayPolicy";
    static final String LAYOUT_WINDOW_LW = "layoutWindowLw";
    /** {@code DisplayContent}'s. */
    static final String M_INPUT_METHOD_WINDOW = "mInputMethodWindow";
    /** Who holds the navigation bar - the taskbar - on a display, and so whether it shows. */
    static final String INSETS_POLICY = "com.android.server.wm.InsetsPolicy";
    static final String GET_NAV_CONTROL_TARGET_INNER_OV = "getNavControlTargetInner_ov";
    static final String M_SHOWING_TRANSIENT_CONTROL_TARGET = "mShowingTransientControlTarget";

    // Activity manager (com.android.server.am).
    static final String PROCESS_STATE_RECORD = "com.android.server.am.ProcessStateRecord";
    static final String SET_CUR_ADJ = "setCurAdj";
    static final String M_APP = "mApp";
    static final String PROCESS_LIST = "com.android.server.am.ProcessList";
    static final String SET_OOM_ADJ = "setOomAdj";
    static final String PROCESS_RECORD = "com.android.server.am.ProcessRecord";
    static final String KILL_LOCKED = "killLocked";
    static final String UID = "uid";

    // Input (com.android.server.input).
    static final String INPUT_MANAGER_SERVICE = "com.android.server.input.InputManagerService";
    static final String M_DISPLAY_VIEWPORT_LIST = "mDisplayViewportList";
    static final String ADD_UNIQUE_ID_ASSOCIATION_BY_DESCRIPTOR =
            "addUniqueIdAssociationByDescriptor";
    static final String REMOVE_UNIQUE_ID_ASSOCIATION_BY_DESCRIPTOR =
            "removeUniqueIdAssociationByDescriptor";
    static final String SET_DISPLAY_VIEWPORTS_INTERNAL = "setDisplayViewportsInternal";
    static final String DELIVER_INPUT_DEVICES_CHANGED = "deliverInputDevicesChanged";
    static final String GET_INPUT_DEVICES = "getInputDevices";
    /** The native input system, and its own account of itself - what dumpsys input prints. */
    static final String M_NATIVE = "mNative";
    /**
     * The focus handed to the input system with the mouse's screen
     * ({@code NativeInputManagerService.setFocusedDisplay(focus, pointer)}), and ZUI's native
     * report of the mouse's screen.
     */
    static final String SET_FOCUSED_DISPLAY = "setFocusedDisplay";
    static final String NATIVE_INPUT_MANAGER_SERVICE =
            "com.android.server.input.NativeInputManagerService";
    static final String INPUT_MANAGER_SERVICE_DELEGATE =
            "com.android.server.input.InputManagerServiceDelegate";
    static final String NOTIFY_POINTER_DISPLAY_ID_CHANGE = "notifyPointerDisplayIdChange";
    /**
     * The power manager starting a screen saver, and ZUI's standby mode's gate on it
     * ({@code PowerManagerService.handleSandman}).
     */
    static final String POWER_MANAGER_SERVICE = "com.android.server.power.PowerManagerService";
    static final String HANDLE_SANDMAN = "handleSandman";
    static final String M_DREAM_MANAGER = "mDreamManager";
    static final String IS_DREAMING = "isDreaming";
    static final String M_ZUI_STANDBY_MODE = "mZuiStandbyMode";
    static final String IS_DREAMING_ALLOWED = "isDreamingAllowed";
    static final String ZUI_STANDBY_MODE_MANAGER = "android.app.ZuiStandbyModeManager";
    static final String GET_SWITCH_FOR_STANDBY = "getSwitchForStandby";
    /** The power manager's own "when to start" and power fields, and a power group's state. */
    static final String GET_WAKEFULNESS_LOCKED = "getWakefulnessLocked";
    static final String M_DREAMS_ACTIVATE_ON_SLEEP_SETTING = "mDreamsActivateOnSleepSetting";
    static final String M_DREAMS_ACTIVATE_ON_DOCK_SETTING = "mDreamsActivateOnDockSetting";
    static final String M_DOCK_STATE = "mDockState";
    static final String M_IS_POWERED = "mIsPowered";
    static final String DUMP = "dump";
    /**
     * A screen saver's copy on the other screen: where its activity is started
     * ({@code ActivityTaskManagerService.startDreamActivityInternal}), the dream manager's report
     * that it stopped, the activity and its surface, the other screen's overlay layer, Android's
     * surface mirror, and the power manager's input from each screen.
     */
    static final String START_DREAM_ACTIVITY_INTERNAL = "startDreamActivityInternal";
    /** The options that start hands on, and their launch activity type (hidden). */
    static final String CREATE_SAFE_ACTIVITY_OPTIONS_WITH_BAL_ALLOWED =
            "createSafeActivityOptionsWithBalAllowed";
    static final String GET_LAUNCH_ACTIVITY_TYPE = "getLaunchActivityType";
    static final String DREAM_MANAGER_SERVICE = "com.android.server.dreams.DreamManagerService";
    static final String REPORT_DREAMING_STOPPED = "reportDreamingStopped";
    static final String M_GLOBAL_LOCK = "mGlobalLock";
    static final String GET_ACTIVITY = "getActivity";
    static final String FINISHING = "finishing";
    static final String GET_SURFACE_CONTROL = "getSurfaceControl";
    static final String GET_OVERLAY_LAYER = "getOverlayLayer";
    static final String GET_DISPLAY_INFO = "getDisplayInfo";
    static final String LOGICAL_WIDTH = "logicalWidth";
    static final String LOGICAL_HEIGHT = "logicalHeight";
    static final String MIRROR_SURFACE = "mirrorSurface";
    static final String USER_ACTIVITY_FROM_NATIVE = "userActivityFromNative";
    static final String STOP_DREAM = "stopDream";
    static final String M_HANDLER = "mHandler";
    /** The input manager as a service: in the system, the input manager itself. */
    static final String SERVICE_MANAGER = "android.os.ServiceManager";
    static final String INPUT_SERVICE = "input";
    /** The window manager's answer to which screen the mouse's pointer belongs on. */
    static final String INPUT_MANAGER_CALLBACK = "com.android.server.wm.InputManagerCallback";
    static final String GET_POINTER_DISPLAY_ID = "getPointerDisplayId";
    /**
     * The input dispatcher's reports to the window manager of a window that stops taking input,
     * takes it again, or a key with no focused window; {@code InputApplicationHandle.name}.
     */
    static final String NOTIFY_WINDOW_UNRESPONSIVE = "notifyWindowUnresponsive";
    static final String NOTIFY_WINDOW_RESPONSIVE = "notifyWindowResponsive";
    static final String NOTIFY_NO_FOCUSED_WINDOW_ANR = "notifyNoFocusedWindowAnr";
    static final String NAME = "name";
    /** What that rule reads: the window manager's flag ZUI sets, and each screen's power state. */
    static final String M_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS =
            "mForceDesktopModeOnExternalDisplays";
    static final String M_ROOT = "mRoot";
    static final String GET_DISPLAY_CONTENT = "getDisplayContent";
    static final String DISPLAY_CONTENT = "com.android.server.wm.DisplayContent";
    static final String M_DISPLAY_INFO = "mDisplayInfo";
    static final String DISPLAY_INFO = "android.view.DisplayInfo";
    static final String STATE = "state";
    /**
     * The mouse at the first lock after a boot: whether the lock screen is up, ZUI's setting for
     * the monitor as its desktop, and the screens the window manager has.
     */
    static final String M_POLICY = "mPolicy";
    static final String IS_KEYGUARD_LOCKED = "isKeyguardLocked";
    static final String ZUI_DP_DISPLAY_PC_MODE = "zui_dp_display_pc_mode";
    /** ZUI turns that setting off at boot: the first lock is told by the user not unlocked. */
    static final String USER_MANAGER_INTERNAL = "com.android.server.pm.UserManagerInternal";
    static final String IS_USER_UNLOCKED = "isUserUnlocked";
    static final String M_CHILDREN = "mChildren";
    static final String DISPLAY_ID = "displayId";
    /**
     * ZUI's monitor desktop ({@link #OV_DESKTOP_CONTROLLER}) turned on and off: where it sets the
     * window manager's flag its rule for the mouse's screen reads.
     */
    static final String ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE = "enterExternalDisplayDesktopMode";
    static final String EXIT_EXTERNAL_DISPLAY_DESKTOP_MODE = "exitExternalDisplayDesktopMode";
    /**
     * ZUI's own step for an external screen added ({@code RootWindowContainer.onDisplayAdded})
     * and removed, the screen it calls its mirror, its work mode, and the controller's handler,
     * window manager and context.
     */
    static final String PREPARE_ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE_INTERNAL =
            "prepareEnterExternalDisplayDesktopModeInternal";
    static final String ON_DISPLAY_REMOVED_FOR_ZUI_DESKTOP = "onDisplayRemovedForZuiDesktop";
    static final String GET_MIRROR_DISPLAY_DESKTOP_DISPLAY_ID = "getMirrorDisplayDesktopDisplayId";
    static final String IS_UNDER_PC_DESKTOP_MODE = "isUnderPcDesktopMode";
    static final String M_WINDOW_MANAGER = "mWindowManager";
    static final String M_CONTEXT = "mContext";
    /**
     * The window manager's cached rule for where each screen's keyboard shows, all the keyboard
     * service asks; ZUI's start refreshes it as it sets the window manager's flag.
     */
    static final String M_DISPLAY_IME_POLICY_CACHE = "mDisplayImePolicyCache";
    /** The display manager's thread, where it hands the input system the screens. */
    static final String DISPLAY_THREAD = "com.android.server.DisplayThread";
    static final String GET_HANDLER = "getHandler";
    /** {@code InputDevice}'s. */
    static final String GET_BLUETOOTH_ADDRESS = "getBluetoothAddress";
    /** {@code DisplayViewport}'s. */
    static final String UNIQUE_ID = "uniqueId";
    static final String TYPE = "type";
    static final String VALID = "valid";
    /** {@code InputEvent}'s, and every window container's (an activity, a display area). */
    static final String GET_DISPLAY_ID = "getDisplayId";

    // The app side of the framework (android.app, android.window, android.os).
    static final String ACTIVITY_THREAD = "android.app.ActivityThread";
    static final String CURRENT_ACTIVITY_THREAD = "currentActivityThread";
    static final String GET_SYSTEM_CONTEXT = "getSystemContext";
    static final String ACTIVITY_TASK_MANAGER = "android.app.ActivityTaskManager";
    static final String GET_SERVICE = "getService";
    static final String REMOVE_TASK = "removeTask";
    /** {@code TaskInfo}'s. */
    static final String TOKEN = "token";
    static final String EFFECTIVE_UID = "effectiveUid";
    static final String WINDOW_ORGANIZER = "android.window.WindowOrganizer";
    static final String APPLY_TRANSACTION = "applyTransaction";
    static final String WINDOW_CONTAINER_TOKEN = "android.window.WindowContainerToken";
    static final String WINDOW_CONTAINER_TRANSACTION = "android.window.WindowContainerTransaction";
    static final String REORDER = "reorder";
    /** {@code UserHandle}'s. */
    static final String CURRENT = "CURRENT";
    static final String SYSTEM_PROPERTIES = "android.os.SystemProperties";
    static final String GET = "get";
    static final String SET = "set";

    // The view side of the framework (android.view): a window's layout.
    /** {@code WindowManager.LayoutParams}'s. */
    static final String M_FIT_INSETS_TYPES = "mFitInsetsTypes";
    static final String INSETS_STATE = "android.view.InsetsState";
    static final String CALCULATE_INSETS = "calculateInsets";

    private SystemNames() {
    }
}
