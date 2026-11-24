package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.SparseArray;
import android.view.SurfaceControl;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.logic.BarShade;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Whether the app's own top under a see-through title bar on the monitor is light, read from the
 * app's pixels. ZUI samples the screen under its pill for this, but Android's sampling serves the
 * default display only ({@code CompositionSamplingListener.register}: "default display only for
 * now"), so on the monitor ZUI does not, and an app that says nothing of its top - FLOW - got
 * white buttons on its white top.
 *
 * <p>So the strip under the buttons is captured from the window's own surface, ZUI's bar left
 * out ({@code ScreenCapture.captureLayersExcluding} on the decoration's task surface, without its
 * decoration surfaces - as the shell's own {@code ScreenshotUtils} captures a task), at a quarter
 * of its size, and is light when its median luma is above half, ZUI's own line. It is taken once a
 * window settles after ZUI binds its bar, only when the window's place, focus or top changed, and
 * once more a moment after a window is new, when the app has drawn. The capture runs on its own
 * thread; the answer is handed back on the main thread. Any failure leaves the other rules to
 * decide ({@link BarShade}).
 */
final class BarSample {

    /** How long a window rests after ZUI's last bind before its top is read. */
    private static final long SETTLE_MS = 250L;
    /** When a new window is read again, the app having drawn its own top by then. */
    private static final long DRAWN_MS = 1500L;
    private static final float SCALE = 0.25f;
    /** The windows remembered, a few more than fit on a monitor. */
    private static final int KEPT = 48;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler sWorker;
    private static Method sCapture;
    private static boolean sBroken;
    /** Each window's top, light or dark, as last read. Main thread. */
    private static final Map<Integer, Boolean> LIGHT = lru();
    /** What each window looked like when it was last asked for. Main thread. */
    private static final Map<Integer, String> SEEN = lru();
    private static final Map<Integer, Runnable> PENDING = new LinkedHashMap<>();

    private BarSample() {
    }

    private static <V> Map<Integer, V> lru() {
        return new LinkedHashMap<Integer, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, V> eldest) {
                return size() > KEPT;
            }
        };
    }

    /** The window's top as last read: light, dark, or null before it is read. Main thread. */
    static Boolean light(int taskId) {
        return LIGHT.get(taskId);
    }

    /**
     * ZUI bound the see-through bar of a window: its top is read once it settles, when it
     * changed. Main thread.
     *
     * @param width   how much of the bar's end the buttons take, in pixels
     * @param height  the bar's height, in pixels
     * @param changed told the window's id when its top turned out another shade
     */
    static void ask(ClassLoader loader, ActivityManager.RunningTaskInfo task, int width,
            int height, IntConsumer changed) {
        if (sBroken || task == null || width <= 0 || height <= 0) {
            return;
        }
        int id = task.taskId;
        Rect bounds = WindowButtons.taskBounds(task);
        if (bounds == null || bounds.isEmpty()) {
            return;
        }
        String look = bounds.toShortString() + Reflect.field(task, SysUiNames.IS_FOCUSED)
                + describe(task);
        boolean fresh = !SEEN.containsKey(id);
        if (look.equals(SEEN.put(id, look)) && LIGHT.containsKey(id)) {
            return;
        }
        Runnable before = PENDING.remove(id);
        if (before != null) {
            MAIN.removeCallbacks(before);
        }
        Rect strip = new Rect(bounds.width() - Math.min(width, bounds.width()), 0,
                bounds.width(), Math.min(height, bounds.height()));
        Runnable read = () -> {
            PENDING.remove(id);
            read(loader, id, strip, changed, fresh);
        };
        PENDING.put(id, read);
        MAIN.postDelayed(read, SETTLE_MS);
    }

    /** What the app says of its top, so a change of it is read again. */
    private static String describe(ActivityManager.RunningTaskInfo task) {
        Object description = task.taskDescription;
        return description == null ? "" : "/"
                + Reflect.call(description, SysUiNames.GET_SYSTEM_BARS_APPEARANCE) + "/"
                + Reflect.call(description, SysUiNames.GET_STATUS_BAR_COLOR);
    }

    /** The window's surfaces, found on the main thread; the capture made on the worker. */
    private static void read(ClassLoader loader, int id, Rect strip, IntConsumer changed,
            boolean fresh) {
        try {
            Object model = ShellModel.get(loader);
            Object decorations = Reflect.field(model, SysUiNames.M_WINDOW_DECOR_BY_TASK_ID);
            Object decoration = decorations instanceof SparseArray
                    ? ((SparseArray<?>) decorations).get(id) : null;
            Object surface = Reflect.field(decoration, SysUiNames.M_TASK_SURFACE);
            if (!(surface instanceof SurfaceControl) || !((SurfaceControl) surface).isValid()) {
                return;
            }
            List<SurfaceControl> bar = new ArrayList<>();
            Object container = Reflect.field(decoration,
                    SysUiNames.M_DECORATION_CONTAINER_SURFACE);
            if (container instanceof SurfaceControl) {
                bar.add((SurfaceControl) container);
            }
            Object host = Reflect.call(Reflect.field(decoration, SysUiNames.M_VIEW_HOST),
                    SysUiNames.GET_SURFACE_CONTROL);
            if (host instanceof SurfaceControl) {
                bar.add((SurfaceControl) host);
            }
            SurfaceControl[] leftOut = bar.toArray(new SurfaceControl[0]);
            worker().post(() -> capture(id, (SurfaceControl) surface, strip, leftOut, changed));
            if (fresh) {
                MAIN.postDelayed(() -> read(loader, id, strip, changed, false), DRAWN_MS);
            }
        } catch (Throwable t) {
            broken("the window's surfaces", t);
        }
    }

    /** The strip captured and judged; on the worker, the answer handed to the main thread. */
    private static void capture(int id, SurfaceControl surface, Rect strip,
            SurfaceControl[] leftOut, IntConsumer changed) {
        try {
            if (sCapture == null) {
                sCapture = Class.forName(SysUiNames.SCREEN_CAPTURE).getMethod(
                        SysUiNames.CAPTURE_LAYERS_EXCLUDING, SurfaceControl.class, Rect.class,
                        float.class, int.class, SurfaceControl[].class);
            }
            Object shot = sCapture.invoke(null, surface, strip, SCALE, PixelFormat.RGBA_8888,
                    leftOut);
            Object picture = Reflect.call(shot, SysUiNames.AS_BITMAP);
            if (!(picture instanceof Bitmap)) {
                return;
            }
            Bitmap drawn = (Bitmap) picture;
            Bitmap readable = drawn.copy(Bitmap.Config.ARGB_8888, false);
            drawn.recycle();
            Object buffer = Reflect.call(shot, SysUiNames.GET_HARDWARE_BUFFER);
            if (buffer instanceof AutoCloseable) {
                ((AutoCloseable) buffer).close();
            }
            if (readable == null) {
                return;
            }
            int[] pixels = new int[readable.getWidth() * readable.getHeight()];
            readable.getPixels(pixels, 0, readable.getWidth(), 0, 0, readable.getWidth(),
                    readable.getHeight());
            readable.recycle();
            float luma = BarShade.medianLuma(pixels);
            Boolean light = BarShade.light(pixels);
            if (light == null) {
                return;
            }
            MAIN.post(() -> {
                Boolean before = LIGHT.put(id, light);
                if (L.once("BarSample." + id + light)) {
                    L.i("window buttons: task " + id + "'s top under its bar read "
                            + (light ? "light" : "dark") + " (median luma "
                            + Math.round(luma * 100) / 100f + " of " + pixels.length
                            + " pixels)");
                }
                if (!light.equals(before)) {
                    changed.accept(id);
                }
            });
        } catch (Throwable t) {
            broken("the capture", t);
        }
    }

    private static synchronized Handler worker() {
        if (sWorker == null) {
            HandlerThread thread = new HandlerThread("ZuxBarSample",
                    Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            sWorker = new Handler(thread.getLooper());
        }
        return sWorker;
    }

    /** The first failure stops the reading for this run; the other rules decide alone. */
    private static void broken(String what, Throwable t) {
        sBroken = true;
        if (L.once("BarSample.broken")) {
            L.w("window buttons: a window's top not read (" + what + ": " + t + ") - the "
                    + "buttons follow the app's word and the night mode");
        }
    }
}
