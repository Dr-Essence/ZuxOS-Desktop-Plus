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

    /**
     * A system drag's start, entry and drop reaching one of the launcher's windows - its home
     * screen, the bar, the taskbar's drawer - named by the window's title: which of them is told
     * of the drag, and which one is under the finger when it is let go.
     */
    private static void systemDrag(Object root, Object[] args) {
        try {
            if (args.length != 1 || !(args[0] instanceof DragEvent)) {
                return;
            }
            DragEvent event = (DragEvent) args[0];
            int action = event.getAction();
            if (action != DragEvent.ACTION_DRAG_STARTED && action != DragEvent.ACTION_DRAG_ENTERED
                    && action != DragEvent.ACTION_DROP) {
                return;
            }
            Object attrs = Reflect.field(root, "mWindowAttributes");
            CharSequence title = attrs instanceof android.view.WindowManager.LayoutParams
                    ? ((android.view.WindowManager.LayoutParams) attrs).getTitle() : null;
            ClipDescription what = event.getClipDescription();
            String where = Math.round(event.getX()) + "," + Math.round(event.getY());
            L.i("home drag trace: window \"" + title + "\" "
                    + (action == DragEvent.ACTION_DRAG_STARTED ? "told of a drag of "
                    + (what == null || what.getMimeTypeCount() == 0 ? "nothing" : what.getMimeType(0))
                    : action == DragEvent.ACTION_DRAG_ENTERED ? "entered at " + where
                    : "got a drop at " + where)
                    + (action == DragEvent.ACTION_DRAG_STARTED ? "" : ", " + homeState(windowContext(root))));
        } catch (Throwable t) {
            L.d("home drag trace: a system drag not read (" + t + ")");
        }
    }

    /** The context of a window's root: an activity's window gives its activity. */
    private static Context windowContext(Object root) {
        Object view = Reflect.field(root, "mView");
        Object window = view == null ? null : Reflect.field(view, "mWindow");
        Object context = window == null ? null : Reflect.call(window, "getContext");
        if (context instanceof Context) {
            return (Context) context;
        }
        return view instanceof View ? ((View) view).getContext() : null;
    }

    private static String homeState(Context ctx) {
        Activity home = ctx == null ? null : activityOf(ctx);
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
