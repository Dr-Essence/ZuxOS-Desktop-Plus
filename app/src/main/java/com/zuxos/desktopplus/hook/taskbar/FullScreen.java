package com.zuxos.desktopplus.hook.taskbar;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.FullScreenWire;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.hook.Tasks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full screen on the monitor: a window that fills the whole screen, the taskbar out of its way
 * until the pointer reaches the bar's edge ({@link BarAutoHide}), and its title bar hidden by
 * SystemUI.
 *
 * <p>The window goes full screen the way Maximize takes it there, by ZUI's own transition; what
 * makes it more than maximised is ZUI's game mode on the monitor's bar, which reports the bar
 * as 1 px to apps, so the app lays itself out to the screen's edge, under where the bar was.
 * That holds only while the full-screen window is the one in front: anything else in front gets
 * the bar back, and the room it keeps.
 *
 * <p>Which windows are full screen is kept here, in ZUX Home, and nowhere else: SystemUI's
 * window menu asks for a change, and is told every change, for its menu's word and its title
 * bars ({@link FullScreenWire}). Nothing is kept across a restart of ZUX Home; a window full
 * screen then stays maximised, with its bar and title bar back.
 */
public final class FullScreen {

    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    /** A burst of task events, answered once. */
    private static final long SOON_MS = 80L;
    /** After our own change, while ZUI's transition takes the window there. */
    private static final long[] SETTLE_MS = {350L, 900L, 1600L};
    /** How long a window just made full screen may still read as floating. */
    private static final long GRACE_MS = 2000L;
    /** The system's word for a window not yet in the task list: looked for this often. */
    private static final int ENTER_TRIES = 4;
    private static final long ENTER_RETRY_MS = 250L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable EVALUATE = FullScreen::evaluate;

    /** The full-screen windows: task id to its display. Asked from the task listener's thread. */
    private static final Map<Integer, Integer> MARKED = new ConcurrentHashMap<>();
    /** When each was made full screen, for {@link #GRACE_MS}. Main thread. */
    private static final Map<Integer, Long> SINCE = new ConcurrentHashMap<>();

    private static Context sApp;
    private static boolean sListening;

    private FullScreen() {
    }

    /** Whether Full screen is offered for windows on this display: the monitor's only. */
    static boolean offered(int display) {
        return display != Display.DEFAULT_DISPLAY && Cfg.enabled() && Cfg.fullScreen();
    }

    /** The menu's word for {@link #toggle}, for the window the entry stands for. */
    static String label(List<ActivityManager.RunningTaskInfo> tasks, String pkg, int taskId) {
        ActivityManager.RunningTaskInfo task = Maximize.find(tasks, pkg, taskId);
        return task != null && MARKED.containsKey(task.taskId) ? FullScreenWire.EXIT
                : FullScreenWire.ENTER;
    }

    /**
     * One window of the app - {@code taskId}, or its front one here when -1 - made full screen,
     * or back to maximised when it already is.
     */
    static void toggle(Context ctx, String pkg, int display, int taskId) {
        try {
            List<ActivityManager.RunningTaskInfo> tasks = TaskbarApps.tasksOn(ctx, display);
            ActivityManager.RunningTaskInfo task = Maximize.find(tasks, pkg, taskId);
            if (task == null) {
                L.i("full screen: " + pkg + " has no window on display " + display);
                return;
            }
            toggle(ctx, tasks, task, display);
        } catch (Throwable t) {
            L.w("full screen: " + pkg + " left as it is (" + t + ")");
        }
    }

    private static void toggle(Context ctx, List<ActivityManager.RunningTaskInfo> tasks,
            ActivityManager.RunningTaskInfo task, int display) {
        listen(ctx);
        int id = task.taskId;
        if (MARKED.remove(id) != null) {
            SINCE.remove(id);
            L.i("full screen: task " + id + " back to maximised, with the taskbar");
            changed();
            return;
        }
        int mode = Maximize.windowingMode(task);
        if (mode == WINDOWING_MODE_FREEFORM) {
            if (!Maximize.toFullScreenAsZui(tasks, task)) {
                return;
            }
        } else if (mode != WINDOWING_MODE_FULLSCREEN) {
            // A split, or unreadable: there is no screen of its own to fill.
            L.i("full screen: task " + id + " (mode " + mode + ") is not a window, left as it is");
            return;
        }
        MARKED.put(id, display);
        SINCE.put(id, SystemClock.uptimeMillis());
        L.i("full screen: " + Tasks.packageOf(task) + " (task " + id + ") fills display "
                + display);
        changed();
        for (long ms : SETTLE_MS) {
            MAIN.postDelayed(EVALUATE, ms);
        }
    }

    /**
     * The system's word that a window opens full screen - the game launcher, and what it opens.
     * The task can be a moment behind the word; it is looked for again a few times.
     */
    private static void enter(Context ctx, int id, int display, int tries) {
        if (!offered(display) || MARKED.containsKey(id)) {
            return;
        }
        List<ActivityManager.RunningTaskInfo> tasks = TaskbarApps.tasksOn(ctx, display);
        ActivityManager.RunningTaskInfo task = byId(tasks, id);
        if (task != null) {
            toggle(ctx, tasks, task, display);
        } else if (tries > 1) {
            MAIN.postDelayed(() -> enter(ctx, id, display, tries - 1), ENTER_RETRY_MS);
        } else {
            L.i("full screen: task " + id + " never showed on display " + display);
        }
    }

    /** A window leaving full screen by another way, Floating: no longer full screen. */
    static void forget(int taskId) {
        if (MARKED.remove(taskId) != null) {
            SINCE.remove(taskId);
            changed();
        }
    }

    /** The system's task events: answered only while a window is full screen, once a burst. */
    static void onTasks() {
        if (MARKED.isEmpty() && !BarAutoHide.engagedAnywhere()) {
            return;
        }
        MAIN.removeCallbacks(EVALUATE);
        MAIN.postDelayed(EVALUATE, SOON_MS);
    }

    private static void changed() {
        announce();
        evaluate();
    }

    /**
     * Each display's bar out of the way while a full-screen window is the one in front there,
     * and back otherwise. Windows closed, moved off the monitor or no longer full screen are let
     * go. One task list per display that has any; nothing at all with none.
     */
    static void evaluate() {
        try {
            evaluateNow();
        } catch (Throwable t) {
            L.w("full screen: the bar left as it is (" + t + ")");
        }
    }

    private static void evaluateNow() {
        Context ctx = sApp;
        if (ctx == null) {
            return;
        }
        Set<Integer> displays = new HashSet<>(MARKED.values());
        displays.addAll(BarAutoHide.engagedDisplays());
        boolean dropped = false;
        long now = SystemClock.uptimeMillis();
        for (int display : displays) {
            List<ActivityManager.RunningTaskInfo> tasks = MARKED.containsValue(display)
                    ? TaskbarApps.tasksOn(ctx, display)
                    : new ArrayList<ActivityManager.RunningTaskInfo>();
            for (Map.Entry<Integer, Integer> e : new ArrayList<>(MARKED.entrySet())) {
                if (e.getValue() != display) {
                    continue;
                }
                ActivityManager.RunningTaskInfo task = byId(tasks, e.getKey());
                Long since = SINCE.get(e.getKey());
                boolean settling = since != null && now - since < GRACE_MS;
                if (task == null || (!settling
                        && Maximize.windowingMode(task) != WINDOWING_MODE_FULLSCREEN)) {
                    MARKED.remove(e.getKey());
                    SINCE.remove(e.getKey());
                    dropped = true;
                    L.i("full screen: task " + e.getKey() + (task == null
                            ? " closed or left the monitor" : " is no longer full screen"));
                }
            }
            ActivityManager.RunningTaskInfo front = front(tasks);
            boolean full = front != null && MARKED.containsKey(front.taskId)
                    && Maximize.windowingMode(front) == WINDOWING_MODE_FULLSCREEN;
            BarAutoHide.engage(display, full);
            TitleEdge.watch(display, full ? front.taskId : -1);
        }
        if (dropped) {
            announce();
        }
    }

    /** This screen's task in front: the first on screen in the list, which is newest first. */
    private static ActivityManager.RunningTaskInfo front(
            List<ActivityManager.RunningTaskInfo> tasks) {
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (RunningTasks.shownOrFocused(task)) {
                return task;
            }
        }
        return null;
    }

    private static ActivityManager.RunningTaskInfo byId(
            List<ActivityManager.RunningTaskInfo> tasks, int id) {
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (task.taskId == id) {
                return task;
            }
        }
        return null;
    }

    /**
     * Hears SystemUI, once per process, from the monitor's first bar: its window menu's Full
     * screen, a full-screen window it saw leave full screen, and its asking for the state - and
     * tells it the state at once, which after a restart of ZUX Home is that nothing is. Hears the
     * system too: the game launcher's windows, to open full screen.
     */
    static void listen(Context ctx) {
        if (sListening || ctx == null || !Cfg.enabled() || !Cfg.fullScreen()) {
            return;
        }
        Context app = ctx.getApplicationContext();
        if (app == null) {
            return;
        }
        sListening = true;
        sApp = app;
        try {
            IntentFilter filter = new IntentFilter(FullScreenWire.ACTION_TOGGLE);
            filter.addAction(FullScreenWire.ACTION_LEFT);
            filter.addAction(FullScreenWire.ACTION_ASK);
            filter.addAction(FullScreenWire.ACTION_ENTER);
            app.registerReceiver(new Receiver(), filter, FullScreenWire.SENDER, MAIN,
                    Context.RECEIVER_EXPORTED);
            L.i("full screen: offered on the monitor, in the taskbar's menus and SystemUI's");
        } catch (Throwable t) {
            L.w("full screen: SystemUI not heard (" + t + ") - the taskbar's menus still offer it");
        }
        announce();
    }

    /** SystemUI told which windows are full screen: for its menu's word and its title bars. */
    private static void announce() {
        Context ctx = sApp;
        if (ctx == null) {
            return;
        }
        int[] ids = new int[MARKED.size()];
        int i = 0;
        for (Integer id : MARKED.keySet()) {
            if (i < ids.length) {
                ids[i++] = id;
            }
        }
        try {
            ctx.sendBroadcast(new Intent(FullScreenWire.ACTION_STATE)
                    .setPackage(FullScreenWire.SYSTEMUI)
                    .putExtra(FullScreenWire.EXTRA_TASKS, java.util.Arrays.copyOf(ids, i)));
        } catch (Throwable t) {
            L.d("full screen: SystemUI not told (" + t + ")");
        }
    }

    /** For the probe: the full-screen windows, and each bar's state. */
    public static String describe() {
        return "\nfull screen\n  windows (task=display): " + MARKED
                + (sListening ? "" : ", SystemUI not heard yet") + "\n  bars: "
                + BarAutoHide.describe() + "\n";
    }

    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                int id = intent.getIntExtra(FullScreenWire.EXTRA_TASK, -1);
                if (FullScreenWire.ACTION_ASK.equals(action)) {
                    announce();
                } else if (FullScreenWire.ACTION_LEFT.equals(action)) {
                    if (MARKED.remove(id) != null) {
                        SINCE.remove(id);
                        L.i("full screen: SystemUI saw task " + id + " leave full screen");
                        changed();
                    }
                } else if (FullScreenWire.ACTION_TOGGLE.equals(action) && id >= 0) {
                    int display = intent.getIntExtra(FullScreenWire.EXTRA_DISPLAY, -1);
                    if (!offered(display)) {
                        return;
                    }
                    List<ActivityManager.RunningTaskInfo> tasks =
                            TaskbarApps.tasksOn(context, display);
                    ActivityManager.RunningTaskInfo task = byId(tasks, id);
                    if (task == null) {
                        L.i("full screen: task " + id + " not on display " + display);
                        return;
                    }
                    toggle(context, tasks, task, display);
                } else if (FullScreenWire.ACTION_ENTER.equals(action) && id >= 0) {
                    enter(context, id, intent.getIntExtra(FullScreenWire.EXTRA_DISPLAY, -1),
                            ENTER_TRIES);
                }
            } catch (Throwable t) {
                L.w("full screen: SystemUI's request not taken (" + t + ")");
            }
        }
    }
}
