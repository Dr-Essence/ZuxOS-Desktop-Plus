package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.os.SystemClock;
import android.util.SparseArray;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A window kept above all others on the monitor, by its title bar's pin: the shell's own call,
 * a window container transaction's {@code setAlwaysOnTop} on the task, applied through the
 * shell's task organizer - as ZUI keeps its floating windows on top on the tablet
 * ({@code DesktopTasksController.ovAppendChanges_OvFreeform}). Whether a window is on top is the
 * system's own word ({@code WindowConfiguration.isAlwaysOnTop}), so the pin shows what is, not
 * what was asked - but ZUI binds the bar again only on its own changes, not on this one, so the
 * pin shows an ask at once and keeps showing it until the system has caught up.
 */
final class KeepOnTop {

    /** How long an ask is shown over the system's word before the system's word stands. */
    private static final long CATCH_UP_MS = 2000L;

    /** Each window just asked, with what was asked and when. */
    private static final Map<Integer, Ask> ASKED = new ConcurrentHashMap<>();
    /** The windows our pin keeps on top, to let go of one that is maximised. */
    private static final Set<Integer> PINNED = ConcurrentHashMap.newKeySet();

    private KeepOnTop() {
    }

    /** Whether the system keeps this window above the others. */
    static boolean on(ActivityManager.RunningTaskInfo task) {
        Object config = Reflect.call(task, SysUiNames.GET_CONFIGURATION);
        Object window = Reflect.field(config, SysUiNames.WINDOW_CONFIGURATION);
        return Boolean.TRUE.equals(Reflect.call(window, SysUiNames.IS_ALWAYS_ON_TOP));
    }

    /**
     * What the pin shows: an ask the system has not caught up with yet, else the system's word.
     * A bar bound with task info older than the ask - its own click can bring one - keeps the
     * ask; once the system agrees, or it has had its time, the ask goes, and the log says once
     * whether it took.
     */
    static boolean shown(ActivityManager.RunningTaskInfo task) {
        boolean on = on(task);
        Ask ask = ASKED.get(task.taskId);
        if (ask == null) {
            return on;
        }
        if (ask.wanted != on && SystemClock.uptimeMillis() - ask.at < CATCH_UP_MS) {
            return ask.wanted;
        }
        ASKED.remove(task.taskId, ask);
        if (L.once("KeepOnTop.took." + ask.wanted + "." + (ask.wanted == on))) {
            L.i("keep on top: task " + task.taskId + " asked " + (ask.wanted ? "on" : "off")
                    + " - the system says on top: " + on);
        }
        return on;
    }

    /**
     * The window kept on top, or let go, the other way from what its pin shows now; what was
     * asked, or null when nothing was.
     */
    static Boolean toggle(ClassLoader loader, int taskId) {
        return ask(loader, taskId, null);
    }

    /**
     * A window our pin keeps on top let go once it is maximised: it would stand over every
     * window opened after it, and a maximised window's bar has no pin to let it go with.
     */
    static void maximised(ClassLoader loader, int taskId) {
        if (PINNED.contains(taskId) && ask(loader, taskId, false) != null
                && L.once("KeepOnTop.maximised")) {
            L.i("keep on top: task " + taskId + " maximised - let go");
        }
    }

    /** The shell asked to keep the window on top or let it go; {@code wanted} null flips it. */
    private static Boolean ask(ClassLoader loader, int taskId, Boolean wanted) {
        try {
            Object model = ShellModel.get(loader);
            Object decorations = Reflect.field(model, SysUiNames.M_WINDOW_DECOR_BY_TASK_ID);
            Object decoration = decorations instanceof SparseArray
                    ? ((SparseArray<?>) decorations).get(taskId) : null;
            Object task = Reflect.field(decoration, SysUiNames.M_TASK_INFO);
            Object token = Reflect.field(task, SysUiNames.TOKEN);
            Object organizer = Reflect.field(model, SysUiNames.M_TASK_ORGANIZER);
            if (!(task instanceof ActivityManager.RunningTaskInfo) || token == null
                    || organizer == null) {
                if (L.once("KeepOnTop.missing")) {
                    L.w("keep on top: the window or the shell's organizer not found - left as "
                            + "it is");
                }
                return null;
            }
            boolean set = wanted != null ? wanted : !now(taskId,
                    (ActivityManager.RunningTaskInfo) task);
            Class<?> type = Class.forName(SysUiNames.WINDOW_CONTAINER_TRANSACTION);
            Object change = type.getConstructor().newInstance();
            Method setOnTop = null;
            for (Method m : type.getMethods()) {
                if (m.getName().equals(SysUiNames.SET_ALWAYS_ON_TOP)
                        && m.getParameterCount() == 2) {
                    setOnTop = m;
                }
            }
            Method apply = null;
            for (Method m : organizer.getClass().getMethods()) {
                if (m.getName().equals(SysUiNames.APPLY_TRANSACTION)
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == type) {
                    apply = m;
                }
            }
            if (setOnTop == null || apply == null) {
                if (L.once("KeepOnTop.calls")) {
                    L.w("keep on top: the shell's calls not found - left as it is");
                }
                return null;
            }
            setOnTop.invoke(change, token, set);
            apply.invoke(organizer, change);
            ASKED.put(taskId, new Ask(set, SystemClock.uptimeMillis()));
            if (set) {
                PINNED.add(taskId);
            } else {
                PINNED.remove(taskId);
            }
            return set;
        } catch (Throwable t) {
            L.w("keep on top: left as it is (" + t + ")");
            return null;
        }
    }

    /** What the pin shows now, without settling an ask: two quick clicks flip it twice. */
    private static boolean now(int taskId, ActivityManager.RunningTaskInfo task) {
        Ask ask = ASKED.get(taskId);
        return ask != null && SystemClock.uptimeMillis() - ask.at < CATCH_UP_MS
                ? ask.wanted : on(task);
    }

    private static final class Ask {
        final boolean wanted;
        final long at;

        Ask(boolean wanted, long at) {
            this.wanted = wanted;
            this.at = at;
        }
    }
}
