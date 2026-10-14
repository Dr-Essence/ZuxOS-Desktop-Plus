package com.zuxos.desktopplus.hook.home;

import android.content.Context;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ZUX Home's home screen on a bigger or finer grid than ZUI offers on this tablet (6x4 and 5x4;
 * its 6x5 is defined but not shown), through the place ZUI itself lets a grid be set from outside.
 *
 * <p>ZUI reads a partner's grid in {@code InvariantDeviceProfile.v(Context, DisplayMetrics)}
 * ("grid_num_rows", "grid_num_columns" of {@code Partner}) and sets {@code numRows} and
 * {@code numColumns} there, before any of its device profiles is made. The size the owner picks
 * goes in at the same place: ZUI then makes every profile with it and sizes its cells
 * ({@code DeviceProfile.calculateCellWidth}/{@code calculateCellHeight}, its icons scaled down by
 * its own {@code DeviceProfile} where a cell is too small), and at load its
 * {@code GridSizeMigrationTask} moves the icons into the new size. ZUI's own grid choice is
 * left as it is, so "ZUI's own" again gives it back at the next start, moved back by ZUI's
 * migration. ZUI's work-mode and learning grids (its tablet desktop mode, "custom") keep theirs.
 *
 * <p>The finest grids are the nearest ZUI's home screen comes to placing an icon anywhere: every
 * home item is kept in a cell, so a cell a fraction of an icon wide lets it land almost where it
 * was let go. The sizes are {@link Const#HOME_GRIDS}.
 */
public final class HomeGrids {

    private HomeGrids() {
    }

    public static void install(ClassLoader loader) {
        try {
            int choice = Cfg.homeGrid();
            if (choice <= 0 || choice >= Const.HOME_GRIDS.length) {
                return;
            }
            Class<?> profile = Reflect.findClass("com.android.launcher3.InvariantDeviceProfile",
                    loader);
            Class<?> utilities = Reflect.findClass("com.android.launcher3.Utilities", loader);
            if (profile == null || utilities == null) {
                L.w("home grids: ZUI's grid not found - its own grid stays");
                return;
            }
            int[] size = Const.HOME_GRIDS[choice];
            int hooked = 0;
            for (java.lang.reflect.Method m : profile.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (m.getName().equals("v") && types.length == 2 && types[0] == Context.class
                        && types[1] == android.util.DisplayMetrics.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            apply(param.thisObject, (Context) param.args[0], utilities, size);
                        }
                    });
                    hooked++;
                }
            }
            if (hooked == 0) {
                L.w("home grids: ZUI's partner grid not found - its own grid stays");
                return;
            }
            L.i("home grids: ZUX Home's home screen on " + size[0] + "x" + size[1]);
        } catch (Throwable t) {
            L.w("home grids: not installed (" + t + ")");
        }
    }

    private static boolean sSaid;

    private static void apply(Object profile, Context ctx, Class<?> utilities, int[] size) {
        try {
            // ZUI's tablet desktop mode and learning home keep their own grids.
            if (Boolean.TRUE.equals(call(utilities, "isCustomLauncher", ctx))
                    || Boolean.TRUE.equals(call(utilities, "isLearningLauncher", ctx))) {
                return;
            }
            profile.getClass().getField("numColumns").setInt(profile, size[0]);
            profile.getClass().getField("numRows").setInt(profile, size[1]);
            if (!sSaid) {
                sSaid = true;
                L.i("home grids: ZUI's profiles made on " + size[0] + "x" + size[1]);
            }
        } catch (Throwable t) {
            L.d("home grids: ZUI's grid kept (" + t + ")");
        }
    }

    private static Object call(Class<?> cls, String name, Context ctx) {
        try {
            return cls.getMethod(name, Context.class).invoke(null, ctx);
        } catch (Throwable t) {
            return null;
        }
    }
}
