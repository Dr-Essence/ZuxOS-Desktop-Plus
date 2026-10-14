package com.zuxos.desktopplus.hook.drawer;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.FrameMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Threads;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Rearranging ZUX Home's own drawer by dragging in it, as ZUI rearranges its home screen.
 *
 * <p>A drag out of ZUI's drawer leaves the drawer the moment it starts: the home screen opens
 * for it ({@code Workspace.onDragStart} going to {@code SPRING_LOADED}). Here the drawer keeps
 * the drag while it is over the drawer - that one step of ZUI's is held back for it - and a drop
 * target of ours over the drawer ({@code DropTarget}, on ZUI's own drag controller, asked before
 * the home screen) rearranges the drawer under the finger ({@link DrawerReorder}): gaps opening,
 * apps folding into apps and folders, apps from our folders taken out. Let go, ZUI's dragged icon
 * flies into its new place and the drag ends as ZUI ends its own after a drop that animates
 * ({@code deferDragViewCleanupPostAnimation}). Carried up past the drawer's grid, to the top edge,
 * the drag goes on to the home screen through ZUI's own step, as before.
 */
final class DrawerRearrange {

    private static final String WORKSPACE = "com.android.launcher3.Workspace";
    private static final String STATE_MANAGER = "com.android.launcher3.statemanager.StateManager";
    private static final String DROP_TARGET = "com.android.launcher3.DropTarget";
    private static final String STATES = "com.android.launcher3.LauncherState";

    private static Object sSpringLoaded;
    /** The drag the drawer is keeping, or null. Main thread. */
    private static Session sSession;

    private DrawerRearrange() {
    }

    static int install(ClassLoader loader) {
        try {
            Class<?> workspace = Reflect.findClass(WORKSPACE, loader);
            Class<?> states = Reflect.findClass(STATE_MANAGER, loader);
            Class<?> launcherStates = Reflect.findClass(STATES, loader);
            if (workspace == null || states == null || launcherStates == null
                    || Reflect.findClass(DROP_TARGET, loader) == null) {
                L.w("drawer rearrange: ZUI's drag parts not found - drags leave the drawer");
                return 0;
            }
            sSpringLoaded = launcherStates.getField("SPRING_LOADED").get(null);
            int hooked = XposedBridge.hookAllMethods(workspace, "onDragStart",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0) {
                                begin(param.args[0]);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(workspace, "onDragEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    end();
                }
            }).size();
            hooked += XposedBridge.hookAllMethods(states, "goToState", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Session session = sSession;
                    if (session != null && session.keeping && param.args.length > 0
                            && param.args[0] == sSpringLoaded) {
                        // The drawer keeps this drag: the home screen does not open for it.
                        param.setResult(null);
                    }
                }
            }).size();
            L.i("drawer rearrange: drags in ZUX Home's drawer rearrange it x" + hooked);
            return hooked;
        } catch (Throwable t) {
            L.w("drawer rearrange: not installed (" + t + ")");
            return 0;
        }
    }

    /** A drag starting: kept by the drawer when it is one out of ZUX Home's drawer. */
    private static void begin(Object drag) {
        try {
            end();
            if (!Cfg.drawerRearrange()) {
                return;
            }
            Object source = Reflect.field(drag, "dragSource");
            if (!(source instanceof View)) {
                return;
            }
            // The launcher whose drawer the drag came out of: the source's own, not the last one
            // made, which may be the monitor's.
            Object launcher = ZuiFolders.lookup(((View) source).getContext());
            Object apps = Reflect.call(launcher, "getAppsView");
            if (apps != source) {
                // A drag from anywhere but ZUX Home's drawer: not ours to keep, nothing to say.
                return;
            }
            android.view.Display display = ((View) source).getDisplay();
            Object info = Reflect.field(drag, "dragInfo");
            Item folder = ZuiFolderHome.folderDragged(info);
            String key = folder != null ? folder.key() : NativeDrawerHooks.keyOfEntry(info);
            String why = null;
            if (display == null
                    || display.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
                why = "not on the tablet's screen";
            } else if (!Boolean.TRUE.equals(Reflect.call(launcher, "isInAllAppsState"))) {
                // ZUI's own question (Launcher.isInAllAppsState). Its isInState is a default
                // method of an interface, which a search of the classes never finds.
                why = "the drawer is not open";
            } else if (Boolean.TRUE.equals(Reflect.call(apps, "isSearching"))) {
                why = "a search is showing";
            } else if (key == null) {
                why = "not an app or folder of the drawer";
            }
            Object grid = why == null ? Reflect.call(apps, "getActiveRecyclerView") : null;
            DrawerReorder reorder = grid instanceof ViewGroup
                    ? DrawerReorder.over((ViewGroup) grid, key) : null;
            Object controller = Reflect.call(launcher, "getDragController");
            if (why == null && (reorder == null || controller == null)) {
                why = reorder == null ? "its place in the drawer not found"
                        : "ZUI's drag controller not found";
            }
            if (why != null) {
                L.i("drawer rearrange: a drag of " + key + " left to ZUI - " + why);
                return;
            }
            ClassLoader loader = source.getClass().getClassLoader();
            Session session = new Session(launcher, (View) apps, (ViewGroup) grid, controller,
                    reorder, key);
            session.target = Proxy.newProxyInstance(loader,
                    new Class<?>[]{Class.forName(DROP_TARGET, false, loader)},
                    (proxy, method, args) -> session.call(proxy, method.getName(), args,
                            method.getReturnType()));
            Reflect.call(controller, "addDropTarget", session.target);
            sSession = session;
            session.frames = FrameTrace.start(launcher);
            L.i("drawer rearrange: the drawer keeps the drag of " + key);
        } catch (Throwable t) {
            sSession = null;
            L.w("drawer rearrange: a drag left to ZUI (" + t + ")");
        }
    }

    /** The drag is over: our drop target goes with it. */
    private static void end() {
        Session session = sSession;
        sSession = null;
        if (session != null) {
            boolean kept = session.keeping;
            session.keeping = false;
            Reflect.call(session.controller, "removeDropTarget", session.target);
            if (!session.dropped) {
                session.reorder.cancel();
            }
            L.i("drawer rearrange: the drag of " + session.key + " ended - "
                    + (session.dropped ? session.reorder.outcome()
                    : kept ? "let go of nowhere in the drawer, or cancelled - no change"
                    : "on the home screen's side, as ZUI has it"));
            if (session.frames != null) {
                L.i("drawer rearrange: trace: " + session.frames.stop());
            }
        }
    }

    /** One drag kept by the drawer. */
    private static final class Session {
        final Object launcher;
        final View apps;
        final ViewGroup grid;
        final Object controller;
        final DrawerReorder reorder;
        final String key;
        Object target;
        /** The drawer keeps it; false once it has gone on to the home screen. */
        boolean keeping = true;
        boolean dropped;
        /** The drag's frames, for the log; null where they cannot be read. */
        FrameTrace frames;

        Session(Object launcher, View apps, ViewGroup grid, Object controller,
                DrawerReorder reorder, String key) {
            this.launcher = launcher;
            this.apps = apps;
            this.grid = grid;
            this.controller = controller;
            this.reorder = reorder;
            this.key = key;
        }

        /** Our {@code DropTarget}. */
        Object call(Object proxy, String name, Object[] args, Class<?> returns) {
            try {
                switch (name) {
                    case "isDropEnabled":
                        return keeping;
                    case "acceptDrop":
                        return keeping;
                    case "getHitRectRelativeToDragLayer":
                        hitRect((Rect) args[0]);
                        return null;
                    case "onDragOver":
                        over(args[0]);
                        return null;
                    case "onDragExit":
                        // ZUI's drop leaves the target first (DragController.drop: onDragExit,
                        // then acceptDrop and onDrop), with the drag already complete: the
                        // gap stays for the drop. Any other exit puts the drawer back.
                        if (!Boolean.TRUE.equals(Reflect.field(args[0], "dragComplete"))) {
                            reorder.cancel();
                        }
                        return null;
                    case "getDropView":
                        // The interface's own answer is "this", a view; ours is ZUI's drag
                        // layer, so the drag's place reaches us as ZUI's layer has it.
                        return dragLayer();
                    case "onDrop":
                        drop(args[0]);
                        return null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return args != null && proxy == args[0];
                    case "toString":
                        return "DrawerRearrange.target";
                    default:
                        break;
                }
            } catch (Throwable t) {
                L.d("drawer rearrange: " + name + " failed (" + t + ")");
            }
            return returns == boolean.class ? Boolean.FALSE : null;
        }

        /** The whole drawer, where ZUI's drag layer is. */
        private void hitRect(Rect out) {
            int[] at = new int[2];
            int[] layer = new int[2];
            apps.getLocationOnScreen(at);
            dragLayer().getLocationOnScreen(layer);
            out.set(at[0] - layer[0], at[1] - layer[1],
                    at[0] - layer[0] + apps.getWidth(), at[1] - layer[1] + apps.getHeight());
        }

        private View dragLayer() {
            Object layer = Reflect.call(launcher, "getDragLayer");
            return layer instanceof View ? (View) layer : apps;
        }

        /** The drag moved over the drawer: rearranged under it, or gone on above the grid. */
        private void over(Object drag) {
            if (!keeping) {
                return;
            }
            int[] layer = new int[2];
            dragLayer().getLocationOnScreen(layer);
            float sx = layer[0] + intField(drag, "x");
            float sy = layer[1] + intField(drag, "y");
            int[] grid = new int[2];
            this.grid.getLocationOnScreen(grid);
            if (sy < grid[1] - Ui.dp(apps.getContext(), 8)) {
                toHome();
                return;
            }
            reorder.moveToScreen(sx, sy);
        }

        /** Up past the grid: on to the home screen, through ZUI's own step. */
        private void toHome() {
            keeping = false;
            reorder.cancel();
            Reflect.call(controller, "removeDropTarget", target);
            Object states = Reflect.call(launcher, "getStateManager");
            Reflect.call(states, "goToState", sSpringLoaded);
            L.i("drawer rearrange: the drag of " + key + " goes on to the home screen");
        }

        /** Let go over the drawer: rearranged, and ZUI's icon flies into its place. */
        private void drop(Object drag) {
            dropped = true;
            View dragged = (View) Reflect.field(drag, "dragView");
            boolean[] landing = new boolean[1];
            // ZUI ends the drag itself unless the drop animates: ours does, so it is ours to end.
            setBoolean(drag, "deferDragViewCleanupPostAnimation", dragged != null);
            try {
                reorder.drop((cell, into) -> {
                    if (!landing[0]) {
                        landing[0] = true;
                        land(dragged, cell, into);
                    }
                });
            } catch (Throwable t) {
                // ZUI ends it after all, at once.
                landing[0] = true;
                setBoolean(drag, "deferDragViewCleanupPostAnimation", false);
                L.w("drawer rearrange: the drop not kept (" + t + ")");
                return;
            }
            if (dragged != null) {
                // Ended however the drawer's next frame goes.
                dragged.postDelayed(() -> {
                    if (!landing[0]) {
                        landing[0] = true;
                        land(dragged, null, false);
                    }
                }, 1000L);
            }
        }

        private static boolean sSaidNoLanding;

        private static void noLanding(String why) {
            if (!sSaidNoLanding) {
                sSaidNoLanding = true;
                L.w("drawer rearrange: a drop lands at once - " + why);
            }
        }

        /**
         * ZUI's dragged icon onto the cell it now has, or into the folder it went in, by ZUI's own
         * drop animation ({@code DragLayer.animateViewIntoPosition}, as ZUI's home screen lands a
         * drop): it stops the picture's own animation, moves and scales it onto the icon, and at
         * its end ZUI removes it and ends the drag ({@code clearAnimatedView} - the drag's
         * {@code deferDragViewCleanupPostAnimation}). Animated by us instead, the picture never
         * moved, and swapped for the cell in one frame (video 14:17, owner: "bigger with no
         * animation"). Where ZUI's animation is not found, the drag ends at once.
         */
        private void land(View dragged, View cell, boolean into) {
            if (dragged == null) {
                return;
            }
            long start = android.os.SystemClock.uptimeMillis();
            Runnable shown = () -> {
                if (cell != null && !into) {
                    cell.setAlpha(1f);
                }
                L.d("drawer rearrange: the icon landed after "
                        + (android.os.SystemClock.uptimeMillis() - start) + " ms"
                        + (into ? ", into a folder" : ""));
            };
            View icon = cell == null ? null : iconIn(cell);
            if (icon != null && zuiLanding(dragged, icon, into, shown)) {
                return;
            }
            shown.run();
            Reflect.call(dragged, "remove");
            Reflect.call(controller, "callOnDragEnd");
        }

        /** ZUI's own landing onto {@code icon}; false where it cannot be had. */
        private boolean zuiLanding(View dragged, View icon, boolean into, Runnable shown) {
            try {
                View layer = dragLayer();
                java.lang.reflect.Method map = layer.getClass().getMethod(
                        "getDescendantCoordRelativeToSelf", View.class, float[].class);
                java.lang.reflect.Method animate = null;
                for (java.lang.reflect.Method m : layer.getClass().getMethods()) {
                    if (m.getName().equals("animateViewIntoPosition")
                            && m.getParameterCount() == 10
                            && m.getParameterTypes()[1] == int.class) {
                        animate = m;
                        break;
                    }
                }
                Object blurValue = Reflect.call(dragged, "getBlurSizeOutline");
                if (animate == null || !(blurValue instanceof Integer)) {
                    noLanding("ZUI's drop animation not found");
                    return false;
                }
                int blur = (Integer) blurValue;
                float[] at = {0f, 0f};
                float inLayer = (Float) map.invoke(layer, icon, at);
                Rect bounds = new Rect();
                Reflect.call(icon, "getWorkspaceVisualDragBounds", bounds);
                if (bounds.isEmpty()) {
                    bounds.set(0, 0, icon.getWidth(), icon.getHeight());
                }
                int width = dragged.getMeasuredWidth();
                int height = dragged.getMeasuredHeight();
                // As ZUI works out where a dropped icon goes (animateViewIntoPosition(DragView,
                // View, ...)): the picture, less its outline, scaled onto the icon's own bounds.
                float scale = bounds.width() / (float) Math.max(1, width - blur) * inLayer;
                if (into) {
                    scale *= 0.35f;
                }
                float left = at[0] + bounds.left * inLayer;
                float top = at[1] + bounds.top * inLayer;
                if (into) {
                    // Into the folder's middle, small.
                    left += bounds.width() * inLayer * (1f - 0.35f) / 2f;
                    top += bounds.height() * inLayer * (1f - 0.35f) / 2f;
                }
                int x = Math.round(left - blur * scale / 2f - width * (1f - scale) / 2f);
                int y = Math.round(top - blur * scale / 2f - height * (1f - scale) / 2f);
                animate.invoke(layer, dragged, x, y, into ? 0f : 1f, scale, scale, shown, 0, -1,
                        null);
                return true;
            } catch (Throwable t) {
                noLanding(String.valueOf(t));
                return false;
            }
        }

        /** The icon drawn in a drawer cell: the app's own, or the folder icon in our cell. */
        private View iconIn(View cell) {
            if (ZuiFolders.folderOfIcon(cell) == null && cell instanceof ViewGroup
                    && ((ViewGroup) cell).getChildCount() > 0
                    && ZuiFolders.folderOfIcon(((ViewGroup) cell).getChildAt(0)) != null) {
                return ((ViewGroup) cell).getChildAt(0);
            }
            return cell;
        }

        private static int intField(Object o, String name) {
            Object value = Reflect.field(o, name);
            return value instanceof Integer ? (Integer) value : 0;
        }

        private static void setBoolean(Object o, String name, boolean value) {
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.setBoolean(o, value);
                    return;
                } catch (NoSuchFieldException e) {
                    // Up the hierarchy.
                } catch (Throwable t) {
                    return;
                }
            }
        }
    }

    /**
     * Read-only trace of the frames of ZUX Home's window while the drawer keeps a drag: how many
     * frames the screen went without a new one (gaps between frames' vsyncs), and where the
     * slowest frame's time went. The owner's recordings show frames up to 35 ms apart during
     * drawer drags on the 144 Hz screen (23:42); this says which part of drawing takes them.
     * Listened to only while the drag lasts, on a thread of its own.
     */
    private static final class FrameTrace implements Window.OnFrameMetricsAvailableListener {

        /** A frame of the 144 Hz screen, and a half more: a gap past this skipped a frame. */
        private static final long GAP_NS = 10_400_000L;
        private static Handler sHandler;

        private final Window mWindow;
        private int mFrames;
        private int mGaps;
        private long mLastVsync;
        private long mLongestGap;
        private long mSlowest;
        private String mSlowestParts = "";

        private FrameTrace(Window window) {
            mWindow = window;
        }

        static FrameTrace start(Object launcher) {
            try {
                if (!(launcher instanceof Activity)) {
                    return null;
                }
                Window window = ((Activity) launcher).getWindow();
                if (sHandler == null) {
                    HandlerThread thread = Threads.guarded(
                            new HandlerThread("zux-desktop-plus-frames"));
                    thread.setDaemon(true);
                    thread.start();
                    sHandler = new Handler(thread.getLooper());
                }
                FrameTrace trace = new FrameTrace(window);
                window.addOnFrameMetricsAvailableListener(trace, sHandler);
                return trace;
            } catch (Throwable t) {
                L.d("drawer rearrange: frames not traced (" + t + ")");
                return null;
            }
        }

        @Override
        public void onFrameMetricsAvailable(Window window, FrameMetrics metrics, int dropped) {
            long vsync = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
            long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            synchronized (this) {
                mFrames++;
                long gap = mLastVsync > 0 ? vsync - mLastVsync : 0;
                mLastVsync = vsync;
                if (gap > GAP_NS) {
                    mGaps++;
                    mLongestGap = Math.max(mLongestGap, gap);
                }
                if (total > mSlowest) {
                    mSlowest = total;
                    mSlowestParts = "input " + ms(metrics.getMetric(
                            FrameMetrics.INPUT_HANDLING_DURATION))
                            + ", animation " + ms(metrics.getMetric(
                            FrameMetrics.ANIMATION_DURATION))
                            + ", layout " + ms(metrics.getMetric(
                            FrameMetrics.LAYOUT_MEASURE_DURATION))
                            + ", draw " + ms(metrics.getMetric(FrameMetrics.DRAW_DURATION))
                            + ", sync " + ms(metrics.getMetric(FrameMetrics.SYNC_DURATION))
                            + ", gpu " + ms(metrics.getMetric(FrameMetrics.GPU_DURATION));
                }
            }
        }

        private static String ms(long ns) {
            return (ns / 100_000L) / 10f + " ms";
        }

        /** Stops listening; what the drag's frames were. */
        String stop() {
            try {
                mWindow.removeOnFrameMetricsAvailableListener(this);
            } catch (Throwable t) {
                L.d("drawer rearrange: frame trace not removed (" + t + ")");
            }
            synchronized (this) {
                return mFrames + " frames, " + mGaps + " gaps of a skipped frame or more, "
                        + "longest " + ms(mLongestGap) + "; slowest frame " + ms(mSlowest)
                        + " (" + mSlowestParts + ")";
            }
        }
    }
}
