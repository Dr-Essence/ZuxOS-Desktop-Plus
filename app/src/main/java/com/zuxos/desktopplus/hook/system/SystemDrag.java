package com.zuxos.desktopplus.hook.system;

import android.content.ClipDescription;
import android.os.IBinder;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.desktop.DragPayload;
import com.zuxos.desktopplus.logic.DragCodec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: a drag of ours is shown to the launcher's own windows and to nobody else.
 *
 * <p>Our drags go between the launcher's windows - the desktop, the drawer, the taskbar, folders
 * - so they have to be global. A global drag is also announced to every other app's windows, and
 * ZUI's sidebar answers each announcement by waiting on its AI service with no time limit: its
 * main thread stops, its gesture monitor stops taking touches, and the tablet's Home and Recents
 * stop responding until the "isn't responding" dialog. The drags carry Android's "same
 * application" flag, which Android 15 and later honour on their own where the platform flag is
 * on; this makes the system honour it everywhere: a window of another app is never a target.
 */
public final class SystemDrag {

    private static boolean sInstalled;
    private static Field sFlags;
    private static Field sUid;
    private static Method sOwningUid;
    private static int sErrors;
    /** Android's {@code ClipDescription.MIMETYPE_APPLICATION_ACTIVITY}, not in the public SDK. */
    private static final String ACTIVITY = "application/vnd.android.activity";
    /** For the drop trace: the drag's clip, the window map, and who was told. */
    private static Field sDescription;
    private static Field sService;
    private static Field sInputToWindow;
    private static Method sNotified;
    private static int sDropErrors;

    private SystemDrag() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> state = Class.forName(SystemNames.DRAG_STATE, false, loader);
            sFlags = state.getDeclaredField(SystemNames.M_FLAGS);
            sFlags.setAccessible(true);
            sUid = state.getDeclaredField(SystemNames.M_UID);
            sUid.setAccessible(true);
            Class<?> window = Class.forName(SystemNames.WINDOW_STATE, false, loader);
            sOwningUid = window.getDeclaredMethod(SystemNames.GET_OWNING_UID);
            sOwningUid.setAccessible(true);
            int hooked = 0;
            for (Method m : state.getDeclaredMethods()) {
                if (m.getName().equals(SystemNames.IS_VALID_DROP_TARGET)
                        && m.getParameterCount() > 0 && m.getParameterTypes()[0] == window
                        && m.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(m, TARGET);
                    hooked++;
                }
            }
            hooked += hookDropTrace(state, window);
            L.i("system drag: watching x" + hooked);
        } catch (Throwable t) {
            L.i("system drag: not available on this build (" + t + ")");
        }
    }

    /**
     * Read only: for every drop of an app's drag or one of ours, the window Android hands it to
     * and whether that window was told of the drag. A window that was not is handed nothing and
     * the drag ends untaken ({@code DragState.reportDropWindowLockInner}: "Drop outside a valid
     * window") - which the launcher's own log cannot see.
     */
    private static int hookDropTrace(Class<?> state, Class<?> window) {
        try {
            sDescription = state.getDeclaredField(SystemNames.M_DATA_DESCRIPTION);
            sDescription.setAccessible(true);
            sService = state.getDeclaredField(SystemNames.M_SERVICE);
            sService.setAccessible(true);
            sInputToWindow = sService.getType().getDeclaredField(SystemNames.M_INPUT_TO_WINDOW_MAP);
            sInputToWindow.setAccessible(true);
            sNotified = state.getDeclaredMethod(SystemNames.IS_WINDOW_NOTIFIED, window);
            sNotified.setAccessible(true);
            Method inner = state.getDeclaredMethod(SystemNames.REPORT_DROP_WINDOW_LOCK_INNER,
                    IBinder.class, float.class, float.class);
            XposedBridge.hookMethod(inner, DROP);
            return 1;
        } catch (Throwable t) {
            L.i("system drag: drops not traced on this build (" + t + ")");
            return 0;
        }
    }

    private static final XC_MethodHook DROP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sDropErrors > 20) {
                return;
            }
            try {
                Object description = sDescription.get(param.thisObject);
                if (!(description instanceof ClipDescription)) {
                    return;
                }
                ClipDescription clip = (ClipDescription) description;
                boolean ours = clip.hasMimeType(DragCodec.MIME);
                if (!ours && !clip.hasMimeType(ACTIVITY)) {
                    return;
                }
                // Read under the window manager's lock, which the caller holds.
                Object windows = sInputToWindow.get(sService.get(param.thisObject));
                Object target = windows instanceof Map ? ((Map<?, ?>) windows).get(param.args[0])
                        : null;
                boolean told = target != null
                        && Boolean.TRUE.equals(sNotified.invoke(param.thisObject, target));
                // The point is the window's own (the unhandled drop adds the window's corner).
                L.i("system drag: " + (ours ? "our" : "an app's") + " drag (uid "
                        + sUid.getInt(param.thisObject) + ") let go over " + target + " at "
                        + Math.round((Float) param.args[1]) + ","
                        + Math.round((Float) param.args[2]) + " of it"
                        + (target == null ? "" : told ? ", told of the drag"
                        : ", NOT told of the drag: Android hands it nothing"));
            } catch (Throwable t) {
                sDropErrors++;
            }
        }
    };

    private static final XC_MethodHook TARGET = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(param.getResult()) || sErrors > 20) {
                return;
            }
            try {
                int flags = sFlags.getInt(param.thisObject);
                if ((flags & DragPayload.SAME_APPLICATION) == 0) {
                    return;
                }
                int uid = (int) sOwningUid.invoke(param.args[0]);
                if (uid != sUid.getInt(param.thisObject)) {
                    param.setResult(Boolean.FALSE);
                }
            } catch (Throwable t) {
                sErrors++;
            }
        }
    };
}
