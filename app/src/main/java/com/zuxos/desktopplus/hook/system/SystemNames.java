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
    static final String WINDOW_ORGANIZER_CONTROLLER =
            "com.android.server.wm.WindowOrganizerController";
    /** Also {@code WindowOrganizer}'s. */
    static final String APPLY_TRANSACTION = "applyTransaction";
    static final String APPLY_SYNC_TRANSACTION = "applySyncTransaction";
    static final String START_TRANSITION = "startTransition";
    static final String START_LEGACY_TRANSITION = "startLegacyTransition";
    static final String WINDOW_STATE = "com.android.server.wm.WindowState";
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
    /** {@code DisplayContent}'s. */
    static final String M_INPUT_METHOD_WINDOW = "mInputMethodWindow";

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
    /** {@code InputEvent}'s. */
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
    static final String WINDOW_CONTAINER_TOKEN = "android.window.WindowContainerToken";
    static final String WINDOW_CONTAINER_TRANSACTION = "android.window.WindowContainerTransaction";
    static final String REORDER = "reorder";
    static final String GET_CHANGES = "getChanges";
    static final String GET_HIERARCHY_OPS = "getHierarchyOps";
    /** {@code WindowContainerTransaction.Change}'s. */
    static final String GET_WINDOWING_MODE = "getWindowingMode";
    static final String GET_ACTIVITY_WINDOWING_MODE = "getActivityWindowingMode";
    static final String GET_WINDOW_SET_MASK = "getWindowSetMask";
    static final String GET_CONFIG_SET_MASK = "getConfigSetMask";
    static final String GET_CHANGE_MASK = "getChangeMask";
    static final String GET_CONFIGURATION = "getConfiguration";
    /** {@code Configuration}'s, and its {@code WindowConfiguration.getBounds}. */
    static final String WINDOW_CONFIGURATION = "windowConfiguration";
    static final String GET_BOUNDS = "getBounds";
    /** {@code UserHandle}'s. */
    static final String CURRENT = "CURRENT";
    static final String SYSTEM_PROPERTIES = "android.os.SystemProperties";
    static final String GET = "get";
    static final String SET = "set";

    private SystemNames() {
    }
}
