package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.util.SparseArray;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A window kept above all others on the monitor, by its title bar's pin: the shell's own call,
 * a window container transaction's {@code setAlwaysOnTop} on the task, applied through the
 * shell's task organizer - as ZUI keeps its floating windows on top on the tablet
 * ({@code DesktopTasksController.ovAppendChanges_OvFreeform}). Whether a window is on top is the
 * system's own word ({@code WindowConfiguration.isAlwaysOnTop}), so the pin shows what is, not
 * what was asked.
 */
final class KeepOnTop {

    /** Each window just asked, and what was asked: the next bind says whether it took. */
    private static final Map<Integer, Boolean> ASKED = new ConcurrentHashMap<>();

    private KeepOnTop() {
    }

    /** Whether the system keeps this window above the others. */
    static boolean on(ActivityManager.RunningTaskInfo task) {
        Object config = Reflect.call(task, SysUiNames.GET_CONFIGURATION);
        Object window = Reflect.field(config, SysUiNames.WINDOW_CONFIGURATION);
        return Boolean.TRUE.equals(Reflect.call(window, SysUiNames.IS_ALWAYS_ON_TOP));
    }

    /** The window kept on top, or let go, the other way from what the system says now. */
    static void toggle(ClassLoader loader, int taskId) {
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
                return;
            }
            boolean wanted = !on((ActivityManager.RunningTaskInfo) task);
            Class<?> type = Class.forName(SysUiNames.WINDOW_CONTAINER_TRANSACTION);
            Object change = type.getConstructor().newInstance();
            Method set = null;
            for (Method m : type.getMethods()) {
                if (m.getName().equals(SysUiNames.SET_ALWAYS_ON_TOP)
                        && m.getParameterCount() == 2) {
                    set = m;
                }
            }
            Method apply = null;
            for (Method m : organizer.getClass().getMethods()) {
                if (m.getName().equals(SysUiNames.APPLY_TRANSACTION)
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == type) {
                    apply = m;
                }
            }
            if (set == null || apply == null) {
                if (L.once("KeepOnTop.calls")) {
                    L.w("keep on top: the shell's calls not found - left as it is");
                }
                return;
            }
            set.invoke(change, token, wanted);
            apply.invoke(organizer, change);
            ASKED.put(taskId, wanted);
        } catch (Throwable t) {
            L.w("keep on top: left as it is (" + t + ")");
        }
    }

    /** The window's next bind after an ask: whether the system took it, said once. */
    static void check(ActivityManager.RunningTaskInfo task) {
        Boolean asked = ASKED.remove(task.taskId);
        if (asked != null && L.once("KeepOnTop.took." + asked)) {
            L.i("keep on top: task " + task.taskId + " asked " + (asked ? "on" : "off")
                    + " - the system says on top: " + on(task));
        }
    }
}
