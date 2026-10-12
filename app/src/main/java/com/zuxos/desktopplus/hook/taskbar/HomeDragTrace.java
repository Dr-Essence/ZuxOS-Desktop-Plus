package com.zuxos.desktopplus.hook.taskbar;

import android.app.Activity;
import android.content.ClipDescription;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.RectF;
import android.view.DragEvent;
import android.view.View;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Read-only: what ZUX Home's home screen does with drags, before drags between it and the bar
 * are built on it. Changes nothing.
 * <ul>
 *   <li>A drag of ZUI's own starting on its home screen or in its drawer
 *       ({@code Workspace.onDragStart}): where from, the home's state, and whether our bar is
 *       showing to be dropped on.</li>
 *   <li>A system drag - ZUI's taskbar drawer's - reaching the home screen's window: whether it
 *       is told of it at all, and whether it is let go there.</li>
 * </ul>
 */
final class HomeDragTrace {

    private HomeDragTrace() {
    }

    static void install(ClassLoader loader) {
        int hooked = 0;
        try {
            Class<?> workspace = Reflect.findClass("com.android.launcher3.Workspace", loader);
            if (workspace != null) {
                hooked += XposedBridge.hookAllMethods(workspace, "onDragStart",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                dragStarted((View) param.thisObject, param.args);
                            }
                        }).size();
            }
        } catch (Throwable t) {
            L.d("home drag trace: ZUI's drag start not traced (" + t + ")");
        }
        try {
            hooked += XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.view.ViewRootImpl", null),
                    "handleDragEvent", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            systemDrag(param.thisObject, param.args);
                        }
                    }).size();
        } catch (Throwable t) {
            L.d("home drag trace: system drags not traced (" + t + ")");
        }
        L.i("home drag trace: drags on ZUX Home's home screen traced x" + hooked);
    }

    private static void dragStarted(View workspace, Object[] args) {
        try {
            Object drag = args.length > 0 ? args[0] : null;
            Object source = drag == null ? null : Reflect.field(drag, "dragSource");
            Object info = drag == null ? null : Reflect.field(drag, "dragInfo");
            RectF bar = TaskbarDrop.barOnScreen(TaskbarTray.displayIdOf(workspace));
            L.i("home drag trace: ZUI's drag of " + name(info) + " from " + name(source)
                    + " on " + homeState(workspace.getContext()) + "; our bar "
                    + (bar == null ? "not showing" : "at " + bar.toShortString()));
        } catch (Throwable t) {
            L.d("home drag trace: a drag start not read (" + t + ")");
        }
    }

    /** One of the drag's events reaching a window of the home screen's activity. */
    private static void systemDrag(Object root, Object[] args) {
        try {
            if (args.length != 1 || !(args[0] instanceof DragEvent)) {
                return;
            }
            DragEvent event = (DragEvent) args[0];
            int action = event.getAction();
            if (action != DragEvent.ACTION_DRAG_STARTED && action != DragEvent.ACTION_DROP) {
                return;
            }
            Object view = Reflect.field(root, "mView");
            Activity home = view instanceof View ? activityOf(((View) view).getContext()) : null;
            if (home == null || !home.getClass().getName().contains("Launcher")) {
                return;
            }
            ClipDescription what = event.getClipDescription();
            L.i("home drag trace: " + home.getClass().getSimpleName() + "'s window "
                    + (action == DragEvent.ACTION_DROP ? "got a drop at "
                    + Math.round(event.getX()) + "," + Math.round(event.getY())
                    : "told of a drag of " + (what == null || what.getMimeTypeCount() == 0
                    ? "nothing" : what.getMimeType(0)))
                    + " on " + homeState(home));
        } catch (Throwable t) {
            L.d("home drag trace: a system drag not read (" + t + ")");
        }
    }

    private static String homeState(Context ctx) {
        Activity home = activityOf(ctx);
        Object states = home == null ? null : Reflect.call(home, "getStateManager");
        Object state = states == null ? null : Reflect.call(states, "getState");
        return (home == null ? "no home" : home.getClass().getSimpleName()) + " in "
                + (state == null ? "an unknown state" : state.toString())
                + (home != null && home.hasWindowFocus() ? ", focused" : "");
    }

    private static Activity activityOf(Context ctx) {
        for (Context c = ctx; c != null; c = c instanceof ContextWrapper
                ? ((ContextWrapper) c).getBaseContext() : null) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
        }
        return null;
    }

    private static String name(Object o) {
        return o == null ? "nothing" : o.getClass().getSimpleName();
    }
}
