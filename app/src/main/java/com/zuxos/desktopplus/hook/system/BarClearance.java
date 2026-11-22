package com.zuxos.desktopplus.hook.system;

import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: apps and the keyboard stop at the taskbar, on both screens, and have its
 * strip only while it is hidden.
 *
 * <p>Apps built for Android 15 and later draw edge to edge, under the bars, and are trusted to
 * keep their own content clear of them; many do not, and their lists, buttons and pictures went
 * on under the taskbar. The system lays each window out from the bars it says it fits
 * ({@code DisplayPolicy.layoutWindowLw} → {@code WindowLayout.computeFrames}, from the window's
 * fit-insets types and sides). So for an app's window that does not fit the navigation bars -
 * which is what the taskbar is - they are added for that one layout and taken off again after
 * it: its frame ends at the bar, and the app finds nothing of the bar inside it.
 *
 * <p>The keyboard's window fits the navigation bars on every side but the bottom
 * ({@code InputMethodService}): it reaches the screen's edge and is trusted to keep its keys
 * above the bar itself, as its window's own padding did. A keyboard built for Android 15 and
 * later has that padding taken away (edge to edge is forced on it), and one that adds none of
 * its own - HeliBoard - drew its strip and bottom row under the taskbar, which lies above it. So
 * for that layout the keyboard fits the bottom too, and ends where ZUI tells keyboards the bar
 * starts ({@code taskbar_ime_size}, the bar's own height). Apps then still end at its top: a
 * keyboard's height to an app is its frame inside the app's.
 *
 * <p>Only while the bar takes room, as apps see it: a bar the app hid (immersive) or one of 1 px
 * (ZUI's game mode, our Full screen) leaves the window the whole screen, as does a floating
 * window clear of the bar. Home screens and recents are left as they are. The switch is read at
 * boot and again when the settings change ({@link SystemBridge}), never on the layout path.
 */
public final class BarClearance {

    private static final int LAST_APPLICATION_WINDOW = 99;
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int MAX_ERRORS = 20;

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    /** The window's own value while one layout runs with ours: {attrs, field, value}. */
    private static final ThreadLocal<Object[]> SAVED = new ThreadLocal<>();
    private static int sErrors;
    private static Field sFitTypes;
    private static Field sFitSides;
    private static Field sAttrs;
    private static Field sDisplayInsets;
    private static Method sActivityType;
    private static Method sInsetsState;
    private static Method sBounds;
    private static Method sCalculate;

    private BarClearance() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            sFitTypes = WindowManager.LayoutParams.class.getDeclaredField(
                    SystemNames.M_FIT_INSETS_TYPES);
            sFitTypes.setAccessible(true);
            sFitSides = WindowManager.LayoutParams.class.getDeclaredField(
                    SystemNames.M_FIT_INSETS_SIDES);
            sFitSides.setAccessible(true);
            Class<?> state = Class.forName(SystemNames.INSETS_STATE, false, loader);
            sCalculate = state.getMethod(SystemNames.CALCULATE_INSETS, Rect.class, int.class,
                    boolean.class);
            Class<?> window = Class.forName(SystemNames.WINDOW_STATE, false, loader);
            sAttrs = window.getField(SystemNames.M_ATTRS);
            sActivityType = window.getMethod(SystemNames.GET_ACTIVITY_TYPE);
            sInsetsState = window.getMethod(SystemNames.GET_INSETS_STATE);
            sBounds = window.getMethod(SystemNames.GET_BOUNDS);
            Class<?> policy = Class.forName(SystemNames.DISPLAY_POLICY, false, loader);
            int hooked = 0;
            for (Method m : policy.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (m.getName().equals(SystemNames.LAYOUT_WINDOW_LW) && types.length == 3
                        && types[0] == window) {
                    keyboardReach(types[2]);
                    XposedBridge.hookMethod(m, LAYOUT);
                    hooked++;
                }
            }
            reread();
            L.i("bar clearance: apps stop at the taskbar x" + hooked
                    + (sDisplayInsets == null ? "" : ", the keyboard too")
                    + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("bar clearance: not installed, apps lay out as they ask (" + t + ")");
        }
    }

    /** The display's insets, for the keyboard; without them only apps are cleared. */
    private static void keyboardReach(Class<?> displayFrames) {
        try {
            sDisplayInsets = displayFrames.getField(SystemNames.M_INSETS_STATE);
        } catch (Throwable t) {
            sDisplayInsets = null;
            L.w("bar clearance: the display's insets not found, the keyboard lays out as it "
                    + "asks (" + t + ")");
        }
    }

    /** The switch, read off the layout path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.barClearance();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    private static final XC_MethodHook LAYOUT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!sOn || sErrors > MAX_ERRORS) {
                return;
            }
            try {
                Object window = param.args[0];
                Object held = window == null ? null : sAttrs.get(window);
                if (!(held instanceof WindowManager.LayoutParams)) {
                    return;
                }
                WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) held;
                int nav = WindowInsets.Type.navigationBars();
                if (attrs.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
                    keyboard(window, attrs, param.args[2], nav);
                    return;
                }
                int fits = sFitTypes.getInt(attrs);
                if (attrs.type < WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                        || attrs.type > LAST_APPLICATION_WINDOW || (fits & nav) != 0
                        || !Integer.valueOf(ACTIVITY_TYPE_STANDARD).equals(
                                sActivityType.invoke(window))
                        || !barTakesRoom(sInsetsState.invoke(window), sBounds.invoke(window),
                                nav)) {
                    return;
                }
                sFitTypes.setInt(attrs, fits | nav);
                SAVED.set(new Object[]{attrs, sFitTypes, fits});
                if (L.once("BarClearance.first")) {
                    L.i("bar clearance: an app's window stops at the taskbar ("
                            + attrs.getTitle() + ")");
                }
            } catch (Throwable t) {
                failed(t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object[] was = SAVED.get();
                if (was != null) {
                    SAVED.remove();
                    ((Field) was[1]).setInt(was[0], (Integer) was[2]);
                }
            } catch (Throwable t) {
                failed(t);
            }
        }
    };

    /**
     * The keyboard fits the bottom too while the display's bar takes room. The bar is read from
     * the display's insets, as apps see them: the keyboard's own copy keeps ZUI's full keyboard
     * height for the bar even in game mode, where apps are told 1 px.
     */
    private static void keyboard(Object window, WindowManager.LayoutParams attrs,
            Object displayFrames, int nav) throws Exception {
        int bottom = WindowInsets.Side.BOTTOM;
        int sides = sFitSides.getInt(attrs);
        if (sDisplayInsets == null || displayFrames == null
                || (sFitTypes.getInt(attrs) & nav) == 0 || (sides & bottom) != 0
                || !barTakesRoom(sDisplayInsets.get(displayFrames), sBounds.invoke(window),
                        nav)) {
            return;
        }
        sFitSides.setInt(attrs, sides | bottom);
        SAVED.set(new Object[]{attrs, sFitSides, sides});
        if (L.once("BarClearance.keyboard")) {
            L.i("bar clearance: the keyboard stops at the taskbar (" + attrs.packageName + ")");
        }
    }

    /** Whether the bar takes more than 1 px of these bounds, by these insets. */
    private static boolean barTakesRoom(Object state, Object bounds, int nav) throws Exception {
        if (state == null || !(bounds instanceof Rect)) {
            return false;
        }
        Object insets = sCalculate.invoke(state, bounds, nav, false);
        if (!(insets instanceof Insets)) {
            return false;
        }
        Insets i = (Insets) insets;
        return Math.max(Math.max(i.left, i.right), Math.max(i.top, i.bottom)) > 1;
    }

    private static void failed(Throwable t) {
        if (++sErrors == MAX_ERRORS) {
            L.w("bar clearance: stopped for this boot after " + MAX_ERRORS + " errors (" + t
                    + ")");
        }
    }
}
