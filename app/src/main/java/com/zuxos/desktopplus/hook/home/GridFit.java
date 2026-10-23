package com.zuxos.desktopplus.hook.home;

import android.content.Context;
import android.graphics.Point;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.logic.GridMath;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The home screen's icons, names and dots made to fit their cells on grids finer than ZUI's own
 * ({@link HomeGrids}), as ZUI's scalable grids fit theirs.
 *
 * <p>ZUI's grids on this tablet are neither scalable nor responsive, and for them its
 * {@code DeviceProfile.updateIconSize} never shrinks an icon: it only works out the cell's height
 * from it. On 10x7 or 12x8 the icon, its name and its dot stayed their 6x4 size in a cell half as
 * large, and spilled over the next one. Here, once ZUI has sized a profile's icons
 * ({@code DeviceProfile.I}, which sets {@code iconSizePx} and
 * {@code iconTextSizePx} and lays the cells out), a profile whose icon and name do not fit its
 * cell ({@code getCellSize()}) gets them shrunk by one scale, and ZUI works its cells, folder
 * icons and hotseat out again from them ({@code updateIconSize}). The dots ZUI then makes for
 * that icon size are shrunk by the same scale. Only the home screen's icons change: the drawer
 * and open folders read their own sizes. The tablet's screen only, never ZUI's desktop mode,
 * learning home or the monitor.
 */
public final class GridFit {

    /** How much of a cell's width an icon may take, leaving room between neighbours. */
    private static final float WIDTH_SHARE = 0.8f;
    /** The smallest name, in sp; smaller, the name goes, as ZUI drops it for its side bar. */
    private static final float MIN_TEXT_SP = 8f;

    /** The icon size and scale of the profile being made on this thread, for its dots. */
    private static final ThreadLocal<float[]> FITTING = new ThreadLocal<>();
    private static boolean sSaid;

    private GridFit() {
    }

    public static void install(ClassLoader loader) {
        try {
            if (!Cfg.enabled() || !Cfg.gridFit()) {
                return;
            }
            Class<?> profile = Reflect.findClass("com.android.launcher3.DeviceProfile", loader);
            Class<?> utilities = Reflect.findClass("com.android.launcher3.Utilities", loader);
            Class<?> dots = Reflect.findClass("com.android.launcher3.icons.DotRenderer", loader);
            if (profile == null || utilities == null) {
                L.w("grid fit: ZUI's device profile not found - icons keep ZUI's size");
                return;
            }
            Method sizing = null;
            for (Method m : profile.getDeclaredMethods()) {
                if (m.getName().equals("I") && m.getReturnType() == int.class
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Context.class) {
                    sizing = m;
                }
            }
            if (sizing == null) {
                L.w("grid fit: ZUI's icon sizing not found - icons keep ZUI's size");
                return;
            }
            XposedBridge.hookMethod(sizing, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    fit(param.thisObject, (Context) param.args[0], utilities);
                }
            });
            XposedBridge.hookAllConstructors(profile, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    FITTING.remove();
                }
            });
            if (dots != null) {
                XposedBridge.hookAllConstructors(dots, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        shrinkDot(param.args);
                    }
                });
            }
            L.i("grid fit: icons fit their cells on fine home grids");
        } catch (Throwable t) {
            L.w("grid fit: not installed (" + t + ")");
        }
    }

    private static void fit(Object dp, Context ctx, Class<?> utilities) {
        try {
            FITTING.remove();
            Class<?> c = dp.getClass();
            Object inv = c.getField("inv").get(dp);
            int columns = inv.getClass().getField("numColumns").getInt(inv);
            int rows = inv.getClass().getField("numRows").getInt(inv);
            if (columns * rows <= 24 || (columns == 7 && rows == 5)
                    || c.getField("displayId").getInt(dp) != 0
                    || c.getField("isUsedToDpMode").getBoolean(dp)
                    || Boolean.TRUE.equals(Reflect.call(dp, "isVerticalBarLayout"))
                    || Boolean.TRUE.equals(call(utilities, "isCustomLauncher", ctx))
                    || Boolean.TRUE.equals(call(utilities, "isLearningLauncher", ctx))) {
                return;
            }
            int icon0 = c.getField("iconSizePx").getInt(dp);
            Method textHeight = utilities.getMethod("calculateTextHeight", float.class);
            // A few passes at most: the room under the icon follows its size, and a name too
            // small to read goes.
            for (int pass = 0; pass < 3; pass++) {
                Point cell = (Point) Reflect.call(dp, "getCellSize");
                int icon = c.getField("iconSizePx").getInt(dp);
                int text = c.getField("iconTextSizePx").getInt(dp);
                int gap = Math.max(0, c.getField("iconDrawablePaddingPx").getInt(dp));
                if (cell == null) {
                    return;
                }
                // What ZUI lays out in a cell (DeviceProfile.updateIconSize): the icon with room
                // for its folder ring (x1.125), the gap and the name. ZUI squeezes the gap when
                // that is too tall, so its own cell height cannot be read for it.
                int content = (int) Math.ceil(icon * 1.125f) + gap
                        + (text > 0 ? (Integer) textHeight.invoke(null, (float) text) : 0);
                float s = GridMath.fit(content, icon, cell.x, cell.y, WIDTH_SHARE);
                if (s >= 0.99f) {
                    break;
                }
                int newText = Math.round(text * s);
                float minText = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, MIN_TEXT_SP,
                        ctx.getResources().getDisplayMetrics());
                c.getField("iconSizePx").setInt(dp, Math.max(1, Math.round(icon * s)));
                c.getField("iconTextSizePx").setInt(dp, newText < minText ? 0 : newText);
                Object toFit = c.getField("cellScaleToFit").get(dp);
                Reflect.call(dp, "updateIconSize", toFit instanceof Float ? toFit : 1f, ctx);
            }
            int icon = c.getField("iconSizePx").getInt(dp);
            if (icon != icon0) {
                FITTING.set(new float[]{icon, icon / (float) icon0});
                if (!sSaid) {
                    sSaid = true;
                    L.i("grid fit: icons " + icon0 + " -> " + icon + " px to fit " + columns + "x"
                            + rows + "'s cells");
                }
            }
        } catch (Throwable t) {
            L.d("grid fit: ZUI's icon size kept (" + t + ")");
        }
    }

    /**
     * A dot made for the shrunk home icon ({@code DotRenderer(context, iconSize, path,
     * pathSize, circleHeight, textSize, ...)}): its circle and number shrunk the same.
     */
    private static void shrinkDot(Object[] args) {
        try {
            float[] fitting = FITTING.get();
            if (fitting == null || args.length < 6 || !(args[1] instanceof Integer)
                    || (Integer) args[1] != (int) fitting[0]) {
                return;
            }
            float scale = fitting[1];
            if (args[4] instanceof Integer) {
                args[4] = Math.max(1, Math.round((Integer) args[4] * scale));
            }
            if (args[5] instanceof Integer) {
                args[5] = Math.max(1, Math.round((Integer) args[5] * scale));
            }
        } catch (Throwable t) {
            L.d("grid fit: a dot kept its size (" + t + ")");
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
