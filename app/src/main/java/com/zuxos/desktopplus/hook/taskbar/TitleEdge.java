package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.zuxos.desktopplus.core.FullScreenWire;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.hook.Overlays;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A full-screen window's title bar back while the pointer rests on the screen's top edge, as its
 * taskbar comes back at the bottom one ({@link BarAutoHide}).
 *
 * <p>ZUI brings a hidden title bar back with the status bar a swipe or the pointer shows at the
 * top - and the monitor has no status bar, and our full-screen windows hide nothing themselves.
 * So while a full-screen window is in front, a 2 px strip at the top edge feels the pointer, and
 * SystemUI is asked to show that window's title bar ({@link FullScreenWire#ACTION_REVEAL}); it
 * lays the bar out as ZUI does, over the app, and takes it away once the pointer leaves it. The
 * strip exists only while a full-screen window is in front, and not with the taskbar at the
 * top, whose edge that is.
 */
final class TitleEdge {

    private static final int STRIP_PX = 2;
    /** How long the pointer rests on the edge first: a sweep past it is not. */
    private static final long DWELL_MS = 100L;
    /** While it stays, SystemUI is told again this often, so the bar stays. */
    private static final long AGAIN_MS = 500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Per display, its strip while a full-screen window is in front there. Main thread. */
    private static final Map<Integer, Edge> EDGES = new ConcurrentHashMap<>();

    private TitleEdge() {
    }

    /** Whether a window's root is one of these strips. */
    static boolean isEdge(View root) {
        return root instanceof Edge;
    }

    /** The strip for {@code taskId} in front on {@code display}, or none for -1. */
    static void watch(int display, int taskId) {
        try {
            Edge edge = EDGES.get(display);
            ViewGroup bar = taskId < 0 ? null : BarAutoHide.engagedLayer(display);
            if (bar == null || BarEdge.onTop(bar)) {
                if (edge != null) {
                    EDGES.remove(display);
                    edge.remove();
                }
                return;
            }
            if (edge == null) {
                edge = Edge.add(bar.getContext(), display);
                if (edge == null) {
                    return;
                }
                EDGES.put(display, edge);
            }
            edge.taskId = taskId;
        } catch (Throwable t) {
            L.d("title edge: left as it is (" + t + ")");
        }
    }

    /** The strip on one display, and the full-screen window it stands for. */
    private static final class Edge extends View {
        private final WindowManager mWm;
        private final int mDisplay;
        int taskId = -1;
        private long mLastAsked;
        private final Runnable mDwell = this::ask;

        private Edge(Context ctx, WindowManager wm, int display) {
            super(ctx);
            mWm = wm;
            mDisplay = display;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        static Edge add(Context barCtx, int display) {
            Context ctx = Overlays.windowContext(barCtx);
            WindowManager wm = Overlays.windowManager(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, STRIP_PX,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("ZuxOS Desktop Plus title edge");
            Edge edge = new Edge(ctx, wm, display);
            wm.addView(edge, lp);
            if (L.once("TitleEdge")) {
                L.i("title edge: the top edge brings a full-screen window's title bar back");
            }
            return edge;
        }

        void remove() {
            MAIN.removeCallbacks(mDwell);
            try {
                mWm.removeViewImmediate(this);
            } catch (Throwable t) {
                L.d("title edge: strip already gone (" + t + ")");
            }
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            try {
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                    MAIN.removeCallbacks(mDwell);
                    mLastAsked = 0;
                } else if (mLastAsked == 0) {
                    if (!MAIN.hasCallbacks(mDwell)) {
                        MAIN.postDelayed(mDwell, DWELL_MS);
                    }
                } else if (SystemClock.uptimeMillis() - mLastAsked > AGAIN_MS) {
                    ask();
                }
            } catch (Throwable t) {
                L.d("title edge: the pointer left as it is (" + t + ")");
            }
            return true;
        }

        /** SystemUI asked to show the window's title bar; asked again while the pointer stays. */
        private void ask() {
            try {
                if (taskId < 0 || !isAttachedToWindow()) {
                    return;
                }
                mLastAsked = SystemClock.uptimeMillis();
                getContext().sendBroadcast(new Intent(FullScreenWire.ACTION_REVEAL)
                        .setPackage(FullScreenWire.SYSTEMUI)
                        .putExtra(FullScreenWire.EXTRA_TASK, taskId)
                        .putExtra(FullScreenWire.EXTRA_DISPLAY, mDisplay));
            } catch (Throwable t) {
                L.d("title edge: SystemUI not asked (" + t + ")");
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            MAIN.removeCallbacks(mDwell);
        }
    }
}
