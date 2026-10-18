package com.zuxos.desktopplus.hook.drawer;

import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A tap on an app in either drawer, or in one of our folders open over it, counts wherever it
 * lands on the icon or its name.
 *
 * <p>ZUI's app icon takes a touch only inside what it counts as its content
 * ({@code BubbleTextView.onTouchEvent} asks {@code CheckLongPressHelper.isContentRect}, before
 * anything else, and lets the touch go when it says no). That area is a square of the home
 * screen's icon size ({@code iconSizePx}) at the top of the cell, the name never in it - and
 * on fine home grids that size is shrunk to fit their cells (198 to 122 px at 12x8), while the
 * drawers draw their icons at their own size. A tap near an icon's edge or on its name went
 * nowhere and the app needed tapping again (owner, 0.0.270). ZUI's answer stands unless the
 * touch is on the icon as drawn, or on its name.
 */
public final class DrawerTaps {

    private static final String HELPER = "com.android.launcher3.CheckLongPressHelper";
    private static final String FOLDER = "com.android.launcher3.folder.Folder";
    /** Corrections told in the log, the first few only. */
    private static int sSaid;

    private DrawerTaps() {
    }

    public static int install(ClassLoader loader) {
        if (!Cfg.drawerTaps()) {
            L.i("drawer taps: off - ZUI's own touch area for drawer apps");
            return 0;
        }
        Class<?> helper = Reflect.findClass(HELPER, loader);
        if (helper == null) {
            L.i("drawer taps: ZUI's touch area check not found - left as ZUI has it");
            return 0;
        }
        try {
            int hooked = XposedBridge.hookAllMethods(helper, "isContentRect",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.FALSE.equals(param.getResult())) {
                                check(param);
                            }
                        }
                    }).size();
            L.i("drawer taps: a tap on a drawer app's icon or name always counts x" + hooked);
            return hooked;
        } catch (Throwable t) {
            L.w("drawer taps: not installed (" + t + ")");
            return 0;
        }
    }

    /** {@code isContentRect(Context, View, int x, int y)} said no: taken if on what is drawn. */
    private static void check(XC_MethodHook.MethodHookParam param) {
        try {
            Object[] args = param.args;
            if (args.length != 4 || !(args[1] instanceof TextView)
                    || !(args[2] instanceof Integer) || !(args[3] instanceof Integer)) {
                return;
            }
            TextView icon = (TextView) args[1];
            int x = (Integer) args[2];
            int y = (Integer) args[3];
            if (!inDrawer(icon) || !onContent(icon, x, y)) {
                return;
            }
            param.setResult(Boolean.TRUE);
            if (sSaid < 3) {
                sSaid++;
                Drawable art = icon.getCompoundDrawables()[1];
                L.i("drawer taps: ZUI counted a tap at " + x + "," + y + " on " + icon.getText()
                        + " as off it (icon " + art.getBounds().width() + "px in a "
                        + icon.getWidth() + "x" + icon.getHeight() + " cell) - taken");
            }
        } catch (Throwable t) {
            L.d("drawer taps: a tap left to ZUI (" + t + ")");
        }
    }

    /** An app of a drawer's list, or of one of our folders open over a drawer. */
    private static boolean inDrawer(View icon) {
        for (ViewParent p = icon.getParent(); p instanceof View; p = p.getParent()) {
            String name = p.getClass().getName();
            if (name.endsWith("AllAppsRecyclerView")) {
                return true;
            }
            if (name.equals(FOLDER)) {
                return ZuiFolders.isOurs(p);
            }
        }
        return false;
    }

    /**
     * On the icon as the text view draws it (its top compound drawable, centred, at its top
     * padding) or on the lines of its name below.
     */
    private static boolean onContent(TextView icon, int x, int y) {
        Drawable art = icon.getCompoundDrawables()[1];
        if (art == null || art.getBounds().isEmpty()) {
            return false;
        }
        int size = art.getBounds().width();
        int padLeft = icon.getCompoundPaddingLeft();
        int space = icon.getWidth() - padLeft - icon.getCompoundPaddingRight();
        int left = padLeft + (space - size) / 2;
        int top = icon.getPaddingTop();
        int bottom = top + art.getBounds().height();
        if (x >= left && x <= left + size && y >= top && y <= bottom) {
            return true;
        }
        Layout lines = icon.getLayout();
        if (lines == null || y < bottom || y > icon.getHeight() - icon.getPaddingBottom()) {
            return false;
        }
        float width = 0f;
        for (int i = 0; i < lines.getLineCount(); i++) {
            width = Math.max(width, lines.getLineWidth(i));
        }
        float from = padLeft + (space - Math.min(width, space)) / 2f;
        return x >= from && x <= from + Math.min(width, space);
    }
}
