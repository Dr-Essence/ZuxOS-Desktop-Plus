package com.zuxos.desktopplus.hook.systemui;

import android.view.SurfaceControl;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A window's title bar made again on the screen its window moved to.
 *
 * <p>SystemUI makes a window's title bar on the screen the window is on, and keeps it when the
 * window moves to another screen: {@code DesktopModeWindowDecorViewModel} only relays it out,
 * from the moved window's details, and only for changes it is told of. A window that reached the
 * monitor without SystemUI being told kept a title bar that still said "full screen on the
 * tablet", and its "…" menu opened on the tablet (22:17: SystemUI's own log had Claude full
 * screen while the system had it floating on the monitor).
 *
 * <p>So when SystemUI is told of a window whose title bar is from another screen, the title bar
 * is closed and made again with SystemUI's own calls - {@code destroyWindowDecoration}, then
 * {@code createWindowDecoration} - as it does for a window it sees opening. Only then; every
 * other change goes through untouched. Everything in try/catch: SystemUI's own handling stands
 * whenever this cannot.
 */
public final class CaptionFollow {

    private static final String MODEL =
            "com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel";

    private static Method sDestroy;
    private static Method sCreate;
    private static Method sShows;
    private static boolean sSaid;

    private CaptionFollow() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> model = Reflect.findClass(MODEL, loader);
            if (model == null) {
                L.w("caption follow: SystemUI's window title bars not found, left as they are");
                return;
            }
            for (Method m : model.getDeclaredMethods()) {
                if (m.getName().equals("destroyWindowDecoration") && m.getParameterCount() == 1) {
                    sDestroy = m;
                } else if (m.getName().startsWith("createWindowDecoration")
                        && m.getParameterCount() == 4) {
                    sCreate = m;
                } else if (m.getName().startsWith("shouldShowWindowDecor")
                        && m.getParameterCount() == 1) {
                    sShows = m;
                }
            }
            if (sDestroy == null || sCreate == null) {
                L.w("caption follow: SystemUI's title bar calls not found, left as they are");
                return;
            }
            sDestroy.setAccessible(true);
            sCreate.setAccessible(true);
            if (sShows != null) {
                sShows.setAccessible(true);
            }
            // Told of a change in a transition: the old title bar goes, and SystemUI makes the
            // new one itself, with the transition's own surfaces, finding none.
            int hooked = XposedBridge.hookAllMethods(model, "onTaskChanging",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0) {
                                dropIfMoved(param.thisObject, param.args[0]);
                            }
                        }
                    }).size();
            // Told of a change outside a transition: SystemUI only relays out a title bar it
            // has, so the new one is made here, as SystemUI makes one for a window opening.
            hooked += XposedBridge.hookAllMethods(model, "onTaskInfoChanged",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 1
                                    && dropIfMoved(param.thisObject, param.args[0])) {
                                remake(param.thisObject, param.args[0]);
                            }
                        }
                    }).size();
            L.i("caption follow: window title bars follow their window to another screen x"
                    + hooked);
        } catch (Throwable t) {
            L.w("caption follow: not installed (" + t + ")");
        }
    }

    /**
     * Closes the title bar of a window that has moved to another screen; true when it did.
     * Nothing for a window still on the screen its title bar was made on.
     */
    private static boolean dropIfMoved(Object model, Object task) {
        try {
            if (!Cfg.enabled() || !Cfg.captionFollow()) {
                return false;
            }
            Object decorations = Reflect.field(model, "mWindowDecorByTaskId");
            Object taskId = Reflect.field(task, "taskId");
            if (!(decorations instanceof android.util.SparseArray) || !(taskId instanceof Integer)) {
                return false;
            }
            Object decoration = ((android.util.SparseArray<?>) decorations).get((Integer) taskId);
            Object had = decoration == null ? null : Reflect.field(decoration, "mTaskInfo");
            Object was = had == null ? null : Reflect.field(had, "displayId");
            Object now = Reflect.field(task, "displayId");
            if (!(was instanceof Integer) || !(now instanceof Integer) || was.equals(now)) {
                return false;
            }
            if (sShows != null && !Boolean.TRUE.equals(sShows.invoke(model, task))) {
                return false;
            }
            // Closed as it was: SystemUI's input bookkeeping is by the screen it was on.
            sDestroy.invoke(model, had);
            if (!sSaid) {
                sSaid = true;
                L.i("caption follow: window " + taskId + " moved from display " + was + " to "
                        + now + " - its title bar made again there");
            }
            return true;
        } catch (Throwable t) {
            L.d("caption follow: title bar left as it was (" + t + ")");
            return false;
        }
    }

    /** A new title bar for the window, made by SystemUI as for a window that opens. */
    private static void remake(Object model, Object task) {
        try {
            Object organizer = Reflect.field(model, "mTaskOrganizer");
            Object taskId = Reflect.field(task, "taskId");
            Object leash = organizer == null || !(taskId instanceof Integer) ? null
                    : organizer.getClass().getMethod("getRunningTaskLeash", int.class)
                    .invoke(organizer, taskId);
            if (!(leash instanceof SurfaceControl)) {
                return;
            }
            SurfaceControl.Transaction start = new SurfaceControl.Transaction();
            SurfaceControl.Transaction finish = new SurfaceControl.Transaction();
            sCreate.invoke(model, task, leash, start, finish);
            start.apply();
            finish.apply();
        } catch (Throwable t) {
            L.d("caption follow: title bar not made again (" + t + ")");
        }
    }
}
