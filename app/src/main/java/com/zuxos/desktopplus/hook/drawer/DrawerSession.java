package com.zuxos.desktopplus.hook.drawer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.FrameMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Threads;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.taskbar.TaskbarDrop;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Field;

/**
 * One drag a drawer keeps ({@link DrawerRearrange}): our drop target on ZUI's drag controller,
 * which rearranges the drawer under the finger ({@link DrawerReorder}), pins to the taskbar what
 * is let go over it, and opens a folder of ours that a dragged app rests on, ZUI's way.
 */
final class DrawerSession {

    /** In the taskbar's drawer, rather than ZUX Home's. */
    final boolean taskbar;
    /** ZUX Home's {@code Launcher}, or the taskbar drawer's {@code TaskbarOverlayContext}. */
    final Object activity;
    final View apps;
    /** The drawer's panel as it is seen; leaving it, the taskbar drawer's drag goes on as ZUI's. */
    final View sheet;
    final View dragLayer;
    final ViewGroup grid;
    final Object controller;
    final DrawerReorder reorder;
    final String key;
    final int display;
    /** ZUI's drag object, as it was when the drawer kept the drag. */
    private final Object drag;
    Object target;
    /** The drawer keeps it; false once it has gone on to the home screen or as ZUI's. */
    boolean keeping = true;
    boolean dropped;
    /** One of our folders opened for the drag: ZUI's folder takes it from there. */
    boolean folderOpened;
    /** Over the taskbar, where it is pinned when let go. */
    private boolean onBar;
    private float lastX = -1f;
    private final Drawable icon;
    /** The apps chosen with the dragged one ({@link DrawerSelect}), going where it goes. */
    private final java.util.List<String> carried;
    /** The drag's frames, for the log; null where they cannot be read. */
    private FrameTrace frames;
    /** Time spent in our own handling of the drag's moves, for the log. */
    private long oursNs;
    private long oursLongestNs;
    private int oursMoves;

    DrawerSession(boolean taskbar, Object activity, View apps, View sheet, View dragLayer,
            ViewGroup grid, Object controller, DrawerReorder reorder, String key, Object drag,
            int display) {
        this.taskbar = taskbar;
        this.activity = activity;
        this.apps = apps;
        this.sheet = sheet;
        this.dragLayer = dragLayer;
        this.grid = grid;
        this.controller = controller;
        this.reorder = reorder;
        this.key = key;
        this.drag = drag;
        this.display = display;
        this.icon = iconOf(Reflect.field(drag, "originalView"));
        this.carried = DrawerSelect.carriedWith(key);
        reorder.carry(carried);
    }

    void startTrace() {
        frames = FrameTrace.start(activity);
    }

    /** Our {@code DropTarget}. */
    Object call(Object proxy, String name, Object[] args, Class<?> returns) {
        try {
            switch (name) {
                case "isDropEnabled":
                case "acceptDrop":
                    return keeping;
                case "getHitRectRelativeToDragLayer":
                    hitRect((Rect) args[0]);
                    return null;
                case "onDragOver": {
                    long began = System.nanoTime();
                    over(args[0]);
                    long took = System.nanoTime() - began;
                    oursNs += took;
                    oursLongestNs = Math.max(oursLongestNs, took);
                    oursMoves++;
                    return null;
                }
                case "onDragExit":
                    // ZUI's drop leaves the target first (DragController.drop: onDragExit,
                    // then acceptDrop and onDrop), with the drag already complete: the gap and
                    // the bar's place stay for the drop. Any other exit puts the drawer back.
                    if (!Boolean.TRUE.equals(Reflect.field(args[0], "dragComplete"))) {
                        reorder.cancel();
                        offBar();
                    }
                    return null;
                case "getDropView":
                    // The interface's own answer is "this", a view; ours is ZUI's drag layer, so
                    // the drag's place reaches us as ZUI's layer has it.
                    return dragLayer;
                case "onDrop":
                    drop(args[0]);
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && proxy == args[0];
                case "toString":
                    return "DrawerSession.target";
                default:
                    break;
            }
        } catch (Throwable t) {
            L.d("drawer rearrange: " + name + " failed (" + t + ")");
        }
        return returns == boolean.class ? Boolean.FALSE : null;
    }

    /**
     * Where the drag is ours: ZUX Home's whole drawer, or for the taskbar's drawer its whole
     * window - leaving its panel is seen there - and the taskbar, wherever it is.
     */
    private void hitRect(Rect out) {
        int[] at = new int[2];
        int[] layer = new int[2];
        View area = taskbar ? dragLayer : apps;
        area.getLocationOnScreen(at);
        dragLayer.getLocationOnScreen(layer);
        out.set(at[0] - layer[0], at[1] - layer[1],
                at[0] - layer[0] + area.getWidth(), at[1] - layer[1] + area.getHeight());
        RectF bar = TaskbarDrop.barOnScreen(display);
        if (bar != null) {
            out.union(Math.round(bar.left) - layer[0], Math.round(bar.top) - layer[1],
                    Math.round(bar.right) - layer[0], Math.round(bar.bottom) - layer[1]);
        }
    }

    /** The drag moved: rearranged under it, over the bar, or gone on. */
    private void over(Object drag) {
        if (!keeping) {
            return;
        }
        int[] layer = new int[2];
        dragLayer.getLocationOnScreen(layer);
        float sx = layer[0] + intField(drag, "x");
        float sy = layer[1] + intField(drag, "y");
        RectF bar = TaskbarDrop.barOnScreen(display);
        if (bar != null && sx >= bar.left && sx <= bar.right && sy >= bar.top
                && sy <= bar.bottom + 1f) {
            if (!onBar) {
                onBar = true;
                reorder.cancel();
            }
            lastX = sx;
            TaskbarDrop.hintAt(display, sx, taskbar ? null : icon);
            return;
        }
        offBar();
        if (taskbar) {
            int[] at = new int[2];
            sheet.getLocationOnScreen(at);
            if (sx < at[0] || sx > at[0] + sheet.getWidth() || sy < at[1]
                    || sy > at[1] + sheet.getHeight()) {
                toSystemDrag();
                return;
            }
        } else {
            int[] at = new int[2];
            grid.getLocationOnScreen(at);
            if (sy < at[1] - Ui.dp(apps.getContext(), 8)) {
                toHome();
                return;
            }
        }
        reorder.moveToScreen(sx, sy);
    }

    private void offBar() {
        if (onBar) {
            onBar = false;
            TaskbarDrop.hintAt(display, -1f);
        }
    }

    /** ZUX Home's drawer: up past the grid, on to the home screen, through ZUI's own step. */
    private void toHome() {
        keeping = false;
        reorder.cancel();
        Reflect.call(controller, "removeDropTarget", target);
        Object states = Reflect.call(activity, "getStateManager");
        Reflect.call(states, "goToState", DrawerRearrange.sSpringLoaded);
        L.i("drawer rearrange: the drag of " + key + " goes on to the home screen");
    }

    /**
     * The taskbar's drawer: out of its panel, the drag goes on as ZUI's own - the floating window
     * and split screen - from the same icon, and the drawer closes, as ZUI does at once.
     */
    private void toSystemDrag() {
        keeping = false;
        reorder.cancel();
        Reflect.call(controller, "removeDropTarget", target);
        Object original = Reflect.field(drag, "originalView");
        if (original instanceof View && systemDrag(controller, (View) original)) {
            L.i("drawer rearrange: the drag of " + key + " left the taskbar's drawer - ZUI's "
                    + "floating window and split screen");
        }
    }

    /** ZUI's system drag of the taskbar drawer's icon, as it starts it itself; false if not. */
    static boolean systemDrag(Object controller, View original) {
        try {
            Reflect.call(controller, "setDisallowGlobalDrag", false);
            Object dragObject = Reflect.field(controller, "mDragObject");
            setBoolean(dragObject, "startSystemDrag", true);
            DrawerRearrange.sSystemDrag.invoke(controller, original);
            return true;
        } catch (Throwable t) {
            L.w("drawer rearrange: ZUI's system drag not started (" + t + ")");
            return false;
        }
    }

    /** A dragged app has rested on one of our folders: ZUI's folder opens for it. */
    boolean springOpen(View cell) {
        try {
            if (!keeping || taskbar) {
                return false;
            }
            Object folder = Reflect.call(iconIn(cell), "getFolder");
            if (folder == null || Boolean.TRUE.equals(Reflect.call(folder, "isOpen"))
                    || Boolean.TRUE.equals(Reflect.call(folder, "isFull"))) {
                return false;
            }
            folderOpened = true;
            // ZUI's own spring open (FolderIcon's open alarm): the folder opens with an empty
            // place for the app, becomes the drag's drop target and moves its apps aside under
            // the finger; let go, ZUI puts the app there (Folder.onDrop) and our model keeps it
            // (ZuiFolderWrites).
            Reflect.call(folder, "beginExternalDrag");
            L.i("drawer rearrange: a folder opened under the drag of " + key);
            return true;
        } catch (Throwable t) {
            L.d("drawer rearrange: the folder stays closed (" + t + ")");
            return false;
        }
    }

    /** Let go: on the bar, pinned; over the drawer, rearranged and ZUI's icon flies in. */
    private void drop(Object drag) {
        dropped = true;
        if (onBar) {
            pin(drag);
            return;
        }
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

    private String mOutcome;

    /** Let go over the taskbar: pinned there, and ZUI ends the drag at once. */
    private void pin(Object drag) {
        TaskbarDrop.hintAt(display, -1f);
        onBar = false;
        setBoolean(drag, "deferDragViewCleanupPostAnimation", false);
        Object info = Reflect.field(drag, "dragInfo");
        Item folder = ZuiFolderHome.folderDragged(info);
        Context ctx = apps.getContext();
        Item item = folder != null ? folder : IconInfo.itemOf(ctx, info);
        // The apps chosen with it after it, in their order: each pinned where it was let go,
        // the last first, so they end up in order.
        for (int i = carried.size() - 1; i >= 0; i--) {
            String other = carried.get(i);
            Item app = com.zuxos.desktopplus.logic.DrawerArrange.appOf(other,
                    NativeDrawerHooks.labelOf(other));
            if (app != null) {
                TaskbarDrop.pinAt(display, app, lastX);
            }
        }
        boolean pinned = item != null && TaskbarDrop.pinAt(display, item, lastX);
        mOutcome = (pinned ? "pinned to the taskbar" : "let go on the taskbar, not pinned")
                + (carried.isEmpty() ? "" : ", with " + carried.size() + " chosen");
    }

    /** The drag is over: our drop target goes with it, and the drawer is as it now is. */
    void end() {
        boolean kept = keeping;
        keeping = false;
        Reflect.call(controller, "removeDropTarget", target);
        if (onBar) {
            onBar = false;
            TaskbarDrop.hintAt(display, -1f);
        }
        if (!dropped) {
            reorder.cancel();
        }
        if (taskbar) {
            // As ZUI has it for this drawer again: the next drag is decided afresh.
            Reflect.call(controller, "setDisallowGlobalDrag", false);
        }
        if (!carried.isEmpty()) {
            // The chosen apps went with it: choosing is over.
            DrawerSelect.end();
        }
        String filed = ZuiFolders.sLastFiled;
        ZuiFolders.sLastFiled = null;
        String outcome = mOutcome != null ? mOutcome
                : dropped ? reorder.outcome()
                : folderOpened && filed != null ? "placed in a folder, where it was let go"
                : kept ? "let go of nowhere in the drawer, or cancelled - no change"
                : taskbar ? "gone on as ZUI's" : "on the home screen's side, as ZUI has it";
        L.i("drawer rearrange: the drag of " + key + " ended - " + outcome);
        String ours = "ours " + oursMoves + " moves, " + FrameTrace.ms(oursNs) + " in all, "
                + "longest " + FrameTrace.ms(oursLongestNs);
        L.i("drawer rearrange: trace: " + (frames != null ? frames.stop() + "; " : "") + ours);
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
     * drop): it stops the picture's own animation, moves and scales it onto the icon, and at its
     * end ZUI removes it and ends the drag ({@code clearAnimatedView} - the drag's
     * {@code deferDragViewCleanupPostAnimation}). Animated by us instead, the picture never moved,
     * and swapped for the cell in one frame (video 14:17, owner: "bigger with no animation").
     * Where ZUI's animation is not found - the taskbar drawer's layer has none - the drag ends at
     * once.
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
            View layer = dragLayer;
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
    private static View iconIn(View cell) {
        if (ZuiFolders.folderOfIcon(cell) == null && cell instanceof ViewGroup
                && ((ViewGroup) cell).getChildCount() > 0
                && ZuiFolders.folderOfIcon(((ViewGroup) cell).getChildAt(0)) != null) {
            return ((ViewGroup) cell).getChildAt(0);
        }
        return cell;
    }

    /** A copy of the dragged app's icon, for the bar's hint; null for anything else. */
    private static Drawable iconOf(Object view) {
        Object icon = Reflect.call(view, "getIcon");
        if (!(icon instanceof Drawable)) {
            return null;
        }
        Drawable.ConstantState state = ((Drawable) icon).getConstantState();
        return state != null ? state.newDrawable().mutate() : null;
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

    /**
     * Read-only trace of the frames of ZUX Home's window while the drawer keeps a drag: how many
     * frames the screen went without a new one (gaps between frames' vsyncs), and where the
     * slowest frame's time went. Listened to only while the drag lasts, on a thread of its own.
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

        static String ms(long ns) {
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
