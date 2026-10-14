package com.zuxos.desktopplus.hook.home;

import android.content.Context;
import android.content.SharedPreferences;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.Mirror;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Bigger and finer home grids for ZUX Home, chosen in ZUI's own home settings, as its own.
 *
 * <p>ZUI's grid picker ({@code ZuiLauncherFragment}, through {@code GridCustomizationsProxy})
 * lists the grid options of its {@code device_profiles.xml} that may be shown -
 * {@code InvariantDeviceProfile.parseAllDefinedGridOptions}: 6x4 and 5x4 on this tablet; its
 * 6x5 is defined but hidden. Here its 6x5 is shown, and 8x5, 8x6, 10x7 and 12x8 join the list,
 * each ZUI's own 6x4 option renamed and resized. Picked, ZUI stores the name and builds its
 * grid by it ({@code InvariantDeviceProfile.F}, which reads the XML again): for one of these
 * names it gets 6x4's display options carrying the new size, so the name stays and ZUI's own
 * migration moves the icons. 7x5 is never offered: ZUI treats exactly 7x5 as its work-mode
 * grid. With the module off, ZUI finds no such name and falls back to its nearest grid itself.
 *
 * <p>The grid chosen in the module's own setting before (0.0.261) is handed to ZUI's choice once.
 */
public final class HomeGrids {

    /** The sizes added, as columns x rows. */
    private static final int[][] ADDED = {{8, 5}, {8, 6}, {10, 7}, {12, 8}};
    /** ZUI's grid the added ones are made from. */
    private static final String TEMPLATE = "6x4";
    /** ZUI's own grid it hides. */
    private static final String HIDDEN = "6x5";
    /** The module's old setting, by its index; read once, for the hand-over. */
    private static final String[] OLD_SETTING = {null, "6x5", "8x5", "8x6", "10x7", "12x8"};
    private static final String PREFS = "zux_desktop_plus_grid";
    private static final String HANDED = "handed_over";

    private static boolean sSaid;

    private HomeGrids() {
    }

    public static void install(ClassLoader loader) {
        try {
            if (!Cfg.enabled()) {
                return;
            }
            Class<?> idp = Reflect.findClass("com.android.launcher3.InvariantDeviceProfile",
                    loader);
            Class<?> option = Reflect.findClass(
                    "com.android.launcher3.InvariantDeviceProfile$GridOption", loader);
            if (idp == null || option == null) {
                L.w("home grids: ZUI's grid options not found - its own grids only");
                return;
            }
            Method resolve = null;
            Method parse = null;
            for (Method m : idp.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers()) || !List.class.equals(m.getReturnType())
                        || types.length == 0 || types[0] != Context.class) {
                    continue;
                }
                if (m.getName().equals("parseAllDefinedGridOptions") && types.length == 2) {
                    parse = m;
                } else if (types.length == 5 && types[1] == String.class
                        && types[3] == boolean.class && types[4] == boolean.class) {
                    resolve = m;
                }
            }
            if (parse == null || resolve == null) {
                L.w("home grids: ZUI's grid list or resolver not found - its own grids only");
                return;
            }
            Method original = parse;
            XposedBridge.hookMethod(parse, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    offer(param, original);
                }
            });
            Method resolver = resolve;
            XposedBridge.hookMethod(resolve, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    build(param, resolver, option);
                }
            });
            L.i("home grids: 6x5, 8x5, 8x6, 10x7 and 12x8 in ZUI's own grid choice");
        } catch (Throwable t) {
            L.w("home grids: not installed (" + t + ")");
        }
    }

    /** ZUI's list of grids, with ours added and its hidden 6x5 shown. */
    @SuppressWarnings("unchecked")
    private static void offer(XC_MethodHook.MethodHookParam param, Method parse) {
        try {
            if (!(param.getResult() instanceof List)) {
                return;
            }
            List<Object> options = (List<Object>) param.getResult();
            for (Object option : options) {
                if (HIDDEN.equals(Reflect.field(option, "name"))) {
                    setField(option, "canBeShown", true);
                }
            }
            for (int[] size : ADDED) {
                // A fresh 6x4 of ZUI's for each: ZUI reads the XML again for every list.
                List<Object> again = (List<Object>) XposedBridge.invokeOriginalMethod(parse,
                        null, param.args);
                Object template = named(again, TEMPLATE);
                if (template == null) {
                    return;
                }
                resize(template, size);
                options.add(template);
            }
        } catch (Throwable t) {
            L.d("home grids: ZUI's list kept as it is (" + t + ")");
        }
    }

    /** ZUI resolving a grid by name: ours made from its 6x4, carrying our size. */
    @SuppressWarnings("unchecked")
    private static void build(XC_MethodHook.MethodHookParam param, Method resolve,
            Class<?> optionClass) {
        try {
            String name = (String) param.args[1];
            String handed = handOver((Context) param.args[0], name);
            if (handed != null) {
                name = handed;
            }
            int[] size = sizeOf(name);
            if (size == null && handed == null) {
                return;
            }
            // Resolved under another name than asked: ZUI then stores the name it got as its
            // choice (InvariantDeviceProfile.G and H), which is what hands it over.
            Object[] args = param.args.clone();
            args[1] = size != null ? TEMPLATE : handed;
            List<Object> options = (List<Object>) XposedBridge.invokeOriginalMethod(resolve,
                    null, args);
            if (size == null) {
                // ZUI's own hidden 6x5, handed over.
                param.setResult(options);
                return;
            }
            Field option = null;
            for (Object display : options) {
                if (option == null) {
                    option = Mirror.fieldOfType(display.getClass(), optionClass);
                }
                Object grid = option != null ? Mirror.get(option, display) : null;
                if (grid == null) {
                    return;
                }
                // Each display option made by this resolve has a GridOption of its own.
                resize(grid, size);
            }
            param.setResult(options);
            if (!sSaid) {
                sSaid = true;
                L.i("home grids: ZUX Home's home screen on " + name);
            }
        } catch (Throwable t) {
            L.d("home grids: ZUI resolves its grid itself (" + t + ")");
        }
    }

    /**
     * The grid chosen in the module's own setting before ZUI's choice had ours, to hand to ZUI
     * once: asked for its own default, ZUI gets that grid and stores it as its choice.
     */
    private static String handOver(Context ctx, String asked) {
        try {
            int old = Cfg.homeGrid();
            // Only over ZUI's regular grids: its desktop mode ("custom") and learning home keep
            // their own.
            if (old <= 0 || old >= OLD_SETTING.length
                    || !(TEMPLATE.equals(asked) || "5x4".equals(asked))) {
                return null;
            }
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(HANDED, false)) {
                return null;
            }
            prefs.edit().putBoolean(HANDED, true).apply();
            L.i("home grids: the module's grid " + OLD_SETTING[old] + " handed to ZUI's choice");
            return OLD_SETTING[old];
        } catch (Throwable t) {
            return null;
        }
    }

    private static int[] sizeOf(String name) {
        if (name == null) {
            return null;
        }
        for (int[] size : ADDED) {
            if (name.equals(size[0] + "x" + size[1])) {
                return size;
            }
        }
        return null;
    }

    private static Object named(List<Object> options, String name) {
        for (Object option : options) {
            if (name.equals(Reflect.field(option, "name"))) {
                return option;
            }
        }
        return null;
    }

    /** One of ZUI's grid options, renamed and resized; its other values stay 6x4's. */
    private static void resize(Object option, int[] size) throws ReflectiveOperationException {
        setField(option, "name", size[0] + "x" + size[1]);
        setField(option, "numColumns", size[0]);
        setField(option, "numRows", size[1]);
        setField(option, "canBeShown", true);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
