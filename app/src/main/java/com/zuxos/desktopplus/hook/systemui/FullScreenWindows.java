package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Display;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.FullScreenWire;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SystemUI's copy of which windows on the monitor are full screen, as ZUX Home keeps them
 * ({@link FullScreenWire}): their title bars stay hidden ({@link WindowCaptions}) and the
 * window menu says "Exit full screen" for them ({@link FullScreenMenu}).
 *
 * <p>While the pointer rests on the screen's top edge ZUX Home asks for one's title bar back
 * ({@link FullScreenWire#ACTION_REVEAL}): it is laid out as ZUI lays out a maximised window's,
 * over the app, which is not resized, and goes again once the pointer has left it
 * ({@link #hover}), or if the pointer never came down to it.
 *
 * <p>Heard on the shell's own thread, where title bars are laid out: a change lays the window's
 * title bar out again with ZUI's own call, as ZUI does for every visible one on unlock. A
 * full-screen window ZUI's shell sees go back to floating, by its own buttons, is reported to
 * ZUX Home, so its taskbar comes back at once.
 */
final class FullScreenWindows {

    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    /** How long after the pointer leaves a shown title bar it goes. */
    private static final long LEAVE_MS = 600L;
    /** How long a shown title bar waits for the pointer to come down to it. */
    private static final long UNREACHED_MS = 1500L;

    /** The full-screen windows. Written and read on the shell's thread; read by the menu. */
    private static final Set<Integer> MARKED = ConcurrentHashMap.newKeySet();
    /** Those of them seen full screen here: one still on its way there has not left. */
    private static final Set<Integer> SEEN = ConcurrentHashMap.newKeySet();
    /** Those whose title bar is shown for now, from the top edge, and when each goes. */
    private static final java.util.Map<Integer, Runnable> REVEALED = new ConcurrentHashMap<>();
    /** What each decoration's own field said before full screen hid its title bar. */
    private static final java.util.Map<Object, Boolean> HID =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static boolean sListening;
    private static Context sApp;
    /** The shell's own thread, where title bars are laid out and their pointer felt. */
    private static Handler sShell;
    private static ClassLoader sLoader;
    private static Method sRelayout;

    private FullScreenWindows() {
    }

    /** The task's display: a field the public SDK hides, read as the rest of the module does. */
    static int displayOf(Object task) {
        Object display = Reflect.field(task, "displayId");
        return display instanceof Integer ? (Integer) display : Display.DEFAULT_DISPLAY;
    }

    static boolean has(int taskId) {
        return MARKED.contains(taskId);
    }

    /**
     * Whether this window's title bar is hidden for full screen: one ZUX Home made full screen
     * and still is. One that went back to floating here is reported, and keeps its title bar.
     */
    static boolean hides(ActivityManager.RunningTaskInfo task) {
        int id = task.taskId;
        if (!MARKED.contains(id)) {
            return false;
        }
        Object mode = Reflect.call(task, "getWindowingMode");
        if (Integer.valueOf(WINDOWING_MODE_FULLSCREEN).equals(mode)) {
            SEEN.add(id);
            return true;
        }
        if (SEEN.remove(id)) {
            MARKED.remove(id);
            left(id);
        }
        return false;
    }

    /**
     * The decoration's own status-bar field for its relayout: false while its window is full
     * screen, and what it was once that ends - ZUI keeps the field only as the status bar's
     * insets change, which full screen does not.
     */
    static boolean answer(Object decoration, ActivityManager.RunningTaskInfo task,
            java.lang.reflect.Field statusBarVisible) throws IllegalAccessException {
        if (hides(task) && !REVEALED.containsKey(task.taskId)) {
            if (!HID.containsKey(decoration)) {
                HID.put(decoration, statusBarVisible.getBoolean(decoration));
            }
            statusBarVisible.setBoolean(decoration, false);
            return true;
        }
        Boolean before = HID.remove(decoration);
        if (before != null) {
            statusBarVisible.setBoolean(decoration, before);
        }
        return false;
    }

    /**
     * Hears ZUX Home, once, from the shell's first relayout on the monitor: on the shell's own
     * thread, its looper's. Then asks for the state, which ZUX Home also sends whenever it
     * starts.
     */
    static void listen(Object decoration) {
        if (sListening || !Cfg.enabled() || !Cfg.fullScreen()) {
            return;
        }
        sListening = true;
        try {
            Object ctx = Reflect.field(decoration, SysUiNames.M_CONTEXT);
            Looper looper = Looper.myLooper();
            Context app = ctx instanceof Context ? ((Context) ctx).getApplicationContext() : null;
            if (app == null || looper == null) {
                L.w("full screen windows: no context or thread to hear ZUX Home on");
                return;
            }
            sApp = app;
            sLoader = decoration.getClass().getClassLoader();
            sShell = new Handler(looper);
            IntentFilter filter = new IntentFilter(FullScreenWire.ACTION_STATE);
            filter.addAction(FullScreenWire.ACTION_REVEAL);
            app.registerReceiver(new Receiver(), filter, FullScreenWire.SENDER, sShell,
                    Context.RECEIVER_EXPORTED);
            app.sendBroadcast(new Intent(FullScreenWire.ACTION_ASK)
                    .setPackage(FullScreenWire.ZUX_HOME));
            L.i("full screen windows: title bars of full-screen windows on the monitor hidden");
        } catch (Throwable t) {
            L.w("full screen windows: ZUX Home not heard (" + t + ") - title bars stay");
        }
    }

    /**
     * ZUX Home asked to make a window full screen, or end it: the window menu's row and the
     * title bar's button alike.
     */
    static void askToggle(Context ctx, int taskId, int display) {
        ctx.sendBroadcast(new Intent(FullScreenWire.ACTION_TOGGLE)
                .setPackage(FullScreenWire.ZUX_HOME)
                .putExtra(FullScreenWire.EXTRA_TASK, taskId)
                .putExtra(FullScreenWire.EXTRA_DISPLAY, display));
    }

    /**
     * A full-screen window's title bar shown, from the top edge; asked again while the pointer
     * stays there, which only puts off its going.
     */
    private static void reveal(int taskId) {
        if (!MARKED.contains(taskId) || sShell == null) {
            return;
        }
        Runnable going = REVEALED.get(taskId);
        if (going == null) {
            going = () -> conceal(taskId);
            REVEALED.put(taskId, going);
            relayout(java.util.Collections.singleton(taskId));
            if (L.once("FullScreenWindows.reveal")) {
                L.i("full screen windows: a full-screen window's title bar shown from the top "
                        + "edge");
            }
        }
        sShell.removeCallbacks(going);
        sShell.postDelayed(going, UNREACHED_MS);
    }

    /**
     * The pointer on a title bar or leaving it ({@link WindowButtons}): a shown full-screen
     * window's bar stays while it is there, and goes a moment after it leaves.
     */
    static void hover(int taskId, boolean on) {
        Runnable going = REVEALED.get(taskId);
        if (going == null || sShell == null) {
            return;
        }
        sShell.removeCallbacks(going);
        if (!on) {
            sShell.postDelayed(going, LEAVE_MS);
        }
    }

    private static void conceal(int taskId) {
        if (REVEALED.remove(taskId) != null && MARKED.contains(taskId)) {
            relayout(java.util.Collections.singleton(taskId));
        }
    }

    /** ZUX Home told a window went back to floating here, so its taskbar comes back. */
    private static void left(int taskId) {
        try {
            Context ctx = sApp;
            if (ctx != null) {
                ctx.sendBroadcast(new Intent(FullScreenWire.ACTION_LEFT)
                        .setPackage(FullScreenWire.ZUX_HOME)
                        .putExtra(FullScreenWire.EXTRA_TASK, taskId));
            }
            L.i("full screen windows: task " + taskId + " floats again - ZUX Home told");
        } catch (Throwable t) {
            L.d("full screen windows: ZUX Home not told (" + t + ")");
        }
    }

    /** Each window whose state changed laid out again, as ZUI does: its title bar follows. */
    private static void relayout(Set<Integer> changed) {
        try {
            Class<?> observer = sLoader == null ? null
                    : Reflect.findClass(SysUiNames.OV_CAPTION_BAR_OBSERVER, sLoader);
            Object instance = null;
            if (observer != null) {
                java.lang.reflect.Field field = observer.getDeclaredField(SysUiNames.M_INSTANCE);
                field.setAccessible(true);
                instance = field.get(null);
            }
            Object model = Reflect.field(instance,
                    SysUiNames.M_DESKTOP_MODE_WINDOW_DECOR_VIEW_MODEL);
            Object decorations = Reflect.field(model, SysUiNames.M_WINDOW_DECOR_BY_TASK_ID);
            if (!(decorations instanceof SparseArray)) {
                L.d("full screen windows: the title bars not found, they follow at their next"
                        + " change");
                return;
            }
            for (int id : changed) {
                Object decoration = ((SparseArray<?>) decorations).get(id);
                Object task = Reflect.field(decoration, SysUiNames.M_TASK_INFO);
                Method relayout = shortRelayout(decoration);
                if (task == null || relayout == null) {
                    continue;
                }
                relayout.invoke(decoration, task,
                        Reflect.field(decoration, SysUiNames.M_HAS_GLOBAL_FOCUS),
                        Reflect.field(decoration, SysUiNames.M_EXCLUSION_REGION));
            }
        } catch (Throwable t) {
            L.d("full screen windows: a title bar left as it was (" + t + ")");
        }
    }

    /** {@code relayout(RunningTaskInfo, boolean, Region)}, ZUI's own for an unprompted one. */
    private static Method shortRelayout(Object decoration) {
        if (decoration == null) {
            return null;
        }
        if (sRelayout != null && sRelayout.getDeclaringClass().isInstance(decoration)) {
            return sRelayout;
        }
        for (Class<?> c = decoration.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (m.getName().equals(SysUiNames.RELAYOUT) && types.length == 3
                        && types[0] == ActivityManager.RunningTaskInfo.class
                        && types[1] == boolean.class
                        && !java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    m.setAccessible(true);
                    sRelayout = m;
                    return m;
                }
            }
        }
        return null;
    }

    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (FullScreenWire.ACTION_REVEAL.equals(intent.getAction())) {
                    reveal(intent.getIntExtra(FullScreenWire.EXTRA_TASK, -1));
                    return;
                }
                int[] ids = intent.getIntArrayExtra(FullScreenWire.EXTRA_TASKS);
                Set<Integer> now = new HashSet<>();
                if (ids != null) {
                    for (int id : ids) {
                        now.add(id);
                    }
                }
                Set<Integer> changed = new HashSet<>(now);
                changed.removeAll(MARKED);
                for (Integer id : MARKED) {
                    if (!now.contains(id)) {
                        changed.add(id);
                    }
                }
                MARKED.retainAll(now);
                SEEN.retainAll(now);
                REVEALED.keySet().retainAll(now);
                MARKED.addAll(now);
                if (!changed.isEmpty()) {
                    relayout(changed);
                }
            } catch (Throwable t) {
                L.d("full screen windows: ZUX Home's state unread (" + t + ")");
            }
        }
    }
}
