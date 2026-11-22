package com.zuxos.desktopplus.hook.taskbar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Insets;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.Windows;
import com.zuxos.desktopplus.logic.EdgeMath;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The monitor's taskbar out of a full-screen window's way, and back when the pointer reaches the
 * bar's edge, as DeX does.
 *
 * <p>ZUI has no hide for this bar that can feel the pointer: on the monitor its stash only fades
 * the icons, and its one full hide, for the lock screen, hides the whole window. So the bar's
 * window stays where ZUI put it and its content slides off the screen's edge, ours with it; the
 * window keeps a 2 px strip at that edge to take the pointer, and gives every other pixel to the
 * app (its touchable region, set after ZUI sets it). A pointer resting on the strip for a moment
 * brings the bar back, over the app; leaving the bar sends it away again, unless something of it
 * is open - ZUI's popups and folders, which make its window full screen, or a window of ours or
 * ZUI's drawer on the monitor.
 *
 * <p>The pointer is felt by one transparent view over the whole window, on top, added only while
 * a full-screen window is in front: it sees the window's own hover enter, move and exit, which
 * the bar's own views take whenever the pointer is over them. It takes nothing while the bar is
 * showing, and everything while it is not, so nothing under an invisible bar answers.
 *
 * <p>Nothing here runs while no window is full screen: no view, no region, no game mode.
 */
final class BarAutoHide {

    /** The strip at the bar's edge that still takes the pointer while it is away. */
    private static final int STRIP_PX = 2;
    /** How long the pointer rests on the edge before the bar comes: a sweep past it is not. */
    private static final long DWELL_MS = 100L;
    /** How long after the pointer leaves the bar it goes. */
    private static final long LEAVE_MS = 500L;
    /** While something of the bar is open, how often it looks again. */
    private static final long HELD_MS = 500L;
    /** A bar brought by a touch goes after this long without one. */
    private static final long TOUCH_MS = 3000L;
    private static final int TOUCHABLE_INSETS_REGION = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Per display, its bar while a full-screen window is in front there. Main thread. */
    private static final Map<Integer, Bar> BARS = new ConcurrentHashMap<>();
    /** Each bar's slide under way: one at a time, whichever state asked for it last. */
    private static final Map<View, ValueAnimator> SLIDES = new WeakHashMap<>();

    private static boolean sInstalled;

    private BarAutoHide() {
    }

    /** The bar's touchable region at its edge only, while it is away. Once, with the switch on. */
    static void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        if (!Cfg.enabled() || !Cfg.fullScreen()) {
            return;
        }
        Class<?> insets = Reflect.findClass(TaskbarNames.TASKBAR_INSETS_CONTROLLER_CLASS, loader);
        int hooked = 0;
        if (insets != null) {
            hooked = XposedBridge.hookAllMethods(insets, TaskbarNames.UPDATE_INSETS_TOUCHABILITY,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length == 1) {
                                    edgeOnly(param.thisObject, param.args[0]);
                                }
                            } catch (Throwable t) {
                                L.d("bar auto-hide: the bar kept its touches (" + t + ")");
                            }
                        }
                    }).size();
            hookKeyboardSize(insets);
        }
        Health.hooked("taskbar: full screen, the monitor's bar out of the way", hooked,
                () -> Cfg.enabled() && Cfg.fullScreen());
        if (hooked == 0) {
            L.w("bar auto-hide: ZUI's touch region not found - over a full-screen window the "
                    + "monitor's bar stays");
        }
    }

    /**
     * The keyboard told the bar is as small as apps are told, in ZUI's game mode.
     *
     * <p>ZUI's game mode tells apps the bar is 1 px, so they take the screen under it, but keeps
     * telling keyboards its full height ({@code TaskbarInsetsController.k}: the override for the
     * keyboard's window type is left as it was). A keyboard that ends at the bar
     * ({@code HeliWindow}) then stopped a bar's height above the bottom of a full-screen app,
     * over nothing. So in game mode the navigation bar's override for keyboards takes the size
     * apps are given.
     */
    private static void hookKeyboardSize(Class<?> insets) {
        Method size = null;
        Class<?> provider = Reflect.findClass(TaskbarNames.INSETS_FRAME_PROVIDER,
                insets.getClassLoader());
        for (Method m : insets.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (m.getName().equals(TaskbarNames.SIZE_PROVIDER) && types.length == 3
                    && types[0] == provider) {
                size = m;
            }
        }
        if (size == null) {
            L.w("bar auto-hide: ZUI's inset sizing not found - in Full screen the keyboard "
                    + "stops a bar's height above the bottom");
            return;
        }
        XposedBridge.hookMethod(size, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (Boolean.TRUE.equals(Reflect.call(param.thisObject,
                            TaskbarNames.GET_GAME_MODE)) && Cfg.barClearance()) {
                        keyboardAsApps(param.args[0]);
                    }
                } catch (Throwable t) {
                    L.d("bar auto-hide: the keyboard's size of the bar left to ZUI (" + t + ")");
                }
            }
        });
    }

    private static void keyboardAsApps(Object provider) throws Exception {
        Object type = Reflect.call(provider, TaskbarNames.GET_TYPE);
        Object size = Reflect.call(provider, TaskbarNames.GET_INSETS_SIZE);
        Object held = Reflect.call(provider, TaskbarNames.GET_INSETS_SIZE_OVERRIDES);
        if (!Integer.valueOf(WindowInsets.Type.navigationBars()).equals(type)
                || !(size instanceof Insets) || !(held instanceof Object[])) {
            return;
        }
        Object[] overrides = ((Object[]) held).clone();
        boolean changed = false;
        for (int i = 0; i < overrides.length; i++) {
            Object o = overrides[i];
            if (o != null && Integer.valueOf(WindowManager.LayoutParams.TYPE_INPUT_METHOD)
                    .equals(Reflect.call(o, TaskbarNames.GET_WINDOW_TYPE))
                    && !size.equals(Reflect.call(o, TaskbarNames.GET_INSETS_SIZE))) {
                overrides[i] = o.getClass().getConstructor(int.class, Insets.class)
                        .newInstance(WindowManager.LayoutParams.TYPE_INPUT_METHOD, size);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        provider.getClass().getMethod(TaskbarNames.SET_INSETS_SIZE_OVERRIDES, held.getClass())
                .invoke(provider, (Object) overrides);
        if (L.once("BarAutoHide.keyboard")) {
            L.i("bar auto-hide: in game mode the keyboard is told the bar's size apps are told");
        }
    }

    /** A bar (re)attached: SystemUI heard, and a rebuilt bar taken out of the way again. */
    static void attach(ViewGroup dragLayer) {
        try {
            int display = TaskbarTray.displayIdOf(dragLayer);
            if (!FullScreen.offered(display)) {
                return;
            }
            FullScreen.listen(dragLayer.getContext());
            Bar bar = BARS.get(display);
            if (bar == null || !bar.engaged) {
                return;
            }
            if (bar.layer() != dragLayer) {
                release(bar);
                engage(display, true, dragLayer);
            } else {
                keepOnTop(bar, dragLayer);
            }
        } catch (Throwable t) {
            L.d("bar auto-hide: the bar left as it is (" + t + ")");
        }
    }

    /**
     * The catcher kept the bar's last child: one of ours added after it that answers the pointer
     * would take it first, and the catcher would read the pointer leaving the bar.
     */
    private static void keepOnTop(Bar bar, ViewGroup layer) {
        View catcher = bar.catcher;
        if (catcher != null && catcher.getParent() == layer
                && layer.indexOfChild(catcher) != layer.getChildCount() - 1) {
            catcher.bringToFront();
        }
    }

    static boolean engagedAnywhere() {
        return !BARS.isEmpty();
    }

    static List<Integer> engagedDisplays() {
        return new ArrayList<>(BARS.keySet());
    }

    /** The display's bar out of the way while {@code on}, and back to ZUI's otherwise. */
    static void engage(int display, boolean on) {
        engage(display, on, on ? monitorBar(display) : null);
    }

    private static void engage(int display, boolean on, ViewGroup layer) {
        Bar bar = BARS.get(display);
        if (!on) {
            if (bar != null) {
                BARS.remove(display);
                release(bar);
                L.i("bar auto-hide: display " + display + "'s bar back for good");
            }
            return;
        }
        if (layer == null || !sInstalled) {
            if (L.once("BarAutoHide.noBar")) {
                L.i("bar auto-hide: no bar on display " + display + " to move out of the way");
            }
            return;
        }
        if (bar != null && bar.engaged && bar.layer() == layer) {
            return;
        }
        if (bar == null) {
            bar = new Bar(display);
            BARS.put(display, bar);
        }
        bar.layer = new WeakReference<>(layer);
        bar.engaged = true;
        bar.revealed = false;
        bar.hovered = false;
        bar.baseline.clear();
        for (View root : shownWindows(display, layer.getRootView())) {
            bar.baseline.put(root, Boolean.TRUE);
        }
        addCatcher(bar, layer);
        gameMode(layer, true);
        slide(bar, layer, away(layer));
        layer.requestLayout();
        L.i("bar auto-hide: display " + display + "'s bar out of the way of a full-screen window"
                + " - back at the " + (BarEdge.onTop(layer) ? "top" : "bottom") + " edge");
    }

    /** Back to ZUI's bar: in place, its touches and its room for apps given back. */
    private static void release(Bar bar) {
        bar.engaged = false;
        bar.cancelTimers();
        ViewGroup layer = bar.layer();
        if (layer == null) {
            return;
        }
        if (bar.catcher != null && bar.catcher.getParent() == layer) {
            layer.removeView(bar.catcher);
        }
        bar.catcher = null;
        gameMode(layer, false);
        slide(bar, layer, 0f);
        layer.requestLayout();
    }

    /** Brought back for something opened from it without the pointer: the drawer by a key. */
    static void show(int display) {
        Bar bar = BARS.get(display);
        if (bar != null && bar.engaged && !bar.revealed) {
            safely(() -> {
                reveal(bar, false);
                later(bar, bar.leave, LEAVE_MS);
            });
        }
    }

    /** Every call from a timer, a key or another window: the launcher never stops for one. */
    private static void safely(Runnable what) {
        try {
            what.run();
        } catch (Throwable t) {
            L.d("bar auto-hide: the bar left as it is (" + t + ")");
        }
    }

    private static void reveal(Bar bar, boolean byPointer) {
        ViewGroup layer = bar.layer();
        if (layer == null || !bar.engaged || bar.revealed) {
            return;
        }
        bar.revealed = true;
        bar.hovered = byPointer;
        keepOnTop(bar, layer);
        slide(bar, layer, 0f);
        layer.requestLayout();
        // A change the task events missed - the window floated by its own button - is caught as
        // the bar is asked for.
        FullScreen.onTasks();
    }

    private static void leave(Bar bar) {
        ViewGroup layer = bar.layer();
        if (layer == null || !bar.engaged || !bar.revealed || bar.hovered) {
            return;
        }
        String held = held(bar, layer);
        if (held != null) {
            bar.held = held;
            later(bar, bar.leave, HELD_MS);
            return;
        }
        bar.held = null;
        bar.revealed = false;
        slide(bar, layer, away(layer));
        layer.requestLayout();
    }

    /** What keeps the bar up, or null: something of it open. */
    private static String held(Bar bar, ViewGroup layer) {
        if (Boolean.TRUE.equals(Reflect.call(activityContext(layer),
                TaskbarNames.IS_TASKBAR_WINDOW_FULLSCREEN))) {
            return "ZUI's popup or folder";
        }
        for (View root : shownWindows(bar.display, layer.getRootView())) {
            if (!bar.baseline.containsKey(root)) {
                return "a window over it";
            }
        }
        return null;
    }

    /** This process's windows showing on the display, the bar's own left out. */
    private static List<View> shownWindows(int display, View barRoot) {
        List<View> out = new ArrayList<>();
        for (View root : Windows.roots()) {
            if (root != barRoot && root.isAttachedToWindow() && root.isShown()
                    && TaskbarTray.displayIdOf(root) == display) {
                out.add(root);
            }
        }
        return out;
    }

    /** The monitor's own bar: the taskbar drag layer in a window on that display. */
    private static ViewGroup monitorBar(int display) {
        for (View root : Windows.roots()) {
            ViewGroup layer = root.isAttachedToWindow() ? TaskbarTray.dragLayerOf(root) : null;
            if (layer != null && TaskbarTray.displayIdOf(layer) == display) {
                return layer;
            }
        }
        return null;
    }

    /** Where the bar's content goes to be out of the screen: past its own edge. */
    private static float away(ViewGroup layer) {
        return EdgeMath.slide(BarEdge.onTop(layer), layer.getHeight());
    }

    private static void slide(Bar bar, ViewGroup layer, float target) {
        ValueAnimator running = SLIDES.remove(layer);
        if (running != null) {
            running.cancel();
        }
        float from = layer.getTranslationY();
        boolean leaving = target != 0f;
        long ms = Cfg.animations() ? EdgeMath.remainingMs(leaving ? Motion.MEDIUM
                : Motion.SMOOTH_MS, from, target, Math.max(1, layer.getHeight())) : 0L;
        if (ms <= 0L) {
            place(bar, layer, target);
            return;
        }
        ValueAnimator slide = ValueAnimator.ofFloat(from, target);
        slide.setDuration(ms);
        // Leaving, it accelerates away; coming, it lands without a wobble.
        slide.setInterpolator(leaving ? Motion.EXIT : Motion.SPRING_FIRM);
        slide.addUpdateListener(a -> place(bar, layer, (float) a.getAnimatedValue()));
        SLIDES.put(layer, slide);
        slide.start();
    }

    /**
     * The bar's content at {@code y}, the catcher kept over the window. A window's root is not
     * moved for touches by its own translation, only for drawing; a drag layer inside another
     * root is, so there the catcher is moved back by as much.
     */
    private static void place(Bar bar, ViewGroup layer, float y) {
        layer.setTranslationY(y);
        View catcher = bar.catcher;
        if (catcher != null) {
            catcher.setTranslationY(layer.getRootView() == layer ? 0f : -y);
        }
    }

    private static void addCatcher(Bar bar, ViewGroup layer) {
        if (bar.catcher != null && bar.catcher.getParent() == layer) {
            return;
        }
        ViewGroup.LayoutParams lp = TaskbarTray.dragLayerParams(layer, null);
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            L.w("bar auto-hide: the bar's layout params are not reproducible - it cannot feel "
                    + "the pointer, so it stays");
            return;
        }
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        Catcher catcher = new Catcher(layer.getContext(), bar);
        bar.catcher = catcher;
        // Last, so on top: the first to see the pointer, and passing it on while the bar shows.
        layer.addView(catcher, lp);
    }

    /** The pointer over the bar's window; true when the catcher keeps it. */
    private static boolean hover(Bar bar, MotionEvent event) {
        if (!bar.engaged) {
            return false;
        }
        int action = event.getActionMasked();
        if (!bar.revealed) {
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                MAIN.removeCallbacks(bar.dwell);
            } else if (!MAIN.hasCallbacks(bar.dwell)) {
                MAIN.postDelayed(bar.dwell, DWELL_MS);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            bar.hovered = false;
            later(bar, bar.leave, LEAVE_MS);
        } else {
            bar.hovered = true;
            MAIN.removeCallbacks(bar.leave);
        }
        return false;
    }

    /** A touch on the bar's window; true when the catcher keeps it. */
    private static boolean touch(Bar bar, MotionEvent event) {
        if (!bar.engaged) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            boolean finger = !event.isFromSource(InputDevice.SOURCE_MOUSE);
            if (!bar.revealed) {
                reveal(bar, !finger);
                if (finger) {
                    later(bar, bar.leave, TOUCH_MS);
                }
                return true;
            }
            if (finger) {
                // A finger on the bar keeps it a while longer; a mouse has its hover for that.
                later(bar, bar.leave, TOUCH_MS);
            }
        }
        return !bar.revealed;
    }

    private static void later(Bar bar, Runnable what, long ms) {
        MAIN.removeCallbacks(what);
        MAIN.postDelayed(what, ms);
    }

    /**
     * Right after ZUI set the bar's touchable region: only the strip at its edge while the bar is
     * away. A window ZUI made full screen for a popup or a drag brings the bar back first.
     */
    private static void edgeOnly(Object controller, Object info) {
        if (BARS.isEmpty()) {
            return;
        }
        Object ctx = Reflect.call(controller, TaskbarNames.GET_CONTEXT);
        if (!(ctx instanceof Context) || ((Context) ctx).getDisplay() == null) {
            return;
        }
        Bar bar = BARS.get(((Context) ctx).getDisplay().getDisplayId());
        ViewGroup layer = bar == null ? null : bar.layer();
        if (layer == null || !bar.engaged || bar.revealed
                || Reflect.call(ctx, TaskbarNames.GET_DRAG_LAYER) != layer) {
            return;
        }
        if (Boolean.TRUE.equals(Reflect.call(ctx, TaskbarNames.IS_TASKBAR_WINDOW_FULLSCREEN))) {
            MAIN.post(() -> safely(() -> {
                reveal(bar, false);
                later(bar, bar.leave, LEAVE_MS);
            }));
            return;
        }
        Object region = Reflect.field(info, "touchableRegion");
        if (!(region instanceof Region)) {
            return;
        }
        View root = layer.getRootView();
        int[] strip = EdgeMath.strip(BarEdge.onTop(layer), root.getWidth(), root.getHeight(),
                STRIP_PX);
        ((Region) region).set(strip[0], strip[1], strip[2], strip[3]);
        Reflect.call(info, "setTouchableInsets", TOUCHABLE_INSETS_REGION);
        ValueAnimator running = SLIDES.get(layer);
        if (layer.getTranslationY() != away(layer)
                && (running == null || !running.isRunning())) {
            // ZUI changed the window's height while the bar was away: still all the way out.
            place(bar, layer, away(layer));
        }
    }

    /**
     * ZUI's game mode on the bar: while on, the bar reports 1 px to apps and the full-screen app
     * takes the screen under it. ZUI's exit does not tell apps, so its insets are given again.
     */
    private static void gameMode(ViewGroup layer, boolean on) {
        Object ctx = activityContext(layer);
        Object controllers = Reflect.field(ctx, TaskbarNames.M_CONTROLLERS);
        Object insets = Reflect.field(controllers, TaskbarNames.TASKBAR_INSETS_CONTROLLER);
        if (on) {
            Reflect.call(ctx, TaskbarNames.ENTER_GAME_MODE);
        } else {
            Reflect.call(ctx, TaskbarNames.EXIT_GAME_MODE);
            Reflect.call(insets,
                    TaskbarNames.ON_TASKBAR_OR_BUBBLEBAR_WINDOW_HEIGHT_OR_INSETS_CHANGED);
        }
        Object now = Reflect.call(insets, TaskbarNames.GET_GAME_MODE);
        if (!Boolean.valueOf(on).equals(now) && L.once("BarAutoHide.gameMode." + on)) {
            L.w("bar auto-hide: ZUI's game mode not " + (on ? "on" : "off") + " (reads " + now
                    + ") - apps keep " + (on ? "clear of the bar's room" : "the bar's room"));
        }
    }

    /** ZUI's taskbar context the bar was made with, through any wrapper. */
    private static Object activityContext(View layer) {
        Context ctx = layer.getContext();
        for (int i = 0; i < 4 && ctx instanceof ContextWrapper; i++) {
            if (ctx.getClass().getName().contains("TaskbarActivityContext")) {
                return ctx;
            }
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return layer.getContext();
    }

    /** For the probe: each bar out of a full-screen window's way, and how it is. */
    static String describe() {
        if (BARS.isEmpty()) {
            return "no bar out of the way";
        }
        StringBuilder out = new StringBuilder();
        for (Bar bar : BARS.values()) {
            ViewGroup layer = bar.layer();
            Object insets = layer == null ? null : Reflect.field(Reflect.field(
                    activityContext(layer), TaskbarNames.M_CONTROLLERS),
                    TaskbarNames.TASKBAR_INSETS_CONTROLLER);
            out.append("display ").append(bar.display).append(": ")
                    .append(layer == null ? "bar gone" : bar.revealed ? "showing" : "away")
                    .append(bar.hovered ? ", pointer on it" : "")
                    .append(bar.held != null ? ", held by " + bar.held : "")
                    .append(", ZUI's game mode ")
                    .append(Reflect.call(insets, TaskbarNames.GET_GAME_MODE)).append("; ");
        }
        return out.toString();
    }

    /** One display's bar, while a full-screen window is in front there. */
    private static final class Bar {
        final int display;
        WeakReference<ViewGroup> layer;
        boolean engaged;
        boolean revealed;
        boolean hovered;
        String held;
        View catcher;
        /** The windows showing when it went: always there, so they hold nothing up. */
        final Map<View, Boolean> baseline = new WeakHashMap<>();
        final Runnable dwell = () -> safely(() -> reveal(this, true));
        final Runnable leave = () -> safely(() -> leave(this));

        Bar(int display) {
            this.display = display;
        }

        ViewGroup layer() {
            ViewGroup v = layer == null ? null : layer.get();
            return v != null && v.isAttachedToWindow() ? v : null;
        }

        void cancelTimers() {
            MAIN.removeCallbacks(dwell);
            MAIN.removeCallbacks(leave);
        }
    }

    /** Over the bar's whole window: feels the pointer, keeps it only while the bar is away. */
    private static final class Catcher extends View {
        private final Bar mBar;

        Catcher(Context ctx, Bar bar) {
            super(ctx);
            mBar = bar;
            setWillNotDraw(true);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            try {
                return hover(mBar, event);
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            try {
                return touch(mBar, event);
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
