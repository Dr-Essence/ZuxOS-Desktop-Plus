package com.zuxos.desktopplus.hook;

import android.content.ComponentName;
import android.os.UserHandle;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Constructor;

/**
 * ZUX Home's own app entries: its home activity, and the drawer's {@code AppInfo} for an app,
 * from its all-apps store - what its own drawer drags, and what its home screen copies when one
 * is dropped there.
 */
public final class ZuiApps {

    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    private static final String KEY = "com.android.launcher3.util.ComponentKey";

    private ZuiApps() {
    }

    /** ZUX Home's home activity as it was last created ({@code Launcher.ACTIVITY_TRACKER}). */
    public static Object launcher(ClassLoader loader) {
        try {
            Class<?> launcher = Class.forName(LAUNCHER, false, loader);
            return Reflect.call(launcher.getField("ACTIVITY_TRACKER").get(null),
                    "getCreatedContext");
        } catch (Throwable t) {
            L.d("zui apps: no home activity (" + t + ")");
            return null;
        }
    }

    /** The drawer's own entry for this app and user, or null where ZUI has none. */
    public static Object appInfo(Object launcher, ComponentName component, UserHandle user) {
        try {
            Object apps = Reflect.call(launcher, "getAppsView");
            Object store = Reflect.call(apps, "getAppsStore");
            Constructor<?> keyOf = Class.forName(KEY, false, launcher.getClass().getClassLoader())
                    .getConstructor(ComponentName.class, UserHandle.class);
            return store == null ? null
                    : Reflect.call(store, "getApp", keyOf.newInstance(component, user));
        } catch (Throwable t) {
            L.d("zui apps: no entry for " + component + " (" + t + ")");
            return null;
        }
    }
}
