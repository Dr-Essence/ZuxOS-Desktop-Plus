package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Several apps of a drawer chosen to move together, as ZUI's home screen chooses them in its edit
 * mode - which ZUI's drawers have none of.
 *
 * <p>Select in an app's menu starts it with that app. A tap on an app chooses it or lets it go,
 * up to ZUI's own 24, and every app of the drawer carries ZUI's own marks for it
 * ({@code edit_mode_selected}, {@code edit_mode_unselected}, where ZUI draws them:
 * {@code ZuiEditModePanel.drawSelectedFlag}). ZUI's Done, in its edit mode's look, ends it. A drag
 * of a chosen app carries the others ({@link DrawerReorder#carry}): let go in the drawer they
 * follow it in their order, on a folder they go in with it, on the taskbar they are all pinned.
 * Choosing ends with the drag, at Done, or when the drawer goes.
 */
public final class DrawerSelect {

    /** ZUI's own limit ({@code ZuiEditModePanel.MAX_SELECTED_COUNT}). */
    private static final int MAX = 24;

    private static final Set<String> KEYS = new LinkedHashSet<>();
    private static java.lang.ref.WeakReference<ViewGroup> sGrid;
    private static TextView sDone;
    private static Drawable sOn;
    private static Drawable sOff;
    private static int sOffset;
    private static View sTapped;

    private DrawerSelect() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> icon = Reflect.findClass("com.android.launcher3.BubbleTextView", loader);
            if (icon == null) {
                L.w("drawer select: ZUI's icons not found - no Select in the drawers");
                return;
            }
            XposedBridge.hookAllMethods(icon, "onDraw", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sGrid != null && param.args.length == 1
                            && param.args[0] instanceof Canvas) {
                        mark((View) param.thisObject, (Canvas) param.args[0]);
                    }
                }
            });
            L.i("drawer select: Select in the drawers, with ZUI's marks");
        } catch (Throwable t) {
            L.w("drawer select: not installed (" + t + ")");
        }
    }

    /** Whether Select belongs in this drawer app's menu. */
    public static boolean selectable(View icon) {
        return Cfg.drawerSelect() && icon != null && icon.getParent() instanceof ViewGroup
                && NativeDrawerHooks.keyOfIcon(icon) != null && inDrawer(icon)
                && icon.getDisplay() != null && icon.getDisplay().getDisplayId() == 0;
    }

    /** Select from an app's menu: choosing starts in its drawer, with it chosen. */
    public static void start(View icon) {
        try {
            end();
            ViewGroup grid = (ViewGroup) icon.getParent();
            sGrid = new java.lang.ref.WeakReference<>(grid);
            KEYS.add(NativeDrawerHooks.keyOfIcon(icon));
            showDone(grid);
            redraw();
            L.i("drawer select: choosing in the drawer");
        } catch (Throwable t) {
            L.w("drawer select: not started (" + t + ")");
            end();
        }
    }

    /** A tap on a drawer app while choosing: chosen or let go; true when it was ours to take. */
    static boolean tapped(View view) {
        ViewGroup grid = sGrid != null ? sGrid.get() : null;
        if (grid == null) {
            return false;
        }
        if (!grid.isShown()) {
            // The drawer went: choosing went with it.
            end();
            return false;
        }
        if (view.getParent() != grid) {
            return false;
        }
        String key = NativeDrawerHooks.keyOfIcon(view);
        if (key == null) {
            return false;
        }
        if (!KEYS.remove(key)) {
            if (KEYS.size() >= MAX) {
                return true;
            }
            KEYS.add(key);
        }
        sTapped = view;
        view.invalidate();
        updateDone();
        return true;
    }

    /** The tap taken by choosing, so nothing else follows it; once. */
    static boolean consumed(View view) {
        if (sTapped == view) {
            sTapped = null;
            return true;
        }
        return false;
    }

    /** The apps chosen with {@code key}, when it is one of them; empty otherwise. */
    static List<String> carriedWith(String key) {
        List<String> others = new ArrayList<>();
        if (key == null || !KEYS.contains(key)) {
            return others;
        }
        for (String chosen : KEYS) {
            if (!chosen.equals(key)) {
                others.add(chosen);
            }
        }
        return others;
    }

    /** Choosing ends: the marks go, and Done with them. */
    public static void end() {
        boolean was = sGrid != null;
        KEYS.clear();
        if (sDone != null) {
            View done = sDone;
            sDone = null;
            done.animate().alpha(0f).setDuration(175L).withEndAction(() -> {
                if (done.getParent() instanceof ViewGroup) {
                    ((ViewGroup) done.getParent()).removeView(done);
                }
            }).start();
        }
        if (was) {
            redraw();
            sGrid = null;
        }
    }

    private static boolean inDrawer(View icon) {
        for (android.view.ViewParent p = icon.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().endsWith("AllAppsContainerView")) {
                return true;
            }
        }
        return false;
    }

    private static void redraw() {
        ViewGroup grid = sGrid != null ? sGrid.get() : null;
        if (grid != null) {
            for (int i = 0; i < grid.getChildCount(); i++) {
                grid.getChildAt(i).invalidate();
            }
        }
    }

    /** ZUI's mark on a drawer app while choosing, where ZUI draws its own on home icons. */
    private static void mark(View icon, Canvas canvas) {
        try {
            ViewGroup grid = sGrid != null ? sGrid.get() : null;
            if (grid == null || icon.getParent() != grid) {
                return;
            }
            String key = NativeDrawerHooks.keyOfIcon(icon);
            if (key == null || !loadMarks(icon.getContext())) {
                return;
            }
            Drawable mark = KEYS.contains(key) ? sOn : sOff;
            Rect bounds = new Rect();
            Reflect.call(icon, "getIconBounds", bounds);
            if (bounds.isEmpty()) {
                return;
            }
            int w = mark.getIntrinsicWidth();
            int h = mark.getIntrinsicHeight();
            int left = bounds.right + sOffset - w;
            int top = bounds.top > sOffset ? bounds.top - sOffset : 0;
            canvas.save();
            canvas.translate(icon.getScrollX() + left, icon.getScrollY() + top);
            mark.setBounds(0, 0, w, h);
            mark.draw(canvas);
            canvas.restore();
        } catch (Throwable t) {
            L.d("drawer select: a mark not drawn (" + t + ")");
        }
    }

    private static boolean loadMarks(Context ctx) {
        if (sOn != null && sOff != null) {
            return true;
        }
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        int on = res.getIdentifier("edit_mode_selected", "drawable", pkg);
        int off = res.getIdentifier("edit_mode_unselected", "drawable", pkg);
        int offset = res.getIdentifier("edit_mode_checkbox_offset", "dimen", pkg);
        if (on == 0 || off == 0) {
            return false;
        }
        sOn = ctx.getDrawable(on);
        sOff = ctx.getDrawable(off);
        sOffset = offset != 0 ? res.getDimensionPixelOffset(offset) : 0;
        return sOn != null && sOff != null;
    }

    /** ZUI's edit mode Done, in its look, at the drawer's top end: it ends choosing. */
    private static void showDone(ViewGroup grid) {
        try {
            View root = grid.getRootView();
            if (!(root instanceof ViewGroup)) {
                return;
            }
            Context ctx = grid.getContext();
            Resources res = ctx.getResources();
            String pkg = ctx.getPackageName();
            TextView done = new TextView(ctx);
            int back = res.getIdentifier("edit_view_drag_hint_bg", "drawable", pkg);
            int colour = res.getIdentifier("edit_mode_drag_hint_text_color", "color", pkg);
            int size = res.getIdentifier("edit_mode_complete_text_size", "dimen", pkg);
            int height = res.getIdentifier("edit_mode_drag_hint_height", "dimen", pkg);
            int pad = res.getIdentifier("edit_mode_drag_hint_padding_h", "dimen", pkg);
            if (back != 0) {
                done.setBackground(ctx.getDrawable(back));
            }
            if (colour != 0) {
                done.setTextColor(res.getColor(colour, null));
            }
            if (size != 0) {
                done.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(size));
            }
            int padding = pad != 0 ? res.getDimensionPixelSize(pad) : 24;
            done.setPadding(padding, 0, padding, 0);
            done.setGravity(android.view.Gravity.CENTER);
            done.setOnClickListener(v -> end());
            int h = height != 0 ? res.getDimensionPixelSize(height) : 96;
            sDone = done;
            updateDone();
            done.measure(View.MeasureSpec.UNSPECIFIED,
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            // Placed by translation at the top end, beside where ZUI puts its own Done.
            int[] at = new int[2];
            grid.getLocationOnScreen(at);
            int[] rootAt = new int[2];
            root.getLocationOnScreen(rootAt);
            done.setTranslationX(at[0] - rootAt[0] + grid.getWidth() - done.getMeasuredWidth()
                    - padding);
            done.setTranslationY(Math.max(0, at[1] - rootAt[1] - h - padding / 2));
            done.setAlpha(0f);
            ((ViewGroup) root).addView(done, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, h));
            done.animate().alpha(1f).setDuration(175L).start();
        } catch (Throwable t) {
            L.d("drawer select: no Done shown (" + t + ")");
        }
    }

    private static void updateDone() {
        TextView done = sDone;
        if (done == null) {
            return;
        }
        Context ctx = done.getContext();
        int id = ctx.getResources().getIdentifier("gesture_tutorial_action_button_label",
                "string", ctx.getPackageName());
        String label = id != 0 ? ctx.getString(id) : "Done";
        done.setText(KEYS.size() + "  ·  " + label);
    }
}
