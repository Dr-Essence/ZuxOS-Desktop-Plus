package com.zuxos.desktopplus.hook.taskbar;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.view.DragEvent;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.Tasks;
import com.zuxos.desktopplus.hook.ZuiApps;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;
import java.util.List;

/**
 * An app dragged out of the taskbar's drawer over the home screen lands on the home screen, as
 * one dragged out of ZUX Home's own drawer does.
 *
 * <p>Android hands an app's drag only to the window it started in and to windows that take
 * every drag ({@code DragState.isValidDropTarget}: "application extras"), so ZUX Home's home
 * screen is never told of the taskbar drawer's drag. Its own way in for drags from elsewhere is
 * {@code BaseItemDragListener}: a drag of its own, started at the system drag's point
 * ({@code DragOptions.simulatedDndStartPoint}), fed every {@code DragEvent}
 * ({@code DragController.onDragEvent}). The drawer's window is told of the drag, and it is in the
 * same process on the same screen: it starts ZUX Home's drag there and hands it every event. From
 * then on it is ZUI's drag, from ZUI's drawer - the home screen opening as for its own drawer
 * ({@code SPRING_LOADED}), its reorder, folders, drop animation and database - with the system's
 * shadow hidden so only ZUI's own icon is dragged. SystemUI stands aside for it (the clip says so,
 * {@code ShellDropBar}).
 */
final class ZuiHomeDrop {

    /** The clip's word to SystemUI that the home screen takes this drag. */
    static final String HOME_TAKES = "com.zuxos.desktopplus.HOME_TAKES";

    private static final String OPTIONS = "com.android.launcher3.dragndrop.DragOptions";
    private static final String DRAGGABLE = "com.android.launcher3.dragndrop.DraggableView";
    private static final int FULLSCREEN = 1;

    /** ZUX Home's drag controller carrying the drag under way, or null. Main thread. */
    private static Object sController;
    private static View sFaded;

    private ZuiHomeDrop() {
    }

    /** Whether the home screen is what is showing on this screen, full screen and in front. */
    static boolean homeBehind(Context ctx, int display) {
        if (!Cfg.homeDrop()) {
            return false;
        }
        List<android.app.ActivityManager.RunningTaskInfo> tasks = TaskbarApps.tasksOn(ctx, display);
        if (tasks.isEmpty() || !ctx.getPackageName().equals(Tasks.packageOf(tasks.get(0)))) {
            return false;
        }
        Object config = Reflect.field(tasks.get(0).configuration, "windowConfiguration");
        Object mode = config == null ? null : Reflect.call(config, "getWindowingMode");
        return !(mode instanceof Integer) || (Integer) mode == FULLSCREEN;
    }

    /**
     * ZUX Home's own drag of this app, started at the system drag's start; true when it began. The
     * catcher is the drawer's window's view that is told of the system drag.
     */
    static boolean begin(View catcher, View source, Item item, DragEvent started) {
        try {
            ClassLoader loader = catcher.getContext().getClassLoader();
            Object launcher = ZuiApps.launcher(loader);
            Class<?> draggable = Class.forName(DRAGGABLE, false, loader);
            if (launcher == null || !draggable.isInstance(source)) {
                L.i("zui home drop: no home screen to hand the drag to");
                return false;
            }
            View homeLayer = (View) Reflect.call(launcher, "getDragLayer");
            int[] home = new int[2];
            int[] here = new int[2];
            homeLayer.getLocationOnScreen(home);
            catcher.getLocationOnScreen(here);
            if (home[0] != here[0] || home[1] != here[1]) {
                // The events are passed on as they are: both windows must share their origin.
                L.i("zui home drop: the home screen is not where the drawer is");
                return false;
            }
            Object apps = Reflect.call(launcher, "getAppsView");
            Object app = ZuiApps.appInfo(launcher, new ComponentName(item.pkg, item.cls),
                    IconInfo.userOf(source.getTag()));
            Object icon = app == null ? null : Reflect.call(app, "newIcon", launcher);
            Object controller = Reflect.call(launcher, "getDragController");
            Method start = startDrag(controller);
            if (!(icon instanceof Drawable) || controller == null || start == null) {
                L.i("zui home drop: ZUI's app or drag not found for " + item.pkg);
                return false;
            }
            Object options = Class.forName(OPTIONS, false, loader).getConstructor().newInstance();
            Point at = new Point(Math.round(started.getX()), Math.round(started.getY()));
            options.getClass().getField("simulatedDndStartPoint").set(options, at);
            Drawable drawable = (Drawable) icon;
            int half = Math.max(1, drawable.getIntrinsicWidth()) / 2;
            // From ZUI's drawer: ZUI opens the home screen for it as for its own (SPRING_LOADED).
            start.invoke(controller, drawable, source, at.x - half, at.y - half, apps, app, null,
                    1f, 1f, options);
            sController = controller;
            // Only ZUI's own icon is dragged now: the system's card is hidden, the drawer too.
            catcher.updateDragShadow(new View.DragShadowBuilder() {
                @Override
                public void onProvideShadowMetrics(Point size, Point touch) {
                    size.set(1, 1);
                    touch.set(0, 0);
                }

                @Override
                public void onDrawShadow(Canvas canvas) {
                    // Nothing: ZUI's own drag view is the one seen.
                }
            });
            View drawer = catcher.getRootView();
            drawer.animate().alpha(0f).setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                    .start();
            sFaded = drawer;
            L.i("zui home drop: the drag of " + item.pkg + " handed to the home screen");
            return true;
        } catch (Throwable t) {
            L.w("zui home drop: not handed to the home screen (" + t + ")");
            sController = null;
            return false;
        }
    }

    /** ZUX Home's {@code DragController.startDrag(Drawable, DraggableView, ...)}. */
    private static Method startDrag(Object controller) {
        for (Class<?> c = controller == null ? null : controller.getClass(); c != null;
                c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("startDrag") && m.getParameterCount() == 10
                        && m.getParameterTypes()[0] == Drawable.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** Whether a drag of ours is with the home screen now. */
    static boolean active() {
        return sController != null;
    }

    /** One more event of the system drag, for ZUI's drag: it moves, drops or ends with it. */
    static boolean forward(DragEvent event) {
        Object controller = sController;
        if (controller == null) {
            return false;
        }
        Object handled = Reflect.call(controller, "onDragEvent", event);
        return Boolean.TRUE.equals(handled);
    }

    /** Let go over the bar instead: ZUI's drag gives up, the bar pins the app. */
    static void cancel() {
        Object controller = sController;
        if (controller != null && Boolean.TRUE.equals(Reflect.call(controller, "isDragging"))) {
            Reflect.call(controller, "cancelDrag");
        }
    }

    /** The system drag is over: ZUI's ends with it if it has not, and the drawer comes back. */
    static void end(DragEvent ended) {
        if (sController != null && Boolean.TRUE.equals(Reflect.call(sController, "isDragging"))) {
            forward(ended);
        }
        sController = null;
        View drawer = sFaded;
        sFaded = null;
        if (drawer != null) {
            drawer.animate().cancel();
            drawer.setAlpha(1f);
        }
    }
}
