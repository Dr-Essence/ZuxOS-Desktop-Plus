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
    /** {@code RootWindowContainer}'s. */
    static final String GET_TOP_FOCUSED_DISPLAY_CONTENT = "getTopFocusedDisplayContent";
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
