package com.zuxos.desktopplus.hook.home;

import android.content.Context;
import android.util.ArrayMap;
import android.view.View;
import android.view.ViewParent;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Select on a home-screen app's menu: several apps chosen to move together, as One UI has it -
 * through ZUI's own edit mode, which already selects.
 *
 * <p>ZUI's edit mode ({@code LauncherState.EDIT_MODE}) selects an icon on a tap
 * ({@code ZuiEditModePanel.switchSelectedState}: its check mark, up to 24), drags every selected
 * icon at once ({@code MultiDragSupport}), and can put them in a folder or remove them. Select
 * only takes the home screen there with the held app already selected; ZUI does the rest.
 *
 * <p>A selected icon also shakes, with Launcher3's own shake - the one its home screen gives an
 * icon about to make room ({@code ReorderPreviewAnimation}, repeating) - until it is no longer
 * selected, a drag picks it up, or edit mode ends.
 */
public final class HomeSelect {

    private static final String PANEL = "com.zui.launcher.uiextend.ZuiEditModePanel";
    private static final String STATE = "com.android.launcher3.LauncherState";
    private static final String SHAKE = "com.android.launcher3.celllayout.ReorderPreviewAnimation";
    private static final String CELLS = "com.android.launcher3.CellLayout";
    private static final String WORKSPACE = "com.android.launcher3.Workspace";
    /** Launcher3's {@code ReorderPreviewAnimation.MODE_PREVIEW}: repeats until stopped. */
    private static final int MODE_PREVIEW = 1;
    /** Launcher3's {@code CellLayout.REORDER_PREVIEW_MAGNITUDE}, of the icon size. */
    private static final float MAGNITUDE = 0.12f;
    /** ZUI's {@code LauncherSettings.Favorites} containers: the home screen and its dock. */
    private static final int DESKTOP = -100;
    private static final int HOTSEAT = -101;

    private static Object sEditMode;
    private static Class<?> sCells;
    private static Constructor<?> sShake;
    /** The shakes under way, by icon. Main thread only. */
    private static final Map<View, Object> SHAKING = new WeakHashMap<>();
    /** Launcher3 keeps a shake by its icon in a map it is given; ours is this one. */
    private static final ArrayMap<Object, Object> SHAKE_MAP = new ArrayMap<>();

    private HomeSelect() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> panel = Reflect.findClass(PANEL, loader);
            Class<?> state = Reflect.findClass(STATE, loader);
            sCells = Reflect.findClass(CELLS, loader);
            if (panel == null || state == null || sCells == null) {
                L.w("home select: ZUI's edit mode not found - no Select in the app menu");
                return;
            }
            sEditMode = state.getField("EDIT_MODE").get(null);
            findShake(loader);
            XC_MethodHook resync = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    resync(param.thisObject);
                }
            };
            int hooked = 0;
            for (String name : new String[]{"switchSelectedState", "clearSelectedItems",
                    "removeAllSelectedIcon", "removeSelectedIcon", "initSelectedItemByIds"}) {
                hooked += XposedBridge.hookAllMethods(panel, name, resync).size();
            }
            hooked += XposedBridge.hookAllMethods(panel, "onStateTransitionStart",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && param.args[0] != sEditMode) {
                                stopAll();
                            }
                        }
                    }).size();
            hookDrag(loader);
            Health.hooked("home: Select in the app menu", hooked, Cfg::homeSelect);
            L.i("home select: Select through ZUI's edit mode x" + hooked
                    + (sShake == null ? ", without the shake" : ""));
        } catch (Throwable t) {
            sEditMode = null;
            L.w("home select: not installed (" + t + ")");
        }
    }

    private static void findShake(ClassLoader loader) {
        Class<?> shake = Reflect.findClass(SHAKE, loader);
        for (Constructor<?> c : shake == null ? new Constructor<?>[0] : shake.getConstructors()) {
            if (c.getParameterCount() == 11) {
                sShake = c;
            }
        }
        if (sShake == null) {
            L.w("home select: Launcher3's shake not found - selected icons keep still");
        }
    }

    /** A drag picks the selected icons up: still while it lasts, shaking again after. */
    private static void hookDrag(ClassLoader loader) {
        Class<?> workspace = Reflect.findClass(WORKSPACE, loader);
        if (workspace == null) {
            return;
        }
        XposedBridge.hookAllMethods(workspace, "onDragStart", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                stopAll();
            }
        });
        XposedBridge.hookAllMethods(workspace, "onDragEnd", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                // Once ZUI has laid the dropped icons in their new places.
                view.post(() -> resync(panelOf(view.getContext())));
            }
        });
    }

    /**
     * Whether Select belongs in the menu of this item on this screen: an app or shortcut on ZUX
     * Home's home screen or its dock, where ZUI's edit mode selects.
     */
    public static boolean selectable(Object target, Object info) {
        if (sEditMode == null || !Cfg.homeSelect() || info == null || panelOf(target) == null) {
            return false;
        }
        Object container = Reflect.field(info, "container");
        // In one of the home screen's folders too (a folder's id, above 0), which ZUI's edit mode
        // selects in the open folder; never in the drawers' folders of ours (ids far below 0).
        return info.getClass().getSimpleName().equals("WorkspaceItemInfo")
                && container instanceof Integer
                && ((Integer) container == DESKTOP || (Integer) container == HOTSEAT
                || (Integer) container > 0);
    }

    /** ZUI's edit mode, with this icon selected in it. */
    public static void select(Object launcher, View icon) {
        try {
            Object states = Reflect.call(launcher, "getStateManager");
            Method go = null;
            for (Method m : states.getClass().getMethods()) {
                if (m.getName().equals("goToState") && m.getParameterCount() == 1) {
                    go = m;
                }
            }
            Object panel = panelOf(launcher);
            if (go == null || panel == null) {
                L.w("home select: ZUI's edit mode could not be opened");
                return;
            }
            go.invoke(states, sEditMode);
            if (!Boolean.TRUE.equals(Reflect.call(panel, "isSelectedView", icon))) {
                Reflect.call(panel, "switchSelectedState", icon);
            }
        } catch (Throwable t) {
            L.w("home select: not selected (" + t + ")");
        }
    }

    /** ZUI's edit-mode panel of this launcher, or null where it has none. */
    private static Object panelOf(Object launcher) {
        Object pmr = launcher == null ? null : Reflect.call(launcher, "getPMRLauncher");
        return pmr == null ? null : Reflect.call(pmr, "getEditModePanel");
    }

    private static Object panelOf(Context ctx) {
        for (Context c = ctx; c != null; c = c instanceof android.content.ContextWrapper
                ? ((android.content.ContextWrapper) c).getBaseContext() : null) {
            if (c instanceof android.app.Activity) {
                return panelOf((Object) c);
            }
        }
        return null;
    }

    /** The shakes follow ZUI's selection: on for every selected icon, off for every other. */
    @SuppressWarnings("unchecked")
    private static void resync(Object panel) {
        try {
            if (panel == null) {
                return;
            }
            Object views = Cfg.homeSelect() ? Reflect.call(panel, "getSelectedViews") : null;
            List<View> selected = views instanceof List ? (List<View>) views : new ArrayList<>();
            for (View shaking : new ArrayList<>(SHAKING.keySet())) {
                if (!selected.contains(shaking)) {
                    stop(shaking);
                }
            }
            for (View view : selected) {
                if (!SHAKING.containsKey(view)) {
                    shake(view);
                }
            }
        } catch (Throwable t) {
            L.d("home select: shakes not matched to the selection (" + t + ")");
        }
    }

    /** Launcher3's repeating shake on one icon, nudging toward its neighbour cell. */
    private static void shake(View icon) {
        if (sShake == null || !icon.isAttachedToWindow()) {
            return;
        }
        try {
            ViewParent container = icon.getParent();
            Object cells = container == null ? null : container.getParent();
            if (!sCells.isInstance(cells)) {
                return;
            }
            Object lp = icon.getLayoutParams();
            Object x = Reflect.call(lp, "getCellX");
            Object y = Reflect.call(lp, "getCellY");
            Object columns = Reflect.call(cells, "getCountX");
            if (!(x instanceof Integer) || !(y instanceof Integer) || !(columns instanceof Integer)) {
                return;
            }
            int cellX = (Integer) x;
            int toward = cellX + 1 < (Integer) columns ? cellX + 1 : cellX - 1;
            Object profile = Reflect.call(icon.getContext(), "getDeviceProfile");
            Object iconSize = profile == null ? null : Reflect.field(profile, "iconSizePx");
            float amount = (iconSize instanceof Integer ? (Integer) iconSize : icon.getWidth())
                    * MAGNITUDE;
            Object shake = sShake.newInstance(icon, MODE_PREVIEW, cellX, y, toward, y, 1, 1,
                    amount, cells, SHAKE_MAP);
            Reflect.call(shake, "animate");
            SHAKING.put(icon, shake);
        } catch (Throwable t) {
            L.d("home select: an icon not shaken (" + t + ")");
        }
    }

    private static void stop(View icon) {
        Object shake = SHAKING.remove(icon);
        if (shake != null) {
            // Launcher3's own way out: the icon eases back to its place.
            Reflect.call(shake, "finishAnimation");
            SHAKE_MAP.remove(icon);
        }
    }

    private static void stopAll() {
        for (View icon : new ArrayList<>(SHAKING.keySet())) {
            stop(icon);
        }
    }
}
