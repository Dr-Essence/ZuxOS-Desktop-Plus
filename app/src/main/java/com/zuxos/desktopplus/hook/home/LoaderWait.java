package com.zuxos.desktopplus.hook.home;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Threads;

import java.lang.reflect.Field;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUX Home's drawer filled within a second of its start, not only once an app is opened.
 *
 * <p>ZUI's loader ({@code LoaderTask}, Launcher3's) binds the home screen, then "takes a break"
 * ({@code waitForIdle}) until the main thread has nothing to do, and only then loads the app list
 * ({@code LoadAllApps}). The wait has no limit ({@code LooperIdleLock}, waited for a second at a
 * time for as long as the main thread is busy). With the home screen in front its main thread
 * never went idle, and the drawer stayed empty until an app was opened over it: 14 s and 23 s
 * after a boot, 14 s after a restart in front, about 1 s after starts in the background (logs
 * 18:59, 21:31, 21:58). The break is kept, one second at most: the idle lock ZUI makes
 * ({@code BaseLauncherBinder.newIdleLock}) is let go then through its own
 * {@code queueIdle}, as if the main thread had gone idle. Released from a thread of its own:
 * the loader holds the lock's monitor while it works, and the main thread must never wait on it.
 */
public final class LoaderWait {

    /** The longest ZUI's loader waits for an idle main thread. */
    private static final long WAIT_MS = 1000L;

    private static ScheduledExecutorService sTimer;
    private static Field sWaiting;

    private LoaderWait() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> binder = Reflect.findClass("com.android.launcher3.model.BaseLauncherBinder",
                    loader);
            Class<?> lock = Reflect.findClass("com.android.launcher3.util.LooperIdleLock", loader);
            if (binder == null || lock == null) {
                L.w("loader wait: ZUI's loader not found - its app list waits as ZUI has it");
                return;
            }
            for (Field f : lock.getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    f.setAccessible(true);
                    sWaiting = f;
                }
            }
            int hooked = XposedBridge.hookAllMethods(binder, "newIdleLock", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() != null && Cfg.loaderNoWait()) {
                        letGoLater(param.getResult());
                    }
                }
            }).size();
            L.i("loader wait: ZUI's app list waits for an idle home screen a second at most x"
                    + hooked);
        } catch (Throwable t) {
            L.w("loader wait: not installed (" + t + ")");
        }
    }

    private static synchronized void letGoLater(Object lock) {
        if (sTimer == null) {
            sTimer = Executors.newSingleThreadScheduledExecutor(
                    Threads.named("zux-desktop-plus-loader-wait"));
        }
        sTimer.schedule(() -> letGo(lock), WAIT_MS, TimeUnit.MILLISECONDS);
    }

    /** Still waiting a second on: ZUI's lock released as its idle handler would release it. */
    private static void letGo(Object lock) {
        try {
            if (sWaiting != null && !sWaiting.getBoolean(lock)) {
                // The main thread went idle in time.
                return;
            }
            Reflect.call(lock, "queueIdle");
            L.i("loader wait: ZUI's loader waited " + WAIT_MS + " ms for an idle home screen - "
                    + "it goes on loading");
        } catch (Throwable t) {
            L.d("loader wait: the lock not let go (" + t + ")");
        }
    }
}
