package com.zuxos.desktopplus.hook.taskbar;

import android.app.ActivityManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.Tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is open, for our taskbar row ({@link TaskbarRunning}): the packages open on a screen, from
 * the activity manager or else the launcher's own recent-apps controller, with the app in front
 * there and the windows each app has open.
 */
public final class RunningTasks {

    private RunningTasks() {
    }

    /** Per display: where its open apps were last read from, so a change is said once. */
    private static final Map<Integer, Integer> SOURCE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The packages that are open.
     *
     * <p>Two ways of asking, and root is deliberately not one of them. Reading the task list
     * through the shell would mean a root round trip every few seconds for as long as the
     * taskbar is up - which would raise a prompt unasked, and, since one root request at a time
     * is the rule here, would leave the quick settings toggles failing at random. A feature
     * that quietly breaks another one is not worth having.
     *
     * <p>So: the activity manager, which a system launcher is usually allowed to ask and an
     * ordinary app is not; and failing that the launcher's own recent-apps controller, which
     * will say whether a given icon is running even though it does not fill this bar. The
     * second only knows about icons that are already there, so with it the bar can be narrowed
     * but not added to - which is said plainly in the log rather than silently half-done.
     */
    static Set<String> running(Context ctx, ViewGroup icons, int displayId) {
        Set<String> out = fromActivityManager(ctx, displayId);
        if (!out.isEmpty()) {
            source(displayId, 0, "the activity manager");
            return out;
        }
        out = fromLauncher(icons);
        if (!out.isEmpty()) {
            source(displayId, 1, "the launcher's own running-app state (open apps that are not "
                    + "pinned cannot be added this way)");
            return out;
        }
        source(displayId, 2, "nothing open on this screen, or nowhere readable - no open apps "
                + "shown");
        return out;
    }

    /**
     * Said once per display and change. It was one value for every bar: the tablet with apps
     * open and the monitor with none took turns changing it, and every refresh wrote two lines
     * to the log - 156 in an hour.
     */
    private static void source(int displayId, int which, String what) {
        Integer before = SOURCE.put(displayId, which);
        if (before == null || before != which) {
            L.d("taskbar running: display " + displayId + " reads what is open from " + what);
        }
    }

    /**
     * The task list, where this launcher is allowed to see it.
     *
     * <p>{@code getRunningTasks} is refused to ordinary apps - they get their own task and
     * nothing else, which is why one result counts as no answer rather than as one app.
     *
     * <p>What comes back is task <em>records</em>, which outlive the app that made them: without
     * the two tests below, everything the user had open this week would be in the bar. Both read
     * fields the framework keeps but does not publish, so both are skipped where they cannot be
     * read rather than guessed at.
     */
    private static Set<String> fromActivityManager(Context ctx, int displayId) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> everything = new LinkedHashSet<>();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return out;
            }
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(25);
            if (tasks == null) {
                return out;
            }
            Map<String, List<Integer>> windows = new java.util.LinkedHashMap<>();
            boolean front = true;
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task.baseActivity == null) {
                    continue;
                }
                describeTaskFields(task);
                String pkg = task.baseActivity.getPackageName();
                if (!onDisplay(task, displayId)) {
                    // Another screen's: never on this bar, whatever the test below makes of it.
                    continue;
                }
                if (front && shownOrFocused(task)) {
                    // The list is most recent first: this screen's first task on screen is the
                    // one in front, which the bar's fade asks about on every frame. A closed or
                    // minimised app's record ahead of it is not.
                    front = false;
                    String before = FRONT.put(displayId, pkg);
                    FRONT_TASK.put(displayId, task.taskId);
                    if (displayId == 0 && before != null && !before.equals(pkg)) {
                        TaskbarDiag.onFrontChanged(before, pkg);
                    }
                }
                everything.add(pkg);
                if (isOpen(task)) {
                    out.add(pkg);
                    List<Integer> ids = windows.get(pkg);
                    if (ids == null) {
                        ids = new ArrayList<>();
                        windows.put(pkg, ids);
                    }
                    ids.add(task.taskId);
                }
            }
            // In the order they were opened, not front first: each icon keeps its window, where
            // ordering by recency made the app's icon and its window icon trade windows on
            // every tap, and Close hit whichever happened to be in front.
            for (List<Integer> ids : windows.values()) {
                java.util.Collections.sort(ids);
            }
            WINDOWS.put(displayId, windows);
            if (front) {
                // Nothing on screen here: the monitor's desktop with every app closed.
                FRONT.remove(displayId);
                FRONT_TASK.remove(displayId);
            }
            if (everything.size() <= 1) {
                // Nothing but the home screen on this display - or a list this launcher is not
                // allowed to read, which looks the same. Either way nothing to show.
                return new LinkedHashSet<>();
            }
            if (out.isEmpty()) {
                // The open test threw away everything on this screen, which means it is reading
                // something other than what it is named after on this build. A bar of every task
                // here is wrong; an empty one is worse. Other screens' tasks never count - that
                // filled the monitor's bar with the tablet's apps.
                if (L.once("TaskbarRunning.unfiltered")) {
                    L.i("taskbar running: nothing on this screen survived the open test, so every "
                            + "task on it counts");
                }
                return everything;
            }
        } catch (Throwable t) {
            L.d("taskbar running: the activity manager will not list tasks (" + t + ")");
        }
        return out;
    }

    /** Per display: the package of the task in front, as of the last read of the task list. */
    private static final Map<Integer, String> FRONT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Per display: the task in front, read with {@link #FRONT}. */
    static final Map<Integer, Integer> FRONT_TASK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The package in front on this display, or null before the task list was first read. */
    public static String frontPackage(int displayId) {
        return FRONT.get(displayId);
    }

    /** Per display: each open app's windows (task ids), in the order they were opened. */
    private static final Map<Integer, Map<String, List<Integer>>> WINDOWS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** An extra window's key in the row: the app, and which of its tasks. */
    static final char WINDOW_MARK = '#';

    /**
     * The row's keys with one more icon for every extra window: an app opened twice is two
     * icons, the second right after the first - or, when the first is a pin or one of the
     * launcher's own icons, at the end.
     */
    static List<String> withWindows(List<String> wanted, Set<String> running,
            int displayId, String launcher) {
        Map<String, List<Integer>> windows = WINDOWS.get(displayId);
        if (windows == null || windows.isEmpty()) {
            return wanted;
        }
        List<String> out = new ArrayList<>();
        for (String pkg : wanted) {
            out.add(pkg);
            addExtra(out, windows.get(pkg), pkg);
        }
        for (String pkg : running) {
            if (!wanted.contains(pkg) && !pkg.equals(launcher)) {
                addExtra(out, windows.get(pkg), pkg);
            }
        }
        return out;
    }

    /**
     * The window an app's own icon stands for: its first, when it has more than one here; -1
     * when it has one or none, where the app and its window are the same thing.
     */
    static int firstWindow(String pkg, int displayId) {
        Map<String, List<Integer>> windows = WINDOWS.get(displayId);
        List<Integer> ids = windows == null ? null : windows.get(pkg);
        return ids != null && ids.size() > 1 ? ids.get(0) : -1;
    }

    private static void addExtra(List<String> out, List<Integer> ids, String pkg) {
        if (ids == null) {
            return;
        }
        for (int i = 1; i < ids.size(); i++) {
            out.add(pkg + WINDOW_MARK + ids.get(i));
        }
    }

    /** Which of the task list's unpublished fields this build actually lets us read. */
    private static void describeTaskFields(Object task) {
        if (!L.once("TaskbarRunning.fields")) {
            return;
        }
        L.i("taskbar running: the task list says isRunning="
                + Reflect.field(task, "isRunning") + ", isVisible="
                + Reflect.field(task, "isVisible") + ", displayId="
                + Reflect.field(task, "displayId"));
    }

    /**
     * Whether a task is an app that is open, rather than a record of one that was.
     *
     * <p>Visibility is not the test: an app minimised on the desktop is exactly what a taskbar is
     * for, and it is not visible. Running is the test, and where the build will not say, every
     * task counts - which is where this started.
     */
    static boolean isOpen(Object task) {
        Object visible = Reflect.field(task, "isVisible");
        if (visible instanceof Boolean && (Boolean) visible) {
            return true;
        }
        // An app that has just been opened: in front, but not visible yet and, while its process
        // is still starting, not running yet either. Without this it reached the bar only once
        // it had drawn - two seconds after it was opened, on a cold start.
        if (Boolean.TRUE.equals(Reflect.field(task, "isFocused"))) {
            return true;
        }
        Object running = Reflect.field(task, "isRunning");
        if (running instanceof Boolean) {
            return (Boolean) running;
        }
        return true;
    }

    /** On screen, or just opened and about to be; true where the build tells neither. */
    private static boolean shownOrFocused(Object task) {
        Object visible = Reflect.field(task, "isVisible");
        return !(visible instanceof Boolean) || (Boolean) visible
                || Boolean.TRUE.equals(Reflect.field(task, "isFocused"));
    }

    /** This bar belongs to one display; an app on the tablet screen is not on it. */
    private static boolean onDisplay(Object task, int displayId) {
        if (displayId < 0) {
            return true;
        }
        int where = Tasks.displayOf(task);
        return where == Tasks.UNKNOWN || where == displayId;
    }

    /**
     * Asks the launcher which of its own icons are running.
     *
     * <p>{@code getRunningAppState} is on the controller the probe found; it answers per item
     * even on a firmware where that controller does not fill the bar. The values it returns are
     * logged the first time, because which of them means "not running" is the whole question.
     */
    private static Set<String> fromLauncher(ViewGroup icons) {
        Set<String> out = new LinkedHashSet<>();
        Object controller = TaskbarApps.recentAppsController();
        if (controller == null) {
            return out;
        }
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            String pkg = IconInfo.packageOfView(child);
            if (pkg == null) {
                continue;
            }
            Object state = Reflect.call(controller, "getRunningAppState", child.getTag());
            if (state == null) {
                // Not "this app is not running" - no answer at all, which for the first icon
                // means the method is not there. Hiding every icon on the strength of that
                // would empty the taskbar over a failed reflection call.
                return new LinkedHashSet<>();
            }
            if (!state.getClass().isEnum()) {
                // The states are named constants; anything else and the test below is reading
                // tea leaves, so the whole source is declined rather than half-trusted.
                if (sStates.add("?")) {
                    L.i("taskbar running: the launcher answers with "
                            + state.getClass().getName() + ", which cannot be read as a state");
                }
                return new LinkedHashSet<>();
            }
            String name = String.valueOf(state);
            if (sStates.add(name)) {
                L.i("taskbar running: the launcher calls one of these states '" + name + "'");
            }
            if (!name.toUpperCase(java.util.Locale.US).contains("NOT")) {
                out.add(pkg);
            }
        }
        return out;
    }

    private static final Set<String> sStates = new LinkedHashSet<>();
}
