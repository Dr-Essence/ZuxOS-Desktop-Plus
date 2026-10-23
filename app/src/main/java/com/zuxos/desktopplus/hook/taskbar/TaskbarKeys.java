package com.zuxos.desktopplus.hook.taskbar;

import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.ZuiFocus;
import com.zuxos.desktopplus.hook.drawer.ZuiFolders;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A game controller on the tablet's taskbar: the D-pad goes down onto the bar from the home
 * screen or a drawer, and L1 or R1 from anywhere but a full-screen app ({@link #shoulder}); it
 * moves across the start button, the apps and the folders, A opens, Triangle gives the icon's
 * menu - or, in a folder open on the bar, the menu of the app it is on - and B or up goes back.
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
 * <p>The icon the controller is on carries ZUI's own focus mark ({@link ZuiFocus#mark}), inside
 * its edges, and only our bar's icons ever carry it. The way down onto the bar is a D-pad down
 * that moves the focus nowhere: the bottom row of the home screen or of a drawer, with no menu or
 * folder open. From the taskbar's drawer, in a window of its own above the bar, the drawer closes
 * first, as the start button closes it. When ZUI's menu or folder on the bar closes, the
 * controller goes back to the icon it was opened from ({@link #settle}).
 */
public final class TaskbarKeys {

    /** A bar icon's hold menu, where its hold is a gesture of ours rather than a long click. */
    static final int TAG_MENU = 0x7A000502;
    /** The bar's mark on an icon, kept to be shown again. */
    private static final int TAG_MARK = 0x7A000503;
    /** The bar's mark's corners, as a share of the icon's size. */
    private static final float BAR_MARK_CORNER = 0.28f;
    /** A bar layer whose window's focus is watched. */
    private static final int TAG_WATCHED = 0x7A000504;
    /** One of our bar's icons: the only views the controller's mark goes on. */
    private static final int TAG_ICON = 0x7A000505;

    private static final String FLOATING = "com.android.launcher3.AbstractFloatingView";
    private static final String FOLDER = "com.android.launcher3.folder.Folder";

    /** The tablet bar's layer while the controller is on it; null otherwise. Main thread. */
    private static WeakReference<ViewGroup> sOn;
    /** The icon the controller was last on, to come back to. */
    private static WeakReference<View> sLast;
    /** The view a menu was opened from on the bar - an icon, or an app in a folder. */
    private static WeakReference<View> sMenuOf;
    /** The bar's folder icon whose folder is open. */
    private static WeakReference<View> sFolderOf;
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
                    // The taskbar's drawer is a window above the bar, which would keep the
                    // focus: it closes, as the start button closes it.
                    enter(layer.getClass().getName().endsWith("TaskbarOverlayDragLayer"),
                            layer.getClass().getSimpleName());
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

    /**
     * L1 or R1 on a controller, taken in the system wherever no full-screen app has the screen
     * ({@code SystemGamepad}): the controller onto the bar - its drawer closed first, as the way
     * down closes it - or one icon along it. Nothing while ZUI's menu or folder is open on it.
     */
    static void shoulder(int step) {
        try {
            if (!Cfg.gamepadBar()) {
                return;
            }
            ViewGroup bar = on();
            if (bar == null) {
                enter(TaskbarStart.drawerOpen(Display.DEFAULT_DISPLAY), step < 0 ? "L1" : "R1");
                return;
            }
            Object context = ZuiFocus.lookup(bar.getContext());
            if (context == null || ZuiFocus.openPopup(bar, context) != null
                    || topOpen(context) != null) {
                return;
            }
            View focused = bar.findFocus();
            if (focused == null || !isIcon(focused)) {
                focusLast(bar);
                return;
            }
            View next = focused.focusSearch(step < 0 ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
            if (next != null && isIcon(next) && ZuiFocus.within(next, bar)) {
                next.requestFocus();
            }
        } catch (Throwable t) {
            L.d("taskbar keys: L1/R1 left (" + t + ")");
        }
    }

    /** The controller onto the tablet's bar, from {@code from}; the taskbar's drawer closed. */
    private static void enter(boolean drawerUp, String from) {
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
        if (drawerUp) {
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
            L.i("taskbar keys: the controller on the bar, from " + from);
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

    /**
     * While the controller is on the bar, ZUI's own "not focusable" keeps it focusable. ZUI asks
     * as its popup or folder on the bar opens and closes, and the controller settles after it.
     */
    private static void keepFocusable(Object context, Object[] args) {
        ViewGroup bar = on();
        if (bar == null || args.length != 2 || context != ZuiFocus.lookup(bar.getContext())) {
            return;
        }
        if (Boolean.FALSE.equals(args[0])) {
            args[0] = Boolean.TRUE;
            args[1] = Boolean.TRUE;
        }
        bar.post(() -> settle(bar));
    }

    /**
     * The controller kept where it can be seen once ZUI's popup or folder on the bar opened or
     * closed. A closing popup took the focus with it, and Android handed it to whatever view of the
     * bar's window came first, which carries no mark: after B the controller seemed gone from the
     * bar. With a popup open its rows have the focus; with a folder open, the app it was on inside
     * it, or its first; with neither, the icon the menu or the folder was opened from.
     */
    private static void settle(ViewGroup bar) {
        try {
            Object context = on() == bar ? ZuiFocus.lookup(bar.getContext()) : null;
            if (context == null || ZuiFocus.openPopup(bar, context) != null) {
                return;
            }
            View focused = bar.findFocus();
            View menuOf = sMenuOf != null ? sMenuOf.get() : null;
            Object open = topOpen(context);
            if (isFolder(open)) {
                ViewGroup folder = (ViewGroup) open;
                if (focused == null || !ZuiFocus.within(focused, folder)
                        || focused instanceof EditText) {
                    View in = menuOf != null && menuOf.isShown()
                            && ZuiFocus.within(menuOf, folder) ? menuOf
                            : ZuiFocus.firstRow(folder);
                    if (in != null) {
                        in.requestFocus();
                    }
                }
                return;
            }
            View folderOf = sFolderOf != null ? sFolderOf.get() : null;
            sMenuOf = null;
            sFolderOf = null;
            View back = menuOf != null && menuOf.isShown() && isIcon(menuOf) ? menuOf
                    : folderOf != null && folderOf.isShown() ? folderOf : null;
            if (back != null && back != focused) {
                back.requestFocus();
            }
            focusLast(bar);
        } catch (Throwable t) {
            L.d("taskbar keys: the controller not settled (" + t + ")");
        }
    }

    private static boolean isFolder(Object open) {
        for (Class<?> c = open != null ? open.getClass() : null; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(FOLDER)) {
                return open instanceof ViewGroup;
            }
        }
        return false;
    }

    private static boolean isIcon(View v) {
        return v.getTag(TAG_ICON) != null;
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

    /**
     * The icon the controller was last on, or the start button, takes the focus - unless one of our
     * icons has it already. Whatever else of the bar's window held the focus is no place for the
     * controller: kept, ZUI's whole icon row took the mark and the lift, and a ring wider than the
     * screen swung across it.
     */
    private static void focusLast(ViewGroup bar) {
        View focused = bar.findFocus();
        if (focused != null && isIcon(focused)) {
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
        if (now != null && isIcon(now)) {
            look(now, true);
        }
    }

    // --- on the bar ----------------------------------------------------------

    /** A key on the bar's layer while the controller is on the bar; true when it was ours. */
    private static boolean onBar(ViewGroup layer, KeyEvent event) {
        try {
            if (on() != layer || event.isCanceled()) {
                // A cancelled key is no press: the release of a back already taken.
                return false;
            }
            int code = event.getKeyCode();
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            Object context = ZuiFocus.lookup(layer.getContext());
            Object popup = context == null ? null : ZuiFocus.openPopup(layer, context);
            Object open = popup == null ? topOpen(context) : popup;
            View focused = layer.findFocus();
            if (code == KeyEvent.KEYCODE_BUTTON_Y) {
                if (down && event.getRepeatCount() == 0) {
                    if (popup != null) {
                        Reflect.call(popup, "close", true);
                    } else if (open == null) {
                        menu(focused, null);
                    } else if (isFolder(open) && focused != null
                            && ZuiFocus.within(focused, (ViewGroup) open)) {
                        // An app in a folder open on the bar: the bar's own popup for it.
                        menu(focused, context);
                    }
                }
                return true;
            }
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                    && !down) {
                // A (its fallback, the centre key): clicked as ZUI clicks it, then the
                // controller follows whatever opened.
                opened(layer, focused);
                return false;
            }
            int direction = ZuiFocus.direction(code);
            if (open != null) {
                // ZUI's popup or folder over the bar: its own keys and back, the arrows kept
                // inside it.
                return direction != 0 && down && (popup != null
                        ? ZuiFocus.keepIn(layer, direction)
                        : isFolder(open) && ZuiFocus.keepWithin(layer, (ViewGroup) open,
                        direction));
            }
            if (down && focused != null && isIcon(focused)
                    && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) {
                // Along our icons only: a view of ZUI's beyond them would carry no mark.
                View next = focused.focusSearch(direction);
                return next == null || !isIcon(next);
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
                    if (direction != 0 && (focused == null || !isIcon(focused))) {
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

    /**
     * The icon's hold menu, as a hold gives it: our pins' own, or the view's long click; for an
     * app in a folder open on the bar ({@code bar} given), the bar's own popup for it. Opened by
     * a key, without the window options ({@link ZuiFocus#byKeys}).
     */
    private static void menu(View icon, Object bar) {
        if (icon == null || (bar == null && !isIcon(icon))) {
            return;
        }
        sMenuOf = new WeakReference<>(icon);
        try {
            ZuiFocus.byKeys(() -> {
                if (bar != null) {
                    return ZuiFocus.showOnTaskbar(icon, bar);
                }
                Object menu = icon.getTag(TAG_MENU);
                if (menu instanceof Runnable) {
                    ((Runnable) menu).run();
                    return true;
                }
                return icon.performLongClick();
            });
        } catch (Throwable t) {
            L.d("taskbar keys: no menu (" + t + ")");
        }
    }

    /**
     * A clicked the icon or a row: an app opened from it - on the bar, in a folder of the bar,
     * from a menu - takes the screen, and the controller goes with it; the bar, above every app,
     * would otherwise keep its keys. A folder opens on the bar and keeps it; the start button
     * gives the bar back itself, before its drawer opens ({@link #release}).
     */
    private static void opened(ViewGroup layer, View icon) {
        if (icon == null || icon instanceof TaskbarStart.StartButton) {
            return;
        }
        if (ZuiFolders.isZuiFolder(icon)) {
            sFolderOf = new WeakReference<>(icon);
            return;
        }
        layer.post(() -> leave("an app opened"));
    }

    /**
     * The bar given back before a drawer opens from the start button - a tap, A, or the
     * controller's Home. The bar's window is above the drawer's and, kept focusable for the
     * controller, kept its keys: the D-pad moved nothing in the drawer until the screen was
     * touched.
     */
    static void release(String why) {
        if (on() != null) {
            leave(why);
        }
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
        if (focused != null && isIcon(focused)) {
            sLast = new WeakReference<>(focused);
            look(focused, false);
        }
        sMenuOf = null;
        sFolderOf = null;
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
     * controller is on the bar the icon it is on carries ZUI's mark. A listener the icon already
     * had is still told first. The tablet's bar only.
     */
    static void focusable(View icon, int displayId) {
        if (icon == null || displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        icon.setFocusable(true);
        icon.setTag(TAG_ICON, Boolean.TRUE);
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

    /**
     * ZUI's mark on an icon, or taken off again. The mark alone, as ZUI's drawers show it, inside
     * the icon's edges: with the mouse's lift (x1.14 and a turn) it ran off the screen's edge at
     * the start button and past the row's ends at its first and last icons.
     */
    private static void look(View icon, boolean on) {
        Object mark = icon.getTag(TAG_MARK);
        if (on) {
            if (!(mark instanceof android.graphics.drawable.Drawable)) {
                // ZUI's drawer corners (20 dp) on a bar icon made the ring a circle: corners in
                // proportion to the icon, as the bar's rounded icons have.
                mark = ZuiFocus.mark(icon.getContext(),
                        Math.min(icon.getWidth(), icon.getHeight()) * BAR_MARK_CORNER);
                icon.setTag(TAG_MARK, mark);
            }
            if (icon.getForeground() == null || icon.getForeground() == mark) {
                icon.setForeground((android.graphics.drawable.Drawable) mark);
            }
        } else if (mark != null && icon.getForeground() == mark) {
            icon.setForeground(null);
        }
    }
}
