package com.zuxos.desktopplus.hook.drawer;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.ZuiFocus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A game controller's right stick scrolls ZUI's drawers: ZUX Home's own and the taskbar's.
 *
 * <p>Android moves the focus with the left stick (its joystick events become arrow keys) and does
 * nothing with the right one ({@code AXIS_RZ}, up and down). None of ZUI's views takes a joystick
 * event, so each reaches the root of the drawer's window, whatever has the focus. There, while
 * the stick leans, the drawer's list scrolls by its own {@code scrollBy} on every frame - ZUI's
 * fast scroller, header and marks follow it as they follow a finger - faster the further it
 * leans. Nothing ticks once the stick is back, the list is at its end or the drawer is gone. The
 * event is never taken, so the left stick still moves the focus; and the icon the controller is
 * on stays in view: as it reaches the list's edge, the focus goes to the nearest whole icon of
 * its column. Not while a menu or folder is open over the drawer.
 */
public final class DrawerStick {

    private static final String CONTAINER =
            "com.android.launcher3.allapps.ActivityAllAppsContainerView";
    private static final String FOLDER = "com.android.launcher3.folder.Folder";
    /** The drawer of a window, on its root view. */
    private static final int TAG_APPS = 0x7A000506;
    /** The fastest scroll, with the stick all the way over. */
    private static final float MAX_DP_PER_S = 2000f;
    /** The least lean that counts, whatever the controller says its own dead zone is. */
    private static final float MIN_FLAT = 0.12f;

    /** The list being scrolled; the stick's lean, -1 to 1, 0 at rest. Main thread. */
    private static WeakReference<ViewGroup> sList;
    private static float sLean;
    private static long sLastFrame;
    private static float sCarry;
    private static boolean sSaid;

    private DrawerStick() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> container = Reflect.findClass(CONTAINER, loader);
            if (container == null) {
                L.w("drawer stick: ZUI's drawer not found - the right stick does nothing there");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(container, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            attach((View) param.thisObject);
                        }
                    }).size();
            Health.hooked("drawer: the controller's right stick scrolls it", hooked,
                    Cfg::gamepadStick);
            L.i("drawer stick: the right stick scrolls the drawers x" + hooked);
        } catch (Throwable t) {
            L.w("drawer stick: not installed (" + t + ")");
        }
    }

    /** The drawer's window listens at its root, once; ZUI sets no listener there. */
    private static void attach(View apps) {
        try {
            View root = apps.getRootView();
            if (root == null || root == apps) {
                return;
            }
            boolean listening = root.getTag(TAG_APPS) != null;
            root.setTag(TAG_APPS, new WeakReference<>(apps));
            if (!listening) {
                root.setOnGenericMotionListener(DrawerStick::onStick);
            }
        } catch (Throwable t) {
            L.d("drawer stick: a drawer not listened to (" + t + ")");
        }
    }

    /** A joystick event at the window's root; never taken. */
    private static boolean onStick(View root, MotionEvent event) {
        try {
            if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                    || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                return false;
            }
            sLean = lean(event);
            ViewGroup ticking = sList != null ? sList.get() : null;
            if (sLean == 0f || (ticking != null && ticking.isAttachedToWindow())
                    || !Cfg.gamepadStick()) {
                // At rest, or already scrolling: the next frame takes the new lean.
                return false;
            }
            ViewGroup list = listIn(root);
            if (list != null) {
                sList = new WeakReference<>(list);
                sLastFrame = 0L;
                sCarry = 0f;
                list.postOnAnimation(TICK);
                if (!sSaid) {
                    sSaid = true;
                    L.i("drawer stick: the right stick scrolls " + list.getClass().getSimpleName()
                            + " in " + root.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            L.d("drawer stick: the stick left (" + t + ")");
        }
        return false;
    }

    /**
     * The right stick's lean up or down: 0 inside its dead zone, then growing with the square of
     * the way past it, for fine control near the middle. {@code AXIS_RZ} on most controllers,
     * {@code AXIS_RY} on those without it.
     */
    private static float lean(MotionEvent event) {
        InputDevice device = event.getDevice();
        int axis = MotionEvent.AXIS_RZ;
        InputDevice.MotionRange range = device == null ? null
                : device.getMotionRange(axis, event.getSource());
        if (range == null && device != null) {
            axis = MotionEvent.AXIS_RY;
            range = device.getMotionRange(axis, event.getSource());
        }
        float value = event.getAxisValue(axis);
        float flat = Math.max(MIN_FLAT, range == null ? 0f : range.getFlat());
        if (Math.abs(value) <= flat) {
            return 0f;
        }
        float past = Math.min(1f, (Math.abs(value) - flat) / (1f - flat));
        return Math.signum(value) * past * past;
    }

    /** The open drawer's list in this window; null with none, or a menu or folder over it. */
    private static ViewGroup listIn(View root) {
        Object ref = root.getTag(TAG_APPS);
        View apps = ref instanceof WeakReference ? (View) ((WeakReference<?>) ref).get() : null;
        if (apps == null || !apps.isAttachedToWindow() || !apps.isShown()
                || apps.getAlpha() <= 0f) {
            return null;
        }
        Object activity = ZuiFocus.lookup(apps.getContext());
        if (activity == null) {
            return null;
        }
        if (ZuiFolders.isLauncher(activity)
                && !Boolean.TRUE.equals(Reflect.call(activity, "isInAllAppsState"))) {
            // ZUX Home's drawer, put away.
            return null;
        }
        if (ZuiFocus.openPopup(apps, activity) != null || folderOpen(activity)) {
            return null;
        }
        Object list = Reflect.call(apps, "getActiveRecyclerView");
        return list instanceof ViewGroup && ((View) list).isShown() ? (ViewGroup) list : null;
    }

    private static boolean folderOpen(Object activity) {
        try {
            for (Method m : Class.forName(FOLDER, false, activity.getClass().getClassLoader())
                    .getMethods()) {
                if (m.getName().equals("getOpen") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(activity)) {
                    return m.invoke(null, activity) != null;
                }
            }
        } catch (Throwable t) {
            L.d("drawer stick: no open folder read (" + t + ")");
        }
        return false;
    }

    private static final Runnable TICK = DrawerStick::tick;

    /** One frame of scrolling, while the stick leans and the list can go that way. */
    private static void tick() {
        ViewGroup list = sList != null ? sList.get() : null;
        float lean = sLean;
        int way = lean > 0f ? 1 : -1;
        if (list == null || lean == 0f || !list.isAttachedToWindow() || !list.isShown()
                || !list.canScrollVertically(way)) {
            sList = null;
            return;
        }
        long now = AnimationUtils.currentAnimationTimeMillis();
        if (now == sLastFrame) {
            // A second tick in one frame - one queued on a list that left its window and came
            // back - ends there.
            return;
        }
        float seconds = sLastFrame == 0L ? 1f / 120f
                : Math.min(0.05f, (now - sLastFrame) / 1000f);
        sLastFrame = now;
        sCarry += lean * MAX_DP_PER_S * list.getResources().getDisplayMetrics().density * seconds;
        int dy = (int) sCarry;
        sCarry -= dy;
        if (dy != 0) {
            list.scrollBy(0, dy);
            keepFocusInView(list, way);
        }
        list.postOnAnimation(TICK);
    }

    /**
     * The icon the controller is on, reaching the list's edge as it scrolls, hands the focus to
     * the whole icon nearest its column in the row at that edge - scrolled away, its view would
     * be recycled with the focus on it.
     */
    private static void keepFocusInView(ViewGroup list, int way) {
        View cell = list.getFocusedChild();
        if (cell == null) {
            return;
        }
        int top = list.getPaddingTop();
        int bottom = list.getHeight() - list.getPaddingBottom();
        if (cell.getTop() >= top && cell.getBottom() <= bottom) {
            return;
        }
        float column = cell.getLeft() + cell.getWidth() / 2f;
        View best = null;
        int bestRow = 0;
        float bestOff = 0f;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c == cell || !c.hasFocusable() || c.getTop() < top || c.getBottom() > bottom) {
                continue;
            }
            // The row at the edge the focus is leaving by: the top one going down.
            int row = way > 0 ? c.getTop() : -c.getBottom();
            float off = Math.abs(c.getLeft() + c.getWidth() / 2f - column);
            if (best == null || row < bestRow || (row == bestRow && off < bestOff)) {
                best = c;
                bestRow = row;
                bestOff = off;
            }
        }
        if (best != null) {
            best.requestFocus(way > 0 ? View.FOCUS_DOWN : View.FOCUS_UP);
        }
    }
}
