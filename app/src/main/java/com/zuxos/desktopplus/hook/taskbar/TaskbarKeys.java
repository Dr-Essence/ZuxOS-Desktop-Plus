package com.zuxos.desktopplus.hook.taskbar;

import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.motion.Hover;
import com.zuxos.desktopplus.core.theme.Theme;
import com.zuxos.desktopplus.hook.ZuiFocus;
import com.zuxos.desktopplus.hook.drawer.ZuiFolders;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A game controller on the tablet's taskbar: the D-pad goes down onto the bar from the home
 * screen or a drawer, moves across the start button, the apps and the folders, A opens, Triangle
 * gives the icon's menu, and B or up goes back.
 *
 * <p>The bar's window takes no keys: ZUI makes it focusable only while its own popup or folder is
 * open ({@code TaskbarActivityContext.setTaskbarWindowFocusable}, from
 * {@code onPopupVisibilityChanged} and its folders' state). Taking the controller onto the bar
 * goes through that same switch, and while the controller is on the bar it is one more reason
 * ZUI keeps the window focusable, as an open folder is. Leaving hands the window back to ZUI's own
 * decision ({@code onPopupVisibilityChanged(false)}): on B, on up, on any touch, on an app opened
 * from the bar, and whenever the bar's window loses the focus - to the drawer, an app, the lock
 * screen. Nothing polls and nothing ticks; every step is a key, a touch or a focus change.
 *
 * <p>The icon the controller is on lifts as under the mouse ({@link Hover}) and carries ZUI's own
 * focus mark ({@link ZuiFocus#mark}). The way down onto the bar is a D-pad down that moves the
 * focus nowhere: the bottom row of the home screen or of a drawer, with no menu or folder open.
 * From the taskbar's drawer, in a window of its own above the bar, the drawer closes first, as
 * the start button closes it.
 */
public final class TaskbarKeys {

    /** A bar icon's hold menu, where its hold is a gesture of ours rather than a long click. */
    static final int TAG_MENU = 0x7A000502;
    /** The bar's mark on an icon, kept to be shown again. */
    private static final int TAG_MARK = 0x7A000503;
    /** A bar layer whose window's focus is watched. */
    private static final int TAG_WATCHED = 0x7A000504;

    private static final String FLOATING = "com.android.launcher3.AbstractFloatingView";

    /** The tablet bar's layer while the controller is on it; null otherwise. Main thread. */
    private static WeakReference<ViewGroup> sOn;
    /** The icon the controller was last on, to come back to. */
    private static WeakReference<View> sLast;
    private static boolean sSaidOn;
    private static boolean sSaidNoBar;

    private TaskbarKeys() {
    }

    public static void install(ClassLoader loader) {
        int hooked = 0;
        for (String name : new String[]{"com.android.launcher3.dragndrop.DragLayer",
                "com.android.launcher3.taskbar.overlay.TaskbarOverlayDragLayer"}) {
            try {
                Class<?> layer = Reflect.findClass(name, loader);
                if (layer != null) {
                    hooked += XposedBridge.hookAllMethods(layer, "dispatchKeyEvent",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (param.args.length == 1
                                            && param.args[0] instanceof KeyEvent) {
                                        downward((View) param.thisObject,
                                                (KeyEvent) param.args[0]);
                                    }
                                }
                            }).size();
                }
            } catch (Throwable t) {
                L.w("taskbar keys: " + name + " not watched (" + t + ")");
            }
        }
        try {
            Class<?> bar = Reflect.findClass("com.android.launcher3.taskbar.TaskbarDragLayer",
                    loader);
            if (bar != null) {
                hooked += XposedBridge.hookAllMethods(bar, "dispatchKeyEvent",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args.length == 1 && param.args[0] instanceof KeyEvent
                                        && onBar((ViewGroup) param.thisObject,
                                        (KeyEvent) param.args[0])) {
                                    param.setResult(Boolean.TRUE);
                                }
                            }
                        }).size();
                hooked += XposedBridge.hookAllMethods(bar, "dispatchTouchEvent",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args.length == 1
                                        && param.args[0] instanceof MotionEvent
                                        && touched((ViewGroup) param.thisObject,
                                        (MotionEvent) param.args[0])) {
                                    param.setResult(Boolean.TRUE);
                                }
                            }
                        }).size();
            }
            Class<?> context = Reflect.findClass(
                    "com.android.launcher3.taskbar.TaskbarActivityContext", loader);
            if (context != null) {
                hooked += XposedBridge.hookAllMethods(context, "setTaskbarWindowFocusable",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                keepFocusable(param.thisObject, param.args);
                            }
                        }).size();
            }
        } catch (Throwable t) {
            L.w("taskbar keys: the bar not reached (" + t + ")");
        }
        Health.hooked("taskbar: the game controller on the bar", hooked, Cfg::gamepadBar);
        L.i("taskbar keys: the controller goes onto the bar x" + hooked);
    }

    // --- onto the bar --------------------------------------------------------

    /**
     * A D-pad down on the home screen or a drawer: once Android has moved the focus, or found
     * nowhere to move it, the controller goes onto the bar if it found nowhere. Not from inside a
     * folder or a menu, and not on a held key, which scrolls a drawer to its end.
     */
    private static void downward(View layer, KeyEvent event) {
        try {
            if (event.getKeyCode() != KeyEvent.KEYCODE_DPAD_DOWN
                    || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0
                    || !Cfg.gamepadBar() || layer.getDisplay() == null
                    || layer.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) {
                return;
            }
            View before = layer.findFocus();
            Object activity = ZuiFocus.lookup(layer.getContext());
            if (before == null || inFolder(before)
                    || (activity != null && ZuiFocus.openPopup(layer, activity) != null)) {
                return;
            }
            // After Android's own move: its focus search runs once the layers have passed on
            // the key (ViewRootImpl.performFocusNavigation), in the same message.
            layer.post(() -> {
                if (layer.findFocus() == before && before.isAttachedToWindow()) {
                    enter(layer);
                }
            });
        } catch (Throwable t) {
            L.d("taskbar keys: down left to ZUI (" + t + ")");
        }
    }

    private static boolean inFolder(View v) {
        for (View p = v; p != null; p = ZuiFocus.parentOf(p)) {
            if (p.getClass().getName().endsWith(".folder.Folder")) {
                return true;
            }
        }
        return false;
    }

    /** The controller onto the tablet's bar, from {@code from}. */
    private static void enter(View from) {
        View button = TaskbarStart.shownOn(Display.DEFAULT_DISPLAY);
        ViewGroup bar = button != null && button.getParent() instanceof ViewGroup
                ? (ViewGroup) button.getParent() : null;
        Object context = bar == null ? null : ZuiFocus.lookup(bar.getContext());
        if (context == null) {
            if (!sSaidNoBar) {
                sSaidNoBar = true;
                L.i("taskbar keys: no bar with our start button shown - the controller stays");
            }
            return;
        }
        if (from.getClass().getName().endsWith("TaskbarOverlayDragLayer")) {
            // The taskbar's drawer is a window above the bar, which would keep the focus: it
            // closes, as the start button closes it.
            TaskbarStart.toggleDrawer(Display.DEFAULT_DISPLAY);
        }
        sOn = new WeakReference<>(bar);
        if (sLast == null || sLast.get() == null || !ZuiFocus.within(sLast.get(), bar)) {
            sLast = new WeakReference<>(button);
        }
        watch(bar);
        if (!focusable(context, true)) {
            sOn = null;
            return;
        }
        if (bar.hasWindowFocus()) {
            focusLast(bar);
        }
        if (!sSaidOn) {
            sSaidOn = true;
            L.i("taskbar keys: the controller on the bar, from "
                    + from.getClass().getSimpleName());
        }
    }

    /** The bar's window, given the focus or giving it up, as ZUI's own popups have it. */
    private static boolean focusable(Object context, boolean on) {
        try {
            Method set = context.getClass().getMethod("setTaskbarWindowFocusable",
                    boolean.class, boolean.class);
            set.invoke(context, on, on);
            return true;
        } catch (Throwable t) {
            L.w("taskbar keys: the bar's window not made focusable (" + t + ")");
            return false;
        }
    }

    /** While the controller is on the bar, ZUI's own "not focusable" keeps it focusable. */
    private static void keepFocusable(Object context, Object[] args) {
        ViewGroup bar = on();
        if (bar != null && args.length == 2 && Boolean.FALSE.equals(args[0])
                && context == ZuiFocus.lookup(bar.getContext())) {
            args[0] = Boolean.TRUE;
            args[1] = Boolean.TRUE;
        }
    }

    /** The bar's window focus watched once per bar: it got it, or lost it. */
    private static void watch(ViewGroup bar) {
        if (bar.getTag(TAG_WATCHED) != null) {
            return;
        }
        bar.setTag(TAG_WATCHED, Boolean.TRUE);
        bar.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (on() != bar) {
                            return;
                        }
                        if (hasFocus) {
                            focusLast(bar);
                        } else {
                            leave("the bar's window lost the focus");
                        }
                    }
                });
    }

    private static ViewGroup on() {
        return sOn != null ? sOn.get() : null;
    }

    /** The icon the controller was last on, or the start button, takes the focus. */
    private static void focusLast(ViewGroup bar) {
        View focused = bar.findFocus();
        if (focused != null && focused != bar) {
            look(focused, true);
            return;
        }
        View last = sLast != null ? sLast.get() : null;
        if (last == null || !last.isShown() || !last.requestFocus()) {
            View button = TaskbarStart.shownOn(Display.DEFAULT_DISPLAY);
            if (button != null) {
                button.requestFocus();
            }
        }
        View now = bar.findFocus();
        if (now != null) {
            look(now, true);
        }
    }

    // --- on the bar ----------------------------------------------------------

    /** A key on the bar's layer while the controller is on the bar; true when it was ours. */
    private static boolean onBar(ViewGroup layer, KeyEvent event) {
        try {
            if (on() != layer) {
                return false;
            }
            int code = event.getKeyCode();
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            Object context = ZuiFocus.lookup(layer.getContext());
            Object popup = context == null ? null : ZuiFocus.openPopup(layer, context);
            if (code == KeyEvent.KEYCODE_BUTTON_Y) {
                if (down && event.getRepeatCount() == 0) {
                    if (popup != null) {
                        Reflect.call(popup, "close", true);
                    } else if (topOpen(context) == null) {
                        menu(layer.findFocus());
                    }
                }
                return true;
            }
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                    && !down) {
                // A (its fallback, the centre key): clicked as ZUI clicks it, then the
                // controller follows whatever opened.
                opened(layer, layer.findFocus());
                return false;
            }
            if (popup != null || topOpen(context) != null) {
                // ZUI's popup or folder over the bar: its own keys and back, kept to its rows.
                int direction = ZuiFocus.direction(code);
                return direction != 0 && down && ZuiFocus.keepIn(layer, direction);
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) {
                        leave("up");
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_BUTTON_B:
                    // On the release, as back is: the press alone would leave its release to
                    // whatever has the focus next.
                    if (!down) {
                        leave("back");
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // Nothing below the bar.
                    return true;
                default:
                    if (ZuiFocus.direction(code) != 0 && layer.findFocus() == null) {
                        focusLast(layer);
                        return true;
                    }
                    return false;
            }
        } catch (Throwable t) {
            L.d("taskbar keys: a key left to ZUI (" + t + ")");
            return false;
        }
    }

    /** ZUI's floating view open over the bar - a popup or a folder - or null. */
    private static Object topOpen(Object context) {
        if (context == null) {
            return null;
        }
        try {
            for (Method m : Class.forName(FLOATING, false, context.getClass().getClassLoader())
                    .getMethods()) {
                if (m.getName().equals("getTopOpenView") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(context)) {
                    return m.invoke(null, context);
                }
            }
        } catch (Throwable t) {
            L.d("taskbar keys: no open view read (" + t + ")");
        }
        return null;
    }

    /** The icon's hold menu, as a hold gives it: our pins' own, or the view's long click. */
    private static void menu(View icon) {
        if (icon == null) {
            return;
        }
        Object menu = icon.getTag(TAG_MENU);
        if (menu instanceof Runnable) {
            ((Runnable) menu).run();
        } else {
            icon.performLongClick();
        }
    }

    /**
     * A clicked the icon or a row: an app opened from it - on the bar, in a folder of the bar,
     * from a menu - takes the screen, and the controller goes with it; the bar, above every app,
     * would otherwise keep its keys. A folder opens on the bar and keeps it; the start button's
     * drawer takes the window focus, which ends the bar's turn by itself.
     */
    private static void opened(ViewGroup layer, View icon) {
        if (icon == null || ZuiFolders.isZuiFolder(icon)
                || icon instanceof TaskbarStart.StartButton) {
            return;
        }
        layer.post(() -> leave("an app opened"));
    }

    /**
     * A touch on the bar's window while the controller is on it: the controller's turn ends. The
     * window takes every touch while it is focusable (ZUI's switch clears
     * {@code FLAG_NOT_TOUCH_MODAL}); one outside the bar only ends the turn, one on it goes on.
     */
    private static boolean touched(ViewGroup layer, MotionEvent event) {
        int action = event.getActionMasked();
        if (sSwallowing) {
            // The rest of a touch that began outside the bar: it was only the way back to touch.
            sSwallowing = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
            return true;
        }
        if (on() != layer || action != MotionEvent.ACTION_DOWN) {
            return false;
        }
        leave("a touch");
        sSwallowing = event.getY() < 0 || event.getY() > layer.getHeight()
                || event.getX() < 0 || event.getX() > layer.getWidth();
        return sSwallowing;
    }

    /** A touch that began outside the bar while the controller was on it, till it ends. */
    private static boolean sSwallowing;

    /** The controller off the bar: the window's focus is ZUI's own decision again. */
    private static void leave(String why) {
        ViewGroup bar = on();
        sOn = null;
        if (bar == null) {
            return;
        }
        View focused = bar.findFocus();
        if (focused != null) {
            sLast = new WeakReference<>(focused);
            look(focused, false);
        }
        Object context = ZuiFocus.lookup(bar.getContext());
        if (context == null) {
            return;
        }
        try {
            // ZUI's own decision: focusable still for an open folder of its, else not.
            context.getClass().getMethod("onPopupVisibilityChanged", boolean.class)
                    .invoke(context, false);
        } catch (Throwable t) {
            focusable(context, false);
        }
        L.d("taskbar keys: the controller off the bar - " + why);
    }

    // --- the icons -----------------------------------------------------------

    /**
     * A bar icon the controller can be on: focusable (nothing in touch mode), and while the
     * controller is on the bar the icon it is on lifts and carries ZUI's mark. A listener the
     * icon already had is still told first. The tablet's bar only.
     */
    static void focusable(View icon, int displayId) {
        if (icon == null || displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        icon.setFocusable(true);
        View.OnFocusChangeListener before = icon.getOnFocusChangeListener();
        icon.setOnFocusChangeListener((v, has) -> {
            if (before != null) {
                before.onFocusChange(v, has);
            }
            if (has && on() != null) {
                sLast = new WeakReference<>(v);
            }
            look(v, has && on() != null);
        });
    }

    /** The lift and ZUI's mark on an icon, or both taken off again. */
    private static void look(View icon, boolean on) {
        Object mark = icon.getTag(TAG_MARK);
        if (on) {
            if (!(mark instanceof android.graphics.drawable.Drawable)) {
                mark = ZuiFocus.mark(icon.getContext());
                icon.setTag(TAG_MARK, mark);
            }
            if (icon.getForeground() == null || icon.getForeground() == mark) {
                icon.setForeground((android.graphics.drawable.Drawable) mark);
            }
            if (!Theme.of(icon).retro()) {
                Hover.enter(icon);
            }
        } else {
            if (mark != null && icon.getForeground() == mark) {
                icon.setForeground(null);
            }
            Hover.exit(icon);
        }
    }
}
