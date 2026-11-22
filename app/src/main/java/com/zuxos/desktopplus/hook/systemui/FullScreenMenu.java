package com.zuxos.desktopplus.hook.systemui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.FullScreenWire;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.icons.Glyphs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * "Full screen" in ZUI's window menu ("…") for a window on the monitor - a row of ZUI's own.
 *
 * <p>ZUI works out the menu's size when it makes it ({@code OvHandleMenu}'s constructor: one row
 * height per row of its second group, two empty bars around them, and the space between the
 * groups) and builds and binds its view later ({@code ovOnAssistContentReceived}). So the size
 * gets one more row by ZUI's own sums, and the view one more row: ZUI's "Replace app" row from a
 * fresh copy of ZUI's own layout, so its background, size, text and colours are ZUI's, with our
 * icon and words. A tap closes the menu with ZUI's own call and asks ZUX Home, which keeps which
 * windows are full screen ({@link FullScreenWire}).
 *
 * <p>Both places or neither: a menu grown without its row would show a gap.
 */
public final class FullScreenMenu {

    /** Menus made a row taller, until their view is built: each gets its row once. */
    private static final Map<Object, Boolean> GROWN =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** SystemUI's resource ids by type and name: the same for the life of the process. */
    private static final Map<String, Integer> IDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Field sGroup2Height;
    private static Field sWindowHeight;

    private FullScreenMenu() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> menu = Reflect.findClass(SysUiNames.OV_HANDLE_MENU, loader);
            Class<?> decoration = Reflect.findClass(SysUiNames.DESKTOP_MODE_WINDOW_DECORATION,
                    loader);
            Method bind = null;
            if (decoration != null) {
                for (Method m : decoration.getDeclaredMethods()) {
                    if (m.getName().equals(SysUiNames.OV_ON_ASSIST_CONTENT_RECEIVED)
                            && m.getParameterCount() == 1) {
                        bind = m;
                    }
                }
            }
            if (menu != null) {
                sGroup2Height = menu.getDeclaredField(SysUiNames.MENU_GROUP2_HEIGHT);
                sWindowHeight = menu.getDeclaredField(SysUiNames.MENU_WINDOW_HEIGHT);
                sGroup2Height.setAccessible(true);
                sWindowHeight.setAccessible(true);
            }
            if (menu == null || bind == null) {
                L.w("full screen menu: not in ZUI's window menu - not found:"
                        + (menu == null ? " the menu" : "") + (bind == null ? " its build" : ""));
                return;
            }
            int hooked = XposedBridge.hookAllConstructors(menu, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        grow(param.thisObject);
                    } catch (Throwable t) {
                        L.d("full screen menu: ZUI's menu left its size (" + t + ")");
                    }
                }
            }).size();
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        addRow(param.thisObject);
                    } catch (Throwable t) {
                        L.w("full screen menu: the row not added (" + t + ")");
                    }
                }
            });
            L.i("full screen menu: in ZUI's window menu on the monitor x" + (hooked + 1));
        } catch (Throwable t) {
            L.w("full screen menu: not installed (" + t + ")");
        }
    }

    /** One more row in the menu's second group, by ZUI's own sums, for a window on the monitor. */
    private static void grow(Object menu) throws IllegalAccessException {
        Object task = Reflect.field(menu, SysUiNames.TASK_INFO);
        Object ctx = Reflect.field(menu, SysUiNames.CONTEXT);
        if (!Cfg.enabled() || !Cfg.fullScreen() || !onMonitor(task)
                || !(ctx instanceof Context)) {
            return;
        }
        Resources res = ((Context) ctx).getResources();
        int row = dimen(res, SysUiNames.OVC_WD_HANDLE_MENU_GROUP2_ITEM_HEIGHT_PAD);
        int bar = dimen(res, SysUiNames.OVC_WD_HANDLE_MENU_GROUP2_EMPTY_BAR_HEIGHT_PAD);
        int space = dimen(res, SysUiNames.OVC_WD_HANDLE_MENU_GROUPS_SPACE_PAD);
        if (row <= 0 || id(res, SysUiNames.OV_DESKTOP_MODE_WINDOW_DECOR_HANDLE_MENU_PAD_GROUP_TWO,
                "layout") == 0) {
            if (L.once("FullScreenMenu.resources")) {
                L.w("full screen menu: ZUI's menu row not found - no Full screen in it");
            }
            return;
        }
        int group2 = sGroup2Height.getInt(menu);
        int window = sWindowHeight.getInt(menu);
        if (group2 == 0) {
            // A second group of its own: the row, its two empty bars, and the space above it.
            int grown = row + 2 * bar;
            sGroup2Height.setInt(menu, grown);
            sWindowHeight.setInt(menu, window + grown + space);
        } else {
            sGroup2Height.setInt(menu, group2 + row);
            sWindowHeight.setInt(menu, window + row);
        }
        GROWN.put(menu, Boolean.TRUE);
    }

    /** The menu's view, just built by ZUI: our row in its second group, which shows. */
    private static void addRow(Object decoration) {
        Object menu = Reflect.field(decoration, SysUiNames.M_OV_HANDLE_MENU);
        if (menu == null || GROWN.remove(menu) == null) {
            return;
        }
        Object view = Reflect.field(menu, SysUiNames.HANDLE_MENU_VIEW);
        Object root = Reflect.field(view, SysUiNames.HANDLE_MENU_ROOT);
        Object task = Reflect.field(menu, SysUiNames.TASK_INFO);
        if (!(root instanceof View) || !(task instanceof ActivityManager.RunningTaskInfo)) {
            L.w("full screen menu: ZUI's menu view not found - its second group has a gap");
            return;
        }
        View menuRoot = (View) root;
        Context ctx = menuRoot.getContext();
        Resources res = ctx.getResources();
        View last = byName(menuRoot, SysUiNames.OVC_WD_HANDLE_MENU_GROUP2_EMPTY_BAR2);
        View ours = zuiRow(ctx, res);
        if (last == null || !(last.getParent() instanceof ViewGroup) || ours == null) {
            L.w("full screen menu: ZUI's row or its place not found - its second group has a gap");
            return;
        }
        for (String name : new String[]{SysUiNames.OVC_HANDLE_MENU_GROUP2_LAYOUT,
                SysUiNames.OVC_HANDLE_MENU_GROUP2_LAYOUT_SELECT_BG,
                SysUiNames.OVC_WD_HANDLE_MENU_GROUP2_EMPTY_BAR1,
                SysUiNames.OVC_WD_HANDLE_MENU_GROUP2_EMPTY_BAR2}) {
            // As ZUI shows them for a row of its own.
            View v = byName(menuRoot, name);
            if (v != null) {
                v.setVisibility(View.VISIBLE);
            }
        }
        ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) task;
        int taskId = info.taskId;
        int display = FullScreenWindows.displayOf(info);
        boolean on = FullScreenWindows.has(taskId);
        dress(ours, res, on);
        ours.setOnClickListener(v -> {
            try {
                Reflect.call(decoration, SysUiNames.CLOSE_HANDLE_MENU);
                ctx.sendBroadcast(new Intent(FullScreenWire.ACTION_TOGGLE)
                        .setPackage(FullScreenWire.ZUX_HOME)
                        .putExtra(FullScreenWire.EXTRA_TASK, taskId)
                        .putExtra(FullScreenWire.EXTRA_DISPLAY, display));
                L.i("full screen menu: " + (on ? "exit" : "full screen") + " for task " + taskId
                        + " - ZUX Home asked");
            } catch (Throwable t) {
                L.w("full screen menu: ZUX Home not asked (" + t + ")");
            }
        });
        ViewGroup group = (ViewGroup) last.getParent();
        group.addView(ours, group.indexOfChild(last));
    }

    /** ZUI's "Replace app" row, from a copy of ZUI's own layout, taken out of it to be ours. */
    private static View zuiRow(Context ctx, Resources res) {
        int layout = id(res, SysUiNames.OV_DESKTOP_MODE_WINDOW_DECOR_HANDLE_MENU_PAD_GROUP_TWO,
                "layout");
        if (layout == 0) {
            return null;
        }
        View copy = LayoutInflater.from(ctx).inflate(layout, null);
        View row = byName(copy, SysUiNames.OVC_WD_HANDLE_MENU_ITEM_REPLACE_APP);
        if (row == null || !(row.getParent() instanceof ViewGroup)) {
            return null;
        }
        ((ViewGroup) row.getParent()).removeView(row);
        row.setId(View.generateViewId());
        row.setVisibility(View.VISIBLE);
        return row;
    }

    /** Our icon and words in ZUI's row: the icon in the colour of ZUI's text beside it. */
    private static void dress(View row, Resources res, boolean on) {
        String label = on ? FullScreenWire.EXIT : FullScreenWire.ENTER;
        TextView text = firstText(row);
        if (text != null) {
            text.setText(label);
        }
        View icon = byName(row, SysUiNames.OVC_WD_HANDLE_MENU_IV_REPLACE_APP);
        Drawable glyph = Glyphs.of(on ? Glyphs.EXIT_FULL_SCREEN : Glyphs.FULL_SCREEN);
        if (icon instanceof ImageView && glyph != null) {
            if (text != null) {
                glyph.setTint(text.getCurrentTextColor());
            }
            ((ImageView) icon).setImageTintList(null);
            ((ImageView) icon).setImageDrawable(glyph);
        }
        row.setContentDescription(label);
    }

    private static TextView firstText(View view) {
        if (view instanceof TextView) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = firstText(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean onMonitor(Object task) {
        Object display = Reflect.field(task, "displayId");
        return display instanceof Integer && (Integer) display != Display.DEFAULT_DISPLAY;
    }

    private static View byName(View root, String name) {
        int id = id(root.getResources(), name, "id");
        return id == 0 ? null : root.findViewById(id);
    }

    private static int dimen(Resources res, String name) {
        int id = id(res, name, "dimen");
        return id == 0 ? 0 : res.getDimensionPixelSize(id);
    }

    /** One of SystemUI's own resources by name; 0 when it has none. Looked up once each. */
    private static int id(Resources res, String name, String type) {
        String key = type + "/" + name;
        Integer known = IDS.get(key);
        if (known == null) {
            known = res.getIdentifier(name, type, FullScreenWire.SYSTEMUI);
            IDS.put(key, known);
        }
        return known;
    }
}
