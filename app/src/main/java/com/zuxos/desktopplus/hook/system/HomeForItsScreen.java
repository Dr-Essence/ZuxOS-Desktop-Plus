package com.zuxos.desktopplus.hook.system;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.view.Display;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XCallback;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: Home on the tablet opens the tablet's home, never the system's chooser.
 *
 * <p>Android picks the home through the choice it saved for the HOME intent, and when that choice
 * names an activity that is not among the homes there are now, it skips it and shows the chooser
 * ({@code ComputerEngine.findPreferredActivityBody}) - although the home role still names ZUX
 * Home. ZUX Home turns its own home activities on and off as its modes change, so the saved
 * choice could point at one that is off: the tablet asked for a launcher at boot and at Home. So
 * when the tablet's home resolves to a chooser ({@code RootWindowContainer.resolveHomeActivity}),
 * the home is Android's own default home instead: the home role holder's home activity among the
 * ones there are ({@code PackageManagerInternal.getHomeActivitiesAsUser}), the one Android's
 * settings show as the default. With no role holder, or none of its homes there, the chooser
 * stays.
 *
 * <p>While the monitor desktop is on, ZUI's package manager also answers every query for a home
 * with the monitor's launcher when ZUI's "focus" is the monitor ({@code ComputerEngine
 * .queryIntentActivitiesInternal}, asking {@code OVDesktopController
 * .getCurrentFocusDislayIdWhenUnderExtendDisplayInternal}: the top focused screen) - the tablet's
 * home too. So while the system starts a home for a screen ({@code RootWindowContainer
 * .startHomeOnTaskDisplayArea}, given that screen's display area), ZUI's focus check answers with
 * that screen, on that thread only: the monitor still gets its launcher, and the lookup above
 * sees the tablet's homes. ZUI's other reader of the check (its keyboard shortcuts) is untouched.
 * The switch is read at boot and when the settings change ({@link SystemBridge}).
 */
public final class HomeForItsScreen {

    /** The chooser's class, Android's own or ZUI's ({@code com.zui.resolver}). */
    private static final String CHOOSER = "ResolverActivity";

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    /** The screen a home is being started for on this thread; null outside a start. */
    private static final ThreadLocal<Integer> SCREEN = new ThreadLocal<>();
    /** Android's own default home, and how a home's activity is prepared for its user. */
    private static Class<?> sPackagesClass;
    private static Method sService;
    private static Method sRoleHome;
    private static Method sForUser;

    private HomeForItsScreen() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> root = Class.forName(SystemNames.ROOT_WINDOW_CONTAINER, false, loader);
            Class<?> desktop = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER, false, loader);
            Class<?> door = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER_INTERNAL, false,
                    loader);
            sPackagesClass = Class.forName(SystemNames.PACKAGE_MANAGER_INTERNAL, false, loader);
            sService = Class.forName(SystemNames.LOCAL_SERVICES, false, loader)
                    .getMethod(SystemNames.GET_SERVICE, Class.class);
            sRoleHome = sPackagesClass.getMethod(SystemNames.GET_HOME_ACTIVITIES_AS_USER,
                    List.class, int.class);
            sForUser = Class.forName(SystemNames.ACTIVITY_TASK_MANAGER_SERVICE, false, loader)
                    .getMethod(SystemNames.GET_APP_INFO_FOR_USER, ApplicationInfo.class,
                            int.class);
            // Both: the door is one call, which may be compiled into its callers.
            int checks = XposedBridge.hookAllMethods(desktop,
                    SystemNames.GET_CURRENT_FOCUS_DISLAY_ID_WHEN_UNDER_EXTEND_DISPLAY_INTERNAL,
                    FOCUS).size()
                    + XposedBridge.hookAllMethods(door,
                    SystemNames.GET_CURRENT_FOCUS_DISLAY_ID_WHEN_UNDER_EXTEND_DISPLAY, FOCUS)
                    .size();
            int starts = XposedBridge.hookAllMethods(root,
                    SystemNames.START_HOME_ON_TASK_DISPLAY_AREA, START).size();
            int resolves = 0;
            for (Method m : root.getDeclaredMethods()) {
                if (m.getName().equals(SystemNames.RESOLVE_HOME_ACTIVITY)
                        && m.getReturnType() == ActivityInfo.class) {
                    XposedBridge.hookMethod(m, RESOLVED);
                    resolves++;
                }
            }
            if (starts == 0 || resolves == 0) {
                L.w("home for its screen: the system's home start not found - the tablet's "
                        + "Home may still give a chooser");
                return;
            }
            reread();
            L.i("home for its screen: a screen's home is resolved for that screen, the tablet's "
                    + "by the home role x" + (checks + starts + resolves)
                    + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("home for its screen: not installed, the tablet's Home may still give a "
                    + "chooser (" + t + ")");
        }
    }

    /** The switch, read off the home's path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.noHomeChooser();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    /** A home started for a screen: that screen, held on this thread while it starts. */
    private static final XC_MethodHook START = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object area = param.args.length > 2 ? param.args[2] : null;
                Object screen = sOn && area != null
                        ? Reflect.call(area, SystemNames.GET_DISPLAY_ID) : null;
                if (screen instanceof Integer) {
                    SCREEN.set((Integer) screen);
                } else {
                    SCREEN.remove();
                }
            } catch (Throwable t) {
                SCREEN.remove();
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            SCREEN.remove();
        }
    };

    /** ZUI's focus, asked while a home starts: the screen the home is for. */
    private static final XC_MethodHook FOCUS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Integer screen = SCREEN.get();
                Object focus = param.getResult();
                if (screen == null || !sOn || screen.equals(focus)) {
                    return;
                }
                param.setResult(screen);
                if (L.once("HomeForItsScreen." + screen)) {
                    L.i("home for its screen: screen " + screen + "'s home resolved for it, "
                            + "with ZUI's focus on screen " + focus);
                }
            } catch (Throwable t) {
                if (L.once("HomeForItsScreen.focus")) {
                    L.w("home for its screen: ZUI's focus left as it was (" + t + ")");
                }
            }
        }
    };

    /**
     * The tablet's home resolved: a chooser becomes the home role's home. Its after-hook runs
     * before the launch trace's, which then writes what the tablet really gets.
     */
    private static final XC_MethodHook RESOLVED = new XC_MethodHook(XCallback.PRIORITY_LOWEST) {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Integer screen = SCREEN.get();
                Object chosen = param.getResult();
                if (!sOn || screen == null || screen != Display.DEFAULT_DISPLAY
                        || !(chosen instanceof ActivityInfo)
                        || ((ActivityInfo) chosen).name == null
                        || !((ActivityInfo) chosen).name.endsWith(CHOOSER)) {
                    return;
                }
                int user = (Integer) param.args[0];
                List<ResolveInfo> homes = new ArrayList<>();
                Object packages = sService.invoke(null, sPackagesClass);
                Object role = packages == null ? null : sRoleHome.invoke(packages, homes, user);
                ActivityInfo home = role instanceof ComponentName
                        ? homeAmong(homes, (ComponentName) role) : null;
                if (home == null) {
                    if (L.once("HomeForItsScreen.noRole")) {
                        L.i("home for its screen: the tablet's home is the chooser and the home "
                                + "role names none of its " + homes.size() + " homes - it stays");
                    }
                    return;
                }
                ActivityInfo info = new ActivityInfo(home);
                Object service = Reflect.field(param.thisObject, SystemNames.M_SERVICE);
                Object forUser = service == null ? null
                        : sForUser.invoke(service, info.applicationInfo, user);
                if (forUser instanceof ApplicationInfo) {
                    info.applicationInfo = (ApplicationInfo) forUser;
                }
                param.setResult(info);
                if (L.once("HomeForItsScreen.role")) {
                    L.i("home for its screen: the tablet's home was the chooser, with "
                            + homes.size() + " homes - the home role's " + role + " instead");
                }
            } catch (Throwable t) {
                if (L.once("HomeForItsScreen.resolved")) {
                    L.w("home for its screen: the tablet's home left to Android (" + t + ")");
                }
            }
        }
    };

    /** The home activity the role names, among the ones there are; null when it is not there. */
    private static ActivityInfo homeAmong(List<ResolveInfo> homes, ComponentName role) {
        for (ResolveInfo home : homes) {
            ActivityInfo info = home == null ? null : home.activityInfo;
            if (info != null && role.getPackageName().equals(info.packageName)
                    && role.getClassName().equals(info.name)) {
                return info;
            }
        }
        return null;
    }
}
