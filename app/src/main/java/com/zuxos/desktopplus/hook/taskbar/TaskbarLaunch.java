package com.zuxos.desktopplus.hook.taskbar;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A tap on an app in the tablet's taskbar drawer, or in one of its folders, that opened nothing.
 *
 * <p>ZUI hands every such tap to {@code TaskbarActivityContext.launchFromTaskbar}. Outside its
 * desktop mode that launches through ZUI's recents view, and with no recents view it gives up
 * without a word ("launchFromOverviewTaskbar, force return", or nothing at all with an app in
 * front). The view comes from the activity that holds ZUI's recents; when that activity goes,
 * ZUI hands the bar a controller that has none ({@code TaskbarUIController.DEFAULT}), and with
 * another app as the tablet's home nothing brings one back - the tap closed the drawer and the
 * system never heard of it.
 *
 * <p>So where ZUI would drop the tap, it gets ZUI's own launch from an app instead
 * ({@code startItemInfoActivity}): a plain start on the bar's screen. Every other tap is ZUI's.
 */
public final class TaskbarLaunch {

    private static boolean sInstalled;
    private static Method sLaunch;
    private static Method sDesktopMode;

    private TaskbarLaunch() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> context = Reflect.findClass(TaskbarNames.TASKBAR_ACTIVITY_CONTEXT, loader);
            if (context == null) {
                // A launcher without ZUI's taskbar.
                return;
            }
            Method launch = null;
            for (Method m : context.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (m.getName().equals(TaskbarNames.LAUNCH_FROM_TASKBAR) && types.length == 3
                        && types[2] == List.class) {
                    launch = m;
                } else if (m.getName().equals(TaskbarNames.START_ITEM_INFO_ACTIVITY)
                        && types.length == 2 && m.getReturnType() == void.class) {
                    sLaunch = m;
                }
            }
            sDesktopMode = context.getMethod(TaskbarNames.IS_IN_DESKTOP_MODE);
            if (launch == null || sLaunch == null) {
                L.w("taskbar launch: ZUI's taskbar launch not found, taps stay ZUI's");
                return;
            }
            sLaunch.setAccessible(true);
            XposedBridge.hookMethod(launch, BEFORE);
            L.i("taskbar launch: a tap ZUI would drop opens the app as from an app");
        } catch (Throwable t) {
            L.w("taskbar launch: not installed, taps stay ZUI's (" + t + ")");
        }
    }

    private static final XC_MethodHook BEFORE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                // ZUI's own launch needs the recents view only outside its desktop mode.
                if (param.args[0] != null || !(param.args[2] instanceof List)
                        || ((List<?>) param.args[2]).size() != 1 || !Cfg.taskbarTapsLaunch()
                        || Boolean.TRUE.equals(sDesktopMode.invoke(param.thisObject))) {
                    return;
                }
                Object item = ((List<?>) param.args[2]).get(0);
                sLaunch.invoke(param.thisObject, item, null);
                param.setResult(null);
                if (L.once("TaskbarLaunch")) {
                    L.i("taskbar launch: ZUI had no recents view to open " + item
                            + " from - opened as from an app");
                }
            } catch (Throwable t) {
                L.d("taskbar launch: left to ZUI (" + t + ")");
            }
        }
    };
}
